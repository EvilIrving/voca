package com.parley.speaker.data.settings

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.parley.speaker.BuildConfig
import com.parley.speaker.data.SeedContent
import com.parley.speaker.data.analysis.TextProviderCatalog
import com.parley.speaker.domain.AnalysisCredentials
import com.parley.speaker.domain.AnalysisSettings
import com.parley.speaker.domain.AppSettings
import com.parley.speaker.domain.AssessorProvider
import com.parley.speaker.domain.AssessorSettings
import com.parley.speaker.domain.AuthKind
import com.parley.speaker.domain.LiveSettings
import com.parley.speaker.domain.LiveTuning
import com.parley.speaker.domain.LiveVendor
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 凭证与模型选择的本地存储，密钥走 AES 加密，全部留在设备上。
 */
class SettingsStore(context: Context) {

    private val prefs: SharedPreferences = EncryptedSharedPreferences.create(
        context,
        FILE_NAME,
        MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build(),
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
    )

    // 出厂提示词要在 _settings 之前就位：read() 一上来就拿它当 fallback，字段初始化是按声明顺序跑的。
    /** 出厂的系统提示词，设置页里的「恢复默认」回到这一段。 */
    val defaultPrompt: String = SeedContent.DefaultPrompt

    private val _settings = MutableStateFlow(read())
    val settings: StateFlow<AppSettings> = _settings.asStateFlow()

    // 出厂这份只在开发构建里带密钥：带得进来就顺手选中那一家，装到机器上就能直接开口。
    private fun defaults(): AppSettings {
        val live = LiveSettings(
            geminiKey = BuildConfig.DEV_GEMINI_API_KEY,
            tuning = mapOf(
                LiveVendor.Gemini to LiveTuning(
                    BuildConfig.DEV_GEMINI_LIVE_MODEL,
                    BuildConfig.DEV_GEMINI_VOICE,
                ),
            ),
        )
        val analysis = AnalysisSettings(
            saved = mapOf(
                TextProviderCatalog.GeminiId to AnalysisCredentials(
                    apiKey = BuildConfig.DEV_GEMINI_API_KEY,
                    model = BuildConfig.DEV_GEMINI_TEXT_MODEL,
                ),
            ),
        )
        return AppSettings(
            live = live.selectIfReady(LiveVendor.Gemini),
            analysis = analysis.selectIfReady(TextProviderCatalog.GeminiId),
            assessor = AssessorSettings()
                .withXfyun(
                    BuildConfig.DEV_XFYUN_APP_ID,
                    BuildConfig.DEV_XFYUN_API_KEY,
                    BuildConfig.DEV_XFYUN_API_SECRET,
                ),
            prompt = defaultPrompt,
        )
    }

    private fun read(): AppSettings {
        if (!prefs.contains(KEY_SEEDED)) {
            val initial = defaults()
            write(initial)
            prefs.edit().putBoolean(KEY_SEEDED, true).apply()
            return initial
        }
        return AppSettings(
            live = readLive(prefs),
            analysis = readAnalysis(prefs),
            assessor = readAssessor(prefs),
            prompt = readPrompt(prefs, defaultPrompt),
        )
    }

    private fun write(settings: AppSettings) {
        prefs.edit()
            .putLive(settings.live)
            .putAnalysis(settings.analysis)
            .putAssessor(settings.assessor)
            .putString(KEY_PROMPT, settings.prompt)
            .apply()
    }

    // 每次改动都由调用方按设置自己那几条规矩落定（存一家就切过去、清一家就找同组另一个），
    // 这里不再按凭据齐备重排谁生效，否则显式选中的那家会被推导规则顶掉。
    fun update(transform: (AppSettings) -> AppSettings) {
        val next = transform(_settings.value)
        write(next)
        _settings.value = next
    }

    private companion object {
        const val FILE_NAME = "parley_secure"
        const val KEY_SEEDED = "seeded"
    }
}

private const val KEY_PROMPT = "prompt"

private const val KEY_ANALYSIS_SAVED = "analysis_saved"
private const val KEY_ANALYSIS_PROVIDER = "analysis_provider"
private const val KEY_ANALYSIS_THINKING = "analysis_thinking"

/** 旧版只有一格，这六个键是那版的遗留，读到就挪进对应供应商名下，挪完删掉。 */
private const val LEGACY_ANALYSIS_KEY = "analysis_api_key"
private const val LEGACY_ANALYSIS_MODEL = "analysis_model"
private const val LEGACY_ANALYSIS_ENDPOINT = "analysis_endpoint"
private const val LEGACY_ANALYSIS_AUTH = "analysis_auth_kind"
private const val LEGACY_ANALYSIS_AUTH_NAME = "analysis_auth_name"
private const val LEGACY_ANALYSIS_AUTH_PREFIX = "analysis_auth_prefix"

private fun slotKey(id: String, field: String): String = "analysis.$id.$field"

/**
 * 文本分析按供应商各存一格：id 集合记在 analysis_saved 里，其余按 analysis.<id>.<字段> 摊开。
 * 配第二家只写它自己那几条，第一家原封不动。读到旧版单格那套就顺手迁过来并删掉遗留键，
 * 升级的人不必重填密钥，模型留空由清单到位时自动选。
 */
internal fun readAnalysis(prefs: SharedPreferences): AnalysisSettings {
    val provider = prefs.getString(KEY_ANALYSIS_PROVIDER, null) ?: TextProviderCatalog.GeminiId
    val thinking = prefs.getBoolean(KEY_ANALYSIS_THINKING, false)
    if (prefs.contains(KEY_ANALYSIS_SAVED)) {
        val saved = prefs.getStringSet(KEY_ANALYSIS_SAVED, null).orEmpty().associateWith { id ->
            AnalysisCredentials(
                apiKey = prefs.getString(slotKey(id, "key"), "").orEmpty(),
                model = prefs.getString(slotKey(id, "model"), "").orEmpty(),
                endpoint = prefs.getString(slotKey(id, "endpoint"), "").orEmpty(),
                authKind = prefs.getString(slotKey(id, "auth"), null)?.let(::authKindOf),
                authName = prefs.getString(slotKey(id, "auth_name"), "").orEmpty(),
                authPrefix = prefs.getString(slotKey(id, "auth_prefix"), "").orEmpty(),
            )
        }
        return AnalysisSettings(provider, saved.filterValues { it != AnalysisCredentials() }, thinking)
    }

    val legacy = AnalysisCredentials(
        apiKey = prefs.getString(LEGACY_ANALYSIS_KEY, "").orEmpty(),
        model = prefs.getString(LEGACY_ANALYSIS_MODEL, "").orEmpty(),
        endpoint = prefs.getString(LEGACY_ANALYSIS_ENDPOINT, "").orEmpty(),
        authKind = prefs.getString(LEGACY_ANALYSIS_AUTH, null)?.let(::authKindOf),
        authName = prefs.getString(LEGACY_ANALYSIS_AUTH_NAME, "").orEmpty(),
        authPrefix = prefs.getString(LEGACY_ANALYSIS_AUTH_PREFIX, "").orEmpty(),
    )
    val migrated = if (legacy == AnalysisCredentials()) emptyMap() else mapOf(provider to legacy)
    val settings = AnalysisSettings(provider, migrated, thinking)
    prefs.edit()
        .putAnalysis(settings)
        .remove(LEGACY_ANALYSIS_KEY)
        .remove(LEGACY_ANALYSIS_MODEL)
        .remove(LEGACY_ANALYSIS_ENDPOINT)
        .remove(LEGACY_ANALYSIS_AUTH)
        .remove(LEGACY_ANALYSIS_AUTH_NAME)
        .remove(LEGACY_ANALYSIS_AUTH_PREFIX)
        .apply()
    return settings
}

private fun authKindOf(name: String): AuthKind? = AuthKind.entries.firstOrNull { it.name == name }

/** 只写非空那几家：清空的供应商落到默认格子，写与读都当它不存在。 */
internal fun SharedPreferences.Editor.putAnalysis(analysis: AnalysisSettings): SharedPreferences.Editor {
    val saved = analysis.saved.filterValues { it != AnalysisCredentials() }
    putStringSet(KEY_ANALYSIS_SAVED, saved.keys)
    saved.forEach { (id, credentials) ->
        putString(slotKey(id, "key"), credentials.apiKey)
        putString(slotKey(id, "model"), credentials.model)
        putString(slotKey(id, "endpoint"), credentials.endpoint)
        putString(slotKey(id, "auth"), credentials.authKind?.name)
        putString(slotKey(id, "auth_name"), credentials.authName)
        putString(slotKey(id, "auth_prefix"), credentials.authPrefix)
    }
    return putString(KEY_ANALYSIS_PROVIDER, analysis.provider)
        .putBoolean(KEY_ANALYSIS_THINKING, analysis.thinking)
}

private const val KEY_ASSESSOR_PROVIDER = "assessor_provider"
private const val KEY_AZURE_KEY = "azure_key"
private const val KEY_AZURE_REGION = "azure_region"
private const val KEY_TENCENT_APP_ID = "tencent_app_id"
private const val KEY_TENCENT_SECRET_ID = "tencent_secret_id"
private const val KEY_TENCENT_SECRET_KEY = "tencent_secret_key"
private const val KEY_XFYUN_APP_ID = "xfyun_app_id"
private const val KEY_XFYUN_API_KEY = "xfyun_api_key"
private const val KEY_XFYUN_API_SECRET = "xfyun_api_secret"

/** 凭据齐备算启用，显式存下来的 provider 在参数齐备时优先。 */
internal fun readAssessor(prefs: SharedPreferences): AssessorSettings {
    val stored = prefs.getString(KEY_ASSESSOR_PROVIDER, null)
        ?.let { name -> AssessorProvider.entries.firstOrNull { it.name == name } }
    val settings = AssessorSettings(
        provider = stored ?: AssessorProvider.None,
        azureKey = prefs.getString(KEY_AZURE_KEY, "").orEmpty(),
        azureRegion = prefs.getString(KEY_AZURE_REGION, "").orEmpty(),
        tencentAppId = prefs.getString(KEY_TENCENT_APP_ID, "").orEmpty(),
        tencentSecretId = prefs.getString(KEY_TENCENT_SECRET_ID, "").orEmpty(),
        tencentSecretKey = prefs.getString(KEY_TENCENT_SECRET_KEY, "").orEmpty(),
        xfyunAppId = prefs.getString(KEY_XFYUN_APP_ID, "").orEmpty(),
        xfyunApiKey = prefs.getString(KEY_XFYUN_API_KEY, "").orEmpty(),
        xfyunApiSecret = prefs.getString(KEY_XFYUN_API_SECRET, "").orEmpty(),
    )
    return settings.copy(provider = settings.resolvedProvider)
}

internal fun SharedPreferences.Editor.putAssessor(settings: AssessorSettings): SharedPreferences.Editor =
    putString(KEY_ASSESSOR_PROVIDER, settings.resolvedProvider.name)
        .putString(KEY_AZURE_KEY, settings.azureKey)
        .putString(KEY_AZURE_REGION, settings.azureRegion)
        .putString(KEY_TENCENT_APP_ID, settings.tencentAppId)
        .putString(KEY_TENCENT_SECRET_ID, settings.tencentSecretId)
        .putString(KEY_TENCENT_SECRET_KEY, settings.tencentSecretKey)
        .putString(KEY_XFYUN_APP_ID, settings.xfyunAppId)
        .putString(KEY_XFYUN_API_KEY, settings.xfyunApiKey)
        .putString(KEY_XFYUN_API_SECRET, settings.xfyunApiSecret)

/**
 * 系统提示词的读法。存着的那份还是旧出厂默认时换成新的，用户自己改过的原样留着；
 * 这道迁移只认那一段旧文字，不会把别人写过的东西盖掉。
 */
internal fun readPrompt(prefs: SharedPreferences, fallback: String): String {
    val stored = prefs.getString(KEY_PROMPT, null)
    return when (stored) {
        null -> fallback
        SeedContent.DefaultPrompt -> fallback
        else -> stored
    }
}

private const val KEY_LIVE_PROVIDER = "live_provider"
private const val KEY_LIVE_GEMINI_KEY = "live_api_key"
private const val KEY_LIVE_OPENAI_KEY = "live_openai_key"
private const val KEY_LIVE_DOUBAO_API_KEY = "live_doubao_api_key"
private const val KEY_LIVE_QWEN_API_KEY = "live_qwen_api_key"

/** 模型与音色按家各存一格：live.<家>.<字段>，换走再换回来还是原先那一对。 */
private fun tuningKey(vendor: LiveVendor, field: String): String =
    "live.${vendor.name.lowercase()}.$field"

internal fun readLive(prefs: SharedPreferences): LiveSettings = LiveSettings(
    provider = prefs.getString(KEY_LIVE_PROVIDER, null)
        ?.let { name -> LiveVendor.entries.firstOrNull { it.name == name } },
    geminiKey = prefs.getString(KEY_LIVE_GEMINI_KEY, "").orEmpty(),
    openAiKey = prefs.getString(KEY_LIVE_OPENAI_KEY, "").orEmpty(),
    doubaoApiKey = prefs.getString(KEY_LIVE_DOUBAO_API_KEY, "").orEmpty(),
    qwenApiKey = prefs.getString(KEY_LIVE_QWEN_API_KEY, "").orEmpty(),
    tuning = LiveVendor.entries.associateWith { vendor ->
        LiveTuning(
            model = prefs.getString(tuningKey(vendor, "model"), "").orEmpty(),
            voice = prefs.getString(tuningKey(vendor, "voice"), "").orEmpty(),
        )
    },
)

internal fun SharedPreferences.Editor.putLive(live: LiveSettings): SharedPreferences.Editor {
    putString(KEY_LIVE_PROVIDER, live.provider?.name)
    putString(KEY_LIVE_GEMINI_KEY, live.geminiKey)
    putString(KEY_LIVE_OPENAI_KEY, live.openAiKey)
    putString(KEY_LIVE_DOUBAO_API_KEY, live.doubaoApiKey)
    putString(KEY_LIVE_QWEN_API_KEY, live.qwenApiKey)
    LiveVendor.entries.forEach { vendor ->
        val tuning = live.tuningOf(vendor)
        putString(tuningKey(vendor, "model"), tuning.model)
        putString(tuningKey(vendor, "voice"), tuning.voice)
    }
    return this
}
