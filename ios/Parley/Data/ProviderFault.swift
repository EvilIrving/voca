import Foundation
import os

enum ProviderFault {
    static let quota = "额度已用完，请充值"
    static let busy = "请求太频繁，请稍后重试"
    static let key = "apikey 无效，请在设置中检查"
    static let region = "当前地区不可用，请检查网络"
    static let config = "模型或音色不可用，请在设置中更换"

    private static let rules: [(String, [String])] = [
        (quota, [
            "spending cap", "quota", "resource_exhausted", "insufficient", "billing",
            "credit", "balance", "payment", "额度", "余额", "欠费",
        ]),
        (busy, ["rate limit", "ratelimit", "too many requests", "concurrency", "429", "并发", "限流"]),
        (key, [
            "api key", "api_key", "apikey", "unauthenticated", "unauthorized", "forbidden",
            "permission", "401", "403", "密钥", "鉴权",
        ]),
        (region, ["location", "region", "country", "地区", "区域"]),
        (config, [
            "invalid_request", "invalid argument", "invalid voice", "invalid model",
            "model not found", "not found", "does not exist", "unsupported", "unknown name",
            "400", "模型", "音色",
        ]),
    ]

    static func notice(_ hints: String?...) -> String { message(hints) ?? "" }

    static func message(_ hints: [String?]) -> String? {
        let hay = hints.compactMap { $0 }.joined(separator: " ").lowercased()
        if hay.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty { return nil }
        return rules.first { _, keys in keys.contains { hay.contains($0) } }?.0
    }
}
