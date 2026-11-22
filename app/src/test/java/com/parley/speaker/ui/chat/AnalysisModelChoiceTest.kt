package com.parley.speaker.ui.chat

import com.parley.speaker.data.analysis.TextEngineConfig
import com.parley.speaker.data.analysis.TextModel
import com.parley.speaker.domain.AnalysisSettings
import com.parley.speaker.domain.AppSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 清单到位后的模型选择：空清单不动设置，空着或不在清单里的选择改到最新一个，
 * 换供应商时旧那家的模型留空等新家清单。清单在这里故意乱序给，选择只认发布日。
 */
class AnalysisModelChoiceTest {

    private val settings = AnalysisSettings(apiKey = "sk-deep", model = "", provider = "deepseek")

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
        val stale = settings.copy(model = "gemini-flash-latest")

        assertEquals("deepseek-chat", pickedAnalysis(stale, "deepseek", catalog)?.model)
    }

    @Test
    fun `模型还在清单里就不动`() {
        assertNull(pickedAnalysis(settings.copy(model = "deepseek-v3"), "deepseek", catalog))
    }

    @Test
    fun `别家的清单不改写当前选择`() {
        assertNull(pickedAnalysis(settings, "kimi", catalog))
    }

    @Test
    fun `换供应商就把模型留空`() {
        val previous = AnalysisSettings(
            apiKey = "sk-gem",
            model = "gemini-flash-latest",
            provider = "gemini",
        )
        val switched = AppSettings(analysis = previous.copy(provider = "deepseek"))

        assertEquals("", switched.withFreshModel(previous).analysis.model)
    }

    @Test
    fun `同供应商的选择原样保留`() {
        val previous = settings.copy(model = "deepseek-chat")

        assertEquals("deepseek-chat", AppSettings(analysis = previous).withFreshModel(previous).analysis.model)
    }
}
