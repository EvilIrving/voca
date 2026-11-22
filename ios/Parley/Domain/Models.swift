import Foundation

enum Speaker: String, Codable {
    case echo, user

    var dbName: String {
        switch self {
        case .echo: "Echo"
        case .user: "User"
        }
    }

    static func fromDB(_ name: String) -> Speaker? {
        switch name {
        case "Echo": .echo
        case "User": .user
        default: Speaker(rawValue: name.lowercased())
        }
    }
}

extension String {
    func withoutAnnotation() -> String {
        replacingOccurrences(of: #"[（(][^（()）]*[）)]"#, with: "", options: .regularExpression)
    }
}

struct PhonemeIssue: Codable, Hashable {
    var word: String
    var phonetic: String
    var note: String
}

struct PronunciationReview: Codable, Hashable {
    var score: Int
    var note: String
    var phonemes: [PhonemeIssue] = []
}

struct GrammarReview: Codable, Hashable {
    var score: Int
    var note: String
}

enum Register: String, Codable {
    case idiomatic
    case advanced
    var label: String { self == .idiomatic ? "地道表达" : "高级表达" }
}

struct Rewrite: Codable, Hashable {
    var register: Register
    var text: String
    var gloss: String
}

struct Review: Codable, Hashable {
    var original: String
    var grammar: GrammarReview
    var pronunciation: PronunciationReview?
    var rewrites: [Rewrite]
}

enum AnalysisStatus: String, Codable { case none, pending, ready, failed }

struct Turn: Codable, Hashable, Identifiable {
    var id: String
    var speaker: Speaker
    var text: String
    var review: Review? = nil
    var audioPath: String? = nil
    var analysis: AnalysisStatus = .none
    var saved: Bool = false
    var translation: String? = nil
    var sessionId: String? = nil
}

struct VocabCard: Codable, Hashable, Identifiable {
    var id: String
    var word: String
    var phonetic: String
    var meaning: String
    var example: String
    var highlight: String
    var repeats: Int
    var lastSeenAt: Int64 = 0

    var entryType: VocabEntryType { word.trimmingCharacters(in: .whitespaces).contains(" ") ? .collocation : .word }

    func tags() -> [(String, VocabTagKind)] {
        [(entryType.label, .type), ("复现 \(repeats) 次", .repeats)]
    }
}

enum VocabBucket: String, CaseIterable {
    case today, due, earlier
    var label: String {
        switch self {
        case .today: "今天"
        case .due: "待复习"
        case .earlier: "更早"
        }
    }
}

enum VocabFilter: String, CaseIterable {
    case today, due, all
    var label: String {
        switch self {
        case .today: "今天"
        case .due: "待复习"
        case .all: "全部"
        }
    }
}

enum VocabEntryType {
    case collocation, word
    var label: String { self == .collocation ? "地道搭配" : "生词" }
}

enum VocabTagKind { case type, repeats }

struct VocabSection {
    var bucket: VocabBucket
    var cards: [VocabCard]
}

struct VocabCounts {
    var today: Int
    var due: Int
    var all: Int
    subscript(_ filter: VocabFilter) -> Int {
        switch filter {
        case .today: today
        case .due: due
        case .all: all
        }
    }
}

struct VocabPage {
    var counts: VocabCounts
    var sections: [VocabSection]
}

func vocabPage(cards: [VocabCard], todayStart: Int64, filter: VocabFilter, query: String = "") -> VocabPage {
    let needle = query.trimmingCharacters(in: .whitespaces)
    let matched = cards.filter { card in
        needle.isEmpty
            || card.word.localizedCaseInsensitiveContains(needle)
            || card.phonetic.localizedCaseInsensitiveContains(needle)
            || card.meaning.localizedCaseInsensitiveContains(needle)
    }
    func bucket(of card: VocabCard) -> VocabBucket {
        if card.lastSeenAt >= todayStart { return .today }
        if card.repeats > 0 { return .due }
        return .earlier
    }
    var grouped: [VocabBucket: [VocabCard]] = [:]
    for card in matched {
        grouped[bucket(of: card), default: []].append(card)
    }
    let sections = VocabBucket.allCases.compactMap { bucket -> VocabSection? in
        var list = grouped[bucket] ?? []
        if list.isEmpty { return nil }
        if bucket == .due {
            list.sort { a, b in
                if a.repeats != b.repeats { return a.repeats < b.repeats }
                if a.lastSeenAt != b.lastSeenAt { return a.lastSeenAt < b.lastSeenAt }
                return a.word.lowercased() < b.word.lowercased()
            }
        } else {
            list.sort { a, b in
                if a.lastSeenAt != b.lastSeenAt { return a.lastSeenAt > b.lastSeenAt }
                return a.word.lowercased() < b.word.lowercased()
            }
        }
        if filter != .all {
            let wanted: VocabBucket = filter == .today ? .today : .due
            if bucket != wanted { return nil }
        }
        return VocabSection(bucket: bucket, cards: list)
    }
    return VocabPage(
        counts: VocabCounts(
            today: grouped[.today]?.count ?? 0,
            due: grouped[.due]?.count ?? 0,
            all: matched.count
        ),
        sections: sections
    )
}

func startOfLocalDay(_ now: Int64 = Int64(Date().timeIntervalSince1970 * 1000)) -> Int64 {
    let date = Date(timeIntervalSince1970: TimeInterval(now) / 1000)
    let start = Calendar.current.startOfDay(for: date)
    return Int64(start.timeIntervalSince1970 * 1000)
}

enum LinkStatus { case connected, configured, unconfigured, failed, notSupported }

struct ProviderLine: Identifiable {
    var key: String
    var group: String
    var name: String
    var detail: String
    var status: LinkStatus
    var editable: Bool = false
    var id: String { key }
}

enum AssessorProvider: String, Codable, CaseIterable {
    case none, azure, xfyun
    var label: String {
        switch self {
        case .none: "未选择"
        case .azure: "微软 Azure 语音评测"
        case .xfyun: "讯飞评测流式版"
        }
    }
}

enum AuthKind: String, Codable { case bearer, header, query }

enum LiveVendor: String, Codable, CaseIterable {
    case gemini, openAi, doubao
    var label: String {
        switch self {
        case .gemini: "Gemini Live"
        case .openAi: "OpenAI Realtime"
        case .doubao: "豆包实时语音"
        }
    }
}

struct VoiceOption: Hashable {
    var id: String
    var style: String
    var isDefault: Bool = false
    var group: String = ""
}

struct LiveSettings: Codable, Equatable {
    var provider: LiveVendor = .gemini
    var geminiKey: String = ""
    var openAiKey: String = ""
    var doubaoAppId: String = ""
    var doubaoAppKey: String = ""
    var doubaoAccessKey: String = ""
    var model: String = LiveDefaults.model(.gemini)
    var voice: String = LiveDefaults.voice(.gemini)

    func ready(_ vendor: LiveVendor) -> Bool {
        switch vendor {
        case .gemini: !geminiKey.isEmpty
        case .openAi: !openAiKey.isEmpty
        case .doubao: !doubaoAppId.isEmpty && !doubaoAppKey.isEmpty && !doubaoAccessKey.isEmpty
        }
    }

    var ready: Bool { ready(provider) }
}

enum LiveDefaults {
    static func model(_ vendor: LiveVendor) -> String {
        switch vendor {
        case .gemini: "gemini-3.8-live"
        case .openAi: "gpt-realtime-2.1"
        case .doubao: ""
        }
    }

    static func voice(_ vendor: LiveVendor) -> String { VoiceCatalog.defaultId(vendor) }
}

struct AnalysisCredentials: Codable, Equatable {
    var apiKey: String = ""
    var model: String = ""
    var endpoint: String = ""
    var authKind: AuthKind? = nil
    var authName: String = ""
    var authPrefix: String = ""
    var ready: Bool { !apiKey.isEmpty }
}

struct AnalysisSettings: Codable, Equatable {
    var provider: String = "gemini"
    var saved: [String: AnalysisCredentials] = [:]
    var thinking: Bool = false

    func of(_ id: String) -> AnalysisCredentials { saved[id] ?? AnalysisCredentials() }
    var active: AnalysisCredentials { of(provider) }
    var apiKey: String { active.apiKey }
    var model: String { active.model }
    var endpoint: String { active.endpoint }
    var authKind: AuthKind? { active.authKind }
    var authName: String { active.authName }
    var authPrefix: String { active.authPrefix }

    func amend(_ id: String, _ transform: (AnalysisCredentials) -> AnalysisCredentials) -> AnalysisSettings {
        var next = self
        next.saved[id] = transform(of(id))
        return next
    }

    func select(_ id: String) -> AnalysisSettings {
        var next = self
        next.provider = id
        return next
    }
}

struct AssessorSettings: Codable, Equatable {
    var provider: AssessorProvider = .none
    var azureKey: String = ""
    var azureRegion: String = ""
    var xfyunAppId: String = ""
    var xfyunApiKey: String = ""
    var xfyunApiSecret: String = ""

    var azureReady: Bool { !azureKey.isEmpty && !azureRegion.isEmpty }
    var xfyunReady: Bool { !xfyunAppId.isEmpty && !xfyunApiKey.isEmpty && !xfyunApiSecret.isEmpty }

    var resolvedProvider: AssessorProvider {
        if provider == .xfyun, xfyunReady { return .xfyun }
        if azureReady { return .azure }
        if xfyunReady { return .xfyun }
        return .none
    }
}

struct AppSettings: Codable, Equatable {
    var live: LiveSettings = LiveSettings()
    var analysis: AnalysisSettings = AnalysisSettings()
    var assessor: AssessorSettings = AssessorSettings()
    var prompt: String = ""
}

enum Connection: Equatable { case unconfigured, connecting, ready, failed }

struct ChatState: Equatable {
    var turns: [Turn]
    var hint: String? = nil
    var recording: Bool = false
    var connection: Connection = .connecting
    var notice: String? = nil
    /// 顶栏中段显示的会话名：新的一段不显示，接着聊某条历史会话时是那一条的名字。
    var sessionName: String? = nil
    /// 正在接着聊的那条历史会话；nil 表示屏幕上就是手上新的一段。
    var sessionId: String? = nil
}

struct SessionRecord: Codable, Identifiable, Hashable {
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

struct SessionGroup: Identifiable {
    var label: String
    var sessions: [SessionRecord]
    var id: String { label }
}

func groupedByDay(_ records: [SessionRecord], now: Int64 = Int64(Date().timeIntervalSince1970 * 1000)) -> [SessionGroup] {
    let calendar = Calendar.current
    let today = calendar.startOfDay(for: Date(timeIntervalSince1970: TimeInterval(now) / 1000))
    let todayStart = Int64(today.timeIntervalSince1970 * 1000)
    let weekStart = Int64(calendar.date(byAdding: .day, value: -6, to: today)!.timeIntervalSince1970 * 1000)
    let fresh = records.filter { $0.startedAt >= todayStart }
    let week = records.filter { $0.startedAt >= weekStart && $0.startedAt < todayStart }
    let older = records.filter { $0.startedAt < weekStart }
    var groups: [SessionGroup] = []
    if !fresh.isEmpty { groups.append(SessionGroup(label: "今天", sessions: fresh)) }
    if !week.isEmpty { groups.append(SessionGroup(label: "7天内", sessions: week)) }
    if !older.isEmpty { groups.append(SessionGroup(label: "更早", sessions: older)) }
    return groups
}

enum LiveChange { case none, rebuild, voice, model, prompt }

func liveChange(before: AppSettings, after: AppSettings) -> LiveChange {
    if before.live.provider != after.live.provider { return .rebuild }
    if before.live.geminiKey != after.live.geminiKey
        || before.live.openAiKey != after.live.openAiKey
        || before.live.doubaoAppId != after.live.doubaoAppId
        || before.live.doubaoAppKey != after.live.doubaoAppKey
        || before.live.doubaoAccessKey != after.live.doubaoAccessKey
    {
        return .rebuild
    }
    if before.live.voice != after.live.voice { return .voice }
    if before.live.model != after.live.model { return .model }
    if before.prompt != after.prompt { return .prompt }
    return .none
}
