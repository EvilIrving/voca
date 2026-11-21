package com.parley.speaker.data.analysis

import com.parley.speaker.domain.PronunciationReview
import com.parley.speaker.domain.Register
import com.parley.speaker.domain.Review
import com.parley.speaker.domain.Rewrite
import com.parley.speaker.domain.VocabCard
import com.parley.speaker.domain.GrammarReview

data class TurnAnalysis(
    val review: Review,
    val vocab: List<VocabCard>,
)

/**
 * 异步文本分析：语法分、母语者改写、语境生词。与主音频链路隔离，不阻塞回合。
 */
interface TurnAnalyzer {
    suspend fun analyze(text: String, turnId: String): TurnAnalysis?
}

/**
 * 音素级发音评测的接口层。未配置供应商时返回 null，界面据此不渲染发音分。
 */
interface PronunciationAssessor {
    val configured: Boolean
    suspend fun assess(pcm: ByteArray, referenceText: String?): PronunciationReview?
}

class UnconfiguredAssessor : PronunciationAssessor {
    override val configured = false
    override suspend fun assess(pcm: ByteArray, referenceText: String?): PronunciationReview? = null
}

internal fun rewriteOf(register: Register, text: String?, gloss: String?): Rewrite? {
    if (text.isNullOrBlank()) return null
    return Rewrite(register = register, text = text, gloss = gloss.orEmpty())
}

internal fun reviewOf(
    original: String,
    score: Int,
    note: String,
    rewrites: List<Rewrite>,
): Review = Review(
    original = original,
    grammar = GrammarReview(score = score.coerceIn(0, 100), note = note),
    pronunciation = null,
    rewrites = rewrites,
)
