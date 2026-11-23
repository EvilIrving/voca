import CryptoKit
import Foundation
import os

struct XfyunAssessor: SpeechAssessor {
    let requiresReferenceText = true
    let maxAudioMillis = 5 * 60 * 1000

    private let credentials: XfyunCredentials
    private let session: URLSession

    init(credentials: XfyunCredentials, session: URLSession = URLSession(configuration: .default)) {
        self.credentials = credentials
        self.session = session
    }

    func assess(pcm16k: Data, referenceText: String?, locale: String) async -> AssessOutcome {
        let text = referenceText?.trimmingCharacters(in: .whitespacesAndNewlines) ?? ""
        guard credentials.isComplete else { return .failed(.key) }
        guard !text.isEmpty, !pcm16k.isEmpty else { return .failed(.audio) }
        let date = xfyunDate()
        guard let url = URL(string: xfyunUrl(credentials: credentials, date: date)) else {
            return .failed(.service)
        }
        let task = session.webSocketTask(with: url)
        task.resume()
        defer { task.cancel(with: .goingAway, reason: nil) }
        do {
            if let first = jsonString(xfyunFirstFrame(appId: credentials.appId, text: text)) {
                try await task.send(.string(first))
            }
            try await sendAudio(task: task, pcm: pcm16k.pcmSlice(maxMillis: maxAudioMillis))
            let deadline = Date().addingTimeInterval(360)
            return try await waitForResult(task: task, deadline: deadline)
        } catch {
            Logger.xfyun.warning("assess failed: \(error.localizedDescription, privacy: .public)")
            return .failed(assessTransportFailure(error))
        }
    }

    private func sendAudio(task: URLSessionWebSocketTask, pcm: Data) async throws {
        for frame in xfyunAudioFrames(pcm: pcm) {
            if let payload = jsonString(xfyunAudioFrame(frame)) {
                try await task.send(.string(payload))
            }
            if frame.status == xfyunStatusMiddle {
                try await Task.sleep(for: .milliseconds(40))
            }
        }
    }

    /// 服务端每一次回话都收敛成一条结局：带错误码的归成类别，末帧带结果就解析，超时算服务异常。
    private func waitForResult(task: URLSessionWebSocketTask, deadline: Date) async throws -> AssessOutcome {
        while Date() < deadline {
            let message = try await task.receive()
            switch message {
            case .string(let text):
                let code = jsonOf(text).flatMap { intField($0, "code") }
                if let code, code != 0 {
                    Logger.xfyun.warning("ise \(code) \(jsonOf(text).flatMap { stringField($0, "message") }?.prefix(120) ?? "", privacy: .public)")
                    return .failed(xfyunFailure(code))
                }
                if frameStatus(text) == xfyunStatusLast { return xfyunOutcome(text) }
            case .data:
                continue
            @unknown default:
                continue
            }
        }
        return .failed(.service)
    }
}

private enum Logger {
    static let xfyun = os.Logger(subsystem: "com.parley.speaker", category: "XfyunAssessor")
}

private let xfyunHost = "ise-api.xfyun.cn"
private let xfyunPath = "/v2/open-ise"
private let xfyunStatusFirst = 0
private let xfyunStatusMiddle = 1
private let xfyunStatusLast = 2
private let xfyunFrameBytes = 1280
private let xfyunAusFirst = 1
private let xfyunAusMiddle = 2
private let xfyunAusLast = 4

private let xfyunDateFormatter: DateFormatter = {
    let f = DateFormatter()
    f.locale = Locale(identifier: "en_US_POSIX")
    f.timeZone = TimeZone(secondsFromGMT: 0)
    f.dateFormat = "EEE, dd MMM yyyy HH:mm:ss 'GMT'"
    return f
}()

func xfyunDate(now: Date = Date()) -> String {
    xfyunDateFormatter.string(from: now)
}

func xfyunSignature(apiSecret: String, date: String) -> String {
    let origin = "host: \(xfyunHost)\ndate: \(date)\nGET \(xfyunPath) HTTP/1.1"
    let key = SymmetricKey(data: Data(apiSecret.utf8))
    let mac = HMAC<SHA256>.authenticationCode(for: Data(origin.utf8), using: key)
    return Data(mac).base64EncodedString()
}

func xfyunAuthorization(apiKey: String, signature: String) -> String {
    let origin = "api_key=\"\(apiKey)\", algorithm=\"hmac-sha256\", headers=\"host date request-line\", signature=\"\(signature)\""
    return Data(origin.utf8).base64EncodedString()
}

func xfyunUrl(credentials: XfyunCredentials, date: String) -> String {
    let authorization = xfyunAuthorization(
        apiKey: credentials.apiKey,
        signature: xfyunSignature(apiSecret: credentials.apiSecret, date: date)
    )
    var components = URLComponents()
    components.scheme = "wss"
    components.host = xfyunHost
    components.path = xfyunPath
    components.queryItems = [
        URLQueryItem(name: "authorization", value: authorization),
        URLQueryItem(name: "date", value: date),
        URLQueryItem(name: "host", value: xfyunHost),
    ]
    return components.url!.absoluteString
}

func xfyunFirstFrame(appId: String, text: String) -> [String: Any] {
    [
        "common": ["app_id": appId],
        "business": [
            "sub": "ise",
            "ent": "en_vip",
            "category": "read_sentence",
            "cmd": "ssb",
            "auf": "audio/L16;rate=16000",
            "aue": "raw",
            "tte": "utf-8",
            "rstcd": "utf8",
            "text": "\u{FEFF}[content]\n\(text)",
            "ttp_skip": true,
            "ise_unite": "1",
            "rst": "entirety",
            "extra_ability": "syll_phone_err_msg;multi_dimension",
        ],
        "data": ["status": xfyunStatusFirst],
    ]
}

struct XfyunAudioFrame {
    let aus: Int
    let status: Int
    let chunk: Data
}

func xfyunAudioFrames(pcm: Data, frameBytes: Int = xfyunFrameBytes) -> [XfyunAudioFrame] {
    guard !pcm.isEmpty else { return [] }
    var frames: [XfyunAudioFrame] = []
    var offset = 0
    while offset < pcm.count {
        let end = min(offset + frameBytes, pcm.count)
        frames.append(XfyunAudioFrame(
            aus: frames.isEmpty ? xfyunAusFirst : xfyunAusMiddle,
            status: xfyunStatusMiddle,
            chunk: pcm.subdata(in: offset ..< end)
        ))
        offset = end
    }
    frames.append(XfyunAudioFrame(aus: xfyunAusLast, status: xfyunStatusLast, chunk: Data()))
    return frames
}

func xfyunAudioFrame(_ frame: XfyunAudioFrame) -> [String: Any] {
    [
        "business": ["cmd": "auw", "aus": frame.aus, "aue": "raw"],
        "data": ["status": frame.status, "data": frame.chunk.base64EncodedString()],
    ]
}

/// 错误码归类照官方错误码表：密钥与授权类、额度类、音频类、读取超时各有归属，
/// 认不出的码一律算服务异常，界面只说一句话，码与原文进日志。
func xfyunFailure(_ code: Int) -> AssessFailure {
    switch code {
    case 10313, 11200: return .key
    case 11201, 42306: return .quota
    case 60114, 68676, 40037, 40038, 40007, 10043, 10114: return .audio
    case 10200: return .offline
    default: return .service
    }
}

/// 解析出四项分才算出了结果，报文能读但没有分说明服务端这一轮没评出来。
func xfyunOutcome(_ body: String) -> AssessOutcome {
    guard let result = parseXfyunResult(body) else { return .failed(.service) }
    guard result.total != nil || result.accuracy != nil else { return .failed(.service) }
    return .scored(result)
}

func parseXfyunResult(_ raw: String) -> AssessResult? {
    guard let root = jsonOf(raw) else { return nil }
    if let code = intField(root, "code"), code != 0 { return nil }
    guard let body = obj(root, "data"), let encoded = stringField(body, "data"),
          let xml = Data(base64Encoded: encoded) else { return nil }

    let reader = XfyunResultReader()
    let parser = XMLParser(data: xml)
    parser.delegate = reader
    guard parser.parse(), let scores = reader.scores else { return nil }

    return AssessResult(
        accuracy: scores.accuracy,
        fluency: scores.fluency,
        prosody: nil,
        completeness: scores.integrity,
        total: scores.total ?? weightedTotal(
            accuracy: scores.accuracy,
            fluency: scores.fluency,
            standard: scores.standard,
            integrity: scores.integrity
        ),
        issues: reader.issues
    )
}

private func weightedTotal(accuracy: Double?, fluency: Double?, standard: Double?, integrity: Double?) -> Double? {
    guard let accuracy, let fluency, let standard else { return nil }
    let coefficient = (integrity ?? 100) / 100
    return (accuracy * 0.6 + fluency * 0.3 + standard * 0.1) * coefficient
}

/// 末帧的 data.data 是 base64 包的 XML，分数与偏误都挂在属性上，按元素名逐层收。
/// 英文只有音节这一层给 serr_msg，音素那层的 dp_message 主要给中文用，所以偏误按音节归集。
private final class XfyunResultReader: NSObject, XMLParserDelegate {
    struct Scores {
        var accuracy: Double?
        var fluency: Double?
        var standard: Double?
        var integrity: Double?
        var total: Double?
    }

    private(set) var scores: Scores?
    private(set) var issues: [PhonemeIssue] = []
    private var word = ""

    func parser(
        _ parser: XMLParser,
        didStartElement elementName: String,
        namespaceURI: String?,
        qualifiedName qName: String?,
        attributes attributeDict: [String: String] = [:]
    ) {
        if scores == nil,
           ["read_word", "read_sentence", "read_chapter", "read_syllable"].contains(elementName),
           attributeDict["accuracy_score"] != nil || attributeDict["total_score"] != nil {
            scores = Scores(
                accuracy: attributeDict["accuracy_score"].flatMap { Double($0) },
                fluency: attributeDict["fluency_score"].flatMap { Double($0) },
                standard: attributeDict["standard_score"].flatMap { Double($0) },
                integrity: attributeDict["integrity_score"].flatMap { Double($0) },
                total: attributeDict["total_score"].flatMap { Double($0) }
            )
            return
        }
        if elementName == "word" {
            word = attributeDict["content"] ?? ""
            return
        }
        guard elementName == "syll" else { return }
        let dp = Int(attributeDict["dp_message"] ?? "0") ?? 0
        let serr = Int(attributeDict["serr_msg"] ?? "0") ?? 0
        guard dp != 0 || serr != 0 else { return }
        issues.append(PhonemeIssue(
            word: word,
            phonetic: "/\(attributeDict["content"] ?? "")/",
            note: xfyunNote(dp: dp, serr: serr)
        ))
    }
}

private func xfyunNote(dp: Int, serr: Int) -> String {
    switch dp {
    case 16: return "漏读"
    case 32: return "增读"
    case 64: return "回读"
    case 128: return "替换"
    default: return serr != 0 ? "误读" : "音素偏误"
    }
}

private func frameStatus(_ raw: String) -> Int? {
    jsonOf(raw).flatMap { obj($0, "data") }.flatMap { intField($0, "status") }
}

private func jsonString(_ object: [String: Any]) -> String? {
    guard let data = try? JSONSerialization.data(withJSONObject: object) else { return nil }
    return String(data: data, encoding: .utf8)
}
