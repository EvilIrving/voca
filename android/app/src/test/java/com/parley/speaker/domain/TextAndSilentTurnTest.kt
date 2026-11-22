package com.parley.speaker.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 接进运行链路的两个新入口：打字发送绕过收音与 activityStart，闸门丢弃的那一轮什么都不留。
 */
class TextAndSilentTurnTest {

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
    fun `打字发送立刻落一条用户轮再送文本`() {
        val drive = Drive()
        drive.send(TurnEvent.SessionReady)
        val sent = drive.send(TurnEvent.TextTurn("  我昨天去喝咖啡了  "))

        assertTrue(
            "用户轮要立刻进列表，实际 ${drive.state.turns.map { it.text }}",
            drive.state.turns.single().text == "我昨天去喝咖啡了",
        )
        assertTrue("用户轮立刻写库，实际发的是 $sent", sent.filterIsInstance<TurnEffect.Persist>().isNotEmpty())
        assertTrue("文本要送到会话，实际发的是 $sent", sent.filterIsInstance<TurnEffect.SendText>().isNotEmpty())
        assertFalse("打字不走收音，实际发的是 $sent", sent.any { it is TurnEffect.StartCapture })
        assertFalse("打字不开新的 activity，实际发的是 $sent", sent.contains(TurnEffect.SendTurnStart))
        assertTrue("送完文本等回复", drive.state.phase is TurnPhase.Awaiting)
    }

    @Test
    fun `打字之后模型的回复照常落进来`() {
        val drive = Drive()
        drive.send(TurnEvent.SessionReady, TurnEvent.TextTurn("what's up"))
        drive.send(TurnEvent.AssistantText("Not much."), TurnEvent.TurnComplete)

        assertEquals(listOf("what's up", "Not much."), drive.state.turns.map { it.text })
        assertEquals(TurnPhase.Idle, drive.state.phase)
    }

    @Test
    fun `闸门丢弃的一轮不发收尾也不落气泡`() {
        val drive = Drive()
        drive.send(TurnEvent.SessionReady, TurnEvent.PressStart)
        val dropped = drive.send(TurnEvent.SilentTurn)

        assertEquals("本地直接回 Idle", TurnPhase.Idle, drive.state.phase)
        assertEquals("不留任何轮次", emptyList<Turn>(), drive.state.turns)
        assertFalse("不发收尾", dropped.contains(TurnEffect.SendTurnEnd))
        assertTrue("要告诉服务端这一轮作废", dropped.filterIsInstance<TurnEffect.CancelTurn>().isNotEmpty())
        assertFalse("不弹提示", dropped.any { it is TurnEffect.Notify })
        assertTrue("迟到的内容进黑名单", "user-1" in drive.state.cancelledTurnIds)
    }

    @Test
    fun `音频先到也要有轮次可落录音`() {
        val drive = Drive()
        drive.send(TurnEvent.SessionReady, TurnEvent.PressStart, TurnEvent.UserText("hello"), TurnEvent.Commit)
        val played = drive.send(TurnEvent.Audio(ByteArray(4)))

        val play = played.filterIsInstance<TurnEffect.Play>().single()
        assertEquals("音频要带上这一轮助手的轮次", "echo-2", play.turnId)
        assertEquals(play.turnId, (drive.state.phase as TurnPhase.Awaiting).echoTurnId)
    }

    @Test
    fun `译文回来并进这一轮`() {
        val drive = Drive()
        drive.send(TurnEvent.SessionReady, TurnEvent.PressStart, TurnEvent.UserText("hi"), TurnEvent.Commit)
        val echoEffects = drive.send(TurnEvent.AssistantText("Morning."), TurnEvent.TurnComplete)
        val echoId = echoEffects.filterIsInstance<TurnEffect.Persist>().first { it.turn.text == "Morning." }.turn.id
        val translated = drive.send(TurnEvent.TranslationDone(echoId, "早上好。"))

        assertEquals("早上好。", drive.state.turns.first { it.id == echoId }.translation)
        assertTrue(
            "译文要随轮落库，实际发的是 $translated",
            translated.filterIsInstance<TurnEffect.Persist>().any { it.turn.translation == "早上好。" },
        )
    }
}
