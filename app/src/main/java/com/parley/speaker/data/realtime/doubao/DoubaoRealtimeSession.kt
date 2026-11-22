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
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * 火山豆包端到端实时语音适配器。整条链路是二进制帧：先 StartConnection 建连接，
 * 回来 ConnectionStarted 再发 StartSession，SessionStarted 之后才能灌音频；
 * 上行 PCM16 十六千赫兹单声道，下行在 StartSession 的 tts.audio_config 里要成 pcm 二十四千赫兹十六位，
 * 不然服务端发的是 OGG Opus，播放器拿到的不是裸流。
 *
 * 手动回合制：StartSession 里把 dialog.input_mod 设成 push_to_talk 并打开 enable_custom_vad，
 * 由客户端自己宣告一段音频结束。这一条文档自身说法相左，一边写这两个开关能屏蔽服务端判停、
 * 一边写实时接口只支持 server_vad，所以这里留了 pushToTalk 开关：关掉就完全交给服务端 VAD，
 * 松手不再发结束标记。**豆包能否真正关掉自动判停尚未验证，要真机实测**，测不出来就与长按说话冲突。
 *
 * 取消没有可发的单轮中止帧，服务端也没给响应号，所以线上什么都不发，
 * 只在本地按时间窗口把这一轮之后到达的识别文本、模型文本与音频标上新旧 turnId，界面据此丢弃。
 */
class DoubaoRealtimeSession(
    private val appId: String,
    private val appKey: String,
    private val accessKey: String,
    private val voice: String = VoiceCatalog.defaultId(LiveVendor.Doubao),
    private val systemPrompt: String = "",
    private val onEvent: (RealtimeEvent) -> Unit,
    private val pushToTalk: Boolean = true,
    private val connectId: String = UUID.randomUUID().toString(),
) : RealtimeSession {

    override val capabilities = Capabilities(
        canInterrupt = true,
        // 端到端接口的文本输入通道在文档里没有写清，先按不支持报给界面，打字输入据此隐藏。
        acceptsText = false,
        streamsTranscript = true,
        phonemeScores = false,
        maxSessionMinutes = null,
    )

    private val client = sharedClient

    private val pendingFrames = ArrayDeque<ByteArray>()
    private val lock = Any()

    private val late = LateTurn(LATE_WINDOW_MS) { System.nanoTime() / 1_000_000 }

    /** 会话号由客户端生成，服务端若在 SessionStarted 里回了自己的就换成它的。 */
    @Volatile
    private var sessionId: String = UUID.randomUUID().toString()

    @Volatile
    private var socket: WebSocket? = null

    @Volatile
    private var connectionOpen = false

    @Volatile
    private var ready = false

    @Volatile
    private var turnOpen = false

    @Volatile
    private var closed = false

    @Volatile
    private var pendingEnd = false

    override fun connect() {
        if (closed || socket != null) return
        Log.i(TAG, "connecting appId=$appId voice=$voice pushToTalk=$pushToTalk keyLen=${accessKey.length}")
        val request = Request.Builder()
            .url(DoubaoWire.ENDPOINT)
            .header("X-Api-App-ID", appId)
            .header("X-Api-App-Key", appKey)
            .header("X-Api-Access-Key", accessKey)
            .header("X-Api-Resource-Id", DoubaoWire.RESOURCE_ID)
            .header("X-Api-Connect-Id", connectId)
            .build()
        socket = client.newWebSocket(request, listener)
    }

    private val listener = object : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            Log.i(TAG, "open ${response.code}")
            connectionOpen = true
            send(DoubaoWire.startConnection())
        }

        override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
            accept(webSocket, bytes.toByteArray())
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            Log.w(TAG, "unexpected text frame: ${text.take(120)}")
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
            onEvent(RealtimeEvent.Failed("正在重连"))
        }
    }

    private fun reset() {
        ready = false
        connectionOpen = false
        socket = null
    }

    private fun classify(t: Throwable, response: Response?): String = when {
        response?.code == 401 || response?.code == 403 -> ProviderFault.Key
        response?.code == 404 -> "端点不可用，请在设置中检查"
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
        // 采集本身就是十六千赫兹单声道十六位，跟上行要求一致，不需要重采样。
        val pcm = Pcm.shortsToBytes(frame)
        if (!ready) {
            synchronized(lock) {
                if (pendingFrames.size < MAX_PENDING) pendingFrames.addLast(pcm)
            }
            return
        }
        send(DoubaoWire.audio(sessionId, pcm))
    }

    override fun sendText(text: String) {
        Log.i(TAG, "text input not supported yet, dropped")
    }

    override fun endTurn() {
        turnOpen = false
        if (!pushToTalk) return
        if (!ready) {
            pendingEnd = true
            Log.i(TAG, "endTurn parked until SessionStarted")
            return
        }
        send(DoubaoWire.turnEnd(sessionId))
    }

    override fun cancelTurn(turnId: String) {
        turnOpen = false
        pendingEnd = false
        synchronized(lock) { pendingFrames.clear() }
        late.cancel(turnId)
        // 协议里没有单轮中止帧，线上什么都不发；迟到的内容靠 turnId 在界面那头丢。
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
        if (wasReady && connectionOpen) {
            send(DoubaoWire.finishSession(sessionId))
            send(DoubaoWire.finishConnection())
        }
        val current = socket
        socket = null
        current?.close(1000, "bye")
    }

    private fun flushPending() {
        val buffered = synchronized(lock) {
            val copy = pendingFrames.toList()
            pendingFrames.clear()
            copy
        }
        buffered.forEach { send(DoubaoWire.audio(sessionId, it)) }
        if (pendingEnd) {
            pendingEnd = false
            if (pushToTalk) send(DoubaoWire.turnEnd(sessionId))
        }
    }

    private fun send(frame: ByteArray) {
        socket?.send(ByteString.of(*frame))
    }

    private fun accept(webSocket: WebSocket, bytes: ByteArray) {
        if (socket !== webSocket) return
        val frame = try {
            DoubaoFrameCodec.decode(bytes)
        } catch (e: Exception) {
            Log.w(TAG, "bad frame: ${e.message}")
            return
        }
        val inbound = DoubaoWire.parse(frame)
        if (inbound is DoubaoInbound.SessionStarted) {
            DoubaoWire.sessionIdOf(frame)?.takeIf { it.isNotBlank() }?.let { sessionId = it }
            val first = !ready
            ready = true
            if (first) flushPending()
            if (first && !closed) onEvent(RealtimeEvent.Ready)
            return
        }
        if (inbound is DoubaoInbound.ConnectionStarted) {
            send(DoubaoWire.startSession(sessionId, voice, systemPrompt, pushToTalk))
            return
        }
        if (inbound is DoubaoInbound.SessionFinished) {
            late.close()
            if (closed) return
            onEvent(RealtimeEvent.Failed("正在重连"))
            return
        }
        val tag = late.tag(null)
        val event = inbound.event()
        if (event == null) return
        when (event) {
            is RealtimeEvent.Audio -> onEvent(event.copy(turnId = tag))
            is RealtimeEvent.AssistantText -> onEvent(event.copy(turnId = tag))
            is RealtimeEvent.UserText -> onEvent(event.copy(turnId = tag))
            RealtimeEvent.TurnComplete -> {
                late.close()
                if (tag != null) return
                onEvent(RealtimeEvent.TurnComplete)
            }

            is RealtimeEvent.Failed -> {
                Log.w(TAG, "server error: ${event.message}")
                onEvent(event)
            }

            RealtimeEvent.Ready, RealtimeEvent.Interrupted -> Unit
        }
    }

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
