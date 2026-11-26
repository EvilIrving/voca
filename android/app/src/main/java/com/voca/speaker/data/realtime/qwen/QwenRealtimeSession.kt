package com.voca.speaker.data.realtime.qwen

import android.util.Log
import com.voca.speaker.data.realtime.Capabilities
import com.voca.speaker.data.realtime.LateTurn
import com.voca.speaker.data.realtime.Pcm
import com.voca.speaker.data.realtime.ProviderFault
import com.voca.speaker.data.realtime.RealtimeEvent
import com.voca.speaker.data.realtime.RealtimeSession
import com.voca.speaker.domain.DialogTurn
import com.voca.speaker.domain.LiveVendor
import com.voca.speaker.domain.VoiceCatalog
import java.util.concurrent.TimeUnit
import kotlinx.serialization.json.JsonObject
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener

/**
 * 千问实时语音（qwen-audio-3.1-realtime-flash）的适配器。官方把「手动控制」列成一等交互模式：
 * session.update 里 turn_detection 置 null 就进 push-to-talk，服务端不再自己判停，
 * 松手发 input_audio_buffer.commit 与 response.create 才开口，与长按说话的骨架逐项对得上。
 *
 * 音频在本层不重采样：上行就是十六千赫兹单声道十六位，采集帧原样进 append；
 * 下行是二十四千赫兹裸 PCM，原样交给播放。
 *
 * 打断分两处。作废这一轮发 input_audio_buffer.clear 加 response.cancel，缓冲区里没提交的音频
 * 一并清掉，免得跟着下一轮被当成用户发言。按住时上一轮还在说，就在按下的那一刻取消它，
 * 剩下的内容按迟到内容标上、界面认 id 丢掉：协议里手动模式不会自己开口，那一响只可能是上一轮的尾巴。
 *
 * 协议没有跨连接续接的句柄，断线只能重建一条连接，接着聊的那段流水以 conversation.item.create
 * 重新灌进去，记忆靠文本接上，不靠服务端的会话状态。
 */
class QwenRealtimeSession(
    private val apiKey: String,
    private val model: String,
    private val voice: String = VoiceCatalog.defaultId(LiveVendor.Qwen),
    private val instructions: String = "",
    private val onEvent: (RealtimeEvent) -> Unit,
    /** 接着一条历史会话聊、或重连之后要先交代的成对流水，走 conversation.item.create，不进系统提示。 */
    context: List<DialogTurn> = emptyList(),
) : RealtimeSession {

    private val seed = context.map { it.role to it.text }

    override val capabilities = Capabilities(
        canInterrupt = true,
        acceptsText = true,
        streamsTranscript = true,
        phonemeScores = false,
        maxSessionMinutes = null,
    )

    private val client = sharedClient

    private val pendingFrames = ArrayDeque<String>()
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

    /** 服务端已收到 response.create、还没回 response.done。按住时它还在说，就是被打断的那一段。 */
    @Volatile
    private var responding = false

    /** 握手没完成时松手不能丢：commit 与 create 留到会话就绪之后补发。 */
    @Volatile
    private var pendingEnd = false

    /** 这一轮已经在按下时取消过上一轮，迟到窗口立好了，模型的内容直接吞掉。 */
    @Volatile
    private var bargeHeld = false

    override fun connect() {
        if (closed || socket != null) return
        Log.i(TAG, "connecting model=$model voice=$voice keyLen=${apiKey.length}")
        open()
    }

    private fun open() {
        if (closed) return
        ready = false
        val request = Request.Builder()
            .url("${QwenWire.ENDPOINT}?model=$model")
            .header("Authorization", "Bearer $apiKey")
            .build()
        socket = client.newWebSocket(request, listener)
    }

    private val listener = object : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            Log.i(TAG, "open ${response.code}")
            // 音色与判停方式只认第一次 session.update，整场只发这一条。
            send(QwenWire.sessionUpdate(voice, instructions))
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
            reset()
            Log.w(TAG, "socket failed: ${t.message}", t)
            if (closed) return
            onEvent(RealtimeEvent.Failed(classify(t, response)))
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            if (socket !== webSocket) return
            reset()
            Log.i(TAG, "closed $code $reason")
            if (closed) return
            onEvent(RealtimeEvent.Failed(""))
        }
    }

    private fun reset() {
        ready = false
        responding = false
        socket = null
    }

    private fun classify(t: Throwable, response: Response?): String = when {
        response?.code == 401 || response?.code == 403 -> ProviderFault.Key
        response?.code == 429 -> ProviderFault.Busy
        response?.code == 404 -> ProviderFault.Config
        t is java.net.SocketTimeoutException -> ""
        t is java.net.UnknownHostException -> ""
        t is javax.net.ssl.SSLException -> ""
        else -> ""
    }

    override fun startTurn() {
        turnOpen = true
        bargeHeld = false
        late.nextTurn()
        // 上一轮还在说：界面已经停播，服务端也停下，否则这一轮的 create 会被「另一条响应正在生成」顶回来。
        if (responding) {
            bargeHeld = true
            cancelInFlight()
        }
    }

    override fun sendAudio(frame: ShortArray) {
        if (!turnOpen) return
        if (frame.isEmpty()) return
        // 采集本身就是十六千赫兹单声道十六位，与上行要求一致。
        val base64 = Pcm.encodeBase64(Pcm.shortsToBytes(frame))
        if (!ready) {
            synchronized(lock) {
                if (pendingFrames.size < MAX_PENDING) pendingFrames.addLast(base64)
            }
            return
        }
        send(QwenWire.appendAudio(base64))
    }

    override fun sendText(text: String) {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return
        if (!ready) {
            Log.i(TAG, "text turn dropped, session not ready")
            return
        }
        if (responding) cancelInFlight()
        send(QwenWire.message("user", trimmed))
        send(QwenWire.createResponse())
        responding = true
        // 这一轮的新响应与上一轮的迟到内容靠响应号分，barge 状态不能带过来，否则新响应整段被丢。
        bargeHeld = false
    }

    override fun endTurn() {
        turnOpen = false
        if (!ready) {
            pendingEnd = true
            Log.i(TAG, "endTurn parked until session ready")
            return
        }
        commitAndCreate()
    }
    override fun cancelTurn(turnId: String) {
        turnOpen = false
        pendingEnd = false
        bargeHeld = false
        synchronized(lock) { pendingFrames.clear() }
        late.cancel(turnId)
        if (!ready) return
        // 先清缓冲区：这一轮说过的话不该跟着下一轮一起提交；再掐掉可能正在生成的回复。
        send(QwenWire.clearBuffer())
        if (responding) {
            send(QwenWire.cancelResponse())
            responding = false
        }
    }

    override fun interrupt() {
        turnOpen = false
        late.close()
        if (ready && responding) cancelInFlight()
        onEvent(RealtimeEvent.Interrupted)
    }

    override fun close() {
        closed = true
        ready = false
        turnOpen = false
        responding = false
        val current = socket
        socket = null
        current?.close(1000, "bye")
    }

    private fun commitAndCreate() {
        // 上一轮的迟到内容交给响应号那本账，barge 只负责按住那一段。
        bargeHeld = false
        late.seal()
        send(QwenWire.commit())
        send(QwenWire.createResponse())
        responding = true
    }

    /** 停下正在生成的那条回复，并把它的尾巴挂进迟到窗口，界面认 id 丢掉。 */
    private fun cancelInFlight() {
        late.cancel(BARGE_TURN)
        send(QwenWire.cancelResponse())
        responding = false
    }

    private fun flushPending() {
        // 接着聊的那段流水先落地：它不触发推理，只补齐对话项。
        seed.forEach { (role, text) -> send(QwenWire.message(role, text)) }
        val buffered = synchronized(lock) {
            val copy = pendingFrames.toList()
            pendingFrames.clear()
            copy
        }
        buffered.forEach { send(QwenWire.appendAudio(it)) }
        if (pendingEnd) {
            pendingEnd = false
            commitAndCreate()
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
        val parsed = QwenWire.parse(text) ?: return
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

            is RealtimeEvent.Audio -> deliver(event, tag)
            is RealtimeEvent.AssistantText -> deliver(event, tag)

            is RealtimeEvent.UserText -> {
                if (tag == null) onEvent(event)
            }

            RealtimeEvent.TurnComplete -> {
                responding = false
                late.close()
                if (parsed.cancelled || tag != null) return
                onEvent(RealtimeEvent.TurnComplete)
            }

            RealtimeEvent.Interrupted -> onEvent(RealtimeEvent.Interrupted)

            is RealtimeEvent.Failed -> {
                responding = false
                Log.w(TAG, "server error: ${event.message}")
                onEvent(event)
            }
        }
    }

    /**
     * 模型这一侧的内容。按住时它到了就是上一轮的尾巴：打上被取消那一轮的记号，剩下的块跟着窗口一起丢。
     * 被取消那一轮的内容一律到这里为止，界面拿不到半块音频，也就不必再认 id。
     */
    private fun deliver(event: RealtimeEvent, tag: String?) {
        if (turnOpen && !bargeHeld) {
            bargeHeld = true
            late.cancel(BARGE_TURN)
            send(QwenWire.cancelResponse())
            responding = false
            Log.i(TAG, "barge-in cancelled")
        }
        if (tag != null || bargeHeld) return
        onEvent(event)
    }

    companion object {
        private val sharedClient: OkHttpClient by lazy {
            OkHttpClient.Builder()
                .connectTimeout(8, TimeUnit.SECONDS)
                .pingInterval(15, TimeUnit.SECONDS)
                .readTimeout(0, TimeUnit.MILLISECONDS)
                .build()
        }

        private const val TAG = "QwenRealtime"
        private const val MAX_PENDING = 200
        private const val BARGE_TURN = "barge"

        /** 取消之后等迟到内容的窗口，够覆盖一轮生成来回。 */
        private const val LATE_WINDOW_MS = 1_500L
    }
}
