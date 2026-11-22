package com.parley.speaker.ui.settings

import com.parley.speaker.domain.LiveDefaults
import com.parley.speaker.domain.LiveSettings
import com.parley.speaker.domain.LiveVendor
import com.parley.speaker.domain.VoiceCatalog
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 音色下拉：候选只来自这一家的静态目录，每行音色名配一行风格词，
 * 选中项就是当前这一家的音色，目录里查不到的名字回默认。
 */
class VoiceMenuTest {

    @Test
    fun `候选就是这一家目录里的全部音色，每行都带风格词`() {
        val menu = voiceMenu(LiveVendor.Gemini, "Aoede")

        assertEquals(VoiceCatalog.voices(LiveVendor.Gemini).map { it.id }, menu.options.map { it.id })
        assertTrue("风格词不能缺行", menu.options.all { !it.note.isNullOrBlank() })
        assertEquals(30, menu.options.size)
        assertEquals("轻快", menu.options.first { it.id == "Aoede" }.note)
        assertEquals("Aoede", menu.selected)
    }

    @Test
    fun `三家的候选各是各的，不混着列`() {
        val openai = voiceMenu(LiveVendor.OpenAi, LiveDefaults.voice(LiveVendor.OpenAi)).options.map { it.id }
        val doubao = voiceMenu(LiveVendor.Doubao, LiveDefaults.voice(LiveVendor.Doubao)).options.map { it.id }

        assertEquals(listOf("alloy", "ash", "ballad", "coral", "echo", "sage", "shimmer", "verse", "marin", "cedar"), openai)
        assertTrue("Gemini 的名字不出现在豆包清单里", doubao.none { it == "Aoede" })
        assertTrue("豆包按族分段，段名不空", voiceMenu(LiveVendor.Doubao, "").options.all { it.group.isNotBlank() })
        assertEquals(
            listOf("O 系列", "SC 系列", "SC 2.0 系列"),
            voiceMenu(LiveVendor.Doubao, "").options.map { it.group }.distinct(),
        )
    }

    @Test
    fun `选中项取当前那家的音色，别家的旧值不套过来`() {
        val gemini = LiveSettings(provider = LiveVendor.Gemini, voice = "Puck")

        assertEquals("Puck", currentVoice(gemini, LiveVendor.Gemini))
        assertEquals(
            "换了供应商就按那家的默认显示",
            LiveDefaults.voice(LiveVendor.OpenAi),
            currentVoice(gemini, LiveVendor.OpenAi),
        )
    }

    @Test
    fun `目录里查不到的音色回默认，下拉里勾不上`() {
        val stale = LiveSettings(provider = LiveVendor.Gemini, voice = "en-us-arlo")

        assertEquals(LiveDefaults.voice(LiveVendor.Gemini), currentVoice(stale, LiveVendor.Gemini))
        assertNull("目录外的名字没有风格词", voiceStyle(LiveVendor.Gemini, "en-us-arlo"))
        assertTrue(voiceMenu(LiveVendor.Gemini, "en-us-arlo").options.none { it.id == "en-us-arlo" })
    }

    @Test
    fun `风格词跟着音色走`() {
        assertEquals("活泼", voiceStyle(LiveVendor.Gemini, "Puck"))
        assertEquals("中文女声 · 多情感，日常对话", voiceStyle(LiveVendor.Doubao, "zh_female_vv_jupiter_bigtts"))
        assertNull(voiceStyle(LiveVendor.OpenAi, "Aoede"))
    }
}
