import Foundation

enum RealtimeEvent {
    case ready
    case userText(String, String?)
    case assistantText(String, String?)
    case audio(Data, String?)
    case interrupted
    case turnComplete
    case failed(String)
}

struct Capabilities {
    var canInterrupt: Bool
    var acceptsText: Bool
    var streamsTranscript: Bool
    var phonemeScores: Bool
    var maxSessionMinutes: Int?
}

protocol RealtimeSession: AnyObject, Sendable {
    var capabilities: Capabilities { get }
    func connect()
    func startTurn()
    func sendAudio(_ frame: [Int16])
    func sendText(_ text: String)
    func endTurn()
    func cancelTurn(_ turnId: String)
    func interrupt()
    func close()
}

enum Copy {
    static let fillLiveKey = "请填写语音模型 apikey"
    static let textUnsupported = "当前语音模型不支持打字"
    static let greeting = "用一句自然的英文开场，问我今天过得怎么样，不要解释你是模型。"
}

enum TextGate { case allowed, waiting, unsupported, unconfigured }

func textGate(connection: Connection, capabilities: Capabilities?) -> TextGate {
    switch connection {
    case .unconfigured: return .unconfigured
    case .connecting, .failed: return .waiting
    case .ready: return capabilities?.acceptsText == false ? .unsupported : .allowed
    }
}

enum ReconnectBackoff {
    static let baseMs: Int64 = 1000
    static let capMs: Int64 = 30_000
    static let jitterMs: Int64 = 300

    static func delayMs(attempt: Int, jitter: Double) -> Int64 {
        let exp = min(baseMs << min(attempt, 16), capMs)
        return exp + Int64(jitter * Double(jitterMs))
    }
}
