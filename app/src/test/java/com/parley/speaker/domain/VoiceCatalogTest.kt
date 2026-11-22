package com.parley.speaker.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 音色目录是静态表，界面上每一行都来自这里，所以清单条数、默认项与分族都得钉住：
 * 默认项多一个或少一个都会让配置中心的下拉出现两条勾。
 */
class VoiceCatalogTest {

    @Test
    fun `三家都有清单且恰好一个默认项`() {
        LiveVendor.entries.forEach { vendor ->
            val voices = VoiceCatalog.voices(vendor)
            assertTrue("${vendor.label} 的清单是空的", voices.isNotEmpty())
            assertEquals(
                "${vendor.label} 的默认项应当只有一个，实际 ${voices.filter { it.default }.map { it.id }}",
                1,
                voices.count { it.default },
            )
            assertTrue("${vendor.label} 的 id 不该重复", voices.map { it.id }.toSet().size == voices.size)
            assertTrue("${vendor.label} 的每一项都要有风格词", voices.all { it.style.isNotBlank() })
            assertEquals(VoiceCatalog.defaultId(vendor), voices.first { it.default }.id)
        }
    }

    @Test
    fun `Gemini 是那三十个预置音色，默认 Aoede 轻快`() {
        val voices = VoiceCatalog.voices(LiveVendor.Gemini)

        assertEquals(30, voices.size)
        assertEquals("Aoede", VoiceCatalog.defaultId(LiveVendor.Gemini))
        mapOf(
            "Aoede" to "轻快",
            "Puck" to "活泼",
            "Charon" to "稳重",
            "Sulafat" to "温暖",
        ).forEach { (id, style) ->
            assertEquals("$id 的风格词", style, voices.first { it.id == id }.style)
        }
        assertFalse("Live 不认语音库专用的音色，目录里不能有", VoiceCatalog.isKnown(LiveVendor.Gemini, "en-us-arlo"))
    }

    @Test
    fun `OpenAI 是 alloy 到 cedar 那十个`() {
        val ids = VoiceCatalog.voices(LiveVendor.OpenAi).map { it.id }

        assertEquals(
            listOf("alloy", "ash", "ballad", "coral", "echo", "sage", "shimmer", "verse", "marin", "cedar"),
            ids,
        )
    }

    @Test
    fun `豆包按 O、SC、SC 2 点 0 分族，族内名字前缀与族对齐`() {
        val groups = VoiceCatalog.groups(LiveVendor.Doubao).toMap()

        assertEquals(listOf("O 系列", "SC 系列", "SC 2.0 系列"), groups.keys.toList())
        assertTrue(
            "精品音色只在 O 系列",
            groups.getValue("O 系列").map { it.id }.containsAll(
                listOf("zh_female_vv_jupiter_bigtts", "zh_female_xiaohe_jupiter_bigtts"),
            ),
        )
        assertTrue(
            "ICL_ 克隆音色只在 SC",
            groups.getValue("SC 系列").filter { it.id.startsWith("ICL_") }.isNotEmpty(),
        )
        assertTrue(
            "saturn_ 只在 SC 2.0",
            groups.getValue("SC 2.0 系列").all { it.id.startsWith("saturn_") },
        )
        assertEquals("zh_female_vv_jupiter_bigtts", VoiceCatalog.defaultId(LiveVendor.Doubao))
    }
}
