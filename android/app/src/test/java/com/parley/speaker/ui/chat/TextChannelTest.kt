package com.parley.speaker.ui.chat

import com.parley.speaker.data.realtime.Capabilities
import com.parley.speaker.data.realtime.doubao.DoubaoRealtimeSession
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/**
 * 文本通道的准入由适配器自己报的能力清单裁决：不收文字的线路，打字与开场问候都发不出去，
 * 界面只拿到一条提示。连接没就绪不算配置问题，那一档只在日志里说一句，界面不出声。
 */
class TextChannelTest {

    @Test
    fun `能力清单说不收文字就弹一条提示`() {
        assertEquals(TextGate.Unsupported, textGate(Connection.Ready, Audio))
    }

    @Test
    fun `收文字的线路照常放行`() {
        assertEquals(TextGate.Allowed, textGate(Connection.Ready, Text))
    }

    @Test
    fun `豆包这条端到端线路自报不收文字`() {
        val doubao = DoubaoRealtimeSession(apiKey = "key", onEvent = {})

        assertFalse(doubao.capabilities.acceptsText)
        assertEquals(TextGate.Unsupported, textGate(Connection.Ready, doubao.capabilities))
    }

    @Test
    fun `连接没就绪就静默拦住，缺凭据才指路`() {
        assertEquals(TextGate.Waiting, textGate(Connection.Connecting, Text))
        assertEquals(TextGate.Waiting, textGate(Connection.Failed, Text))
        assertEquals(TextGate.Unconfigured, textGate(Connection.Unconfigured, Text))
    }

    private companion object {
        val Text = Capabilities(
            canInterrupt = true,
            acceptsText = true,
            streamsTranscript = true,
            phonemeScores = false,
            maxSessionMinutes = null,
        )
        val Audio = Text.copy(acceptsText = false)
    }
}
