package com.parley.speaker.data.realtime.gemini

import android.util.Base64
import android.util.Log
import com.parley.speaker.data.realtime.Capabilities
import com.parley.speaker.data.realtime.RealtimeEvent
import com.parley.speaker.data.realtime.RealtimeSession
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.util.concurrent.TimeUnit

/**
 * Gemini 实时语音适配器：手动回合制。自动断句关掉之后，服务端一直等到 activityEnd 才开口。
 * 上行 16 kHz 单声道 16 位小端、20 ms 一帧；下行 24 kHz。
 */
class GeminiLiveSession(
    private val apiKey: String,
    private val model: String,
    private val voice: String,
    private val systemPrompt: String,
    private val onEvent: (RealtimeEvent) -> Unit,
) : RealtimeSession {

    override val capabilities = Capabilities(
        canInterrupt = true,
        acceptsText = true,
        streamsTranscript = true,
        phonemeScores = false,
        maxSessionMinutes = 10,
    )

    private val json = Json { ignoreUnknownKeys = true }
    private val client = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .pingInterval(15, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .build()

    private val pendingFrames = ArrayDeque<ByteArray>()
    private val lock = Any()

    private var socket: WebSocket? = null

    @Volatile
    private var ready = false

    @Volatile
    private var turnOpen = false

    @Volatile
    private var closed = false

    override fun connect() {
        if (closed || socket != null) return
        Log.i(TAG, "connecting model=$model voice=$voice keyLen=${apiKey.length}")
        val request = Request.Builder().url(url()).build()
        socket = client.newWebSocket(request, listener)
    }

    private fun classify(t: Throwable, response: Response?): String = when {
        response?.code == 401 || response?.code == 403 -> "密钥被拒绝，检查配置中心的实时语音密钥"
        response?.code == 404 -> "模型不可用，换一个实时语音模型"
        t is java.net.SocketTimeoutException -> "连不上语音服务，检查网络与代理"
        t is java.net.UnknownHostException -> "域名解析失败，检查网络与代理"
        t is javax.net.ssl.SSLException -> "安全连接失败，检查网络与代理"
        else -> "连接中断，重新连接后会接着聊"
    }

    private fun url(): String =
        "wss://generativelanguage.googleapis.com/ws/google.ai.generativelanguage.v1beta.GenerativeService.BidiGenerateContent?key=$apiKey"

    private val listener = object : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            Log.i(TAG, "open ${response.code}")
            webSocket.send(setupMessage().toString())
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            handle(text)
        }

        override fun onMessage(webSocket: WebSocket, bytes: okio.ByteString) {
            handle(bytes.utf8())
        }

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            Log.i(TAG, "closing $code $reason")
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            ready = false
            socket = null
            Log.w(TAG, "socket failed: ${t.message}", t)
            onEvent(RealtimeEvent.Failed(classify(t, response)))
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            ready = false
            socket = null
            if (!closed) onEvent(RealtimeEvent.Failed("会话已结束，重新连接后会接着聊"))
        }
    }

    private fun setupMessage() = buildJsonObject {
        putJsonObject("setup") {
            put("model", "models/$model")
            putJsonObject("generationConfig") {
                putJsonArray("responseModalities") { add("AUDIO") }
                putJsonObject("speechConfig") {
                    putJsonObject("voiceConfig") {
                        putJsonObject("prebuiltVoiceConfig") {
                            put("voiceName", voice)
                        }
                    }
                }
            }
            putJsonObject("systemInstruction") {
                putJsonArray("parts") {
                    add(buildJsonObject { put("text", systemPrompt) })
                }
            }
            putJsonObject("realtimeInputConfig") {
                putJsonObject("automaticActivityDetection") {
                    put("disabled", true)
                }
            }
            putJsonObject("inputAudioTranscription") {}
            putJsonObject("outputAudioTranscription") {}
        }
    }

    override fun startTurn() {
        if (!ready) {
            turnOpen = true
            return
        }
        turnOpen = true
        send(buildJsonObject {
            putJsonObject("realtimeInput") { putJsonObject("activityStart") {} }
        })
    }

    override fun sendAudio(frame: ShortArray) {
        if (!turnOpen) return
        val bytes = ByteArray(frame.size * 2)
        for (i in frame.indices) {
            val value = frame[i].toInt()
            bytes[i * 2] = (value and 0xFF).toByte()
            bytes[i * 2 + 1] = ((value shr 8) and 0xFF).toByte()
        }
        if (!ready) {
            synchronized(lock) {
                if (pendingFrames.size < MAX_PENDING) pendingFrames.addLast(bytes)
            }
            return
        }
        sendAudioBytes(bytes)
    }

    private fun sendAudioBytes(bytes: ByteArray) {
        val encoded = Base64.encodeToString(bytes, Base64.NO_WRAP)
        send(buildJsonObject {
            putJsonObject("realtimeInput") {
                putJsonObject("audio") {
                    put("data", encoded)
                    put("mimeType", "audio/pcm;rate=16000")
                }
            }
        })
    }

    override fun sendText(text: String) {
        send(buildJsonObject {
            putJsonObject("clientContent") {
                putJsonArray("turns") {
                    add(buildJsonObject {
                        put("role", "user")
                        putJsonArray("parts") { add(buildJsonObject { put("text", text) }) }
                    })
                }
                put("turnComplete", true)
            }
        })
    }

    override fun endTurn() {
        turnOpen = false
        if (!ready) return
        send(buildJsonObject {
            putJsonObject("realtimeInput") { put("audioStreamEnd", true) }
        })
        send(buildJsonObject {
            putJsonObject("realtimeInput") { putJsonObject("activityEnd") {} }
        })
    }

    override fun interrupt() {
        turnOpen = false
        onEvent(RealtimeEvent.Interrupted)
    }

    override fun close() {
        closed = true
        ready = false
        turnOpen = false
        socket?.close(1000, "bye")
        socket = null
    }

    private fun send(payload: kotlinx.serialization.json.JsonObject) {
        socket?.send(payload.toString())
    }

    private fun handle(text: String) {
        val root = try {
            json.parseToJsonElement(text).jsonObject
        } catch (e: Exception) {
            Log.w(TAG, "bad payload: ${e.message}")
            return
        }

        root["setupComplete"]?.let {
            ready = true
            onEvent(RealtimeEvent.Ready)
            drainPending()
            return
        }

        root["serverContent"]?.jsonObject?.let { content ->
            (content["interrupted"]?.jsonPrimitive?.contentOrNull == "true").let { interrupted ->
                if (interrupted) {
                    onEvent(RealtimeEvent.Interrupted)
                    return@let
                }
            }
            content["inputTranscription"]?.jsonObject?.get("text")?.jsonPrimitive?.contentOrNull?.let {
                onEvent(RealtimeEvent.UserText(it))
            }
            content["outputTranscription"]?.jsonObject?.get("text")?.jsonPrimitive?.contentOrNull?.let {
                onEvent(RealtimeEvent.AssistantText(it))
            }
            content["modelTurn"]?.jsonObject?.get("parts")?.jsonArray?.forEach { part ->
                val inline = part.jsonObject["inlineData"]?.jsonObject ?: return@forEach
                val data = inline["data"]?.jsonPrimitive?.contentOrNull ?: return@forEach
                val pcm = Base64.decode(data, Base64.NO_WRAP)
                onEvent(RealtimeEvent.Audio(pcm))
            }
            if (content["turnComplete"]?.jsonPrimitive?.contentOrNull == "true") {
                onEvent(RealtimeEvent.TurnComplete)
            }
        }

        root["error"]?.let { error ->
            onEvent(RealtimeEvent.Failed(error.toString()))
        }
    }

    private fun drainPending() {
        val buffered = synchronized(lock) {
            val copy = pendingFrames.toList()
            pendingFrames.clear()
            copy
        }
        buffered.forEach { sendAudioBytes(it) }
    }

    companion object {
        private const val TAG = "GeminiLive"
        private const val MAX_PENDING = 200
    }
}
