package com.voca.speaker.ui.settings

import com.voca.speaker.domain.LiveDefaults
import com.voca.speaker.domain.LiveSettings
import com.voca.speaker.domain.LiveVendor
import com.voca.speaker.domain.VoiceCatalog
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 音色下拉：候选只来自这一家的静态目录，每行的行名是界面上的名字，副行是风格词；
 * 豆包那串 voice_type 不上屏，写的是音色表上的中文名。选中项就是当前这一家的音色。
 */
class VoiceMenuTest {

    @Test
    fun `候选就是这一家目录里的全部音色，行名与 id 一一对得上`() {
        val menu = voiceMenu(LiveVendor.Gemini, "Aoede")

        assertEquals(VoiceCatalog.voices(LiveVendor.Gemini).map { it.id }, menu.options.map { it.id })
        assertEquals(VoiceCatalog.voices(LiveVendor.Gemini).map { it.name }, menu.options.map { it.label })
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
    fun `豆包下拉写中文名，voice_type 不上屏`() {
        val menu = voiceMenu(LiveVendor.Doubao, "zh_female_xiaohe_jupiter_bigtts")

        assertEquals(
            listOf("vivi", "小何", "云舟", "小天"),
            menu.options.take(4).map { it.label },
        )
        assertTrue(
            "行名里不该出现 voice_type 的碎片",
            menu.options.none { it.label.contains("bigtts") || it.label.contains("_") },
        )
        assertEquals("中文女声 · 温柔，长对话陪伴", menu.options.first { it.label == "小何" }.note)
    }

    @Test
    fun `选中项取当前那家的音色，别家的旧值不套过来`() {
        val gemini = LiveSettings(provider = LiveVendor.Gemini).withTuning(LiveVendor.Gemini, voice = "Puck")

        assertEquals("Puck", currentVoice(gemini, LiveVendor.Gemini))
        assertEquals(
            "换了供应商就按那家的默认显示",
            LiveDefaults.voice(LiveVendor.OpenAi),
            currentVoice(gemini, LiveVendor.OpenAi),
        )
    }

    @Test
    fun `目录里查不到的音色回默认，下拉里勾不上`() {
        val stale = LiveSettings(tuning = mapOf(LiveVendor.Gemini to com.voca.speaker.domain.LiveTuning("", "en-us-arlo")))

        assertEquals(LiveDefaults.voice(LiveVendor.Gemini), currentVoice(stale, LiveVendor.Gemini))
        assertEquals("目录外的旧值原样写回", "en-us-arlo", voiceName(LiveVendor.Gemini, "en-us-arlo"))
        assertTrue(voiceMenu(LiveVendor.Gemini, "en-us-arlo").options.none { it.id == "en-us-arlo" })
    }

    @Test
    fun `界面读名字，协议读 id`() {
        assertEquals("小何", voiceName(LiveVendor.Doubao, "zh_female_xiaohe_jupiter_bigtts"))
        assertEquals("Puck", voiceName(LiveVendor.Gemini, "Puck"))
        assertTrue(VoiceCatalog.isKnown(LiveVendor.Doubao, "zh_female_xiaohe_jupiter_bigtts"))
    }
}
