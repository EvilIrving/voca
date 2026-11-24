package com.parley.speaker.ui.chat

import com.parley.speaker.data.analysis.TextModel
import com.parley.speaker.data.analysis.TextProviderCatalog
import com.parley.speaker.domain.AnalysisCredentials
import com.parley.speaker.domain.AnalysisSettings
import com.parley.speaker.domain.AppSettings
import com.parley.speaker.domain.AssessorProvider
import com.parley.speaker.domain.AssessorSettings
import com.parley.speaker.domain.AuthKind
import com.parley.speaker.domain.LiveSettings
import com.parley.speaker.domain.LiveVendor
import com.parley.speaker.domain.ProviderGroup
import com.parley.speaker.domain.ProviderLine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 设置页每行的那份读数：已配置只看这一家自己的凭据，同组几家互不影响；
 * 单选点只填在生效那家，第二行写这一家的模型号或端点。
 */
class ProviderLinesTest {

    @Test
    fun `实时对话四行，第二行是这一家的模型号`() {
        val lines = group(AppSettings(), ProviderGroup.Realtime)

        assertEquals(listOf("gemini", "openai", "doubao", "qwen"), lines.map { it.key })
        assertEquals(
            listOf("Gemini Live", "OpenAI Realtime", "豆包实时语音", "千问实时语音"),
            lines.map { it.name },
        )
        assertEquals(
            listOf("gemini-3.8-live", "gpt-realtime-2.1", "1.2.6.1", "qwen-audio-3.1-realtime-flash"),
            lines.map { it.detail },
        )
        assertTrue(lines.none { it.configured })
        assertTrue("谁都没选中时单选点不填", lines.none { it.active })
    }

    @Test
    fun `四家都配齐就四行都已配置，单选点只落在一家`() {
        val live = LiveSettings(geminiKey = "AIza", openAiKey = "sk", doubaoApiKey = "key", qwenApiKey = "sk-qwen")
            .select(LiveVendor.Doubao)

        val lines = group(AppSettings(live = live), ProviderGroup.Realtime)

        assertTrue(lines.all { it.configured })
        assertEquals(listOf("doubao"), lines.filter { it.active }.map { it.key })
    }

    @Test
    fun `填了哪家就哪家已配置`() {
        val gemini = group(AppSettings(live = LiveSettings(geminiKey = "AIza")), ProviderGroup.Realtime)
        assertTrue(gemini.first { it.key == "gemini" }.configured)
        assertFalse(gemini.first { it.key == "openai" }.configured)

        val openai = group(AppSettings(live = LiveSettings(openAiKey = "sk")), ProviderGroup.Realtime)
        assertTrue(openai.first { it.key == "openai" }.configured)
        assertFalse(openai.first { it.key == "gemini" }.configured)
    }

    @Test
    fun `评测三行各看自己的凭据，生效只认显式选中的那家`() {
        val bare = group(AppSettings(), ProviderGroup.Assessor)
        assertEquals(listOf("azure", "tencent", "xfyun"), bare.map { it.key })
        assertTrue(bare.none { it.configured })
        assertTrue(bare.none { it.active })

        val azure = AssessorSettings().withAzure("key", "eastasia")
        val azureRows = group(AppSettings(assessor = azure), ProviderGroup.Assessor)
        assertTrue(azureRows.first { it.key == "azure" }.configured)
        assertFalse(azureRows.first { it.key == "xfyun" }.configured)
        assertEquals("eastasia", azureRows.first { it.key == "azure" }.detail)
        assertEquals(listOf("azure"), azureRows.filter { it.active }.map { it.key })

        val both = azure.withXfyun("app", "key", "secret")
        val bothRows = group(AppSettings(assessor = both), ProviderGroup.Assessor)
        assertTrue("两家齐备就算已配置", bothRows.filter { it.key != "tencent" }.all { it.configured })
        assertFalse("没填的那家不算", bothRows.first { it.key == "tencent" }.configured)
        assertEquals("填完讯飞就切到讯飞", listOf("xfyun"), bothRows.filter { it.active }.map { it.key })
        assertEquals(AssessorProvider.Xfyun, both.resolvedProvider)

        val tencent = both.withTencent("125922", "sid", "skey")
        val allRows = group(AppSettings(assessor = tencent), ProviderGroup.Assessor)
        assertEquals("AppID、SecretID、SecretKey", allRows.first { it.key == "tencent" }.detail)
        assertEquals(listOf("tencent"), allRows.filter { it.active }.map { it.key })
    }

    @Test
    fun `文本分析七家一行不落，第二行是挑好的模型或端点`() {
        val settings = AppSettings(
            analysis = AnalysisSettings(
                provider = "deepseek",
                saved = mapOf("deepseek" to AnalysisCredentials(apiKey = "sk-deep", model = "deepseek-chat")),
            ),
        )

        val lines = group(settings, ProviderGroup.Analysis)

        assertEquals(7, lines.size)
        assertEquals(
            listOf("deepseek", "gemini", "openai", "anthropic", "kimi", "glm", "custom"),
            lines.map { it.key },
        )
        assertEquals("deepseek-chat", lines.first { it.key == "deepseek" }.detail)
        assertTrue(lines.first { it.key == "deepseek" }.configured)
        assertTrue(lines.first { it.key == "deepseek" }.active)
        assertFalse(lines.first { it.key == "kimi" }.configured)
        assertEquals("api.moonshot.cn", lines.first { it.key == "kimi" }.detail)
    }

    @Test
    fun `文本分析没挑模型时第二行写端点域名`() {
        val settings = AppSettings(
            analysis = AnalysisSettings(
                saved = mapOf("deepseek" to AnalysisCredentials(apiKey = "sk-deep")),
            ),
        )

        val deepseek = group(settings, ProviderGroup.Analysis).first { it.key == "deepseek" }

        assertTrue(deepseek.configured)
        assertFalse("没选中的那家不占单选点", deepseek.active)
        assertEquals("api.deepseek.com", deepseek.detail)
    }

    @Test
    fun `自定义端点那一行也用目录 id 作 key`() {
        val settings = AppSettings(
            analysis = AnalysisSettings(
                provider = TextProviderCatalog.CustomId,
                saved = mapOf(
                    TextProviderCatalog.CustomId to AnalysisCredentials(
                        apiKey = "sk-custom",
                        model = "deepseek-chat",
                        endpoint = "https://api.example.com/v1",
                        authKind = AuthKind.Header,
                        authName = "x-api-key",
                    ),
                ),
            ),
        )

        val lines = group(settings, ProviderGroup.Analysis)
        val custom = lines.first { it.key == TextProviderCatalog.CustomId }

        assertEquals("deepseek-chat", custom.detail)
        assertTrue(custom.configured)
        assertTrue(custom.active)
        assertTrue(
            "其它几家没密钥就不算已配置",
            lines.filter { it.key != TextProviderCatalog.CustomId }.none { it.configured },
        )
    }

    @Test
    fun `清单注入只换一家，别家的清单原样留着`() {
        val deepseek = TextProviderCatalog.deepseek
        val kimi = TextProviderCatalog.kimi
        val injected = injectModels(TextProviderCatalog.all, deepseek.id, listOf(TextModel("deepseek-chat")))

        assertEquals(listOf("deepseek-chat"), injected.first { it.id == deepseek.id }.models.map { it.id })
        assertTrue(injected.first { it.id == kimi.id }.models.isEmpty())

        val both = injectModels(injected, kimi.id, listOf(TextModel("kimi-k2")))

        assertEquals(listOf("deepseek-chat"), both.first { it.id == deepseek.id }.models.map { it.id })
        assertEquals(listOf("kimi-k2"), both.first { it.id == kimi.id }.models.map { it.id })
    }

    @Test
    fun `没有密钥就把清单注成空`() {
        val loaded = injectModels(
            TextProviderCatalog.all,
            TextProviderCatalog.deepseek.id,
            listOf(TextModel("deepseek-chat")),
        )

        val cleared = injectModels(loaded, TextProviderCatalog.deepseek.id, emptyList())

        assertTrue(cleared.first { it.id == TextProviderCatalog.deepseek.id }.models.isEmpty())
    }

    private fun group(settings: AppSettings, group: ProviderGroup): List<ProviderLine> =
        providerLines(settings).filter { it.group == group }
}
