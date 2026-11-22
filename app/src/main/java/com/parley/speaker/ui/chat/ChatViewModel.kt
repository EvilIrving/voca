package com.parley.speaker.ui.chat

import android.app.Application
import android.os.SystemClock
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.parley.speaker.audio.AudioCapture
import com.parley.speaker.audio.AudioPlayer
import com.parley.speaker.audio.AzureCredential
import com.parley.speaker.audio.Tts
import com.parley.speaker.audio.TurnAudioStore
import com.parley.speaker.data.SeedContent
import com.parley.speaker.data.SessionRecord
import com.parley.speaker.data.SessionStore
import com.parley.speaker.data.analysis.TurnAnalyzer
import com.parley.speaker.data.analysis.TurnAnalyzerFactory
import com.parley.speaker.data.assessor.SpeechAssessor
import com.parley.speaker.data.assessor.assessorOf
import com.parley.speaker.data.realtime.RealtimeEvent
import com.parley.speaker.data.realtime.RealtimeSession
import com.parley.speaker.data.realtime.gemini.GeminiLiveSession
import com.parley.speaker.data.settings.ConnectivityProbe
import com.parley.speaker.data.settings.SettingsStore
import com.parley.speaker.domain.AppSettings
import com.parley.speaker.domain.LinkStatus
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
import com.parley.speaker.domain.VocabGroup
import java.io.ByteArrayOutputStream
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.roundToInt
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

enum class Connection { Unconfigured, Connecting, Ready, Failed }

data class ChatState(
    val scenario: String,
    val turns: List<Turn>,
    val hint: String? = null,
    val recording: Boolean = false,
    val cancelArmed: Boolean = false,
    val connection: Connection = Connection.Connecting,
    val notice: String? = null,
) {
    val round: Int get() = turns.count { it.speaker == Speaker.User }
}

class ChatViewModel(application: Application) : AndroidViewModel(application) {

    private val capture = AudioCapture(viewModelScope)
    private val player = AudioPlayer(application)
    private val settingsStore = SettingsStore(application)
    private val connectivity = ConnectivityProbe()
    private val store = SessionStore(application)
    private val turnAudio = TurnAudioStore(application)

    /** 朗读凭据与发音评测共用同一份 Azure 语音资源，没配就自然落到系统语音。 */
    private val tts by lazy {
        Tts(application, player) {
            val assessor = settingsStore.settings.value.assessor
            AzureCredential(assessor.azureKey, assessor.azureRegion)
        }
    }

    val settings: StateFlow<AppSettings> = settingsStore.settings

    private val _state = MutableStateFlow(
        ChatState(
            scenario = SeedContent.Scenarios.first(),
            turns = emptyList(),
            hint = null,
            connection = Connection.Connecting,
        ),
    )
    val state: StateFlow<ChatState> = _state.asStateFlow()

    /** 挂断之后签到的那条会话记录，自动名写好之后会被替换一次。 */
    private val _lastSession = MutableStateFlow<SessionRecord?>(null)
    val lastSession: StateFlow<SessionRecord?> = _lastSession.asStateFlow()

    private val _sessions = MutableStateFlow<List<SessionRecord>>(emptyList())
    val sessions: StateFlow<List<SessionRecord>> = _sessions.asStateFlow()

    // 电平每二十毫秒一帧、持续整段收音，绝不新建数组：采集侧就地把数值写进同一个缓冲，
    // 界面只订阅帧计数，重绘一次读一次。
    private val levelBuffer = FloatArray(BarCount)
    val levels: FloatArray get() = levelBuffer

    private val _levelFrame = MutableStateFlow(0L)
    val levelFrame: StateFlow<Long> = _levelFrame.asStateFlow()

    private val _elapsedMs = MutableStateFlow(0L)
    val elapsedMs: StateFlow<Long> = _elapsedMs.asStateFlow()

    private val _vocab = MutableStateFlow<List<VocabGroup>>(emptyList())
    val vocab: StateFlow<List<VocabGroup>> = _vocab.asStateFlow()

    private val _providers = MutableStateFlow<List<ProviderLine>>(emptyList())
    val providers: StateFlow<List<ProviderLine>> = _providers.asStateFlow()

    private val _report = MutableStateFlow<String?>(null)
    val report: StateFlow<String?> = _report.asStateFlow()

    private val events = Channel<TurnEvent>(Channel.UNLIMITED)
    private var flow = TurnFlowState()
    private var connection = Connection.Connecting
    private var notice: String? = null
    private var noticeId = 0L
    private var noticeJob: Job? = null
    private var scenario: String = SeedContent.Scenarios.first()
    private var session: RealtimeSession? = null
    private var timerJob: Job? = null
    private var startedAt = 0L
    private var greeted = false
    private val turnVocab = mutableMapOf<String, List<VocabCard>>()
    private val _linkStatus = MutableStateFlow<Map<String, LinkStatus>>(emptyMap())

    private var sessionId = newSessionId()
    private var sessionStartedAt = System.currentTimeMillis()

    /** 已经落到文件上的助手轮次，持久化时据此补 audioPath。 */
    private val recordedTurns = mutableSetOf<String>()
    private val audioWrites = Channel<AudioChunk>(Channel.UNLIMITED)

    // 采集线程写、主线程读，用递增计数而不是加锁：帧序只增不减，读时按当时计数截断即可。
    private val gateFrames = DoubleArray(MaxGateFrames)
    private val gateCount = AtomicInteger()

    // 上行快照的读写跨线程，缓冲本身不设可见性保证，所有触碰都在这一把锁里完成。
    private val uplinkLock = Any()
    private val uplink = ByteArrayOutputStream()

    init {
        viewModelScope.launch {
            for (event in events) handle(event)
        }
        // 录音文件按到达顺序追加，单消费者保证块序，播放时才不会把同一轮的话打乱。
        viewModelScope.launch {
            for (chunk in audioWrites) turnAudio.append(chunk.turnId, chunk.pcm)
        }
        viewModelScope.launch {
            val stored = store.loadTurns()
            if (stored.isNotEmpty()) {
                greeted = true
                flow = flow.copy(turns = stored)
                project()
            }
            _vocab.value = store.loadVocab()
            refreshProviders()
            connect()
        }
        viewModelScope.launch { turnAudio.prune() }
        viewModelScope.launch { backfillSessionNames() }
        viewModelScope.launch {
            settingsStore.settings
                .map { it.live.apiKey to it.live.model to it.live.voice to it.prompt }
                .distinctUntilChanged()
                .drop(1)
                .collect { reconnect() }
        }
    }

    private fun dispatch(event: TurnEvent) {
        events.trySend(event)
    }

    private suspend fun handle(event: TurnEvent) {
        when (event) {
            TurnEvent.SessionReady -> {
                connection = Connection.Ready
                clearNotice()
                if (!greeted) {
                    greeted = true
                    session?.sendText(GreetingPrompt)
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
                is TurnEffect.StartCapture -> beginCaptureOrRetry()

                TurnEffect.StopCapture -> {
                    timerJob?.cancel()
                    timerJob = null
                    capture.stop()
                    java.util.Arrays.fill(levelBuffer, 0f)
                    _levelFrame.value += 1
                }

                TurnEffect.SendTurnStart -> session?.startTurn()
                TurnEffect.SendTurnEnd -> session?.endTurn()
                is TurnEffect.SendText -> session?.sendText(effect.text)

                // 取消要发到适配器：服务端停下这一轮并开黑名单，迟到的文本与音频由 TurnFlow 认 id 丢掉。
                is TurnEffect.CancelTurn -> session?.cancelTurn(effect.turnId)

                is TurnEffect.Play -> {
                    player.enqueue(effect.pcm)
                    effect.turnId?.let { id ->
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
                    viewModelScope.launch { store.saveTurn(turn.copy(audioPath = path)) }
                }

                is TurnEffect.Delete -> viewModelScope.launch {
                    store.deleteTurn(effect.turnId)
                    recordedTurns -= effect.turnId
                    turnAudio.delete(effect.turnId)
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
        if (tryStartCapture()) return
        viewModelScope.launch {
            delay(180)
            if (!tryStartCapture()) dispatch(TurnEvent.CaptureFailed)
        }
    }

    private fun tryStartCapture(): Boolean {
        val current = session ?: return false
        resetGate()
        uplink.reset()
        val started = capture.start(
            onFrame = { frame ->
                recordFrame(frame)
                appendUplink(frame)
                current.sendAudio(frame)
            },
            onLevel = ::pushLevel,
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
                scenario = scenario,
                turns = flow.turns,
                recording = phase is TurnPhase.Recording,
                cancelArmed = flow.cancelArmed,
                connection = connection,
                notice = notice,
            )
        }
    }

    private fun connect() {
        val current = settingsStore.settings.value
        if (current.live.apiKey.isBlank()) {
            connection = Connection.Unconfigured
            project()
            return
        }
        val created = GeminiLiveSession(
            apiKey = current.live.apiKey,
            model = current.live.model,
            voice = current.live.voice,
            systemPrompt = current.prompt,
            onEvent = ::onEvent,
        )
        session = created
        connection = Connection.Connecting
        project()
        created.connect()
    }

    private fun onEvent(event: RealtimeEvent) {
        when (event) {
            RealtimeEvent.Ready -> dispatch(TurnEvent.SessionReady)
            is RealtimeEvent.UserText -> dispatch(TurnEvent.UserText(event.text, event.turnId))
            is RealtimeEvent.AssistantText -> dispatch(TurnEvent.AssistantText(event.text, event.turnId))
            is RealtimeEvent.Audio -> dispatch(TurnEvent.Audio(event.pcm, event.turnId))
            RealtimeEvent.Interrupted -> dispatch(TurnEvent.Interrupted)
            RealtimeEvent.TurnComplete -> dispatch(TurnEvent.TurnComplete)
            is RealtimeEvent.Failed -> {
                showNotice(event.message)
                dispatch(TurnEvent.SessionLost)
            }
        }
    }

    private fun launchAnalysis(turnId: String, text: String) {
        // 上行的十六千赫兹原始音频就是评测要的形态，回合收尾这一刻先取走快照。
        val pcm = takeUplink()
        val assessor = assessorOf(settingsStore.settings.value.assessor)
        val analyzer = analyzer()
        viewModelScope.launch {
            val analysis = analyzer.analyze(text, turnId)
            if (analysis == null) {
                dispatch(TurnEvent.AnalysisFailed(turnId))
            } else {
                val review = withPronunciation(text, analysis.review, assessor, pcm)
                dispatch(TurnEvent.AnalysisDone(turnId, review, analysis.vocab))
            }
        }
    }

    /**
     * 发音评测只在评测引擎配置齐、这一轮有参考文本、本地也确实录到音频时才发起；
     * 三项缺一项就一个请求都不发，review 原样留着，界面按 pronunciation 是否为空决定画不画发音格。
     */
    private suspend fun withPronunciation(
        text: String,
        review: Review,
        assessor: SpeechAssessor?,
        pcm: ByteArray,
    ): Review {
        if (assessor == null || text.isBlank() || pcm.isEmpty()) return review
        val result = runCatching { assessor.assess(pcm, text, AssessorLocale) }.getOrNull() ?: return review
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

    /** 音频文件路径晚于文本落库，回合收尾时把还缺路径的那几轮补写一次。 */
    private fun attachAudioPaths() {
        val pending = flow.turns
            .filter { it.speaker == Speaker.Echo && it.audioPath == null && it.id in recordedTurns }
            .map { it.id }
            .toSet()
        if (pending.isEmpty()) return
        flow = flow.copy(
            turns = flow.turns.map { turn ->
                if (turn.id in pending) turn.copy(audioPath = turnAudio.path(turn.id)) else turn
            },
        )
        val filled = flow.turns.filter { it.id in pending }
        viewModelScope.launch { filled.forEach { store.saveTurn(it) } }
    }

    private fun mergeVocab(cards: List<VocabCard>) {
        val existing = _vocab.value.flatMap { it.cards }.associateBy { it.word.lowercase() }
        val fresh = cards.filterNot { existing.containsKey(it.word.lowercase()) }
        if (fresh.isEmpty()) {
            _vocab.value = _vocab.value.map { group ->
                group.copy(
                    cards = group.cards.map { card ->
                        cards.firstOrNull { it.word.equals(card.word, ignoreCase = true) }
                            ?.let { card.copy(repeats = card.repeats + 1) } ?: card
                    },
                )
            }
            return
        }
        val today = _vocab.value.firstOrNull { it.label == TodayLabel }
        _vocab.value = if (today == null) {
            _vocab.value + VocabGroup(TodayLabel, fresh)
        } else {
            _vocab.value.map { group ->
                if (group.label == TodayLabel) group.copy(cards = fresh + group.cards) else group
            }
        }
    }

    fun reconnect() {
        dispatch(TurnEvent.SessionLost)
        session?.close()
        session = null
        greeted = false
        connect()
    }

    fun onRecordPressStart() {
        when (connection) {
            Connection.Unconfigured -> {
                showNotice("先在配置中心填入实时语音密钥")
                return
            }

            Connection.Connecting -> {
                showNotice("正在连接，稍候再按")
                return
            }

            Connection.Failed -> {
                showNotice("点击顶栏重新连接")
                reconnect()
                return
            }

            Connection.Ready -> Unit
        }
        dispatch(TurnEvent.PressStart)
    }

    fun onRecordArmed(armed: Boolean) = dispatch(TurnEvent.ArmCancel(armed))

    fun onRecordPressEnd(cancelled: Boolean) {
        if (cancelled) {
            dispatch(TurnEvent.Cancel)
            dropUplink()
            return
        }
        // 太短或者整段没高过环境底噪的一轮在这里就结束：本地回 Idle，不发收尾、不落气泡、也不弹提示。
        if (SpeechGate.shouldDrop(gateFrames())) {
            dispatch(TurnEvent.SilentTurn)
            // 这一轮不会走到评测，攒下的上行音频也就没有归属，当场丢掉，免得起下一轮评错人。
            dropUplink()
            return
        }
        dispatch(TurnEvent.Commit)
    }

    /** 打字发送：不收音也不开新的 activity，落一条用户轮之后直接送文本。 */
    fun sendText(text: String) {
        if (text.isBlank()) return
        when (connection) {
            Connection.Unconfigured -> {
                showNotice("先在配置中心填入实时语音密钥")
                return
            }

            Connection.Connecting -> {
                showNotice("正在连接，稍候再按")
                return
            }

            Connection.Failed -> {
                showNotice("点击顶栏重新连接")
                reconnect()
                return
            }

            Connection.Ready -> Unit
        }
        dispatch(TurnEvent.TextTurn(text))
    }

    fun retryAnalysis(turnId: String) = dispatch(TurnEvent.RetryAnalysis(turnId))

    fun setScenario(name: String) {
        scenario = name
        project()
    }

    /** 气泡译文：库里已有就直接给，没有才请求一次，回来先显示再随轮落库。 */
    suspend fun translate(text: String): String? {
        if (text.isBlank()) return null
        val turn = flow.turns.lastOrNull { it.speaker == Speaker.Echo && it.text == text }
        turn?.translation?.let { return it }
        val translated = analyzer().translate(text) ?: return null
        turn?.let { dispatch(TurnEvent.TranslationDone(it.id, translated)) }
        return translated
    }

    /** 气泡上的书签：库里的 saved 位是唯一数据源，界面读 Turn.saved。 */
    fun setSaved(turnId: String, saved: Boolean) {
        val turn = flow.turns.firstOrNull { it.id == turnId } ?: return
        if (turn.saved == saved) return
        flow = flow.copy(turns = flow.turns.map { if (it.id == turnId) it.copy(saved = saved) else it })
        project()
        viewModelScope.launch { store.setSaved(turnId, saved) }
        if (saved) showNotice("已收进生词本")
    }

    /** 气泡朗读键：这一轮留下过录音就播录音，没有就交给语音合成读正文。 */
    fun playTurn(turnId: String) {
        val turn = flow.turns.firstOrNull { it.id == turnId } ?: return
        viewModelScope.launch {
            val pcm = turnAudio.read(turnId)
            if (pcm != null) player.enqueue(pcm) else tts.speak(turn.text)
        }
    }

    /** 生词卡上的朗读：念例句，例句缺失时退回词形本身。 */
    fun speakCard(card: VocabCard) {
        speakText(card.example.ifBlank { card.word })
    }

    fun speakText(text: String) {
        if (text.isBlank()) return
        viewModelScope.launch { tts.speak(text) }
    }

    fun hangup() {
        val closedTurns = flow.turns
        timerJob?.cancel()
        timerJob = null
        capture.stop()
        player.stop()
        session?.close()
        session = null
        attachAudioPaths()
        // 会话结束了但这一屏对话留着，等开始新会话再清；小结卡要拿它算轮次与均值。
        flow = flow.copy(phase = TurnPhase.Offline, cancelArmed = false)
        // 会话真的结束了，顶栏停在未连接；重试即新开一条连接，小结卡底部的主按钮走开始新会话。
        connection = Connection.Failed
        clearNotice()
        greeted = false
        player.flush()
        project()
        val ended = sessionId
        val begin = sessionStartedAt
        viewModelScope.launch {
            val record = runCatching { store.closeSession(ended, begin) }.getOrNull()
            _lastSession.value = record
            // 命名只写一次，材料取整段对话；manualName 为真的会话在 SQL 那一层就挡住自动名。
            val name = nameOfSession(ended, closedTurns) ?: return@launch
            _lastSession.value = _lastSession.value?.copy(name = name)
        }
    }

    fun startNewSession() {
        _lastSession.value = null
        sessionId = newSessionId()
        sessionStartedAt = System.currentTimeMillis()
        recordedTurns.clear()
        turnVocab.clear()
        dropUplink()
        flow = TurnFlowState()
        greeted = false
        project()
        viewModelScope.launch { store.clearTurns() }
        connect()
    }

    /** 小结卡标题旁那支铅笔：手动改过即固定，自动名不再覆盖。 */
    fun renameSession(name: String) {
        val id = _lastSession.value?.id ?: return
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return
        viewModelScope.launch {
            store.rename(id, trimmed, manual = true)
            _lastSession.value = _lastSession.value?.copy(name = trimmed, manualName = true)
        }
    }

    fun loadSessions() {
        viewModelScope.launch {
            _sessions.value = runCatching { store.loadSessions() }.getOrDefault(emptyList())
                .flatMap { it.sessions }
        }
    }

    fun updateSettings(transform: (AppSettings) -> AppSettings) {
        settingsStore.update(transform)
        _linkStatus.value = emptyMap()
        refreshProviders()
    }

    fun probe() {
        viewModelScope.launch {
            _report.value = "正在自检"
            val live = settingsStore.settings.value.live
            val status = connectivity.checkGemini(live.apiKey)
            _linkStatus.value = _linkStatus.value + ("live" to status)
            refreshProviders()
            _report.value = when (status) {
                LinkStatus.Connected -> "自检通过，实时语音可连"
                LinkStatus.Failed -> "自检失败，检查密钥与网络"
                LinkStatus.Unconfigured -> "还没填实时语音密钥"
                else -> "自检未通过"
            }
        }
    }

    private fun refreshProviders() {
        val current = settingsStore.settings.value
        val liveStatus = _linkStatus.value["live"]
            ?: if (current.live.apiKey.isBlank()) LinkStatus.Unconfigured else LinkStatus.Configured
        val analysisStatus = _linkStatus.value["analysis"]
            ?: if (current.analysis.apiKey.isBlank()) LinkStatus.Unconfigured else LinkStatus.Configured
        val azureStatus = if (current.assessor.azureKey.isBlank() || current.assessor.azureRegion.isBlank()) {
            LinkStatus.Unconfigured
        } else {
            LinkStatus.Configured
        }
        _providers.value = listOf(
            ProviderLine("live", "realtime", "Gemini Live", current.live.model, liveStatus, editable = true),
            ProviderLine("openai", "realtime", "OpenAI Realtime", "gpt-realtime", LinkStatus.NotSupported),
            ProviderLine("doubao", "realtime", "豆包端到端实时语音", "volc.speech.dialog", LinkStatus.NotSupported),
            ProviderLine("azure", "assessor", "微软 Azure 语音评测", current.assessor.azureRegion.ifBlank { "key 与区域" }, azureStatus, editable = true),
            ProviderLine("xfyun", "assessor", "讯飞评测流式版", "朗读题型", LinkStatus.NotSupported),
            ProviderLine("deepseek", "analysis", "DeepSeek", "deepseek-chat", LinkStatus.NotSupported),
            ProviderLine("analysis", "analysis", "Gemini Flash", current.analysis.model, analysisStatus, editable = true),
        )
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

    private fun recordFrame(frame: ShortArray) {
        val index = gateCount.getAndIncrement()
        if (index < gateFrames.size) gateFrames[index] = SpeechGate.frameDb(frame)
    }

    private fun gateFrames(): DoubleArray = gateFrames.copyOf(gateCount.get().coerceIn(0, gateFrames.size))

    private fun resetGate() = gateCount.set(0)

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

    private fun dropUplink() {
        synchronized(uplinkLock) { uplink.reset() }
    }

    /** 文本分析一律按配置中心当下的设置现装配，供应商、端点、鉴权与思考参数都从那里取。 */
    private fun analyzer(): TurnAnalyzer = TurnAnalyzerFactory.of(settingsStore.settings.value.analysis)

    /**
     * 自动会话名：材料取整段对话，产出能指认内容的短语。
     * 只有一个自由文本回复，不走结构化解析，所以放在这里而不是结构化分析那一层。
     */
    private suspend fun nameOfSession(id: String, fallback: List<Turn>): String? {
        val settings = settingsStore.settings.value.analysis
        if (settings.apiKey.isBlank()) return null
        val turns = runCatching { store.transcript(id) }.getOrDefault(emptyList()).ifEmpty { fallback }
        val transcript = transcriptOf(turns) ?: return null
        val name = TurnAnalyzerFactory.of(settings).name(transcript) ?: return null
        runCatching { store.rename(id, name, manual = false) }
        return name
    }

    private fun transcriptOf(turns: List<Turn>): String? = turns
        .filter { it.text.isNotBlank() }
        .joinToString("\n") { turn ->
            val who = if (turn.speaker == Speaker.User) "学生" else "助教"
            "$who：${turn.text}"
        }
        .takeIf { it.isNotBlank() }

    /** 启动补写：最近一天里没写成名字的会话最多补三条。 */
    private suspend fun backfillSessionNames() {
        val pending = runCatching { store.takeUnnamed() }.getOrDefault(emptyList())
        pending.forEach { record -> nameOfSession(record.id, emptyList()) }
    }

    private fun newTurnId(prefix: String) = "$prefix-${SystemClock.elapsedRealtimeNanos()}"

    private fun newSessionId() = "session-${System.currentTimeMillis()}"

    override fun onCleared() {
        events.close()
        audioWrites.close()
        capture.stop()
        player.stop()
        session?.close()
        super.onCleared()
    }

    private data class AudioChunk(val turnId: String, val pcm: ByteArray)

    companion object {
        const val BarCount = 34
        private const val TodayLabel = "今天"
        private const val NOTICE_MS = 2_000L
        private const val AssessorLocale = "en-US"
        private const val MaxGateFrames = 3_000
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
