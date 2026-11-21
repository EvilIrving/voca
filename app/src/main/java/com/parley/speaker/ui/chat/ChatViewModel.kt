package com.parley.speaker.ui.chat

import android.app.Application
import android.os.SystemClock
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.parley.speaker.BuildConfig
import com.parley.speaker.audio.AudioCapture
import com.parley.speaker.audio.AudioPlayer
import com.parley.speaker.data.SeedContent
import com.parley.speaker.data.analysis.GeminiTurnAnalyzer
import com.parley.speaker.data.analysis.TurnAnalyzer
import com.parley.speaker.data.SessionStore
import com.parley.speaker.domain.VocabCard
import com.parley.speaker.domain.VocabGroup
import com.parley.speaker.data.realtime.RealtimeEvent
import com.parley.speaker.data.realtime.RealtimeSession
import com.parley.speaker.data.realtime.gemini.GeminiLiveSession
import com.parley.speaker.domain.Speaker
import com.parley.speaker.domain.Turn
import kotlinx.coroutines.Job
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
    private val player = AudioPlayer()
    private val analyzer: TurnAnalyzer = GeminiTurnAnalyzer(
        apiKey = BuildConfig.DEV_GEMINI_API_KEY,
        model = BuildConfig.DEV_GEMINI_TEXT_MODEL,
    )

    private val _state = MutableStateFlow(
        ChatState(
            scenario = SeedContent.Scenarios.first(),
            turns = emptyList(),
            hint = null,
            connection = if (BuildConfig.DEV_GEMINI_API_KEY.isBlank()) Connection.Unconfigured else Connection.Connecting,
        ),
    )
    val state: StateFlow<ChatState> = _state.asStateFlow()

    private val _levels = MutableStateFlow(FloatArray(BarCount))
    val levels: StateFlow<FloatArray> = _levels.asStateFlow()

    private val _elapsedMs = MutableStateFlow(0L)
    val elapsedMs: StateFlow<Long> = _elapsedMs.asStateFlow()

    private val _vocab = MutableStateFlow<List<VocabGroup>>(emptyList())
    val vocab: StateFlow<List<VocabGroup>> = _vocab.asStateFlow()

    private var session: RealtimeSession? = null
    private var timerJob: Job? = null
    private var startedAt = 0L
    private var acceptTurn = false
    private var pendingUserId: String? = null
    private var pendingEchoId: String? = null
    private var greeted = false
    private var smoothedLevel = 0f
    private val turnVocab = mutableMapOf<String, List<VocabCard>>()

    private val store = SessionStore(application)

    init {
        viewModelScope.launch {
            val stored = store.loadTurns()
            if (stored.isNotEmpty()) {
                greeted = true
                _state.update { it.copy(turns = stored) }
            }
            _vocab.value = store.loadVocab()
            connect()
        }
        viewModelScope.launch {
            _state.map { it.turns }.distinctUntilChanged().drop(1).collect { turns ->
                store.saveTurns(turns)
            }
        }
    }

    private fun connect() {
        val apiKey = BuildConfig.DEV_GEMINI_API_KEY
        if (apiKey.isBlank()) {
            _state.update { it.copy(connection = Connection.Unconfigured) }
            return
        }
        val created = GeminiLiveSession(
            apiKey = apiKey,
            model = BuildConfig.DEV_GEMINI_LIVE_MODEL,
            voice = BuildConfig.DEV_GEMINI_VOICE,
            systemPrompt = SeedContent.DefaultPrompt,
            onEvent = ::onEvent,
        )
        session = created
        _state.update { it.copy(connection = Connection.Connecting) }
        created.connect()
    }

    private fun onEvent(event: RealtimeEvent) {
        when (event) {
            RealtimeEvent.Ready -> {
                _state.update { it.copy(connection = Connection.Ready, notice = null) }
                if (!greeted) {
                    greeted = true
                    session?.sendText(GreetingPrompt)
                }
            }

            is RealtimeEvent.UserText -> appendUserText(event.text)
            is RealtimeEvent.AssistantText -> appendAssistantText(event.text)
            is RealtimeEvent.Audio -> if (acceptTurn) player.enqueue(event.pcm)
            RealtimeEvent.Interrupted -> player.flush()
            RealtimeEvent.TurnComplete -> completeTurn()
            is RealtimeEvent.Failed -> _state.update {
                it.copy(connection = Connection.Failed, notice = event.message)
            }
        }
    }

    private fun appendUserText(text: String) {
        _state.update { current ->
            val id = pendingUserId ?: newId("user").also { pendingUserId = it }
            val existing = current.turns.firstOrNull { it.id == id }
            val turns = if (existing == null) {
                current.turns + Turn(id = id, speaker = Speaker.User, text = text)
            } else {
                current.turns.map { if (it.id == id) it.copy(text = it.text + text) else it }
            }
            current.copy(turns = turns)
        }
    }

    private fun appendAssistantText(text: String) {
        _state.update { current ->
            val id = pendingEchoId ?: newId("echo").also { pendingEchoId = it }
            val existing = current.turns.firstOrNull { it.id == id }
            val turns = if (existing == null) {
                current.turns + Turn(id = id, speaker = Speaker.Echo, text = text)
            } else {
                current.turns.map { if (it.id == id) it.copy(text = it.text + text) else it }
            }
            current.copy(turns = turns)
        }
    }

    private fun completeTurn() {
        val userId = pendingUserId
        val userText = _state.value.turns.firstOrNull { it.id == userId }?.text.orEmpty()
        _state.update { current ->
            val pruned = current.turns.filterNot { it.id == pendingUserId && it.text.isBlank() }
            current.copy(turns = pruned)
        }
        pendingUserId = null
        pendingEchoId = null
        acceptTurn = false
        if (userId != null && userText.isNotBlank()) analyzeTurn(userId, userText)
    }

    private fun analyzeTurn(turnId: String, text: String) {
        viewModelScope.launch {
            val analysis = analyzer.analyze(text, turnId)
            android.util.Log.i("ChatViewModel", "analysis turn=$turnId ok=${analysis != null} text=${text.take(40)}")
            if (analysis == null) return@launch
            _state.update { current ->
                current.copy(
                    turns = current.turns.map { turn ->
                        if (turn.id == turnId) turn.copy(review = analysis.review) else turn
                    },
                )
            }
            turnVocab[turnId] = analysis.vocab
            if (analysis.vocab.isNotEmpty()) {
                mergeVocab(analysis.vocab)
                store.recordVocab(analysis.vocab)
            }
        }
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
        if (_state.value.connection == Connection.Connecting) return
        session?.close()
        session = null
        greeted = false
        connect()
    }

    fun onRecordPressStart() {
        val connection = _state.value.connection
        if (connection == Connection.Unconfigured) {
            _state.update { it.copy(notice = "先在配置中心填入实时语音密钥") }
            return
        }
        if (connection != Connection.Ready) {
            _state.update { it.copy(notice = if (connection == Connection.Connecting) "正在连接，稍候再按" else "点击顶栏重新连接") }
            if (connection == Connection.Failed) reconnect()
            return
        }
        val session = session ?: return
        val started = capture.start(
            onFrame = { frame -> session.sendAudio(frame) },
            onLevel = ::pushLevel,
        )
        if (!started) {
            _state.update { it.copy(notice = "麦克风不可用") }
            return
        }
        acceptTurn = true
        startedAt = SystemClock.elapsedRealtime()
        _elapsedMs.value = 0L
        _state.update { it.copy(recording = true, cancelArmed = false, notice = null) }
        session.startTurn()
        timerJob?.cancel()
        timerJob = viewModelScope.launch {
            while (isActive) {
                _elapsedMs.value = SystemClock.elapsedRealtime() - startedAt
                delay(80)
            }
        }
    }

    fun onRecordArmed(armed: Boolean) {
        _state.update { it.copy(cancelArmed = armed) }
    }

    fun onRecordPressEnd(cancelled: Boolean) {
        timerJob?.cancel()
        timerJob = null
        capture.stop()
        _levels.value = FloatArray(BarCount)
        _state.update { it.copy(recording = false, cancelArmed = false) }
        if (cancelled) {
            acceptTurn = false
            pendingUserId?.let { id ->
                _state.update { current -> current.copy(turns = current.turns.filterNot { it.id == id }) }
            }
            pendingUserId = null
            session?.endTurn()
        } else {
            session?.endTurn()
        }
    }

    fun setScenario(name: String) {
        _state.update { it.copy(scenario = name) }
    }

    fun hangup() {
        capture.stop()
        player.stop()
        session?.close()
        session = null
        pendingUserId = null
        pendingEchoId = null
        acceptTurn = false
        greeted = false
        _state.update {
            it.copy(turns = emptyList(), hint = null, recording = false, cancelArmed = false, notice = null)
        }
        viewModelScope.launch { store.clearTurns() }
        connect()
    }

    fun saveTurnVocab(turnId: String) {
        val cards = turnVocab[turnId].orEmpty()
        if (cards.isEmpty()) return
        viewModelScope.launch { store.recordVocab(cards) }
        _state.update { it.copy(notice = "已收进生词本") }
    }

    fun dismissNotice() {
        _state.update { it.copy(notice = null) }
    }

    private fun pushLevel(level: Float) {
        smoothedLevel = smoothedLevel * 0.72f + level * 0.28f
        val current = _levels.value
        val next = FloatArray(BarCount)
        for (i in 0 until BarCount - 1) next[i] = current[i + 1]
        next[BarCount - 1] = smoothedLevel
        _levels.value = next
    }

    private fun newId(prefix: String) = "$prefix-${SystemClock.elapsedRealtimeNanos()}"

    override fun onCleared() {
        capture.stop()
        player.stop()
        session?.close()
        super.onCleared()
    }

    companion object {
        const val BarCount = 34
        private const val TodayLabel = "今天"
        private const val GreetingPrompt =
            "用一句自然的英文开场，问我今天过得怎么样，不要解释你是模型。"
    }
}
