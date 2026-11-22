import Combine
import Foundation

@MainActor
protocol ParleySession: AnyObject, ObservableObject {
    var state: ChatState { get }
    var meter: VoiceMeter { get }
    var vocab: [VocabCard] { get }
    var sessions: [SessionRecord] { get }
    var checkIn: CheckInWeeks { get }
    var settings: AppSettings { get }
    var providers: [ProviderLine] { get }
    var textProviders: [TextProvider] { get }
    var report: String? { get }
    var voicePreview: VoicePreview? { get }
    var clipPlayer: ClipPlayer { get }
    var defaultPrompt: String { get }

    func onRecordPressStart() -> Bool
    func onRecordArmed(_ armed: Bool)
    func onRecordPressEnd(cancelled: Bool)
    func onRecordHold(_ held: Bool)
    func togglePreviewPlayback()
    func sendText(_ text: String)
    func retryAnalysis(turnId: String)
    func setSaved(turnId: String, saved: Bool)
    func playTurn(turnId: String)
    func copyTurn(turnId: String)
    func deleteTurn(turnId: String)
    func translate(_ text: String) async -> String?
    func speakCard(_ card: VocabCard)
    func speakText(_ text: String)
    func deleteVocab(word: String)
    func restoreVocab(card: VocabCard)
    func resumeSession(_ record: SessionRecord)
    func leaveSession()
    func startNewSession()
    func loadSessions()
    func deleteSession(id: String)
    func renameSession(id: String, name: String)
    func updateSettings(_ transform: (AppSettings) -> AppSettings)
    func probe()
    func loadModels(providerId: String)
    func saveTurnVocab(turnId: String)
    func dismissNotice()
    func showNotice(_ message: String)
}

@MainActor
final class PreviewParleySession: ParleySession {
    @Published var state = ChatState(turns: [])
    let meter = VoiceMeter()
    @Published var vocab: [VocabCard] = []
    @Published var sessions: [SessionRecord] = []
    @Published var checkIn = checkInWeeks(sessions: [], now: 0)
    @Published var settings = AppSettings()
    @Published var providers: [ProviderLine] = []
    @Published var textProviders: [TextProvider] = TextProviderCatalog.all
    @Published var report: String?
    @Published var voicePreview: VoicePreview?
    let clipPlayer = ClipPlayer()

    var defaultPrompt: String { SeedContent.defaultPrompt as String }

    func onRecordPressStart() -> Bool { false }
    func onRecordArmed(_ armed: Bool) {}
    func onRecordPressEnd(cancelled: Bool) {}
    func onRecordHold(_ held: Bool) {}
    func togglePreviewPlayback() {}
    func sendText(_ text: String) {}
    func retryAnalysis(turnId: String) {}
    func setSaved(turnId: String, saved: Bool) {}
    func playTurn(turnId: String) {}
    func copyTurn(turnId: String) {}
    func deleteTurn(turnId: String) {}
    func translate(_ text: String) async -> String? { nil }
    func speakCard(_ card: VocabCard) {}
    func speakText(_ text: String) {}
    func deleteVocab(word: String) {}
    func restoreVocab(card: VocabCard) {}
    func resumeSession(_ record: SessionRecord) {}
    func leaveSession() {}
    func startNewSession() {}
    func loadSessions() {}
    func deleteSession(id: String) {}
    func renameSession(id: String, name: String) {}
    func updateSettings(_ transform: (AppSettings) -> AppSettings) { settings = transform(settings) }
    func probe() {}
    func loadModels(providerId: String) {}
    func saveTurnVocab(turnId: String) {}
    func dismissNotice() { state.notice = nil }
    func showNotice(_ message: String) { state.notice = message }
}