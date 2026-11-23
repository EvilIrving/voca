package com.parley.speaker.data.settings

import android.content.Context
import android.content.SharedPreferences
import com.parley.speaker.domain.LiveSettings
import com.parley.speaker.domain.LiveVendor
import com.parley.speaker.domain.VoiceCatalog
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/**
 * 实时对话设置的存取：三家凭据齐备判定、旧单密钥的兼容读法、
 * 换供应商后模型与音色落到那家的默认值、音色越出目录就拉回默认。
 */
@RunWith(RobolectricTestRunner::class)
class LiveSettingsStoreTest {

    private val prefs: SharedPreferences =
        RuntimeEnvironment.getApplication().getSharedPreferences("live", Context.MODE_PRIVATE)

    @Before
    fun clear() {
        prefs.edit().clear().commit()
    }

    @Test
    fun `旧单密钥键读出来当 Gemini 的密钥`() {
        prefs.edit()
            .putString("live_api_key", "AIza-legacy")
            .putString("live_model", "gemini-3.5-transcribe-live")
            .putString("live_voice", "Puck")
            .commit()

        val loaded = readLive(prefs)

        assertEquals(LiveVendor.Gemini, loaded.provider)
        assertEquals("AIza-legacy", loaded.geminiKey)
        assertEquals("gemini-3.5-transcribe-live", loaded.model)
        assertEquals("Puck", loaded.voice)
        assertTrue(loaded.ready)
    }

    @Test
    fun `空存储给出 Gemini 的默认模型与音色`() {
        val loaded = readLive(prefs)

        assertEquals(LiveVendor.Gemini, loaded.provider)
        assertEquals("", loaded.geminiKey)
        assertEquals(LiveCatalogGeminiModel, loaded.model)
        assertEquals(VoiceCatalog.defaultId(LiveVendor.Gemini), loaded.voice)
        assertFalse(loaded.ready)
    }

    @Test
    fun `存在库里的音色不属于这家就拉回默认`() {
        prefs.edit()
            .putString("live_provider", LiveVendor.OpenAi.name)
            .putString("live_openai_key", "sk-openai")
            .putString("live_voice", "Aoede")
            .commit()

        val loaded = readLive(prefs)

        assertEquals(LiveVendor.OpenAi, loaded.provider)
        assertEquals(VoiceCatalog.defaultId(LiveVendor.OpenAi), loaded.voice)
        assertTrue(loaded.ready)
    }

    @Test
    fun `四家的密钥与模型音色存回后原样读出`() {
        val live = LiveSettings(
            provider = LiveVendor.OpenAi,
            geminiKey = "AIza-key",
            openAiKey = "sk-openai",
            doubaoApiKey = "doubao-key",
            model = "gpt-realtime-2.1",
            voice = "verse",
        )

        prefs.edit().putLive(live).commit()

        assertEquals(live, readLive(prefs))
    }

    @Test
    fun `三家各认一把密钥`() {
        assertTrue(LiveSettings(geminiKey = "AIza").ready(LiveVendor.Gemini))
        assertFalse(LiveSettings(geminiKey = "AIza").ready(LiveVendor.OpenAi))
        assertTrue(LiveSettings(openAiKey = "sk").ready(LiveVendor.OpenAi))
        assertFalse(LiveSettings(openAiKey = "sk").ready(LiveVendor.Gemini))
        assertTrue(LiveSettings(doubaoApiKey = "key").ready(LiveVendor.Doubao))
        assertFalse(LiveSettings(doubaoApiKey = "key").ready(LiveVendor.OpenAi))
    }

    @Test
    fun `齐备判定只看当前供应商`() {
        val doubao = LiveSettings(provider = LiveVendor.Doubao, doubaoApiKey = "key")

        assertTrue(doubao.ready)
        assertFalse(doubao.copy(provider = LiveVendor.Gemini).ready)
        assertTrue(doubao.copy(geminiKey = "AIza", provider = LiveVendor.Gemini).ready)
    }

    @Test
    fun `切换供应商后模型与音色落到那家的默认`() {
        val gemini = LiveSettings(geminiKey = "AIza", model = "gemini-3.8-live", voice = "Puck")

        val openai = normalizedLive(gemini, gemini.copy(provider = LiveVendor.OpenAi, openAiKey = "sk"))
        assertEquals("gpt-realtime-2.1", openai.model)
        assertEquals("alloy", openai.voice)
        assertEquals("AIza", openai.geminiKey)

        val doubao = normalizedLive(
            openai,
            openai.copy(provider = LiveVendor.Doubao, doubaoApiKey = "key"),
        )
        assertEquals("1.2.6.1", doubao.model)
        assertEquals(VoiceCatalog.defaultId(LiveVendor.Doubao), doubao.voice)

        val back = normalizedLive(doubao, doubao.copy(provider = LiveVendor.Gemini))
        assertEquals("gemini-3.8-live", back.model)
        assertEquals("Aoede", back.voice)
        assertEquals("sk", back.openAiKey)
    }

    @Test
    fun `同一家内换音色不被覆盖`() {
        val before = LiveSettings(provider = LiveVendor.OpenAi, openAiKey = "sk", model = "gpt-realtime-2.1", voice = "alloy")
        val after = normalizedLive(before, before.copy(voice = "verse"))

        assertEquals("verse", after.voice)
        assertEquals("gpt-realtime-2.1", after.model)
    }

    @Test
    fun `模型空着就取该家的默认`() {
        val settled = settled(LiveSettings(provider = LiveVendor.OpenAi, openAiKey = "sk", model = "", voice = "alloy"))

        assertEquals("gpt-realtime-2.1", settled.model)
    }

    private companion object {
        const val LiveCatalogGeminiModel = "gemini-3.8-live"
    }
}
