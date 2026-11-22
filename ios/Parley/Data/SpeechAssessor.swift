import Foundation

protocol SpeechAssessor: Sendable {
    var requiresReferenceText: Bool { get }
    var maxAudioMillis: Int { get }
    func assess(pcm16k: Data, referenceText: String?, locale: String) async -> AssessResult?
}

let pcmBytesPerMilli = 32

extension Data {
    func pcmSlice(maxMillis: Int) -> Data {
        let limit = pcmBytesPerMilli * maxMillis
        if count <= limit { return self }
        return prefix(limit)
    }
}

struct AssessResult: Equatable {
    var accuracy: Double?
    var fluency: Double?
    var prosody: Double?
    var completeness: Double?
    var total: Double?
    var issues: [PhonemeIssue] = []
}

struct XfyunCredentials: Equatable {
    var appId: String = ""
    var apiKey: String = ""
    var apiSecret: String = ""

    var isComplete: Bool { !apiKey.isEmpty && !apiSecret.isEmpty }
}