import Foundation

struct TurnAnalysis {
    var review: Review
    var vocab: [VocabCard]
}

protocol TurnAnalyzer: Sendable {
    func analyze(text: String, turnId: String) async -> TurnAnalysis?
    func translate(text: String) async -> String?
    func name(transcript: String) async -> String?
    func memorize(memory: String, material: String) async -> String?
}

protocol PronunciationAssessor: Sendable {
    var configured: Bool { get }
    func assess(pcm: Data, referenceText: String?) async -> PronunciationReview?
}

struct UnconfiguredAssessor: PronunciationAssessor {
    let configured = false
    func assess(pcm: Data, referenceText: String?) async -> PronunciationReview? { nil }
}

func rewriteOf(register: Register, text: String?, gloss: String?) -> Rewrite? {
    guard let text, !text.isEmpty else { return nil }
    return Rewrite(register: register, text: text, gloss: gloss ?? "")
}

func reviewOf(original: String, score: Int, note: String, rewrites: [Rewrite]) -> Review {
    Review(
        original: original,
        grammar: GrammarReview(score: min(100, max(0, score)), note: note),
        pronunciation: nil,
        rewrites: rewrites
    )
}

enum TurnAnalyzerFactory {
    static func of(settings: AnalysisSettings, models: [TextModel] = []) -> TurnAnalyzer {
        OpenAiCompatibleEngine(config: TextEngineConfig.of(settings: settings, models: models))
    }
}