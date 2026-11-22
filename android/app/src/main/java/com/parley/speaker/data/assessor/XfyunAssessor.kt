package com.parley.speaker.data.assessor

import android.util.Log
import com.parley.speaker.domain.PhonemeIssue
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Base64
import java.util.Locale
import java.util.concurrent.TimeUnit
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener

/**
 * 科大讯飞语音评测流式版。只提供 WebSocket，一段音频分三段推进：
 * 参数帧带原文本、音频帧分批送、末帧置结束位，等结果帧回来再解析。
 * 朗读型评测必须给定文本，没有原文本就不发起，自由交流的场景交给 Azure 的无文本模式。
 * locale 只喂给 Azure，这里的语种按 en_vip 固定，将来要评中文再换成中文的题型标识。
 */
class XfyunAssessor(
    private val credentials: XfyunCredentials,
) : SpeechAssessor {

    override val requiresReferenceText = true
    override val maxAudioMillis = MAX_AUDIO_MILLIS

    private val client = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()

    override suspend fun assess(pcm16k: ByteArray, referenceText: String?, locale: String): AssessResult? =
        withContext(Dispatchers.IO) {
            val text = referenceText?.trim().orEmpty()
            if (!credentials.isComplete || text.isBlank() || pcm16k.isEmpty()) return@withContext null
            val result = CompletableDeferred<String?>()
            val request = Request.Builder().url(xfyunUrl(credentials, xfyunDate())).build()
            val socket = client.newWebSocket(request, listenerOf(result))
            try {
                socket.send(xfyunFirstFrame(text).toString())
                sendAudio(socket, pcm16k.pcmSlice(MAX_AUDIO_MILLIS))
                val body = withTimeoutOrNull(SESSION_TIMEOUT_MS) { result.await() } ?: return@withContext null
                parseXfyunResult(body)
            } catch (e: Exception) {
                Log.w(TAG, "assess failed: ${e.message}")
                null
            } finally {
                socket.close(1000, null)
            }
        }

    /** 文档要求按实时速率送帧，压得太快会被判非法，因此 40 毫秒一帧。 */
    private suspend fun sendAudio(socket: WebSocket, pcm: ByteArray) {
        for ((status, chunk) in xfyunAudioChunks(pcm)) {
            socket.send(xfyunAudioFrame(chunk, status).toString())
            if (status == XFYUN_STATUS_MIDDLE) delay(FRAME_INTERVAL_MS)
        }
    }

    private fun listenerOf(result: CompletableDeferred<String?>) = object : WebSocketListener() {
        override fun onMessage(webSocket: WebSocket, text: String) {
            val code = jsonOf(text)?.int("code")
            when {
                code != null && code != 0 -> {
                    Log.w(TAG, "ise $code ${jsonOf(text)?.string("message").orEmpty().take(120)}")
                    result.complete(null)
                }
                frameStatus(text) == XFYUN_STATUS_LAST -> result.complete(text)
            }
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            Log.w(TAG, "socket failure: ${t.message}")
            result.complete(null)
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            result.complete(null)
        }
    }

    private companion object {
        const val TAG = "XfyunAssessor"
        const val MAX_AUDIO_MILLIS = 5 * 60 * 1000
        const val FRAME_INTERVAL_MS = 40L
        const val SESSION_TIMEOUT_MS = 6 * 60 * 1000L
    }
}

private const val XFYUN_HOST = "ise-api.xfyun.cn"
private const val XFYUN_PATH = "/v2/open-ise"
private const val XFYUN_STATUS_FIRST = 0
private const val XFYUN_STATUS_MIDDLE = 1
private const val XFYUN_STATUS_LAST = 2
private const val XFYUN_FRAME_BYTES = 1280

private val XFYUN_DATE = DateTimeFormatter.ofPattern("EEE, dd MMM yyyy HH:mm:ss 'GMT'", Locale.US)

internal fun xfyunDate(now: Instant = Instant.now()): String = XFYUN_DATE.format(now.atZone(ZoneOffset.UTC))

/** 握手签名：host、date、request-line 三行原样拼起来做 HMAC-SHA256，再 Base64。 */
internal fun xfyunSignature(apiSecret: String, date: String): String {
    val origin = "host: $XFYUN_HOST\ndate: $date\nGET $XFYUN_PATH HTTP/1.1"
    val mac = Mac.getInstance("HmacSHA256")
    mac.init(SecretKeySpec(apiSecret.toByteArray(Charsets.UTF_8), "HmacSHA256"))
    return Base64.getEncoder().encodeToString(mac.doFinal(origin.toByteArray(Charsets.UTF_8)))
}

internal fun xfyunAuthorization(apiKey: String, signature: String): String {
    val origin = """api_key="$apiKey", algorithm="hmac-sha256", headers="host date request-line", signature="$signature""""
    return Base64.getEncoder().encodeToString(origin.toByteArray(Charsets.UTF_8))
}

// HttpUrl 不接受 wss，签名只用到 host 与 path，因此先把查询串拼成字符串再交给 Request。
internal fun xfyunUrl(credentials: XfyunCredentials, date: String): String {
    val authorization = xfyunAuthorization(
        credentials.apiKey,
        xfyunSignature(credentials.apiSecret, date),
    )
    return "wss://$XFYUN_HOST$XFYUN_PATH?authorization=$authorization&date=$date&host=$XFYUN_HOST"
}

/**
 * 首帧：en_vip 配 read_sentence 是英文字词句的朗读题型，原文本带 BOM 头直接放进 business，
 * 再把 ttp_skip 打开跳过独立文本上传那一段，于是整个会话只有参数帧与音频帧两段。
 * aus 与 extra_ability 的取值未在本仓库任何文档里核对过，按讯飞 demo 的写法先固定，拿到真实返回体后再校。
 */
internal fun xfyunFirstFrame(text: String): JsonObject = buildJsonObject {
    putJsonObject("common") { put("cmd", "ssb") }
    putJsonObject("business") {
        put("sub", "ise")
        put("ent", "en_vip")
        put("category", "read_sentence")
        put("cmd", "ssb")
        put("auf", "audio/L16;rate=16000")
        put("aue", "raw")
        put("aus", 1)
        put("text", "\uFEFF$text")
        put("ttp_skip", true)
        put("extra_ability", "syll_phone_err_msg")
    }
    putJsonObject("data") {
        put("status", XFYUN_STATUS_FIRST)
        put("data", "")
    }
}

/** 帧状态分首中末：末帧带最后一块音频并置 2，中间帧一律置 1。 */
internal fun xfyunAudioChunks(pcm: ByteArray, frameBytes: Int = XFYUN_FRAME_BYTES): Sequence<Pair<Int, ByteArray>> =
    sequence {
        if (pcm.isEmpty()) return@sequence
        var offset = 0
        while (offset < pcm.size) {
            val end = minOf(offset + frameBytes, pcm.size)
            yield((if (end == pcm.size) XFYUN_STATUS_LAST else XFYUN_STATUS_MIDDLE) to pcm.copyOfRange(offset, end))
            offset = end
        }
    }

internal fun xfyunAudioFrame(chunk: ByteArray, status: Int): JsonObject = buildJsonObject {
    putJsonObject("business") {
        put("cmd", "auw")
        put("aus", 1)
        put("aue", "raw")
    }
    putJsonObject("data") {
        put("status", status)
        put("data", Base64.getEncoder().encodeToString(chunk))
    }
}

internal fun parseXfyunResult(raw: String): AssessResult? {
    val root = jsonOf(raw) ?: return null
    val code = root.int("code")
    if (code != null && code != 0) return null
    val outer = root.obj("data") ?: return null
    val inner = outer.obj("data") ?: outer.string("data")?.let(::decodeBase64Json) ?: outer
    val sentence = inner.obj("read_sentence") ?: inner

    val accuracy = sentence.double("accuracy_score")
    val fluency = sentence.double("fluency_score")
    val standard = sentence.double("standard_score")
    val integrity = sentence.double("integrity_score")

    return AssessResult(
        accuracy = accuracy,
        fluency = fluency,
        prosody = null,
        completeness = integrity,
        total = sentence.double("total_score") ?: weightedTotal(accuracy, fluency, standard, integrity),
        issues = issuesOf(sentence),
    )
}

/**
 * 文档给的总分规则是准确度零点六、流利度零点三、标准度零点一加权，再乘完整性系数。
 * 返回体自带 total_score 时以它为准，缺了才照这条规则算。
 * 标准度没有对应槽位，不进 AssessResult，界面要展示得另开字段。
 */
private fun weightedTotal(accuracy: Double?, fluency: Double?, standard: Double?, integrity: Double?): Double? {
    if (accuracy == null || fluency == null || standard == null) return null
    val coefficient = (integrity ?: 100.0) / 100.0
    return (accuracy * 0.6 + fluency * 0.3 + standard * 0.1) * coefficient
}

/**
 * 音素偏误挂在 read_sentence 的逐词逐音素节点上。dp_message 非零表示这个音素读得不对，
 * 但它各取值的具体含义未核对，先统一标成音素偏误，不做漏读误读的猜测归类。
 */
private fun issuesOf(sentence: JsonObject): List<PhonemeIssue> =
    sentence.array("word").orEmpty().flatMap { element ->
        val word = element as? JsonObject ?: return@flatMap emptyList()
        val wordText = word.string("content").orEmpty()
        word.array("phone").orEmpty().mapNotNull { phoneElement ->
            val phone = phoneElement as? JsonObject ?: return@mapNotNull null
            if ((phone.int("dp_message") ?: 0) == 0) return@mapNotNull null
            PhonemeIssue(
                word = wordText,
                phonetic = "/" + phone.string("content").orEmpty() + "/",
                note = "音素偏误",
            )
        }
    }

private fun frameStatus(raw: String): Int? =
    jsonOf(raw)?.obj("data")?.int("status")

private fun decodeBase64Json(encoded: String): JsonObject? = runCatching {
    jsonOf(String(Base64.getDecoder().decode(encoded), Charsets.UTF_8))
}.getOrNull()

