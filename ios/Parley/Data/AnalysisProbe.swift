import Foundation
import os

enum AnalysisProbe {
    struct Plan {
        var status: LinkStatus
        var request: URLRequest?
    }

    static func plan(config: TextEngineConfig) -> Plan {
        if config.apiKey.isEmpty || config.endpoint.isEmpty { return Plan(status: .unconfigured) }
        guard let request = config.probeRequest() else { return Plan(status: .failed) }
        return Plan(status: .configured, request: request)
    }

    static func check(config: TextEngineConfig, session: URLSession = AnalysisProbe.probeSession) async -> LinkStatus {
        let planned = plan(config: config)
        guard let request = planned.request else { return planned.status }
        do {
            let (_, response) = try await session.data(for: request)
            guard let http = response as? HTTPURLResponse else { return .failed }
            return (200 ..< 300).contains(http.statusCode) ? .connected : .failed
        } catch {
            Logger.probe.warning("probe failed: \(error.localizedDescription, privacy: .public)")
            return .failed
        }
    }

    static let probeSession: URLSession = {
        let config = URLSessionConfiguration.ephemeral
        config.timeoutIntervalForRequest = 6
        config.timeoutIntervalForResource = 12
        return URLSession(configuration: config)
    }()
}

private enum Logger {
    static let probe = os.Logger(subsystem: "com.parley.speaker", category: "Probe")
}