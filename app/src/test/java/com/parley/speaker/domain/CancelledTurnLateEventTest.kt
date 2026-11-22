package com.parley.speaker.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 取消之后服务端还会继续回吐这一轮的识别文本与音频。适配器把它们打上被取消那轮 id，
 * 这里认 id 丢弃，包括用户已经按下一次、新一轮开始收音的时候。
 */
class CancelledTurnLateEventTest {

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

    @Test
    fun `取消这一轮发取消指令而不是收尾指令`() {
        val drive = Drive()
        drive.send(TurnEvent.SessionReady, TurnEvent.PressStart, TurnEvent.UserText("把这一句丢掉"))
        val cancelEffects = drive.send(TurnEvent.Cancel)

        assertTrue(
            "取消要发 CancelTurn 让服务端别生成，实际发的是 $cancelEffects",
            cancelEffects.filterIsInstance<TurnEffect.CancelTurn>().any { it.turnId == "user-1" },
        )
        assertTrue(
            "取消不能再发 SendTurnEnd，实际发的是 $cancelEffects",
            cancelEffects.none { it == TurnEffect.SendTurnEnd },
        )
    }

    @Test
    fun `迟到的事件不建气泡也不播`() {
        val drive = Drive()
        drive.send(TurnEvent.SessionReady, TurnEvent.PressStart, TurnEvent.UserText("把这一句丢掉"))
        drive.send(TurnEvent.Cancel)

        val late = drive.send(
            TurnEvent.UserText("迟到的识别", "user-1"),
            TurnEvent.AssistantText("迟到的回复", "user-1"),
            TurnEvent.Audio(ByteArray(8), "user-1"),
        )

        assertEquals("迟到的内容不能在列表里留下任何东西，实际 ${drive.state.turns}", emptyList<Turn>(), drive.state.turns)
        assertTrue("迟到的音频不能送播放，实际发的是 $late", late.none { it is TurnEffect.Play })
    }

    @Test
    fun `新一轮收音期间旧轮的迟到文本也不能混进草稿`() {
        val drive = Drive()
        drive.send(TurnEvent.SessionReady, TurnEvent.PressStart, TurnEvent.UserText("把这一句丢掉"))
        drive.send(TurnEvent.Cancel)
        drive.send(TurnEvent.PressStart)

        drive.send(TurnEvent.UserText("迟到的识别", "user-1"))
        assertEquals("新一轮草稿里不能有旧轮的文字，实际 ${drive.state.turns}", emptyList<Turn>(), drive.state.turns)

        drive.send(TurnEvent.UserText("这一轮真说的话"))
        assertEquals(listOf("这一轮真说的话"), drive.state.turns.map { it.text })
    }

    @Test
    fun `麦克风失败也走取消`() {
        val drive = Drive()
        drive.send(TurnEvent.SessionReady, TurnEvent.PressStart, TurnEvent.UserText("说了一半"))
        val failed = drive.send(TurnEvent.CaptureFailed)

        assertTrue(
            "麦克风失败时半截音频已经在服务端手里，要按取消收，实际发的是 $failed",
            failed.filterIsInstance<TurnEffect.CancelTurn>().any { it.turnId == "user-1" },
        )
    }

    @Test
    fun `取消名单只留最近几次`() {
        val drive = Drive()
        drive.send(TurnEvent.SessionReady)
        repeat(6) { drive.send(TurnEvent.PressStart, TurnEvent.Cancel) }

        assertEquals(4, drive.state.cancelledTurnIds.size)
        assertTrue("最近取消的那一轮还在名单里", "user-6" in drive.state.cancelledTurnIds)
    }
}
