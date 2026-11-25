package com.voca.speaker.ui.chat

import android.app.Application
import com.voca.speaker.data.SessionStore
import com.voca.speaker.domain.AnalysisStatus
import com.voca.speaker.domain.GrammarReview
import com.voca.speaker.domain.PronunciationReview
import com.voca.speaker.domain.Review
import com.voca.speaker.domain.Speaker
import com.voca.speaker.domain.Turn
import com.voca.speaker.domain.VocabCard
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/**
 * 收段会换掉整屏对话，在飞的分析回来时状态机里已经没有这一轮。
 * 那一下如果只走 dispatch，评测与发音分永远停在待分析，生词却已经入库；
 * 这里钉住另一条路：按 id 把评测写回库，生词照常进。
 */
@RunWith(RobolectricTestRunner::class)
class LateAnalysisTest {

    private val app: Application = RuntimeEnvironment.getApplication()

    private fun review(score: Int = 78) = Review(
        original = "I get up early today",
        grammar = GrammarReview(score, "第三人称单数"),
        pronunciation = PronunciationReview(score, "准确度 78"),
        rewrites = emptyList(),
    )

    private fun card(word: String) = VocabCard(
        id = word,
        word = word,
        phonetic = "/x/",
        meaning = "释义",
        example = "I said $word.",
        highlight = word,
        repeats = 1,
    )

    @Test
    fun `收段带走了这一轮，评测直接落库，那一轮不再是待分析`() = runBlocking {
        val store = SessionStore(app)
        store.saveTurn(
            Turn(
                id = "u1",
                speaker = Speaker.User,
                text = "I get up early today",
                analysis = AnalysisStatus.Pending,
                sessionId = "s1",
            ),
        )
        // 收段换了整屏对话：手上这一段的轮次列表已经空掉。
        assertEquals(AnalysisRoute.Store, analysisRoute(emptyList(), "u1"))
        assertEquals("还没写回时库里没有评测", AnalysisStatus.None, SessionStore(app).loadTurns().single().analysis)

        assertTrue(store.saveReview("u1", review()))

        val turn = SessionStore(app).loadTurns().single { it.id == "u1" }
        assertEquals(AnalysisStatus.Ready, turn.analysis)
        assertEquals(78, turn.review?.grammar?.score)
        assertEquals(78, turn.review?.pronunciation?.score)
        assertEquals("只写评测，正文与会话不动", "s1", turn.sessionId)
    }

    @Test
    fun `这一轮还在手上就交给状态机，不走直接落库那条路`() {
        val turns = listOf(Turn(id = "u1", speaker = Speaker.User, text = "hi"))

        assertEquals(AnalysisRoute.Flow, analysisRoute(turns, "u1"))
        assertEquals(AnalysisRoute.Store, analysisRoute(turns, "u2"))
    }

    @Test
    fun `删掉的那一轮晚到的评测不再写回`() = runBlocking {
        val store = SessionStore(app)
        store.saveTurn(Turn(id = "u9", speaker = Speaker.User, text = "删掉的一句"))
        store.deleteTurn("u9")

        assertFalse("行都不在了，一个字段都不写", store.saveReview("u9", review()))
        assertTrue(SessionStore(app).loadTurns().isEmpty())
    }

    @Test
    fun `晚到的生词照常入库，同一份结果写两次也不重复`() = runBlocking {
        val store = SessionStore(app)
        val cards = listOf(card("burn out"), card("sleep in"))

        store.recordVocab(cards)
        // 收段与重试两条路各写一次同一份结果。
        store.recordVocab(cards)

        val vocab = SessionStore(app).loadVocab()
        assertEquals(listOf("burn out", "sleep in"), vocab.map { it.word }.sorted())
        assertEquals("同一条词是复现两次，不是两条", listOf(2, 2), vocab.map { it.repeats })
    }
}
