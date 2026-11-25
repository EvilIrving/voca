package com.voca.speaker.data.settings

import android.content.Context
import android.content.SharedPreferences
import com.voca.speaker.domain.LiveDefaults
import com.voca.speaker.domain.LiveSettings
import com.voca.speaker.domain.LiveTuning
import com.voca.speaker.domain.LiveVendor
import com.voca.speaker.domain.VoiceCatalog
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/**
 * 实时对话设置的存取：三家凭据各一格，模型与音色也按家各存一格，
 * 切到豆包再切回来仍是原先那只音色；目录外的音色与空模型都拉回这一家的默认。
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
    fun `空存储谁都不生效，三家都用目录默认的模型与音色`() {
        val loaded = readLive(prefs)

        assertNull("没选过就不该有生效那家", loaded.provider)
        assertFalse(loaded.ready)
        LiveVendor.entries.forEach { vendor ->
            assertEquals(LiveDefaults.model(vendor), loaded.tuningOf(vendor).model)
            assertEquals(VoiceCatalog.defaultId(vendor), loaded.tuningOf(vendor).voice)
            assertFalse(loaded.ready(vendor))
        }
    }

    @Test
    fun `凭据、生效那家与各家的模型音色存回后原样读出`() {
        val live = LiveSettings(
            provider = LiveVendor.Doubao,
            geminiKey = "AIza-key",
            openAiKey = "sk-openai",
            doubaoApiKey = "doubao-key",
            tuning = mapOf(
                LiveVendor.Gemini to LiveTuning("gemini-3.8-live", "Puck"),
                LiveVendor.Doubao to LiveTuning("1.2.6.1", "zh_female_xiaohe_jupiter_bigtts"),
            ),
        )

        prefs.edit().putLive(live).commit()

        val loaded = readLive(prefs)
        assertEquals(LiveVendor.Doubao, loaded.provider)
        assertEquals("AIza-key", loaded.geminiKey)
        assertEquals("sk-openai", loaded.openAiKey)
        assertEquals("doubao-key", loaded.doubaoApiKey)
        assertEquals("Puck", loaded.tuningOf(LiveVendor.Gemini).voice)
        assertEquals("gemini-3.8-live", loaded.tuningOf(LiveVendor.Gemini).model)
        assertEquals("zh_female_xiaohe_jupiter_bigtts", loaded.tuningOf(LiveVendor.Doubao).voice)
        assertEquals("1.2.6.1", loaded.tuningOf(LiveVendor.Doubao).model)
        assertEquals("没挑过的那家落在目录默认", LiveDefaults.voice(LiveVendor.OpenAi), loaded.tuningOf(LiveVendor.OpenAi).voice)
    }

    @Test
    fun `切到豆包再切回来仍是原先那只音色`() {
        val gemini = LiveSettings(geminiKey = "AIza")
            .withTuning(LiveVendor.Gemini, voice = "Puck")
            .select(LiveVendor.Gemini)
        prefs.edit().putLive(gemini).commit()

        val doubao = readLive(prefs)
            .withKey(LiveVendor.Doubao, "key")
            .withTuning(LiveVendor.Doubao, voice = "zh_male_yunzhou_jupiter_bigtts")
            .selectIfReady(LiveVendor.Doubao)
        prefs.edit().putLive(doubao).commit()

        val reloaded = readLive(prefs)
        assertEquals(LiveVendor.Doubao, reloaded.provider)
        assertEquals("zh_male_yunzhou_jupiter_bigtts", reloaded.tuningOf(LiveVendor.Doubao).voice)

        val back = reloaded.select(LiveVendor.Gemini)
        assertEquals(LiveVendor.Gemini, back.provider)
        assertEquals("Puck", back.voice)
        assertEquals("AIza", back.geminiKey)
        assertEquals("key", back.doubaoApiKey)
    }

    @Test
    fun `目录外的音色与空模型都拉回这一家的默认`() {
        val live = LiveSettings(tuning = mapOf(LiveVendor.OpenAi to LiveTuning("", "Aoede")))

        assertEquals("gpt-realtime-2.1", live.tuningOf(LiveVendor.OpenAi).model)
        assertEquals(VoiceCatalog.defaultId(LiveVendor.OpenAi), live.tuningOf(LiveVendor.OpenAi).voice)
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
    fun `齐备判定只看生效那家`() {
        val doubao = LiveSettings(provider = LiveVendor.Doubao, doubaoApiKey = "key")

        assertTrue(doubao.ready)
        assertFalse(doubao.copy(provider = LiveVendor.Gemini).ready)
        assertTrue(doubao.copy(geminiKey = "AIza", provider = LiveVendor.Gemini).ready)
        assertFalse("没选过谁都不生效", doubao.copy(provider = null).ready)
    }

    @Test
    fun `换走再换回来，别家的音色也没被顶掉`() {
        val picked = LiveSettings(provider = LiveVendor.OpenAi, openAiKey = "sk", geminiKey = "AIza")
            .withTuning(LiveVendor.OpenAi, voice = "verse")
            .withTuning(LiveVendor.Gemini, voice = "Puck")

        assertEquals("verse", picked.select(LiveVendor.OpenAi).voice)
        assertEquals("Puck", picked.select(LiveVendor.Gemini).voice)
        assertEquals(LiveDefaults.model(LiveVendor.Gemini), picked.select(LiveVendor.Gemini).model)
    }
}
