package com.parley.speaker.data.assessor

import com.parley.speaker.domain.PhonemeIssue

/**
 * 发音评测适配层。微软 Azure 与讯飞都落在这个接口后面，界面与 ViewModel 只认它。
 * 分数一律可空：不同供应商给的维度不一样，缺的那项留 null，界面据此少画一格，不做零分顶替。
 */
interface SpeechAssessor {

    /** 讯飞属于必须给定文本的朗读型评测，Azure 允许无文本。没原文时界面靠这个字段决定要不要发起评测。 */
    val requiresReferenceText: Boolean

    /** 单次送评的音频上限，适配器内部按它截断，超出的尾部丢弃。 */
    val maxAudioMillis: Int

    suspend fun assess(pcm16k: ByteArray, referenceText: String?, locale: String): AssessResult?
}

/** 16 kHz 单声道 16 位，一毫秒 32 字节。 */
internal const val PCM_BYTES_PER_MILLI = 32

internal fun ByteArray.pcmSlice(maxMillis: Int): ByteArray {
    val limit = PCM_BYTES_PER_MILLI * maxMillis
    return if (size <= limit) this else copyOf(limit)
}

data class AssessResult(
    val accuracy: Double? = null,
    val fluency: Double? = null,
    val prosody: Double? = null,
    val completeness: Double? = null,
    val total: Double? = null,
    val issues: List<PhonemeIssue> = emptyList(),
)

/** 讯飞的鉴权只用 apiKey 与 apiSecret 两件套，appId 收在这里是为了配置界面三件套齐全与日后排查。 */
data class XfyunCredentials(
    val appId: String = "",
    val apiKey: String = "",
    val apiSecret: String = "",
) {
    val isComplete: Boolean get() = apiKey.isNotBlank() && apiSecret.isNotBlank()
}
