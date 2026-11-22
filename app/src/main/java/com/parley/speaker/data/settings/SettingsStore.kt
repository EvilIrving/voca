package com.parley.speaker.data.settings

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.parley.speaker.BuildConfig
import com.parley.speaker.data.SeedContent
import com.parley.speaker.domain.AnalysisSettings
import com.parley.speaker.domain.AppSettings
import com.parley.speaker.domain.AssessorProvider
import com.parley.speaker.domain.AssessorSettings
import com.parley.speaker.domain.AuthKind
import com.parley.speaker.domain.LiveDefaults
import com.parley.speaker.domain.LiveSettings
import com.parley.speaker.domain.LiveVendor
import com.parley.speaker.domain.VoiceCatalog
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

    private val _settings = MutableStateFlow(read())
    val settings: StateFlow<AppSettings> = _settings.asStateFlow()

    private fun defaults(): AppSettings = AppSettings(
        live = settled(
            LiveSettings(
                geminiKey = BuildConfig.DEV_GEMINI_API_KEY,
                model = BuildConfig.DEV_GEMINI_LIVE_MODEL,
                voice = BuildConfig.DEV_GEMINI_VOICE,
            ),
        ),
        analysis = AnalysisSettings(
            apiKey = BuildConfig.DEV_GEMINI_API_KEY,
            model = BuildConfig.DEV_GEMINI_TEXT_MODEL,
        ),
        assessor = AssessorSettings(provider = AssessorProvider.None),
        prompt = SeedContent.DefaultPrompt,
    )

    private fun read(): AppSettings {
        if (!prefs.contains(KEY_SEEDED)) {
            val initial = defaults()
            write(initial)
            prefs.edit().putBoolean(KEY_SEEDED, true).apply()
            return initial
        }
        return AppSettings(
            live = readLive(prefs),
            analysis = AnalysisSettings(
                apiKey = prefs.getString(KEY_ANALYSIS_KEY, "") ?: "",
                model = prefs.getString(KEY_ANALYSIS_MODEL, "") ?: BuildConfig.DEV_GEMINI_TEXT_MODEL,
                provider = prefs.getString(KEY_ANALYSIS_PROVIDER, null) ?: "gemini",
                endpoint = prefs.getString(KEY_ANALYSIS_ENDPOINT, "") ?: "",
                authKind = prefs.getString(KEY_ANALYSIS_AUTH, null)
                    ?.let { name -> AuthKind.entries.firstOrNull { it.name == name } },
                authName = prefs.getString(KEY_ANALYSIS_AUTH_NAME, "") ?: "",
                authPrefix = prefs.getString(KEY_ANALYSIS_AUTH_PREFIX, "") ?: "",
                thinking = prefs.getBoolean(KEY_ANALYSIS_THINKING, false),
            ),
            assessor = readAssessor(prefs),
            prompt = prefs.getString(KEY_PROMPT, null) ?: SeedContent.DefaultPrompt,
        )
    }

    private fun write(settings: AppSettings) {
        prefs.edit()
            .putLive(settings.live)
            .putString(KEY_ANALYSIS_KEY, settings.analysis.apiKey)
            .putString(KEY_ANALYSIS_MODEL, settings.analysis.model)
            .putString(KEY_ANALYSIS_PROVIDER, settings.analysis.provider)
            .putString(KEY_ANALYSIS_ENDPOINT, settings.analysis.endpoint)
            .putString(KEY_ANALYSIS_AUTH, settings.analysis.authKind?.name)
            .putString(KEY_ANALYSIS_AUTH_NAME, settings.analysis.authName)
            .putString(KEY_ANALYSIS_AUTH_PREFIX, settings.analysis.authPrefix)
            .putBoolean(KEY_ANALYSIS_THINKING, settings.analysis.thinking)
            .putAssessor(settings.assessor)
            .putString(KEY_PROMPT, settings.prompt)
            .apply()
    }

    // 存储器里的 provider 是推导后的值，内存里那份也按同一规则归位，免得重启前后看到两家不同的状态。
    // 实时对话那组同规矩：换了供应商就落到那家的默认模型与音色，音色不在目录里拉回默认。
    fun update(transform: (AppSettings) -> AppSettings) {
        val before = _settings.value
        val next = transform(before)
            .let { it.copy(assessor = it.assessor.copy(provider = it.assessor.resolvedProvider)) }
            .let { it.copy(live = normalizedLive(before.live, it.live)) }
        write(next)
        _settings.value = next
    }

    private companion object {
        const val FILE_NAME = "parley_secure"
        const val KEY_SEEDED = "seeded"
        const val KEY_ANALYSIS_KEY = "analysis_api_key"
        const val KEY_ANALYSIS_MODEL = "analysis_model"
        const val KEY_ANALYSIS_PROVIDER = "analysis_provider"
        const val KEY_ANALYSIS_ENDPOINT = "analysis_endpoint"
        const val KEY_ANALYSIS_AUTH = "analysis_auth_kind"
        const val KEY_ANALYSIS_AUTH_NAME = "analysis_auth_name"
        const val KEY_ANALYSIS_AUTH_PREFIX = "analysis_auth_prefix"
        const val KEY_ANALYSIS_THINKING = "analysis_thinking"
        const val KEY_PROMPT = "prompt"
    }
}

private const val KEY_ASSESSOR_PROVIDER = "assessor_provider"
private const val KEY_AZURE_KEY = "azure_key"
private const val KEY_AZURE_REGION = "azure_region"
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
        .putString(KEY_XFYUN_APP_ID, settings.xfyunAppId)
        .putString(KEY_XFYUN_API_KEY, settings.xfyunApiKey)
        .putString(KEY_XFYUN_API_SECRET, settings.xfyunApiSecret)

private const val KEY_LIVE_PROVIDER = "live_provider"

/** 旧版本只有这一把密钥，读出来当 Gemini 的密钥，已经填过的人不必重填。 */
private const val KEY_LIVE_KEY = "live_api_key"
private const val KEY_LIVE_OPENAI_KEY = "live_openai_key"
private const val KEY_LIVE_DOUBAO_APP_ID = "live_doubao_app_id"
private const val KEY_LIVE_DOUBAO_APP_KEY = "live_doubao_app_key"
private const val KEY_LIVE_DOUBAO_ACCESS_KEY = "live_doubao_access_key"
private const val KEY_LIVE_MODEL = "live_model"
private const val KEY_LIVE_VOICE = "live_voice"

internal fun readLive(prefs: SharedPreferences): LiveSettings {
    val provider = prefs.getString(KEY_LIVE_PROVIDER, null)
        ?.let { name -> LiveVendor.entries.firstOrNull { it.name == name } }
        ?: LiveVendor.Gemini
    return settled(
        LiveSettings(
            provider = provider,
            geminiKey = prefs.getString(KEY_LIVE_KEY, "").orEmpty(),
            openAiKey = prefs.getString(KEY_LIVE_OPENAI_KEY, "").orEmpty(),
            doubaoAppId = prefs.getString(KEY_LIVE_DOUBAO_APP_ID, "").orEmpty(),
            doubaoAppKey = prefs.getString(KEY_LIVE_DOUBAO_APP_KEY, "").orEmpty(),
            doubaoAccessKey = prefs.getString(KEY_LIVE_DOUBAO_ACCESS_KEY, "").orEmpty(),
            model = prefs.getString(KEY_LIVE_MODEL, "").orEmpty(),
            voice = prefs.getString(KEY_LIVE_VOICE, "").orEmpty(),
        ),
    )
}

internal fun SharedPreferences.Editor.putLive(live: LiveSettings): SharedPreferences.Editor =
    putString(KEY_LIVE_PROVIDER, live.provider.name)
        .putString(KEY_LIVE_KEY, live.geminiKey)
        .putString(KEY_LIVE_OPENAI_KEY, live.openAiKey)
        .putString(KEY_LIVE_DOUBAO_APP_ID, live.doubaoAppId)
        .putString(KEY_LIVE_DOUBAO_APP_KEY, live.doubaoAppKey)
        .putString(KEY_LIVE_DOUBAO_ACCESS_KEY, live.doubaoAccessKey)
        .putString(KEY_LIVE_MODEL, live.model)
        .putString(KEY_LIVE_VOICE, live.voice)

/** 音色必须落在这一家的目录里，不在就拉回默认；模型空着取该家的默认值。 */
internal fun settled(live: LiveSettings): LiveSettings = live.copy(
    model = live.model.ifBlank { LiveDefaults.model(live.provider) },
    voice = live.voice.takeIf { VoiceCatalog.isKnown(live.provider, it) }
        ?: LiveDefaults.voice(live.provider),
)

/** 换到另一家时模型与音色整体落到那家的默认值，再按目录归位。 */
internal fun normalizedLive(before: LiveSettings, after: LiveSettings): LiveSettings =
    settled(if (before.provider == after.provider) after else after.copy(model = "", voice = ""))
