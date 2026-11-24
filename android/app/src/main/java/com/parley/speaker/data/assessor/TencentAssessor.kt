package com.parley.speaker.data.assessor

import android.util.Log
import com.parley.speaker.domain.PhonemeIssue
import java.net.URLEncoder
import java.util.Base64
import java.util.Locale
import java.util.UUID
import java.util.concurrent.TimeUnit
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString.Companion.toByteString

/**
 * 腾讯云智聆口语评测（新版）。一条 WebSocket 就是一次评测，凭据是 AppID、SecretID、SecretKey 三件，
 * 签名走 HMAC-SHA1 塞在 query 里，不是云 API 的 TC3。音频按录音模式一次性送完，收尾发一条 end 文本帧。
 * 自由说模式不要求原文本，所以这里不像讯飞那样必须先有原文：有原文按句子或段落评，没有就按自由说评。
 */
class TencentAssessor(
    private val credentials: TencentCredentials,
) : SpeechAssessor {

    override val requiresReferenceText = false
    override val maxAudioMillis = MAX_AUDIO_MILLIS

    private val client = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(90, TimeUnit.SECONDS)
        .build()

    override suspend fun assess(pcm16k: ByteArray, referenceText: String?, locale: String): AssessOutcome =
        withContext(Dispatchers.IO) {
            if (!credentials.isComplete) return@withContext AssessOutcome.Failed(AssessFailure.Key)
            val pcm = pcm16k.pcmSlice(MAX_AUDIO_MILLIS)
            if (pcm.isEmpty()) return@withContext AssessOutcome.Failed(AssessFailure.Audio)
            val outcome = CompletableDeferred<AssessOutcome>()
            val params = tencentParams(referenceText)
            val url = tencentUrl(credentials, params)
            val wantsIpa = params["ref_text"]?.startsWith(TENCENT_IPA_PREFIX) == true
            val socket = client.newWebSocket(Request.Builder().url(url).build(), listenerOf(outcome, wantsIpa))
            try {
                sendAudio(socket, pcm)
                socket.send(TENCENT_END_FRAME)
                withTimeoutOrNull(SESSION_TIMEOUT_MS) { outcome.await() }
                    ?: AssessOutcome.Failed(AssessFailure.Service)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "assess failed: ${e.message}")
                AssessOutcome.Failed(AssessFailure.Offline)
            } finally {
                socket.close(1000, null)
            }
        }

    /**
     * 录音模式一次连接只评一段，音频切成 1280 字节的帧连续送完，最后单独一条文本帧宣告结束。
     * 发送节奏按错误码 4000 给的宽限走：一秒内最多送三秒音频，所以 40 毫秒的帧留 13 毫秒间隔，
     * 既不触发超速判定，又比边说边评的实时速率快三倍。
     */
    private suspend fun sendAudio(socket: WebSocket, pcm: ByteArray) {
        var offset = 0
        while (offset < pcm.size) {
            val end = minOf(offset + FRAME_BYTES, pcm.size)
            socket.send(pcm.copyOfRange(offset, end).toByteString())
            offset = end
            if (offset < pcm.size) delay(FRAME_INTERVAL_MS)
        }
    }

    private fun listenerOf(outcome: CompletableDeferred<AssessOutcome>, wantsIpa: Boolean) = object : WebSocketListener() {
        override fun onMessage(webSocket: WebSocket, text: String) {
            val body = jsonOf(text)
            val code = body?.int("code")
            when {
                code != null && code != 0 -> {
                    Log.w(TAG, "soe $code ${body.string("message").orEmpty().take(120)}")
                    outcome.complete(AssessOutcome.Failed(tencentFailure(code)))
                }
                body?.int("final") == 1 || body?.obj("result") != null ->
                    outcome.complete(tencentOutcome(text, wantsIpa))
            }
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            Log.w(TAG, "socket failure: ${t.message} ${response?.code}")
            outcome.complete(AssessOutcome.Failed(AssessFailure.Offline))
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            outcome.complete(AssessOutcome.Failed(AssessFailure.Offline))
        }
    }

    private companion object {
        const val TAG = "TencentAssessor"

        /** 录音模式单条连接只收 60 秒，超出的尾部在适配器里先截掉。 */
        const val MAX_AUDIO_MILLIS = 60 * 1000
        const val FRAME_INTERVAL_MS = 13L
        const val SESSION_TIMEOUT_MS = 2 * 60 * 1000L
    }
}

private const val TENCENT_HOST = "soe.cloud.tencent.com"
private const val TENCENT_PATH = "/soe/api/"
private const val FRAME_BYTES = 1280
private const val TENCENT_END_FRAME = """{"type":"end"}"""

/** 严苛度落在儿童 1.0 与考试 4.0 之间偏轻的一档，自用练习按成年人取中位偏轻，不按考试卡分。 */
private const val SCORE_COEFF = 2.5

/** 句子模式上限 30 词，段落模式 120 词，超出的部分服务端会整段拒掉。 */
private const val SENTENCE_WORDS = 30
private const val PARAGRAPH_WORDS = 120

/** 音素分低于这条线算读错，界面每个偏误词条只列一个音素。 */
private const val PHONEME_FLOOR = 60.0

/**
 * 评测请求的全部 query 参数。有原文按词数在句子与段落之间选，没有原文走自由说；
 * 自由说与段落都不支持音素到国际音标转换，只有句子模式把 F_IPA 拼进参考文本。
 */
internal fun tencentParams(referenceText: String?): Map<String, String> {
    val text = tencentRefText(referenceText)
    val mode = when {
        text.isBlank() -> TENCENT_MODE_FREE
        text.wordCount() <= SENTENCE_WORDS -> TENCENT_MODE_SENTENCE
        else -> TENCENT_MODE_PARAGRAPH
    }
    val params = linkedMapOf(
        "eval_mode" to mode.toString(),
        "server_engine_type" to TENCENT_ENGINE_EN,
        "voice_format" to "0",
        "score_coeff" to SCORE_COEFF.toString(),
        "rec_mode" to "1",
        "sentence_info_enabled" to "0",
        "voice_id" to UUID.randomUUID().toString(),
        "text_mode" to "0",
    )
    val body = if (mode == TENCENT_MODE_FREE) text else text.takeWords(if (mode == TENCENT_MODE_SENTENCE) SENTENCE_WORDS else PARAGRAPH_WORDS)
    if (body.isNotBlank()) {
        params["ref_text"] = if (mode == TENCENT_MODE_SENTENCE) "$TENCENT_IPA_PREFIX$body" else body
    }
    return params
}

/**
 * 参考文本先过一遍清洗：换行与连续空白压成单个空格，花括号、竖线、@ 这类在评测文本里带语法含义的字符删掉，
 * 免得用户说出来的句子把参数解析带偏；含汉字时整段清空，引擎只认英文，带着汉字过去会被 4115 撂掉。
 */
internal fun tencentRefText(referenceText: String?): String {
    val raw = referenceText?.trim().orEmpty()
    if (raw.isEmpty() || raw.any(::isHanCharacter)) return ""
    return raw
        .replace(Regex("""\{::[^}]*\}\}"""), " ")
        .filterNot { it in "{}|@" }
        .replace(Regex("""\s+"""), " ")
        .trim()
}

private fun isHanCharacter(character: Char): Boolean =
    character.code in 0x4E00..0x9FFF ||
        character.code in 0x3400..0x4DBF ||
        character.code in 0xF900..0xFAFF

internal fun String.wordCount(): Int = split(' ').count { it.isNotBlank() }

internal fun String.takeWords(limit: Int): String =
    split(' ').filter { it.isNotBlank() }.take(limit).joinToString(" ")

/**
 * 签名原串：除 signature 外的参数按字典序拼成 key=value，前面接 host 加路径，不带协议头。
 * 值在签名原串里保持原文，urlencode 只发生在真正拼进请求地址的时候。
 */
internal fun tencentSignOrigin(appId: String, params: Map<String, String>, host: String = TENCENT_HOST): String {
    val query = params.entries
        .sortedBy { it.key }
        .filter { it.key != "signature" }
        .joinToString("&") { "${it.key}=${it.value}" }
    return "$host$TENCENT_PATH$appId?$query"
}

internal fun tencentSignature(secretKey: String, origin: String): String {
    val mac = Mac.getInstance("HmacSHA1")
    mac.init(SecretKeySpec(secretKey.toByteArray(Charsets.UTF_8), "HmacSHA1"))
    return Base64.getEncoder().encodeToString(mac.doFinal(origin.toByteArray(Charsets.UTF_8)))
}

/**
 * 完整连接地址。签名只认 secretid 与 secretkey，appid 走路径段；过期时间给到九十天上限之内的一小时，
 * 单次评测用完即断，不需要更长的有效期。
 */
internal fun tencentUrl(credentials: TencentCredentials, params: Map<String, String>, nowSeconds: Long = System.currentTimeMillis() / 1000): String {
    val timestamp = nowSeconds - 60
    val signed = params + mapOf(
        "secretid" to credentials.secretId,
        "timestamp" to timestamp.toString(),
        "expired" to (timestamp + 3600).toString(),
        "nonce" to (1000..9999999).random().toString(),
    )
    val signature = tencentSignature(credentials.secretKey, tencentSignOrigin(credentials.appId, signed))
    val query = signed.entries
        .sortedBy { it.key }
        .joinToString("&") { "${it.key}=${it.value.encodeQueryValue()}" }
    return "wss://$TENCENT_HOST$TENCENT_PATH${credentials.appId}?$query&signature=${signature.encodeQueryValue()}"
}

/** 空格写成 %20 而不是 +，加号与等号必须转义，文档专门点过这两处，转漏了会偶现鉴权失败。 */
internal fun String.encodeQueryValue(): String =
    URLEncoder.encode(this, "UTF-8").replace("+", "%20")

/**
 * 错误码归类照官方错误码表：鉴权与未开通归密钥，额度与欠费归额度，并发超限归忙，
 * 音频与参考文本类的错重录才有结果，连接断与引擎负载归服务异常。码与原文只进日志。
 */
internal fun tencentFailure(code: Int): AssessFailure = when (code) {
    4002, 4003 -> AssessFailure.Key
    4004, 4005 -> AssessFailure.Quota
    4006 -> AssessFailure.Busy
    4000, 4001, 4007, 4011, 4014, 4102, 4103, 4104, 4105, 4107, 4108, 4110, 4111, 4112, 4113, 4114, 4115 ->
        AssessFailure.Audio
    4008, 4009 -> AssessFailure.Offline
    else -> AssessFailure.Service
}

internal fun tencentOutcome(body: String, withPhonemes: Boolean = true): AssessOutcome {
    val result = parseTencentResult(body, withPhonemes) ?: return AssessOutcome.Failed(AssessFailure.Service)
    return if (result.total != null || result.accuracy != null) {
        AssessOutcome.Scored(result)
    } else {
        AssessOutcome.Failed(AssessFailure.Service)
    }
}

/**
 * 返回体有两种形状：新版把 result 给成对象，老版给成一段没有引号的字符串。
 * 对象形态才带 Words，音素偏误只从对象形态里取；字符串形态只捞顶层四维分。
 * PronAccuracy 是百分制，PronFluency 与 PronCompletion 是零点到一，量纲在适配层统一成百分制，
 * -1 是服务端表示这一维没有值的写法，转成 null 交给界面少画一格。
 */
internal fun parseTencentResult(body: String, withPhonemes: Boolean = true): AssessResult? {
    val root = jsonOf(body) ?: return null
    val result = when (val node = root["result"]) {
        is JsonObject -> node
        is JsonPrimitive -> looseTencentScores(node.contentOrNull.orEmpty())
        else -> null
    } ?: return null

    val accuracy = result.score("PronAccuracy")
    val total = result.score("SuggestedScore") ?: accuracy
    return AssessResult(
        accuracy = accuracy,
        fluency = result.ratio("PronFluency"),
        prosody = null,
        completeness = result.ratio("PronCompletion"),
        total = total,
        issues = if (withPhonemes) tencentIssues(result) else emptyList(),
    )
}

private fun JsonObject.score(key: String): Double? =
    double(key)?.takeIf { it >= 0 }?.coerceAtMost(100.0)

private fun JsonObject.ratio(key: String): Double? =
    double(key)?.takeIf { it >= 0 }?.let { (it * 100).coerceAtMost(100.0) }

/** 老版字符串形态只保证顶层四维分能读出来，字段名与数字之间用冒号连，值可能是负数。 */
internal fun looseTencentScores(raw: String): JsonObject? {
    if (raw.isBlank()) return null
    val body = buildJsonObject {
        LOOSE_FIELDS.forEach { field ->
            Regex("""$field\s*:\s*(-?\d+(?:\.\d+)?)""").find(raw)?.groupValues?.get(1)?.let { put(field, it.toDouble()) }
        }
    }
    return body.takeIf { it.isNotEmpty() }
}

private val LOOSE_FIELDS = listOf("SuggestedScore", "PronAccuracy", "PronFluency", "PronCompletion")

/**
 * 音素偏误按词归集：某个音素分低于六十就记一条，标注取该音素本身。
 * 只有句子模式开了 F_IPA，音素才是国际音标；段落与自由说返回的是智聆内部标记，
 * 量纲对不上就不往界面送，字节层把 withPhonemes 置 false 走这条分支。
 * MatchTag 为 5 的词是标点，不算发音问题。
 */
private fun tencentIssues(result: JsonObject): List<PhonemeIssue> {
    val words = result["Words"] as? JsonArray ?: return emptyList()
    return words.mapNotNull { it as? JsonObject }
        .filter { it.int("MatchTag") != 5 }
        .flatMap { word ->
            val text = word.string("Word").orEmpty()
            val phones = word["PhoneInfos"] as? JsonArray ?: return@flatMap emptyList()
            phones.mapNotNull { it as? JsonObject }
                .filter { (it.double("PronAccuracy") ?: 100.0) < PHONEME_FLOOR }
                .filter { it.string("ReferencePhone").isNullOrBlank() }
                .map { phone ->
                    PhonemeIssue(
                        word = text,
                        phonetic = "/" + phone.string("Phone").orEmpty() + "/",
                        note = "音素偏误",
                    )
                }
        }
        .take(6)
}

private const val TENCENT_ENGINE_EN = "16k_en"
private const val TENCENT_MODE_SENTENCE = 1
private const val TENCENT_MODE_PARAGRAPH = 2
private const val TENCENT_MODE_FREE = 3
private const val TENCENT_IPA_PREFIX = "{::cmd{F_IPA=true}}"
