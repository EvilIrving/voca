import SwiftUI

private let dialogWidth: CGFloat = 320
private let realtimeGroup = "realtime"
private let assessorGroup = "assessor"
private let analysisGroupKey = "analysis"
private let providerPrefix = "provider:"

struct SettingsScreen: View {
    var settings: AppSettings
    var providers: [ProviderLine]
    var textProviders: [TextProvider]
    var report: String?
    var defaultPrompt: String
    var onDismiss: @MainActor () -> Void
    var onProbe: @MainActor () -> Void
    var onUpdate: @MainActor ((AppSettings) -> AppSettings) -> Void
    var onLoadModels: @MainActor (String) -> Void

    @State private var editing: String?
    @State private var openModels: String?
    @State private var probeStart: Int64 = 0
    @State private var probeElapsed: Int64?
    @State private var probeVerdict: String?

    var body: some View {
        VStack(spacing: 0) {
            HStack {
                ParleyIconButton(icon: .arrowLeft, contentDescription: "返回对话", onClick: onDismiss, iconSize: 20, tint: Palette.ink2)
                Spacer().frame(width: 4)
                Text("设置").font(Type.display).foregroundStyle(Palette.ink)
                Spacer()
            }
            .padding(.horizontal, Spacing.screen)
            .frame(height: 44)

            ScrollView {
                VStack(spacing: 18) {
                    groupCard(label: "实时对话", lines: providers.filter { $0.group == realtimeGroup })
                    groupCard(label: "发音评测", lines: providers.filter { $0.group == assessorGroup })
                    analysisGroup
                    groupLabel("系统提示词")
                    promptCard
                    probeCard
                }
                .padding(.horizontal, Spacing.screen)
                .padding(.top, 8)
                .padding(.bottom, 24)
            }
        }
        .background(Palette.paper)
        .onChange(of: report) { _, value in
            if probeStart != 0, let value, value != settingsProbePending {
                probeElapsed = Int64(Date().timeIntervalSince1970 * 1000) - probeStart
                probeStart = 0
            }
        }
        .overlay { dialogLayer }
    }

  @ViewBuilder
    private var dialogLayer: some View {
        switch editing {
        case "gemini": LiveDialog(vendor: .gemini, live: settings.live, onUpdate: onUpdate, onDismiss: { editing = nil })
        case "openai": LiveDialog(vendor: .openAi, live: settings.live, onUpdate: onUpdate, onDismiss: { editing = nil })
        case "doubao": LiveDialog(vendor: .doubao, live: settings.live, onUpdate: onUpdate, onDismiss: { editing = nil })
        case "azure": AzureDialog(assessor: settings.assessor, onUpdate: onUpdate, onDismiss: { editing = nil })
        case "xfyun": XfyunDialog(assessor: settings.assessor, onUpdate: onUpdate, onDismiss: { editing = nil })
        case "prompt": PromptDialog(prompt: settings.prompt, defaultPrompt: defaultPrompt, onUpdate: onUpdate, onDismiss: { editing = nil })
        default:
            if let id = editing?.replacingOccurrences(of: providerPrefix, with: ""), let provider = TextProviderCatalog.of(id) {
                ProviderDialog(provider: provider, analysis: settings.analysis, onUpdate: onUpdate, onDismiss: { editing = nil })
            }
        }
    }

    private func groupLabel(_ text: String) -> some View {
        Text(text).font(Type.caption).foregroundStyle(Palette.ink3).frame(maxWidth: .infinity, alignment: .leading).padding(.bottom, 8)
    }

    private func groupCard(label: String, lines: [ProviderLine]) -> some View {
        VStack(alignment: .leading, spacing: 0) {
            groupLabel(label)
            VStack(spacing: 0) {
                ForEach(Array(lines.enumerated()), id: \.element.id) { index, line in
                    if index > 0 { groupDivider }
                    providerRow(line) { editing = line.key }
                }
            }
            .background(Palette.card, in: RoundedRectangle(cornerRadius: Radius.card))
            .overlay(RoundedRectangle(cornerRadius: Radius.card).stroke(Palette.hairSoft, lineWidth: 1))
        }
    }

    private var analysisGroup: some View {
        VStack(alignment: .leading) {
            groupLabel("文本分析")
            ZStack(alignment: .top) {
                VStack(spacing: 0) {
                    ForEach(Array(textProviders.enumerated()), id: \.element.id) { index, provider in
                        if index > 0 { groupDivider }
                        let row = analysisRow(settings.analysis, provider: provider, lines: providers.filter { $0.group == analysisGroupKey })
                        supplierRow(row: row, provider: provider)
                    }
                }
                .background(Palette.card, in: RoundedRectangle(cornerRadius: Radius.card))
                .overlay(RoundedRectangle(cornerRadius: Radius.card).stroke(Palette.hairSoft, lineWidth: 1))
            }
        }
    }

    private func supplierRow(row: AnalysisRow, provider: TextProvider) -> some View {
        let loadable = modelsToLoad(settings.analysis, providerId: provider.id)
        let expanded = openModels == provider.id
        return ZStack(alignment: .top) {
            Pressable(shape: AnyShape(Rectangle()), onClick: { editing = "\(providerPrefix)\(provider.id)" }) {
                HStack {
                    VStack(alignment: .leading) {
                        Text(provider.label).font(Type.uiStrong)
                        Text(row.detail).font(Type.mono()).foregroundStyle(expanded ? Palette.clayDeep : Palette.ink3).lineLimit(1)
                    }
                    Spacer()
                    Text(row.status.uiLabel).font(Type.caption).foregroundStyle(row.status.uiTint)
                    Spacer().frame(width: 10)
                    statusDot(row.status.linked)
                    if !loadable.isEmpty {
                        Spacer().frame(width: 6)
                        Pressable(
                            shape: AnyShape(Circle()),
                            contentDescription: expanded ? "收起模型" : "选择模型",
                            onClick: {
                            if openModels != provider.id { loadable.forEach(onLoadModels) }
                            openModels = openModels == provider.id ? nil : provider.id
                        }
                        ) {
                            GlyphIcon(icon: .chevRight, tint: expanded ? Palette.clayDeep : Palette.ink4)
                                .frame(width: 14, height: 14)
                                .rotationEffect(.degrees(expanded ? -90 : 90))
                        }
                        .frame(width: 24, height: 24)
                    }
                }
                .padding(.horizontal, 14)
                .frame(height: configRowHeight)
            }
            if expanded && !loadable.isEmpty {
                ModelMenu(
                    spec: modelChoices(provider: provider, analysis: settings.analysis),
                    onPick: { id in
                        onUpdate { $0.withAnalysisModel(provider: provider, modelId: id) ?? $0 }
                        openModels = nil
                    },
                    onDismiss: { openModels = provider.id }
                )
                .offset(y: configRowHeight)
            }
        }
    }

    private var promptCard: some View {
        Pressable(shape: AnyShape(Rectangle()), onClick: { editing = "prompt" }) {
            VStack(alignment: .leading, spacing: 8) {
                Text(settings.prompt).font(Type.promptProse).foregroundStyle(Palette.ink2).lineLimit(5)
                HStack {
                    Spacer()
                    GlyphIcon(icon: .edit, tint: Palette.clayDeep).frame(width: 13, height: 13)
                    Text("编辑提示词").font(Type.uiStrong).foregroundStyle(Palette.clayDeep)
                }
            }
            .padding(14)
        }
        .background(Palette.card, in: RoundedRectangle(cornerRadius: Radius.card))
        .overlay(RoundedRectangle(cornerRadius: Radius.card).stroke(Palette.hairSoft, lineWidth: 1))
    }

    private var probeCard: some View {
        let shown = (settings.analysis.provider == TextProviderCatalog.geminiId ? report : nil) ?? probeVerdict ?? report
        return Pressable(shape: AnyShape(Rectangle()), onClick: {
            probeVerdict = analysisVerdict(settings.analysis)
            probeStart = Int64(Date().timeIntervalSince1970 * 1000)
            onProbe()
        }) {
            HStack {
                GlyphIcon(icon: .sync, tint: Palette.ink3).frame(width: 16, height: 16)
                Spacer().frame(width: Spacing.iconText)
                VStack(alignment: .leading) {
                    HStack {
                        Text("连接自检").font(Type.uiStrong)
                        Spacer()
                        if let probeElapsed { Text(formatProbeElapsed(probeElapsed)).font(Type.mono()) }
                    }
                    if let shown {
                        Text(shown).font(Type.caption).foregroundStyle(Palette.ink3).lineLimit(1)
                    }
                }
                GlyphIcon(icon: .chevRight, tint: Palette.ink4).frame(width: 14, height: 14)
            }
            .padding(.horizontal, 14)
            .frame(height: configRowHeight)
        }
        .background(Palette.card, in: RoundedRectangle(cornerRadius: Radius.card))
        .overlay(RoundedRectangle(cornerRadius: Radius.card).stroke(Palette.hairSoft, lineWidth: 1))
    }

    private func providerRow(_ line: ProviderLine, onClick: @escaping () -> Void) -> some View {
        Pressable(shape: AnyShape(Rectangle()), enabled: line.editable, onClick: onClick) {
            HStack {
                VStack(alignment: .leading) {
                    Text(line.name).font(Type.uiStrong).lineLimit(1)
                    if !line.detail.isEmpty {
                        Text(line.detail).font(Type.mono()).lineLimit(1)
                    }
                }
                Spacer()
                Text(line.status.uiLabel).font(Type.caption).foregroundStyle(line.status.uiTint)
                Spacer().frame(width: 10)
                statusDot(line.status.linked)
            }
            .padding(.horizontal, 14)
            .frame(height: configRowHeight)
        }
    }

    private var groupDivider: some View {
        Rectangle().fill(Palette.hairSoft).frame(height: 1).padding(.horizontal, 14)
    }

    private func statusDot(_ linked: Bool) -> some View {
        ZStack {
            Circle().fill(linked ? Palette.clayTint : .clear).frame(width: 14, height: 14)
            Circle().fill(linked ? Palette.clay : .clear).frame(width: linked ? 8 : 10)
                .overlay(Circle().stroke(Palette.ink4, lineWidth: linked ? 0 : 1.5))
        }
    }
}

// MARK: - Dialogs

@MainActor
private struct LiveDialog: View {
    var vendor: LiveVendor
    var live: LiveSettings
    var onUpdate: @MainActor ((AppSettings) -> AppSettings) -> Void
    var onDismiss: @MainActor () -> Void

    @State private var drafts: [String: String] = [:]
    @State private var voice: String = ""
    @State private var voiceOpen = false

    var body: some View {
        SettingsDialog(title: vendor.label, onSave: {
            onUpdate { $0.liveSaved(vendor: vendor, values: drafts, voice: voice) }
            onDismiss()
        }, onDismiss: { onDismiss() }) {
            let fields = liveFields(live, vendor: vendor)
            ForEach(fields, id: \.key) { field in
                editField(label: field.label, text: Binding(get: { drafts[field.key] ?? field.value }, set: { drafts[field.key] = $0 }), secret: field.secret)
                Spacer().frame(height: 12)
            }
            Text("音色").font(Type.caption).foregroundStyle(Palette.ink3)
            Spacer().frame(height: 6)
            ZStack(alignment: .top) {
                Pressable(
                    shape: AnyShape(RoundedRectangle(cornerRadius: Radius.control)),
                    resting: Palette.tint,
                    contentDescription: voiceOpen ? "收起音色" : "选择音色",
                    onClick: { voiceOpen.toggle() }
                ) {
                    HStack {
                        VStack(alignment: .leading) {
                            Text(voice).font(Type.uiStrong)
                            if let style = voiceStyle(vendor, id: voice) {
                                Text(style).font(Type.clock)
                            }
                        }
                        Spacer()
                        GlyphIcon(icon: .chevRight, tint: voiceOpen ? Palette.clayDeep : Palette.ink4)
                            .frame(width: 14, height: 14)
                            .rotationEffect(.degrees(voiceOpen ? -90 : 90))
                    }
                    .padding(.horizontal, 12)
                    .frame(height: optionRowHeight)
                }
                if voiceOpen {
                    ModelMenu(spec: voiceMenu(vendor: vendor, selected: voice), onPick: { voice = $0; voiceOpen = false }, onDismiss: { voiceOpen = false })
                        .offset(y: optionRowHeight)
                }
            }
        }
        .onAppear {
            let fields = liveFields(live, vendor: vendor)
            drafts = Dictionary(uniqueKeysWithValues: fields.map { ($0.key, $0.value) })
            voice = currentVoice(live, vendor: vendor)
        }
    }
}

@MainActor
private struct AzureDialog: View {
    var assessor: AssessorSettings
    var onUpdate: @MainActor ((AppSettings) -> AppSettings) -> Void
    var onDismiss: @MainActor () -> Void
    @State private var key = ""
    @State private var region = ""
    var body: some View {
        SettingsDialog(title: AssessorProvider.azure.label, onSave: {
            onUpdate {
                AppSettings(live: $0.live, analysis: $0.analysis, assessor: $0.assessor.withAzure(key: key, region: region), prompt: $0.prompt)
            }
            onDismiss()
        }, onDismiss: { onDismiss() }) {
            editField(label: "密钥", text: $key, secret: true)
            Spacer().frame(height: 12)
            editField(label: "区域", text: $region, secret: false)
        }
        .onAppear { key = assessor.azureKey; region = assessor.azureRegion }
    }
}

@MainActor
private struct XfyunDialog: View {
    var assessor: AssessorSettings
    var onUpdate: @MainActor ((AppSettings) -> AppSettings) -> Void
    var onDismiss: @MainActor () -> Void
    @State private var appId = ""
    @State private var apiKey = ""
    @State private var apiSecret = ""
    var body: some View {
        SettingsDialog(title: AssessorProvider.xfyun.label, onSave: {
            onUpdate {
                AppSettings(live: $0.live, analysis: $0.analysis, assessor: $0.assessor.withXfyun(appId: appId, apiKey: apiKey, apiSecret: apiSecret), prompt: $0.prompt)
            }
            onDismiss()
        }, onDismiss: { onDismiss() }) {
            editField(label: "AppID", text: $appId, secret: false)
            Spacer().frame(height: 12)
            editField(label: "APIKey", text: $apiKey, secret: true)
            Spacer().frame(height: 12)
            editField(label: "APISecret", text: $apiSecret, secret: true)
        }
        .onAppear { appId = assessor.xfyunAppId; apiKey = assessor.xfyunApiKey; apiSecret = assessor.xfyunApiSecret }
    }
}

@MainActor
private struct PromptDialog: View {
    var prompt: String
    var defaultPrompt: String
    var onUpdate: @MainActor ((AppSettings) -> AppSettings) -> Void
    var onDismiss: @MainActor () -> Void
    @State private var text = ""
    var body: some View {
        SettingsDialog(title: "系统提示词", onSave: {
            onUpdate { AppSettings(live: $0.live, analysis: $0.analysis, assessor: $0.assessor, prompt: text) }
            onDismiss()
        }, onDismiss: { onDismiss() }) {
            TextEditor(text: $text)
                .font(Type.promptProse)
                .frame(minHeight: 232, maxHeight: 320)
                .padding(12)
                .background(Palette.tint, in: RoundedRectangle(cornerRadius: Radius.control))
            Pressable(shape: AnyShape(Rectangle()), onClick: { text = defaultPrompt }) {
                Text("恢复默认").font(Type.uiStrong).foregroundStyle(Palette.clayDeep).frame(height: 32, alignment: .leading)
            }
        }
        .onAppear { text = prompt }
    }
}

@MainActor
private struct ProviderDialog: View {
    var provider: TextProvider
    var analysis: AnalysisSettings
    var onUpdate: @MainActor ((AppSettings) -> AppSettings) -> Void
    var onDismiss: @MainActor () -> Void
    @State private var apiKey = ""
    @State private var endpoint = ""
    @State private var model = ""
    @State private var name = ""
    @State private var prefix = ""
    @State private var kind: AuthKind = .bearer

    var body: some View {
        SettingsDialog(title: provider.label, onSave: {
            onUpdate {
                $0.withAnalysisCredentials(provider: provider, apiKey: apiKey, model: model, endpoint: endpoint, authKind: kind, authName: name, authPrefix: prefix)
            }
            onDismiss()
        }, onDismiss: { onDismiss() }) {
            if provider.custom {
                editField(label: "端点", text: $endpoint, secret: false)
                Spacer().frame(height: 12)
                editField(label: "模型", text: $model, secret: false)
                Spacer().frame(height: 12)
                authKindChips
                Spacer().frame(height: 12)
            }
            editField(label: "密钥", text: $apiKey, secret: true)
            if provider.custom && kind != .bearer {
                Spacer().frame(height: 12)
                editField(label: kind == .header ? "请求头" : "查询参数", text: $name, secret: false)
            }
            if provider.custom && kind == .header {
                Spacer().frame(height: 12)
                editField(label: "前缀", text: $prefix, secret: false)
            }
        }
        .onAppear {
            let own = analysis.of(provider.id)
            apiKey = own.apiKey
            endpoint = own.endpoint
            model = own.model
            name = own.authName.isEmpty ? provider.auth.name : own.authName
            prefix = own.authPrefix.isEmpty ? provider.auth.prefix : own.authPrefix
            kind = own.authKind ?? provider.auth.kind
        }
    }

    private var authKindChips: some View {
        HStack(spacing: 8) {
            ForEach([AuthKind.bearer, .header, .query], id: \.self) { item in
                Pressable(shape: AnyShape(RoundedRectangle(cornerRadius: Radius.chip)), resting: item == kind ? Palette.clayTint : Palette.tint, onClick: { kind = item }) {
                    Text(item.label).font(Type.caption).foregroundStyle(item == kind ? Palette.clayDeep : Palette.ink3)
                        .padding(.horizontal, 12).padding(.vertical, 7)
                }
            }
        }
    }
}

@MainActor
private struct SettingsDialog<Content: View>: View {
    var title: String
    var onSave: @MainActor () -> Void
    var onDismiss: @MainActor () -> Void
    @ViewBuilder var content: () -> Content

    var body: some View {
        ZStack {
            Scrim(onDismiss: onDismiss)
            VStack(alignment: .leading, spacing: 16) {
                Text(title).font(Type.display)
                content()
                Pressable(shape: AnyShape(Capsule()), resting: Palette.clay, onClick: { onSave() }) {
                    Text("保存").font(Type.uiStrong).foregroundStyle(.white).frame(maxWidth: .infinity).frame(height: optionRowHeight)
                }
            }
            .padding(20)
            .frame(maxWidth: dialogWidth)
            .background(Palette.card, in: RoundedRectangle(cornerRadius: Radius.sheet))
            .padding(.horizontal, Spacing.screen)
        }
    }
}

private func editField(label: String, text: Binding<String>, secret: Bool) -> some View {
    VStack(alignment: .leading, spacing: 6) {
        Text(label).font(Type.caption).foregroundStyle(Palette.ink3)
        Group {
            if secret {
                SecureField("", text: text)
            } else {
                TextField("", text: text)
            }
        }
        .font(Type.uiFont)
        .foregroundStyle(Palette.ink)
        .padding(.horizontal, 12)
        .padding(.vertical, 12)
        .frame(minHeight: optionRowHeight)
        .background(Palette.tint, in: RoundedRectangle(cornerRadius: Radius.control))
    }
}