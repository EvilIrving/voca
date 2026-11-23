import Foundation

protocol SpeechAssessor: Sendable {
    var requiresReferenceText: Bool { get }
    var maxAudioMillis: Int { get }
    func assess(pcm16k: Data, referenceText: String?, locale: String) async -> AssessOutcome
}

enum AssessOutcome: Equatable {
    case scored(AssessResult)
    case failed(AssessFailure)
}

/// 评测拿不到分的归类，每一类带界面那一句。
/// 密钥、额度、限流三类说法与实时链路共用，网络、音频、服务三类是评测独有。
/// 传输层抛出来的错误归成类别：服务端拒绝握手、认证、权限都算密钥，其余算连接没通。
/// URLSession 不把握手被拒的 HTTP 状态交出来，只给 badServerResponse，这个评测端点拒绝升级的原因就是签名与密钥。
func assessTransportFailure(_ error: Error) -> AssessFailure {
    guard let urlError = error as? URLError else { return .service }
    switch urlError.code {
    case .userAuthenticationRequired, .noPermissionsToReadFile,
         .appTransportSecurityRequiresSecureConnection, .badServerResponse:
        return .key
    default:
        return .offline
    }
}

enum AssessFailure: Equatable {
    case key
    case quota
    case busy
    case offline
    case audio
    case service

    var notice: String {
        switch self {
        case .key: ProviderFault.key
        case .quota: ProviderFault.quota
        case .busy: ProviderFault.busy
        case .offline: "评测服务未连通，请检查网络"
        case .audio: "评测失败，请重录"
        case .service: "评测服务异常，请稍后重试"
        }
    }
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

    var isComplete: Bool { !appId.isEmpty && !apiKey.isEmpty && !apiSecret.isEmpty }
}