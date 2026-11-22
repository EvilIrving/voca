import Foundation
import os

final class DoubaoRealtimeSession: RealtimeSession, @unchecked Sendable {
    let capabilities: Capabilities

    private let appId: String
    private let appKey: String
    private let accessKey: String
    private let voice: String
    private let systemPrompt: String
    private let onEvent: @MainActor (RealtimeEvent) -> Void
    private let pushToTalk: Bool
    private let connectId: String

    private let logger = Logger(subsystem: "com.parley.speaker", category: "DoubaoRealtime")
    private let workQueue = DispatchQueue(label: "DoubaoRealtimeSession.work")
    private let frameLock = NSLock()
    private let socketDelegate = DoubaoWebSocketDelegate()

    private var session: URLSession!
    private var socket: URLSessionWebSocketTask?
    private var pingTimer: DispatchSourceTimer?

    private var pendingFrames = [Data]()
    private let late: LateTurn

    private var sessionId: String
    private var connectionOpen = false
    private var ready = false
    private var turnOpen = false
    private var closed = false
    private var pendingEnd = false

    private static let maxPending = 200
    private static let lateWindowMs: Int64 = 1_500

    init(
        appId: String,
        appKey: String,
        accessKey: String,
        voice: String = VoiceCatalog.defaultId(.doubao),
        systemPrompt: String = "",
        onEvent: @escaping @MainActor (RealtimeEvent) -> Void,
        pushToTalk: Bool = true,
        connectId: String = UUID().uuidString,
        /// 接着一条历史会话聊时要带上的那段流水：豆包没有跨连接续上的句柄，它接在系统提示后面。
        history: String? = nil
    ) {
        self.appId = appId
        self.appKey = appKey
        self.accessKey = accessKey
        self.voice = voice
        self.systemPrompt = promptWithHistory(systemPrompt, history: history)
        self.onEvent = onEvent
        self.pushToTalk = pushToTalk
        self.connectId = connectId
        self.sessionId = UUID().uuidString
        self.late = LateTurn(windowMs: Self.lateWindowMs) {
            Int64(DispatchTime.now().uptimeNanoseconds / 1_000_000)
        }
        self.capabilities = Capabilities(
            canInterrupt: true,
            acceptsText: false,
            streamsTranscript: true,
            phonemeScores: false,
            maxSessionMinutes: nil
        )
        let cfg = URLSessionConfiguration.default
        cfg.timeoutIntervalForRequest = 8
        socketDelegate.owner = self
        self.session = URLSession(configuration: cfg, delegate: socketDelegate, delegateQueue: nil)
    }

    func connect() {
        workQueue.async { [weak self] in
            guard let self else { return }
            if self.closed || self.socket != nil { return }
            self.logger.info("connecting appId=\(self.appId, privacy: .public) voice=\(self.voice, privacy: .public) pushToTalk=\(self.pushToTalk) keyLen=\(self.accessKey.count)")
            var request = URLRequest(url: URL(string: DoubaoWire.endpoint)!)
            request.setValue(appId, forHTTPHeaderField: "X-Api-App-ID")
            request.setValue(appKey, forHTTPHeaderField: "X-Api-App-Key")
            request.setValue(accessKey, forHTTPHeaderField: "X-Api-Access-Key")
            request.setValue(DoubaoWire.resourceId, forHTTPHeaderField: "X-Api-Resource-Id")
            request.setValue(connectId, forHTTPHeaderField: "X-Api-Connect-Id")
            let task = self.session.webSocketTask(with: request)
            self.socket = task
            task.resume()
            self.startPing()
            self.listen(task)
        }
    }

    func startTurn() {
        workQueue.async { [weak self] in
            guard let self else { return }
            self.turnOpen = true
            self.late.nextTurn()
        }
    }

    func sendAudio(_ frame: [Int16]) {
        workQueue.async { [weak self] in
            guard let self, self.turnOpen, !frame.isEmpty else { return }
            let pcm = Pcm.shortsToBytes(frame)
            if !self.ready {
                self.frameLock.lock()
                if self.pendingFrames.count < Self.maxPending { self.pendingFrames.append(pcm) }
                self.frameLock.unlock()
                return
            }
            self.send(DoubaoWire.audio(sessionId: self.sessionId, pcm: pcm))
        }
    }

    func sendText(_ text: String) {
        workQueue.async { [weak self] in
            self?.logger.info("text input not supported yet, dropped")
        }
    }

    func endTurn() {
        workQueue.async { [weak self] in
            guard let self else { return }
            self.turnOpen = false
            if !self.pushToTalk { return }
            if !self.ready {
                self.pendingEnd = true
                self.logger.info("endTurn parked until SessionStarted")
                return
            }
            self.send(DoubaoWire.turnEnd(sessionId: self.sessionId))
        }
    }

    func cancelTurn(_ turnId: String) {
        workQueue.async { [weak self] in
            guard let self else { return }
            self.turnOpen = false
            self.pendingEnd = false
            self.frameLock.lock()
            self.pendingFrames.removeAll()
            self.frameLock.unlock()
            self.late.cancel(turnId: turnId)
        }
    }

    func interrupt() {
        workQueue.async { [weak self] in
            guard let self else { return }
            self.turnOpen = false
            self.emit(.interrupted)
        }
    }

    func close() {
        workQueue.async { [weak self] in
            guard let self else { return }
            self.closed = true
            let wasReady = self.ready
            self.ready = false
            self.turnOpen = false
            if wasReady && self.connectionOpen {
                self.send(DoubaoWire.finishSession(sessionId: self.sessionId))
                self.send(DoubaoWire.finishConnection())
            }
            self.stopPing()
            let current = self.socket
            self.socket = nil
            current?.cancel(with: .goingAway, reason: nil)
            self.session.invalidateAndCancel()
        }
    }

    func handleSocketOpen(_ task: URLSessionWebSocketTask) {
        let code = (task.response as? HTTPURLResponse)?.statusCode ?? 0
        logger.info("open \(code)")
        connectionOpen = true
        send(DoubaoWire.startConnection())
    }

    private func reset() {
        ready = false
        connectionOpen = false
        socket = nil
    }

    private func classify(_ error: Error, response: HTTPURLResponse?) -> String {
        if let response {
            let code = response.statusCode
            if code == 401 || code == 403 { return ProviderFault.key }
            if code == 404 { return "端点不可用，请在设置中检查" }
        }
        if let urlError = error as? URLError {
            switch urlError.code {
            case .timedOut, .cannotFindHost, .secureConnectionFailed:
                return ""
            default:
                break
            }
        }
        return ""
    }

    private func listen(_ task: URLSessionWebSocketTask) {
        task.receive { [weak self, weak task] result in
            guard let self, let task, task === self.socket else { return }
            switch result {
            case let .success(message):
                switch message {
                case let .data(data):
                    self.accept(task, bytes: data)
                case let .string(text):
                    self.logger.warning("unexpected text frame: \(text.prefix(120), privacy: .public)")
                @unknown default:
                    break
                }
                if self.socket === task { self.listen(task) }
            case let .failure(error):
                guard self.socket === task else { return }
                self.reset()
                self.stopPing()
                self.logger.warning("socket failed: \(error.localizedDescription, privacy: .public)")
                if self.closed { return }
                self.emit(.failed(self.classify(error, response: task.response as? HTTPURLResponse)))
            }
        }
    }

    func handleSocketClose(_ task: URLSessionWebSocketTask, code: Int, reason: String) {
        guard socket === task else { return }
        reset()
        stopPing()
        logger.info("closed \(code) \(reason, privacy: .public)")
        if closed { return }
        emit(.failed(""))
    }

    private func flushPending() {
        frameLock.lock()
        let buffered = pendingFrames
        pendingFrames.removeAll()
        frameLock.unlock()
        for pcm in buffered {
            send(DoubaoWire.audio(sessionId: sessionId, pcm: pcm))
        }
        if pendingEnd {
            pendingEnd = false
            if pushToTalk { send(DoubaoWire.turnEnd(sessionId: sessionId)) }
        }
    }

    private func send(_ frame: Data) {
        socket?.send(.data(frame)) { [weak self] error in
            if let error { self?.logger.warning("send failed: \(error.localizedDescription, privacy: .public)") }
        }
    }

    private func accept(_ task: URLSessionWebSocketTask, bytes: Data) {
        guard socket === task else { return }
        let frame: DoubaoFrame
        do {
            frame = try DoubaoFrameCodec.decode(bytes)
        } catch {
            logger.warning("bad frame: \(error.localizedDescription, privacy: .public)")
            return
        }
        let inbound = DoubaoWire.parse(frame)
        if case .sessionStarted = inbound {
            if let id = DoubaoWire.sessionIdOf(frame), !id.isEmpty { sessionId = id }
            let first = !ready
            ready = true
            if first { flushPending() }
            if first && !closed { emit(.ready) }
            return
        }
        if case .connectionStarted = inbound {
            send(DoubaoWire.startSession(sessionId: sessionId, speaker: voice, systemPrompt: systemPrompt, pushToTalk: pushToTalk))
            return
        }
        if case .sessionFinished = inbound {
            late.close()
            if closed { return }
            emit(.failed(""))
            return
        }
        let tag = late.tag(responseId: nil)
        guard let event = inbound.event() else { return }
        switch event {
        case let .audio(pcm, _):
            emit(.audio(pcm, tag))
        case let .assistantText(text, _):
            emit(.assistantText(text, tag))
        case let .userText(text, _):
            emit(.userText(text, tag))
        case .turnComplete:
            late.close()
            if tag != nil { return }
            emit(.turnComplete)
        case let .failed(message):
            logger.warning("server error: \(message, privacy: .public)")
            emit(.failed(message))
        case .ready, .interrupted:
            break
        }
    }

    private func startPing() {
        stopPing()
        let timer = DispatchSource.makeTimerSource(queue: workQueue)
        timer.schedule(deadline: .now() + .seconds(15), repeating: .seconds(15))
        timer.setEventHandler { [weak self] in
            self?.socket?.sendPing { _ in }
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
}

private final class DoubaoWebSocketDelegate: NSObject, URLSessionWebSocketDelegate {
    weak var owner: DoubaoRealtimeSession?

    func urlSession(
        _ session: URLSession,
        webSocketTask: URLSessionWebSocketTask,
        didOpenWithProtocol protocol: String?
    ) {
        owner?.onWork { $0.handleSocketOpen(webSocketTask) }
    }

    func urlSession(
        _ session: URLSession,
        webSocketTask: URLSessionWebSocketTask,
        didCloseWith closeCode: URLSessionWebSocketTask.CloseCode,
        reason: Data?
    ) {
        let text = reason.flatMap { String(data: $0, encoding: .utf8) } ?? ""
        owner?.onWork { $0.handleSocketClose(webSocketTask, code: closeCode.rawValue, reason: text) }
    }
}

extension DoubaoRealtimeSession {
    fileprivate func onWork(_ block: @escaping (DoubaoRealtimeSession) -> Void) {
        workQueue.async { [weak self] in
            guard let self else { return }
            block(self)
        }
    }
}