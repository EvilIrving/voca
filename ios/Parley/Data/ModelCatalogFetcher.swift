import Foundation
import os

struct ModelCatalogFetcher {
    private let session: URLSession

    init(session: URLSession = ModelCatalogFetcher.catalogSession) {
        self.session = session
    }

    func models(config: TextEngineConfig) async -> [TextModel]? {
        guard let request = config.modelsRequest() else { return nil }
        guard let body = await body(request) else { return nil }
        return ModelCatalog.available(body)
    }

    func directory(key: String) async -> [TextModel] {
        var request = URLRequest(url: URL(string: Self.directoryUrl)!)
        request.httpMethod = "GET"
        guard let body = await body(request) else { return [] }
        return ModelCatalog.directory(body, key: key)
    }

    private func body(_ request: URLRequest) async -> String? {
        do {
            let (data, response) = try await session.data(for: request)
            guard let http = response as? HTTPURLResponse else { return nil }
            let text = String(data: data, encoding: .utf8) ?? ""
            if (200 ..< 300).contains(http.statusCode) {
                return text
            }
            Logger.modelCatalog.warning("models \(http.statusCode): \(text.prefix(160), privacy: .public)")
            return nil
        } catch {
            Logger.modelCatalog.warning("models request failed: \(error.localizedDescription, privacy: .public)")
            return nil
        }
    }

    static let directoryUrl = "https://models.dev/api.json"
    static var DirectoryUrl: String { directoryUrl }

    static let catalogSession: URLSession = {
        let config = URLSessionConfiguration.ephemeral
        config.timeoutIntervalForRequest = 8
        config.timeoutIntervalForResource = 20
        return URLSession(configuration: config)
    }()
}

private enum Logger {
    static let modelCatalog = os.Logger(subsystem: "com.parley.speaker", category: "ModelCatalog")
}