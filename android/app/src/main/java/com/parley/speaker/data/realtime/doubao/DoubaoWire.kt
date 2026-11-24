package com.parley.speaker.data.realtime.doubao

import android.util.Log
import com.parley.speaker.data.realtime.ProviderFault
import com.parley.speaker.data.realtime.RealtimeEvent
import java.util.Base64
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/** 后台送来的一条东西。取不到文字的帧一律归为 Ignored，不往上抛半条消息。 */
internal sealed interface DoubaoInbound {
    /** 带响应号的那几类能精确判出迟到的内容，取消之后靠它把旧的一轮筛出来。 */
    sealed interface WithResponse : DoubaoInbound {
        val responseId: String?
    }

    data class Created(val sessionId: String) : DoubaoInbound
    data class UserText(val text: String) : DoubaoInbound
    data class AssistantText(val text: String, override val responseId: String? = null) : WithResponse
    data class Audio(val pcm: ByteArray, override val responseId: String? = null) : WithResponse
    data object Committed : DoubaoInbound
    data object Canceled : DoubaoInbound
    data class TurnComplete(override val responseId: String? = null) : WithResponse
    data object Closed : DoubaoInbound
    data class Failed(val message: String) : DoubaoInbound
    data object Ignored : DoubaoInbound
}

internal fun DoubaoInbound.event(): RealtimeEvent? = when (this) {
    is DoubaoInbound.Created -> RealtimeEvent.Ready
    is DoubaoInbound.UserText -> RealtimeEvent.UserText(text)
    is DoubaoInbound.AssistantText -> RealtimeEvent.AssistantText(text)
    is DoubaoInbound.Audio -> RealtimeEvent.Audio(pcm)
    is DoubaoInbound.TurnComplete -> RealtimeEvent.TurnComplete
    is DoubaoInbound.Failed -> RealtimeEvent.Failed(message)
    // 提交回执、打断回执与整场结束都由会话层自己消化，界面不认识它们。
    DoubaoInbound.Committed, DoubaoInbound.Canceled, DoubaoInbound.Closed, DoubaoInbound.Ignored -> null
}

/** 模型开口的那几类。按住时这些到了就是服务端自己判停抢话，转写不算。 */
internal fun DoubaoInbound.isModelTalk(): Boolean = when (this) {
    is DoubaoInbound.Audio, is DoubaoInbound.AssistantText, is DoubaoInbound.TurnComplete -> true
    else -> false
}

/**
 * 豆包实时语音 3.0（Seeduplex）的报文。整条链路是 JSON 文本帧，没有二进制帧头：
 * 上行 session.create 建会话，input_audio_buffer.append 送 Base64 音频，
 * input_audio_buffer.commit 与 input_audio_mute.commit 一起收口（两条都要，只发前者不出字也不作答），
 * 再开口前发 input_audio_unmute.commit，response.cancel 打断，session.close 收场。
 *
 * 下行按事件名分派，音频在 response.output_audio.delta 的 delta 字段里，Base64 编码。
 * 转写只在 completed 时取一次，整句在 text 字段里：界面那边的气泡文本是追加语义，
 * 而这张服务的 delta 每一条都带当前整句，逐字回填会把同一句叠上几遍。模型文本反过来取 delta。
 */
internal object DoubaoWire {

    const val ENDPOINT = "wss://openspeech.bytedance.com/api/v3/duplex/realtime/dialogue"

    /** 协议里必传的模型版本，取值只有这一个。 */
    const val MODEL = "1.2.6.1"

    const val UPLINK_RATE = 16_000
    const val DOWNLINK_RATE = 24_000

    /** 下行默认的 pcm 是三十二位小端，播放链路读十六位，必须在 setup 里点名这一档。 */
    const val DOWNLINK_FORMAT = "pcm_s16le"

    private val json = Json { ignoreUnknownKeys = true }

    /**
     * [context] 是 user/assistant 成对的正文，走 extension.dialog.dialog_context。
     * 官方只在开会话这一刻读它；塞进 instructions 模型当系统提示，当不成对话历史。
     * 审核关掉是因为练习允许中英混说，默认严格审核会把混说拦下来。
     */
    fun sessionCreate(
        sessionId: String,
        voice: String,
        instructions: String,
        context: List<Pair<String, String>> = emptyList(),
    ): String = buildJsonObject {
        put("type", "session.create")
        putJsonObject("session") {
            put("id", sessionId)
            put("model", MODEL)
            if (instructions.isNotBlank()) put("instructions", instructions)
            putJsonObject("audio") {
                putJsonObject("input") {
                    putJsonObject("format") {
                        put("type", "pcm")
                        put("rate", UPLINK_RATE)
                    }
                }
                putJsonObject("output") {
                    putJsonObject("format") {
                        put("type", DOWNLINK_FORMAT)
                        put("rate", DOWNLINK_RATE)
                    }
                    put("voice", voice)
                }
            }
            putJsonObject("extension") {
                putJsonObject("dialog") {
                    put("strict_audit", false)
                    if (context.isNotEmpty()) {
                        putJsonArray("dialog_context") {
                            context.forEach { (role, text) ->
                                add(
                                    buildJsonObject {
                                        put("role", role)
                                        put("text", text)
                                    },
                                )
                            }
                        }
                    }
                }
            }
        }
    }.toString()

    fun audioAppend(pcm: ByteArray): String = buildJsonObject {
        put("type", "input_audio_buffer.append")
        put("audio", Base64.getEncoder().encodeToString(pcm))
    }.toString()

    /** 强制判停：确认这一段说完。只发这一条服务端不问也不答，实测二十二秒没有任何响应。 */
    fun commit(): String = buildJsonObject { put("type", "input_audio_buffer.commit") }.toString()

    /**
     * 关麦通知。模型靠上行音频流保活，说完停掉麦克风就必须发这一条，
     * 服务端收到它才把这一轮收口送去生成；与 commit 是同一步里的两条，缺了它整轮沉底。
     */
    fun audioMute(): String = buildJsonObject { put("type", "input_audio_mute.commit") }.toString()

    /** 再次开口前报一声。不发的话服务端仍当麦克风关着，上行帧被忽略，识别不会出字。 */
    fun audioUnmute(): String = buildJsonObject { put("type", "input_audio_unmute.commit") }.toString()

    fun cancel(): String = buildJsonObject { put("type", "response.cancel") }.toString()

    fun sessionClose(): String = buildJsonObject { put("type", "session.close") }.toString()

    fun parse(text: String): DoubaoInbound {
        val root = try {
            json.parseToJsonElement(text).jsonObject
        } catch (e: Exception) {
            Log.w("DoubaoWire", "非 JSON 的文本帧：${text.take(200)}")
            return DoubaoInbound.Ignored
        }
        return when (root["type"]?.jsonPrimitive?.contentOrNull) {
            "session.created" -> DoubaoInbound.Created(
                root["session"]?.let { runCatching { it.jsonObject["id"]?.jsonPrimitive?.contentOrNull }.getOrNull() }.orEmpty(),
            )

            "conversation.item.input_audio_transcription.completed" ->
                (textOf(root, "text") ?: textOf(root, "transcript"))
                    ?.let { DoubaoInbound.UserText(it) } ?: DoubaoInbound.Ignored

            "response.output_text.delta" ->
                textOf(root, "delta")?.let { DoubaoInbound.AssistantText(it, responseIdOf(root)) }
                    ?: DoubaoInbound.Ignored

            "response.output_audio.delta" ->
                textOf(root, "delta")?.let { decode(it) }?.let { DoubaoInbound.Audio(it, responseIdOf(root)) }
                    ?: DoubaoInbound.Ignored

            "response.output_audio.done" -> DoubaoInbound.TurnComplete(responseIdOf(root))
            "input_audio_buffer.committed" -> DoubaoInbound.Committed
            "response.canceled" -> DoubaoInbound.Canceled
            "session.closed" -> DoubaoInbound.Closed
            "error" -> DoubaoInbound.Failed(faultOf(root))
            else -> DoubaoInbound.Ignored
        }
    }

    private fun responseIdOf(root: JsonObject): String? =
        textOf(root, "response_id")

    private fun textOf(root: JsonObject, key: String): String? =
        root[key]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotEmpty() }

    private fun decode(base64: String): ByteArray? =
        runCatching { Base64.getDecoder().decode(base64) }.getOrNull()?.takeIf { it.isNotEmpty() }

    /** 服务端的错误原文只进日志，界面拿到的是归类之后那一句，认不出类别就是空串。 */
    private fun faultOf(root: JsonObject): String {
        val error = root["error"]
        val code = error?.let { runCatching { it.jsonObject["code"]?.jsonPrimitive?.contentOrNull }.getOrNull() }
        val said = error?.let { runCatching { it.jsonObject["message"]?.jsonPrimitive?.contentOrNull }.getOrNull() }
        Log.w("DoubaoWire", "服务端报错 code=$code ${said?.take(200)}")
        return ProviderFault.notice(code, said)
    }
}
