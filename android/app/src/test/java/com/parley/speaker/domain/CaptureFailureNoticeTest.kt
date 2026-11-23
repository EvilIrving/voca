package com.parley.speaker.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 采集中断时界面说的那句话由原因决定，不按「麦克风坏了」一概而论。
 * 链路没就绪与设备被占用是两件完全不同的事，提示语指向的处理动作也不同。
 */
class CaptureFailureNoticeTest {

    private class Drive {
        var state = TurnFlowState()
        val effects = mutableListOf<TurnEffect>()
        private var counter = 0

        fun send(vararg sequence: TurnEvent): List<TurnEffect> {
            val before = effects.size
            sequence.forEach { event ->
                val reduction = TurnFlow.reduce(state, event) { prefix -> "$prefix-${++counter}" }
                state = reduction.state
                effects += reduction.effects
            }
            return effects.subList(before, effects.size).toList()
        }
    }

    private fun noticeOf(cause: CaptureFailure): String {
        val drive = Drive()
        drive.send(TurnEvent.SessionReady, TurnEvent.PressStart, TurnEvent.UserText("说了一半"))
        val effects = drive.send(TurnEvent.CaptureFailed(cause))
        val notice = effects.filterIsInstance<TurnEffect.Notify>().singleOrNull()
        return notice?.message ?: error("没有提示，实际发的是 $effects")
    }

    @Test
    fun `链路没就绪说的是网络`() {
        assertEquals("网络异常，正在重连", noticeOf(CaptureFailure.Offline))
    }

    @Test
    fun `麦克风被占用说的是占用`() {
        assertEquals("麦克风被占用", noticeOf(CaptureFailure.Busy))
    }

    @Test
    fun `每条原因都有自己的话，不重复`() {
        val notices = CaptureFailure.entries.map { it.notice }
        assertEquals("四条原因对应四句提示，实际 $notices", notices.size, notices.toSet().size)
    }

    @Test
    fun `中断按取消收尾，半截音频不落气泡`() {
        val drive = Drive()
        drive.send(TurnEvent.SessionReady, TurnEvent.PressStart, TurnEvent.UserText("说了一半"))
        val effects = drive.send(TurnEvent.CaptureFailed(CaptureFailure.Device))

        assertTrue(
            "要发取消，实际发的是 $effects",
            effects.filterIsInstance<TurnEffect.CancelTurn>().any { it.turnId == "user-1" },
        )
        assertEquals("半截那一轮要从列表里拿掉", emptyList<Turn>(), drive.state.turns)
        assertTrue("界面回到空态", drive.state.phase == TurnPhase.Idle)
    }
}
