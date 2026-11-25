package com.voca.speaker.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 未连接后的长退避时间表与这一轮重连的生死。
 * 期望值直接取自顶栏状态点的规矩：一秒起逐次翻倍、封顶三十秒、每次叠零点三秒以内的抖动。
 */
class ReconnectBackoffTest {

    @Test
    fun `退避从一秒起逐次翻倍`() {
        val waits = (0..4).map { ReconnectBackoff.delayMs(it, 0.0) }

        assertEquals(listOf(1_000L, 2_000L, 4_000L, 8_000L, 16_000L), waits)
    }

    @Test
    fun `封顶三十秒，再往上翻也不再变长`() {
        assertEquals(30_000L, ReconnectBackoff.delayMs(5, 0.0))
        assertEquals(30_000L, ReconnectBackoff.delayMs(6, 0.0))
        assertEquals(30_000L, ReconnectBackoff.delayMs(40, 0.0))
    }

    @Test
    fun `抖动落在零到三百毫秒之间`() {
        assertEquals("不抖时就是底数", 1_000L, ReconnectBackoff.delayMs(0, 0.0))
        assertEquals("抖到最大也就是三百毫秒", 1_300L, ReconnectBackoff.delayMs(0, 1.0))

        // 比例越界按上下界夹住，单次等待不会长过封顶加三百毫秒。
        assertEquals(1_000L, ReconnectBackoff.delayMs(0, -3.0))
        assertEquals(30_300L, ReconnectBackoff.delayMs(9, 4.0))

        var jitter = 0.0
        while (jitter < 1.0) {
            val wait = ReconnectBackoff.delayMs(3, jitter)
            assertTrue("jitter=$jitter 落到 ${wait}ms", wait in 8_000L..8_300L)
            jitter += 0.05
        }
    }

    @Test
    fun `握手成功一次就把序号退回一秒`() {
        val reset = ReconnectState(attempt = 7).reset()

        assertEquals(0, reset.attempt)
        assertEquals(1_000L, reset.waitMs(0.0))
    }

    @Test
    fun `排上一次等待后序号前进，下一次失败按翻倍算`() {
        val scheduled = ReconnectState().scheduled()

        assertEquals(1, scheduled.attempt)
        assertEquals(2_000L, scheduled.waitMs(0.0))
    }

    @Test
    fun `点击圆点只跳过当前等待，不重置翻倍`() {
        val waiting = ReconnectState(attempt = 3)
        val skipped = waiting.skipped()

        assertEquals("跳过这次等待，下一次失败还是按第 3 次翻倍算", 3, skipped.attempt)
        assertFalse("这一轮在飞的等待回来时对不上代号，不能再重连", skipped.isCurrent(waiting.epoch))
    }

    @Test
    fun `挂断与进入回看取消这一轮，在飞的等待不再重连`() {
        val waiting = ReconnectState(attempt = 2)
        val cancelled = waiting.reset()

        assertFalse(cancelled.isCurrent(waiting.epoch))

        val next = cancelled.reset()
        assertEquals("新开一轮又从一秒数", 0, next.attempt)
        assertTrue("新的一轮只认自己的代号", next.isCurrent(next.epoch))
        assertFalse("也不认被取消那一轮的", next.isCurrent(waiting.epoch))
    }

}
