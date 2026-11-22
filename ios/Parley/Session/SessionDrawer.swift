import SwiftUI
import UIKit

private let untitledSession = "本次会话"

struct SessionDrawerModel {
    var groups: [SessionGroup]
    var currentId: String?

    func isCurrent(_ record: SessionRecord) -> Bool { record.id == currentId }
}

func sessionDrawerModel(sessions: [SessionRecord], viewingId: String?, query: String = "", now: Int64 = Int64(Date().timeIntervalSince1970 * 1000)) -> SessionDrawerModel {
    let keyword = query.trimmingCharacters(in: .whitespaces)
    let matched = keyword.isEmpty ? sessions : sessions.filter { $0.name.localizedCaseInsensitiveContains(keyword) }
    return SessionDrawerModel(groups: groupedByDay(matched, now: now), currentId: viewingId)
}

struct DrawerScaffold<Drawer: View, Content: View>: View {
    @ObservedObject var state: DrawerState
    @ViewBuilder var drawer: () -> Drawer
    @ViewBuilder var content: () -> Content

    var body: some View {
        GeometryReader { geo in
            let width = drawerWidth(geo.size.width)
            let widthPx = width

            ZStack(alignment: .leading) {
                drawer()
                    .frame(width: width)
                    .frame(maxHeight: .infinity, alignment: .top)
                    .offset(x: widthPx * (state.fraction - 1))

                ZStack {
                    content()
                    if state.fraction > 0.001 {
                        Color.white.opacity(SessionDrawerSpec.veilAlpha * state.fraction)
                            .onTapGesture { state.close() }
                    }
                }
                .background(Palette.paper)
                .clipShape(RoundedRectangle(cornerRadius: SessionDrawerSpec.cardRadius(progress: state.fraction)))
                .shadow(color: Palette.shade, radius: SessionDrawerSpec.cardShadow * state.fraction)
                .scaleEffect(1 - (1 - SessionDrawerSpec.contentScale) * state.fraction, anchor: .center)
                .offset(x: widthPx * state.fraction)
            }
            .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .leading)
            .background {
                DrawerPanCatcher(
                    widthPx: widthPx,
                    guardBottom: SessionDrawerSpec.swipeGuardBottom,
                    fraction: state.fraction,
                    onDrag: { state.dragTo($0) },
                    onSettle: { velocity in
                        if drawerTarget(progress: state.fraction, velocity: velocity, threshold: SessionDrawerSpec.velocityThreshold) {
                            state.open()
                        } else {
                            state.close()
                        }
                    }
                )
                .frame(maxWidth: .infinity, maxHeight: .infinity)
                .allowsHitTesting(false)
            }
        }
    }
}

/// 横滑挂在没被变换的那一层的祖先上，自己不参与命中。底栏那条录音带让开。
private struct DrawerPanCatcher: UIViewRepresentable {
    var widthPx: CGFloat
    var guardBottom: CGFloat
    var fraction: CGFloat
    var onDrag: (CGFloat) -> Void
    var onSettle: (CGFloat) -> Void

    func makeCoordinator() -> Coordinator {
        Coordinator()
    }

    func makeUIView(context: Context) -> DrawerPanView {
        let view = DrawerPanView()
        view.coordinator = context.coordinator
        context.coordinator.view = view
        apply(view, context: context)
        return view
    }

    func updateUIView(_ uiView: DrawerPanView, context: Context) {
        apply(uiView, context: context)
    }

    private func apply(_ view: DrawerPanView, context: Context) {
        context.coordinator.widthPx = widthPx
        context.coordinator.guardBottom = guardBottom
        context.coordinator.fraction = fraction
        context.coordinator.onDrag = onDrag
        context.coordinator.onSettle = onSettle
        view.coordinator = context.coordinator
    }

    final class Coordinator {
        weak var view: DrawerPanView?
        var widthPx: CGFloat = 1
        var guardBottom: CGFloat = 96
        var fraction: CGFloat = 0
        var origin: CGFloat = 0
        var onDrag: (CGFloat) -> Void = { _ in }
        var onSettle: (CGFloat) -> Void = { _ in }
    }
}

private final class DrawerPanView: UIView, UIGestureRecognizerDelegate {
    var coordinator: DrawerPanCatcher.Coordinator?
    private let pan = UIPanGestureRecognizer()
    private weak var host: UIView?

    override init(frame: CGRect) {
        super.init(frame: frame)
        isUserInteractionEnabled = false
        backgroundColor = .clear
        pan.addTarget(self, action: #selector(handlePan))
        pan.delegate = self
        pan.cancelsTouchesInView = false
        pan.delaysTouchesBegan = false
        pan.delaysTouchesEnded = false
    }

    required init?(coder: NSCoder) { fatalError() }

    override func didMoveToWindow() {
        super.didMoveToWindow()
        attach()
    }

    override func didMoveToSuperview() {
        super.didMoveToSuperview()
        attach()
    }

    private func attach() {
        guard window != nil, let superview else { return }
        if host === superview, superview.gestureRecognizers?.contains(pan) == true { return }
        host?.removeGestureRecognizer(pan)
        superview.addGestureRecognizer(pan)
        host = superview
    }

    override func willMove(toWindow newWindow: UIWindow?) {
        super.willMove(toWindow: newWindow)
        if newWindow == nil {
            host?.removeGestureRecognizer(pan)
            host = nil
        }
    }

    override func hitTest(_ point: CGPoint, with event: UIEvent?) -> UIView? { nil }

    override func gestureRecognizerShouldBegin(_ gestureRecognizer: UIGestureRecognizer) -> Bool {
        let point = pan.location(in: self)
        if point.y > bounds.height - (coordinator?.guardBottom ?? 96) { return false }
        let translation = pan.translation(in: self)
        if abs(translation.x) < abs(translation.y) { return false }
        let fraction = coordinator?.fraction ?? 0
        if translation.x < 0 && fraction <= 0.001 { return false }
        return true
    }

    func gestureRecognizer(
        _ gestureRecognizer: UIGestureRecognizer,
        shouldRecognizeSimultaneouslyWith otherGestureRecognizer: UIGestureRecognizer
    ) -> Bool {
        true
    }

    func gestureRecognizer(_ gestureRecognizer: UIGestureRecognizer, shouldReceive touch: UITouch) -> Bool {
        let point = touch.location(in: self)
        return point.y <= bounds.height - (coordinator?.guardBottom ?? 96)
    }

    @objc private func handlePan(_ gesture: UIPanGestureRecognizer) {
        guard let coordinator else { return }
        let translation = gesture.translation(in: self)
        switch gesture.state {
        case .began:
            coordinator.origin = coordinator.fraction
            coordinator.onDrag(coordinator.origin + translation.x / max(coordinator.widthPx, 1))
        case .changed:
            coordinator.onDrag(coordinator.origin + translation.x / max(coordinator.widthPx, 1))
        case .ended, .cancelled, .failed:
            coordinator.onSettle(gesture.velocity(in: self).x)
        default:
            break
        }
    }
}

struct SessionDrawer: View {
    var sessions: [SessionRecord]
    var viewingId: String?
    var open: Bool
    var onNewSession: @MainActor () -> Void
    var onPick: @MainActor (String) -> Void
    var onSettings: @MainActor () -> Void
    var onDelete: @MainActor (String) -> Void = { _ in }
    var onRename: @MainActor (String, String) -> Void = { _, _ in }

    @State private var query = ""
    @State private var searching = false
    @State private var renaming: SessionRecord?
    @State private var renameText = ""
    @FocusState private var searchFocused: Bool

    var body: some View {
        let model = sessionDrawerModel(sessions: sessions, viewingId: viewingId, query: query)

        VStack(spacing: 0) {
            if searching {
                searchField
            } else {
                HStack {
                    Text("会话").font(Type.display).foregroundStyle(Palette.ink)
                    Spacer()
                    ParleyIconButton(
                        icon: .search,
                        contentDescription: "搜索会话",
                        onClick: { searching = true },
                        size: SessionDrawerSpec.searchButton,
                        iconSize: SessionDrawerSpec.searchIcon,
                        tint: Palette.ink2
                    )
                }
                .frame(height: SessionDrawerSpec.headerHeight)
            }

            Spacer().frame(height: SessionDrawerSpec.headerGap)

            newSessionPill

            Spacer().frame(height: SessionDrawerSpec.listGap)

            if model.groups.isEmpty {
                Text(query.isEmpty ? "还没有会话" : "没有匹配的会话")
                    .font(Type.caption)
                    .foregroundStyle(Palette.ink4)
                    .padding(.leading, 4)
                    .padding(.top, 12)
                Spacer()
            } else {
                ScrollView {
                    LazyVStack(alignment: .leading, spacing: 0) {
                        ForEach(model.groups) { group in
                            Text(group.label)
                                .font(Type.caption)
                                .foregroundStyle(Palette.ink4)
                                .padding(.leading, 4)
                                .padding(.top, 12)
                                .padding(.bottom, 4)
                            ForEach(group.sessions) { record in
                                sessionRow(record, current: model.isCurrent(record))
                            }
                        }
                    }
                }
            }

            HStack {
                Spacer()
                ParleyIconButton(
                    icon: .settings,
                    contentDescription: "设置",
                    onClick: { onSettings() },
                    size: SessionDrawerSpec.settingsButton,
                    iconSize: SessionDrawerSpec.settingsIcon,
                    tint: Palette.ink2
                )
            }
            .padding(.vertical, SessionDrawerSpec.dockPadding)
        }
        .padding(.horizontal, Spacing.drawer)
        .frame(maxHeight: .infinity)
        .background(Palette.paper)
        .onChange(of: open) { _, isOpen in
            if !isOpen { searching = false; query = "" }
        }
        .onChange(of: searching) { _, on in if on { searchFocused = true } }
    }

    private var searchField: some View {
        HStack {
            GlyphIcon(icon: .search, tint: Palette.ink4).frame(width: 16, height: 16)
            Spacer().frame(width: Spacing.iconText)
            ZStack(alignment: .leading) {
                if query.isEmpty {
                    Text("搜索会话").font(Type.uiFont).foregroundStyle(Palette.ink4)
                }
                TextField("", text: $query)
                    .font(Type.uiFont)
                    .foregroundStyle(Palette.ink)
                    .focused($searchFocused)
            }
            ParleyIconButton(icon: .dismiss, contentDescription: "收起搜索", onClick: { searching = false; query = "" }, size: 28, iconSize: 14, tint: Palette.ink4)
        }
        .padding(.leading, 12)
        .padding(.trailing, 2)
        .frame(height: SessionDrawerSpec.searchFieldHeight)
        .background(Palette.card, in: Capsule())
        .overlay(Capsule().stroke(Palette.hairSoft, lineWidth: 1))
    }

    private var newSessionPill: some View {
        Pressable(shape: AnyShape(Capsule()), resting: Palette.card, border: Palette.hairSoft, onClick: onNewSession) {
            HStack {
                GlyphIcon(icon: .chatAdd, tint: Palette.ink).frame(width: SessionDrawerSpec.newGlyph, height: SessionDrawerSpec.newGlyph)
                Spacer().frame(width: 10)
                Text("新会话").font(Type.title).foregroundStyle(Palette.ink)
                Spacer()
            }
            .padding(.horizontal, 18)
        }
        .frame(height: SessionDrawerSpec.newRowHeight)
    }

    private func sessionRow(_ record: SessionRecord, current: Bool) -> some View {
        Pressable(
            shape: AnyShape(RoundedRectangle(cornerRadius: Radius.drawerRow)),
            resting: current ? Palette.clayTint : .clear,
            onClick: { onPick(record.id) }
        ) {
            Text(record.name.isEmpty ? untitledSession : record.name)
                .font(current ? Type.uiStrong : Type.uiStrong)
                .foregroundStyle(current ? Palette.ink : Palette.ink2)
                .lineLimit(1)
                .padding(.horizontal, 12)
                .frame(maxWidth: .infinity, alignment: .leading)
        }
        .padding(.vertical, 2)
        .frame(height: SessionDrawerSpec.rowHeight)
        .contextMenu {
            Button {
                renaming = record
                renameText = record.name
            } label: {
                Label("重命名", systemImage: "pencil")
            }
            Button(role: .destructive) {
                onDelete(record.id)
            } label: {
                Label("删除", systemImage: "trash")
            }
        }
        .alert("重命名会话", isPresented: Binding(get: { renaming != nil }, set: { if !$0 { renaming = nil } })) {
            TextField("会话名称", text: $renameText)
            Button("取消", role: .cancel) { renaming = nil }
            Button("保存") {
                if let target = renaming {
                    onRename(target.id, renameText.trimmingCharacters(in: .whitespacesAndNewlines))
                }
                renaming = nil
            }
        }
    }
}