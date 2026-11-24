package com.parley.speaker.ui.chat

import android.app.Application
import android.content.ClipData
import android.content.ClipboardManager
import android.os.SystemClock
import android.util.Log
import androidx.compose.runtime.Immutable
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.room.Room
import com.parley.speaker.audio.ClipPlayer
import com.parley.speaker.audio.AudioPlayer
import com.parley.speaker.audio.AzureCredential
import com.parley.speaker.audio.Tts
import com.parley.speaker.audio.TurnAudioStore
import com.parley.speaker.audio.SileroVad
import com.parley.speaker.audio.VoiceClip
import com.parley.speaker.audio.VoiceRecorder
import com.parley.speaker.data.SeedContent
import com.parley.speaker.data.SessionRecord
import com.parley.speaker.data.SessionStore
import com.parley.speaker.data.startOfLocalDay
import com.parley.speaker.data.analysis.ModelCatalog
import com.parley.speaker.data.analysis.ModelCatalogStore
import com.parley.speaker.data.analysis.TextModel
import com.parley.speaker.data.analysis.TextProvider
import com.parley.speaker.data.analysis.TextProviderCatalog
import com.parley.speaker.data.analysis.TurnAnalyzer
import com.parley.speaker.data.analysis.TurnAnalyzerFactory
import com.parley.speaker.data.analysis.authSpec
import com.parley.speaker.data.assessor.AssessFailure
import com.parley.speaker.data.assessor.AssessOutcome
import com.parley.speaker.data.assessor.AzureAssessor
import com.parley.speaker.data.assessor.SpeechAssessor
import com.parley.speaker.data.assessor.XfyunAssessor
import com.parley.speaker.data.assessor.assessorOf
import com.parley.speaker.data.assessor.scorableText
import com.parley.speaker.data.db.MIGRATION_1_2
import com.parley.speaker.data.db.MIGRATION_2_3
import com.parley.speaker.data.db.MIGRATION_3_4
import com.parley.speaker.data.db.MIGRATION_4_5
import com.parley.speaker.data.db.MIGRATION_5_6
import com.parley.speaker.data.db.MIGRATION_6_7
import com.parley.speaker.data.db.ParleyDatabase
import com.parley.speaker.data.realtime.Capabilities
import com.parley.speaker.data.realtime.RealtimeEvent
import com.parley.speaker.data.realtime.RealtimeSession
import com.parley.speaker.data.realtime.doubao.DoubaoRealtimeSession
import com.parley.speaker.data.realtime.gemini.GeminiLiveSession
import com.parley.speaker.data.realtime.openai.OpenAiRealtimeSession
import com.parley.speaker.data.settings.ConnectivityProbe
import com.parley.speaker.data.settings.SettingsStore
import com.parley.speaker.domain.CaptureFailure
import com.parley.speaker.domain.AnalysisSettings
import com.parley.speaker.domain.AppSettings
import com.parley.speaker.domain.AssessorProvider
import com.parley.speaker.domain.CheckInWeeks
import com.parley.speaker.domain.LinkStatus
import com.parley.speaker.domain.LiveChange
import com.parley.speaker.domain.LiveDefaults
import com.parley.speaker.domain.LiveSettings
import com.parley.speaker.domain.LiveVendor
import com.parley.speaker.domain.PronunciationReview
import com.parley.speaker.domain.ProviderLine
import com.parley.speaker.domain.Review
import com.parley.speaker.domain.Speaker
import com.parley.speaker.domain.SpeechGate
import com.parley.speaker.domain.Turn
import com.parley.speaker.domain.TurnEffect
import com.parley.speaker.domain.TurnEvent
import com.parley.speaker.domain.TurnFlow
import com.parley.speaker.domain.TurnFlowState
import com.parley.speaker.domain.TurnPhase
import com.parley.speaker.domain.VocabCard
import com.parley.speaker.domain.checkInWeeks
import com.parley.speaker.domain.checkInWindowStart
import com.parley.speaker.domain.continuationHistory
import com.parley.speaker.domain.dialogContextOf
import com.parley.speaker.domain.liveChange
import com.parley.speaker.domain.memoryMaterial
import com.parley.speaker.domain.systemPromptOf
import com.parley.speaker.domain.transcriptLine
import java.io.ByteArrayOutputStream
import java.io.File
import kotlin.math.roundToInt
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.random.Random

/** 连接状态只在内部流转，界面上不呈现：连不上就按退避重连，失败原文进日志。 */
enum class Connection { Unconfigured, Connecting, Ready, Failed }

@Immutable
data class ChatState(
    val turns: List<Turn>,
    val hint: String? = null,
    val recording: Boolean = false,
    val connection: Connection = Connection.Connecting,
    val notice: String? = null,
    /** 顶栏中段显示的会话名：新的一段不显示，接着聊某条历史会话时是那一条的名字。 */
    val sessionName: String? = null,
    /** 正在接着聊的那条历史会话；null 表示屏幕上就是手上新的一段。 */
    val sessionId: String? = null,
) {
    val round: Int get() = turns.count { it.speaker == Speaker.User }
}

class ChatViewModel(application: Application) : AndroidViewModel(application) {

    private val recorder = VoiceRecorder(viewModelScope)
    private val clipPlayer = ClipPlayer()
    private val player = AudioPlayer(application)
    private val settingsStore = SettingsStore(application)
    private val connectivity = ConnectivityProbe()
    private val store = SessionStore(application)
    private val turnAudio = TurnAudioStore(application)

    /** 模型清单的缓存与拉取。和会话库共用一个文件，只读清单那一张表。 */
    private val catalogs by lazy {
        ModelCatalogStore(
            Room.databaseBuilder(application, ParleyDatabase::class.java, "parley.db")
                .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7)
                .build()
                .modelCatalogs(),
        )
    }

    /** 朗读凭据与发音评测共用同一份 Azure 语音资源，缺凭据或未成功时保持静默。 */
    private val tts by lazy {
        Tts(player) {
            val assessor = settingsStore.settings.value.assessor
            AzureCredential(assessor.azureKey, assessor.azureRegion)
        }
    }

    val settings: StateFlow<AppSettings> = settingsStore.settings

    /** 出厂的系统提示词，设置页那个「恢复默认」要向它回来。 */
    val defaultPrompt: String get() = settingsStore.defaultPrompt

    private val _state = MutableStateFlow(
        ChatState(
            turns = emptyList(),
            hint = null,
            connection = Connection.Connecting,
        ),
    )
    val state: StateFlow<ChatState> = _state.asStateFlow()

    /** 最近收掉的那条会话记录：自动命名写好之后会被替换一次。 */
    private val _lastSession = MutableStateFlow<SessionRecord?>(null)
    val lastSession: StateFlow<SessionRecord?> = _lastSession.asStateFlow()

    private val _sessions = MutableStateFlow<List<SessionRecord>>(emptyList())
    val sessions: StateFlow<List<SessionRecord>> = _sessions.asStateFlow()

    /** 设置页那张打卡热力图：收段记录一有新的一份就重算，界面不再自己扫库。 */
    private val _checkIn = MutableStateFlow(checkInWeeks(emptyList(), System.currentTimeMillis()))
    val checkIn: StateFlow<CheckInWeeks> = _checkIn.asStateFlow()

    /** 收音一停就量出来的真实长度，按轮 id 存着，落库时挂到那一轮上。 */
    private val spokenMs = mutableMapOf<String, Long>()
    private var capturingTurnId: String? = null

    // 电平每二十毫秒一帧、持续整段收音，绝不新建数组：采集侧就地把数值写进同一个缓冲，
    // 界面只订阅帧计数，重绘一次读一次。
    private val levelBuffer = FloatArray(BarCount)
    val levels: FloatArray get() = levelBuffer

    private val _levelFrame = MutableStateFlow(0L)
    val levelFrame: StateFlow<Long> = _levelFrame.asStateFlow()

    private val _elapsedMs = MutableStateFlow(0L)
    val elapsedMs: StateFlow<Long> = _elapsedMs.asStateFlow()

    /** 暂停态摊在输入条上的那一段：波形、时长与播到哪儿。空表示没有预览。 */
    private val _voicePreview = MutableStateFlow<VoicePreview?>(null)
    val voicePreview: StateFlow<VoicePreview?> = _voicePreview.asStateFlow()
    private var previewClip: VoiceClip? = null
    private var previewJob: Job? = null

    // 只存扁平的卡片：分档、计数与排序由 `vocabPage` 一处算，界面与角标不会各用一份「今天」。

    private val _vocab = MutableStateFlow<List<VocabCard>>(emptyList())
    val vocab: StateFlow<List<VocabCard>> = _vocab.asStateFlow()

    private val _providers = MutableStateFlow<List<ProviderLine>>(emptyList())
    val providers: StateFlow<List<ProviderLine>> = _providers.asStateFlow()

    /** 文本分析七家的接入参数，models 是缓存里的可见清单，界面只读这一份不取静态目录。 */
    private val _textProviders = MutableStateFlow(TextProviderCatalog.all)
    val textProviders: StateFlow<List<TextProvider>> = _textProviders.asStateFlow()

    private val _report = MutableStateFlow<String?>(null)
    val report: StateFlow<String?> = _report.asStateFlow()

    private val events = Channel<TurnEvent>(Channel.UNLIMITED)
    private var flow = TurnFlowState()
    private var connection = Connection.Connecting
    private var notice: String? = null
    private var noticeId = 0L
    private var noticeJob: Job? = null
    private var session: RealtimeSession? = null

    /** 当前这一段的账：起点、上次活动、有没有收过。四种收段时机都从这里过。 */
    private val ledger = SessionLedger()

    /** 当前这一段开场时定下的系统提示词。 */
    private var plan = SegmentPlan(settingsStore.settings.value.prompt)

    /**
     * 全局记忆：跨会话保留的那一段，界面看不见也改不了。库里留着上一场写下的那份就接着用，
     * 库是空的时候才种下 [SeedContent.DefaultMemory] 那段口吻；每轮由改写请求整段重写并写库。
     * 建连接时它接在系统提示词后面进 setup，换连接、断线重连都能把最新的那份带上。
     */
    private val memory = MemoryLedger(persist = { store.saveMemory(it) })

    /** 当前连接自报的能力清单，打字输入与开场问候按它开关。 */
    private var capabilities: Capabilities? = null
    private var timerJob: Job? = null

    /** 松手后的 VAD 判定：上一轮还没判完就来了新的一轮，先把它掐掉。 */
    private var vadJob: Job? = null

    /** 每次开连接加一，适配器回调里带着这一格，旧连接迟到的事件按作废处理。 */
    private var connectSeq = 0
    private var reconnect = ReconnectState()
    private var reconnectJob: Job? = null

    /** 正在接着聊的那条历史会话；null 表示手上的这一段是新开的。 */
    private var continuing: SessionRecord? = null

    /** 这一段开场时带进来的历史流水：接着聊的那一条重连也要把同一段带上。 */
    private var historyOf: String? = null

    /** 正在读进来的那条历史会话：连点两条时后来的接管，前一条的连接不会留在外面。 */
    private var resumeJob: Job? = null
    private var startedAt = 0L
    private var greeted = false
    private val turnVocab = mutableMapOf<String, List<VocabCard>>()
    private val _linkStatus = MutableStateFlow<Map<String, LinkStatus>>(emptyMap())

    private val sessionId: String get() = ledger.current.id

    /** 已经落到文件上的助手轮次，持久化时据此补 audioPath。 */
    private val recordedTurns = mutableSetOf<String>()
    private val audioWrites = Channel<AudioChunk>(Channel.UNLIMITED)

    /** 删掉的那几轮还会继续吐音频：出声与写盘都先问它一句。 */
    private val audio = AudioGate()

    /** 这一轮有没有人说话由本地 VAD 判断，模型随包走，第一次用到时在后台建会话。 */
    private val vad = SileroVad {
        getApplication<Application>().assets.open(SileroVad.AssetName).readBytes()
    }

    // 上行快照的读写跨线程，缓冲本身不设可见性保证，所有触碰都在这一把锁里完成。
    private val uplinkLock = Any()
    private val uplink = ByteArrayOutputStream()

    init {
        viewModelScope.launch(Dispatchers.Default) { runCatching { vad.warmUp() } }
        viewModelScope.launch {
            for (event in events) handle(event)
        }
        // 录音文件按到达顺序追加，单消费者保证块序，播放时才不会把同一轮的话打乱。
        // 过闸才写：删掉的那一轮排着的分片比删除动作晚到，不拦下来会重写出一份残段。
        viewModelScope.launch {
            for (chunk in audioWrites) {
                if (audio.accepts(chunk.turnId)) turnAudio.append(chunk.turnId, chunk.pcm)
            }
        }
        viewModelScope.launch {
            // 冷启动开的是新的一段：上一次的对话留在历史里回看，不搬进这一段的列表。
            _vocab.value = store.loadVocab()
            refreshProviders()
            publishCachedModels()
            // 记忆先于连接到位，第一条连接就带着上一次留下来的事。
            memory.seedWith(
                runCatching { store.globalMemory() }.getOrDefault("").ifBlank { SeedContent.DefaultMemory },
            )
            connect()
        }
        // 改写一条一条走，每条都读当下这份记忆，并进去的东西不会互相覆盖。
        viewModelScope.launch {
            memory.drain(
                materialOf = { turnId -> memoryMaterial(flow.turns, turnId) },
                rewrite = { current, material -> analyzer().memorize(current, material) },
                continuous = true,
            )
        }
        viewModelScope.launch { turnAudio.prune() }
        viewModelScope.launch { backfillSessionNames() }
        listenSettings()
        watchIdle()
    }

    /**
     * 设置变了只做两件事：凭据或供应商换了重建连接，其余的只提一句要等新会话。
     * 练习中改音色或模型不碰在跑的连接，正在进行的一轮不会被掐断。
     */
    private fun listenSettings() {
        viewModelScope.launch {
            var previous = settingsStore.settings.value
            settingsStore.settings.drop(1).collect { next ->
                when (liveChange(previous, next)) {
                    LiveChange.Rebuild -> reconnect()
                    LiveChange.Voice -> showNotice("音色将在新会话生效")
                    LiveChange.Model -> showNotice("模型将在新会话生效")
                    LiveChange.Prompt -> showNotice("提示词将在新会话生效")
                    LiveChange.None -> Unit
                }
                previous = next
            }
        }
    }

    private fun dispatch(event: TurnEvent) {
        // 任何一道回合事件都算活动，静默三十分钟从这一刻重新数。
        ledger.touch()
        events.trySend(event)
    }

    private suspend fun handle(event: TurnEvent) {
        when (event) {
            TurnEvent.SessionReady -> {
                connection = Connection.Ready
                // 握手成功一次，退避序号就从一秒重新数。
                cancelReconnect()
                clearNotice()
                if (!greeted) {
                    greeted = true
                    // 开场问候本身就是一条文字消息，线路不收文字就一条都不发，改弹一条说明。
                    when (textGate(Connection.Ready, capabilities)) {
                        TextGate.Allowed ->
                            session?.sendText(openingOf(SeedContent.SessionOpening, GreetingPrompt))
                        TextGate.Unsupported -> showNotice(TextUnsupported)
                        TextGate.Waiting, TextGate.Unconfigured -> Unit
                    }
                }
            }

            TurnEvent.SessionLost -> connection = Connection.Failed
            else -> Unit
        }

        val reduction = TurnFlow.reduce(flow, event, ::newTurnId)
        flow = reduction.state
        applyEffects(reduction.effects)
        if (event in AttachAudioEvents) attachAudioPaths()
        project()
    }

    private fun applyEffects(effects: List<TurnEffect>) {
        effects.forEach { effect ->
            when (effect) {
                is TurnEffect.StartCapture -> {
                    capturingTurnId = effect.turnId
                    beginCaptureOrRetry()
                }

                TurnEffect.StopCapture -> {
                    timerJob?.cancel()
                    timerJob = null
                    stopPreview()
                    // 这一段是发出去还是作废，落到文件上的那只 OGG 都不再有人用，
                    // 封口前先量出它的真实长度，打卡认的就是这个数。
                    val captured = capturingTurnId
                    capturingTurnId = null
                    viewModelScope.launch {
                        recorder.finish()?.let { clip ->
                            if (captured != null) spokenMs[captured] = clip.durationMs
                            runCatching { File(clip.path).delete() }
                        }
                    }
                    java.util.Arrays.fill(levelBuffer, 0f)
                    _levelFrame.value += 1
                }

                TurnEffect.SendTurnStart -> session?.startTurn()
                TurnEffect.SendTurnEnd -> session?.endTurn()
                is TurnEffect.SendText -> session?.sendText(effect.text)

                // 取消要发到适配器：服务端停下这一轮并开黑名单，迟到的文本与音频由 TurnFlow 认 id 丢掉。
                is TurnEffect.CancelTurn -> session?.cancelTurn(effect.turnId)

                is TurnEffect.Play -> {
                    val turnId = effect.turnId
                    // 已经删掉的那一轮：声音与录音都不该再多活一秒。
                    if (turnId != null && !audio.accepts(turnId)) return@forEach
                    player.enqueue(effect.pcm)
                    turnId?.let { id ->
                        recordedTurns += id
                        audioWrites.trySend(AudioChunk(id, effect.pcm))
                    }
                }

                TurnEffect.StopPlayback -> player.flush()

                is TurnEffect.Persist -> {
                    val turn = effect.turn
                    val path = if (turn.audioPath == null && turn.id in recordedTurns) {
                        turnAudio.path(turn.id)
                    } else {
                        turn.audioPath
                    }
                    val toSave = if (turn.sessionId == null) {
                        turn.copy(sessionId = sessionId, audioPath = path)
                    } else {
                        turn.copy(audioPath = path)
                    }
                    // 自己说的那一轮带上量到的长度；打字发的、助手说的都没有这一段录音。
                    val spoken = if (turn.speaker == Speaker.User) spokenMs.remove(turn.id) else null
                    val withSpoken = if (spoken == null) toSave else toSave.copy(durationMs = spoken)
                    viewModelScope.launch { store.saveTurn(withSpoken) }
                }

                is TurnEffect.Delete -> {
                    // 作废掉的那一轮不会再落库，量到的长度也跟着丢，别留在这张表里。
                    spokenMs.remove(effect.turnId)
                    purgeTurn(effect.turnId)
                }

                is TurnEffect.Analyze -> launchAnalysis(effect.turnId, effect.text)
                is TurnEffect.LearnVocab -> {
                    turnVocab[effect.turnId] = effect.cards
                    mergeVocab(effect.cards)
                    viewModelScope.launch { store.recordVocab(effect.cards) }
                }

                is TurnEffect.Notify -> showNotice(effect.message)
            }
        }
    }

    /** 上一轮句柄刚释放时设备可能短暂占用，重试一次再判失败，避免快速连按时按下去没反应。 */
    private fun beginCaptureOrRetry() {
        // 链路没就绪时麦克风本来就不该开：按失败收尾并说清是网络这一层，
        // 不能让用户看到「请检查权限」这种指向错误的提示。
        if (session == null) {
            dispatch(TurnEvent.CaptureFailed(CaptureFailure.Offline))
            return
        }
        if (tryStartCapture()) return
        viewModelScope.launch {
            delay(180)
            if (!tryStartCapture()) {
                dispatch(TurnEvent.CaptureFailed(recorder.lastFailure ?: CaptureFailure.Busy))
            }
        }
    }

    private fun tryStartCapture(): Boolean {
        val current = session ?: return false
        uplink.reset()
        val started = recorder.start(
            getApplication<Application>().cacheDir,
            onFrame = { frame ->
                appendUplink(frame)
                current.sendAudio(frame)
            },
            onLevel = ::pushLevel,
            // 采样线程自己断了（读写异常、句柄被收走）时按失败收尾，输入条不停在录音态。
            onInterrupted = { cause -> dispatch(TurnEvent.CaptureFailed(cause)) },
        )
        if (started) {
            startedAt = SystemClock.elapsedRealtime()
            _elapsedMs.value = 0L
            startTimer()
        }
        return started
    }

    private fun startTimer() {
        timerJob?.cancel()
        timerJob = viewModelScope.launch {
            while (isActive) {
                _elapsedMs.value = SystemClock.elapsedRealtime() - startedAt
                delay(80)
            }
        }
    }

    private fun project() {
        val phase = flow.phase
        _state.update {
            it.copy(
                turns = flow.turns,
                recording = phase is TurnPhase.Recording,
                connection = connection,
                notice = notice,
                sessionName = continuing?.name?.ifBlank { null },
                sessionId = continuing?.id,
            )
        }
    }

    /**
     * 按供应商分发到各自的适配器。未配置判定按当前供应商的凭据，构造参数与各家真实签名对齐：
     * Gemini 吃密钥、模型、音色与系统提示，OpenAI 叫 instructions，豆包吃一把密钥且模型号写在协议里。
     * [history] 是接着一条历史会话聊时要先交代的那段流水，新开的一段为 null。
     */
    private fun connect(history: String? = historyOf) {
        val current = settingsStore.settings.value
        val live = current.live
        if (!live.ready) {
            connection = Connection.Unconfigured
            cancelReconnect()
            project()
            return
        }
        connectSeq += 1
        val seq = connectSeq
        // 记忆每一轮都在变，连接却只在开场、换连接与重连时建，系统提示与全局记忆在这里一起进 setup。
        // 正在进行的那条连接上下文里本来就有这段对话，中途再灌一份只会把上下文撑大。
        val instructions = systemPromptOf(plan.prompt, memory.text)
        val created = when (live.provider) {
            LiveVendor.Gemini -> GeminiLiveSession(
                apiKey = live.geminiKey,
                model = live.model,
                voice = live.voice,
                systemPrompt = instructions,
                onEvent = { event -> onEvent(seq, event) },
                history = history,
            )

            LiveVendor.OpenAi -> OpenAiRealtimeSession(
                apiKey = live.openAiKey,
                model = live.model,
                voice = live.voice,
                instructions = instructions,
                onEvent = { event -> onEvent(seq, event) },
                history = history,
            )

            LiveVendor.Doubao -> DoubaoRealtimeSession(
                apiKey = live.doubaoApiKey,
                voice = live.voice,
                systemPrompt = instructions,
                onEvent = { event -> onEvent(seq, event) },
                context = dialogContextOf(flow.turns),
            )
        }
        session = created
        capabilities = created.capabilities
        connection = Connection.Connecting
        project()
        created.connect()
    }

    /** 关掉当前连接，并让它在关掉之后还排在队列里的事件不再算数。 */
    private fun dropSession() {
        connectSeq += 1
        session?.close()
        session = null
        capabilities = null
    }

    private fun onEvent(seq: Int, event: RealtimeEvent) {
        if (seq != connectSeq) return
        when (event) {
            RealtimeEvent.Ready -> dispatch(TurnEvent.SessionReady)
            is RealtimeEvent.UserText -> dispatch(TurnEvent.UserText(event.text, event.turnId))
            is RealtimeEvent.AssistantText -> dispatch(TurnEvent.AssistantText(event.text, event.turnId))
            is RealtimeEvent.Audio -> dispatch(TurnEvent.Audio(event.pcm, event.turnId))
            RealtimeEvent.Interrupted -> dispatch(TurnEvent.Interrupted)
            RealtimeEvent.TurnComplete -> dispatch(TurnEvent.TurnComplete)
            is RealtimeEvent.Failed -> {
                // 认得出类别的（额度、鉴权、模型）才给一句能动手的话，网络与协议杂音只进日志。
                if (event.message.isNotBlank()) showNotice(event.message) else Log.i(Tag, "连接失败，按退避重连")
                dispatch(TurnEvent.SessionLost)
                // 适配器自己那两次带 handle 的短退避已经用尽，或者初次握手就不成立，长退避从这里接手。
                viewModelScope.launch { scheduleReconnect(seq) }
            }
        }
    }

    /**
     * 未连接后的自动重连：一秒起、逐次翻倍、封顶三十秒，每次叠 0..300 ms 抖动。
     * 只排一次，[reconnect] 的序号只在真正握手成功、收段与改配置时归零。
     */
    private fun scheduleReconnect(seq: Int) {
        if (seq != connectSeq) return
        // 这一段已经收掉就不必再接：下一次操作会开新的一段并重连。
        if (ledger.current.closed) return
        if (!settingsStore.settings.value.live.ready) return
        if (reconnectJob?.isActive == true) return
        val mine = reconnect
        val wait = mine.waitMs(Random.nextDouble())
        reconnect = mine.scheduled()
        reconnectJob = viewModelScope.launch {
            delay(wait)
            reconnectJob = null
            // 这一轮等待期间被取消过就作废，别把已经收掉的那一段重新拉起来。
            if (!reconnect.isCurrent(mine.epoch) || seq != connectSeq) return@launch
            retryNow()
        }
    }

    private fun cancelReconnect() {
        reconnectJob?.cancel()
        reconnectJob = null
        reconnect = reconnect.reset()
    }

    /** 退避到点：跳过当前这次等待，序号不动，下一次失败照旧翻倍。 */
    private fun retryNow() {
        reconnectJob?.cancel()
        reconnectJob = null
        reconnect = reconnect.skipped()
        dropSession()
        connect()
    }

    private fun launchAnalysis(turnId: String, text: String) {
        // 上行的十六千赫兹原始音频就是评测要的形态，回合收尾这一刻先取走快照。
        val pcm = takeUplink()
        val assessor = assessorOf(settingsStore.settings.value.assessor)
        viewModelScope.launch {
            val analysis = analyzer().analyze(text, turnId)
            if (analysis == null) {
                if (analysisRoute(flow.turns, turnId) == AnalysisRoute.Flow) {
                    dispatch(TurnEvent.AnalysisFailed(turnId))
                }
                return@launch
            }
            val review = withPronunciation(text, analysis.review, assessor, pcm)
            when (analysisRoute(flow.turns, turnId)) {
                AnalysisRoute.Flow -> dispatch(TurnEvent.AnalysisDone(turnId, review, analysis.vocab))
                AnalysisRoute.Store -> writeBackAnalysis(turnId, review, analysis.vocab)
            }
        }
        // 记忆与评测各跑各的：评测回来说的是这一句说的对不对，记忆说的是这一场还该记住什么。
        memory.offer(turnId)
    }

    /**
     * 收段把这一轮带走了：结果进不了状态机，直接按 id 写回库，评测与生词都不丢。
     * 库里已经没有这一行（用户删了它）就什么都不写。
     */
    private suspend fun writeBackAnalysis(turnId: String, review: Review, vocab: List<VocabCard>) {
        store.saveReview(turnId, review)
        if (vocab.isEmpty()) return
        mergeVocab(vocab)
        store.recordVocab(vocab)
    }

    /**
     * 发音评测只在评测引擎配置齐、这一轮有参考文本、本地也确实录到音频时才发起；
     * 三项缺一项就一个请求都不发，review 原样留着，界面按 pronunciation 是否为空决定画不画发音格。
     * 参考文本不是英语时也一个请求都不发：两家引擎只认英语，发过去只会被服务端撂掉，
     * 换回来一句指向服务故障的提示，把「这轮说的是中文」说成了服务的锅。
     * 送出去没拿回分时，失败原因已经在适配器那层归好类，这里只负责把那一句话浮出来，原文不进界面。
     */
    private suspend fun withPronunciation(
        text: String,
        review: Review,
        assessor: SpeechAssessor?,
        pcm: ByteArray,
    ): Review {
        if (assessor == null || !scorableText(text) || pcm.isEmpty()) return review
        val outcome = try {
            assessor.assess(pcm, text, AssessorLocale)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(Tag, "评测异常: ${e.message}")
            AssessOutcome.Failed(AssessFailure.Service)
        }
        val result = when (outcome) {
            is AssessOutcome.Failed -> {
                showNotice(outcome.failure.notice)
                return review
            }
            is AssessOutcome.Scored -> outcome.result
        }
        val score = (result.total ?: result.accuracy)?.roundToInt() ?: return review
        val note = listOfNotNull(
            result.accuracy?.let { "准确度 ${it.roundToInt()}" },
            result.fluency?.let { "流利度 ${it.roundToInt()}" },
            result.prosody?.let { "韵律 ${it.roundToInt()}" },
        ).joinToString(" · ")
        return review.copy(
            pronunciation = PronunciationReview(
                score = score.coerceIn(0, 100),
                note = note,
                phonemes = result.issues,
            ),
        )
    }

    /** 音频文件路径晚于文本落库，回合收尾时把还缺路径的那几轮补写一次；返回补上的那几轮。 */
    private fun attachAudioPaths(): List<Turn> {
        val pending = flow.turns
            .filter { it.speaker == Speaker.Echo && it.audioPath == null && it.id in recordedTurns }
            .map { it.id }
            .toSet()
        if (pending.isEmpty()) return emptyList()
        flow = flow.copy(
            turns = flow.turns.map { turn ->
                if (turn.id in pending) turn.copy(audioPath = turnAudio.path(turn.id)) else turn
            },
        )
        val filled = flow.turns.filter { it.id in pending }
        viewModelScope.launch {
            filled.forEach {
                store.saveTurn(if (it.sessionId == null) it.copy(sessionId = sessionId) else it)
            }
        }
        return filled
    }

    private fun mergeVocab(cards: List<VocabCard>) {
        val now = System.currentTimeMillis()
        val current = _vocab.value
        val incoming = cards.distinctBy { it.word.lowercase() }.associateBy { it.word.lowercase() }
        val known = current.mapTo(HashSet()) { it.word.lowercase() }
        // 再见到的词推进最近出现时间并加一次复现，与库里 refresh 写法一致，跨天后它会挪回今天。
        val merged = current.map { card ->
            incoming[card.word.lowercase()]?.let { card.copy(repeats = card.repeats + 1, lastSeenAt = now) } ?: card
        }
        val fresh = incoming.values
            .filterNot { it.word.lowercase() in known }
            .map { it.copy(lastSeenAt = now) }
        _vocab.value = fresh + merged
    }

    /** 配置变更后重开连接：这一轮退避作废，序号回到一秒。 */
    fun reconnect() {
        cancelReconnect()
        dispatch(TurnEvent.SessionLost)
        dropSession()
        plan = plan.resuming(settingsStore.settings.value.prompt)
        // 接着聊的那一段重连时模型已经从流水里接过上下文，开场那一句不再发。
        greeted = continuing != null
        connect()
    }

    /**
     * 按下去的这一下有没有被收下。输入条按住够久才调它，被拒时只弹一条提示并返回假，
     * 界面据此退回 Idle，不装作在收音。
     */
    fun onRecordPressStart(): Boolean {
        // 上一段已经收尾（静默到点或刚从一条历史会话退出来），这一下就是新的一段的第一手。
        ledger.touch()
        when (connection) {
            Connection.Unconfigured -> {
                showNotice(FillLiveKey)
                return false
            }

            // 连接还在路上就当这一下没按过，不弹任何提示，状态只在日志里。
            Connection.Connecting, Connection.Failed -> {
                Log.i(Tag, "连接未就绪，这一下不收")
                return false
            }

            Connection.Ready -> Unit
        }
        // 上一轮的判定还在跑就掐掉：它的结论属于上一段，落到这一轮会把这一段判错。
        vadJob?.cancel()
        vadJob = null
        dispatch(TurnEvent.PressStart)
        return true
    }

    fun onRecordArmed(armed: Boolean) = dispatch(TurnEvent.ArmCancel(armed))

    fun onRecordPressEnd(cancelled: Boolean) {
        vadJob?.cancel()
        vadJob = null
        if (cancelled) {
            recorder.cancel()
            stopPreview()
            dispatch(TurnEvent.Cancel)
            dropUplink()
            return
        }
        // 这一段里没有人说话就在这里结束：本地回 Idle，不发收尾、不落气泡。
        // 判定要跑一遍模型，几百毫秒，挪到后台；采集照旧跑到收尾事件把它停掉，多录的那点静音无害。
        // 模型与电平两条判据都判掉才算静音：模型看频谱形状，气声近了以后形状不典型；
        // 电平那条只看这一段有没有持续高出自己底噪的时间。任何一条说有人声就发出去。
        val pcm = peekUplink()
        if (pcm.isEmpty()) {
            settleSilentTurn()
            return
        }
        vadJob = viewModelScope.launch {
            val speechMs = withContext(Dispatchers.Default) {
                SpeechGate.longestSpeechMs(vad.probabilities(pcm))
            }
            // 电平那条要增益之前的那一串：放大后的电平带着增益爬坡，安静的一段也会被判成说话。
            val levels = recorder.peekLevels()
            val loudMs = withContext(Dispatchers.Default) {
                SpeechGate.longestLoudMs(levels, VoiceRecorder.FRAME_MS.toLong())
            }
            if (SpeechGate.shouldDrop(speechMs) && SpeechGate.shouldDrop(loudMs)) {
                Log.i(Tag, "这一段没人说话，人声 ${speechMs}ms 电平 ${loudMs}ms")
                settleSilentTurn()
                return@launch
            }
            dispatch(TurnEvent.Commit)
        }
    }

    /**
     * 暂停与恢复。暂停把采集停在原地并封住这一段，波形与时长交给输入条的预览；
     * 中途不能再开一段新的，所以这里只认 hold 与 resume，新的一段要等 StopCapture 收完。
     */
    fun onRecordHold(held: Boolean) {
        if (held) {
            timerJob?.cancel()
            timerJob = null
            stopPreview()
            viewModelScope.launch {
                val clip = recorder.hold() ?: return@launch
                previewClip = clip
                _voicePreview.value = VoicePreview(clip.waveform, clip.durationMs, 0f, false)
            }
            return
        }
        stopPreview()
        if (!recorder.resume()) {
            // 麦克风没抢回来这一段就继续不下去，当场收掉，界面不留在锁定态。
            showNotice("麦克风被占用")
            dispatch(TurnEvent.Cancel)
            dropUplink()
            return
        }
        startedAt = SystemClock.elapsedRealtime() - _elapsedMs.value
        startTimer()
    }

    /** 预览条上的播放键：在放就停下，停下再点从头放。 */
    fun togglePreviewPlayback() {
        val preview = _voicePreview.value ?: return
        val clip = previewClip ?: return
        if (preview.playing) {
            clipPlayer.pause()
            previewJob?.cancel()
            previewJob = null
            _voicePreview.value = preview.copy(playing = false, progress = clipPlayer.progress())
            return
        }
        viewModelScope.launch {
            if (!clipPlayer.prepare(clip.path) || !clipPlayer.start()) return@launch
            _voicePreview.value = (_voicePreview.value ?: preview).copy(playing = true)
            previewJob?.cancel()
            previewJob = viewModelScope.launch {
                while (isActive) {
                    delay(PreviewTickMs)
                    val progress = clipPlayer.progress()
                    val playing = clipPlayer.isPlaying()
                    _voicePreview.value = _voicePreview.value?.copy(progress = progress, playing = playing)
                    if (!playing) break
                }
            }
        }
    }

    /** 预览只在暂停态存在：放掉播放器、清掉波形，回到录音态与收尾都不该留着它。 */
    private fun stopPreview() {
        previewJob?.cancel()
        previewJob = null
        clipPlayer.release()
        previewClip = null
        _voicePreview.value = null
    }

    /** 打字发送：不收音也不开新的 activity，落一条用户轮之后直接送文本。 */
    fun sendText(text: String) {
        if (text.isBlank()) return
        when (textGate(connection, capabilities)) {
            TextGate.Allowed -> dispatch(TurnEvent.TextTurn(text))
            TextGate.Unconfigured -> showNotice(FillLiveKey)
            TextGate.Unsupported -> showNotice(TextUnsupported)
            // 连接没就绪时这一句根本发不出去，界面不出声，日志里留一行。
            TextGate.Waiting -> Log.i(Tag, "连接未就绪，文字没有发出")
        }
    }

    fun retryAnalysis(turnId: String) = dispatch(TurnEvent.RetryAnalysis(turnId))

    /** 气泡译文：库里已有就直接给，没有才请求一次，回来先显示再随轮落库。 */
    suspend fun translate(text: String): String? {
        if (text.isBlank()) return null
        val turn = flow.turns.lastOrNull { it.speaker == Speaker.Echo && it.text == text }
        turn?.translation?.let { return it }
        val translated = analyzer().translate(text) ?: return null
        if (turn != null) {
            dispatch(TurnEvent.TranslationDone(turn.id, translated))
            viewModelScope.launch { store.saveTurn(turn.copy(translation = translated)) }
        }
        return translated
    }

    /** 气泡上的书签：库里的 saved 位是唯一数据源，界面读 Turn.saved。 */
    fun setSaved(turnId: String, saved: Boolean) {
        val turn = flow.turns.firstOrNull { it.id == turnId }
        if (turn != null) {
            if (turn.saved == saved) return
            flow = flow.copy(turns = flow.turns.map { if (it.id == turnId) it.copy(saved = saved) else it })
            project()
        }
        viewModelScope.launch { store.setSaved(turnId, saved) }
        if (saved) showNotice("已收进生词本")
    }

    /** 气泡朗读键：这一轮留下过录音就播录音，没有就交给语音合成读正文。 */
    fun playTurn(turnId: String) {
        val turn = turnById(turnId) ?: return
        viewModelScope.launch {
            val pcm = turnAudio.read(turnId)
            if (pcm != null) player.enqueue(pcm) else speakText(turn.text)
        }
    }

    /** 长按菜单里的复制：只有正文进系统剪贴板，译文那行是辅助，不跟着贴出去。 */
    fun copyTurn(turnId: String) {
        val turn = turnById(turnId) ?: return
        val clip = getApplication<Application>().getSystemService(ClipboardManager::class.java) ?: return
        clip.setPrimaryClip(ClipData.newPlainText(CopyLabel, copiedText(turn)))
        showNotice("已复制")
    }

    /**
     * 长按菜单里的删除：这一轮从列表、库里与录音文件上一起撤掉，只动这一轮。
     * 会话记录那一行不重算，轮次与双项均值是收段那一刻算下的，删一句不回写历史统计。
     */
    fun deleteTurn(turnId: String) {
        // 正在播的那一段先停：删掉的那一句不该还把声音放完。
        player.stop()
        if (flow.turns.any { it.id == turnId }) {
            flow = flow.copy(turns = flow.turns.minusTurn(turnId))
            project()
        }
        purgeTurn(turnId)
    }

    /**
     * 撤掉一轮留下的东西：库里的行、录音文件、以及排队中的音频分片。
     * 长按菜单的删除与状态机发的 Delete 共用这一份，两处不会各写一遍。
     */
    private fun purgeTurn(turnId: String) {
        recordedTurns -= turnId
        audio.delete(turnId)
        viewModelScope.launch {
            store.deleteTurn(turnId)
            turnAudio.delete(turnId)
        }
    }

    /** 当前这一段找一轮：接着聊的历史会话与手上的新一段走同一份列表，轮次 id 一致。 */
    private fun turnById(turnId: String): Turn? = flow.turns.firstOrNull { it.id == turnId }

    /** 生词卡上的朗读：直接朗读例句句子，例句缺失时朗读词条。 */
    fun speakCard(card: VocabCard) {
        val word = card.word.trim()
        val example = card.example.trim()
        val text = example.ifEmpty { word }
        speakText(text)
    }

    fun speakText(text: String) {
        if (text.isBlank()) return
        // 没填评测密钥就别去撞一次失败：先在界面上说清楚要去哪里填。
        val assessor = settingsStore.settings.value.assessor
        if (assessor.azureKey.isBlank() || assessor.azureRegion.isBlank()) {
            showNotice(FillAssessorKey)
            return
        }
        viewModelScope.launch {
            val ok = tts.speak(text)
            if (!ok) showNotice(TtsFailed)
        }
    }

    /**
     * 收段：把这一段落库，轮次与均值由 [SessionStore.closeSession] 当场算好，再异步取一次会话名。
     * 同一段只会写一遍，切场景与静默同时到点也只是一条记录。收完停在闭合态，
     * 开新的一段由调用方决定：切场景、点新会话、静默之后的下一手操作各接各的。
     */
    private fun endSegment(): Boolean {
        val ended = ledger.closeIfOpen() ?: return false
        val closedTurns = flow.turns
        // 这一段到此为止，自动重连与计时都停，圆点停在红色。
        cancelReconnect()
        timerJob?.cancel()
        timerJob = null
        recorder.cancel()
        stopPreview()
        player.stop()
        dropSession()
        attachAudioPaths()
        // 这一屏对话留到下一段开起来再换，自动命名要拿它算。
        flow = flow.copy(phase = TurnPhase.Offline, cancelArmed = false)
        connection = if (settingsStore.settings.value.live.ready) Connection.Failed else Connection.Unconfigured
        clearNotice()
        greeted = false
        player.flush()
        project()
        // 一轮都没有的一段没有可记的东西，不往历史里塞空壳，命名也不必发一次请求。
        if (closedTurns.isEmpty()) return true
        viewModelScope.launch {
            val record = runCatching { store.closeSession(ended.id, ended.startedAt) }.getOrNull()
            publishSession(record)
            loadSessions()
            // 名字只写一次：接着聊回来的那一段早就有名字，收回去时原样留着。
            if (record == null || record.name.isNotBlank()) return@launch
            // 材料取整段对话；manualName 为真的会话在 SQL 那一层就挡住自动名。
            val name = nameOfSession(ended.id, closedTurns) ?: return@launch
            // 自动名只写给收口的那一段：期间换了段、或者用户自己改过名，这一次自动名就丢掉。
            publishSession(autoNameTarget(_lastSession.value, ended.id, name) ?: return@launch)
            loadSessions()
        }
        return true
    }

    /** 收段之后紧接着开新的一段：点新会话、离开接着聊的那一条都走这里。 */
    private fun rollSegment() {
        endSegment()
        beginSegment()
    }

    /**
     * 开新的一段：角色提示词照旧带走，只换会话标识与那一屏对话。
     * 历史对话留在库里，接着聊那条链只看 sessionId，不靠清表。
     * [greet] 为假就不让助手先开口：静默收段那一段是收给空房间的，用户回来自己起头即可。
     */
    private fun beginSegment(greet: Boolean = true) {
        // 读进来的那一截还没跑完就换了段：那一次续聊作废，别把它接上来的连接留在线路上。
        resumeJob?.cancel()
        resumeJob = null
        ledger.begin()
        plan = plan.resuming(settingsStore.settings.value.prompt)
        // 新的一段不是接着哪一条聊的，顶栏中段的会话名与开场流水都跟着退下去。
        continuing = null
        historyOf = null
        recordedTurns.clear()
        turnVocab.clear()
        // 新的一段从空阔的闸开起：上一段删过哪几轮与这一段的轮次无关。
        audio.reset()
        dropUplink()
        flow = TurnFlowState()
        greeted = !greet
        project()
        connect()
    }

    /** 记录收段历史：自动命名完成后写回并通知界面。 */
    private fun publishSession(record: SessionRecord?) {
        _lastSession.value = record
        project()
    }

    /**
     * 接着一条历史会话聊：把那一整段对话读回手上，新的一轮续在后面，账本改用那条的标识与起点，
     * 连接按那段流水开场，模型接着原来的场景说下去。
     */
    fun resumeSession(record: SessionRecord) {
        cancelReconnect()
        resumeJob?.cancel()
        endSegment()
        resumeJob = viewModelScope.launch {
            val turns = runCatching { store.transcript(record.id) }.getOrDefault(emptyList())
            // 读库期间又被别的操作接管了：这一次续聊作废，不接上连接。
            if (!isActive) return@launch
            continuing = record
            ledger.adopt(record.id, record.startedAt)
            plan = plan.resuming(settingsStore.settings.value.prompt)
            recordedTurns.clear()
            turnVocab.clear()
            audio.reset()
            dropUplink()
            flow = TurnFlowState(turns = turns)
            historyOf = continuationHistory(turns)
            // 开场那一句不再发：模型已经从这段流水里接过上下文。
            greeted = true
            project()
            connect()
        }
    }

    /** 离开接着聊的那一条：这一段收回去写进同一条记录，屏幕上换成新的一段。 */
    fun leaveSession() {
        if (continuing == null) return
        rollSegment()
    }

    /** 顶栏气泡加号与抽屉里的「新会话」走同一条：手上这一段收掉，开新的一段。 */
    fun startNewSession() = rollSegment()

    fun loadSessions() {
        viewModelScope.launch {
            val records = runCatching { store.loadSessions() }.getOrDefault(emptyList())
            _sessions.value = records.flatMap { it.sessions }
            val now = System.currentTimeMillis()
            val practice = runCatching { store.practiceSessions(checkInWindowStart(now)) }.getOrDefault(emptyList())
            _checkIn.value = checkInWeeks(practice, now)
        }
    }

    /** 删掉的一段会话如果就是正在看的那条，当场开新的一段，不回看一个已经不存在的段。 */
    fun deleteSession(sessionId: String) {
        viewModelScope.launch {
            store.deleteSession(sessionId)
            loadSessions()
            if (continuing?.id == sessionId || ledger.current.id == sessionId) {
                startNewSession()
            }
        }
    }

    /** 批量删除走同一条级联，正在看的那段在名单里就只开一次新段。 */
    fun deleteSessions(sessionIds: List<String>) {
        if (sessionIds.isEmpty()) return
        viewModelScope.launch {
            sessionIds.forEach { store.deleteSession(it) }
            loadSessions()
            if (continuing?.id in sessionIds || ledger.current.id in sessionIds) {
                startNewSession()
            }
        }
    }

    /** 手动改名是真名字：接回来那条的 manualName 跟着置真，自动补名不能再盖掉它。 */
    fun renameSession(sessionId: String, name: String) {
        viewModelScope.launch {
            store.rename(sessionId, name, manual = true)
            loadSessions()
            if (continuing?.id == sessionId) {
                continuing = continuing?.copy(name = name, manualName = true)
                project()
            }
        }
    }

    fun updateSettings(transform: (AppSettings) -> AppSettings) {
        val before = settingsStore.settings.value
        settingsStore.update(transform)
        val after = settingsStore.settings.value
        _linkStatus.value = emptyMap()
        refreshProviders()
        viewModelScope.launch { syncCatalog(before.analysis, after.analysis) }
    }

    fun probe() {
        viewModelScope.launch {
            _report.value = "正在自检"
            val live = settingsStore.settings.value.live
            // 只有 Gemini 有能真发出去的只读探针：列一次模型。其余两家没有轻量接口，只判参数齐备。
            val status = when (live.provider) {
                LiveVendor.Gemini -> connectivity.checkGemini(live.geminiKey)
                LiveVendor.OpenAi, LiveVendor.Doubao ->
                    if (live.ready) LinkStatus.Configured else LinkStatus.Unconfigured
            }
            _linkStatus.value = _linkStatus.value + (liveKey(live.provider) to status)
            refreshProviders()
            _report.value = probeReport(live, status)
        }
    }

    private fun refreshProviders() {
        _providers.value = providerLines(settingsStore.settings.value, _linkStatus.value)
    }

    /**
     * 供应商展开：缓存命中的清单先上屏，有密钥再拉一次 /models。
     * 没有密钥那家不列模型，界面据此把行设成不可展开。
     */
    fun loadModels(providerId: String) {
        viewModelScope.launch {
            publishModels(providerId)
            refreshModels(providerId)
        }
    }

    /** 上一次拉到的清单先上屏，一个请求都不发。 */
    private suspend fun publishCachedModels() {
        TextProviderCatalog.all.forEach { publishModels(it.id) }
    }

    private suspend fun publishModels(providerId: String) {
        val models = runCatching { catalogs.models(providerId) }.getOrDefault(emptyList())
        _textProviders.update { injectModels(it, providerId, models) }
        adoptModel(providerId, models)
    }

    /**
     * 拉一次该家的清单并落库。密钥为空就地清掉该家缓存；拉取失败保留旧清单，不弹提示。
     * 每家拿自己那一格的密钥与端点，因此没被选中的那几家照样能列出清单。
     */
    private suspend fun refreshModels(providerId: String) {
        val provider = TextProviderCatalog.of(providerId) ?: return
        val credentials = settingsStore.settings.value.analysis.of(providerId)
        val models = runCatching {
            catalogs.refresh(
                provider = provider,
                apiKey = credentials.apiKey,
                endpoint = credentials.endpoint.ifBlank { provider.endpoint },
                auth = credentials.authSpec(provider),
            )
        }.getOrDefault(emptyList())
        _textProviders.update { injectModels(it, provider.id, models) }
        adoptModel(provider.id, models)
    }

    /** 清单到位就把选择钉进清单：空清单不动，选中的不在清单里就落到最新一个。 */
    private suspend fun adoptModel(providerId: String, models: List<TextModel>) {
        val next = pickedAnalysis(settingsStore.settings.value.analysis, providerId, models) ?: return
        settingsStore.update { it.copy(analysis = next) }
        refreshProviders()
    }

    /**
     * 只重拉凭据变过的那几家：密钥、端点、鉴权任一格变了才发请求，清空密钥则由存储器顺手清缓存。
     * 单纯换选中的家不发请求，别家的清单原样留着，切回来时不用再等一次。
     */
    private suspend fun syncCatalog(before: AnalysisSettings, after: AnalysisSettings) {
        val ids = (before.saved.keys + after.saved.keys).ifEmpty { setOf(after.provider) }
        ids.forEach { id -> if (before.of(id) != after.of(id)) refreshModels(id) }
    }

    private fun probeReport(live: LiveSettings, status: LinkStatus): String = when {
        !live.ready -> "凭据不齐，${live.provider.label} 还缺参数"
        live.provider == LiveVendor.Gemini && status == LinkStatus.Connected -> "自检通过，Gemini Live 可连"
        live.provider == LiveVendor.Gemini -> "自检失败，检查密钥与网络"
        else -> "${live.provider.label} 参数齐备；自检会真发请求的只有 Gemini，这两家只判参数"
    }

    fun saveTurnVocab(turnId: String) {
        val cards = turnVocab[turnId].orEmpty()
        if (cards.isEmpty()) return
        viewModelScope.launch { store.recordVocab(cards) }
        showNotice("已收进生词本")
    }

    fun dismissNotice() {
        clearNotice()
        project()
    }

    fun showNotice(message: String) {
        noticeId += 1
        val current = noticeId
        notice = message
        noticeJob?.cancel()
        noticeJob = viewModelScope.launch {
            delay(NOTICE_MS)
            if (noticeId == current) {
                notice = null
                project()
            }
        }
        project()
    }

    private fun clearNotice() {
        noticeJob?.cancel()
        noticeJob = null
        notice = null
    }

    private fun pushLevel(level: Float) {
        for (i in 0 until BarCount - 1) levelBuffer[i] = levelBuffer[i + 1]
        levelBuffer[BarCount - 1] = level
        _levelFrame.value += 1
    }

    /** 左滑删掉的词先拿掉再落库：等库回来再改界面会先闪一下那一行。 */
    fun deleteVocab(word: String) {
        _vocab.value = _vocab.value.filterNot { it.word.equals(word, ignoreCase = true) }
        viewModelScope.launch { store.deleteVocab(word) }
    }

    /** 撤销时把卡片按原样插回并按最近出现时间归位，界面不等库回来。 */
    fun restoreVocab(card: VocabCard) {
        if (_vocab.value.none { it.word.equals(card.word, ignoreCase = true) }) {
            _vocab.value = (listOf(card) + _vocab.value).sortedByDescending { it.lastSeenAt }
        }
        viewModelScope.launch { store.restoreVocab(card) }
    }

    /** 这一轮按静音收尾：采集停掉、预览收掉、上行音频丢掉，界面回空态。
     *  界面要说一句没收到声音：不说的话，用户分不清是没录上、录上了没认出来，还是被当成了静音。 */
    private fun settleSilentTurn() {
        recorder.cancel()
        stopPreview()
        dispatch(TurnEvent.SilentTurn)
        showNotice(NoSpeech)
        // 这一轮不会走到评测，攒下的上行音频也就没有归属，当场丢掉，免得起下一轮评错人。
        dropUplink()
    }

    /** 上行是十六千赫兹单声道十六位，评测接口要的就是这个形态，按小端摊平即可。 */
    private fun appendUplink(frame: ShortArray) {
        synchronized(uplinkLock) {
            if (uplink.size() >= MaxUplinkBytes) return
            for (sample in frame) {
                val value = sample.toInt()
                uplink.write(value and 0xFF)
                uplink.write((value shr 8) and 0xFF)
            }
        }
    }

    private fun takeUplink(): ByteArray = synchronized(uplinkLock) {
        uplink.toByteArray().also { uplink.reset() }
    }

    /** 判定用：读一份上行音频的副本，不动缓冲本身。 */
    private fun peekUplink(): ShortArray = synchronized(uplinkLock) {
        val bytes = uplink.toByteArray()
        ShortArray(bytes.size / 2) { index ->
            val low = bytes[index * 2].toInt() and 0xFF
            val high = bytes[index * 2 + 1].toInt()
            ((high shl 8) or low).toShort()
        }
    }

    private fun dropUplink() {
        synchronized(uplinkLock) { uplink.reset() }
    }

    /**
     * 文本分析一律按设置页当下的设置现装配，供应商、端点、鉴权与思考参数都从那里取，
     * 清单条目一并带上，上下文窗口、输出上限与思考档位才进得了请求体。
     */
    private suspend fun analyzer(): TurnAnalyzer {
        val analysis = settingsStore.settings.value.analysis
        return TurnAnalyzerFactory.of(analysis, metaOf(analysis))
    }

    /** 装配要的清单条目按键取一条，越出界面展示八条的旧模型也查得到；模型名空着就没有条目。 */
    private suspend fun metaOf(analysis: AnalysisSettings): List<TextModel> {
        if (analysis.model.isBlank()) return emptyList()
        return listOfNotNull(runCatching { catalogs.model(analysis.provider, analysis.model) }.getOrNull())
    }

    /**
     * 自动会话名：材料取整段对话，产出能指认内容的短语。
     * 只有一个自由文本回复，不走结构化解析，所以放在这里而不是结构化分析那一层。
     */
    private suspend fun nameOfSession(id: String, fallback: List<Turn>): String? {
        val settings = settingsStore.settings.value.analysis
        if (settings.apiKey.isBlank()) return null
        val turns = runCatching { store.transcript(id) }.getOrDefault(emptyList()).ifEmpty { fallback }
        val transcript = transcriptOf(turns) ?: return null
        val name = TurnAnalyzerFactory.of(settings, metaOf(settings)).name(transcript) ?: return null
        runCatching { store.rename(id, name, manual = false) }
        return name
    }

    private fun transcriptOf(turns: List<Turn>): String? = turns
        .filter { it.text.isNotBlank() }
        .joinToString("\n", transform = ::transcriptLine)
        .takeIf { it.isNotBlank() }

    /** 启动补写：最近一天里没写成名字的会话最多补三条。 */
    private suspend fun backfillSessionNames() {
        val pending = runCatching { store.takeUnnamed() }.getOrDefault(emptyList())
        pending.forEach { record -> nameOfSession(record.id, emptyList()) }
    }

    private fun newTurnId(prefix: String) = "$prefix-${SystemClock.elapsedRealtimeNanos()}"

    /**
     * 静默三十分钟收段：判定是纯函数 [IdleCut]，走表只在这一处，界面不自己起表。
     * 收完接着开新的一段但不让助手先开口，一段还没开口就没有轮，下一轮静默也就不会再切它。
     */
    private fun watchIdle() {
        viewModelScope.launch {
            while (isActive) {
                delay(IdleCut.CheckMs)
                // 一轮都没有的一段没有可收的东西，也就不去碰它。
                if (flow.turns.isEmpty()) continue
                if (!ledger.idleDue(flow.phase is TurnPhase.Recording)) continue
                endSegment()
                beginSegment(greet = false)
            }
        }
    }

    override fun onCleared() {
        events.close()
        audioWrites.close()
        recorder.cancel()
        stopPreview()
        player.stop()
        timerJob?.cancel()
        session?.close()
        persistOnExit()
        super.onCleared()
    }

    /**
     * 进程退出没有下一段：把手上这一段落库。命名不在这里发，留给下次启动的补写，
     * 退出这一条路上等不起一次网络往返。
     */
    private fun persistOnExit() {
        val ended = ledger.closeIfOpen() ?: return
        if (flow.turns.isEmpty()) return
        // 被打断那一轮的音频路径可能还没补上：这条路上自己拿列表写完，viewModelScope 已经不可用。
        val filled = attachAudioPaths()
        runBlocking {
            withTimeoutOrNull(ExitPersistMs) {
                runCatching {
                    filled.forEach { store.saveTurn(it) }
                    store.closeSession(ended.id, ended.startedAt)
                }.onFailure { Log.w(Tag, "退出时落库失败: ${it.message}") }
            }
        }
    }

    companion object {
        const val BarCount = 34
        private const val NOTICE_MS = 2_000L

        /** 预览进度八十毫秒读一次，跟得上耳朵又不至于每帧问一次播放器。 */
        private const val PreviewTickMs = 80L
        private const val Tag = "ChatViewModel"
        private const val CopyLabel = "Parley"
        private const val AssessorLocale = "en-US"
        private const val ExitPersistMs = 2_000L
        private const val MaxUplinkBytes = 16_000 * 2 * 60
        private const val GreetingPrompt =
            "用一句自然的英文开场，问我今天过得怎么样，不要解释你是模型。"

        /** 这几种事件都会让一轮助手回复停下来，被截断的那几轮要补齐音频路径。 */
        private val AttachAudioEvents = setOf<TurnEvent>(
            TurnEvent.TurnComplete,
            TurnEvent.Interrupted,
            TurnEvent.PressStart,
        )
    }
}

/**
 * 自动名回到手上时该写给哪一条：只有还是收口的那一段、并且没被手动改过名才写回。
 * 期间可能已经换了段（A 段的名字不能写到 B 段上），或者用户自己改了名（手动名不让顶掉）。
 */
internal fun autoNameTarget(last: SessionRecord?, endedId: String, name: String): SessionRecord? =
    last?.takeIf { it.id == endedId && !it.manualName }?.copy(name = name)

/** 一轮助手回复的一段下行音频，落到这一轮自己的录音文件上。 */
internal class AudioChunk(val turnId: String, val pcm: ByteArray)

/**
 * 音频的闸：删掉的那一轮在队列里排着的分片会比删除动作晚到，
 * 不拦下来就会把刚抹掉的录音文件重写出一份残段，声音也会接着放。
 */
internal class AudioGate {
    private val deleted = mutableSetOf<String>()

    fun delete(turnId: String) {
        deleted += turnId
    }

    /** 这一块还能不能写盘、还能不能出声。 */
    fun accepts(turnId: String): Boolean = turnId !in deleted

    /** 开新的一段整份清掉：轮次标识带纳秒，新的一段不会与旧的撞上。 */
    fun reset() = deleted.clear()
}

/** 分析结果的去处：那一轮还在手上交给状态机，已经被收段带走就直接落地。 */
internal enum class AnalysisRoute { Flow, Store }

/**
 * 收段会换掉整屏对话，在飞的分析回来时状态机里已经找不到这一轮；
 * 那一下再 dispatch 就被丢掉，评测与发音分永远停在待分析。
 */
internal fun analysisRoute(turns: List<Turn>, turnId: String): AnalysisRoute =
    if (turns.any { it.id == turnId }) AnalysisRoute.Flow else AnalysisRoute.Store

/**
 * 设置页每行的读数。状态是判定结果，不是密钥填满与否的代理：
 * 实时对话三行看该家自己的凭据，评测两行看 [assessorOf] 出来的实际产物，两家互斥只有一家算已配置。
 */
internal fun providerLines(settings: AppSettings, status: Map<String, LinkStatus>): List<ProviderLine> {
    val live = settings.live
    val realtime = LiveVendor.entries.map { vendor ->
        ProviderLine(
            key = liveKey(vendor),
            group = "realtime",
            name = vendor.label,
            detail = liveDetail(live, vendor),
            status = status[liveKey(vendor)]
                ?: if (live.ready(vendor)) LinkStatus.Configured else LinkStatus.Unconfigured,
            editable = true,
        )
    }
    val assessor = assessorOf(settings.assessor)
    val assessors = listOf(
        ProviderLine(
            key = "azure",
            group = "assessor",
            name = AssessorProvider.Azure.label,
            detail = settings.assessor.azureRegion.ifBlank { "密钥与区域" },
            status = if (assessor is AzureAssessor) LinkStatus.Configured else LinkStatus.Unconfigured,
            editable = true,
        ),
        ProviderLine(
            key = "xfyun",
            group = "assessor",
            name = AssessorProvider.Xfyun.label,
            detail = "AppID、APIKey、APISecret",
            status = if (assessor is XfyunAssessor) LinkStatus.Configured else LinkStatus.Unconfigured,
            editable = true,
        ),
    )
    val analysis = settings.analysis
    val text = TextProviderCatalog.all.map { provider ->
        val own = analysis.of(provider.id)
        ProviderLine(
            key = analysisKey(provider.id),
            group = "analysis",
            name = provider.label,
            detail = own.model.ifBlank { hostOf(provider) },
            status = if (own.ready) LinkStatus.Configured else LinkStatus.Unconfigured,
            editable = true,
        )
    }
    return realtime + assessors + text
}

/** 把一家的可见清单注入目录副本，界面只读 provider.models，不写供应商分支。 */
internal fun injectModels(
    providers: List<TextProvider>,
    providerId: String,
    models: List<TextModel>,
): List<TextProvider> = providers.map { if (it.id == providerId) it.copy(models = models) else it }

/** 实时语音还缺凭证时的提示语。按录音与打字输入共用同一句，指向设置里那一栏。 */
internal const val FillLiveKey = "请填写语音模型 apikey"

/** 线路不收文字时的提示语。开场问候与打字输入共用同一句。 */
internal const val TextUnsupported = "当前语音模型不支持打字"

/** 发音朗读缺凭证时的提示语。指向设置页发音评测那一栏。 */
internal const val FillAssessorKey = "请填写发音评测密钥"

/** 发音网络请求失败时的提示语。 */
internal const val TtsFailed = "发音服务连接失败"

/** 松手后本地判定没收到人声时的提示语。不说这一句，界面回空态就与“没录上”无从分辨。 */
internal const val NoSpeech = "没有听到声音"

/** 删一轮只从这一轮身上动手，别的轮次原样留着。 */
internal fun List<Turn>.minusTurn(turnId: String): List<Turn> = filterNot { it.id == turnId }

/**
 * 文本通道的准入：连接先得就绪，适配器的能力清单还得自报收文字。
 * 未就绪只在日志里说一句，界面不出声；只有配置问题与能力不支持才弹提示。
 */
internal enum class TextGate { Allowed, Waiting, Unsupported, Unconfigured }

internal fun textGate(connection: Connection, capabilities: Capabilities?): TextGate = when (connection) {
    Connection.Unconfigured -> TextGate.Unconfigured
    Connection.Connecting, Connection.Failed -> TextGate.Waiting
    Connection.Ready -> if (capabilities?.acceptsText == false) TextGate.Unsupported else TextGate.Allowed
}

/**
 * 清单到位后的选择：每家各写自己那一格，空清单与已落在清单里的选择都不动，
 * 空着或不在清单里就改到最新一个。返回 null 表示设置不用改写。
 */
internal fun pickedAnalysis(
    analysis: AnalysisSettings,
    providerId: String,
    models: List<TextModel>,
): AnalysisSettings? {
    val current = analysis.of(providerId).model
    val picked = ModelCatalog.pick(ModelCatalog.ordered(models), current)
    if (picked.isBlank() || picked == current) return null
    return analysis.amend(providerId) { it.copy(model = picked) }
}

internal fun liveKey(vendor: LiveVendor): String = vendor.name.lowercase()

/** 行内第二行只放真实读数：模型名；豆包的端到端接口没有模型名，用它当前的音色。 */
private fun liveDetail(live: LiveSettings, vendor: LiveVendor): String {
    val own = live.provider == vendor
    val model = if (own) live.model else LiveDefaults.model(vendor)
    return model.ifBlank { if (own) live.voice else LiveDefaults.voice(vendor) }
}

/** 文本分析的 key 沿用界面已有的映射：Gemini 那条叫 analysis，其余用目录 id。 */
private fun analysisKey(providerId: String): String =
    if (providerId == TextProviderCatalog.GeminiId) "analysis" else providerId

private fun hostOf(provider: TextProvider): String =
    provider.endpoint.substringAfter("://").substringBefore('/').ifBlank { "未填写" }

/**
 * 未连接后的长退避时间表。底数一秒逐次翻倍、封顶三十秒，每次叠 0..300 ms 抖动，
 * 抖动取 [0,1] 的比例参数，便于把上下界写进单测而不依赖随机源。
 */
object ReconnectBackoff {
    const val BaseMs = 1_000L
    const val MaxMs = 30_000L
    const val JitterMs = 300L

    fun delayMs(attempt: Int, jitter: Double): Long {
        var base = BaseMs
        repeat(attempt.coerceAtLeast(0)) {
            if (base < MaxMs) base = (base * 2).coerceAtMost(MaxMs)
        }
        return base + (jitter.coerceIn(0.0, 1.0) * JitterMs).toLong()
    }
}

/**
 * 一轮自动重连的序号与代号。[epoch] 每被取消、跳过或重置一次就前进一格，
 * 已经在飞的等待回来时拿着旧代号对不上就作废，取消因此不必只靠取消定时器收竞态。
 */
data class ReconnectState(val attempt: Int = 0, val epoch: Int = 0) {
    fun waitMs(jitter: Double): Long = ReconnectBackoff.delayMs(attempt, jitter)

    /** 这一次等待已经排上，下一次失败按翻倍算。 */
    fun scheduled(): ReconnectState = copy(attempt = attempt + 1)

    /** 跳过当前等待立刻重试一次：序号不动，只作废在飞的那次定时。 */
    fun skipped(): ReconnectState = copy(epoch = epoch + 1)

    /** 握手成功，或者收段、进入回看、改配置取消这一轮：序号回到一秒。 */
    fun reset(): ReconnectState = ReconnectState(attempt = 0, epoch = epoch + 1)

    fun isCurrent(epoch: Int): Boolean = epoch == this.epoch
}
