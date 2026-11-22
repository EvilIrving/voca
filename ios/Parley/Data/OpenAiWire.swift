import Foundation
import os

enum OpenAiWire {
    static let endpoint = "wss://api.openai.com/v1/realtime"
    static let pcmRate = 24_000
    static let transcriptionModel = "gpt-4o-mini-transcribe"

    private static let logger = Logger(subsystem: "com.parley.speaker", category: "OpenAiRealtime")

    struct Parsed {
        let event: RealtimeEvent?
        let responseId: String?
        let cancelled: Bool

        init(event: RealtimeEvent?, responseId: String? = nil, cancelled: Bool = false) {
            self.event = event
            self.responseId = responseId
            self.cancelled = cancelled
        }
    }

    static func sessionUpdate(model: String, voice: String, instructions: String) -> [String: Any] {
        [
            "type": "session.update",
            "session": [
                "type": "realtime",
                "model": model,
                "instructions": instructions,
                "output_modalities": ["audio"],
                "audio": [
                    "input": [
                        "format": [
                            "type": "audio/pcm",
                            "rate": pcmRate,
                        ],
                        "turn_detection": NSNull(),
                        "transcription": ["model": transcriptionModel],
                    ],
                    "output": [
                        "format": [
                            "type": "audio/pcm",
                            "rate": pcmRate,
                        ],
                        "voice": voice,
                    ],
                ],
            ],
        ]
    }

    static func appendAudio(base64: String) -> [String: Any] {
        ["type": "input_audio_buffer.append", "audio": base64]
    }

    static func commit() -> [String: Any] {
        ["type": "input_audio_buffer.commit"]
    }

    static func createResponse() -> [String: Any] {
        ["type": "response.create"]
    }

    static func cancelResponse() -> [String: Any] {
        ["type": "response.cancel"]
    }

    static func userText(_ text: String) -> [String: Any] {
        [
            "type": "conversation.item.create",
            "item": [
                "type": "message",
                "role": "user",
                "content": [
                    ["type": "input_text", "text": text],
                ],
            ],
        ]
    }

    static func parse(_ text: String) -> Parsed? {
        guard
            let data = text.data(using: .utf8),
            let root = (try? JSONSerialization.jsonObject(with: data)) as? [String: Any]
        else { return nil }
        guard let type = jsonString(root["type"]) else { return nil }
        let response = root["response"] as? [String: Any]
        let responseId = jsonString(root["response_id"]) ?? jsonString(response?["id"])

        switch type {
        case "session.created", "session.updated":
            return Parsed(event: .ready)

        case "response.created":
            return Parsed(event: nil, responseId: responseId)

        case "response.audio.delta", "response.output_audio.delta":
            guard let delta = jsonString(root["delta"]) else { return nil }
            let pcm: Data
            do {
                pcm = try Pcm.decodeBase64(delta)
            } catch {
                return nil
            }
            return Parsed(event: .audio(pcm, nil), responseId: responseId)

        case "response.audio_transcript.delta", "response.output_audio_transcript.delta":
            guard let delta = jsonString(root["delta"]) else { return nil }
            return Parsed(event: .assistantText(delta, nil), responseId: responseId)

        case "conversation.item.input_audio_transcription.completed":
            guard let transcript = jsonString(root["transcript"]) else { return nil }
            return Parsed(event: .userText(transcript, nil))

        case "conversation.item.input_audio_transcription.failed":
            return Parsed(event: .failed("没有听清，请重说"))

        case "response.done":
            let status = jsonString(response?["status"])
            return Parsed(event: .turnComplete, responseId: responseId, cancelled: status == "cancelled")

        case "error":
            let error = root["error"] as? [String: Any]
            let message = jsonString(error?["message"]) ?? "服务端返回错误"
            let code = jsonString(error?["code"])
            let errType = jsonString(error?["type"])
            if message.contains("no active response") {
                return nil
            }
            logger.warning("server error \(code ?? "", privacy: .public) \(errType ?? "", privacy: .public) \(message.prefix(200), privacy: .public)")
            return Parsed(event: .failed(ProviderFault.notice(code, errType, message)))

        default:
            return nil
        }
    }

    private static func jsonString(_ value: Any?) -> String? {
        if let s = value as? String { return s }
        if let n = value as? NSNumber { return n.stringValue }
        return nil
    }
}