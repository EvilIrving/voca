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
import com.parley.speaker.domain.LiveSettings
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
        live = LiveSettings(
            apiKey = BuildConfig.DEV_GEMINI_API_KEY,
            model = BuildConfig.DEV_GEMINI_LIVE_MODEL,
            voice = BuildConfig.DEV_GEMINI_VOICE,
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
            live = LiveSettings(
                apiKey = prefs.getString(KEY_LIVE_KEY, "") ?: "",
                model = prefs.getString(KEY_LIVE_MODEL, "") ?: BuildConfig.DEV_GEMINI_LIVE_MODEL,
                voice = prefs.getString(KEY_LIVE_VOICE, "") ?: BuildConfig.DEV_GEMINI_VOICE,
            ),
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
            assessor = AssessorSettings(
                provider = prefs.getString(KEY_ASSESSOR_PROVIDER, null)
                    ?.let { name -> AssessorProvider.entries.firstOrNull { it.name == name } }
                    ?: AssessorProvider.None,
                azureKey = prefs.getString(KEY_AZURE_KEY, "") ?: "",
                azureRegion = prefs.getString(KEY_AZURE_REGION, "") ?: "",
            ),
            prompt = prefs.getString(KEY_PROMPT, null) ?: SeedContent.DefaultPrompt,
        )
    }

    private fun write(settings: AppSettings) {
        prefs.edit()
            .putString(KEY_LIVE_KEY, settings.live.apiKey)
            .putString(KEY_LIVE_MODEL, settings.live.model)
            .putString(KEY_LIVE_VOICE, settings.live.voice)
            .putString(KEY_ANALYSIS_KEY, settings.analysis.apiKey)
            .putString(KEY_ANALYSIS_MODEL, settings.analysis.model)
            .putString(KEY_ANALYSIS_PROVIDER, settings.analysis.provider)
            .putString(KEY_ANALYSIS_ENDPOINT, settings.analysis.endpoint)
            .putString(KEY_ANALYSIS_AUTH, settings.analysis.authKind?.name)
            .putString(KEY_ANALYSIS_AUTH_NAME, settings.analysis.authName)
            .putString(KEY_ANALYSIS_AUTH_PREFIX, settings.analysis.authPrefix)
            .putBoolean(KEY_ANALYSIS_THINKING, settings.analysis.thinking)
            .putString(KEY_ASSESSOR_PROVIDER, settings.assessor.provider.name)
            .putString(KEY_AZURE_KEY, settings.assessor.azureKey)
            .putString(KEY_AZURE_REGION, settings.assessor.azureRegion)
            .putString(KEY_PROMPT, settings.prompt)
            .apply()
    }

    fun update(transform: (AppSettings) -> AppSettings) {
        val next = transform(_settings.value)
        write(next)
        _settings.value = next
    }

    private companion object {
        const val FILE_NAME = "parley_secure"
        const val KEY_SEEDED = "seeded"
        const val KEY_LIVE_KEY = "live_api_key"
        const val KEY_LIVE_MODEL = "live_model"
        const val KEY_LIVE_VOICE = "live_voice"
        const val KEY_ANALYSIS_KEY = "analysis_api_key"
        const val KEY_ANALYSIS_MODEL = "analysis_model"
        const val KEY_ANALYSIS_PROVIDER = "analysis_provider"
        const val KEY_ANALYSIS_ENDPOINT = "analysis_endpoint"
        const val KEY_ANALYSIS_AUTH = "analysis_auth_kind"
        const val KEY_ANALYSIS_AUTH_NAME = "analysis_auth_name"
        const val KEY_ANALYSIS_AUTH_PREFIX = "analysis_auth_prefix"
        const val KEY_ANALYSIS_THINKING = "analysis_thinking"
        const val KEY_ASSESSOR_PROVIDER = "assessor_provider"
        const val KEY_AZURE_KEY = "azure_key"
        const val KEY_AZURE_REGION = "azure_region"
        const val KEY_PROMPT = "prompt"
    }
}
