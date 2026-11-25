package com.voca.speaker.data.analysis

import com.voca.speaker.domain.PronunciationReview
import com.voca.speaker.domain.Register
import com.voca.speaker.domain.Review
import com.voca.speaker.domain.Rewrite
import com.voca.speaker.domain.VocabCard
import com.voca.speaker.domain.GrammarReview

data class TurnAnalysis(
    val review: Review,
    val vocab: List<VocabCard>,
)

/**
 * 异步文本分析：语法分、母语者改写、语境生词。与主音频链路隔离，不阻塞回合。
 * 气泡译文与会话名是同一套端点、鉴权与思考参数的另外两个追问，放在同一个接口上，
 * 调用方拿到的永远是一台按设置页装配好的引擎，不在本地拼端点与密钥。
 */
interface TurnAnalyzer {
    suspend fun analyze(text: String, turnId: String): TurnAnalysis?

    /** 气泡译文：中文释义随轮落库。 */
    suspend fun translate(text: String): String?

    /** 会话名：材料是整段对话的文字流水，返回能指认内容的短语。 */
    suspend fun name(transcript: String): String?

    /**
     * 会话记忆：把这一轮的材料并进现有记忆，返回改写后的整段正文。
     * 记忆是内部机制，界面没有入口，改写规则写在提示词里，用户改的是角色与场景。
     */
    suspend fun memorize(memory: String, material: String): String?
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
