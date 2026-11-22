package com.parley.speaker.ui.chat

import com.parley.speaker.data.analysis.TextModel
import com.parley.speaker.data.analysis.TextProviderCatalog
import com.parley.speaker.domain.AnalysisCredentials
import com.parley.speaker.domain.AnalysisSettings
import com.parley.speaker.domain.AppSettings
import com.parley.speaker.domain.AssessorSettings
import com.parley.speaker.domain.AuthKind
import com.parley.speaker.domain.LinkStatus
import com.parley.speaker.domain.LiveVendor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 设置页的读数：实时对话三行按各自凭据齐备显示，评测两行取工厂产物，
 * 文本分析每家的清单是注入进来的那一份，不是静态目录。
 */
class ProviderLinesTest {

    @Test
    fun `实时对话三行按各自凭据齐备显示`() {
        val lines = realtime(AppSettings())

        assertEquals(listOf("gemini", "openai", "doubao"), lines.map { it.key })
        assertEquals(listOf("Gemini Live", "OpenAI Realtime", "豆包实时语音"), lines.map { it.name })
        assertTrue("没填凭据都要能点开去填", lines.all { it.editable })
        assertTrue(lines.all { it.status == LinkStatus.Unconfigured })
    }

    @Test
    fun `填了哪家就哪家已配置`() {
        val gemini = realtime(AppSettings(live = liveSettings(provider = LiveVendor.Gemini, geminiKey = "AIza")))
        assertEquals(LinkStatus.Configured, gemini.first { it.key == "gemini" }.status)
        assertEquals(LinkStatus.Unconfigured, gemini.first { it.key == "openai" }.status)

        val openai = realtime(AppSettings(live = liveSettings(provider = LiveVendor.OpenAi, openAiKey = "sk")))
        assertEquals(LinkStatus.Configured, openai.first { it.key == "openai" }.status)

        val doubaoHalf = realtime(
            AppSettings(live = liveSettings(provider = LiveVendor.Doubao, doubaoAppId = "app", doubaoAppKey = "key")),
        )
        assertEquals(LinkStatus.Unconfigured, doubaoHalf.first { it.key == "doubao" }.status)

        val doubaoFull = realtime(
            AppSettings(
                live = liveSettings(
                    provider = LiveVendor.Doubao,
                    doubaoAppId = "app",
                    doubaoAppKey = "key",
                    doubaoAccessKey = "secret",
                ),
            ),
        )
        assertEquals(LinkStatus.Configured, doubaoFull.first { it.key == "doubao" }.status)
    }

    @Test
    fun `探针结果压过参数齐备`() {
        val settings = AppSettings(live = liveSettings(provider = LiveVendor.Gemini, geminiKey = "AIza"))

        val line = providerLines(settings, mapOf("gemini" to LinkStatus.Failed)).first { it.key == "gemini" }

        assertEquals(LinkStatus.Failed, line.status)
    }

    @Test
    fun `评测行状态取自工厂产物`() {
        val azure = assessor(AppSettings(assessor = AssessorSettings(azureKey = "key", azureRegion = "eastasia")))
        assertEquals(listOf("azure", "xfyun"), azure.map { it.key })
        assertEquals(LinkStatus.Configured, azure[0].status)
        assertEquals(LinkStatus.Unconfigured, azure[1].status)
        assertTrue("缺参数那家点开就能去填", azure.all { it.editable })

        val xfyun = assessor(
            AppSettings(
                assessor = AssessorSettings(xfyunAppId = "app", xfyunApiKey = "key", xfyunApiSecret = "secret"),
            ),
        )
        assertEquals(LinkStatus.Unconfigured, xfyun[0].status)
        assertEquals(LinkStatus.Configured, xfyun[1].status)
    }

    @Test
    fun `评测两家都没配齐时两行都未配置，不出现未接入`() {
        val lines = providerLines(AppSettings(), emptyMap())

        assertEquals(LinkStatus.Unconfigured, assessor(AppSettings())[0].status)
        assertEquals(LinkStatus.Unconfigured, assessor(AppSettings())[1].status)
        assertFalse("未接入不再出现在任何一行", lines.any { it.status == LinkStatus.NotSupported })
    }

    @Test
    fun `文本分析七家一行不落，只有当前那家有密钥才算已配置`() {
        val settings = AppSettings(
            analysis = AnalysisSettings(
                provider = "deepseek",
                saved = mapOf("deepseek" to AnalysisCredentials(apiKey = "sk-deep", model = "deepseek-chat")),
            ),
        )

        val lines = providerLines(settings, emptyMap()).filter { it.group == "analysis" }

        assertEquals(7, lines.size)
        assertEquals(
            listOf("deepseek", "analysis", "openai", "anthropic", "kimi", "glm", "custom"),
            lines.map { it.key },
        )
        assertEquals(LinkStatus.Configured, lines.first { it.key == "deepseek" }.status)
        assertEquals(LinkStatus.Unconfigured, lines.first { it.key == "analysis" }.status)
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

        val lines = providerLines(settings, emptyMap()).filter { it.group == "analysis" }

        assertEquals(7, lines.size)
        val custom = lines.first { it.key == TextProviderCatalog.CustomId }
        assertEquals(LinkStatus.Configured, custom.status)
        assertEquals("deepseek-chat", custom.detail)
        assertTrue("其它几家没密钥就不算已配置", lines.filter { it.key != TextProviderCatalog.CustomId }.all { it.status == LinkStatus.Unconfigured })
    }

    private fun realtime(settings: AppSettings) = providerLines(settings, emptyMap()).filter { it.group == "realtime" }

    private fun assessor(settings: AppSettings) = providerLines(settings, emptyMap()).filter { it.group == "assessor" }

    private fun liveSettings(
        provider: LiveVendor,
        geminiKey: String = "",
        openAiKey: String = "",
        doubaoAppId: String = "",
        doubaoAppKey: String = "",
        doubaoAccessKey: String = "",
    ) = com.parley.speaker.domain.LiveSettings(
        provider = provider,
        geminiKey = geminiKey,
        openAiKey = openAiKey,
        doubaoAppId = doubaoAppId,
        doubaoAppKey = doubaoAppKey,
        doubaoAccessKey = doubaoAccessKey,
        model = com.parley.speaker.domain.LiveDefaults.model(provider),
        voice = com.parley.speaker.domain.LiveDefaults.voice(provider),
    )
}
