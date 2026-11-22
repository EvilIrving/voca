package com.parley.speaker.data.realtime.gemini

import android.util.Base64
import android.util.Log
import com.parley.speaker.data.realtime.Capabilities
import com.parley.speaker.data.realtime.RealtimeEvent
import com.parley.speaker.data.realtime.RealtimeSession
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import java.util.concurrent.TimeUnit
import kotlin.random.Random

/**
 * Gemini 实时语音适配器：手动回合制。自动断句关掉之后，服务端一直等到 activityEnd 才开口。
 * 上行 16 kHz 单声道 16 位小端、20 ms 一帧；下行 24 kHz。
 *
 * 三条协议细节都在这里收口：取消不发收尾推理信号、断线按 sessionResumption 的 handle 续接、
 * 到 maxSessionMinutes 主动换一条连接并把历史压成文字带过去。
 */
class GeminiLiveSession(
    private val apiKey: String,
    private val model: String,
    private val voice: String,
    private val systemPrompt: String,
    private val onEvent: (RealtimeEvent) -> Unit,
    private val maxSessionMinutes: Int = DEFAULT_SESSION_MINUTES,
) : RealtimeSession {

    override val capabilities = Capabilities(
        canInterrupt = true,
        acceptsText = true,
        streamsTranscript = true,
        phonemeScores = false,
        maxSessionMinutes = maxSessionMinutes,
    )

    private val json = Json { ignoreUnknownKeys = true }
    private val client = sharedClient
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val pendingFrames = ArrayDeque<ByteArray>()
    private val lock = Any()

    private val cancels = CancelWindow(CANCEL_WINDOW_MS) { System.nanoTime() / 1_000_000 }

    // 换连接时要把旧上下文交给新会话，服务端不给 turn 号，只能自己记一份文字流水。
    // 收消息在 okhttp 的读线程，换连接在计时协程里，这份流水用一把锁守着。
    private val dialogue = ArrayDeque<String>()
    private var userBuffer = StringBuilder()
    private var replyBuffer = StringBuilder()
    private val transcriptLock = Any()

    @Volatile
    private var socket: WebSocket? = null

    @Volatile
    private var ready = false

    @Volatile
    private var turnOpen = false

    /** 已经发完收尾、等服务端把这一轮说完。 */
    @Volatile
    private var awaitingTurn = false

    @Volatile
    private var closed = false

    /** 服务端最近一次说「这一刻可以续」时给的 handle，空表示没有可续的上下文。 */
    @Volatile
    private var resumeHandle: String? = null

    @Volatile
    private var reconnectAttempts = 0

    @Volatile
    private var rotatePending = false

    /** 握手没完成时按下与松手都不能丢：收音帧、开始与收尾标记都留到 setupComplete 之后按序补发。 */
    @Volatile
    private var pendingStart = false

    @Volatile
    private var pendingEnd = false

    @Volatile
    private var rotationJob: Job? = null

    /** 下一次开连接要带上的 handle，只在 onOpen 里读一次。 */
    private var resumeWith: String? = null

    /** 换连接后要先灌进去的历史，setup 里据此打开 historyConfig。 */
    private var seed: String? = null

    override fun connect() {
        if (closed || socket != null) return
        Log.i(TAG, "connecting model=$model voice=$voice keyLen=${apiKey.length}")
        reconnectAttempts = 0
        open(resumeHandle = null, seed = null)
    }

    private fun open(resumeHandle: String?, seed: String?) {
        if (closed) return
        resumeWith = resumeHandle
        this.seed = seed
        ready = false
        socket = client.newWebSocket(Request.Builder().url(url()).build(), listener)
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
            webSocket.send(
                GeminiWire.setup(
                    model = model,
                    voice = voice,
                    systemPrompt = systemPrompt,
                    resumeHandle = resumeWith,
                    seeding = seed != null,
                ).toString(),
            )
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            accept(webSocket, text)
        }

        override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
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
            if (closed || resume()) return
            onEvent(RealtimeEvent.Failed(classify(t, response)))
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            if (socket !== webSocket) return
            ready = false
            socket = null
            Log.i(TAG, "closed $code $reason")
            if (closed || resume()) return
            onEvent(RealtimeEvent.Failed("会话已结束，重新连接后会接着聊"))
        }
    }

    /**
     * 服务端给过 handle 就换一条连接把上下文接回来，本轮不作废；
     * 没给过 handle 就返回 false，调用方照旧发 Failed，界面按断线作废处理。
     */
    private fun resume(): Boolean {
        val current = resumeHandle ?: return false
        if (reconnectAttempts >= MAX_RESUME_ATTEMPTS) return false
        reconnectAttempts += 1
        val wait = (RESUME_BASE_MS shl (reconnectAttempts - 1)) + Random.nextLong(RESUME_JITTER_MS)
        Log.i(TAG, "resume attempt $reconnectAttempts in ${wait}ms")
        scope.launch {
            delay(wait)
            if (!closed && socket == null) open(current, null)
        }
        return true
    }

    override fun startTurn() {
        turnOpen = true
        awaitingTurn = false
        if (!ready) {
            pendingStart = true
            return
        }
        send(GeminiWire.activityStart())
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
            // 握手没完成或正在换连接：先攒着，setupComplete 之后整段补发，这一轮不断。
            synchronized(lock) {
                if (pendingFrames.size < MAX_PENDING) pendingFrames.addLast(bytes)
            }
            return
        }
        send(GeminiWire.audio(Base64.encodeToString(bytes, Base64.NO_WRAP)))
    }

    override fun sendText(text: String) {
        send(GeminiWire.clientContent(text))
    }

    override fun endTurn() {
        turnOpen = false
        if (!ready) {
            pendingEnd = true
            Log.i(TAG, "endTurn parked until setup completes")
            return
        }
        awaitingTurn = true
        send(GeminiWire.audioStreamEnd())
        send(GeminiWire.activityEnd())
    }

    /**
     * 取消只发 activityEnd，不先发 audioStreamEnd。
     *
     * 依据是实测的收尾链路：正常说完时 audioStreamEnd(true) 与 activityEnd 一起发，服务端在
     * activityEnd 之后约 450 ms 开始回音频，也就是说服务端是收到了 audioStreamEnd 才把这段音频
     * 送去推理的。取消时省掉它，服务端拿不到「音频到此为止」的信号。activityEnd 单独发是否会被
     * 服务端当成普通收音结束，这条还没有实测数据，所以本地黑名单是主要防线，这一条只是止损。
     */
    override fun cancelTurn(turnId: String) {
        turnOpen = false
        awaitingTurn = false
        cancels.cancel(turnId)
        // 还没握手完时攒下的开始标记、音频与收尾标记说的都是这一轮，一起丢掉。
        pendingStart = false
        pendingEnd = false
        synchronized(lock) { pendingFrames.clear() }
        if (!ready) return
        send(GeminiWire.activityEnd())
    }

    override fun interrupt() {
        turnOpen = false
        awaitingTurn = false
        onEvent(RealtimeEvent.Interrupted)
    }

    override fun close() {
        closed = true
        ready = false
        turnOpen = false
        awaitingTurn = false
        rotationJob?.cancel()
        rotationJob = null
        val current = socket
        socket = null
        current?.close(1000, "bye")
        scope.cancel()
    }

    private fun flushPending() {
        if (pendingStart) {
            pendingStart = false
            send(GeminiWire.activityStart())
        }
        val buffered = synchronized(lock) {
            val copy = pendingFrames.toList()
            pendingFrames.clear()
            copy
        }
        buffered.forEach { send(GeminiWire.audio(Base64.encodeToString(it, Base64.NO_WRAP))) }
        if (pendingEnd) {
            pendingEnd = false
            awaitingTurn = true
            send(GeminiWire.audioStreamEnd())
            send(GeminiWire.activityEnd())
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
        val root = try {
            json.parseToJsonElement(text).jsonObject
        } catch (e: Exception) {
            Log.w(TAG, "bad payload: ${e.message}")
            return
        }

        root["setupComplete"]?.let {
            ready = true
            reconnectAttempts = 0
            scheduleRotation()
            // 先把上一段历史灌进去，再报 Ready：界面收到 Ready 之后发的第一句话必须排在历史后面。
            val history = seed
            seed = null
            if (history != null) send(GeminiWire.clientContent(history))
            flushPending()
            onEvent(RealtimeEvent.Ready)
            return
        }

        // 服务端只在 resumable 为真时才给新 handle，同一个 handle 只用一次；为假表示这一刻不能续，
        // 手上那个旧 handle 仍对应上一处可续的状态，留着不动。
        root["sessionResumptionUpdate"]?.jsonObject?.let { update ->
            val resumable = update["resumable"]?.jsonPrimitive?.contentOrNull == "true"
            val fresh = update["newHandle"]?.jsonPrimitive?.contentOrNull
            if (resumable && !fresh.isNullOrBlank()) resumeHandle = fresh
        }

        root["goAway"]?.let {
            Log.i(TAG, "goAway $it")
            rotate()
        }

        root["serverContent"]?.jsonObject?.let { content -> serverContent(content) }

        root["error"]?.let { error ->
            onEvent(RealtimeEvent.Failed(error.toString()))
        }
    }

    private fun serverContent(content: JsonObject) {
        val tag = cancels.tag()

        if (content["interrupted"]?.jsonPrimitive?.contentOrNull == "true") {
            awaitingTurn = false
            cancels.boundary()
            onEvent(RealtimeEvent.Interrupted)
            if (rotatePending && !turnOpen) rotate()
            return
        }

        if (content["waitingForInput"]?.jsonPrimitive?.contentOrNull == "true") {
            awaitingTurn = false
            cancels.boundary()
            if (rotatePending && !turnOpen) rotate()
        }

        content["inputTranscription"]?.jsonObject?.get("text")?.jsonPrimitive?.contentOrNull?.let { text ->
            synchronized(transcriptLock) { if (tag == null) userBuffer.append(text) }
            onEvent(RealtimeEvent.UserText(text, tag))
        }
        content["outputTranscription"]?.jsonObject?.get("text")?.jsonPrimitive?.contentOrNull?.let { text ->
            synchronized(transcriptLock) { if (tag == null) replyBuffer.append(text) }
            onEvent(RealtimeEvent.AssistantText(text, tag))
        }
        content["modelTurn"]?.jsonObject?.get("parts")?.jsonArray?.forEach { part ->
            val inline = part.jsonObject["inlineData"]?.jsonObject ?: return@forEach
            val data = inline["data"]?.jsonPrimitive?.contentOrNull ?: return@forEach
            onEvent(RealtimeEvent.Audio(Base64.decode(data, Base64.NO_WRAP), tag))
        }

        if (content["turnComplete"]?.jsonPrimitive?.contentOrNull == "true") {
            awaitingTurn = false
            flushDialogue()
            cancels.boundary()
            onEvent(RealtimeEvent.TurnComplete)
            if (rotatePending) rotate()
        }
    }

    private fun scheduleRotation() {
        rotationJob?.cancel()
        rotationJob = scope.launch {
            delay(maxSessionMinutes * MINUTE_MS)
            rotate()
        }
    }

    /**
     * 换一条连接接着聊。带 handle 就能把上下文原样续上，但那样上下文一点没变小，
     * 到点换连接本来就是为了砍开销，所以这条路径不带 handle，只带一段文字流水。
     * 用户在说话或者服务端还在作答就等到这一轮收尾再换。
     */
    private fun rotate() {
        if (closed) return
        if (turnOpen || awaitingTurn) {
            rotatePending = true
            return
        }
        rotatePending = false
        val history = digest()
        resumeHandle = null
        reconnectAttempts = 0
        ready = false
        val stale = socket
        socket = null
        Log.i(TAG, "rotating session after $maxSessionMinutes min, history=${history?.length ?: 0} chars")
        stale?.close(1000, "rotate")
        open(resumeHandle = null, seed = history)
    }

    private fun flushDialogue() {
        synchronized(transcriptLock) { flushDialogueLocked() }
    }

    private fun flushDialogueLocked() {
        if (userBuffer.isNotEmpty()) {
            dialogue.addLast("我：$userBuffer")
            userBuffer = StringBuilder()
        }
        if (replyBuffer.isNotEmpty()) {
            dialogue.addLast("对方：$replyBuffer")
            replyBuffer = StringBuilder()
        }
        while (dialogue.size > DIALOGUE_LINES) dialogue.removeFirst()
    }

    private fun digest(): String? = synchronized(transcriptLock) {
        flushDialogueLocked()
        if (dialogue.isEmpty()) return@synchronized null
        val kept = mutableListOf<String>()
        var budget = DIGEST_CHARS
        for (line in dialogue.reversed()) {
            if (line.length + 1 > budget) break
            kept.add(0, line)
            budget -= line.length + 1
        }
        if (kept.isEmpty()) return@synchronized null
        "以下是刚才的对话记录，用来接上下文。\n" +
            kept.joinToString("\n") +
            "\n保持同样的角色和场景接着聊，不要复述这段记录。"
    }

    companion object {
        /** 单条连接活到十分钟上下文已经很大，每小时开销里的主要部分来自这里，到点就换。 */
        const val DEFAULT_SESSION_MINUTES = 10

        /** 连接池与线程池跨会话复用，重连不再累积资源。 */
        private val sharedClient: OkHttpClient by lazy {
            OkHttpClient.Builder()
                .connectTimeout(8, TimeUnit.SECONDS)
                .pingInterval(15, TimeUnit.SECONDS)
                .readTimeout(0, TimeUnit.MILLISECONDS)
                .build()
        }

        private const val TAG = "GeminiLive"
        private const val MAX_PENDING = 200
        private const val MINUTE_MS = 60_000L

        /** 取消之后的观察窗，够覆盖服务端一轮生成的往返时间。 */
        private const val CANCEL_WINDOW_MS = 1_500L

        private const val MAX_RESUME_ATTEMPTS = 2
        private const val RESUME_BASE_MS = 400L
        private const val RESUME_JITTER_MS = 300L

        private const val DIALOGUE_LINES = 24
        private const val DIGEST_CHARS = 2_400
    }
}
