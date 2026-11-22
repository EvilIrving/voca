import Combine
import Foundation
import os
import UIKit

@MainActor
final class ChatStore: ObservableObject, ParleySession {
    @Published private(set) var state = ChatState(turns: [], connection: .connecting)
    @Published private(set) var lastSession: SessionRecord?
    @Published private(set) var sessions: [SessionRecord] = []
    let meter = VoiceMeter()
    @Published private(set) var voicePreview: VoicePreview?
    @Published private(set) var vocab: [VocabCard] = []
    /// 设置页那张打卡热力图：收段记录一有新的一份就重算，界面不再自己扫库。
    @Published private(set) var checkIn = checkInWeeks(sessions: [], now: 0)

    /// 收音一停就量出来的真实长度，按轮 id 存着，落库时挂到那一轮上。
    private var spokenMs: [String: Int64] = [:]
    private var capturingTurnId: String?
    @Published private(set) var providers: [ProviderLine] = []
    @Published private(set) var textProviders = TextProviderCatalog.all
    @Published private(set) var report: String?
    @Published private(set) var settings: AppSettings
    let defaultPrompt: String

    private let recorder = VoiceRecorder()
    let clipPlayer = ClipPlayer()
    private let player: AudioPlayer
    private let settingsStore: SettingsStore
    private let connectivity = ConnectivityProbe()
    private let store: SessionStore
    private let catalogs: ModelCatalogStore
    private let turnAudio = TurnAudioStore()
    private let tts: Tts
    private let memory: MemoryLedger
    private let logger = Logger(subsystem: "com.parley.speaker", category: "ChatStore")
    private let capture = CaptureBuffer()

    private var flow = TurnFlowState()
    private var connection = Connection.connecting
    private var notice: String?
    private var noticeId: Int64 = 0
    private var session: RealtimeSession?
    private let ledger = SessionLedger()
    private var plan: SegmentPlan
    private var capabilities: Capabilities?
    private var connectSeq = 0
    private var reconnectState = ReconnectState()
    /// 正在接着聊的那条历史会话；nil 表示手上的这一段是新开的。
    private var continuing: SessionRecord?
    /// 这一段开场时带进来的历史流水：接着聊的那一条重连也要把同一段带上。
    private var historyOf: String?
    /// 正在读进来的那条历史会话：连点两条时后来的接管，前一条的连接不会留在外面。
    private var resumeTask: Task<Void, Never>?
    private var greeted = false
    private var recordedTurns = Set<String>()
    private var turnVocab: [String: [VocabCard]] = [:]
    private var linkStatus: [String: LinkStatus] = [:]
    private var audio = AudioGate()
    private var previewClip: VoiceClip?

    private var noticeTask: Task<Void, Never>?
    private var reconnectTask: Task<Void, Never>?
    private var idleTask: Task<Void, Never>?
    private var memoryTask: Task<Void, Never>?
    private var audioTask: Task<Void, Never>?

    /** 松手后的人声判定：上一轮还没判完就来了新的一轮，先把它掐掉。 */
    private var vadTask: Task<Void, Never>?
    private var cancellables = Set<AnyCancellable>()
    private let audioStream: AsyncStream<AudioChunk>
    private let audioContinuation: AsyncStream<AudioChunk>.Continuation

    private var sessionId: String { ledger.current.id }

    init() {
        let settingsStore = SettingsStore()
        let store = try! SessionStore()
        let database = try! ParleyDatabase()
        let player = AudioPlayer()
        var continuation: AsyncStream<AudioChunk>.Continuation!
        audioStream = AsyncStream(bufferingPolicy: .unbounded) { continuation = $0 }
        audioContinuation = continuation
        self.settingsStore = settingsStore
        self.store = store
        self.player = player
        self.catalogs = ModelCatalogStore(db: database)
        self.settings = settingsStore.settings
        self.defaultPrompt = settingsStore.defaultPrompt
        self.plan = SegmentPlan(prompt: settingsStore.settings.prompt)
        self.tts = Tts(player: player) {
            let assessor = settingsStore.settings.assessor
            return AzureCredential(key: assessor.azureKey, region: assessor.azureRegion)
        }
        self.memory = MemoryLedger(persist: { text in try? await store.saveMemory(text) })

        settingsStore.$settings
            .dropFirst()
            .sink { [weak self] next in
                Task { @MainActor [weak self] in self?.settingsChanged(next) }
            }
            .store(in: &cancellables)
        NotificationCenter.default.publisher(for: UIApplication.willTerminateNotification)
            .sink { [weak self] _ in
                Task { @MainActor [weak self] in await self?.persistOnExit() }
            }
            .store(in: &cancellables)

        audioTask = Task { [weak self] in await self?.drainAudio() }
        memoryTask = Task { [weak self] in await self?.drainMemory() }
        idleTask = Task { [weak self] in await self?.watchIdle() }
        Task { [weak self] in await self?.bootstrap() }
    }

    func onRecordPressStart() -> Bool {
        // 上一段已经收尾（静默到点或刚从一条历史会话退出来），这一下就是新的一段的第一手。
        ledger.touch()
        switch connection {
        case .unconfigured:
            showNotice(Copy.fillLiveKey)
            return false
        case .connecting, .failed:
            logger.info("连接未就绪，这一下不收")
            return false
        case .ready:
            break
        }
        // 上一轮的判定还在跑就掐掉：它的结论属于上一段，落到这一轮会把这一段判错。
        vadTask?.cancel()
        vadTask = nil
        player.flush()
        dispatch(.pressStart)
        return true
    }

    func onRecordArmed(_ armed: Bool) {
        dispatch(.armCancel(armed))
    }

    func onRecordPressEnd(cancelled: Bool) {
        vadTask?.cancel()
        vadTask = nil
        if cancelled {
            recorder.cancel()
            stopPreview()
            dispatch(.cancel)
            capture.dropUplink()
            return
        }
        // 这一段里没人说话就在这里结束：本地回 Idle，不发收尾、不落气泡、也不弹提示。
        // 判定要跑一遍分类器，一百多毫秒，挪到后台；采集照旧跑到收尾事件把它停掉，多录的那点静音无害。
        let pcm = capture.peekPcm()
        guard !pcm.isEmpty else {
            settleSilentTurn()
            return
        }
        vadTask = Task { [weak self] in
            let speechMs = await Task.detached(priority: .userInitiated) {
                SpeechGate.longestSpeechMs(SpeechDetector.windows(pcm))
            }.value
            guard let self, !Task.isCancelled else { return }
            if SpeechGate.shouldDrop(speechMs: speechMs) {
                logger.info("这一段没人说话，人声 \(speechMs)ms")
                settleSilentTurn()
                return
            }
            dispatch(.commit)
        }
    }

    /// 这一轮按静音收尾：采集停掉、预览收掉、上行音频丢掉，界面回空态。
    private func settleSilentTurn() {
        recorder.cancel()
        stopPreview()
        dispatch(.silentTurn)
        capture.dropUplink()
    }

    func onRecordHold(_ held: Bool) {
        if held {
            meter.hold()
            stopPreview()
            Task {
                guard let clip = await recorder.hold() else { return }
                previewClip = clip
                voicePreview = VoicePreview(waveform: clip.waveform, durationMs: clip.durationMs)
            }
            return
        }
        stopPreview()
        guard recorder.resume() else {
            showNotice("麦克风被占用")
            dispatch(.cancel)
            capture.dropUplink()
            return
        }
        meter.resume()
    }

    func togglePreviewPlayback() {
        guard let clip = previewClip else { return }
        if clipPlayer.isPlaying() {
            clipPlayer.pause()
            return
        }
        Task {
            guard await clipPlayer.prepare(path: clip.path) else { return }
            _ = clipPlayer.start()
        }
    }

    func sendText(_ text: String) {
        guard !text.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty else { return }
        switch textGate(connection: connection, capabilities: capabilities) {
        case .allowed: dispatch(.textTurn(text))
        case .unconfigured: showNotice(Copy.fillLiveKey)
        case .unsupported: showNotice(Copy.textUnsupported)
        case .waiting: logger.info("连接未就绪，文字没有发出")
        }
    }

    func retryAnalysis(turnId: String) {
        dispatch(.retryAnalysis(turnId))
    }

    func translate(_ text: String) async -> String? {
        guard !text.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty else { return nil }
        let turn = flow.turns.last { $0.speaker == .echo && $0.text == text }
        if let translation = turn?.translation { return translation }
        let engine = await analyzerWithMeta()
        guard let translated = await engine.translate(text: text) else { return nil }
        if let turn {
            dispatch(.translationDone(turn.id, translated))
            var saved = turn
            saved.translation = translated
            Task { try? await store.saveTurn(saved) }
        }
        return translated
    }

    func setSaved(turnId: String, saved: Bool) {
        if let index = flow.turns.firstIndex(where: { $0.id == turnId }) {
            if flow.turns[index].saved == saved { return }
            flow.turns[index].saved = saved
            project()
        }
        Task { try? await store.setSaved(turnId: turnId, saved: saved) }
        if saved { showNotice("已收进生词本") }
    }

    func playTurn(turnId: String) {
        guard let turn = turnById(turnId) else { return }
        Task {
            if let pcm = await turnAudio.read(turnId: turnId) {
                player.enqueue(pcm)
            } else {
                _ = await tts.speak(turn.text)
            }
        }
    }

    func copyTurn(turnId: String) {
        guard let turn = turnById(turnId) else { return }
        UIPasteboard.general.string = turn.text
        showNotice("已复制")
    }

    func deleteTurn(turnId: String) {
        player.stop()
        flow.turns.removeAll { $0.id == turnId }
        project()
        purgeTurn(turnId)
    }

    func speakCard(_ card: VocabCard) {
        let word = card.word.trimmingCharacters(in: .whitespacesAndNewlines)
        let example = card.example.trimmingCharacters(in: .whitespacesAndNewlines)
        speakText(example.isEmpty ? word : example)
    }

    func speakText(_ text: String) {
        guard !text.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty else { return }
        let assessor = settingsStore.settings.assessor
        let configured = !assessor.azureKey.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty &&
            !assessor.azureRegion.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
        if !configured {
            showNotice(Copy.fillAssessorKey)
            return
        }
        Task {
            let ok = await tts.speak(text)
            if !ok { showNotice(Copy.ttsFailed) }
        }
    }

    /// 左滑删掉的词先拿掉再落库：等库回来再改界面会先闪一下那一行。
    func deleteVocab(word: String) {
        vocab.removeAll { $0.word.caseInsensitiveCompare(word) == .orderedSame }
        Task { try? await store.deleteVocab(word: word) }
    }

    /// 撤销时把卡片按原样插回并按最近出现时间归位，界面不等库回来。
    func restoreVocab(card: VocabCard) {
        if !vocab.contains(where: { $0.word.caseInsensitiveCompare(card.word) == .orderedSame }) {
            vocab = ([card] + vocab).sorted { $0.lastSeenAt > $1.lastSeenAt }
        }
        Task { try? await store.restoreVocab(card: card) }
    }

    /// 接着一条历史会话聊：把那一整段对话读回手上，新的一轮续在后面，账本改用那条的标识与起点，
    /// 连接按那段流水开场，模型接着原来的场景说下去。
    func resumeSession(_ record: SessionRecord) {
        cancelReconnect()
        resumeTask?.cancel()
        _ = endSegment()
        resumeTask = Task { [weak self] in
            guard let self else { return }
            let turns = (try? await self.store.transcript(sessionId: record.id)) ?? []
            guard !Task.isCancelled else { return }
            self.continuing = record
            self.ledger.adopt(id: record.id, startedAt: record.startedAt)
            self.plan = SegmentPlan(prompt: self.settings.prompt)
            self.recordedTurns.removeAll()
            self.turnVocab.removeAll()
            self.audio.reset()
            self.capture.dropUplink()
            self.flow = TurnFlowState(turns: turns)
            self.historyOf = continuationHistory(turns)
            // 开场那一句不再发：模型已经从这段流水里接过上下文。
            self.greeted = true
            self.project()
            self.connect()
        }
    }

    /// 离开接着聊的那一条：这一段收回去写进同一条记录，屏幕上换成新的一段。
    func leaveSession() {
        guard continuing != nil else { return }
        rollSegment()
    }

    func startNewSession() {
        rollSegment()
    }
    func loadSessions() {
        Task {
            let records = (try? await store.loadSessions())?.flatMap(\.sessions) ?? []
            sessions = records
            let now = Int64(Date().timeIntervalSince1970 * 1000)
            let practice = (try? await store.practiceSessions(since: checkInWindowStart(now: now))) ?? []
            checkIn = checkInWeeks(sessions: practice, now: now)
        }
    }



    func updateSettings(_ transform: (AppSettings) -> AppSettings) {
        let before = settingsStore.settings
        settingsStore.update(transform: transform)
        let after = settingsStore.settings
        linkStatus = [:]
        refreshProviders()
        Task { await syncCatalog(before: before.analysis, after: after.analysis) }
    }

    func probe() {
        Task {
            report = "正在自检"
            let live = settings.live
            let status: LinkStatus
            switch live.provider {
            case .gemini: status = await connectivity.checkGemini(apiKey: live.geminiKey)
            case .openAi, .doubao: status = live.ready ? .configured : .unconfigured
            }
            linkStatus[liveKey(live.provider)] = status
            refreshProviders()
            report = probeReport(live: live, status: status)
        }
    }

    func loadModels(providerId: String) {
        Task {
            await publishModels(providerId)
            await refreshModels(providerId)
        }
    }

    func saveTurnVocab(turnId: String) {
        let cards = turnVocab[turnId] ?? []
        guard !cards.isEmpty else { return }
        Task { try? await store.recordVocab(cards: cards) }
        showNotice("已收进生词本")
    }

    func dismissNotice() {
        clearNotice()
        project()
    }

    func showNotice(_ message: String) {
        noticeId += 1
        let current = noticeId
        notice = message
        noticeTask?.cancel()
        noticeTask = Task { [weak self] in
            try? await Task.sleep(for: .seconds(2))
            guard let self, self.noticeId == current else { return }
            self.notice = nil
            self.project()
        }
        project()
    }

    func reconnect() {
        cancelReconnect()
        dispatch(.sessionLost)
        dropSession()
        plan = SegmentPlan(prompt: settings.prompt)
        // 接着聊的那一段重连时模型已经从流水里接过上下文，开场那一句不再发。
        greeted = continuing != nil
        connect()
    }

    private func bootstrap() async {
        vocab = (try? await store.loadVocab()) ?? []
        refreshProviders()
        await publishCachedModels()
        let stored = (try? await store.globalMemory()) ?? ""
        await memory.seedWith(value: stored.isEmpty ? SeedContent.defaultMemory : stored)
        connect()
        await turnAudio.prune()
        await backfillSessionNames()
    }

    private func settingsChanged(_ next: AppSettings) {
        let previous = settings
        settings = next
        switch liveChange(before: previous, after: next) {
        case .rebuild: reconnect()
        case .voice: showNotice("音色将在新会话生效")
        case .model: showNotice("模型将在新会话生效")
        case .prompt: showNotice("提示词将在新会话生效")
        case .none: break
        }
    }

    private func dispatch(_ event: TurnEvent) {
        ledger.touch()
        handle(event)
    }

    private func handle(_ event: TurnEvent) {
        switch event {
        case .sessionReady:
            connection = .ready
            cancelReconnect()
            clearNotice()
            if !greeted {
                greeted = true
                switch textGate(connection: .ready, capabilities: capabilities) {
                case .allowed: session?.sendText(openingOf(loaded: SeedContent.sessionOpening, greeting: Copy.greeting))
                case .unsupported: showNotice(Copy.textUnsupported)
                case .waiting, .unconfigured: break
                }
            }
        case .sessionLost:
            connection = .failed
        default:
            break
        }
        let reduction = TurnFlow.reduce(flow, event, nextTurnId: newTurnId)
        flow = reduction.state
        applyEffects(reduction.effects)
        if event.attachesAudio { _ = attachAudioPaths() }
        project()
    }

    private func applyEffects(_ effects: [TurnEffect]) {
        for effect in effects {
            switch effect {
            case .startCapture(let turnId):
                capturingTurnId = turnId
                beginCaptureOrRetry()
            case .stopCapture:
                meter.reset()
                stopPreview()
                // 封口前先量出这段录音的真实长度，打卡认的就是这个数，文件随后删掉。
                let captured = capturingTurnId
                capturingTurnId = nil
                Task {
                    guard let clip = await recorder.finish() else { return }
                    if let captured { spokenMs[captured] = clip.durationMs }
                    try? FileManager.default.removeItem(atPath: clip.path)
                }
            case .sendTurnStart:
                session?.startTurn()
            case .sendTurnEnd:
                session?.endTurn()
            case .sendText(let text):
                session?.sendText(text)
            case .cancelTurn(let turnId):
                session?.cancelTurn(turnId)
            case .play(let pcm, let turnId):
                if let turnId, !audio.accepts(turnId) { continue }
                player.enqueue(pcm)
                if let turnId {
                    recordedTurns.insert(turnId)
                    audioContinuation.yield(AudioChunk(turnId: turnId, pcm: pcm))
                }
            case .stopPlayback:
                player.flush()
            case .persist(var turn):
                if turn.audioPath == nil, recordedTurns.contains(turn.id) { turn.audioPath = turnAudio.path(turnId: turn.id) }
                if turn.sessionId == nil { turn.sessionId = sessionId }
                // 自己说的那一轮带上量到的长度；打字发的、助手说的都没有这一段录音。
                if turn.speaker == .user, let spoken = spokenMs.removeValue(forKey: turn.id) { turn.durationMs = spoken }
                Task { try? await store.saveTurn(turn) }
            case .delete(let turnId):
                // 作废掉的那一轮不会再落库，量到的长度也跟着丢。
                spokenMs.removeValue(forKey: turnId)
                purgeTurn(turnId)
            case .analyze(let turnId, let text):
                launchAnalysis(turnId: turnId, text: text)
            case .learnVocab(let turnId, let cards):
                turnVocab[turnId] = cards
                mergeVocab(cards)
                Task { try? await store.recordVocab(cards: cards) }
            case .notify(let message):
                showNotice(message)
            }
        }
    }

    private func beginCaptureOrRetry() {
        if tryStartCapture() { return }
        Task {
            try? await Task.sleep(for: .milliseconds(180))
            if !tryStartCapture() { dispatch(.captureFailed) }
        }
    }

    private func tryStartCapture() -> Bool {
        guard let current = session else { return false }
        capture.reset()
        let meter = meter
        let started = recorder.start(
            cacheDirectory: FileManager.default.temporaryDirectory,
            interruptPlayer: player,
            onFrame: { [weak self, weak current] frame in
                guard let self, let current else { return }
                self.capture.record(frame)
                current.sendAudio(frame)
            },
            onLevel: { level in
                meter.push(level)
            }
        )
        if started {
            meter.begin()
        }
        return started
    }

    private func project() {
        let next = ChatState(
            turns: flow.turns,
            hint: state.hint,
            recording: {
                if case .recording = flow.phase { return true }
                return false
            }(),
            connection: connection,
            notice: notice,
            sessionName: (continuing?.name).flatMap { $0.isEmpty ? nil : $0 },
            sessionId: continuing?.id
        )
        if state != next {
            state = next
        }
    }

    private func connect(history: String? = nil) {
        let seed = history ?? historyOf
        let live = settings.live
        guard live.ready else {
            connection = .unconfigured
            cancelReconnect()
            project()
            return
        }
        connectSeq += 1
        let seq = connectSeq
        Task {
            let instructions = MemoryRules.systemPromptOf(rolePrompt: plan.prompt, memory: await memory.text)
            let created: RealtimeSession
            switch live.provider {
            case .gemini:
                created = GeminiLiveSession(
                    apiKey: live.geminiKey,
                    model: live.model,
                    voice: live.voice,
                    systemPrompt: instructions,
                    onEvent: { event in self.onEvent(seq: seq, event: event) },
                    history: seed
                )
            case .openAi:
                created = OpenAiRealtimeSession(
                    apiKey: live.openAiKey,
                    model: live.model,
                    voice: live.voice,
                    instructions: instructions,
                    onEvent: { event in self.onEvent(seq: seq, event: event) },
                    history: seed
                )
            case .doubao:
                created = DoubaoRealtimeSession(
                    appId: live.doubaoAppId,
                    appKey: live.doubaoAppKey,
                    accessKey: live.doubaoAccessKey,
                    voice: live.voice,
                    systemPrompt: instructions,
                    onEvent: { event in self.onEvent(seq: seq, event: event) },
                    history: seed
                )
            }
            guard seq == connectSeq else {
                created.close()
                return
            }
            session = created
            capabilities = created.capabilities
            connection = .connecting
            project()
            created.connect()
        }
    }

    private func dropSession() {
        connectSeq += 1
        session?.close()
        session = nil
        capabilities = nil
    }

    private func onEvent(seq: Int, event: RealtimeEvent) {
        guard seq == connectSeq else { return }
        switch event {
        case .ready: dispatch(.sessionReady)
        case .userText(let text, let id): dispatch(.userText(text, id))
        case .assistantText(let text, let id): dispatch(.assistantText(text, id))
        case .audio(let pcm, let id): dispatch(.audio(pcm, id))
        case .interrupted: dispatch(.interrupted)
        case .turnComplete: dispatch(.turnComplete)
        case .failed(let message):
            if !message.isEmpty { showNotice(message) } else { logger.info("连接失败，按退避重连") }
            dispatch(.sessionLost)
            scheduleReconnect(seq: seq)
        }
    }

    private func scheduleReconnect(seq: Int) {
        guard seq == connectSeq, !ledger.current.closed, settings.live.ready, reconnectTask == nil else { return }
        let mine = reconnectState
        let wait = mine.waitMs(jitter: Double.random(in: 0 ... 1))
        reconnectState = mine.scheduled()
        reconnectTask = Task { [weak self] in
            try? await Task.sleep(for: .milliseconds(wait))
            guard let self else { return }
            self.reconnectTask = nil
            guard self.reconnectState.isCurrent(mine.epoch), seq == self.connectSeq else { return }
            self.retryNow()
        }
    }

    private func cancelReconnect() {
        reconnectTask?.cancel()
        reconnectTask = nil
        reconnectState = reconnectState.reset()
    }

    private func retryNow() {
        reconnectTask?.cancel()
        reconnectTask = nil
        reconnectState = reconnectState.skipped()
        dropSession()
        connect()
    }

    private func launchAnalysis(turnId: String, text: String) {
        let pcm = capture.takeUplink()
        let assessor = assessorOf(settings: settings.assessor)
        Task {
            let engine = await analyzerWithMeta()
            guard let analysis = await engine.analyze(text: text, turnId: turnId) else {
                if analysisRoute(flow.turns, turnId: turnId) == .flow { dispatch(.analysisFailed(turnId)) }
                return
            }
            let review = await withPronunciation(text: text, review: analysis.review, assessor: assessor, pcm: pcm)
            switch analysisRoute(flow.turns, turnId: turnId) {
            case .flow:
                dispatch(.analysisDone(turnId, review, analysis.vocab))
            case .store:
                await writeBackAnalysis(turnId: turnId, review: review, vocab: analysis.vocab)
            }
        }
        Task { await memory.offer(turnId: turnId) }
    }

    private func writeBackAnalysis(turnId: String, review: Review, vocab: [VocabCard]) async {
        _ = try? await store.saveReview(turnId: turnId, review: review)
        guard !vocab.isEmpty else { return }
        mergeVocab(vocab)
        try? await store.recordVocab(cards: vocab)
    }

    private func withPronunciation(
        text: String,
        review: Review,
        assessor: SpeechAssessor?,
        pcm: Data
    ) async -> Review {
        guard let assessor, !text.isEmpty, !pcm.isEmpty,
              let result = await assessor.assess(pcm16k: pcm, referenceText: text, locale: "en-US"),
              let scoreValue = result.total ?? result.accuracy
        else { return review }
        let pieces = [
            result.accuracy.map { "准确度 \(Int($0.rounded()))" },
            result.fluency.map { "流利度 \(Int($0.rounded()))" },
            result.prosody.map { "韵律 \(Int($0.rounded()))" },
        ].compactMap { $0 }
        var changed = review
        changed.pronunciation = PronunciationReview(
            score: min(100, max(0, Int(scoreValue.rounded()))),
            note: pieces.joined(separator: " · "),
            phonemes: result.issues
        )
        return changed
    }

    private func attachAudioPaths() -> [Turn] {
        let pending = Set(flow.turns.filter {
            $0.speaker == .echo && $0.audioPath == nil && recordedTurns.contains($0.id)
        }.map(\.id))
        guard !pending.isEmpty else { return [] }
        flow.turns = flow.turns.map {
            var turn = $0
            if pending.contains(turn.id) { turn.audioPath = turnAudio.path(turnId: turn.id) }
            return turn
        }
        let filled = flow.turns.filter { pending.contains($0.id) }
        Task {
            for var turn in filled {
                if turn.sessionId == nil { turn.sessionId = sessionId }
                try? await store.saveTurn(turn)
            }
        }
        return filled
    }

    private func mergeVocab(_ cards: [VocabCard]) {
        let now = Int64(Date().timeIntervalSince1970 * 1000)
        let incoming = Dictionary(cards.map { ($0.word.lowercased(), $0) }, uniquingKeysWith: { first, _ in first })
        let known = Set(vocab.map { $0.word.lowercased() })
        let merged = vocab.map { card -> VocabCard in
            guard incoming[card.word.lowercased()] != nil else { return card }
            var changed = card
            changed.repeats += 1
            changed.lastSeenAt = now
            return changed
        }
        let fresh = incoming.values.filter { !known.contains($0.word.lowercased()) }.map {
            var changed = $0
            changed.lastSeenAt = now
            return changed
        }
        vocab = fresh + merged
    }

    @discardableResult
    private func endSegment() -> Bool {
        guard let ended = ledger.closeIfOpen() else { return false }
        let closedTurns = flow.turns
        cancelReconnect()
        meter.reset()
        recorder.cancel()
        stopPreview()
        player.stop()
        dropSession()
        _ = attachAudioPaths()
        flow.phase = .offline
        flow.cancelArmed = false
        connection = settings.live.ready ? .failed : .unconfigured
        clearNotice()
        greeted = false
        player.flush()
        project()
        guard !closedTurns.isEmpty else { return true }
        Task {
            let record = try? await store.closeSession(id: ended.id, startedAt: ended.startedAt)
            publishSession(record)
            loadSessions()
            // 名字只写一次：接着聊回来的那一段早就有名字，收回去时原样留着。
            guard let record, record.name.isEmpty,
                  let name = await nameOfSession(id: ended.id, fallback: closedTurns),
                  let target = autoNameTarget(lastSession, endedId: ended.id, name: name)
            else { return }
            publishSession(target)
            loadSessions()
        }
        return true
    }

    private func rollSegment() {
        _ = endSegment()
        beginSegment()
    }

    private func beginSegment(greet: Bool = true) {
        // 读进来的那一截还没跑完就换了段：那一次续聊作废，别把它接上来的连接留在线路上。
        resumeTask?.cancel()
        resumeTask = nil
        ledger.begin()
        plan = SegmentPlan(prompt: settings.prompt)
        // 新的一段不是接着哪一条聊的，顶栏中段的会话名与开场流水都跟着退下去。
        continuing = nil
        historyOf = nil
        recordedTurns.removeAll()
        turnVocab.removeAll()
        audio.reset()
        capture.dropUplink()
        flow = TurnFlowState()
        greeted = !greet
        project()
        connect()
    }

    private func publishSession(_ record: SessionRecord?) {
        lastSession = record
        project()
    }

    private func refreshProviders() {
        providers = providerLines(settings: settings, status: linkStatus)
    }

    private func publishCachedModels() async {
        for provider in TextProviderCatalog.all { await publishModels(provider.id) }
    }

    private func publishModels(_ providerId: String) async {
        let models = (try? await catalogs.models(providerId: providerId)) ?? []
        textProviders = injectModels(textProviders, providerId: providerId, models: models)
        await adoptModel(providerId: providerId, models: models)
    }

    private func refreshModels(_ providerId: String) async {
        guard let provider = TextProviderCatalog.of(providerId) else { return }
        let credentials = settings.analysis.of(providerId)
        let models = (try? await catalogs.refresh(
            provider: provider,
            apiKey: credentials.apiKey,
            endpoint: credentials.endpoint.isEmpty ? provider.endpoint : credentials.endpoint,
            auth: credentials.authSpec(provider: provider)
        )) ?? []
        textProviders = injectModels(textProviders, providerId: provider.id, models: models)
        await adoptModel(providerId: provider.id, models: models)
    }

    private func adoptModel(providerId: String, models: [TextModel]) async {
        guard let next = pickedAnalysis(settings.analysis, providerId: providerId, models: models) else { return }
        settingsStore.update { value in
            var changed = value
            changed.analysis = next
            return changed
        }
        refreshProviders()
    }

    private func syncCatalog(before: AnalysisSettings, after: AnalysisSettings) async {
        var ids = Set(before.saved.keys).union(after.saved.keys)
        if ids.isEmpty { ids.insert(after.provider) }
        for id in ids where before.of(id) != after.of(id) { await refreshModels(id) }
    }

    private func probeReport(live: LiveSettings, status: LinkStatus) -> String {
        if !live.ready { return "凭据不齐，\(live.provider.label) 还缺参数" }
        if live.provider == .gemini, status == .connected { return "自检通过，Gemini Live 可连" }
        if live.provider == .gemini { return "自检失败，检查密钥与网络" }
        return "\(live.provider.label) 参数齐备；自检会真发请求的只有 Gemini，这两家只判参数"
    }

    private func clearNotice() {
        noticeTask?.cancel()
        noticeTask = nil
        notice = nil
    }

    private func analyzerWithMeta() async -> TurnAnalyzer {
        let analysis = settings.analysis
        guard !analysis.model.isEmpty,
              let model = try? await catalogs.model(providerId: analysis.provider, modelId: analysis.model)
        else { return TurnAnalyzerFactory.of(settings: analysis) }
        return TurnAnalyzerFactory.of(settings: analysis, models: [model])
    }

    private func nameOfSession(id: String, fallback: [Turn]) async -> String? {
        guard !settings.analysis.apiKey.isEmpty else { return nil }
        let stored = (try? await store.transcript(sessionId: id)) ?? []
        let turns = stored.isEmpty ? fallback : stored
        let text = turns.filter { !$0.text.isEmpty }.map(MemoryRules.transcriptLine).joined(separator: "\n")
        guard !text.isEmpty, let name = await analyzerWithMeta().name(transcript: text) else { return nil }
        try? await store.rename(id: id, name: name, manual: false)
        return name
    }

    private func backfillSessionNames() async {
        let pending = (try? await store.takeUnnamed()) ?? []
        for record in pending { _ = await nameOfSession(id: record.id, fallback: []) }
    }

    private func watchIdle() async {
        while !Task.isCancelled {
            try? await Task.sleep(for: .milliseconds(IdleCut.checkMs))
            if flow.turns.isEmpty { continue }
            let recording: Bool
            if case .recording = flow.phase { recording = true } else { recording = false }
            if !ledger.idleDue(recording: recording) { continue }
            _ = endSegment()
            beginSegment(greet: false)
        }
    }

    private func drainMemory() async {
        await memory.drain(
            materialOf: { [weak self] turnId in
                await MainActor.run { self.map { MemoryRules.memoryMaterial(turns: $0.flow.turns, turnId: turnId) } ?? nil }
            },
            rewrite: { [weak self] current, material in
                guard let self else { return nil }
                let analyzer = await self.analyzerWithMeta()
                return await analyzer.memorize(memory: current, material: material)
            }
        )
    }

    private func drainAudio() async {
        for await chunk in audioStream {
            guard audio.accepts(chunk.turnId) else { continue }
            await turnAudio.append(turnId: chunk.turnId, pcm: chunk.pcm)
        }
    }

    private func stopPreview() {
        clipPlayer.release()
        previewClip = nil
        voicePreview = nil
    }

    private func purgeTurn(_ turnId: String) {
        recordedTurns.remove(turnId)
        audio.delete(turnId)
        Task {
            try? await store.deleteTurn(turnId: turnId)
            await turnAudio.delete(turnId: turnId)
        }
    }

    private func turnById(_ turnId: String) -> Turn? {
        flow.turns.first { $0.id == turnId }
    }

    private func newTurnId(_ prefix: String) -> String {
        "\(prefix)-\(DispatchTime.now().uptimeNanoseconds)"
    }

    private func persistOnExit() async {
        guard let ended = ledger.closeIfOpen(), !flow.turns.isEmpty else { return }
        let filled = attachAudioPaths()
        for turn in filled { try? await store.saveTurn(turn) }
        _ = try? await store.closeSession(id: ended.id, startedAt: ended.startedAt)
    }
}

private struct AudioChunk {
    var turnId: String
    var pcm: Data
}

private struct AudioGate {
    private var deleted = Set<String>()
    mutating func delete(_ turnId: String) { deleted.insert(turnId) }
    func accepts(_ turnId: String) -> Bool { !deleted.contains(turnId) }
    mutating func reset() { deleted.removeAll() }
}

private final class CaptureBuffer: @unchecked Sendable {
    private let lock = NSLock()
    private var uplink = Data()

    func reset() {
        lock.lock()
        uplink.removeAll(keepingCapacity: true)
        lock.unlock()
    }

    func record(_ frame: [Int16]) {
        lock.lock()
        if uplink.count < 16_000 * 2 * 60 {
            frame.withUnsafeBytes { uplink.append(contentsOf: $0) }
        }
        lock.unlock()
    }

    /// 判定用：读一份上行音频的副本，不动缓冲本身。
    func peekPcm() -> [Int16] {
        lock.lock()
        defer { lock.unlock() }
        var samples = [Int16](repeating: 0, count: uplink.count / 2)
        uplink.withUnsafeBytes { raw in
            for index in samples.indices {
                let offset = index * 2
                let low = Int16(raw[offset]) & 0x00FF
                let high = Int16(bitPattern: UInt16(raw[offset + 1]) << 8)
                samples[index] = high | low
            }
        }
        return samples
    }

    func takeUplink() -> Data {
        lock.lock()
        defer { lock.unlock() }
        let value = uplink
        uplink.removeAll(keepingCapacity: true)
        return value
    }

    func dropUplink() {
        lock.lock()
        uplink.removeAll(keepingCapacity: true)
        lock.unlock()
    }
}

private enum AnalysisRoute { case flow, store }

private func analysisRoute(_ turns: [Turn], turnId: String) -> AnalysisRoute {
    turns.contains { $0.id == turnId } ? .flow : .store
}

private struct ReconnectState {
    var attempt = 0
    var epoch = 0
    func waitMs(jitter: Double) -> Int64 { ReconnectBackoff.delayMs(attempt: attempt, jitter: jitter) }
    func scheduled() -> Self { Self(attempt: attempt + 1, epoch: epoch) }
    func skipped() -> Self { Self(attempt: attempt, epoch: epoch + 1) }
    func reset() -> Self { Self(attempt: 0, epoch: epoch + 1) }
    func isCurrent(_ value: Int) -> Bool { epoch == value }
}

private extension TurnEvent {
    var attachesAudio: Bool {
        switch self {
        case .turnComplete, .interrupted, .pressStart: true
        default: false
        }
    }
}

func autoNameTarget(_ last: SessionRecord?, endedId: String, name: String) -> SessionRecord? {
    guard var last, last.id == endedId, !last.manualName else { return nil }
    last.name = name
    return last
}

func injectModels(_ providers: [TextProvider], providerId: String, models: [TextModel]) -> [TextProvider] {
    providers.map {
        var provider = $0
        if provider.id == providerId { provider.models = models }
        return provider
    }
}

func pickedAnalysis(_ analysis: AnalysisSettings, providerId: String, models: [TextModel]) -> AnalysisSettings? {
    let current = analysis.of(providerId).model
    let picked = ModelCatalog.pick(ModelCatalog.ordered(models), current: current)
    guard !picked.isEmpty, picked != current else { return nil }
    return analysis.amend(providerId) {
        var changed = $0
        changed.model = picked
        return changed
    }
}

func liveKey(_ vendor: LiveVendor) -> String { vendor.rawValue.lowercased() }

private func providerLines(settings: AppSettings, status: [String: LinkStatus]) -> [ProviderLine] {
    let live = settings.live
    let realtime = LiveVendor.allCases.map { vendor in
        ProviderLine(
            key: liveKey(vendor),
            group: "realtime",
            name: vendor.label,
            detail: liveDetail(live: live, vendor: vendor),
            status: status[liveKey(vendor)] ?? (live.ready(vendor) ? .configured : .unconfigured),
            editable: true
        )
    }
    let assessor = assessorOf(settings: settings.assessor)
    let assessors = [
        ProviderLine(
            key: "azure",
            group: "assessor",
            name: AssessorProvider.azure.label,
            detail: settings.assessor.azureRegion.isEmpty ? "密钥与区域" : settings.assessor.azureRegion,
            status: assessor is AzureAssessor ? .configured : .unconfigured,
            editable: true
        ),
        ProviderLine(
            key: "xfyun",
            group: "assessor",
            name: AssessorProvider.xfyun.label,
            detail: "AppID、APIKey、APISecret",
            status: assessor is XfyunAssessor ? .configured : .unconfigured,
            editable: true
        ),
    ]
    let text = TextProviderCatalog.all.map { provider in
        let own = settings.analysis.of(provider.id)
        return ProviderLine(
            key: provider.id == TextProviderCatalog.geminiId ? "analysis" : provider.id,
            group: "analysis",
            name: provider.label,
            detail: own.model.isEmpty ? hostOf(provider.endpoint) : own.model,
            status: own.ready ? .configured : .unconfigured,
            editable: true
        )
    }
    return realtime + assessors + text
}

private func liveDetail(live: LiveSettings, vendor: LiveVendor) -> String {
    let own = live.provider == vendor
    let model = own ? live.model : LiveDefaults.model(vendor)
    return model.isEmpty ? (own ? live.voice : LiveDefaults.voice(vendor)) : model
}

private func hostOf(_ endpoint: String) -> String {
    URL(string: endpoint)?.host ?? "未填写"
}
