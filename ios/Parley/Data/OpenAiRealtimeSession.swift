import Foundation
import os

final class OpenAiRealtimeSession: RealtimeSession, @unchecked Sendable {
    static let sessionMinutes = 60

    let capabilities: Capabilities

    private let apiKey: String
    private let model: String
    private let voice: String
    private let instructions: String
    private let onEvent: @MainActor (RealtimeEvent) -> Void

    private let logger = Logger(subsystem: "com.parley.speaker", category: "OpenAiRealtime")
    private let workQueue = DispatchQueue(label: "OpenAiRealtimeSession.work")
    private let frameLock = NSLock()
    private let socketDelegate = OpenAiWebSocketDelegate()

    private var session: URLSession!
    private var socket: URLSessionWebSocketTask?
    private var pingTimer: DispatchSourceTimer?

    private var pendingFrames = [Data]()
    private let late: LateTurn

    private var ready = false
    private var turnOpen = false
    private var closed = false
    private var pendingEnd = false
    private var limitWorkItem: DispatchWorkItem?

    private static let maxPending = 200
    private static let minuteMs: Int64 = 60_000
    private static let lateWindowMs: Int64 = 1_500

    init(
        apiKey: String,
        model: String,
        voice: String,
        instructions: String,
        onEvent: @escaping @MainActor (RealtimeEvent) -> Void,
        /// 接着一条历史会话聊时要带上的那段流水：没有可续的句柄，它接在 instructions 后面。
        history: String? = nil
    ) {
        self.apiKey = apiKey
        self.model = model
        self.voice = voice
        self.instructions = promptWithHistory(instructions, history: history)
        self.onEvent = onEvent
        self.late = LateTurn(windowMs: Self.lateWindowMs) {
            Int64(DispatchTime.now().uptimeNanoseconds / 1_000_000)
        }
        self.capabilities = Capabilities(
            canInterrupt: true,
            acceptsText: true,
            streamsTranscript: true,
            phonemeScores: false,
            maxSessionMinutes: Self.sessionMinutes
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
            self.logger.info("connecting model=\(self.model, privacy: .public) voice=\(self.voice, privacy: .public) keyLen=\(self.apiKey.count)")
            self.open()
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
            let pcm = Pcm.uplinkFrameToDownlink(frame)
            if !self.ready {
                self.frameLock.lock()
                if self.pendingFrames.count < Self.maxPending { self.pendingFrames.append(pcm) }
                self.frameLock.unlock()
                return
            }
            self.send(OpenAiWire.appendAudio(base64: Pcm.encodeBase64(pcm)))
        }
    }

    func sendText(_ text: String) {
        workQueue.async { [weak self] in
            guard let self else { return }
            let trimmed = text.trimmingCharacters(in: .whitespacesAndNewlines)
            if trimmed.isEmpty { return }
            if !self.ready {
                self.logger.info("text turn dropped, session not ready")
                return
            }
            self.send(OpenAiWire.userText(trimmed))
            self.send(OpenAiWire.createResponse())
        }
    }

    func endTurn() {
        workQueue.async { [weak self] in
            guard let self else { return }
            self.turnOpen = false
            if !self.ready {
                self.pendingEnd = true
                self.logger.info("endTurn parked until session.updated")
                return
            }
            self.send(OpenAiWire.commit())
            self.send(OpenAiWire.createResponse())
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
            if !self.ready { return }
            self.send(OpenAiWire.cancelResponse())
        }
    }

    func interrupt() {
        workQueue.async { [weak self] in
            guard let self else { return }
            self.turnOpen = false
            self.late.close()
            if self.ready { self.send(OpenAiWire.cancelResponse()) }
            self.emit(.interrupted)
        }
    }

    func close() {
        workQueue.async { [weak self] in
            guard let self else { return }
            self.closed = true
            self.ready = false
            self.turnOpen = false
            self.limitWorkItem?.cancel()
            self.limitWorkItem = nil
            self.stopPing()
            let current = self.socket
            self.socket = nil
            current?.cancel(with: .goingAway, reason: nil)
            self.session.invalidateAndCancel()
        }
    }

    private func open() {
        if closed { return }
        ready = false
        var request = URLRequest(url: URL(string: "\(OpenAiWire.endpoint)?model=\(model)")!)
        request.setValue("Bearer \(apiKey)", forHTTPHeaderField: "Authorization")
        let task = session.webSocketTask(with: request)
        socket = task
        task.resume()
        startPing()
        listen(task)
    }

    func handleSocketOpen(_ task: URLSessionWebSocketTask) {
        let code = (task.response as? HTTPURLResponse)?.statusCode ?? 0
        logger.info("open \(code)")
        send(OpenAiWire.sessionUpdate(model: model, voice: voice, instructions: instructions))
        scheduleLimit()
    }

    func handleSocketClose(_ task: URLSessionWebSocketTask, code: Int, reason: String) {
        guard socket === task else { return }
        ready = false
        socket = nil
        stopPing()
        logger.info("closed \(code) \(reason, privacy: .public)")
        if closed { return }
        emit(.failed(""))
    }

    private func classify(_ error: Error, response: HTTPURLResponse?) -> String {
        if let response {
            let code = response.statusCode
            if code == 401 || code == 403 { return ProviderFault.key }
            if code == 404 { return ProviderFault.config }
            if code == 429 { return ProviderFault.busy }
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
                case let .string(text):
                    self.receive(text)
                case let .data(data):
                    if let text = String(data: data, encoding: .utf8) { self.receive(text) }
                @unknown default:
                    break
                }
                if self.socket === task { self.listen(task) }
            case let .failure(error):
                guard self.socket === task else { return }
                self.ready = false
                self.socket = nil
                self.stopPing()
                self.logger.warning("socket failed: \(error.localizedDescription, privacy: .public)")
                if self.closed { return }
                self.emit(.failed(self.classify(error, response: task.response as? HTTPURLResponse)))
            }
        }
    }

    private func scheduleLimit() {
        limitWorkItem?.cancel()
        let item = DispatchWorkItem { [weak self] in
            guard let self, !self.closed else { return }
            self.logger.info("session limit \(Self.sessionMinutes)min reached")
            self.emit(.failed(""))
            self.close()
        }
        limitWorkItem = item
        workQueue.asyncAfter(
            deadline: .now() + .milliseconds(Self.sessionMinutes * Int(Self.minuteMs)),
            execute: item
        )
    }

    private func flushPending() {
        frameLock.lock()
        let buffered = pendingFrames
        pendingFrames.removeAll()
        frameLock.unlock()
        for pcm in buffered {
            send(OpenAiWire.appendAudio(base64: Pcm.encodeBase64(pcm)))
        }
        if pendingEnd {
            pendingEnd = false
            send(OpenAiWire.commit())
            send(OpenAiWire.createResponse())
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
        guard let parsed = OpenAiWire.parse(text) else { return }
        let tag = late.tag(responseId: parsed.responseId)
        guard let event = parsed.event else {
            if let responseId = parsed.responseId, tag != nil {
                late.bind(responseId: responseId)
            }
            return
        }
        switch event {
        case .ready:
            let first = !ready
            ready = true
            if first { flushPending() }
            if first { emit(.ready) }
        case let .audio(pcm, _):
            emit(.audio(pcm, tag))
        case let .assistantText(delta, _):
            emit(.assistantText(delta, tag))
        case let .userText(transcript, _):
            emit(.userText(transcript, tag))
        case .turnComplete:
            late.close()
            if parsed.cancelled || tag != nil { return }
            emit(.turnComplete)
        case .interrupted:
            emit(.interrupted)
        case let .failed(message):
            logger.warning("server error: \(message, privacy: .public)")
            emit(.failed(message))
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

private final class OpenAiWebSocketDelegate: NSObject, URLSessionWebSocketDelegate {
    weak var owner: OpenAiRealtimeSession?

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

extension OpenAiRealtimeSession {
    fileprivate func onWork(_ block: @escaping (OpenAiRealtimeSession) -> Void) {
        workQueue.async { [weak self] in
            guard let self else { return }
            block(self)
        }
    }
}