import SwiftUI
import AVFAudio

private enum Panel { case none, vocab, settings }
private let noticeMs: Double = 0.18

struct RootView<VM: ParleySession>: View {
    @ObservedObject private var vm: VM
    @StateObject private var drawer = DrawerState()

    @State private var panel = Panel.none
    @State private var reviewTurn: Turn?
    @State private var carriedNotice: String?
    @State private var noticeToken = 0

    /// 屏幕上是不是接着某条历史会话聊：那一条的名字与标识都由视图模型一处报上来。
    private var continuing: Bool { vm.state.sessionId != nil }
    private var drawerShown: Bool { drawer.current > 0.001 }

    init(store: VM) {
        self.vm = store
    }

    init(vm: VM) {
        self.vm = vm
    }

    var body: some View {
        let vocabToday = vocabPage(cards: vm.vocab, todayStart: startOfLocalDay(), filter: .today).counts.today

        ZStack {
            DrawerScaffold(state: drawer) {
                SessionDrawer(
                    sessions: vm.sessions,
                    viewingId: vm.state.sessionId,
                    open: drawerShown,
                    onNewSession: {
                        drawer.close()
                        startNewSession()
                    },
                    onPick: { id in
                        drawer.close()
                        if let entry = vm.sessions.first(where: { $0.id == id }) {
                            vm.resumeSession(entry)
                        }
                    },
                    onSettings: {
                        drawer.close()
                        panel = .settings
                    },
                    onDelete: { id in
                        vm.deleteSession(id: id)
                    },
                    onRename: { id, name in
                        vm.renameSession(id: id, name: name)
                    }
                )
            } content: {
                ChatScreen(
                    state: vm.state,
                    turns: vm.state.turns,
                    onVocabClick: { panel = .vocab },
                    onSessionNameTap: { drawer.open() },
                    onReviewClick: { reviewTurn = $0 },
                    onRetryAnalysis: vm.retryAnalysis,
                    vocabToday: vocabToday,
                    meter: vm.meter,
                    onRecordPressStart: beginRecording,
                    onRecordArmed: vm.onRecordArmed,
                    onRecordPressEnd: vm.onRecordPressEnd,
                    sessionName: vm.state.sessionName ?? "",
                    continuing: continuing,
                    onExitSession: vm.leaveSession,
                    onNewSession: startNewSession,
                    onSendText: vm.sendText,
                    onSaveTurn: vm.setSaved,
                    onPlayTurn: vm.playTurn,
                    onTranslateTurn: vm.translate,
                    onCopyTurn: vm.copyTurn,
                    onDeleteTurn: vm.deleteTurn,
                    voicePreview: vm.voicePreview,
                    clipPlayer: vm.clipPlayer,
                    onRecordHold: vm.onRecordHold,
                    onPreviewToggle: vm.togglePreviewPlayback
                )
            }

            if reviewTurn != nil {
                if let turn = reviewTurn {
                    ReviewSheet(
                        turn: turn,
                        onDismiss: { reviewTurn = nil },
                        onSaveToVocab: {
                            if let turn = reviewTurn { vm.saveTurnVocab(turnId: turn.id) }
                            reviewTurn = nil
                        },
                        onContinue: { reviewTurn = nil },
                        onReplay: { reviewTurn.map { vm.playTurn(turnId: $0.id) } },
                        onSpeak: vm.speakText
                    )
                    .transition(.move(edge: .bottom).combined(with: .opacity))
                }
            }

            if panel == .vocab {
                VocabScreen(
                    cards: vm.vocab,
                    onDismiss: { panel = .none },
                    onSpeak: vm.speakCard,
                    onDelete: vm.deleteVocab,
                    onRestore: vm.restoreVocab
                )
                .transition(.move(edge: .bottom))
            }

            if panel == .settings {
                SettingsScreen(
                    settings: vm.settings,
                    providers: vm.providers,
                    textProviders: vm.textProviders,
                    checkIn: vm.checkIn,
                    report: vm.report,
                    defaultPrompt: vm.defaultPrompt,
                    onDismiss: { panel = .none },
                    onProbe: vm.probe,
                    onUpdate: vm.updateSettings,
                    onLoadModels: vm.loadModels
                )
                .transition(.move(edge: .trailing).combined(with: .opacity))
            }

            noticeOverlay
        }
        .background(Palette.paper)
        .onChange(of: drawerShown) { _, shown in
            if shown { vm.dismissNotice(); vm.loadSessions() }
        }
        .onChange(of: panel) { _, shown in
            // 设置页里的打卡热力图读收段记录，和抽屉用的是同一份加载。
            if shown == .settings { vm.loadSessions() }
        }
        .onChange(of: vm.state.notice) { _, notice in
            guard notice != nil else { return }
            noticeToken += 1
            let token = noticeToken
            Task {
                try? await Task.sleep(for: .seconds(2))
                if noticeToken == token { vm.dismissNotice() }
            }
        }
        .onKeyPress(.escape) {
            handleBack() ? .handled : .ignored
        }
    }

    private var noticeOverlay: some View {
        let noticeText = vm.state.notice ?? carriedNotice
        return VStack {
            if let noticeText, vm.state.notice != nil {
                Text(noticeText)
                    .font(Type.caption)
                    .foregroundStyle(Palette.ink2)
                    .padding(.horizontal, 14)
                    .padding(.vertical, 9)
                    .background(Palette.tint, in: Capsule())
                    .overlay(Capsule().stroke(Palette.hairSoft, lineWidth: 1))
                    .padding(.top, 52)
                    .onTapGesture { vm.dismissNotice() }
                    .onAppear { carriedNotice = noticeText }
                    .transition(.opacity.combined(with: .move(edge: .top)))
            }
            Spacer()
        }
        .animation(.easeInOut(duration: noticeMs), value: vm.state.notice)
    }

    private func startNewSession() {
        vm.startNewSession()
    }

    private func beginRecording() -> Bool {
        switch AVAudioApplication.shared.recordPermission {
        case .granted:
            return vm.onRecordPressStart()
        case .denied:
            vm.showNotice("请授权麦克风权限")
            return false
        case .undetermined:
            AVAudioApplication.requestRecordPermission { granted in
                Task { @MainActor in
                    vm.showNotice(granted ? "麦克风已授权" : "请授权麦克风权限")
                }
            }
            return false
        @unknown default:
            vm.showNotice("请授权麦克风权限")
            return false
        }
    }

    @discardableResult
    private func handleBack() -> Bool {
        if reviewTurn != nil {
            reviewTurn = nil
        } else if drawerShown {
            drawer.close()
        } else if continuing {
            vm.leaveSession()
        } else if panel != .none {
            panel = .none
        } else {
            return false
        }
        return true
    }
}