package com.parley.speaker.data.realtime.doubao

import android.util.Log
import com.parley.speaker.data.realtime.ProviderFault
import com.parley.speaker.data.realtime.RealtimeEvent
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

/**
 * 事件号。文档给的是事件名与先后顺序（StartConnection、StartSession、TaskRequest 到
 * FinishSession、FinishConnection），下面这些数字是照事件表填的，离线状态下没法逐条核对，
 * 真机联调时若哪个事件一动不动，先核对这张表；解析全部按这里取号，改一处就够。
 */
internal object DoubaoEvents {
    // 客户端发出
    const val START_CONNECTION = 1
    const val FINISH_CONNECTION = 2
    const val START_SESSION = 5
    const val FINISH_SESSION = 6
    const val TASK_REQUEST = 100

    // 服务端发出
    const val CONNECTION_STARTED = 50
    const val CONNECTION_FAILED = 51
    const val CONNECTION_FINISHED = 52
    const val SESSION_STARTED = 150
    const val SESSION_FINISHED = 152
    const val SESSION_FAILED = 153
    const val SAY_HELLO = 300
    const val CHAT_RESPONSE = 350
    const val CHAT_ENDED = 359
    const val ASR_INFO = 450
    const val ASR_RESPONSE = 451
    const val ASR_ENDED = 459
    const val TTS_SENTENCE_START = 550
    const val TTS_SENTENCE_END = 551
    const val TTS_RESPONSE = 552
    const val TTS_ENDED = 559

    /** 连接级事件不带会话号，帧里那一段不存在，解码与解析都按这张表判断。 */
    val CONNECTION_LEVEL = setOf(
        START_CONNECTION,
        FINISH_CONNECTION,
        CONNECTION_STARTED,
        CONNECTION_FAILED,
        CONNECTION_FINISHED,
    )
}

/** 一帧解出来之后的样子：要么是流控信号，要么是要交给界面的那几类事件。 */
internal sealed interface DoubaoInbound {
    /** 连接已建立，会话层接着发 StartSession，界面不必知道。 */
    data object ConnectionStarted : DoubaoInbound
    data object SessionStarted : DoubaoInbound
    data object SessionFinished : DoubaoInbound
    data class UserText(val text: String) : DoubaoInbound
    data class AssistantText(val text: String) : DoubaoInbound
    data class Audio(val pcm: ByteArray) : DoubaoInbound
    data object TurnComplete : DoubaoInbound
    data class Failed(val message: String) : DoubaoInbound
    data object Ignored : DoubaoInbound
}

/** 投影到界面认的六类事件，turnId 由会话层补。 */
internal fun DoubaoInbound.event(): RealtimeEvent? = when (this) {
    DoubaoInbound.ConnectionStarted -> null
    DoubaoInbound.Ignored -> null
    DoubaoInbound.SessionStarted -> RealtimeEvent.Ready
    // 整场结束由会话层自己当作断线处理，走不到这里，所以没有对应事件。
    DoubaoInbound.SessionFinished -> null
    DoubaoInbound.TurnComplete -> RealtimeEvent.TurnComplete
    is DoubaoInbound.UserText -> RealtimeEvent.UserText(text)
    is DoubaoInbound.AssistantText -> RealtimeEvent.AssistantText(text)
    is DoubaoInbound.Audio -> RealtimeEvent.Audio(pcm)
    is DoubaoInbound.Failed -> RealtimeEvent.Failed(message)
}

/**
 * 豆包端到端实时语音的报文与载荷。
 *
 * 来自文档的部分：端点、握手头、上行 PCM16 十六千赫兹单声道、下行必须在 StartSession 的
 * tts.audio_config 里指定 pcm 二十四千赫兹十六位才给裸流、音色走 tts.speaker、
 * 手动回合制走 dialog.input_mod 置 push_to_talk 加 enable_custom_vad。
 *
 * 属于推断、要真机核对的：事件号的具体数字、会话号由客户端生成 UUID 并逐帧带上、
 * audio_config 里表示十六位的字段名、push_to_talk 下用来宣告一段音频结束的载荷写法、
 * ASR 与 ChatResponse 载荷里取文本的那两个键、TTS 音频落在裸音频响应帧这一条。
 */
internal object DoubaoWire {

    const val ENDPOINT = "wss://openspeech.bytedance.com/api/v3/realtime/dialogue"
    const val RESOURCE_ID = "volc.speech.dialog"

    const val UPLINK_RATE = 16_000
    const val DOWNLINK_RATE = 24_000
    const val DOWNLINK_BITS = 16

    const val BOT_NAME = "Parley"

    private val json = Json { ignoreUnknownKeys = true }

    fun startConnection(): ByteArray = DoubaoFrameCodec.request(
        messageType = DoubaoProto.MSG_FULL_CLIENT,
        event = DoubaoEvents.START_CONNECTION,
        sessionId = "",
        payload = buildJsonObject { }.toString().toByteArray(Charsets.UTF_8),
    )

    fun finishConnection(): ByteArray = DoubaoFrameCodec.request(
        messageType = DoubaoProto.MSG_FULL_CLIENT,
        event = DoubaoEvents.FINISH_CONNECTION,
        sessionId = "",
        payload = buildJsonObject { }.toString().toByteArray(Charsets.UTF_8),
    )

    /**
     * 开会话。下行音频的格式只在这里定得下来：不指定 pcm 二十四千赫兹十六位，服务端发的是 OGG Opus，
     * 播放器拿到的就不是裸流。input_mod 与 enable_custom_vad 只在手动回合制打开时带。
     */
    fun startSession(
        sessionId: String,
        speaker: String,
        systemPrompt: String,
        pushToTalk: Boolean,
    ): ByteArray = DoubaoFrameCodec.request(
        messageType = DoubaoProto.MSG_FULL_CLIENT,
        event = DoubaoEvents.START_SESSION,
        sessionId = sessionId,
        payload = buildJsonObject {
            putJsonObject("asr") {
                putJsonObject("extra") { put("end_smooth_window_ms", ASR_END_WINDOW_MS) }
            }
            putJsonObject("tts") {
                put("speaker", speaker)
                putJsonObject("audio_config") {
                    put("channel", 1)
                    put("format", "pcm")
                    put("sample_rate", DOWNLINK_RATE)
                    put("bit_width", DOWNLINK_BITS)
                }
                put("speech_rate", 0)
            }
            putJsonObject("dialog") {
                put("bot_name", BOT_NAME)
                if (systemPrompt.isNotBlank()) put("system_role", systemPrompt)
                if (pushToTalk) {
                    put("input_mod", "push_to_talk")
                    put("enable_custom_vad", true)
                }
            }
        }.toString().toByteArray(Charsets.UTF_8),
    )

    fun audio(sessionId: String, pcm: ByteArray): ByteArray =
        DoubaoFrameCodec.audio(DoubaoEvents.TASK_REQUEST, sessionId, pcm)

    /** 松手时告诉服务端这段音频到此为止。服务端 VAD 关不掉那就没有意义，开关在会话层。 */
    fun turnEnd(sessionId: String): ByteArray = DoubaoFrameCodec.request(
        messageType = DoubaoProto.MSG_FULL_CLIENT,
        event = DoubaoEvents.TASK_REQUEST,
        sessionId = sessionId,
        payload = buildJsonObject { put("audio_end", true) }.toString().toByteArray(Charsets.UTF_8),
    )

    fun finishSession(sessionId: String): ByteArray = DoubaoFrameCodec.request(
        messageType = DoubaoProto.MSG_FULL_CLIENT,
        event = DoubaoEvents.FINISH_SESSION,
        sessionId = sessionId,
        payload = buildJsonObject { }.toString().toByteArray(Charsets.UTF_8),
    )

    fun parse(frame: DoubaoFrame): DoubaoInbound {
        if (frame.messageType == DoubaoProto.MSG_ERROR) {
            val said = frame.payload.toString(Charsets.UTF_8).trim()
            return DoubaoInbound.Failed(faultOf(frame.errorCode?.toString(), said))
        }
        // 裸音频响应：只有序列化标成裸数据时载荷才是 PCM，0xB 帧里塞 JSON 的不能拿去播。
        if (frame.messageType == DoubaoProto.MSG_AUDIO_SERVER && frame.serialization == DoubaoProto.SERIAL_RAW) {
            return if (frame.payload.isEmpty()) DoubaoInbound.Ignored else DoubaoInbound.Audio(frame.payload)
        }
        return when (frame.event) {
            DoubaoEvents.CONNECTION_STARTED -> DoubaoInbound.ConnectionStarted
            DoubaoEvents.CONNECTION_FAILED -> DoubaoInbound.Failed(faultOf(null, messageOf(frame)))
            DoubaoEvents.CONNECTION_FINISHED -> DoubaoInbound.Ignored
            DoubaoEvents.SESSION_STARTED -> DoubaoInbound.SessionStarted
            DoubaoEvents.SESSION_FINISHED -> DoubaoInbound.SessionFinished
            DoubaoEvents.SESSION_FAILED -> DoubaoInbound.Failed(faultOf(null, messageOf(frame)))
            DoubaoEvents.CHAT_RESPONSE -> textOf(frame)?.let { DoubaoInbound.AssistantText(it) } ?: DoubaoInbound.Ignored
            DoubaoEvents.CHAT_ENDED -> DoubaoInbound.TurnComplete
            DoubaoEvents.ASR_RESPONSE -> textOf(frame)?.let { DoubaoInbound.UserText(it) } ?: DoubaoInbound.Ignored
            DoubaoEvents.SAY_HELLO,
            DoubaoEvents.ASR_INFO,
            DoubaoEvents.ASR_ENDED,
            DoubaoEvents.TTS_SENTENCE_START,
            DoubaoEvents.TTS_SENTENCE_END,
            DoubaoEvents.TTS_RESPONSE,
            DoubaoEvents.TTS_ENDED,
            -> DoubaoInbound.Ignored

            else -> DoubaoInbound.Ignored
        }
    }

    /** 会话号：优先用服务端在 SessionStarted 里回的那个，载荷里没有就沿用帧上带的那个。 */
    fun sessionIdOf(frame: DoubaoFrame): String? {
        val fromPayload = payloadOf(frame)?.let { root ->
            root["session_id"]?.jsonPrimitive?.contentOrNull
                ?: root["sessionId"]?.jsonPrimitive?.contentOrNull
        }
        if (!fromPayload.isNullOrBlank()) return fromPayload
        return frame.sessionId.takeIf { it.isNotBlank() }
    }

    /** 识别结果与模型文本在各自的载荷里，键名两套都认，取不到就当这帧没有文字。 */
    private fun textOf(frame: DoubaoFrame): String? {
        val root = payloadOf(frame) ?: return null
        root["results"]?.let { results ->
            val first = runCatching { results.jsonArray.first().jsonObject }.getOrNull()
            first?.get("text")?.jsonPrimitive?.contentOrNull?.let { return it }
        }
        return root["content"]?.jsonPrimitive?.contentOrNull
            ?: root["text"]?.jsonPrimitive?.contentOrNull
    }

    /** 服务端的原因只进日志，界面上出现的是归类之后那一句。 */
    private fun faultOf(code: String?, said: String?): String {
        Log.w("DoubaoWire", "server said $code ${said?.take(200)}")
        return ProviderFault.notice(code, said)
    }

    private fun messageOf(frame: DoubaoFrame): String? {
        val root = payloadOf(frame) ?: return null
        return root["error"]?.jsonPrimitive?.contentOrNull
            ?: root["message"]?.jsonPrimitive?.contentOrNull
            ?: root["status"]?.jsonPrimitive?.contentOrNull
    }

    private fun payloadOf(frame: DoubaoFrame): JsonObject? {
        if (frame.payload.isEmpty()) return null
        return runCatching { json.parseToJsonElement(frame.payload.toString(Charsets.UTF_8)).jsonObject }.getOrNull()
    }

    private const val ASR_END_WINDOW_MS = 1_500
}
