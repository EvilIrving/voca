package com.parley.speaker.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 回看是只读视图：录音入口整条关上，按下去也不收，提示说清楚为什么。
 * 界面与视图模型读的都是 [recordingAllowed] 这一份判断。
 */
class ReviewGateTest {

    @Test
    fun `回看时录音入口不可用，回到对话就恢复`() {
        assertFalse(recordingAllowed(reviewing = true))
        assertTrue(recordingAllowed(reviewing = false))
    }

    @Test
    fun `回看时按录音弹的是回看这句提示`() {
        assertEquals("回看中，请退出后继续", ReviewReadOnly)
    }

    @Test
    fun `回看时文本通道也一并挡在只读之外`() {
        // 线路就绪也不放行收音，回看那条路上先看只读，再看连接状态。
        assertFalse("回看优先于连接状态", recordingAllowed(reviewing = true))
        assertNull("连接就绪时对话里可以正常按", textRefusal(Connection.Ready, capabilities = null))

        val message = textRefusal(Connection.Failed, capabilities = null)
        assertNotNull(message)
        assertTrue("未连接时提示正在重连", message!!.contains("重连"))
    }
}
