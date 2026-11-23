import Foundation
import os

struct AzureAssessor: SpeechAssessor {
    let requiresReferenceText = false
    let maxAudioMillis = 30_000

    private let subscriptionKey: String
    private let region: String
    private let session: URLSession

    init(subscriptionKey: String, region: String, session: URLSession = AzureAssessor.defaultSession) {
        self.subscriptionKey = subscriptionKey
        self.region = region
        self.session = session
    }

    func assess(pcm16k: Data, referenceText: String?, locale: String) async -> AssessOutcome {
        guard !subscriptionKey.isEmpty, !region.isEmpty else { return .failed(.key) }
        guard !pcm16k.isEmpty else { return .failed(.audio) }
        let url = URL(string: azureEndpoint(region: region, locale: locale))!
        var request = URLRequest(url: url)
        request.httpMethod = "POST"
        request.setValue(subscriptionKey, forHTTPHeaderField: "Ocp-Apim-Subscription-Key")
        request.setValue(azureAssessmentHeader(referenceText: referenceText), forHTTPHeaderField: "Pronunciation-Assessment")
        request.setValue("audio/wav; codecs=\"audio/pcm\"; samplerate=16000", forHTTPHeaderField: "Content-Type")
        request.httpBody = wavContainer(pcm: pcm16k.pcmSlice(maxMillis: maxAudioMillis))
        do {
            let (data, response) = try await session.data(for: request)
            let text = String(data: data, encoding: .utf8) ?? ""
            guard let http = response as? HTTPURLResponse, (200 ..< 300).contains(http.statusCode) else {
                if let http = response as? HTTPURLResponse {
                    Logger.azure.warning("assess \(http.statusCode): \(text.prefix(200), privacy: .public)")
                    return .failed(azureFailure(http.statusCode))
                }
                return .failed(.service)
            }
            return azureOutcome(text)
        } catch {
            Logger.azure.warning("assess failed: \(error.localizedDescription, privacy: .public)")
            return .failed(assessTransportFailure(error))
        }
    }

    static let defaultSession: URLSession = {
        let config = URLSessionConfiguration.ephemeral
        config.timeoutIntervalForRequest = 30
        return URLSession(configuration: config)
    }()
}

private enum Logger {
    static let azure = os.Logger(subsystem: "com.parley.speaker", category: "AzureAssessor")
}

/// HTTP 状态归成类：401 与 403 是密钥或资源授权，429 是限流，400 说明音频与请求本身评不了，
/// 其余状态（含 5xx）算服务异常。响应体不进界面的任何一格。
func azureFailure(_ status: Int) -> AssessFailure {
    switch status {
    case 401, 403: return .key
    case 429: return .busy
    case 400, 413: return .audio
    default: return .service
    }
}

/// 静音、纯噪声与认不出的发音属于音频这一类，其余非 Success 是服务侧的问题。
func azureOutcome(_ body: String) -> AssessOutcome {
    let status = jsonOf(body).flatMap { stringField($0, "RecognitionStatus") }
    switch status {
    case "InitialSilenceTimeout", "BabbleTimeout", "NoMatch":
        return .failed(.audio)
    case "Success", nil:
        break
    default:
        return .failed(.service)
    }
    guard let result = parseAzureResult(body) else { return .failed(.service) }
    guard result.total != nil || result.accuracy != nil else { return .failed(.service) }
    return .scored(result)
}

func azureEndpoint(region: String, locale: String) -> String {
    "https://\(region).stt.speech.microsoft.com/speech/recognition/conversation/cognitiveservices/v1?language=\(locale)"
}

func azureAssessmentHeader(referenceText: String?) -> String {
    var params: [String: Any] = [
        "GradingSystem": "HundredMark",
        "Granularity": "Phoneme",
        "Dimension": "Comprehensive",
        "EnableProsodyAssessment": true,
    ]
    if let referenceText, !referenceText.isEmpty { params["ReferenceText"] = referenceText }
    let data = try! JSONSerialization.data(withJSONObject: params)
    return data.base64EncodedString()
}

func wavContainer(pcm: Data, sampleRate: Int = 16_000) -> Data {
    let bitsPerSample = 16
    let channels = 1
    let blockAlign = channels * bitsPerSample / 8
    var header = Data()
    header.append(contentsOf: "RIFF".utf8)
    header.append(contentsOf: withUnsafeBytes(of: UInt32(36 + pcm.count).littleEndian) { Data($0) })
    header.append(contentsOf: "WAVE".utf8)
    header.append(contentsOf: "fmt ".utf8)
    header.append(contentsOf: withUnsafeBytes(of: UInt32(16).littleEndian) { Data($0) })
    header.append(contentsOf: withUnsafeBytes(of: UInt16(1).littleEndian) { Data($0) })
    header.append(contentsOf: withUnsafeBytes(of: UInt16(channels).littleEndian) { Data($0) })
    header.append(contentsOf: withUnsafeBytes(of: UInt32(sampleRate).littleEndian) { Data($0) })
    header.append(contentsOf: withUnsafeBytes(of: UInt32(sampleRate * blockAlign).littleEndian) { Data($0) })
    header.append(contentsOf: withUnsafeBytes(of: UInt16(blockAlign).littleEndian) { Data($0) })
    header.append(contentsOf: withUnsafeBytes(of: UInt16(bitsPerSample).littleEndian) { Data($0) })
    header.append(contentsOf: "data".utf8)
    header.append(contentsOf: withUnsafeBytes(of: UInt32(pcm.count).littleEndian) { Data($0) })
    return header + pcm
}

func parseAzureResult(_ raw: String) -> AssessResult? {
    guard let root = jsonOf(raw) else { return nil }
    if let status = stringField(root, "RecognitionStatus"), status != "Success" { return nil }
    guard let best = array(root, "NBest")?.first else { return nil }
    let assessment = obj(best, "PronunciationAssessment")
    let issues = (array(best, "Words") ?? []).compactMap { word -> PhonemeIssue? in
        guard let wordAssessment = obj(word, "PronunciationAssessment"),
              let errorType = stringField(wordAssessment, "ErrorType"), errorType != "None"
        else { return nil }
        return PhonemeIssue(
            word: stringField(word, "Word") ?? "",
            phonetic: ipaOf(word),
            note: errorLabel(errorType)
        )
    }
    return AssessResult(
        accuracy: doubleField(assessment ?? [:], "AccuracyScore"),
        fluency: doubleField(assessment ?? [:], "FluencyScore"),
        prosody: doubleField(assessment ?? [:], "ProsodyScore"),
        completeness: doubleField(assessment ?? [:], "CompletenessScore"),
        total: doubleField(assessment ?? [:], "PronScore"),
        issues: issues
    )
}

private func ipaOf(_ word: [String: Any]) -> String {
    let phonemes = (array(word, "Phonemes") ?? []).compactMap { stringField($0, "Phoneme") }
    if phonemes.isEmpty { return "" }
    return "/" + phonemes.joined(separator: " ") + "/"
}

private func errorLabel(_ errorType: String) -> String {
    switch errorType {
    case "Mispronunciation": "误读"
    case "UnexpectedBreak": "多停"
    case "MissingBreak": "少停"
    case "Monotone": "平调"
    default: errorType
    }
}