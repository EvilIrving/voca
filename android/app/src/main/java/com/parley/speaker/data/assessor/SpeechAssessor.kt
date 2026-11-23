package com.parley.speaker.data.assessor

import com.parley.speaker.data.realtime.ProviderFault
import com.parley.speaker.domain.PhonemeIssue

/**
 * 发音评测适配层。微软 Azure 与讯飞都落在这个接口后面，界面与 ViewModel 只认它。
 * 分数一律可空：不同供应商给的维度不一样，缺的那项留 null，界面据此少画一格，不做零分顶替。
 * 拿不到分也走这个接口回来，带着归类好的原因，界面据此说一句话，不用再从 null 里猜。
 */
interface SpeechAssessor {

    /** 讯飞属于必须给定文本的朗读型评测，Azure 允许无文本。没原文时界面靠这个字段决定要不要发起评测。 */
    val requiresReferenceText: Boolean

    /** 单次送评的音频上限，适配器内部按它截断，超出的尾部丢弃。 */
    val maxAudioMillis: Int

    suspend fun assess(pcm16k: ByteArray, referenceText: String?, locale: String): AssessOutcome
}

/**
 * 一次送评的两种结局：出了分，或者归类好的失败。
 * 失败只在适配器这一层归，界面不认供应商错误码，也不把原文写进提示。
 */
sealed interface AssessOutcome {

    data class Scored(val result: AssessResult) : AssessOutcome

    data class Failed(val failure: AssessFailure) : AssessOutcome
}

/**
 * 评测拿不到分的归类，每一类带界面那一句。
 * 密钥、额度、限流三类与实时链路共用同一套说法，同一个供应商错误不在两处各写一句；
 * 网络、音频、服务三类是评测独有：多模态链路不看音频能不能评，评测才看。
 */
enum class AssessFailure(val notice: String) {

    Key(ProviderFault.Key),

    Quota(ProviderFault.Quota),

    Busy(ProviderFault.Busy),

    /** 握手没成、连接中断、读取超时，这一轮没送到或没送完。 */
    Offline("评测服务未连通，请检查网络"),

    /** 音频与文本对不上、太短、解码失败，重录才有结果。 */
    Audio("评测失败，请重录"),

    /** 供应商回了个认不出的错误，或者回来的报文里没有分数。 */
    Service("评测服务异常，请稍后重试"),
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

/** 讯飞的握手签名只吃 apiKey 与 apiSecret，但首帧的 common.app_id 服务端必校验，三件缺一不可。 */
data class XfyunCredentials(
    val appId: String = "",
    val apiKey: String = "",
    val apiSecret: String = "",
) {
    val isComplete: Boolean get() = appId.isNotBlank() && apiKey.isNotBlank() && apiSecret.isNotBlank()
}
