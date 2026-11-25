package com.voca.speaker.domain

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 一轮的位置在它开场那一刻占住，不等文字到齐再插。
 * 千问与 Gemini 的用户转写要等服务端把这一条音频处理完才回，助手那一路的转写常常先到，
 * 按到字的先后来排，回复就落到提问前面。
 */
class TurnOrderTest {

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
    fun `识别文本比回复晚到，提问仍排在回复前面`() {
        val drive = Drive()
        drive.send(TurnEvent.SessionReady, TurnEvent.PressStart, TurnEvent.Commit)
        drive.send(TurnEvent.AssistantText("I can hear you perfectly."))
        drive.send(TurnEvent.UserText("Hi, can you hear me clearly?"))

        assertEquals(
            listOf(Speaker.User to "Hi, can you hear me clearly?", Speaker.Echo to "I can hear you perfectly."),
            drive.state.turns.map { it.speaker to it.text },
        )
    }

    @Test
    fun `连着两轮，各自排在自己的位子上`() {
        val drive = Drive()
        drive.send(TurnEvent.SessionReady, TurnEvent.PressStart, TurnEvent.Commit)
        drive.send(TurnEvent.AssistantText("A1"), TurnEvent.UserText("Q1"), TurnEvent.TurnComplete)
        drive.send(TurnEvent.PressStart, TurnEvent.Commit)
        drive.send(TurnEvent.AssistantText("A2"), TurnEvent.UserText("Q2"), TurnEvent.TurnComplete)

        assertEquals(listOf("Q1", "A1", "Q2", "A2"), drive.state.turns.map { it.text })
    }
}
