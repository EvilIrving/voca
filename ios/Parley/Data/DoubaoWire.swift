import Foundation
import os

enum DoubaoEvents {
    static let startConnection = 1
    static let finishConnection = 2
    static let startSession = 5
    static let finishSession = 6
    static let taskRequest = 100

    static let connectionStarted = 50
    static let connectionFailed = 51
    static let connectionFinished = 52
    static let sessionStarted = 150
    static let sessionFinished = 152
    static let sessionFailed = 153
    static let sayHello = 300
    static let chatResponse = 350
    static let chatEnded = 359
    static let asrInfo = 450
    static let asrResponse = 451
    static let asrEnded = 459
    static let ttsSentenceStart = 550
    static let ttsSentenceEnd = 551
    static let ttsResponse = 552
    static let ttsEnded = 559

    static let connectionLevel: Set<Int> = [
        startConnection,
        finishConnection,
        connectionStarted,
        connectionFailed,
        connectionFinished,
    ]
}

enum DoubaoInbound {
    case connectionStarted
    case sessionStarted
    case sessionFinished
    case userText(String)
    case assistantText(String)
    case audio(Data)
    case turnComplete
    case failed(String)
    case ignored
}

extension DoubaoInbound {
    func event() -> RealtimeEvent? {
        switch self {
        case .connectionStarted, .ignored, .sessionFinished:
            return nil
        case .sessionStarted:
            return .ready
        case .turnComplete:
            return .turnComplete
        case let .userText(text):
            return .userText(text, nil)
        case let .assistantText(text):
            return .assistantText(text, nil)
        case let .audio(pcm):
            return .audio(pcm, nil)
        case let .failed(message):
            return .failed(message)
        }
    }
}

enum DoubaoWire {
    static let endpoint = "wss://openspeech.bytedance.com/api/v3/realtime/dialogue"
    static let resourceId = "volc.speech.dialog"

    static let uplinkRate = 16_000
    static let downlinkRate = 24_000
    static let downlinkBits = 16

    static let botName = "Parley"

    private static let logger = Logger(subsystem: "com.parley.speaker", category: "DoubaoWire")
    private static let asrEndWindowMs = 1_500

    static func startConnection() -> Data {
        DoubaoFrameCodec.request(
            messageType: DoubaoProto.msgFullClient,
            event: DoubaoEvents.startConnection,
            sessionId: "",
            payload: Data("{}".utf8)
        )
    }

    static func finishConnection() -> Data {
        DoubaoFrameCodec.request(
            messageType: DoubaoProto.msgFullClient,
            event: DoubaoEvents.finishConnection,
            sessionId: "",
            payload: Data("{}".utf8)
        )
    }

    static func startSession(
        sessionId: String,
        speaker: String,
        systemPrompt: String,
        pushToTalk: Bool
    ) -> Data {
        var dialog: [String: Any] = ["bot_name": botName]
        if !systemPrompt.isEmpty { dialog["system_role"] = systemPrompt }
        if pushToTalk {
            dialog["input_mod"] = "push_to_talk"
            dialog["enable_custom_vad"] = true
        }
        let json: [String: Any] = [
            "asr": ["extra": ["end_smooth_window_ms": asrEndWindowMs]],
            "tts": [
                "speaker": speaker,
                "audio_config": [
                    "channel": 1,
                    "format": "pcm",
                    "sample_rate": downlinkRate,
                    "bit_width": downlinkBits,
                ],
                "speech_rate": 0,
            ],
            "dialog": dialog,
        ]
        let payload = (try? JSONSerialization.data(withJSONObject: json)) ?? Data("{}".utf8)
        return DoubaoFrameCodec.request(
            messageType: DoubaoProto.msgFullClient,
            event: DoubaoEvents.startSession,
            sessionId: sessionId,
            payload: payload
        )
    }

    static func audio(sessionId: String, pcm: Data) -> Data {
        DoubaoFrameCodec.audio(event: DoubaoEvents.taskRequest, sessionId: sessionId, pcm: pcm)
    }

    static func turnEnd(sessionId: String) -> Data {
        let payload = Data("{\"audio_end\":true}".utf8)
        return DoubaoFrameCodec.request(
            messageType: DoubaoProto.msgFullClient,
            event: DoubaoEvents.taskRequest,
            sessionId: sessionId,
            payload: payload
        )
    }

    static func finishSession(sessionId: String) -> Data {
        DoubaoFrameCodec.request(
            messageType: DoubaoProto.msgFullClient,
            event: DoubaoEvents.finishSession,
            sessionId: sessionId,
            payload: Data("{}".utf8)
        )
    }

    static func parse(_ frame: DoubaoFrame) -> DoubaoInbound {
        if frame.messageType == DoubaoProto.msgError {
            let said = String(data: frame.payload, encoding: .utf8)?.trimmingCharacters(in: .whitespacesAndNewlines) ?? ""
            return .failed(faultOf(frame.errorCode.map { String($0) }, said: said.isEmpty ? nil : said))
        }
        if frame.messageType == DoubaoProto.msgAudioServer, frame.serialization == DoubaoProto.serialRaw {
            return frame.payload.isEmpty ? .ignored : .audio(frame.payload)
        }
        switch frame.event {
        case DoubaoEvents.connectionStarted:
            return .connectionStarted
        case DoubaoEvents.connectionFailed:
            return .failed(faultOf(nil, said: messageOf(frame)))
        case DoubaoEvents.connectionFinished:
            return .ignored
        case DoubaoEvents.sessionStarted:
            return .sessionStarted
        case DoubaoEvents.sessionFinished:
            return .sessionFinished
        case DoubaoEvents.sessionFailed:
            return .failed(faultOf(nil, said: messageOf(frame)))
        case DoubaoEvents.chatResponse:
            if let text = textOf(frame) { return .assistantText(text) }
            return .ignored
        case DoubaoEvents.chatEnded:
            return .turnComplete
        case DoubaoEvents.asrResponse:
            if let text = textOf(frame) { return .userText(text) }
            return .ignored
        case DoubaoEvents.sayHello,
             DoubaoEvents.asrInfo,
             DoubaoEvents.asrEnded,
             DoubaoEvents.ttsSentenceStart,
             DoubaoEvents.ttsSentenceEnd,
             DoubaoEvents.ttsResponse,
             DoubaoEvents.ttsEnded:
            return .ignored
        default:
            return .ignored
        }
    }

    static func sessionIdOf(_ frame: DoubaoFrame) -> String? {
        if let root = payloadOf(frame) {
            if let fromPayload = jsonString(root["session_id"]) ?? jsonString(root["sessionId"]),
               !fromPayload.isEmpty
            {
                return fromPayload
            }
        }
        return frame.sessionId.isEmpty ? nil : frame.sessionId
    }

    private static func textOf(_ frame: DoubaoFrame) -> String? {
        guard let root = payloadOf(frame) else { return nil }
        if let results = root["results"] as? [[String: Any]], let first = results.first {
            if let text = jsonString(first["text"]) { return text }
        }
        if let results = root["results"] as? [Any], let first = results.first as? [String: Any] {
            if let text = jsonString(first["text"]) { return text }
        }
        return jsonString(root["content"]) ?? jsonString(root["text"])
    }

    private static func faultOf(_ code: String?, said: String?) -> String {
        logger.warning("server said \(code ?? "nil", privacy: .public) \(said?.prefix(200) ?? "", privacy: .public)")
        return ProviderFault.notice(code, said)
    }

    private static func messageOf(_ frame: DoubaoFrame) -> String? {
        guard let root = payloadOf(frame) else { return nil }
        return jsonString(root["error"]) ?? jsonString(root["message"]) ?? jsonString(root["status"])
    }

    private static func payloadOf(_ frame: DoubaoFrame) -> [String: Any]? {
        guard !frame.payload.isEmpty else { return nil }
        return (try? JSONSerialization.jsonObject(with: frame.payload)) as? [String: Any]
    }

    private static func jsonString(_ value: Any?) -> String? {
        if let s = value as? String { return s }
        if let n = value as? NSNumber { return n.stringValue }
        return nil
    }
}