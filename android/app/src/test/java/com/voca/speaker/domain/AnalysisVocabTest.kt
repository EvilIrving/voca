package com.voca.speaker.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 分析结果带语境生词时，reducer 必须把它派给入库效果，否则词库只能一直是空的。 */
class AnalysisVocabTest {

    private val card = VocabCard(
        id = "user-1-toss and turn",
        word = "toss and turn",
        phonetic = "/tɔːs ænd tɜːrn/",
        meaning = "辗转反侧",
        example = "I tossed and turned all night.",
        highlight = "tossed and turned",
        repeats = 1,
    )

    private fun readyTurn() = TurnFlowState(
        phase = TurnPhase.Idle,
        turns = listOf(Turn(id = "user-1", speaker = Speaker.User, text = "I slept badly last night")),
    )

    @Test
    fun `分析带回生词时派出入库效果`() {
        val reduction = TurnFlow.reduce(
            readyTurn(),
            TurnEvent.AnalysisDone(
                "user-1",
                Review(
                    original = "I slept badly last night",
                    grammar = GrammarReview(88, "表达无误"),
                    pronunciation = null,
                    rewrites = emptyList(),
                ),
                listOf(card),
            ),
        ) { "user-2" }

        val learned = reduction.effects.filterIsInstance<TurnEffect.LearnVocab>()
        assertEquals(1, learned.size)
        assertEquals("user-1", learned.single().turnId)
        assertEquals(listOf(card), learned.single().cards)
        assertTrue(reduction.state.turns.single().analysis == AnalysisStatus.Ready)
    }

    @Test
    fun `分析没有生词时不派入库效果`() {
        val reduction = TurnFlow.reduce(
            readyTurn(),
            TurnEvent.AnalysisDone(
                "user-1",
                Review(
                    original = "I slept badly last night",
                    grammar = GrammarReview(88, "表达无误"),
                    pronunciation = null,
                    rewrites = emptyList(),
                ),
                emptyList(),
            ),
        ) { "user-2" }

        assertTrue(reduction.effects.none { it is TurnEffect.LearnVocab })
    }
}
