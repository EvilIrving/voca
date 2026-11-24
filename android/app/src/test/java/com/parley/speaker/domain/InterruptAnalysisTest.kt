package com.parley.speaker.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 生成中被强行打断的那一轮同样要收尾：用户说过的句子留在列表里，就得进分析，
 * 语法评测不能因为助手还没说完就永远缺一块。
 */
class InterruptAnalysisTest {

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
    fun `按住说话打断生成时保留的发言照样进分析`() {
        val drive = Drive()
        drive.send(
            TurnEvent.SessionReady,
            TurnEvent.PressStart,
            TurnEvent.UserText("I go to cafe yesterday"),
            TurnEvent.Commit,
            TurnEvent.AssistantText("Nice, what did you order?"),
        )
        val interrupted = drive.send(TurnEvent.PressStart)

        val analyze = interrupted.filterIsInstance<TurnEffect.Analyze>()
        assertEquals("被打断那轮要发分析，实际发的是 $interrupted", 1, analyze.size)
        assertEquals("I go to cafe yesterday", analyze.single().text)
        val kept = drive.state.turns.first { it.speaker == Speaker.User }
        assertEquals("这一轮留在列表里并标成待分析", AnalysisStatus.Pending, kept.analysis)
        assertTrue(
            "待分析的状态要写库，实际发的是 $interrupted",
            interrupted.filterIsInstance<TurnEffect.Persist>().any { it.turn.id == kept.id },
        )
        assertTrue("同时开始新的收音", drive.state.phase is TurnPhase.Recording)
        assertTrue("被打断的助手回复留着", drive.state.turns.any { it.speaker == Speaker.Echo })
    }

    @Test
    fun `打字打断生成时保留的发言也进分析`() {
        val drive = Drive()
        drive.send(
            TurnEvent.SessionReady,
            TurnEvent.PressStart,
            TurnEvent.UserText("I have went there"),
            TurnEvent.Commit,
            TurnEvent.Audio(ByteArray(4)),
        )
        val interrupted = drive.send(TurnEvent.TextTurn("算了，我换个说法"))

        assertTrue(
            "被打断那轮要发分析，实际发的是 $interrupted",
            interrupted.filterIsInstance<TurnEffect.Analyze>().any { it.text == "I have went there" },
        )
        assertEquals(
            "用户轮的分析状态是待分析",
            AnalysisStatus.Pending,
            drive.state.turns.first { it.text == "I have went there" }.analysis,
        )
    }

    @Test
    fun `打断时还没识别出文字就整轮作废且不弹提示`() {
        val drive = Drive()
        drive.send(TurnEvent.SessionReady, TurnEvent.PressStart, TurnEvent.Commit, TurnEvent.Audio(ByteArray(4)))
        val interrupted = drive.send(TurnEvent.PressStart)

        assertEquals("半句都没识别出来就什么都不留", listOf(""), drive.state.turns.map { it.text })
        assertFalse("打断时用户正在说下一句，不弹提示", interrupted.any { it is TurnEffect.Notify })
        assertFalse("没有文字就没有分析", interrupted.any { it is TurnEffect.Analyze })
        assertTrue("被打断的助手回复也要清掉", drive.state.phase is TurnPhase.Recording)
    }

    @Test
    fun `已经拿到评测的轮次不会因为打断再分析一次`() {
        val drive = Drive()
        drive.send(TurnEvent.SessionReady, TurnEvent.PressStart, TurnEvent.UserText("I like it"))
        drive.send(TurnEvent.Commit, TurnEvent.TurnComplete)
        val userTurn = drive.state.turns.first { it.speaker == Speaker.User }
        drive.send(
            TurnEvent.AnalysisDone(
                userTurn.id,
                Review(
                    original = userTurn.text,
                    grammar = GrammarReview(score = 88, note = ""),
                    pronunciation = null,
                    rewrites = emptyList(),
                ),
                emptyList(),
            ),
        )
        val next = drive.send(TurnEvent.PressStart, TurnEvent.Commit, TurnEvent.TurnComplete)

        assertFalse(
            "这一轮已经评测过，不该再发一次，实际发的是 $next",
            next.filterIsInstance<TurnEffect.Analyze>().any { it.turnId == userTurn.id },
        )
        assertEquals(AnalysisStatus.Ready, drive.state.turns.first { it.id == userTurn.id }.analysis)
    }
}
