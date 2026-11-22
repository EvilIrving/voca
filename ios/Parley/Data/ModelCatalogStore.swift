import CryptoKit
import Foundation

actor ModelCatalogStore {
    private let db: ParleyDatabase
    private let fetcher: ModelCatalogFetcher
    private let now: () -> Int64

    init(db: ParleyDatabase, fetcher: ModelCatalogFetcher = ModelCatalogFetcher(), now: @escaping () -> Int64 = {
        Int64(Date().timeIntervalSince1970 * 1000)
    }) {
        self.db = db
        self.fetcher = fetcher
        self.now = now
    }

    func models(providerId: String) async throws -> [TextModel] {
        ModelCatalog.visible(try await entryOf(providerId: providerId)?.models ?? [])
    }

    func model(providerId: String, modelId: String) async throws -> TextModel? {
        try await entryOf(providerId: providerId)?.models.first { $0.id == modelId }
    }

    func fetchedAt(providerId: String) async throws -> Int64? {
        try await entryOf(providerId: providerId)?.fetchedAt
    }

    func refresh(
        provider: TextProvider,
        apiKey: String,
        endpoint: String? = nil,
        auth: AuthSpec? = nil
    ) async throws -> [TextModel] {
        let endpoint = endpoint ?? provider.endpoint
        let auth = auth ?? provider.auth
        if apiKey.isEmpty || endpoint.isEmpty {
            try await clear(providerId: provider.id)
            return []
        }
        let tag = Self.keyTag(apiKey)
        let previous = try await entryOf(providerId: provider.id)
        let sameKey = previous.flatMap { $0.keyTag == tag ? $0 : nil }
        let config = TextEngineConfig(
            provider: provider,
            model: "",
            endpoint: endpoint,
            auth: auth,
            apiKey: apiKey
        )
        let live = await fetcher.models(config: config)
        let merged: [TextModel]?
        if let live {
            var directory: [TextModel] = []
            if let key = TextProviderCatalog.catalogKeyOf(provider.id) {
                directory = await fetcher.directory(key: key)
            }
            merged = ModelCatalog.merged(ModelCatalog.available(live), directory)
        } else {
            merged = nil
        }
        if merged == nil {
            if previous != nil, sameKey == nil { try await clear(providerId: provider.id) }
            return ModelCatalog.visible(sameKey?.models ?? [])
        }
        let ordered = ModelCatalog.ordered(merged!)
        let json = try JSONEncoder().encode(ordered)
        try await db.putModelCatalog(
            ModelCatalogRow(
                providerId: provider.id,
                modelsJson: String(data: json, encoding: .utf8) ?? "[]",
                fetchedAt: now(),
                keyTag: tag
            )
        )
        return ModelCatalog.visible(ordered)
    }

    func clear(providerId: String) async throws {
        try await db.clearModelCatalog(providerId: providerId)
    }

    private struct Entry {
        var models: [TextModel]
        var fetchedAt: Int64
        var keyTag: String
    }

    private func entryOf(providerId: String) async throws -> Entry? {
        guard let row = try await db.modelCatalog(providerId: providerId),
              let data = row.modelsJson.data(using: .utf8),
              let models = try? JSONDecoder().decode([TextModel].self, from: data)
        else { return nil }
        return Entry(models: models, fetchedAt: row.fetchedAt, keyTag: row.keyTag)
    }

    static func keyTag(_ apiKey: String) -> String {
        let digest = SHA256.hash(data: Data(apiKey.utf8))
        return digest.prefix(8).map { String(format: "%02x", $0) }.joined()
    }
}