package com.voca.speaker.data.settings

import android.content.Context
import android.content.SharedPreferences
import com.voca.speaker.domain.AnalysisCredentials
import com.voca.speaker.domain.AnalysisSettings
import com.voca.speaker.domain.AuthKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/**
 * 文本分析的存取：旧版单格密钥一次迁到那家名下，之后按供应商各存一格，
 * 配第二家不动第一家，清空密钥才把那家抹掉。
 */
@RunWith(RobolectricTestRunner::class)
class AnalysisSettingsStoreTest {

    private val prefs: SharedPreferences =
        RuntimeEnvironment.getApplication().getSharedPreferences("analysis", Context.MODE_PRIVATE)

    @Before
    fun clear() {
        prefs.edit().clear().commit()
    }

    @Test
    fun `旧版单格密钥迁到当时那家名下，遗留键删净`() {
        prefs.edit()
            .putString("analysis_provider", "deepseek")
            .putString("analysis_api_key", "sk-deep")
            .putString("analysis_model", "deepseek-chat")
            .putBoolean("analysis_thinking", true)
            .commit()

        val loaded = readAnalysis(prefs)

        assertEquals("deepseek", loaded.provider)
        assertEquals("sk-deep", loaded.of("deepseek").apiKey)
        assertEquals("deepseek-chat", loaded.of("deepseek").model)
        assertTrue(loaded.thinking)
        assertFalse("迁完就不该留着旧键", prefs.contains("analysis_api_key"))
        assertFalse(prefs.contains("analysis_model"))
    }

    @Test
    fun `配第二家不覆盖第一家，来回切都还在`() {
        val first = AnalysisSettings(
            provider = "deepseek",
            saved = mapOf("deepseek" to AnalysisCredentials(apiKey = "sk-deep", model = "deepseek-chat")),
        )
        prefs.edit().putAnalysis(first).commit()

        val second = readAnalysis(prefs)
            .amend("gemini") { it.copy(apiKey = "AIza-gem", model = "gemini-flash-latest") }
            .select("gemini")
        prefs.edit().putAnalysis(second).commit()

        val loaded = readAnalysis(prefs)

        assertEquals("gemini", loaded.provider)
        assertEquals("AIza-gem", loaded.apiKey)
        assertEquals("sk-deep", loaded.of("deepseek").apiKey)
        assertEquals("deepseek-chat", loaded.of("deepseek").model)
        assertEquals("deepseek-chat", loaded.select("deepseek").model)
    }

    @Test
    fun `自定义端点的端点与鉴权跟着那家一起存`() {
        val custom = AnalysisSettings(
            provider = "custom",
            saved = mapOf(
                "custom" to AnalysisCredentials(
                    apiKey = "sk-custom",
                    model = "qwen-max",
                    endpoint = "https://api.example.com/v1",
                    authKind = AuthKind.Header,
                    authName = "x-api-key",
                ),
            ),
        )
        prefs.edit().putAnalysis(custom).commit()

        val loaded = readAnalysis(prefs).of("custom")

        assertEquals("https://api.example.com/v1", loaded.endpoint)
        assertEquals(AuthKind.Header, loaded.authKind)
        assertEquals("x-api-key", loaded.authName)
    }

    @Test
    fun `清空一家的密钥就把那家抹掉，别家留着`() {
        val both = AnalysisSettings(
            provider = "gemini",
            saved = mapOf(
                "gemini" to AnalysisCredentials(apiKey = "AIza-gem", model = "gemini-flash-latest"),
                "kimi" to AnalysisCredentials(apiKey = "sk-kimi", model = "kimi-k2"),
            ),
        )
        prefs.edit().putAnalysis(both).commit()

        val cleared = readAnalysis(prefs).amend("kimi") { AnalysisCredentials() }
        prefs.edit().putAnalysis(cleared).commit()

        val loaded = readAnalysis(prefs)

        assertFalse(loaded.of("kimi").ready)
        assertTrue(loaded.of("gemini").ready)
        assertEquals("gemini-flash-latest", loaded.of("gemini").model)
    }
}
