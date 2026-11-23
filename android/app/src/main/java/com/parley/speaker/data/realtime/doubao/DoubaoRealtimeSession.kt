package com.parley.speaker.data.realtime.doubao

import android.util.Log
import com.parley.speaker.data.realtime.Capabilities
import com.parley.speaker.data.realtime.LateTurn
import com.parley.speaker.data.realtime.Pcm
import com.parley.speaker.data.realtime.ProviderFault
import com.parley.speaker.data.realtime.RealtimeEvent
import com.parley.speaker.data.realtime.RealtimeSession
import com.parley.speaker.domain.LiveVendor
import com.parley.speaker.domain.VoiceCatalog
import com.parley.speaker.domain.promptWithHistory
import java.util.UUID
import java.util.concurrent.TimeUnit
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString

/**
 * 火山豆包实时语音 3.0（Seeduplex）的适配器。鉴权只要一把 API Key，走 X-Api-Key 头。
 * 整场是 JSON 文本帧：
 * 连上之后先发 session.create，收到 session.created 才算能灌音频；
 * 上行十六千赫兹单声道十六位，下行在 setup 里点名 pcm_s16le 二十四千赫兹，
 * 不点名的话服务端按三十二位小端发，播放链路读十六位就成噪声。
 *
 * 回合边界由客户端宣告：松手发 input_audio_buffer.commit。这一条就是协议里的强制判停，
 * 与 Gemini 的 activityEnd 同一个位置；服务端的自动判停仍在跑，所以模型可能在你说话的
 * 时候自己开口，commit 只能收口或加速它结束，不构成「松手之前绝不作答」的保证。
 *
 * 取消有真正的动作可发：response.cancel 之后服务端回 response.canceled。
 * 已发出的那一段音频仍会继续回吐，所以本地还留一层迟到窗口，按响应号把旧一轮的内容标出来丢掉。
 */
class DoubaoRealtimeSession(
    private val apiKey: String,
    private val voice: String = VoiceCatalog.defaultId(LiveVendor.Doubao),
    private val systemPrompt: String = "",
    private val onEvent: (RealtimeEvent) -> Unit,
    /** 接着一条历史会话聊时要带上的那段流水：豆包没有跨连接续上的句柄，它接在系统提示后面。 */
    history: String? = null,
) : RealtimeSession {

    private val prompt = promptWithHistory(systemPrompt, history)

    override val capabilities = Capabilities(
        canInterrupt = true,
        // 端到端接口的文本输入通道是给朗读用的 speech_text_buffer，不是用户打字，界面照样隐藏输入。
        acceptsText = false,
        streamsTranscript = true,
        phonemeScores = false,
        maxSessionMinutes = null,
    )

    private val client = sharedClient

    private val pendingFrames = ArrayDeque<ByteArray>()
    private val lock = Any()

    private val late = LateTurn(LATE_WINDOW_MS) { System.nanoTime() / 1_000_000 }

    /** 会话号由客户端生成，服务端在 session.created 里回了自己的就换成它的。 */
    @Volatile
    private var sessionId: String = UUID.randomUUID().toString()

    @Volatile
    private var socket: WebSocket? = null

    @Volatile
    private var ready = false

    @Volatile
    private var turnOpen = false

    @Volatile
    private var closed = false

    @Volatile
    private var pendingEnd = false

    /** 麦克风在服务端那头是否处于关着的状态。关麦通知发过之后，再开口先要报一声恢复。 */
    @Volatile
    private var muted = false

    @Volatile
    private var pendingUnmute = false

    override fun connect() {
        if (closed || socket != null) return
        Log.i(TAG, "connecting voice=$voice keyLen=${apiKey.length}")
        val request = Request.Builder()
            .url(DoubaoWire.ENDPOINT)
            .header("X-Api-Key", apiKey)
            .build()
        socket = client.newWebSocket(request, listener)
    }

    private val listener = object : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            Log.i(TAG, "open ${response.code}")
            send(DoubaoWire.sessionCreate(sessionId, voice, prompt))
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            accept(webSocket, text)
        }

        override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
            Log.w(TAG, "unexpected binary frame: ${bytes.size} bytes")
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
        socket = null
    }

    private fun classify(t: Throwable, response: Response?): String = when {
        response?.code == 401 || response?.code == 403 -> ProviderFault.Key
        response?.code == 404 -> ProviderFault.Config
        t is java.net.SocketTimeoutException -> ""
        t is java.net.UnknownHostException -> ""
        t is javax.net.ssl.SSLException -> ""
        else -> ProviderFault.notice(response?.message)
    }

    override fun startTurn() {
        turnOpen = true
        late.nextTurn()
        // 上一轮松手时报过关麦，这一轮开口前得先撤销，否则服务端仍当麦克风关着，上行帧被丢掉。
        if (!muted) return
        muted = false
        if (ready) send(DoubaoWire.audioUnmute()) else pendingUnmute = true
    }

    override fun sendAudio(frame: ShortArray) {
        if (!turnOpen) return
        if (frame.isEmpty()) return
        // 采集本身就是十六千赫兹单声道十六位，跟上行要求一致，不需要重采样。
        val pcm = Pcm.shortsToBytes(frame)
        if (!ready) {
            synchronized(lock) {
                if (pendingFrames.size < MAX_PENDING) pendingFrames.addLast(pcm)
            }
            return
        }
        send(DoubaoWire.audioAppend(pcm))
    }

    override fun sendText(text: String) {
        Log.i(TAG, "text input not supported, dropped")
    }

    override fun endTurn() {
        turnOpen = false
        muted = true
        if (!ready) {
            pendingEnd = true
            Log.i(TAG, "endTurn parked until session.created")
            return
        }
        // 两条一起发才算收口：实测只有 commit 时服务端不问也不答，补上关麦通知这一轮才动。
        send(DoubaoWire.commit())
        send(DoubaoWire.audioMute())
    }

    override fun cancelTurn(turnId: String) {
        turnOpen = false
        pendingEnd = false
        muted = true
        synchronized(lock) { pendingFrames.clear() }
        late.cancel(turnId)
        if (!ready) return
        // 这一轮作废在服务端没有对应的丢弃帧，能做的只有掐掉正在生成的回复并关麦，再把迟到的内容本地丢掉。
        send(DoubaoWire.cancel())
        send(DoubaoWire.audioMute())
    }

    override fun interrupt() {
        turnOpen = false
        onEvent(RealtimeEvent.Interrupted)
    }

    override fun close() {
        closed = true
        val wasReady = ready
        ready = false
        turnOpen = false
        if (wasReady) send(DoubaoWire.sessionClose())
        val current = socket
        socket = null
        current?.close(1000, "bye")
    }

    private fun flushPending() {
        if (pendingUnmute) {
            pendingUnmute = false
            send(DoubaoWire.audioUnmute())
        }
        val buffered = synchronized(lock) {
            val copy = pendingFrames.toList()
            pendingFrames.clear()
            copy
        }
        buffered.forEach { send(DoubaoWire.audioAppend(it)) }
        if (pendingEnd) {
            pendingEnd = false
            send(DoubaoWire.commit())
            send(DoubaoWire.audioMute())
        }
    }

    private fun send(text: String) {
        socket?.send(text)
    }

    private fun accept(webSocket: WebSocket, text: String) {
        if (socket !== webSocket) return
        val inbound = DoubaoWire.parse(text)
        if (inbound is DoubaoInbound.Created) {
            inbound.sessionId.takeIf { it.isNotBlank() }?.let { sessionId = it }
            val first = !ready
            ready = true
            if (first) flushPending()
            if (first && !closed) onEvent(RealtimeEvent.Ready)
            return
        }
        if (inbound is DoubaoInbound.Closed) {
            late.close()
            if (closed) return
            onEvent(RealtimeEvent.Failed(""))
            return
        }
        if (inbound is DoubaoInbound.Failed) {
            Log.w(TAG, "server error: ${inbound.message}")
            onEvent(RealtimeEvent.Failed(inbound.message))
            return
        }
        val tag = responseIdOf(inbound).let { id ->
            id?.let { late.bind(it) }
            late.tag(id)
        }
        val event = inbound.event() ?: return
        when (event) {
            is RealtimeEvent.Audio -> onEvent(event.copy(turnId = tag))
            is RealtimeEvent.AssistantText -> onEvent(event.copy(turnId = tag))
            is RealtimeEvent.UserText -> onEvent(event.copy(turnId = tag))
            RealtimeEvent.TurnComplete -> {
                late.close()
                if (tag != null) return
                onEvent(RealtimeEvent.TurnComplete)
            }

            is RealtimeEvent.Failed -> onEvent(event)

            RealtimeEvent.Ready, RealtimeEvent.Interrupted -> Unit
        }
    }

    private fun responseIdOf(inbound: DoubaoInbound): String? =
        (inbound as? DoubaoInbound.WithResponse)?.responseId

    companion object {
        private val sharedClient: OkHttpClient by lazy {
            OkHttpClient.Builder()
                .connectTimeout(8, TimeUnit.SECONDS)
                .pingInterval(15, TimeUnit.SECONDS)
                .readTimeout(0, TimeUnit.MILLISECONDS)
                .build()
        }

        private const val TAG = "DoubaoRealtime"
        private const val MAX_PENDING = 200

        /** 取消之后等迟到内容的窗口，够覆盖一轮生成来回。 */
        private const val LATE_WINDOW_MS = 1_500L
    }
}
