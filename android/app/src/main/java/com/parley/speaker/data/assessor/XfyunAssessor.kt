package com.parley.speaker.data.assessor

import android.util.Log
import com.parley.speaker.domain.PhonemeIssue
import java.io.ByteArrayInputStream
import java.io.IOException
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Base64
import java.util.Locale
import java.util.concurrent.TimeUnit
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import javax.xml.parsers.DocumentBuilderFactory
import kotlinx.coroutines.CancellationException
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
import org.w3c.dom.Element

/**
 * 科大讯飞语音评测流式版。只提供 WebSocket，一段音频分三段推进：
 * 参数帧带原文本、音频帧分批送、末尾单独一帧空音频置结束位，等结果帧回来再解析。
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

    override suspend fun assess(pcm16k: ByteArray, referenceText: String?, locale: String): AssessOutcome =
        withContext(Dispatchers.IO) {
            val text = referenceText?.trim().orEmpty()
            if (!credentials.isComplete) return@withContext AssessOutcome.Failed(AssessFailure.Key)
            if (text.isBlank() || pcm16k.isEmpty()) return@withContext AssessOutcome.Failed(AssessFailure.Audio)
            val outcome = CompletableDeferred<AssessOutcome>()
            val request = Request.Builder().url(xfyunUrl(credentials, xfyunDate())).build()
            val socket = client.newWebSocket(request, listenerOf(outcome))
            try {
                socket.send(xfyunFirstFrame(credentials.appId, text).toString())
                sendAudio(socket, pcm16k.pcmSlice(MAX_AUDIO_MILLIS))
                withTimeoutOrNull(SESSION_TIMEOUT_MS) { outcome.await() }
                    ?: AssessOutcome.Failed(AssessFailure.Service)
            } catch (e: CancellationException) {
                throw e
            } catch (e: IOException) {
                Log.w(TAG, "assess failed: ${e.message}")
                AssessOutcome.Failed(AssessFailure.Offline)
            } catch (e: Exception) {
                Log.w(TAG, "assess failed: ${e.message}")
                AssessOutcome.Failed(AssessFailure.Service)
            } finally {
                socket.close(1000, null)
            }
        }

    /** 文档要求按实时速率送帧，压得太快会被判非法，因此 40 毫秒一帧。 */
    private suspend fun sendAudio(socket: WebSocket, pcm: ByteArray) {
        for (frame in xfyunAudioFrames(pcm)) {
            socket.send(xfyunAudioFrame(frame).toString())
            if (frame.status == XFYUN_STATUS_MIDDLE) delay(FRAME_INTERVAL_MS)
        }
    }

    /**
     * 服务端每一次回话都收敛成一条结局：带错误码的归成类别，末帧带结果就解析，连接掉了按握手状态分。
     * 三条路径都 complete，先到的那条算数，后面重复的静默丢弃。
     */
    private fun listenerOf(outcome: CompletableDeferred<AssessOutcome>) = object : WebSocketListener() {
        override fun onMessage(webSocket: WebSocket, text: String) {
            val code = jsonOf(text)?.int("code")
            when {
                code != null && code != 0 -> {
                    Log.w(TAG, "ise $code ${jsonOf(text)?.string("message").orEmpty().take(120)}")
                    outcome.complete(AssessOutcome.Failed(xfyunFailure(code)))
                }
                frameStatus(text) == XFYUN_STATUS_LAST -> outcome.complete(xfyunOutcome(text))
            }
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            Log.w(TAG, "socket failure: ${t.message} ${response?.code}")
            outcome.complete(AssessOutcome.Failed(xfyunHandshakeFailure(response?.code)))
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            outcome.complete(AssessOutcome.Failed(AssessFailure.Offline))
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
private const val XFYUN_HTTP_UNAUTHORIZED = 401
private const val XFYUN_HTTP_FORBIDDEN = 403
private const val XFYUN_AUS_FIRST = 1
private const val XFYUN_AUS_MIDDLE = 2
private const val XFYUN_AUS_LAST = 4
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
 * 首帧：en_vip 配 read_sentence 是英文字词句的朗读题型，英文试卷要裹在 [content] 节点里，
 * 整段文本前面再补 BOM，ttp_skip 打开后跳过独立文本上传那一段，会话只剩参数帧与音频帧两段。
 * app_id 与 tte、rstcd 都是服务端校验项，缺了整条连接在首帧就被关掉；
 * 音素偏误属于拓展能力，ise_unite 不开时返回体里不给，所以这里连同 rst 一起明确下来。
 */
internal fun xfyunFirstFrame(appId: String, text: String): JsonObject = buildJsonObject {
    putJsonObject("common") { put("app_id", appId) }
    putJsonObject("business") {
        put("sub", "ise")
        put("ent", "en_vip")
        put("category", "read_sentence")
        put("cmd", "ssb")
        put("auf", "audio/L16;rate=16000")
        put("aue", "raw")
        put("tte", "utf-8")
        put("rstcd", "utf8")
        put("text", "\uFEFF[content]\n$text")
        put("ttp_skip", true)
        put("ise_unite", "1")
        put("rst", "entirety")
        put("extra_ability", "syll_phone_err_msg;multi_dimension")
    }
    putJsonObject("data") { put("status", XFYUN_STATUS_FIRST) }
}

/** 一帧音频：首帧标 1、中间帧标 2、收尾帧标 4，收尾帧自身不带音频，只把结束位递给服务端。 */
internal data class XfyunAudioFrame(val aus: Int, val status: Int, val chunk: ByteArray)

/**
 * 帧状态分首中末：音频一律按中间位推进，末位单独走一帧空音频。
 * 首帧与末帧在只有一小段音频时首尾相接，服务端仍按两帧处理。
 */
internal fun xfyunAudioFrames(pcm: ByteArray, frameBytes: Int = XFYUN_FRAME_BYTES): Sequence<XfyunAudioFrame> =
    sequence {
        if (pcm.isEmpty()) return@sequence
        var offset = 0
        var first = true
        while (offset < pcm.size) {
            val end = minOf(offset + frameBytes, pcm.size)
            yield(
                XfyunAudioFrame(
                    aus = if (first) XFYUN_AUS_FIRST else XFYUN_AUS_MIDDLE,
                    status = XFYUN_STATUS_MIDDLE,
                    chunk = pcm.copyOfRange(offset, end),
                ),
            )
            first = false
            offset = end
        }
        yield(XfyunAudioFrame(XFYUN_AUS_LAST, XFYUN_STATUS_LAST, ByteArray(0)))
    }

internal fun xfyunAudioFrame(frame: XfyunAudioFrame): JsonObject = buildJsonObject {
    putJsonObject("business") {
        put("cmd", "auw")
        put("aus", frame.aus)
        put("aue", "raw")
    }
    putJsonObject("data") {
        put("status", frame.status)
        put("data", Base64.getEncoder().encodeToString(frame.chunk))
    }
}

/**
 * 错误码归类照官方错误码表：密钥与授权类、额度类、音频类、读取超时各有归属，
 * 认不出的码一律算服务异常，界面只说一句话，码与原文进日志。
 */
internal fun xfyunFailure(code: Int): AssessFailure = when (code) {
    10313, 11200 -> AssessFailure.Key
    11201, 42306 -> AssessFailure.Quota
    60114, 68676, 40037, 40038, 40007, 10043, 10114 -> AssessFailure.Audio
    10200 -> AssessFailure.Offline
    else -> AssessFailure.Service
}

/** 握手没成时只有 HTTP 状态可看，401 与 403 是密钥或授权，其余当连接没通。 */
internal fun xfyunHandshakeFailure(httpCode: Int?): AssessFailure = when (httpCode) {
    XFYUN_HTTP_UNAUTHORIZED, XFYUN_HTTP_FORBIDDEN -> AssessFailure.Key
    else -> AssessFailure.Offline
}

/** 解析出四项分才算出了结果，报文能读但没有分说明服务端这一轮没评出来。 */
internal fun xfyunOutcome(body: String): AssessOutcome {
    val result = parseXfyunResult(body) ?: return AssessOutcome.Failed(AssessFailure.Service)
    return if (result.total != null || result.accuracy != null) {
        AssessOutcome.Scored(result)
    } else {
        AssessOutcome.Failed(AssessFailure.Service)
    }
}

/**
 * 末帧的 data.data 是 base64 包的 XML，分数与音素偏误都挂在属性上：
 * read_chapter 带四项分与总分，往下 word、syll、phone 逐层带 content 与增漏标记。
 * 英文只有音节这一层给出 serr_msg，音素那一层的 dp_message 主要给中文用，所以偏误按音节归集。
 */
internal fun parseXfyunResult(raw: String): AssessResult? {
    val root = jsonOf(raw) ?: return null
    val code = root.int("code")
    if (code != null && code != 0) return null
    val encoded = root.obj("data")?.string("data") ?: return null
    val document = runCatching {
        DocumentBuilderFactory.newInstance()
            .newDocumentBuilder()
            .parse(ByteArrayInputStream(Base64.getDecoder().decode(encoded)))
    }.getOrNull() ?: return null
    val paper = scoreElement(document.documentElement) ?: return null

    val accuracy = paper.double("accuracy_score")
    val fluency = paper.double("fluency_score")
    val standard = paper.double("standard_score")
    val integrity = paper.double("integrity_score")

    return AssessResult(
        accuracy = accuracy,
        fluency = fluency,
        prosody = null,
        completeness = integrity,
        total = paper.double("total_score") ?: weightedTotal(accuracy, fluency, standard, integrity),
        issues = issuesOf(paper),
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

/** 一项分只挂在带 accuracy_score 或 total_score 的那一层，逐级往里找，外层只当包装。 */
private fun scoreElement(root: Element): Element? =
    listOf("read_word", "read_sentence", "read_chapter", "read_syllable").firstNotNullOfOrNull { tag ->
        root.children(tag).firstOrNull {
            it.attribute("accuracy_score") != null || it.attribute("total_score") != null
        }
    }

/**
 * 增漏标记各取值的含义照文档：16 漏读、32 增读、64 回读、128 替换；
 * 音节另有一个 serr_msg，非零表示这一整个音节读错，不细分声韵还是重音。
 */
private fun issuesOf(paper: Element): List<PhonemeIssue> =
    paper.children("word").flatMap { word ->
        val wordText = word.attribute("content").orEmpty()
        word.children("syll").mapNotNull { syll ->
            val dp = syll.attribute("dp_message")?.toIntOrNull() ?: 0
            val serr = syll.attribute("serr_msg")?.toIntOrNull() ?: 0
            if (dp == 0 && serr == 0) return@mapNotNull null
            PhonemeIssue(
                word = wordText,
                phonetic = "/" + syll.attribute("content").orEmpty() + "/",
                note = noteOf(dp, serr),
            )
        }
    }

private fun noteOf(dp: Int, serr: Int): String = when {
    dp == 16 -> "漏读"
    dp == 32 -> "增读"
    dp == 64 -> "回读"
    dp == 128 -> "替换"
    serr != 0 -> "误读"
    else -> "音素偏误"
}

private fun Element.attribute(name: String): String? = getAttribute(name).takeIf { it.isNotEmpty() }

private fun Element.double(name: String): Double? = attribute(name)?.toDoubleOrNull()

private fun Element.children(tag: String): List<Element> {
    val nodes = getElementsByTagName(tag)
    return (0 until nodes.length).mapNotNull { nodes.item(it) as? Element }
}

private fun frameStatus(raw: String): Int? =
    jsonOf(raw)?.obj("data")?.int("status")


