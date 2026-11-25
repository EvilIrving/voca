package com.voca.speaker.ui.chat

import com.voca.speaker.domain.AppSettings
import com.voca.speaker.domain.LiveChange
import com.voca.speaker.domain.LiveSettings
import com.voca.speaker.domain.LiveVendor
import com.voca.speaker.domain.liveChange
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 设置变更对进行中会话的影响。凭据与供应商之外的东西一律留到下一次开新会话，
 * 练习中改音色或模型不重建连接，正在进行的一轮不会被掐断。
 */
class LiveChangeTest {

    @Test
    fun `练习中改生效那家的音色不重建连接`() {
        assertEquals(LiveChange.Voice, liveChange(AllSet, AllSet.copy(live = AllSet.live.withTuning(LiveVendor.Gemini, voice = "Puck"))))
    }

    @Test
    fun `练习中改模型不重建连接`() {
        assertEquals(
            LiveChange.Model,
            liveChange(
                AllSet,
                AllSet.copy(live = AllSet.live.withTuning(LiveVendor.Gemini, model = "gemini-2.5-flash-native-audio-latest")),
            ),
        )
    }

    @Test
    fun `改没生效那家的音色不碰正在用的连接`() {
        val after = AllSet.copy(live = AllSet.live.withTuning(LiveVendor.Doubao, voice = "zh_male_xiaotian_jupiter_bigtts"))

        assertEquals(LiveChange.None, liveChange(AllSet, after))
    }

    @Test
    fun `改角色提示词不重建连接`() {
        assertEquals(LiveChange.Prompt, liveChange(AllSet, AllSet.copy(prompt = "换一个更严格的教练")))
    }

    @Test
    fun `换密钥重建连接`() {
        assertEquals(LiveChange.Rebuild, liveChange(AllSet, AllSet.copy(live = AllSet.live.withKey(LiveVendor.Gemini, "AIza-new"))))
        assertEquals(LiveChange.Rebuild, liveChange(AllSet, AllSet.copy(live = AllSet.live.withKey(LiveVendor.OpenAi, "sk-new"))))
        assertEquals(LiveChange.Rebuild, liveChange(AllSet, AllSet.copy(live = AllSet.live.withKey(LiveVendor.Doubao, "key-new"))))
    }

    @Test
    fun `切供应商重建连接`() {
        assertEquals(
            LiveChange.Rebuild,
            liveChange(AllSet, AllSet.copy(live = AllSet.live.select(LiveVendor.OpenAi))),
        )
    }

    @Test
    fun `清掉生效那家的密钥也重建连接`() {
        assertEquals(
            LiveChange.Rebuild,
            liveChange(AllSet, AllSet.copy(live = AllSet.live.cleared(LiveVendor.Gemini))),
        )
    }

    @Test
    fun `供应商与音色同时变，按重建连接算`() {
        val after = AllSet.copy(
            live = AllSet.live
                .withTuning(LiveVendor.OpenAi, voice = "alloy")
                .select(LiveVendor.OpenAi),
        )

        assertEquals(LiveChange.Rebuild, liveChange(AllSet, after))
    }

    @Test
    fun `改文本分析设置不碰实时会话`() {
        val after = AllSet.copy(
            analysis = AllSet.analysis
                .amend("deepseek") { it.copy(apiKey = "sk-deep", model = "deepseek-chat") }
                .select("deepseek"),
        )

        assertEquals(LiveChange.None, liveChange(AllSet, after))
    }

    @Test
    fun `什么都没改就是无变更`() {
        assertEquals(LiveChange.None, liveChange(AllSet, AllSet.copy()))
    }

    private companion object {
        val AllSet = AppSettings(
            live = LiveSettings(
                provider = LiveVendor.Gemini,
                geminiKey = "AIza-key",
                openAiKey = "sk-openai",
                doubaoApiKey = "doubao-key",
            ).withTuning(LiveVendor.Gemini, model = "gemini-3.8-live", voice = "Aoede"),
        )
    }
}
