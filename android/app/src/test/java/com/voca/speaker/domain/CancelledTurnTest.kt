package com.voca.speaker.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 取消掉的一轮在回合层面就不留下落库指令。库里最终有没有这一行由 SessionPersistenceTest 验证。
 */
class CancelledTurnTest {

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
    fun `取消后这一轮不进列表且只发删除`() {
        val drive = Drive()
        drive.send(TurnEvent.SessionReady, TurnEvent.PressStart, TurnEvent.UserText("把这一句丢掉"))
        val cancelEffects = drive.send(TurnEvent.Cancel)

        assertTrue(
            "取消这一轮要把草稿从列表里去掉，实际剩 ${drive.state.turns.map { it.text }}",
            drive.state.turns.none { it.text == "把这一句丢掉" },
        )
        assertTrue(
            "取消要发出删除指令，实际发的是 $cancelEffects",
            cancelEffects.filterIsInstance<TurnEffect.Delete>().any { it.turnId == "user-1" },
        )
        assertFalse(
            "取消之后不能再落库，实际发的是 $cancelEffects",
            cancelEffects.any { it is TurnEffect.Persist && it.turn.id == "user-1" },
        )
    }

    @Test
    fun `正常说完的一轮会落库`() {
        val drive = Drive()
        drive.send(TurnEvent.SessionReady, TurnEvent.PressStart, TurnEvent.UserText("I went to the coffee shop"))
        val completeEffects = drive.send(TurnEvent.Commit, TurnEvent.AssistantText("Nice."), TurnEvent.TurnComplete)

        val persisted = drive.effects.filterIsInstance<TurnEffect.Persist>().map { it.turn.text }
        assertTrue(
            "用户这一轮说完要落库，实际落了 $persisted",
            persisted.contains("I went to the coffee shop"),
        )
        assertTrue(
            "收尾时也要把这一轮落定，实际发的是 $completeEffects",
            completeEffects.filterIsInstance<TurnEffect.Persist>().any { it.turn.text == "I went to the coffee shop" },
        )
        assertEquals(
            listOf("I went to the coffee shop", "Nice."),
            drive.state.turns.map { it.text },
        )
    }
}
