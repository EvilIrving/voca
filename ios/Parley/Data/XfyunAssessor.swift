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

    func assess(pcm16k: Data, referenceText: String?, locale: String) async -> AssessResult? {
        let text = referenceText?.trimmingCharacters(in: .whitespacesAndNewlines) ?? ""
        guard credentials.isComplete, !text.isEmpty, !pcm16k.isEmpty else { return nil }
        let date = xfyunDate()
        guard let url = URL(string: xfyunUrl(credentials: credentials, date: date)) else { return nil }
        let task = session.webSocketTask(with: url)
        task.resume()
        defer { task.cancel(with: .goingAway, reason: nil) }
        do {
            if let first = jsonString(xfyunFirstFrame(text: text)) {
                try await task.send(.string(first))
            }
            try await sendAudio(task: task, pcm: pcm16k.pcmSlice(maxMillis: maxAudioMillis))
            let deadline = Date().addingTimeInterval(360)
            let body = try await waitForResult(task: task, deadline: deadline)
            guard let body else { return nil }
            return parseXfyunResult(body)
        } catch {
            Logger.xfyun.warning("assess failed: \(error.localizedDescription, privacy: .public)")
            return nil
        }
    }

    private func sendAudio(task: URLSessionWebSocketTask, pcm: Data) async throws {
        for (status, chunk) in xfyunAudioChunks(pcm: pcm) {
            if let payload = jsonString(xfyunAudioFrame(chunk: chunk, status: status)) {
                try await task.send(.string(payload))
            }
            if status == xfyunStatusMiddle {
                try await Task.sleep(for: .milliseconds(40))
            }
        }
    }

    private func waitForResult(task: URLSessionWebSocketTask, deadline: Date) async throws -> String? {
        while Date() < deadline {
            let message = try await task.receive()
            switch message {
            case .string(let text):
                let code = jsonOf(text).flatMap { intField($0, "code") }
                if let code, code != 0 {
                    Logger.xfyun.warning("ise \(code) \(jsonOf(text).flatMap { stringField($0, "message") }?.prefix(120) ?? "", privacy: .public)")
                    return nil
                }
                if frameStatus(text) == xfyunStatusLast { return text }
            case .data:
                continue
            @unknown default:
                continue
            }
        }
        return nil
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

func xfyunFirstFrame(text: String) -> [String: Any] {
    [
        "common": ["cmd": "ssb"],
        "business": [
            "sub": "ise",
            "ent": "en_vip",
            "category": "read_sentence",
            "cmd": "ssb",
            "auf": "audio/L16;rate=16000",
            "aue": "raw",
            "aus": 1,
            "text": "\u{FEFF}\(text)",
            "ttp_skip": true,
            "extra_ability": "syll_phone_err_msg",
        ],
        "data": ["status": xfyunStatusFirst, "data": ""],
    ]
}

func xfyunAudioChunks(pcm: Data, frameBytes: Int = xfyunFrameBytes) -> [(Int, Data)] {
    guard !pcm.isEmpty else { return [] }
    var out: [(Int, Data)] = []
    var offset = 0
    while offset < pcm.count {
        let end = min(offset + frameBytes, pcm.count)
        let status = end == pcm.count ? xfyunStatusLast : xfyunStatusMiddle
        out.append((status, pcm.subdata(in: offset ..< end)))
        offset = end
    }
    return out
}

func xfyunAudioFrame(chunk: Data, status: Int) -> [String: Any] {
    [
        "business": ["cmd": "auw", "aus": 1, "aue": "raw"],
        "data": ["status": status, "data": chunk.base64EncodedString()],
    ]
}

func parseXfyunResult(_ raw: String) -> AssessResult? {
    guard let root = jsonOf(raw) else { return nil }
    if let code = intField(root, "code"), code != 0 { return nil }
    guard let outer = obj(root, "data") else { return nil }
    let inner: [String: Any]
    if let nested = obj(outer, "data") {
        inner = nested
    } else if let encoded = stringField(outer, "data"), let decoded = decodeBase64Json(encoded) {
        inner = decoded
    } else {
        inner = outer
    }
    let sentence = obj(inner, "read_sentence") ?? inner
    let accuracy = doubleField(sentence, "accuracy_score")
    let fluency = doubleField(sentence, "fluency_score")
    let standard = doubleField(sentence, "standard_score")
    let integrity = doubleField(sentence, "integrity_score")
    return AssessResult(
        accuracy: accuracy,
        fluency: fluency,
        prosody: nil,
        completeness: integrity,
        total: doubleField(sentence, "total_score") ?? weightedTotal(accuracy: accuracy, fluency: fluency, standard: standard, integrity: integrity),
        issues: issuesOf(sentence)
    )
}

private func weightedTotal(accuracy: Double?, fluency: Double?, standard: Double?, integrity: Double?) -> Double? {
    guard let accuracy, let fluency, let standard else { return nil }
    let coefficient = (integrity ?? 100) / 100
    return (accuracy * 0.6 + fluency * 0.3 + standard * 0.1) * coefficient
}

private func issuesOf(_ sentence: [String: Any]) -> [PhonemeIssue] {
    (array(sentence, "word") ?? []).flatMap { word -> [PhonemeIssue] in
        let wordText = stringField(word, "content") ?? ""
        return (array(word, "phone") ?? []).compactMap { phone in
            guard (intField(phone, "dp_message") ?? 0) != 0 else { return nil }
            return PhonemeIssue(
                word: wordText,
                phonetic: "/\(stringField(phone, "content") ?? "")/",
                note: "音素偏误"
            )
        }
    }
}

private func frameStatus(_ raw: String) -> Int? {
    jsonOf(raw).flatMap { obj($0, "data") }.flatMap { intField($0, "status") }
}

private func decodeBase64Json(_ encoded: String) -> [String: Any]? {
    guard let data = Data(base64Encoded: encoded) else { return nil }
    return try? JSONSerialization.jsonObject(with: data) as? [String: Any]
}

private func jsonString(_ object: [String: Any]) -> String? {
    guard let data = try? JSONSerialization.data(withJSONObject: object) else { return nil }
    return String(data: data, encoding: .utf8)
}
