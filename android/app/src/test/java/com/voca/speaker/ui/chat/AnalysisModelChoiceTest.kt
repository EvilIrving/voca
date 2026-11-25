package com.voca.speaker.ui.chat

import com.voca.speaker.data.analysis.TextEngineConfig
import com.voca.speaker.data.analysis.TextModel
import com.voca.speaker.domain.AnalysisCredentials
import com.voca.speaker.domain.AnalysisSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 清单到位后的模型选择：空清单不动设置，空着或不在清单里的选择改到最新一个，
 * 改动只落在那一家的格子里，别家挑好的模型不动。清单在这里故意乱序给，选择只认发布日。
 */
class AnalysisModelChoiceTest {

    private val settings = AnalysisSettings(
        provider = "deepseek",
        saved = mapOf("deepseek" to AnalysisCredentials(apiKey = "sk-deep")),
    )

    private val catalog = listOf(
        TextModel(id = "deepseek-v3", releaseDate = "2024-12-26"),
        TextModel(id = "deepseek-chat", releaseDate = "2025-03-24"),
        TextModel(id = "deepseek-coder", releaseDate = "2024-06-14"),
    )

    @Test
    fun `清单为空时设置不动`() {
        assertNull(pickedAnalysis(settings, "deepseek", emptyList()))
    }

    @Test
    fun `清单为空就发不出分析请求`() {
        val adopted = pickedAnalysis(settings, "deepseek", emptyList()) ?: settings

        assertFalse("空模型名不算配置齐备", TextEngineConfig.of(adopted).ready)
    }

    @Test
    fun `模型空着就落到最新一个`() {
        assertEquals("deepseek-chat", pickedAnalysis(settings, "deepseek", catalog)?.model)
    }

    @Test
    fun `模型不在清单里也落到最新一个`() {
        val stale = settings.amend("deepseek") { it.copy(model = "gemini-flash-latest") }

        assertEquals("deepseek-chat", pickedAnalysis(stale, "deepseek", catalog)?.model)
    }

    @Test
    fun `模型还在清单里就不动`() {
        val kept = settings.amend("deepseek") { it.copy(model = "deepseek-v3") }

        assertNull(pickedAnalysis(kept, "deepseek", catalog))
    }

    @Test
    fun `别家的清单只写别家那一格，当前那家不动`() {
        val adopted = pickedAnalysis(settings, "kimi", catalog)

        assertEquals("deepseek-chat", adopted?.of("kimi")?.model)
        assertEquals("", adopted?.of("deepseek")?.model)
        assertEquals("deepseek", adopted?.provider)
    }

    @Test
    fun `换供应商不碰模型，各家记各家的`() {
        val both = settings
            .amend("gemini") { it.copy(apiKey = "sk-gem", model = "gemini-flash-latest") }
            .amend("deepseek") { it.copy(model = "deepseek-chat") }

        assertEquals("deepseek-chat", both.of("deepseek").model)
        assertEquals("gemini-flash-latest", both.of("gemini").model)
        assertEquals("gemini-flash-latest", both.select("gemini").model)
        assertEquals("deepseek-chat", both.select("gemini").select("deepseek").model)
    }
}
