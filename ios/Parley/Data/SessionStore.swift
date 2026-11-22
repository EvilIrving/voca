import Foundation

actor SessionStore {
    private let db: ParleyDatabase
    private let jsonDecoder = JSONDecoder()
    private let jsonEncoder = JSONEncoder()

    init(db: ParleyDatabase) {
        self.db = db
    }

    init() throws {
        try self.init(db: ParleyDatabase())
    }

    func loadTurns() async throws -> [Turn] {
        try await db.allTurns().compactMap { toTurn($0) }
    }

    func globalMemory() async throws -> String {
        try await db.memoryById(Self.globalMemoryId)?.text ?? ""
    }

    func saveMemory(_ text: String) async throws {
        let now = Int64(Date().timeIntervalSince1970 * 1000)
        try await db.putMemory(MemoryRow(id: Self.globalMemoryId, text: text, updatedAt: now))
    }

    func saveTurn(_ turn: Turn) async throws {
        let now = Int64(Date().timeIntervalSince1970 * 1000)
        let reviewJson = turn.review.flatMap { try? String(data: jsonEncoder.encode($0), encoding: .utf8) }
        let row = TurnRow(
            id: turn.id,
            speaker: turn.speaker.dbName,
            text: turn.text,
            reviewJson: reviewJson,
            createdAt: now,
            audioPath: turn.audioPath,
            saved: turn.saved,
            translation: turn.translation,
            sessionId: turn.sessionId
        )
        _ = try await db.insertTurnIfAbsent(row)
        try await db.updateTurn(
            id: row.id,
            speaker: row.speaker,
            text: row.text,
            reviewJson: row.reviewJson,
            audioPath: row.audioPath,
            translation: row.translation,
            sessionId: row.sessionId
        )
    }

    func setSaved(turnId: String, saved: Bool) async throws {
        try await db.setTurnSaved(id: turnId, saved: saved)
    }

    @discardableResult
    func saveReview(turnId: String, review: Review) async throws -> Bool {
        guard let row = try await db.allTurns().first(where: { $0.id == turnId }) else { return false }
        let reviewJson = try String(data: jsonEncoder.encode(review), encoding: .utf8)
        try await db.updateTurn(
            id: row.id,
            speaker: row.speaker,
            text: row.text,
            reviewJson: reviewJson,
            audioPath: row.audioPath,
            translation: row.translation,
            sessionId: row.sessionId
        )
        return true
    }

    func deleteTurn(turnId: String) async throws {
        try await db.deleteTurn(id: turnId)
    }

    func clearTurns() async throws {
        try await db.clearTurns()
    }

    func loadVocab() async throws -> [VocabCard] {
        try await db.allVocab().map { toCard($0) }
    }

    func due(now: Int64 = Int64(Date().timeIntervalSince1970 * 1000)) async throws -> [VocabCard] {
        try await db.vocabDue(startOfToday: startOfLocalDay(now)).map { toCard($0) }
    }

    func recordVocab(cards: [VocabCard]) async throws {
        let now = Int64(Date().timeIntervalSince1970 * 1000)
        for card in cards {
            let inserted = try await db.insertVocabIfAbsent(
                VocabRow(
                    word: card.word,
                    phonetic: card.phonetic,
                    meaning: card.meaning,
                    example: card.example,
                    highlight: card.highlight,
                    repeats: card.repeats,
                    firstSeenAt: now,
                    lastSeenAt: now
                )
            )
            if !inserted {
                try await db.refreshVocab(
                    word: card.word,
                    phonetic: card.phonetic,
                    meaning: card.meaning,
                    example: card.example,
                    highlight: card.highlight,
                    now: now
                )
            }
        }
    }

    func bumpVocab(word: String) async throws {
        let now = Int64(Date().timeIntervalSince1970 * 1000)
        try await db.bumpVocab(word: word, now: now)
    }

    /// 会话结束时签到一条记录，轮次、双项均值与新词数从 `startedAt` 到 `endedAt` 的库里算出来。
    /// 已经有一行的（接着聊回来的那一段）不另起一行，只把收口时间与重算的读数写回去，
    /// 名字与起点保持第一次的值；这一条也不按时间窗重绑轮次，中间夹着的别的会话不会被圈进来。
    func closeSession(id: String, startedAt: Int64, endedAt: Int64 = Int64(Date().timeIntervalSince1970 * 1000)) async throws -> SessionRecord {
        let existing = try await db.sessionById(id)
        let turnsInSession: [TurnRow]
        if existing != nil {
            turnsInSession = try await db.turnsBySession(id)
        } else {
            try await db.bindSession(sessionId: id, startedAt: startedAt, endedAt: endedAt)
            let bound = try await db.turnsBySession(id)
            turnsInSession = bound.isEmpty
                ? try await db.turnsBetween(since: startedAt, until: endedAt)
                : bound
        }
        let reviews = turnsInSession.compactMap { toTurn($0)?.review }
        let rounds = turnsInSession.filter { $0.speaker == Speaker.user.dbName }.count
        let pronunciationAvg = reviews.compactMap(\.pronunciation?.score).mean
        let grammarAvg = reviews.map(\.grammar.score).mean
        let newWordCount = try await db.countVocabFirstSeenSince(startedAt)
        if let existing {
            try await db.updateSessionStats(
                id: id,
                endedAt: endedAt,
                rounds: rounds,
                pronunciationAvg: pronunciationAvg,
                grammarAvg: grammarAvg,
                newWordCount: newWordCount
            )
            return toRecord((try await db.sessionById(id)) ?? existing)
        }
        let row = SessionRow(
            id: id,
            name: "",
            startedAt: startedAt,
            endedAt: endedAt,
            rounds: rounds,
            pronunciationAvg: pronunciationAvg,
            grammarAvg: grammarAvg,
            newWordCount: newWordCount,
            manualName: false
        )
        _ = try await db.insertSessionIfAbsent(row)
        if let saved = try await db.sessionById(id) {
            return toRecord(saved)
        }
        return toRecord(row)
    }

    func loadSessions(now: Int64 = Int64(Date().timeIntervalSince1970 * 1000)) async throws -> [SessionGroup] {
        let records = try await db.allSessions().map { toRecord($0) }
        return groupedByDay(records, now: now)
    }

    func takeUnnamed(limit: Int = 3, now: Int64 = Int64(Date().timeIntervalSince1970 * 1000)) async throws -> [SessionRecord] {
        try await db.unnamedSessionsSince(since: now - Self.unnamedWindow, limit: limit).map { toRecord($0) }
    }

    func transcript(sessionId: String) async throws -> [Turn] {
        let bySession = try await db.turnsBySession(sessionId).compactMap { toTurn($0) }
        if !bySession.isEmpty { return bySession }
        guard let session = try await db.sessionById(sessionId) else { return [] }
        return try await db.turnsBetween(since: session.startedAt, until: session.endedAt).compactMap { toTurn($0) }
    }

    func rename(id: String, name: String, manual: Bool) async throws {
        let trimmed = name.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty else { return }
        try await db.renameSession(id: id, name: trimmed, manual: manual)
    }

    private func toTurn(_ row: TurnRow) -> Turn? {
        guard let speaker = Speaker.fromDB(row.speaker) else { return nil }
        let review: Review? = row.reviewJson.flatMap { raw in
            guard let data = raw.data(using: .utf8) else { return nil }
            return try? jsonDecoder.decode(Review.self, from: data)
        }
        return Turn(
            id: row.id,
            speaker: speaker,
            text: row.text,
            review: review,
            audioPath: row.audioPath,
            analysis: review != nil ? .ready : .none,
            saved: row.saved,
            translation: row.translation,
            sessionId: row.sessionId
        )
    }

    private func toCard(_ row: VocabRow) -> VocabCard {
        VocabCard(
            id: row.word,
            word: row.word,
            phonetic: row.phonetic,
            meaning: row.meaning.withoutAnnotation(),
            example: row.example,
            highlight: row.highlight,
            repeats: row.repeats,
            lastSeenAt: row.lastSeenAt
        )
    }

    private func toRecord(_ row: SessionRow) -> SessionRecord {
        SessionRecord(
            id: row.id,
            name: row.name,
            startedAt: row.startedAt,
            endedAt: row.endedAt,
            rounds: row.rounds,
            pronunciationAvg: row.pronunciationAvg,
            grammarAvg: row.grammarAvg,
            newWordCount: row.newWordCount,
            manualName: row.manualName
        )
    }

    private static let unnamedWindow: Int64 = 24 * 60 * 60 * 1000
    static let globalMemoryId = "global"
}

private extension Collection where Element == Int {
    var mean: Int? {
        guard !isEmpty else { return nil }
        return Int((Double(reduce(0, +)) / Double(count)).rounded())
    }
}