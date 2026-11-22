import Foundation
import os

final class GeminiLiveSession: RealtimeSession, @unchecked Sendable {
    static let defaultSessionMinutes = 10

    let capabilities: Capabilities

    private let apiKey: String
    private let model: String
    private let voice: String
    private let systemPrompt: String
    private let onEvent: @MainActor (RealtimeEvent) -> Void
    private let maxSessionMinutes: Int

    private let logger = Logger(subsystem: "com.parley.speaker", category: "GeminiLive")
    private let workQueue = DispatchQueue(label: "GeminiLiveSession.work")
    private let frameLock = NSLock()
    private let transcriptLock = NSLock()

    private let socketDelegate = GeminiWebSocketDelegate()
    private var session: URLSession!
    private var socket: URLSessionWebSocketTask?
    private var pingTimer: DispatchSourceTimer?

    private var pendingFrames = [Data]()
    private let cancels: CancelWindow

    private var dialogue = [String]()
    private var userBuffer = ""
    private var replyBuffer = ""

    private var ready = false
    private var turnOpen = false
    private var awaitingTurn = false
    private var closed = false
    private var resumeHandle: String?
    private var reconnectAttempts = 0
    private var rotatePending = false
    private var pendingStart = false
    private var pendingEnd = false
    private var rotationWorkItem: DispatchWorkItem?
    private var setupWorkItem: DispatchWorkItem?
    private var resumeWorkItem: DispatchWorkItem?
    private var serverReason: String?
    private var resumeWith: String?
    private var seed: String?

    /// 接着一条历史会话聊时要先灌进去的流水，只在第一次连接时交代。
    private let history: String?

    private static let maxPending = 200
    private static let minuteMs: Int64 = 60_000
    private static let cancelWindowMs: Int64 = 1_500
    private static let setupTimeoutMs: Int64 = 8_000
    private static let peekBytes = 1_024
    private static let maxResumeAttempts = 2
    private static let resumeBaseMs: Int64 = 400
    private static let resumeJitterMs: Int64 = 300
    private static let dialogueLines = 24
    private static let digestChars = 2_400

    /// `history` 是接着一条历史会话聊时要先灌进去的流水，只在第一次连接时交代，
    /// 之后换连接与断线续接都走各自的 handle 与摘要，不再重复这一段。
    init(
        apiKey: String,
        model: String,
        voice: String,
        systemPrompt: String,
        onEvent: @escaping @MainActor (RealtimeEvent) -> Void,
        maxSessionMinutes: Int = GeminiLiveSession.defaultSessionMinutes,
        history: String? = nil
    ) {
        self.apiKey = apiKey
        self.model = model
        self.voice = voice
        self.systemPrompt = systemPrompt
        self.onEvent = onEvent
        self.maxSessionMinutes = maxSessionMinutes
        self.history = history
        self.cancels = CancelWindow(windowMs: Self.cancelWindowMs) {
            Int64(DispatchTime.now().uptimeNanoseconds / 1_000_000)
        }
        self.capabilities = Capabilities(
            canInterrupt: true,
            acceptsText: true,
            streamsTranscript: true,
            phonemeScores: false,
            maxSessionMinutes: maxSessionMinutes
        )
        let cfg = URLSessionConfiguration.default
        cfg.timeoutIntervalForRequest = 8
        cfg.waitsForConnectivity = true
        socketDelegate.owner = self
        self.session = URLSession(configuration: cfg, delegate: socketDelegate, delegateQueue: nil)
    }

    func connect() {
        workQueue.async { [weak self] in
            guard let self else { return }
            if self.closed || self.socket != nil { return }
            self.logger.info("connecting model=\(self.model, privacy: .public) voice=\(self.voice, privacy: .public) keyLen=\(self.apiKey.count)")
            self.reconnectAttempts = 0
            self.open(resumeHandle: nil, seed: self.history)
        }
    }

    func startTurn() {
        workQueue.async { [weak self] in
            guard let self else { return }
            self.turnOpen = true
            self.awaitingTurn = false
            if !self.ready {
                self.pendingStart = true
                return
            }
            self.send(GeminiWire.activityStart())
        }
    }

    func sendAudio(_ frame: [Int16]) {
        workQueue.async { [weak self] in
            guard let self, self.turnOpen else { return }
            let bytes = Pcm.shortsToBytes(frame)
            if !self.ready {
                self.frameLock.lock()
                if self.pendingFrames.count < Self.maxPending { self.pendingFrames.append(bytes) }
                self.frameLock.unlock()
                return
            }
            self.send(GeminiWire.audio(base64: bytes.base64EncodedString()))
        }
    }

    func sendText(_ text: String) {
        workQueue.async { [weak self] in
            self?.send(GeminiWire.clientContent(text: text))
        }
    }

    func endTurn() {
        workQueue.async { [weak self] in
            guard let self else { return }
            self.turnOpen = false
            if !self.ready {
                self.pendingEnd = true
                self.logger.info("endTurn parked until setup completes")
                return
            }
            self.awaitingTurn = true
            self.send(GeminiWire.audioStreamEnd())
            self.send(GeminiWire.activityEnd())
        }
    }

    func cancelTurn(_ turnId: String) {
        workQueue.async { [weak self] in
            guard let self else { return }
            self.turnOpen = false
            self.awaitingTurn = false
            self.cancels.cancel(turnId: turnId)
            self.pendingStart = false
            self.pendingEnd = false
            self.frameLock.lock()
            self.pendingFrames.removeAll()
            self.frameLock.unlock()
            if !self.ready { return }
            self.send(GeminiWire.activityEnd())
        }
    }

    func interrupt() {
        workQueue.async { [weak self] in
            guard let self else { return }
            self.turnOpen = false
            self.awaitingTurn = false
            self.emit(.interrupted)
        }
    }

    func close() {
        workQueue.async { [weak self] in
            guard let self else { return }
            self.closed = true
            self.ready = false
            self.turnOpen = false
            self.awaitingTurn = false
            self.rotationWorkItem?.cancel()
            self.rotationWorkItem = nil
            self.setupWorkItem?.cancel()
            self.setupWorkItem = nil
            self.resumeWorkItem?.cancel()
            self.resumeWorkItem = nil
            self.stopPing()
            let current = self.socket
            self.socket = nil
            current?.cancel(with: .goingAway, reason: nil)
            self.session.invalidateAndCancel()
        }
    }

    private func open(resumeHandle: String?, seed: String?) {
        if closed { return }
        resumeWith = resumeHandle
        self.seed = seed
        ready = false
        serverReason = nil
        let url = URL(string: "wss://generativelanguage.googleapis.com/ws/google.ai.generativelanguage.v1beta.GenerativeService.BidiGenerateContent?key=\(apiKey)")!
        let task = session.webSocketTask(with: url)
        socket = task
        task.resume()
        startPing()
        listen(task)
        watchSetup(task)
    }

    private func watchSetup(_ task: URLSessionWebSocketTask) {
        setupWorkItem?.cancel()
        let item = DispatchWorkItem { [weak self, weak task] in
            guard let self, let task, task === self.socket, !self.ready else { return }
            self.logger.warning("setup timeout after \(Self.setupTimeoutMs)ms, lastServerReason=\(self.serverReason ?? "none", privacy: .public)")
            self.ready = false
            self.socket = nil
            task.cancel(with: .goingAway, reason: nil)
            self.emit(.failed(self.reasonOrFail("")))
        }
        setupWorkItem = item
        workQueue.asyncAfter(deadline: .now() + .milliseconds(Int(Self.setupTimeoutMs)), execute: item)
    }

    private func reasonOrFail(_ fallback: String) -> String {
        let said = serverReason
        serverReason = nil
        if said == nil || said!.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty { return fallback }
        logger.warning("server said \(said!.prefix(200), privacy: .public)")
        let notice = ProviderFault.notice(said)
        return notice.isEmpty ? fallback : notice
    }

    private func handshakeBody(_ response: HTTPURLResponse) -> String? {
        _ = response
        return nil
    }

    private func classify(_ error: Error, response: HTTPURLResponse?) -> String {
        if let response {
            let code = response.statusCode
            if code == 401 || code == 403 { return ProviderFault.key }
            if code == 404 { return ProviderFault.config }
            if code >= 400 { return ProviderFault.notice("\(code)", handshakeBody(response)) }
        }
        if error is URLError {
            let code = (error as? URLError)?.code
            if code == .timedOut || code == .cannotFindHost || code == .secureConnectionFailed { return "" }
        }
        return ""
    }

    private func listen(_ task: URLSessionWebSocketTask) {
        task.receive { [weak self, weak task] result in
            guard let self, let task, task === self.socket else { return }
            switch result {
            case let .success(message):
                switch message {
                case let .string(text):
                    self.receive(text)
                case let .data(data):
                    if let text = String(data: data, encoding: .utf8) { self.receive(text) }
                @unknown default:
                    break
                }
                if self.socket === task { self.listen(task) }
            case let .failure(error):
                self.onSocketFailure(task, error: error, response: task.response as? HTTPURLResponse)
            }
        }
    }

    func handleSocketOpen(_ task: URLSessionWebSocketTask) {
        let code = (task.response as? HTTPURLResponse)?.statusCode ?? 0
        logger.info("open \(code)")
        send(
            GeminiWire.setup(
                model: model,
                voice: voice,
                systemPrompt: systemPrompt,
                resumeHandle: resumeWith,
                seeding: seed != nil
            )
        )
    }

    func handleSocketClose(_ task: URLSessionWebSocketTask, code: Int, reason: String) {
        onSocketClosed(task, code: code, reason: reason)
    }

    private func onSocketFailure(_ task: URLSessionWebSocketTask, error: Error, response: HTTPURLResponse?) {
        guard socket === task else { return }
        ready = false
        socket = nil
        stopPing()
        logger.warning("socket failed: \(error.localizedDescription, privacy: .public)")
        if closed || resume() { return }
        emit(.failed(reasonOrFail(classify(error, response: response))))
    }

    private func onSocketClosed(_ task: URLSessionWebSocketTask, code: Int, reason: String) {
        guard socket === task else { return }
        ready = false
        socket = nil
        stopPing()
        logger.info("closed \(code) \(reason, privacy: .public)")
        if !reason.isEmpty && !closed { serverReason = reason.trimmingCharacters(in: .whitespacesAndNewlines) }
        if closed || resume() { return }
        emit(.failed(reasonOrFail("")))
    }

    private func resume() -> Bool {
        guard let current = resumeHandle else { return false }
        if reconnectAttempts >= Self.maxResumeAttempts { return false }
        reconnectAttempts += 1
        let wait = (Self.resumeBaseMs << (reconnectAttempts - 1)) + Int64.random(in: 0..<Self.resumeJitterMs)
        logger.info("resume attempt \(self.reconnectAttempts) in \(wait)ms")
        resumeWorkItem?.cancel()
        let item = DispatchWorkItem { [weak self] in
            guard let self, !self.closed, self.socket == nil else { return }
            self.open(resumeHandle: current, seed: nil)
        }
        resumeWorkItem = item
        workQueue.asyncAfter(deadline: .now() + .milliseconds(Int(wait)), execute: item)
        return true
    }

    private func flushPending() {
        if pendingStart {
            pendingStart = false
            send(GeminiWire.activityStart())
        }
        frameLock.lock()
        let buffered = pendingFrames
        pendingFrames.removeAll()
        frameLock.unlock()
        for bytes in buffered {
            send(GeminiWire.audio(base64: bytes.base64EncodedString()))
        }
        if pendingEnd {
            pendingEnd = false
            awaitingTurn = true
            send(GeminiWire.audioStreamEnd())
            send(GeminiWire.activityEnd())
        }
    }

    private func send(_ payload: [String: Any]) {
        guard let socket,
              let data = try? JSONSerialization.data(withJSONObject: payload),
              let text = String(data: data, encoding: .utf8)
        else { return }
        socket.send(.string(text)) { [weak self] error in
            if let error { self?.logger.warning("send failed: \(error.localizedDescription, privacy: .public)") }
        }
    }

    private func receive(_ text: String) {
        guard let data = text.data(using: .utf8),
              let root = (try? JSONSerialization.jsonObject(with: data)) as? [String: Any]
        else {
            logger.warning("bad payload")
            return
        }

        if root["setupComplete"] != nil {
            ready = true
            reconnectAttempts = 0
            setupWorkItem?.cancel()
            setupWorkItem = nil
            scheduleRotation()
            let history = seed
            seed = nil
            if let history { send(GeminiWire.clientContent(text: history)) }
            flushPending()
            emit(.ready)
            return
        }

        if let update = root["sessionResumptionUpdate"] as? [String: Any] {
            let resumable = jsonString(update["resumable"]) == "true"
            let fresh = jsonString(update["newHandle"])
            if resumable, let fresh, !fresh.isEmpty { resumeHandle = fresh }
        }

        if let goAway = root["goAway"] {
            logger.info("goAway \(String(describing: goAway), privacy: .public)")
            rotate()
        }

        if let content = root["serverContent"] as? [String: Any] {
            serverContent(content)
        }

        if let error = root["error"] as? [String: Any] {
            let code = jsonString(error["code"])
            let status = jsonString(error["status"])
            let said = jsonString(error["message"])
            logger.warning("server error \(code ?? "", privacy: .public) \(status ?? "", privacy: .public) \(said?.prefix(200) ?? "", privacy: .public)")
            emit(.failed(ProviderFault.notice(code, status, said)))
        }
    }

    private func serverContent(_ content: [String: Any]) {
        let tag = cancels.tag()

        if jsonString(content["interrupted"]) == "true" {
            awaitingTurn = false
            cancels.boundary()
            emit(.interrupted)
            if rotatePending && !turnOpen { rotate() }
            return
        }

        if jsonString(content["waitingForInput"]) == "true" {
            awaitingTurn = false
            cancels.boundary()
            if rotatePending && !turnOpen { rotate() }
        }

        if let input = content["inputTranscription"] as? [String: Any], let text = jsonString(input["text"]) {
            transcriptLock.lock()
            if tag == nil { userBuffer.append(text) }
            transcriptLock.unlock()
            emit(.userText(text, tag))
        }
        if let output = content["outputTranscription"] as? [String: Any], let text = jsonString(output["text"]) {
            transcriptLock.lock()
            if tag == nil { replyBuffer.append(text) }
            transcriptLock.unlock()
            emit(.assistantText(text, tag))
        }
        if let modelTurn = content["modelTurn"] as? [String: Any],
           let partsArray = modelTurn["parts"] as? [Any]
        {
            for item in partsArray {
                guard let part = item as? [String: Any],
                      let inline = part["inlineData"] as? [String: Any],
                      let dataB64 = jsonString(inline["data"]),
                      let pcm = Data(base64Encoded: dataB64)
                else { continue }
                emit(.audio(pcm, tag))
            }
        }

        if jsonString(content["turnComplete"]) == "true" {
            awaitingTurn = false
            flushDialogue()
            cancels.boundary()
            emit(.turnComplete)
            if rotatePending { rotate() }
        }
    }

    private func scheduleRotation() {
        rotationWorkItem?.cancel()
        let item = DispatchWorkItem { [weak self] in self?.rotate() }
        rotationWorkItem = item
        workQueue.asyncAfter(
            deadline: .now() + .milliseconds(maxSessionMinutes * Int(Self.minuteMs)),
            execute: item
        )
    }

    private func rotate() {
        if closed { return }
        if turnOpen || awaitingTurn {
            rotatePending = true
            return
        }
        rotatePending = false
        let history = digest()
        resumeHandle = nil
        reconnectAttempts = 0
        ready = false
        let stale = socket
        socket = nil
        stopPing()
        logger.info("rotating session after \(self.maxSessionMinutes) min, history=\(history?.count ?? 0) chars")
        stale?.cancel(with: .goingAway, reason: nil)
        open(resumeHandle: nil, seed: history)
    }

    private func flushDialogue() {
        transcriptLock.lock()
        flushDialogueLocked()
        transcriptLock.unlock()
    }

    private func flushDialogueLocked() {
        if !userBuffer.isEmpty {
            dialogue.append("我：\(userBuffer)")
            userBuffer = ""
        }
        if !replyBuffer.isEmpty {
            dialogue.append("对方：\(replyBuffer)")
            replyBuffer = ""
        }
        while dialogue.count > Self.dialogueLines { dialogue.removeFirst() }
    }

    private func digest() -> String? {
        transcriptLock.lock()
        defer { transcriptLock.unlock() }
        flushDialogueLocked()
        if dialogue.isEmpty { return nil }
        var kept = [String]()
        var budget = Self.digestChars
        for line in dialogue.reversed() {
            if line.count + 1 > budget { break }
            kept.insert(line, at: 0)
            budget -= line.count + 1
        }
        if kept.isEmpty { return nil }
        return "以下是刚才的对话记录，用来接上下文。\n" +
            kept.joined(separator: "\n") +
            "\n保持同样的角色和场景接着聊，不要复述这段记录。"
    }

    private func startPing() {
        stopPing()
        let timer = DispatchSource.makeTimerSource(queue: workQueue)
        timer.schedule(deadline: .now() + .seconds(15), repeating: .seconds(15))
        timer.setEventHandler { [weak self] in
            self?.socket?.sendPing { error in
                if let error { self?.logger.warning("ping failed: \(error.localizedDescription, privacy: .public)") }
            }
        }
        timer.resume()
        pingTimer = timer
    }

    private func stopPing() {
        pingTimer?.cancel()
        pingTimer = nil
    }

    private func emit(_ event: RealtimeEvent) {
        Task { @MainActor in self.onEvent(event) }
    }

    private func jsonString(_ value: Any?) -> String? {
        if let s = value as? String { return s }
        if let n = value as? NSNumber { return n.stringValue }
        if let b = value as? Bool { return b ? "true" : "false" }
        return nil
    }
}

private final class GeminiWebSocketDelegate: NSObject, URLSessionWebSocketDelegate {
    weak var owner: GeminiLiveSession?

    func urlSession(
        _ session: URLSession,
        webSocketTask: URLSessionWebSocketTask,
        didOpenWithProtocol protocol: String?
    ) {
        owner?.onDelegate { $0.handleSocketOpen(webSocketTask) }
    }

    func urlSession(
        _ session: URLSession,
        webSocketTask: URLSessionWebSocketTask,
        didCloseWith closeCode: URLSessionWebSocketTask.CloseCode,
        reason: Data?
    ) {
        let text = reason.flatMap { String(data: $0, encoding: .utf8) } ?? ""
        owner?.onDelegate {
            if !text.isEmpty {
                $0.noteClosingReason(text)
            }
            $0.handleSocketClose(webSocketTask, code: closeCode.rawValue, reason: text)
        }
    }
}

extension GeminiLiveSession {
    fileprivate func onDelegate(_ block: @escaping (GeminiLiveSession) -> Void) {
        workQueue.async { [weak self] in
            guard let self else { return }
            block(self)
        }
    }

    fileprivate func noteClosingReason(_ reason: String) {
        guard !closed else { return }
        serverReason = reason.trimmingCharacters(in: .whitespacesAndNewlines)
    }
}