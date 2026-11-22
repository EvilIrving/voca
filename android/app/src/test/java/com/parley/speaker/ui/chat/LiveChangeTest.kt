package com.parley.speaker.ui.chat

import com.parley.speaker.domain.AppSettings
import com.parley.speaker.domain.LiveChange
import com.parley.speaker.domain.LiveSettings
import com.parley.speaker.domain.LiveVendor
import com.parley.speaker.domain.liveChange
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 设置变更对进行中会话的影响。凭据与供应商之外的东西一律留到下一次开新会话，
 * 练习中改音色或模型不重建连接，正在进行的一轮不会被掐断。
 */
class LiveChangeTest {

    @Test
    fun `练习中改音色不重建连接`() {
        assertEquals(LiveChange.Voice, liveChange(AllSet, AllSet.copy(live = AllSet.live.copy(voice = "Puck"))))
    }

    @Test
    fun `练习中改模型不重建连接`() {
        assertEquals(
            LiveChange.Model,
            liveChange(AllSet, AllSet.copy(live = AllSet.live.copy(model = "gemini-2.5-flash-native-audio-latest"))),
        )
    }

    @Test
    fun `改角色提示词不重建连接`() {
        assertEquals(LiveChange.Prompt, liveChange(AllSet, AllSet.copy(prompt = "换一个更严格的教练")))
    }

    @Test
    fun `换密钥重建连接`() {
        assertEquals(LiveChange.Rebuild, liveChange(AllSet, AllSet.copy(live = AllSet.live.copy(geminiKey = "AIza-new"))))
        assertEquals(LiveChange.Rebuild, liveChange(AllSet, AllSet.copy(live = AllSet.live.copy(openAiKey = "sk-new"))))
        assertEquals(
            LiveChange.Rebuild,
            liveChange(AllSet, AllSet.copy(live = AllSet.live.copy(doubaoAccessKey = "secret-new"))),
        )
    }

    @Test
    fun `切供应商重建连接`() {
        assertEquals(
            LiveChange.Rebuild,
            liveChange(AllSet, AllSet.copy(live = AllSet.live.copy(provider = LiveVendor.OpenAi))),
        )
    }

    @Test
    fun `供应商与音色同时变，按重建连接算`() {
        val after = AllSet.copy(
            live = AllSet.live.copy(provider = LiveVendor.OpenAi, voice = "alloy", model = "gpt-realtime-2.1"),
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
                doubaoAppId = "app-id",
                doubaoAppKey = "app-key",
                doubaoAccessKey = "access-key",
                model = "gemini-3.8-live",
                voice = "Aoede",
            ),
        )
    }
}
