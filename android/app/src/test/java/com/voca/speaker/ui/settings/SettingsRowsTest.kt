package com.voca.speaker.ui.settings

import com.voca.speaker.data.analysis.TextModel
import com.voca.speaker.data.analysis.TextProviderCatalog
import com.voca.speaker.domain.AnalysisCredentials
import com.voca.speaker.domain.AnalysisSettings
import com.voca.speaker.domain.AppSettings
import com.voca.speaker.domain.AssessorProvider
import com.voca.speaker.domain.AssessorSettings
import com.voca.speaker.domain.LiveSettings
import com.voca.speaker.domain.LiveVendor
import com.voca.speaker.domain.VoiceCatalog
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 设置页那几个动作：存一家就切到那家，清一家就落到同组另一个还配着的家，一家不剩就空着。
 * 同组别家的凭据与音色都不许被顶掉。
 */
class SettingsRowsTest {

    @Test
    fun `存实时对话一家就切过去，别家的凭据与音色原样留着`() {
        val withGemini = AppSettings()
            .let { it.copy(live = it.live.saved(LiveVendor.Gemini, " AIza ", voice = "Puck")) }

        assertEquals(LiveVendor.Gemini, withGemini.live.provider)
        assertEquals("AIza", withGemini.live.geminiKey)
        assertEquals("Puck", withGemini.live.voice)

        val withDoubao = withGemini
            .let { it.copy(live = it.live.saved(LiveVendor.Doubao, "key", voice = "zh_female_xiaohe_jupiter_bigtts")) }

        assertEquals(LiveVendor.Doubao, withDoubao.live.provider)
        assertTrue(withDoubao.live.ready(LiveVendor.Doubao))
        assertEquals("AIza", withDoubao.live.geminiKey)
        assertEquals("zh_female_xiaohe_jupiter_bigtts", withDoubao.live.voice)

        val back = withDoubao.let { it.copy(live = it.live.select(LiveVendor.Gemini)) }
        assertEquals("AIza", back.live.geminiKey)
        assertEquals("Gemini 挑好的音色没被豆包那格顶掉", "Puck", back.live.voice)
    }

    @Test
    fun `空着保存不切过去`() {
        val live = LiveSettings(geminiKey = "AIza").select(LiveVendor.Gemini)

        val saved = live.saved(LiveVendor.OpenAi, key = "  ", voice = VoiceCatalog.defaultId(LiveVendor.OpenAi))

        assertEquals("没密钥那家存下来也不算生效", LiveVendor.Gemini, saved.provider)
        assertFalse(saved.ready(LiveVendor.OpenAi))
    }

    @Test
    fun `清掉实时对话生效那家落到同组另一个已配置的家`() {
        val live = LiveSettings(geminiKey = "AIza", doubaoApiKey = "key")
            .withTuning(LiveVendor.Gemini, voice = "Puck")
            .select(LiveVendor.Doubao)

        val cleared = live.cleared(LiveVendor.Doubao)

        assertEquals("", cleared.doubaoApiKey)
        assertEquals("AIza", cleared.geminiKey)
        assertEquals(LiveVendor.Gemini, cleared.provider)
        assertEquals("清一家不动它挑好的音色", "Puck", cleared.voice)
    }

    @Test
    fun `一家不剩就空着`() {
        val only = LiveSettings(geminiKey = "AIza").select(LiveVendor.Gemini)

        val cleared = only.cleared(LiveVendor.Gemini)

        assertNull("一家都不剩就没有生效那家", cleared.provider)
        assertFalse(cleared.ready)
    }

    @Test
    fun `清没生效那家不动单选点`() {
        val live = LiveSettings(geminiKey = "AIza", openAiKey = "sk").select(LiveVendor.Gemini)

        val cleared = live.cleared(LiveVendor.OpenAi)

        assertEquals(LiveVendor.Gemini, cleared.provider)
        assertEquals("", cleared.openAiKey)
        assertEquals("AIza", cleared.geminiKey)
    }

    @Test
    fun `文本分析存一家切过去，配第二家不动第一家`() {
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
    }

    @Test
    fun `文本分析没填密钥就不算生效`() {
        val deepseek = TextProviderCatalog.deepseek

        val blank = AppSettings().withAnalysisCredentials(
            provider = deepseek,
            apiKey = " ",
            model = "deepseek-chat",
            endpoint = "",
            authKind = null,
            authName = "",
            authPrefix = "",
        )

        assertEquals("", blank.analysis.provider)
    }

    @Test
    fun `文本分析清掉生效那家落到另一家，都不剩就空着`() {
        val both = AnalysisSettings(
            provider = "deepseek",
            saved = mapOf(
                "deepseek" to AnalysisCredentials(apiKey = "sk-deep", model = "deepseek-chat"),
                "kimi" to AnalysisCredentials(apiKey = "sk-kimi", model = "kimi-k2"),
            ),
        )

        val oneLeft = both.cleared("deepseek")
        assertEquals("kimi", oneLeft.provider)
        assertEquals("", oneLeft.of("deepseek").apiKey)

        val noneLeft = oneLeft.cleared("kimi")
        assertEquals("", noneLeft.provider)
        assertTrue(noneLeft.saved.isEmpty())
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

        val kimi = TextProviderCatalog.kimi.copy(models = listOf(TextModel("kimi-k2")))
        val picked = settings.withAnalysisModel(kimi, "kimi-k2")
        assertEquals("kimi-k2", picked?.analysis?.of("kimi")?.model)
        assertEquals("deepseek-chat", picked?.analysis?.of("deepseek")?.model)
        assertEquals("没填密钥那家挑了模型也不生效", "deepseek", picked?.analysis?.provider)

        val withKey = settings.let {
            it.copy(analysis = it.analysis.amend("kimi") { kimi -> kimi.copy(apiKey = "sk-kimi") })
        }
        assertEquals("配好的那家挑了模型就切过去", "kimi", withKey.withAnalysisModel(kimi, "kimi-k2")?.analysis?.provider)
    }

    @Test
    fun `评测两家都齐备时由显式选择说了算`() {
        val azure = AssessorSettings().withAzure("key", "eastasia")
        assertEquals(AssessorProvider.Azure, azure.resolvedProvider)

        val both = azure.withXfyun("app", "key", "secret")
        assertEquals("填完讯飞就切到讯飞", AssessorProvider.Xfyun, both.resolvedProvider)

        val backToAzure = both.select(AssessorProvider.Azure)
        assertEquals("Azure 齐备也不再自动顶上来，得点它", AssessorProvider.Azure, backToAzure.resolvedProvider)
        assertEquals("换回去讯飞的凭据还在", "app", backToAzure.xfyunAppId)
    }

    @Test
    fun `评测只看显式选择，没选过就是未选择`() {
        val both = AssessorSettings(azureKey = "key", azureRegion = "eastasia", xfyunAppId = "app", xfyunApiKey = "k", xfyunApiSecret = "s")

        assertEquals(AssessorProvider.None, both.resolvedProvider)
    }

    @Test
    fun `评测清掉生效那家落到另一家，都不剩就空着`() {
        val both = AssessorSettings().withAzure("key", "eastasia").withXfyun("app", "k", "s")

        val oneLeft = both.cleared(AssessorProvider.Xfyun)
        assertEquals(AssessorProvider.Azure, oneLeft.resolvedProvider)
        assertFalse(oneLeft.ready(AssessorProvider.Xfyun))

        val noneLeft = oneLeft.cleared(AssessorProvider.Azure)
        assertEquals(AssessorProvider.None, noneLeft.resolvedProvider)
        assertEquals("", noneLeft.azureKey)
    }
}
