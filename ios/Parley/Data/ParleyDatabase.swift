import Foundation
import SQLite3

private let SQLITE_TRANSIENT = unsafeBitCast(-1, to: sqlite3_destructor_type.self)

struct TurnRow {
    var id: String
    var speaker: String
    var text: String
    var reviewJson: String?
    var createdAt: Int64
    var audioPath: String?
    var saved: Bool
    var translation: String?
    var sessionId: String?
    /// 这一轮话筒录了多久：打卡按真实录音长度算，老库没有这一列时补零。
    var durationMs: Int64
}

struct VocabRow {
    var word: String
    var phonetic: String
    var meaning: String
    var example: String
    var highlight: String
    var repeats: Int
    var firstSeenAt: Int64
    var lastSeenAt: Int64
}

struct SessionRow {
    var id: String
    var name: String
    var startedAt: Int64
    var endedAt: Int64
    var rounds: Int
    var pronunciationAvg: Int?
    var grammarAvg: Int?
    var newWordCount: Int
    var manualName: Bool
}

struct ModelCatalogRow {
    var providerId: String
    var modelsJson: String
    var fetchedAt: Int64
    var keyTag: String
}

struct MemoryRow {
    var id: String
    var text: String
    var updatedAt: Int64
}

enum ParleyMigrations {
    static let v1Turn = """
    CREATE TABLE IF NOT EXISTS turn (
        id TEXT NOT NULL PRIMARY KEY,
        speaker TEXT NOT NULL,
        text TEXT NOT NULL,
        reviewJson TEXT,
        createdAt INTEGER NOT NULL
    )
    """
    static let v1Vocab = """
    CREATE TABLE IF NOT EXISTS vocab (
        word TEXT NOT NULL PRIMARY KEY,
        phonetic TEXT NOT NULL,
        meaning TEXT NOT NULL,
        example TEXT NOT NULL,
        highlight TEXT NOT NULL,
        repeats INTEGER NOT NULL,
        firstSeenAt INTEGER NOT NULL,
        lastSeenAt INTEGER NOT NULL
    )
    """

    static func migrate(_ db: OpaquePointer, from: Int, to: Int) throws {
        var version = from
        while version < to {
            switch version {
            case 1: try migrate1to2(db)
            case 2: try migrate2to3(db)
            case 3: try migrate3to4(db)
            case 4: try migrate4to5(db)
            case 5: try migrate5to6(db)
            case 6: try migrate6to7(db)
            case 7: try migrate7to8(db)
            default:
                throw NSError(domain: "ParleyDatabase", code: 1, userInfo: [NSLocalizedDescriptionKey: "Unknown version \(version)"])
            }
            version += 1
        }
    }

    static func execSQL(_ db: OpaquePointer, _ sql: String) throws {
        var message: UnsafeMutablePointer<CChar>?
        let code = sqlite3_exec(db, sql, nil, nil, &message)
        if code != SQLITE_OK {
            let msg = message.map { String(cString: $0) } ?? "sqlite error"
            sqlite3_free(message)
            throw NSError(domain: "ParleyDatabase", code: Int(code), userInfo: [NSLocalizedDescriptionKey: msg])
        }
    }

    private static func migrate1to2(_ db: OpaquePointer) throws {
        try execSQL(db, "ALTER TABLE turn ADD COLUMN audioPath TEXT")
        try execSQL(db, "ALTER TABLE turn ADD COLUMN saved INTEGER NOT NULL DEFAULT 0")
        try execSQL(
            db,
            """
            CREATE TABLE IF NOT EXISTS session (
                id TEXT NOT NULL,
                name TEXT NOT NULL,
                startedAt INTEGER NOT NULL,
                endedAt INTEGER NOT NULL,
                rounds INTEGER NOT NULL,
                pronunciationAvg INTEGER,
                grammarAvg INTEGER,
                newWordCount INTEGER NOT NULL,
                manualName INTEGER NOT NULL,
                PRIMARY KEY(id)
            )
            """
        )
    }

    private static func migrate2to3(_ db: OpaquePointer) throws {
        try execSQL(db, "ALTER TABLE turn ADD COLUMN translation TEXT")
    }

    private static func migrate3to4(_ db: OpaquePointer) throws {
        try execSQL(db, "ALTER TABLE turn ADD COLUMN sessionId TEXT")
        try execSQL(
            db,
            """
            UPDATE turn SET sessionId = (
                SELECT id FROM session
                WHERE turn.createdAt >= session.startedAt
                AND turn.createdAt <= session.endedAt
                ORDER BY session.startedAt DESC LIMIT 1
            ) WHERE sessionId IS NULL
            """
        )
    }

    private static func migrate4to5(_ db: OpaquePointer) throws {
        try execSQL(
            db,
            """
            CREATE TABLE IF NOT EXISTS model_catalog (
                providerId TEXT NOT NULL,
                modelsJson TEXT NOT NULL,
                fetchedAt INTEGER NOT NULL,
                keyTag TEXT NOT NULL,
                PRIMARY KEY(providerId)
            )
            """
        )
    }

    private static func migrate5to6(_ db: OpaquePointer) throws {
        try execSQL(
            db,
            """
            CREATE TABLE IF NOT EXISTS vocab_new (
                word TEXT NOT NULL COLLATE NOCASE,
                phonetic TEXT NOT NULL,
                meaning TEXT NOT NULL,
                example TEXT NOT NULL,
                highlight TEXT NOT NULL,
                repeats INTEGER NOT NULL,
                firstSeenAt INTEGER NOT NULL,
                lastSeenAt INTEGER NOT NULL,
                PRIMARY KEY(word)
            )
            """
        )
        try execSQL(
            db,
            """
            INSERT INTO vocab_new SELECT word, phonetic, meaning, example, highlight,
                SUM(repeats), MIN(firstSeenAt), MAX(lastSeenAt) FROM vocab
                GROUP BY word COLLATE NOCASE
            """
        )
        try execSQL(db, "DROP TABLE vocab")
        try execSQL(db, "ALTER TABLE vocab_new RENAME TO vocab")
    }

    /// 七到八：每一轮记下录音长度。老库补零，等于那几轮没有可测的音频，不计进打卡。
    private static func migrate7to8(_ db: OpaquePointer) throws {
        try execSQL(db, "ALTER TABLE turn ADD COLUMN durationMs INTEGER NOT NULL DEFAULT 0")
    }

    private static func migrate6to7(_ db: OpaquePointer) throws {
        try execSQL(
            db,
            """
            CREATE TABLE IF NOT EXISTS memory (
                id TEXT NOT NULL,
                text TEXT NOT NULL,
                updatedAt INTEGER NOT NULL,
                PRIMARY KEY(id)
            )
            """
        )
    }

}

actor ParleyDatabase {
    static let fileName = "parley.db"
    static let schemaVersion = 8

    nonisolated(unsafe) private var db: OpaquePointer?

    init(directory: URL? = nil) throws {
        let dir = directory ?? FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0]
            .appendingPathComponent("Parley", isDirectory: true)
        try FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        let path = dir.appendingPathComponent(Self.fileName).path
        var handle: OpaquePointer?
        guard sqlite3_open(path, &handle) == SQLITE_OK, let handle else {
            throw NSError(domain: "ParleyDatabase", code: 2, userInfo: [NSLocalizedDescriptionKey: "Unable to open database"])
        }
        db = handle
        sqlite3_busy_timeout(handle, 5_000)
        try Self.prepareSchema(handle)
    }

    deinit {
        if let db { sqlite3_close(db) }
    }

    private static func prepareSchema(_ db: OpaquePointer) throws {
        let version = userVersion(db)
        if version == 0 {
            try ParleyMigrations.execSQL(db, ParleyMigrations.v1Turn)
            try ParleyMigrations.execSQL(db, ParleyMigrations.v1Vocab)
            try ParleyMigrations.migrate(db, from: 1, to: Self.schemaVersion)
            try ParleyMigrations.execSQL(db, "PRAGMA user_version = \(Self.schemaVersion)")
        } else if version < Self.schemaVersion {
            try ParleyMigrations.migrate(db, from: version, to: Self.schemaVersion)
            try ParleyMigrations.execSQL(db, "PRAGMA user_version = \(Self.schemaVersion)")
        }
    }

    // MARK: - Turn

    func allTurns() throws -> [TurnRow] {
        try queryTurns("SELECT * FROM turn ORDER BY createdAt ASC")
    }

    func turnsBetween(since: Int64, until: Int64) throws -> [TurnRow] {
        try queryTurns("SELECT * FROM turn WHERE createdAt BETWEEN ? AND ? ORDER BY createdAt ASC", since, until)
    }

    func turnsBySession(_ sessionId: String) throws -> [TurnRow] {
        try queryTurns("SELECT * FROM turn WHERE sessionId = ? ORDER BY createdAt ASC", sessionId)
    }

    func bindSession(sessionId: String, startedAt: Int64, endedAt: Int64) throws {
        try run(
            """
            UPDATE turn SET sessionId = ?
            WHERE (sessionId IS NULL OR sessionId = '') AND createdAt BETWEEN ? AND ?
            """,
            sessionId, startedAt, endedAt
        )
    }

    @discardableResult
    func insertTurnIfAbsent(_ row: TurnRow) throws -> Bool {
        let sql = """
        INSERT OR IGNORE INTO turn (id, speaker, text, reviewJson, createdAt, audioPath, saved, translation, sessionId)
        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
        """
        let changesBefore = sqlite3_total_changes(db)
        try run(
            sql,
            row.id, row.speaker, row.text, row.reviewJson, row.createdAt,
            row.audioPath, row.saved ? 1 : 0, row.translation, row.sessionId, row.durationMs
        )
        return sqlite3_total_changes(db) > changesBefore
    }

    func updateTurn(
        id: String,
        speaker: String,
        text: String,
        reviewJson: String?,
        audioPath: String?,
        translation: String?,
        sessionId: String?,
        durationMs: Int64
    ) throws {
        try run(
            """
            UPDATE turn SET speaker = ?, text = ?, reviewJson = ?,
            audioPath = COALESCE(?, audioPath), translation = COALESCE(?, translation),
            sessionId = COALESCE(?, sessionId),
            durationMs = CASE WHEN ? > 0 THEN ? ELSE durationMs END WHERE id = ?
            """,
            speaker, text, reviewJson, audioPath, translation, sessionId, durationMs, durationMs, id
        )
    }

    func setTurnSaved(id: String, saved: Bool) throws {
        try run("UPDATE turn SET saved = ? WHERE id = ?", saved ? 1 : 0, id)
    }

    func deleteTurn(id: String) throws {
        try run("DELETE FROM turn WHERE id = ?", id)
    }

    func clearTurns() throws {
        try run("DELETE FROM turn")
    }

    /// 删一段会话时连带清掉它的轮次，录音文件由存储层按列逐个删。
    func deleteTurnsBySession(sessionId: String) throws {
        try run("DELETE FROM turn WHERE sessionId = ?", sessionId)
    }

    private func queryTurns(_ sql: String, _ args: Any?...) throws -> [TurnRow] {
        try query(sql, args) { stmt in
            TurnRow(
                id: columnText(stmt, 0),
                speaker: columnText(stmt, 1),
                text: columnText(stmt, 2),
                reviewJson: columnOptionalText(stmt, 3),
                createdAt: columnInt64(stmt, 4),
                audioPath: columnOptionalText(stmt, 5),
                saved: sqlite3_column_int(stmt, 6) != 0,
                translation: columnOptionalText(stmt, 7),
                sessionId: columnOptionalText(stmt, 8),
                durationMs: columnInt64(stmt, 9)
            )
        }
    }

    // MARK: - Vocab

    func allVocab() throws -> [VocabRow] {
        try queryVocab("SELECT * FROM vocab ORDER BY lastSeenAt DESC")
    }

    @discardableResult
    func insertVocabIfAbsent(_ row: VocabRow) throws -> Bool {
        let changesBefore = sqlite3_total_changes(db)
        try run(
            """
            INSERT OR IGNORE INTO vocab (word, phonetic, meaning, example, highlight, repeats, firstSeenAt, lastSeenAt)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?)
            """,
            row.word, row.phonetic, row.meaning, row.example, row.highlight, row.repeats, row.firstSeenAt, row.lastSeenAt
        )
        return sqlite3_total_changes(db) > changesBefore
    }

    func refreshVocab(
        word: String,
        phonetic: String,
        meaning: String,
        example: String,
        highlight: String,
        now: Int64
    ) throws {
        try run(
            """
            UPDATE vocab SET phonetic = ?, meaning = ?, example = ?, highlight = ?,
            repeats = repeats + 1, lastSeenAt = ? WHERE word = ?
            """,
            phonetic, meaning, example, highlight, now, word
        )
    }

    func vocabDue(startOfToday: Int64) throws -> [VocabRow] {
        try queryVocab(
            """
            SELECT * FROM vocab WHERE repeats > 0 AND lastSeenAt < ?
            ORDER BY repeats ASC, lastSeenAt ASC, word ASC
            """,
            startOfToday
        )
    }

    func countVocabFirstSeenSince(_ since: Int64) throws -> Int {
        try scalarInt("SELECT COUNT(*) FROM vocab WHERE firstSeenAt >= ?", since) ?? 0
    }

    func bumpVocab(word: String, now: Int64) throws {
        try run("UPDATE vocab SET repeats = repeats + 1, lastSeenAt = ? WHERE word = ?", now, word)
    }

    func deleteVocab(word: String) throws {
        try run("DELETE FROM vocab WHERE word = ? COLLATE NOCASE", word)
    }

    /// 撤销删除时整行插回去：复现次数与首次收录时间都在行里，不能重算。
    func restoreVocab(_ row: VocabRow) throws {
        try run(
            """
            INSERT OR REPLACE INTO vocab (word, phonetic, meaning, example, highlight, repeats, firstSeenAt, lastSeenAt)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?)
            """,
            row.word, row.phonetic, row.meaning, row.example, row.highlight, row.repeats, row.firstSeenAt, row.lastSeenAt
        )
    }

    private func queryVocab(_ sql: String, _ args: Any?...) throws -> [VocabRow] {
        try query(sql, args) { stmt in
            VocabRow(
                word: columnText(stmt, 0),
                phonetic: columnText(stmt, 1),
                meaning: columnText(stmt, 2),
                example: columnText(stmt, 3),
                highlight: columnText(stmt, 4),
                repeats: Int(sqlite3_column_int(stmt, 5)),
                firstSeenAt: columnInt64(stmt, 6),
                lastSeenAt: columnInt64(stmt, 7)
            )
        }
    }

    // MARK: - Session

    func allSessions() throws -> [SessionRow] {
        try querySessions("SELECT * FROM session ORDER BY startedAt DESC")
    }

    func sessionById(_ id: String) throws -> SessionRow? {
        try querySessions("SELECT * FROM session WHERE id = ?", id).first
    }

    @discardableResult
    func insertSessionIfAbsent(_ row: SessionRow) throws -> Bool {
        let changesBefore = sqlite3_total_changes(db)
        try run(
            """
            INSERT OR IGNORE INTO session (id, name, startedAt, endedAt, rounds, pronunciationAvg, grammarAvg, newWordCount, manualName)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
            """,
            row.id, row.name, row.startedAt, row.endedAt, row.rounds,
            row.pronunciationAvg, row.grammarAvg, row.newWordCount, row.manualName ? 1 : 0
        )
        return sqlite3_total_changes(db) > changesBefore
    }

    func unnamedSessionsSince(since: Int64, limit: Int) throws -> [SessionRow] {
        try querySessions(
            "SELECT * FROM session WHERE name = '' AND endedAt >= ? ORDER BY endedAt DESC LIMIT ?",
            since, limit
        )
    }

    func renameSession(id: String, name: String, manual: Bool) throws {
        try run(
            "UPDATE session SET name = ?, manualName = ? WHERE id = ? AND (manualName = 0 OR ? = 1)",
            name, manual ? 1 : 0, id, manual ? 1 : 0
        )
    }

    /// 接着聊的那一段收回去：只刷新收口时间与重算出来的数与均值，名字与起点保持第一次的值。
    func updateSessionStats(
        id: String,
        endedAt: Int64,
        rounds: Int,
        pronunciationAvg: Int?,
        grammarAvg: Int?,
        newWordCount: Int
    ) throws {
        try run(
            """
            UPDATE session SET endedAt = ?, rounds = ?, pronunciationAvg = ?, grammarAvg = ?, newWordCount = ?
            WHERE id = ?
            """,
            endedAt, rounds, pronunciationAvg, grammarAvg, newWordCount, id
        )
    }

    func deleteSession(id: String) throws {
        try run("DELETE FROM session WHERE id = ?", id)
    }

    private func querySessions(_ sql: String, _ args: Any?...) throws -> [SessionRow] {
        try query(sql, args) { stmt in
            SessionRow(
                id: columnText(stmt, 0),
                name: columnText(stmt, 1),
                startedAt: columnInt64(stmt, 2),
                endedAt: columnInt64(stmt, 3),
                rounds: Int(sqlite3_column_int(stmt, 4)),
                pronunciationAvg: columnOptionalInt(stmt, 5),
                grammarAvg: columnOptionalInt(stmt, 6),
                newWordCount: Int(sqlite3_column_int(stmt, 7)),
                manualName: sqlite3_column_int(stmt, 8) != 0
            )
        }
    }

    // MARK: - Model catalog

    func modelCatalog(providerId: String) throws -> ModelCatalogRow? {
        let rows: [ModelCatalogRow] = try query(
            "SELECT providerId, modelsJson, fetchedAt, keyTag FROM model_catalog WHERE providerId = ?",
            [providerId]
        ) { stmt in
            ModelCatalogRow(
                providerId: columnText(stmt, 0),
                modelsJson: columnText(stmt, 1),
                fetchedAt: columnInt64(stmt, 2),
                keyTag: columnText(stmt, 3)
            )
        }
        return rows.first
    }

    func putModelCatalog(_ row: ModelCatalogRow) throws {
        try run(
            """
            INSERT OR REPLACE INTO model_catalog (providerId, modelsJson, fetchedAt, keyTag)
            VALUES (?, ?, ?, ?)
            """,
            row.providerId, row.modelsJson, row.fetchedAt, row.keyTag
        )
    }

    func clearModelCatalog(providerId: String) throws {
        try run("DELETE FROM model_catalog WHERE providerId = ?", providerId)
    }

    // MARK: - Memory

    func memoryById(_ id: String) throws -> MemoryRow? {
        let rows: [MemoryRow] = try query("SELECT id, text, updatedAt FROM memory WHERE id = ?", [id]) { stmt in
            MemoryRow(id: columnText(stmt, 0), text: columnText(stmt, 1), updatedAt: columnInt64(stmt, 2))
        }
        return rows.first
    }

    func putMemory(_ row: MemoryRow) throws {
        try run("INSERT OR REPLACE INTO memory (id, text, updatedAt) VALUES (?, ?, ?)", row.id, row.text, row.updatedAt)
    }

    // MARK: - SQLite helpers

    private static func userVersion(_ db: OpaquePointer) -> Int {
        var stmt: OpaquePointer?
        defer { sqlite3_finalize(stmt) }
        guard sqlite3_prepare_v2(db, "PRAGMA user_version", -1, &stmt, nil) == SQLITE_OK,
              sqlite3_step(stmt) == SQLITE_ROW
        else { return 0 }
        return Int(sqlite3_column_int(stmt, 0))
    }

    private func run(_ sql: String, _ args: Any?...) throws {
        guard let db else { return }
        var stmt: OpaquePointer?
        defer { sqlite3_finalize(stmt) }
        guard sqlite3_prepare_v2(db, sql, -1, &stmt, nil) == SQLITE_OK, let stmt else {
            throw dbError(db)
        }
        try bind(stmt, Array(args))
        let step = sqlite3_step(stmt)
        guard step == SQLITE_DONE else {
            throw dbError(db)
        }
    }

    private func query<T>(_ sql: String, _ args: [Any?], map: (OpaquePointer) -> T) throws -> [T] {
        guard let db else { return [] }
        var stmt: OpaquePointer?
        defer { sqlite3_finalize(stmt) }
        guard sqlite3_prepare_v2(db, sql, -1, &stmt, nil) == SQLITE_OK, let stmt else {
            throw dbError(db)
        }
        try bind(stmt, args)
        var out: [T] = []
        while sqlite3_step(stmt) == SQLITE_ROW {
            out.append(map(stmt))
        }
        return out
    }

    private func query<T>(_ sql: String, _ args: Any?..., map: (OpaquePointer) -> T) throws -> [T] {
        try query(sql, Array(args), map: map)
    }

    private func scalarInt(_ sql: String, _ args: Any?...) throws -> Int? {
        guard let db else { return nil }
        var stmt: OpaquePointer?
        defer { sqlite3_finalize(stmt) }
        guard sqlite3_prepare_v2(db, sql, -1, &stmt, nil) == SQLITE_OK, let stmt else {
            throw dbError(db)
        }
        try bind(stmt, Array(args))
        guard sqlite3_step(stmt) == SQLITE_ROW else { return nil }
        return Int(sqlite3_column_int(stmt, 0))
    }

    private func bind(_ stmt: OpaquePointer, _ args: [Any?]) throws {
        for (index, arg) in args.enumerated() {
            let position = Int32(index + 1)
            switch arg {
            case nil:
                sqlite3_bind_null(stmt, position)
            case let value as String:
                sqlite3_bind_text(stmt, position, value, -1, SQLITE_TRANSIENT)
            case let value as Int:
                sqlite3_bind_int(stmt, position, Int32(value))
            case let value as Int64:
                sqlite3_bind_int64(stmt, position, value)
            case let value as Bool:
                sqlite3_bind_int(stmt, position, value ? 1 : 0)
            default:
                sqlite3_bind_null(stmt, position)
            }
        }
    }

    private func columnText(_ stmt: OpaquePointer, _ index: Int32) -> String {
        guard let cString = sqlite3_column_text(stmt, index) else { return "" }
        return String(cString: cString)
    }

    private func columnOptionalText(_ stmt: OpaquePointer, _ index: Int32) -> String? {
        guard sqlite3_column_type(stmt, index) != SQLITE_NULL else { return nil }
        return columnText(stmt, index)
    }

    private func columnInt64(_ stmt: OpaquePointer, _ index: Int32) -> Int64 {
        sqlite3_column_int64(stmt, index)
    }

    private func columnOptionalInt(_ stmt: OpaquePointer, _ index: Int32) -> Int? {
        guard sqlite3_column_type(stmt, index) != SQLITE_NULL else { return nil }
        return Int(sqlite3_column_int(stmt, index))
    }

    private func dbError(_ db: OpaquePointer?) -> NSError {
        let msg = db.flatMap { sqlite3_errmsg($0) }.map { String(cString: $0) } ?? "database error"
        return NSError(domain: "ParleyDatabase", code: 3, userInfo: [NSLocalizedDescriptionKey: msg])
    }
}
