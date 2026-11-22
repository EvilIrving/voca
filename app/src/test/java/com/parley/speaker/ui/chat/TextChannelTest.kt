package com.parley.speaker.ui.chat

import com.parley.speaker.data.realtime.Capabilities
import com.parley.speaker.data.realtime.doubao.DoubaoRealtimeSession
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 文本通道的准入由适配器自己报的能力清单裁决：不收文字的线路，打字与开场问候都发不出去，
 * 界面只拿到一条提示。豆包那条端到端链路正是如此。
 */
class TextChannelTest {

    @Test
    fun `能力清单说不收文字就弹一条提示`() {
        assertEquals(TextUnsupported, textRefusal(Connection.Ready, Audio))
    }

    @Test
    fun `收文字的线路照常放行`() {
        assertNull(textRefusal(Connection.Ready, Text))
    }

    @Test
    fun `豆包这条端到端线路自报不收文字`() {
        val doubao = DoubaoRealtimeSession(
            appId = "app",
            appKey = "key",
            accessKey = "secret",
            onEvent = {},
        )

        assertFalse(doubao.capabilities.acceptsText)
        assertEquals(TextUnsupported, textRefusal(Connection.Ready, doubao.capabilities))
    }

    @Test
    fun `没就绪时先提示等连接`() {
        assertEquals("正在连接", textRefusal(Connection.Connecting, Text))
        assertEquals("正在重连", textRefusal(Connection.Failed, Text))
        assertEquals(FillLiveKey, textRefusal(Connection.Unconfigured, Text))
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
