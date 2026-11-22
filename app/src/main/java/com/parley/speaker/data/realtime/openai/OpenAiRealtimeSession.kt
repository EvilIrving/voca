package com.parley.speaker.data.realtime.openai

import android.util.Log
import com.parley.speaker.data.realtime.Capabilities
import com.parley.speaker.data.realtime.LateTurn
import com.parley.speaker.data.realtime.Pcm
import com.parley.speaker.data.realtime.ProviderFault
import com.parley.speaker.data.realtime.RealtimeEvent
import com.parley.speaker.data.realtime.RealtimeSession
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.util.concurrent.TimeUnit

/**
 * OpenAI Realtime 适配器，手动回合制：turn_detection 置 null 之后服务端不断句，
 * 松手发 input_audio_buffer.commit 与 response.create 才开口。
 *
 * 音频在本层归一：采集给的十六千赫兹帧重采样到二十四千赫兹再 base64 进 append，
 * 下行的 response.audio.delta 本来就是二十四千赫兹裸 PCM，原样交给播放。
 *
 * 取消走两条路：线上发 response.cancel 让服务端别再生成，本地把迟到的
 * response.audio.delta 与转写按 turnId 标上被取消那一轮，界面认 id 丢弃。
 * response.done 带 status=cancelled 的那条不再当回合结束处理。
 *
 * 连接上限六十分钟：这是服务端的硬上限，到点本地先断并报一次失败，界面按断线退回未连接，
 * 重新开一条连接接着聊。协议里没有能跨连接续上的句柄，这一层不做上下文搬运。
 */
class OpenAiRealtimeSession(
    private val apiKey: String,
    private val model: String,
    private val voice: String,
    private val instructions: String,
    private val onEvent: (RealtimeEvent) -> Unit,
) : RealtimeSession {

    override val capabilities = Capabilities(
        canInterrupt = true,
        acceptsText = true,
        streamsTranscript = true,
        phonemeScores = false,
        maxSessionMinutes = SESSION_MINUTES,
    )

    private val client = sharedClient
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val pendingFrames = ArrayDeque<ByteArray>()
    private val lock = Any()

    private val late = LateTurn(LATE_WINDOW_MS) { System.nanoTime() / 1_000_000 }

    @Volatile
    private var socket: WebSocket? = null

    @Volatile
    private var ready = false

    @Volatile
    private var turnOpen = false

    @Volatile
    private var closed = false

    /** 握手没完成时松手不能丢：commit 与 create 留到 session.updated 之后补发。 */
    @Volatile
    private var pendingEnd = false

    private var limitJob: Job? = null

    override fun connect() {
        if (closed || socket != null) return
        Log.i(TAG, "connecting model=$model voice=$voice keyLen=${apiKey.length}")
        open()
    }

    private fun open() {
        if (closed) return
        ready = false
        val request = Request.Builder()
            .url("${OpenAiWire.ENDPOINT}?model=$model")
            .header("Authorization", "Bearer $apiKey")
            .build()
        socket = client.newWebSocket(request, listener)
    }

    private val listener = object : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            Log.i(TAG, "open ${response.code}")
            send(OpenAiWire.sessionUpdate(model, voice, instructions))
            scheduleLimit()
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            accept(webSocket, text)
        }

        override fun onMessage(webSocket: WebSocket, bytes: okio.ByteString) {
            accept(webSocket, bytes.utf8())
        }

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            Log.i(TAG, "closing $code $reason")
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            if (socket !== webSocket) return
            ready = false
            socket = null
            Log.w(TAG, "socket failed: ${t.message}", t)
            if (closed) return
            onEvent(RealtimeEvent.Failed(classify(t, response)))
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            if (socket !== webSocket) return
            ready = false
            socket = null
            Log.i(TAG, "closed $code $reason")
            if (closed) return
            onEvent(RealtimeEvent.Failed("正在重连"))
        }
    }

    private fun classify(t: Throwable, response: Response?): String = when {
        response?.code == 401 || response?.code == 403 -> ProviderFault.Key
        response?.code == 404 -> ProviderFault.Config
        response?.code == 429 -> ProviderFault.Busy
        t is java.net.SocketTimeoutException -> "网络异常，正在重连"
        t is java.net.UnknownHostException -> "网络异常，正在重连"
        t is javax.net.ssl.SSLException -> "网络异常，正在重连"
        else -> "服务异常，正在重连"
    }

    override fun startTurn() {
        turnOpen = true
        late.nextTurn()
    }

    override fun sendAudio(frame: ShortArray) {
        if (!turnOpen) return
        if (frame.isEmpty()) return
        val pcm = Pcm.uplinkFrameToDownlink(frame)
        if (!ready) {
            synchronized(lock) {
                if (pendingFrames.size < MAX_PENDING) pendingFrames.addLast(pcm)
            }
            return
        }
        send(OpenAiWire.appendAudio(Pcm.encodeBase64(pcm)))
    }

    override fun sendText(text: String) {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return
        if (!ready) {
            Log.i(TAG, "text turn dropped, session not ready")
            return
        }
        send(OpenAiWire.userText(trimmed))
        send(OpenAiWire.createResponse())
    }

    override fun endTurn() {
        turnOpen = false
        if (!ready) {
            pendingEnd = true
            Log.i(TAG, "endTurn parked until session.updated")
            return
        }
        send(OpenAiWire.commit())
        send(OpenAiWire.createResponse())
    }

    override fun cancelTurn(turnId: String) {
        turnOpen = false
        pendingEnd = false
        synchronized(lock) { pendingFrames.clear() }
        late.cancel(turnId)
        if (!ready) return
        send(OpenAiWire.cancelResponse())
    }

    override fun interrupt() {
        turnOpen = false
        late.close()
        if (ready) send(OpenAiWire.cancelResponse())
        onEvent(RealtimeEvent.Interrupted)
    }

    override fun close() {
        closed = true
        ready = false
        turnOpen = false
        limitJob?.cancel()
        limitJob = null
        val current = socket
        socket = null
        current?.close(1000, "bye")
        scope.cancel()
    }

    private fun scheduleLimit() {
        limitJob?.cancel()
        limitJob = scope.launch {
            delay(SESSION_MINUTES * MINUTE_MS)
            if (closed) return@launch
            Log.i(TAG, "session limit ${SESSION_MINUTES}min reached")
            onEvent(RealtimeEvent.Failed("已达会话时长上限，正在重连"))
            close()
        }
    }

    private fun flushPending() {
        val buffered = synchronized(lock) {
            val copy = pendingFrames.toList()
            pendingFrames.clear()
            copy
        }
        buffered.forEach { send(OpenAiWire.appendAudio(Pcm.encodeBase64(it))) }
        if (pendingEnd) {
            pendingEnd = false
            send(OpenAiWire.commit())
            send(OpenAiWire.createResponse())
        }
    }

    private fun send(payload: JsonObject) {
        socket?.send(payload.toString())
    }

    private fun accept(webSocket: WebSocket, text: String) {
        if (socket !== webSocket) return
        receive(text)
    }

    private fun receive(text: String) {
        val parsed = OpenAiWire.parse(text) ?: return
        val tag = late.tag(parsed.responseId)
        val event = parsed.event
        if (event == null) {
            // 只带响应号的消息：挂到窗口上，之后同一个响应号的块都算被取消那轮。
            parsed.responseId?.let { if (tag != null) late.bind(it) }
            return
        }
        when (event) {
            RealtimeEvent.Ready -> {
                val first = !ready
                ready = true
                if (first) flushPending()
                if (first) onEvent(RealtimeEvent.Ready)
            }

            is RealtimeEvent.Audio -> onEvent(event.copy(turnId = tag))

            is RealtimeEvent.AssistantText -> onEvent(event.copy(turnId = tag))

            is RealtimeEvent.UserText -> onEvent(event.copy(turnId = tag))

            RealtimeEvent.TurnComplete -> {
                late.close()
                if (parsed.cancelled || tag != null) return
                onEvent(RealtimeEvent.TurnComplete)
            }

            RealtimeEvent.Interrupted -> onEvent(RealtimeEvent.Interrupted)

            is RealtimeEvent.Failed -> {
                Log.w(TAG, "server error: ${event.message}")
                onEvent(event)
            }
        }
    }

    companion object {
        /** 服务端给的单条连接上限。 */
        const val SESSION_MINUTES = 60

        private val sharedClient: OkHttpClient by lazy {
            OkHttpClient.Builder()
                .connectTimeout(8, TimeUnit.SECONDS)
                .pingInterval(15, TimeUnit.SECONDS)
                .readTimeout(0, TimeUnit.MILLISECONDS)
                .build()
        }

        private const val TAG = "OpenAiRealtime"
        private const val MAX_PENDING = 200
        private const val MINUTE_MS = 60_000L

        /** 取消之后等迟到音频的窗口，够覆盖一轮生成来回。 */
        private const val LATE_WINDOW_MS = 1_500L
    }
}
