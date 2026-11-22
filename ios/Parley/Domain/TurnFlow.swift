import Foundation

enum TurnPhase: Equatable {
    case offline
    case idle
    case recording(userTurnId: String)
    case awaiting(userTurnId: String?, echoTurnId: String?)
}

struct TurnFlowState {
    var phase: TurnPhase = .offline
    var turns: [Turn] = []
    var cancelArmed: Bool = false
    var cancelledTurnIds: Set<String> = []
}

enum TurnEvent {
    case sessionReady
    case sessionLost
    case pressStart
    case armCancel(Bool)
    case commit
    case cancel
    case silentTurn
    case textTurn(String)
    case userText(String, String?)
    case assistantText(String, String?)
    case audio(Data, String?)
    case interrupted
    case turnComplete
    case captureFailed
    case translationDone(String, String)
    case analysisDone(String, Review, [VocabCard])
    case analysisFailed(String)
    case retryAnalysis(String)
}

enum TurnEffect {
    case startCapture(String)
    case stopCapture
    case sendTurnStart
    case sendTurnEnd
    case sendText(String)
    case cancelTurn(String)
    case play(Data, String?)
    case stopPlayback
    case persist(Turn)
    case delete(String)
    case analyze(String, String)
    case learnVocab(String, [VocabCard])
    case notify(String)
}

struct Reduction {
    var state: TurnFlowState
    var effects: [TurnEffect]
}

enum TurnFlow {
    private static let maxCancelled = 4

    static func reduce(
        _ state: TurnFlowState,
        _ event: TurnEvent,
        nextTurnId: (String) -> String
    ) -> Reduction {
        if event.isLateForCancelled(state) { return Reduction(state: state, effects: []) }
        var effects: [TurnEffect] = []
        let next: TurnFlowState
        switch event {
        case .sessionReady:
            next = state.copy(phase: .idle)
        case .sessionLost:
            next = loseSession(state, &effects)
        case .pressStart:
            switch state.phase {
            case .idle:
                next = startRecording(state, &effects, nextTurnId)
            case .awaiting(let userTurnId, let echoTurnId):
                effects.append(.stopPlayback)
                let settled = settleAwaiting(state, userTurnId: userTurnId, echoTurnId: echoTurnId, &effects, notify: false)
                next = startRecording(settled, &effects, nextTurnId)
            default:
                next = state
            }
        case .armCancel(let armed):
            switch state.phase {
            case .recording: next = state.copy(cancelArmed: armed)
            default: next = state
            }
        case .commit:
            if case .recording(let userTurnId) = state.phase {
                effects.append(.stopCapture)
                effects.append(.sendTurnEnd)
                next = state.copy(phase: .awaiting(userTurnId: userTurnId, echoTurnId: nil), cancelArmed: false)
            } else {
                next = state
            }
        case .cancel:
            if case .recording(let userTurnId) = state.phase {
                effects.append(.stopCapture)
                effects.append(.cancelTurn(userTurnId))
                effects.append(.delete(userTurnId))
                next = state.copy(
                    phase: .idle,
                    turns: state.turns.filter { $0.id != userTurnId },
                    cancelArmed: false,
                    cancelledTurnIds: state.blacklist(userTurnId)
                )
            } else {
                next = state
            }
        case .silentTurn:
            if case .recording(let userTurnId) = state.phase {
                effects.append(.stopCapture)
                effects.append(.cancelTurn(userTurnId))
                effects.append(.delete(userTurnId))
                next = state.copy(
                    phase: .idle,
                    turns: state.turns.filter { $0.id != userTurnId },
                    cancelArmed: false,
                    cancelledTurnIds: state.blacklist(userTurnId)
                )
            } else {
                next = state
            }
        case .textTurn(let text):
            switch state.phase {
            case .offline, .recording:
                next = state
            case .awaiting(let userTurnId, let echoTurnId):
                effects.append(.stopPlayback)
                let settled = settleAwaiting(state, userTurnId: userTurnId, echoTurnId: echoTurnId, &effects, notify: false)
                next = startTextTurn(settled, text, &effects, nextTurnId)
            case .idle:
                next = startTextTurn(state, text, &effects, nextTurnId)
            }
        case .userText(let text, _):
            next = appendUserText(state, text, &effects)
        case .assistantText(let text, _):
            next = appendAssistantText(state, text, &effects, nextTurnId)
        case .audio(let pcm, _):
            if case .awaiting(let userTurnId, let echoTurnId) = state.phase {
                let echoId = echoTurnId ?? nextTurnId("echo")
                effects.append(.play(pcm, echoId))
                next = state.copy(phase: .awaiting(userTurnId: userTurnId, echoTurnId: echoId))
            } else {
                next = state
            }
        case .interrupted:
            effects.append(.stopPlayback)
            next = state
        case .turnComplete:
            next = completeTurn(state, &effects)
        case .captureFailed:
            if case .recording(let userTurnId) = state.phase {
                effects.append(.stopCapture)
                effects.append(.cancelTurn(userTurnId))
                effects.append(.delete(userTurnId))
                effects.append(.notify("麦克风不可用，请检查权限"))
                next = state.copy(
                    phase: .idle,
                    turns: state.turns.filter { $0.id != userTurnId },
                    cancelArmed: false,
                    cancelledTurnIds: state.blacklist(userTurnId)
                )
            } else {
                next = state
            }
        case .analysisDone(let turnId, let review, let vocab):
            if !vocab.isEmpty { effects.append(.learnVocab(turnId, vocab)) }
            next = markAnalysis(state, turnId, &effects) { turn in
                var t = turn
                t.review = review
                t.analysis = .ready
                return t
            }
        case .analysisFailed(let turnId):
            next = markAnalysis(state, turnId, &effects) { turn in
                var t = turn
                t.analysis = .failed
                return t
            }
        case .retryAnalysis(let turnId):
            if let turn = state.turns.first(where: { $0.id == turnId && !$0.text.trimmingCharacters(in: .whitespaces).isEmpty }) {
                effects.append(.analyze(turn.id, turn.text))
                next = markAnalysis(state, turn.id, &effects) { t in
                    var x = t
                    x.analysis = .pending
                    return x
                }
            } else {
                next = state
            }
        case .translationDone(let turnId, let translation):
            next = markAnalysis(state, turnId, &effects) { turn in
                var t = turn
                t.translation = translation
                return t
            }
        }
        return Reduction(state: next, effects: effects)
    }

    private static func startRecording(
        _ state: TurnFlowState,
        _ effects: inout [TurnEffect],
        _ nextTurnId: (String) -> String
    ) -> TurnFlowState {
        let id = nextTurnId("user")
        effects.append(.startCapture(id))
        effects.append(.sendTurnStart)
        return state.copy(phase: .recording(userTurnId: id), cancelArmed: false)
    }

    private static func startTextTurn(
        _ state: TurnFlowState,
        _ text: String,
        _ effects: inout [TurnEffect],
        _ nextTurnId: (String) -> String
    ) -> TurnFlowState {
        let trimmed = text.trimmingCharacters(in: .whitespacesAndNewlines)
        if trimmed.isEmpty { return state }
        let id = nextTurnId("user")
        let turn = Turn(id: id, speaker: .user, text: trimmed)
        effects.append(.persist(turn))
        effects.append(.sendText(trimmed))
        return state.copy(phase: .awaiting(userTurnId: id, echoTurnId: nil), turns: state.turns + [turn], cancelArmed: false)
    }

    private static func loseSession(_ state: TurnFlowState, _ effects: inout [TurnEffect]) -> TurnFlowState {
        switch state.phase {
        case .recording(let userTurnId):
            effects.append(.stopCapture)
            effects.append(.delete(userTurnId))
            return state.copy(phase: .offline, turns: state.turns.filter { $0.id != userTurnId }, cancelArmed: false)
        case .awaiting(let userTurnId, let echoTurnId):
            var dropped = Set<String>()
            if let userTurnId { dropped.insert(userTurnId) }
            if let echoTurnId { dropped.insert(echoTurnId) }
            dropped.forEach { effects.append(.delete($0)) }
            return state.copy(phase: .offline, turns: state.turns.filter { !dropped.contains($0.id) }, cancelArmed: false)
        default:
            return state.copy(phase: .offline)
        }
    }

    private static func appendUserText(_ state: TurnFlowState, _ text: String, _ effects: inout [TurnEffect]) -> TurnFlowState {
        let turnId: String?
        switch state.phase {
        case .recording(let id): turnId = id
        case .awaiting(let id, _): turnId = id
        default: turnId = nil
        }
        guard let turnId else { return state }
        let updated = state.turns.upsertText(id: turnId, speaker: .user, chunk: text)
        if let persisted = updated.first(where: { $0.id == turnId }) {
            effects.append(.persist(persisted))
        }
        return state.copy(turns: updated)
    }

    private static func appendAssistantText(
        _ state: TurnFlowState,
        _ text: String,
        _ effects: inout [TurnEffect],
        _ nextTurnId: (String) -> String
    ) -> TurnFlowState {
        guard case .awaiting(let userTurnId, let echoTurnId) = state.phase else { return state }
        let turnId = echoTurnId ?? nextTurnId("echo")
        let updated = state.turns.upsertText(id: turnId, speaker: .echo, chunk: text)
        if let persisted = updated.first(where: { $0.id == turnId }) {
            effects.append(.persist(persisted))
        }
        return state.copy(phase: .awaiting(userTurnId: userTurnId, echoTurnId: turnId), turns: updated)
    }

    private static func completeTurn(_ state: TurnFlowState, _ effects: inout [TurnEffect]) -> TurnFlowState {
        guard case .awaiting(let userTurnId, let echoTurnId) = state.phase else { return state }
        let settled = settleAwaiting(state, userTurnId: userTurnId, echoTurnId: echoTurnId, &effects, notify: true)
        return settled.copy(phase: .idle, cancelArmed: false)
    }

    private static func settleAwaiting(
        _ state: TurnFlowState,
        userTurnId: String?,
        echoTurnId: String?,
        _ effects: inout [TurnEffect],
        notify: Bool
    ) -> TurnFlowState {
        var turns = state.turns
        var dropped = Set<String>()
        let userTurn = userTurnId.flatMap { id in turns.first { $0.id == id } }
        if userTurn == nil || (userTurn?.text.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty ?? true) {
            if let userTurnId { dropped.insert(userTurnId) }
            if let echoTurnId { dropped.insert(echoTurnId) }
            if notify { effects.append(.notify("没有听清，请重说")) }
        } else if userTurn?.analysis == AnalysisStatus.none, let userTurn {
            effects.append(.analyze(userTurn.id, userTurn.text))
            turns = turns.map { turn in
                guard turn.id == userTurn.id else { return turn }
                var changed = turn
                changed.analysis = .pending
                return changed
            }
            if let persisted = turns.first(where: { $0.id == userTurn.id }) {
                effects.append(.persist(persisted))
            }
        }
        dropped.forEach { effects.append(.delete($0)) }
        return state.copy(turns: turns.filter { !dropped.contains($0.id) })
    }

    private static func markAnalysis(
        _ state: TurnFlowState,
        _ turnId: String,
        _ effects: inout [TurnEffect],
        transform: (Turn) -> Turn
    ) -> TurnFlowState {
        guard let turn = state.turns.first(where: { $0.id == turnId }) else { return state }
        let updated = transform(turn)
        effects.append(.persist(updated))
        return state.copy(turns: state.turns.map { $0.id == turnId ? updated : $0 })
    }
}

private extension TurnEvent {
    func isLateForCancelled(_ state: TurnFlowState) -> Bool {
        let tag: String?
        switch self {
        case .userText(_, let id), .assistantText(_, let id), .audio(_, let id): tag = id
        default: tag = nil
        }
        return tag.map { state.cancelledTurnIds.contains($0) } ?? false
    }
}

private extension TurnFlowState {
    func blacklist(_ turnId: String) -> Set<String> {
        Array(cancelledTurnIds.union([turnId])).suffix(4).reduce(into: Set<String>()) { $0.insert($1) }
    }

    func copy(
        phase: TurnPhase? = nil,
        turns: [Turn]? = nil,
        cancelArmed: Bool? = nil,
        cancelledTurnIds: Set<String>? = nil
    ) -> TurnFlowState {
        TurnFlowState(
            phase: phase ?? self.phase,
            turns: turns ?? self.turns,
            cancelArmed: cancelArmed ?? self.cancelArmed,
            cancelledTurnIds: cancelledTurnIds ?? self.cancelledTurnIds
        )
    }
}

private extension Array where Element == Turn {
    func upsertText(id: String, speaker: Speaker, chunk: String) -> [Turn] {
        if let existing = first(where: { $0.id == id }) {
            return map {
                if $0.id == id {
                    var t = existing
                    t.text += chunk
                    return t
                }
                return $0
            }
        }
        return self + [Turn(id: id, speaker: speaker, text: chunk)]
    }
}
