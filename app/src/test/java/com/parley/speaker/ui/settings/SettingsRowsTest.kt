package com.parley.speaker.ui.settings

import com.parley.speaker.data.analysis.TextModel
import com.parley.speaker.data.analysis.TextProviderCatalog
import com.parley.speaker.domain.AnalysisCredentials
import com.parley.speaker.domain.AnalysisSettings
import com.parley.speaker.domain.AppSettings
import com.parley.speaker.domain.AssessorProvider
import com.parley.speaker.domain.AssessorSettings
import com.parley.speaker.domain.LinkStatus
import com.parley.speaker.domain.LiveVendor
import com.parley.speaker.ui.chat.providerLines
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 配置中心这屏读的是现成的数据：实时对话三行按各家自己的凭据齐备判定，
 * 文本分析的模型清单只从传进来的供应商清单里取，评测两行归到参数齐备的那家。
 */
class SettingsRowsTest {

    @Test
    fun `实时对话三行各按自己的凭据齐备显示`() {
        val empty = realtime(providerLines(AppSettings(), emptyMap()))
        assertEquals(listOf("gemini", "openai", "doubao"), empty.map { it.key })
        assertTrue("三家都要能点开去填", empty.all { it.editable })
        assertTrue(empty.all { it.status == LinkStatus.Unconfigured })

        val gemini = realtime(providerLines(AppSettings(live = live(geminiKey = "AIza")), emptyMap()))
        assertEquals(LinkStatus.Configured, gemini.first { it.key == "gemini" }.status)
        assertEquals(LinkStatus.Unconfigured, gemini.first { it.key == "openai" }.status)

        val doubaoHalf = realtime(
            providerLines(AppSettings(live = live(provider = LiveVendor.Doubao, doubaoAppId = "app")), emptyMap()),
        )
        assertEquals("三件套缺一件就不算齐备", LinkStatus.Unconfigured, doubaoHalf.first { it.key == "doubao" }.status)

        val doubaoFull = realtime(
            providerLines(
                AppSettings(
                    live = live(
                        provider = LiveVendor.Doubao,
                        doubaoAppId = "app",
                        doubaoAppKey = "key",
                        doubaoAccessKey = "secret",
                    ),
                ),
                emptyMap(),
            ),
        )
        assertEquals(LinkStatus.Configured, doubaoFull.first { it.key == "doubao" }.status)
    }

    @Test
    fun `保存一家的凭据就切到那家，别家的凭据原样留着`() {
        val withGemini = AppSettings().liveSaved(LiveVendor.Gemini, mapOf("geminiKey" to " AIza "), voice = "Puck")
        assertEquals(LiveVendor.Gemini, withGemini.live.provider)
        assertEquals("AIza", withGemini.live.geminiKey)
        assertEquals("Puck", withGemini.live.voice)

        val withDoubao = withGemini.liveSaved(
            LiveVendor.Doubao,
            mapOf("doubaoAppId" to "app", "doubaoAppKey" to "key", "doubaoAccessKey" to "secret"),
            voice = "zh_female_vv_jupiter_bigtts",
        )

        assertEquals(LiveVendor.Doubao, withDoubao.live.provider)
        assertTrue(withDoubao.live.ready(LiveVendor.Doubao))
        assertEquals("AIza", withDoubao.live.geminiKey)
        assertEquals("zh_female_vv_jupiter_bigtts", withDoubao.live.voice)
    }

    @Test
    fun `填了密钥的那家可展开，没填的不可展开`() {
        val bare = AnalysisSettings(provider = "deepseek")

        assertTrue(modelsToLoad(bare, "deepseek").isEmpty())
        assertTrue(modelsToLoad(bare, "kimi").isEmpty())

        val withKey = bare.amend("deepseek") { it.copy(apiKey = "sk-deep") }
        assertEquals(listOf("deepseek"), modelsToLoad(withKey, "deepseek"))
        assertTrue(modelsToLoad(withKey, "kimi").isEmpty())
    }

    @Test
    fun `配了第二家，第一家的密钥与模型都还在`() {
        val deepseek = TextProviderCatalog.deepseek.copy(models = listOf(TextModel("deepseek-chat")))
        val gemini = TextProviderCatalog.of(TextProviderCatalog.GeminiId)!!.copy(
            models = listOf(TextModel("gemini-flash-latest")),
        )
        val first = AppSettings().withAnalysisCredentials(
            provider = deepseek,
            apiKey = "sk-deep",
            model = "deepseek-chat",
            endpoint = "",
            authKind = null,
            authName = "",
            authPrefix = "",
        )

        val both = first.withAnalysisCredentials(
            provider = gemini,
            apiKey = "AIza-gem",
            model = "gemini-flash-latest",
            endpoint = "",
            authKind = null,
            authName = "",
            authPrefix = "",
        )

        assertEquals("gemini", both.analysis.provider)
        assertEquals("sk-deep", both.analysis.of("deepseek").apiKey)
        assertEquals("deepseek-chat", both.analysis.of("deepseek").model)
        assertEquals("AIza-gem", both.analysis.of(TextProviderCatalog.GeminiId).apiKey)

        assertEquals(LinkStatus.Configured, analysisRow(both.analysis, deepseek, analysisLines(both.analysis)).status)
        assertEquals("deepseek-chat", analysisRow(both.analysis, deepseek, analysisLines(both.analysis)).detail)
    }

    @Test
    fun `文本分析行读清单里的模型，模型不在清单里就写未选择`() {
        val provider = TextProviderCatalog.deepseek.copy(
            models = listOf(TextModel("deepseek-chat"), TextModel("deepseek-reasoner")),
        )
        val analysis = AnalysisSettings(
            provider = "deepseek",
            saved = mapOf("deepseek" to AnalysisCredentials(apiKey = "sk-deep", model = "deepseek-reasoner")),
        )

        val picked = analysisRow(analysis, provider, analysisLines(analysis))
        assertEquals("deepseek-reasoner", picked.detail)
        assertEquals(LinkStatus.Configured, picked.status)

        val gone = analysisRow(stale(analysis, "deepseek-v2"), provider, analysisLines(analysis))
        assertEquals(Unpicked, gone.detail)

        val blank = analysisRow(stale(analysis, ""), provider, analysisLines(analysis))
        assertEquals(Unpicked, blank.detail)
    }

    @Test
    fun `模型下拉的候选就是这一家的清单，选中项只在清单里才勾得上`() {
        val provider = TextProviderCatalog.kimi.copy(models = listOf(TextModel("kimi-k2"), TextModel("kimi-k1")))
        val analysis = AnalysisSettings(
            provider = "kimi",
            saved = mapOf("kimi" to AnalysisCredentials(apiKey = "sk", model = "kimi-k1")),
        )

        val menu = modelChoices(provider, analysis)

        assertEquals(listOf("kimi-k2", "kimi-k1"), menu.options.map { it.id })
        assertEquals("kimi-k1", menu.selected)

        val stale = modelChoices(provider, stale(analysis, "kimi-legacy"))
        assertTrue("清单里没有的名字勾不上", stale.options.none { it.id == stale.selected })
    }

    @Test
    fun `清单外的模型 id 与空串都不会写回设置`() {
        val provider = TextProviderCatalog.deepseek.copy(models = listOf(TextModel("deepseek-chat")))
        val settings = AppSettings(
            analysis = AnalysisSettings(
                provider = "deepseek",
                saved = mapOf("deepseek" to AnalysisCredentials(apiKey = "sk", model = "deepseek-chat")),
            ),
        )

        assertNull("空串不发下去", settings.withAnalysisModel(provider, ""))
        assertNull("清单外的 id 不发下去", settings.withAnalysisModel(provider, "deepseek-v9"))
        assertEquals(
            "deepseek-chat",
            settings.withAnalysisModel(provider, "deepseek-chat")?.analysis?.model,
        )

        val kimi = TextProviderCatalog.kimi.copy(models = listOf(TextModel("kimi-k2")))
        val picked = settings.withAnalysisModel(kimi, "kimi-k2")
        assertEquals("kimi", picked?.analysis?.provider)
        assertEquals("kimi-k2", picked?.analysis?.of("kimi")?.model)
        assertEquals("deepseek-chat", picked?.analysis?.of("deepseek")?.model)
    }

    @Test
    fun `讯飞保存后归到讯飞，Azure 行显示未配置`() {
        val saved = AssessorSettings().withXfyun("app", "key", "secret")

        assertEquals(AssessorProvider.Xfyun, saved.resolvedProvider)
        assertEquals(AssessorProvider.Xfyun, saved.provider)

        val rows = assessorLines(AppSettings(assessor = saved))

        assertEquals(LinkStatus.Unconfigured, rows.first { it.key == "azure" }.status)
        assertEquals(LinkStatus.Configured, rows.first { it.key == "xfyun" }.status)
    }

    @Test
    fun `两家都齐备时认刚保存的那家`() {
        val azure = AssessorSettings().withAzure("key", "eastasia")
        assertEquals(AssessorProvider.Azure, azure.resolvedProvider)

        val both = azure.withXfyun("app", "key", "secret")
        assertEquals("填完讯飞就切到讯飞", AssessorProvider.Xfyun, both.resolvedProvider)

        val backToAzure = both.withAzure("key", "eastasia")
        assertEquals(AssessorProvider.Azure, backToAzure.resolvedProvider)
    }

    @Test
    fun `参数不齐备就退回另一家，都不齐落到未选择`() {
        val azureOnly = AssessorSettings().withAzure("key", "eastasia")

        val halfXfyun = azureOnly.withXfyun("app", "key", "")

        assertEquals("讯飞缺一件就退回齐备的 Azure", AssessorProvider.Azure, halfXfyun.resolvedProvider)
        assertEquals(AssessorProvider.None, AssessorSettings().withAzure("key", "").resolvedProvider)
        assertFalse(
            assessorLines(AppSettings(assessor = AssessorSettings().withAzure("key", "")))
                .any { it.status == LinkStatus.Configured },
        )
    }

    private fun realtime(lines: List<com.parley.speaker.domain.ProviderLine>) =
        lines.filter { it.group == "realtime" }

    private fun assessorLines(settings: AppSettings) =
        providerLines(settings, emptyMap()).filter { it.group == "assessor" }

    private fun analysisLines(analysis: AnalysisSettings) =
        providerLines(AppSettings(analysis = analysis), emptyMap()).filter { it.group == "analysis" }

    private fun stale(analysis: AnalysisSettings, model: String): AnalysisSettings =
        analysis.amend(analysis.provider) { it.copy(model = model) }

    private fun live(
        provider: LiveVendor = LiveVendor.Gemini,
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
