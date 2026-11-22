import Foundation
import os

struct ConnectivityProbe: Sendable {
    private let session: URLSession

    init(session: URLSession = ConnectivityProbe.defaultSession) {
        self.session = session
    }

    func checkGemini(apiKey: String) async -> LinkStatus {
        guard !apiKey.isEmpty else { return .unconfigured }
        var components = URLComponents(string: "https://generativelanguage.googleapis.com/v1beta/models")!
        components.queryItems = [URLQueryItem(name: "key", value: apiKey)]
        var request = URLRequest(url: components.url!)
        request.httpMethod = "GET"
        do {
            let (_, response) = try await session.data(for: request)
            guard let http = response as? HTTPURLResponse else { return .failed }
            switch http.statusCode {
            case 200 ..< 300: return .connected
            case 401, 403: return .failed
            default: return .failed
            }
        } catch {
            Logger.probe.warning("probe failed: \(error.localizedDescription, privacy: .public)")
            return .failed
        }
    }

    static let defaultSession: URLSession = {
        let config = URLSessionConfiguration.ephemeral
        config.timeoutIntervalForRequest = 6
        config.timeoutIntervalForResource = 12
        return URLSession(configuration: config)
    }()
}

private enum Logger {
    static let probe = os.Logger(subsystem: "com.parley.speaker", category: "Probe")
}