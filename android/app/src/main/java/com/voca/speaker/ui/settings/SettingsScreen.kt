package com.voca.speaker.ui.settings

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateMap
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.voca.speaker.data.analysis.TextProvider
import com.voca.speaker.domain.AnalysisCredentials
import com.voca.speaker.domain.AnalysisSettings
import com.voca.speaker.domain.AppSettings
import com.voca.speaker.domain.AssessorProvider
import com.voca.speaker.domain.AssessorSettings
import com.voca.speaker.domain.AuthKind
import com.voca.speaker.domain.CheckInWeeks
import com.voca.speaker.domain.LiveSettings
import com.voca.speaker.domain.LiveVendor
import com.voca.speaker.domain.ProviderGroup
import com.voca.speaker.domain.ProviderLine
import com.voca.speaker.domain.key
import com.voca.speaker.ui.component.VocaIconButton
import com.voca.speaker.ui.component.Pressable
import com.voca.speaker.ui.icon.FluentIcons
import com.voca.speaker.domain.monthLabels
import com.voca.speaker.domain.readout
import com.voca.speaker.domain.summary
import com.voca.speaker.ui.theme.Palette
import com.voca.speaker.ui.theme.Radius
import com.voca.speaker.ui.theme.Spacing
import com.voca.speaker.ui.theme.Type

/** FluentIcons 里没有返回图元，这一支按 Fluent 线宽自己画。 */
private val BackArrow: ImageVector = ImageVector.Builder(
    name = "back",
    defaultWidth = 20.dp,
    defaultHeight = 20.dp,
    viewportWidth = 20f,
    viewportHeight = 20f,
).apply {
    addPath(
        pathData = PathParser().parsePathString("M9.2 4.6 3.6 10.2 9.2 15.8 M3.9 10.2 H16.4").toNodes(),
        stroke = SolidColor(Color.Black),
        strokeLineWidth = 1.6f,
        strokeLineCap = StrokeCap.Round,
        strokeLineJoin = StrokeJoin.Round,
    )
}.build()

/** 对话框是一张三百二十宽的纸面卡片，左右各留二十，与配置行的宽度对齐。 */
private val DialogWidth = 320.dp

/** 卡片内缩十四，行与行之间那道细线也从这里起。 */
private val CardInset = 14.dp

/** 摊开的是哪一行：组 + 组内标识。同组只会摊一家，收起时为空。 */
private data class OpenLine(val group: ProviderGroup, val key: String)

/**
 * 设置页。三组各一张卡：收着只留生效那 1 行，点开在卡内铺开同组全部几家，
 * 谁被选中谁生效。已配置那家摊开只剩可挑的那一项与行尾清除，没配过的就地铺凭据与保存，
 * 边填边算草稿，按下保存才落盘。底下是打卡热力图、系统提示词与全局记忆。
 */
@Composable
fun SettingsScreen(
    settings: AppSettings,
    providers: List<ProviderLine>,
    textProviders: List<TextProvider>,
    checkIn: CheckInWeeks,
    memory: String,
    defaultPrompt: String,
    defaultMemory: String,
    onDismiss: () -> Unit,
    onUpdate: ((AppSettings) -> AppSettings) -> Unit,
    onUpdateMemory: (String) -> Unit,
    onLoadModels: (String) -> Unit,
) {
    var open by remember { mutableStateOf<OpenLine?>(null) }
    var editingPrompt by remember { mutableStateOf(false) }
    var editingMemory by remember { mutableStateOf(false) }

    fun select(line: ProviderLine) {
        when (line.group) {
            ProviderGroup.Realtime -> vendorOf(line.key)?.let { vendor ->
                onUpdate { it.copy(live = it.live.select(vendor)) }
            }

            ProviderGroup.Assessor -> assessorOf(line.key)?.let { vendor ->
                onUpdate { it.copy(assessor = it.assessor.select(vendor)) }
            }

            ProviderGroup.Analysis -> onUpdate { it.copy(analysis = it.analysis.select(line.key)) }
        }
    }

    fun clear(line: ProviderLine) {
        when (line.group) {
            ProviderGroup.Realtime -> vendorOf(line.key)?.let { vendor ->
                onUpdate { it.copy(live = it.live.cleared(vendor)) }
            }

            ProviderGroup.Assessor -> assessorOf(line.key)?.let { vendor ->
                onUpdate { it.copy(assessor = it.assessor.cleared(vendor)) }
            }

            ProviderGroup.Analysis -> onUpdate { it.copy(analysis = it.analysis.cleared(line.key)) }
        }
    }

    // 点一行：同一行再点收起，换行则摊开这一家。已配置那家顺手设为生效那家，单选点跟着走；
    // 没配过的不改生效那家，填完保存才切过去。
    fun openRow(line: ProviderLine) {
        if (open == OpenLine(line.group, line.key)) {
            open = null
            return
        }
        open = OpenLine(line.group, line.key)
        if (line.configured) select(line)
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Palette.paper)
            .windowInsetsPadding(WindowInsets.statusBars),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = Spacing.screen)
                .height(44.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            VocaIconButton(
                icon = BackArrow,
                contentDescription = "返回对话",
                onClick = onDismiss,
                iconSize = 20.dp,
                tint = Palette.ink2,
            )
            Spacer(Modifier.width(4.dp))
            Text("设置", style = Type.display, modifier = Modifier.weight(1f))
        }
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = Spacing.screen),
            verticalArrangement = Arrangement.spacedBy(Spacing.cardInner),
        ) {
            Group("实时对话") {
                ProviderCard(
                    lines = providers.filter { it.group == ProviderGroup.Realtime },
                    open = open,
                    onTap = ::openRow,
                    onClear = ::clear,
                ) { line -> vendorOf(line.key)?.let { RealtimeDetail(it, settings.live, onUpdate) } }
            }
            Group("发音评测") {
                ProviderCard(
                    lines = providers.filter { it.group == ProviderGroup.Assessor },
                    open = open,
                    onTap = ::openRow,
                    onClear = ::clear,
                ) { line -> assessorOf(line.key)?.let { AssessorDetail(it, settings.assessor, onUpdate) } }
            }
            Group("文本分析") {
                ProviderCard(
                    lines = providers.filter { it.group == ProviderGroup.Analysis },
                    open = open,
                    onTap = ::openRow,
                    onClear = ::clear,
                ) { line ->
                    textProviders.firstOrNull { it.id == line.key }?.let { provider ->
                        AnalysisDetail(provider, settings.analysis, onUpdate, onLoadModels)
                    }
                }
            }
            Group("打卡统计") { CheckInCard(checkIn) }
            Group("系统提示词") {
                ProseCard(text = settings.prompt, action = "编辑提示词", onEdit = { editingPrompt = true })
            }
            Group("全局记忆") {
                ProseCard(text = memory, action = "编辑记忆", onEdit = { editingMemory = true })
            }
            Spacer(Modifier.height(Spacing.sheet))
        }
    }

    if (editingPrompt) {
        ProseDialog(
            title = "系统提示词",
            initial = settings.prompt,
            resetTo = defaultPrompt,
            onSave = { text -> onUpdate { it.copy(prompt = text) } },
            onDismiss = { editingPrompt = false },
        )
    }

    if (editingMemory) {
        ProseDialog(
            title = "全局记忆",
            initial = memory,
            resetTo = defaultMemory,
            onSave = onUpdateMemory,
            onDismiss = { editingMemory = false },
        )
    }
}

private fun vendorOf(key: String): LiveVendor? = LiveVendor.entries.firstOrNull { it.key == key }

private fun assessorOf(key: String): AssessorProvider? = AssessorProvider.entries.firstOrNull { it.key == key }

/** 一节是一行轴标加一张卡，节与节之间隔八个。 */
@Composable
private fun Group(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(Spacing.cardInner),
    ) {
        Box(Modifier.fillMaxWidth().height(18.dp), contentAlignment = Alignment.CenterStart) {
            Text(title, style = Type.sectionLabel)
        }
        content()
    }
}

@Composable
private fun SettingsCard(fill: Color = Palette.card, content: @Composable ColumnScope.() -> Unit) {
    Surface(
        color = fill,
        shape = RoundedCornerShape(Radius.vocabCard),
        border = BorderStroke(1.dp, Palette.hairSoft),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(content = content)
    }
}

/**
 * 一组那几家。收起只画生效那 1 行，摊开画同组全部几家，行与行之间一道半透明细线；
 * 摊开那家的详条直接接在它自己那一行下面，与下一家之间仍有细线。
 */
@Composable
private fun ProviderCard(
    lines: List<ProviderLine>,
    open: OpenLine?,
    onTap: (ProviderLine) -> Unit,
    onClear: (ProviderLine) -> Unit,
    detail: @Composable (ProviderLine) -> Unit,
) {
    val group = lines.firstOrNull()?.group
    val expanded = open != null && open.group == group
    val shown = if (expanded) lines else lines.filter { it.active }.ifEmpty { lines.take(1) }
    SettingsCard {
        shown.forEachIndexed { index, line ->
            // 每行按标识分组，摊开的详条与它的草稿跟着这一家走，换一家看不串。
            key(line.key) {
                if (index > 0) CardRule()
                ProviderRow(
                    line = line,
                    open = expanded && open?.key == line.key,
                    // 收着的那一行只是一份摘要，清除得摊开同组才有，画板 10 帧上就没有这一枚。
                    clearable = expanded && line.configured,
                    onTap = { onTap(line) },
                    onClear = { onClear(line) },
                )
                if (expanded && open?.key == line.key) detail(line)
            }
        }
    }
}

@Composable
private fun CardRule() {
    Box(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = CardInset)
            .height(1.dp)
            .background(Palette.hairSoft),
    )
}

/**
 * 一家一行：单选点、家名、第二行的模型号或端点、行尾的状态字。
 * 单选点填实是生效那家；已配置的行尾还带一枚清除。
 */
@Composable
private fun ProviderRow(
    line: ProviderLine,
    open: Boolean,
    clearable: Boolean,
    onTap: () -> Unit,
    onClear: () -> Unit,
) {
    Pressable(modifier = Modifier.fillMaxWidth(), onClick = onTap) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(ConfigRowHeight)
                .padding(horizontal = CardInset),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Radio(on = line.active)
            Spacer(Modifier.width(Spacing.block))
            Column(Modifier.weight(1f)) {
                Text(
                    text = line.name,
                    style = if (line.active || open) Type.uiStrong else Type.uiStrongInk3,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = line.detail,
                        style = if (open) Type.clockClay else Type.clockInk4,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Spacer(Modifier.width(4.dp))
                    Icon(
                        imageVector = if (open) FluentIcons.ChevUp else FluentIcons.ChevDown,
                        contentDescription = null,
                        modifier = Modifier.size(12.dp),
                        tint = if (open) Palette.clayDeep else Palette.ink4,
                    )
                }
            }
            Spacer(Modifier.width(Spacing.block))
            if (line.configured) {
                Box(Modifier.size(5.dp).background(Palette.clay, CircleShape))
                Spacer(Modifier.width(5.dp))
            }
            Text(
                text = if (line.configured) "已配置" else "未配置",
                style = if (line.configured) Type.captionInk2 else Type.captionInk4,
            )
            if (clearable) {
                Spacer(Modifier.width(Spacing.block))
                Pressable(
                    modifier = Modifier.size(24.dp),
                    shape = CircleShape,
                    contentDescription = "清除这一家的凭据",
                    onClick = onClear,
                ) {
                    Icon(
                        imageVector = FluentIcons.Dismiss,
                        contentDescription = null,
                        modifier = Modifier.size(13.dp),
                        tint = Palette.ink4,
                    )
                }
            }
        }
    }
}

/** 十八的圈，选中的那一格填一粒九的陶土点。 */
@Composable
private fun Radio(on: Boolean) {
    Box(
        modifier = Modifier
            .size(18.dp)
            .background(Palette.card, CircleShape)
            .border(1.dp, if (on) Palette.clay else Palette.hairStrong, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        if (on) Box(Modifier.size(9.dp).background(Palette.clay, CircleShape))
    }
}

/**
 * 展开一行时接在它下面那块。实时对话与文本分析各有一项可挑：
 * 已配置的只摆那一项，没配过的铺凭据与保存；发音评测只有凭据。
 */
@Composable
private fun RealtimeDetail(
    vendor: LiveVendor,
    live: LiveSettings,
    onUpdate: ((AppSettings) -> AppSettings) -> Unit,
) {
    if (!live.ready(vendor)) {
        LiveKeyForm(vendor, live, onUpdate)
        return
    }
    val voice = currentVoice(live, vendor)
    PickerRow(
        label = "音色",
        value = voiceName(vendor, voice),
        spec = voiceMenu(vendor, voice),
        onPick = { picked -> onUpdate { it.copy(live = it.live.withTuning(vendor, voice = picked)) } },
    )
}

@Composable
private fun AssessorDetail(
    vendor: AssessorProvider,
    assessor: AssessorSettings,
    onUpdate: ((AppSettings) -> AppSettings) -> Unit,
) {
    if (assessor.ready(vendor)) return
    when (vendor) {
        AssessorProvider.Azure -> {
            val drafts = rememberDrafts(
                remember(assessor) { mapOf("key" to assessor.azureKey, "region" to assessor.azureRegion) },
            )
            FormBody {
                EditField("密钥", drafts.getValue("key"), secret = true) { drafts["key"] = it }
                EditField("区域", drafts.getValue("region")) { drafts["region"] = it }
                SaveButton {
                    onUpdate {
                        it.copy(assessor = it.assessor.withAzure(drafts.getValue("key"), drafts.getValue("region")))
                    }
                }
            }
        }

        AssessorProvider.Tencent -> {
            val drafts = rememberDrafts(
                remember(assessor) {
                    mapOf(
                        "appId" to assessor.tencentAppId,
                        "secretId" to assessor.tencentSecretId,
                        "secretKey" to assessor.tencentSecretKey,
                    )
                },
            )
            FormBody {
                EditField("AppID", drafts.getValue("appId")) { drafts["appId"] = it }
                EditField("SecretID", drafts.getValue("secretId"), secret = true) { drafts["secretId"] = it }
                EditField("SecretKey", drafts.getValue("secretKey"), secret = true) { drafts["secretKey"] = it }
                SaveButton {
                    onUpdate {
                        it.copy(
                            assessor = it.assessor.withTencent(
                                appId = drafts.getValue("appId"),
                                secretId = drafts.getValue("secretId"),
                                secretKey = drafts.getValue("secretKey"),
                            ),
                        )
                    }
                }
            }
        }

        AssessorProvider.Xfyun -> {
            val drafts = rememberDrafts(
                remember(assessor) {
                    mapOf(
                        "appId" to assessor.xfyunAppId,
                        "apiKey" to assessor.xfyunApiKey,
                        "apiSecret" to assessor.xfyunApiSecret,
                    )
                },
            )
            FormBody {
                EditField("AppID", drafts.getValue("appId")) { drafts["appId"] = it }
                EditField("APIKey", drafts.getValue("apiKey"), secret = true) { drafts["apiKey"] = it }
                EditField("APISecret", drafts.getValue("apiSecret"), secret = true) { drafts["apiSecret"] = it }
                SaveButton {
                    onUpdate {
                        it.copy(
                            assessor = it.assessor.withXfyun(
                                appId = drafts.getValue("appId"),
                                apiKey = drafts.getValue("apiKey"),
                                apiSecret = drafts.getValue("apiSecret"),
                            ),
                        )
                    }
                }
            }
        }

        AssessorProvider.None -> Unit
    }
}

@Composable
private fun AnalysisDetail(
    provider: TextProvider,
    analysis: AnalysisSettings,
    onUpdate: ((AppSettings) -> AppSettings) -> Unit,
    onLoadModels: (String) -> Unit,
) {
    val own = analysis.of(provider.id)
    if (!own.ready) {
        AnalysisForm(provider, own, onUpdate, onLoadModels)
        return
    }
    PickerRow(
        label = "模型",
        value = own.model.ifBlank { Unpicked },
        spec = modelChoices(provider, own.model),
        onOpen = { onLoadModels(provider.id) },
        onPick = { picked ->
            onUpdate { it.withAnalysisModel(provider, picked) ?: it }
        },
    )
}

/** 没配过的那家就地铺凭据：密钥必填，自定义端点还多端点、模型与鉴权落点，最后一行陶土实心的保存。 */
@Composable
private fun AnalysisForm(
    provider: TextProvider,
    own: AnalysisCredentials,
    onUpdate: ((AppSettings) -> AppSettings) -> Unit,
    onLoadModels: (String) -> Unit,
) {
    val seed = remember(provider.id, own) { own }
    var key by remember(seed) { mutableStateOf(own.apiKey) }
    var endpoint by remember(seed) { mutableStateOf(own.endpoint) }
    var model by remember(seed) { mutableStateOf(own.model) }
    var name by remember(seed) { mutableStateOf(own.authName.ifBlank { provider.auth.name }) }
    var prefix by remember(seed) { mutableStateOf(own.authPrefix.ifBlank { provider.auth.prefix }) }
    var kind by remember(seed) { mutableStateOf(own.authKind ?: provider.auth.kind) }
    FormBody {
        if (provider.custom) {
            EditField("端点", endpoint) { endpoint = it }
        }
        EditField("密钥", key, secret = true) { key = it }
        if (provider.custom) {
            EditField("模型", model) { model = it }
            AuthKindChips(kind) { kind = it }
            if (kind != AuthKind.Bearer) {
                EditField(if (kind == AuthKind.Header) "请求头" else "查询参数", name) { name = it }
            }
            if (kind == AuthKind.Header) {
                EditField("前缀", prefix) { prefix = it }
            }
        } else if (provider.models.isNotEmpty()) {
            PickerField(
                label = "模型",
                value = model.ifBlank { Unpicked },
                spec = modelChoices(provider, model),
                onOpen = { onLoadModels(provider.id) },
                onPick = { model = it },
            )
        }
        SaveButton {
            onUpdate {
                it.withAnalysisCredentials(
                    provider = provider,
                    apiKey = key,
                    model = model,
                    endpoint = endpoint,
                    authKind = kind,
                    authName = name,
                    authPrefix = prefix,
                )
            }
        }
    }
}

/** 一家的凭据与音色，边填边算草稿；按保存才写盘，写盘齐备就把这家设为生效。 */
@Composable
private fun LiveKeyForm(
    vendor: LiveVendor,
    live: LiveSettings,
    onUpdate: ((AppSettings) -> AppSettings) -> Unit,
) {
    val stored = live.keyOf(vendor)
    var key by remember(vendor, stored) { mutableStateOf(stored) }
    var voice by remember(vendor, stored) { mutableStateOf(currentVoice(live, vendor)) }
    FormBody {
        EditField("API Key", key, secret = true) { key = it }
        PickerField(
            label = "音色",
            value = voiceName(vendor, voice),
            spec = voiceMenu(vendor, voice),
            onPick = { voice = it },
        )
        SaveButton { onUpdate { it.copy(live = it.live.saved(vendor, key, voice)) } }
    }
}

/** 表单那一栏：卡片内缩十四，字段之间隔十二。 */
@Composable
private fun FormBody(content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = CardInset, end = CardInset, bottom = CardInset),
        verticalArrangement = Arrangement.spacedBy(Spacing.block),
        content = content,
    )
}

@Composable
private fun SaveButton(onSave: () -> Unit) {
    Pressable(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(Radius.pill),
        resting = Palette.clay,
        onClick = onSave,
    ) {
        Box(Modifier.fillMaxWidth().height(OptionRowHeight), contentAlignment = Alignment.Center) {
            Text("保存", style = Type.uiStrongOnDark)
        }
    }
}

/**
 * 只挑不填的项，与行同高：左边写这一项的名字，右边是挑好的那个值与开合箭头。
 * 用在已配置那家的展开处，与卡片里那些行一条线对齐。
 */
@Composable
private fun PickerRow(
    label: String,
    value: String,
    spec: MenuSpec,
    onOpen: () -> Unit = {},
    onPick: (String) -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    Box {
        Pressable(
            modifier = Modifier.fillMaxWidth(),
            onClick = {
                open = !open
                if (open) onOpen()
            },
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(OptionRowHeight)
                    .padding(start = 44.dp, end = CardInset),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(label, style = Type.captionInk4)
                Row(
                    modifier = Modifier.weight(1f),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = value,
                        style = Type.ui,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Spacer(Modifier.width(4.dp))
                    Icon(
                        imageVector = if (open) FluentIcons.ChevUp else FluentIcons.ChevDown,
                        contentDescription = null,
                        modifier = Modifier.size(12.dp),
                        tint = if (open) Palette.clayDeep else Palette.ink4,
                    )
                }
            }
        }
        if (open) {
            ModelMenu(spec, onPick = { onPick(it); open = false }, onDismiss = { open = false })
        }
    }
}

/** 只挑不填的项，表单里那一格：标签在上，四十四高的浅底框里写值与箭头。 */
@Composable
private fun PickerField(
    label: String,
    value: String,
    spec: MenuSpec,
    onOpen: () -> Unit = {},
    onPick: (String) -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    Column {
        Text(label, style = Type.captionInk3)
        Spacer(Modifier.height(6.dp))
        Box {
            Pressable(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(Radius.control),
                resting = Palette.tint,
                onClick = {
                    open = !open
                    if (open) onOpen()
                },
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(OptionRowHeight)
                        .padding(horizontal = Spacing.block),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = value,
                        style = Type.ui,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    Icon(
                        imageVector = if (open) FluentIcons.ChevUp else FluentIcons.ChevDown,
                        contentDescription = null,
                        modifier = Modifier.size(12.dp),
                        tint = if (open) Palette.clayDeep else Palette.ink4,
                    )
                }
            }
            if (open) {
                ModelMenu(spec, onPick = { onPick(it); open = false }, onDismiss = { open = false })
            }
        }
    }
}

@Composable
private fun rememberDrafts(seed: Map<String, String>): SnapshotStateMap<String, String> =
    remember(seed) { mutableStateMapOf<String, String>().apply { putAll(seed) } }

@Composable
private fun EditField(
    label: String,
    value: String,
    secret: Boolean = false,
    onChange: (String) -> Unit,
) {
    Column {
        Text(label, style = Type.captionInk3)
        Spacer(Modifier.height(6.dp))
        Surface(
            color = Palette.tint,
            shape = RoundedCornerShape(Radius.control),
            modifier = Modifier.fillMaxWidth(),
        ) {
            BasicTextField(
                value = value,
                onValueChange = onChange,
                singleLine = true,
                textStyle = Type.uiInk,
                visualTransformation = if (secret) PasswordVisualTransformation() else VisualTransformation.None,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = OptionRowHeight)
                    .padding(horizontal = Spacing.block, vertical = 12.dp),
            )
        }
    }
}

@Composable
private fun AuthKindChips(selected: AuthKind, onPick: (AuthKind) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        AuthKind.entries.forEach { kind ->
            val on = kind == selected
            Pressable(
                shape = RoundedCornerShape(Radius.chip),
                resting = if (on) Palette.clayTint else Palette.tint,
                onClick = { onPick(kind) },
            ) {
                Box(Modifier.padding(horizontal = 12.dp, vertical = 7.dp)) {
                    Text(
                        text = kind.label(),
                        style = Type.caption.copy(color = if (on) Palette.clayDeep else Palette.ink3),
                    )
                }
            }
        }
    }
}

/** 一段整块交出去的中文叙述：角色提示词与全局记忆共用这张卡，点一下进编辑台。 */
@Composable
private fun ProseCard(text: String, action: String, onEdit: () -> Unit) {
    SettingsCard(fill = Palette.paper) {
        Pressable(modifier = Modifier.fillMaxWidth(), onClick = onEdit) {
            Column(Modifier.padding(horizontal = CardInset, vertical = 12.dp)) {
                // 提示词是所有会话共享的那一段，记忆是跨会话留下来的那一段，都是建连接时整段带走。
                Text(
                    text = text,
                    style = Type.promptProse,
                    maxLines = 5,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(8.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        imageVector = FluentIcons.Edit,
                        contentDescription = null,
                        tint = Palette.clayDeep,
                        modifier = Modifier.size(13.dp),
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(text = action, style = Type.captionClay)
                }
            }
        }
    }
}

/**
 * 编辑台。草稿不在参数上重建：记忆每一轮都在变，卡背后的那份换了也不能把正在写的这段填掉。
 * 弹窗是另一个窗口，输入法得自己请：开场先拿焦点再要键盘，点一下文字区也再要一次——
 * 焦点还在输入框上时，点下去不会再触发一次输入法上浮。
 */
@Composable
private fun ProseDialog(
    title: String,
    initial: String,
    resetTo: String,
    onSave: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var text by remember { mutableStateOf(initial) }
    val focus = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
    ) {
        // 窗口不缩到键盘之上，卡片自己抬起来，下半截不会压在键盘底下。
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .imePadding()
                .padding(horizontal = Spacing.screen),
            contentAlignment = Alignment.Center,
        ) {
            LaunchedEffect(Unit) {
                focus.requestFocus()
                keyboard?.show()
            }
            Surface(
                color = Palette.card,
                shape = RoundedCornerShape(Radius.sheet),
                modifier = Modifier
                    .widthIn(max = DialogWidth)
                    .fillMaxWidth(),
            ) {
                Column(
                    Modifier
                        .heightIn(max = 560.dp)
                        .verticalScroll(rememberScrollState())
                        .padding(Spacing.screen),
                ) {
                    Text(title, style = Type.display)
                    Spacer(Modifier.height(16.dp))
                    Surface(
                        color = Palette.tint,
                        shape = RoundedCornerShape(Radius.control),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        BasicTextField(
                            value = text,
                            onValueChange = { text = it },
                            textStyle = Type.promptProse,
                            cursorBrush = SolidColor(Palette.clay),
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(min = 232.dp, max = 320.dp)
                                .focusRequester(focus)
                                .pointerInput(Unit) {
                                    awaitEachGesture {
                                        awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                                        if (waitForUpOrCancellation(pass = PointerEventPass.Initial) != null) {
                                            keyboard?.show()
                                        }
                                    }
                                }
                                .padding(horizontal = 12.dp, vertical = 12.dp),
                        )
                    }
                    Spacer(Modifier.height(12.dp))
                    Pressable(modifier = Modifier.fillMaxWidth(), onClick = { text = resetTo }) {
                        Box(Modifier.height(32.dp), contentAlignment = Alignment.CenterStart) {
                            Text(text = "恢复默认", style = Type.uiStrongClay)
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    Pressable(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(Radius.pill),
                        resting = Palette.clay,
                        onClick = {
                            onSave(text)
                            onDismiss()
                        },
                    ) {
                        Box(Modifier.fillMaxWidth().height(OptionRowHeight), contentAlignment = Alignment.Center) {
                            Text("保存", style = Type.uiStrongOnDark)
                        }
                    }
                }
            }
        }
    }
}

/**
 * 打卡热力图：一列一周、一行一个日子，行首是周一；左边一列十个宽的周标只标一三五，
 * 上面一行月份只在跨月那一列起字。格宽由卡片可用宽度除出来，182 个方块在一个 Canvas 里画完；
 * 点中某一格就把底下那行换成那天的日期与时长，不足一分钟写秒，并在格上套一圈墨色细边。
 */
@Composable
private fun CheckInCard(stats: CheckInWeeks) {
    var picked by remember(stats) { mutableStateOf<Pair<Int, Int>?>(null) }
    SettingsCard {
        Column(Modifier.padding(horizontal = CardInset, vertical = 12.dp)) {
            BoxWithConstraints(Modifier.fillMaxWidth()) {
                val axisWidth = 10.dp
                val gap = 3.dp
                val cell = (maxWidth - axisWidth - gap * CheckInWeeks.Columns) / CheckInWeeks.Columns
                val pitch = cell + gap
                Column {
                    Box(Modifier.fillMaxWidth().height(12.dp)) {
                        stats.monthLabels().forEach { entry ->
                            Text(
                                text = entry.label,
                                style = Type.heatAxis,
                                maxLines = 1,
                                modifier = Modifier.offset(x = axisWidth + gap + pitch * entry.column),
                            )
                        }
                    }
                    Spacer(Modifier.height(2.dp))
                    Row {
                        Box(Modifier.width(axisWidth).height(cell * CheckInWeeks.Rows + gap * (CheckInWeeks.Rows - 1))) {
                            CheckInWeekdays.forEach { (row, label) ->
                                Text(
                                    text = label,
                                    style = Type.heatAxis,
                                    maxLines = 1,
                                    textAlign = TextAlign.End,
                                    modifier = Modifier.width(axisWidth).offset(y = pitch * row - 1.dp),
                                )
                            }
                        }
                        Spacer(Modifier.width(gap))
                        Canvas(
                            Modifier
                                .width(cell * CheckInWeeks.Columns + gap * (CheckInWeeks.Columns - 1))
                                .height(cell * CheckInWeeks.Rows + gap * (CheckInWeeks.Rows - 1))
                                .pointerInput(stats) {
                                    detectTapGestures { at ->
                                        val step = (cell + gap).toPx()
                                        val column = (at.x / step).toInt()
                                        val row = (at.y / step).toInt()
                                        if (column in 0 until CheckInWeeks.Columns && row in 0 until CheckInWeeks.Rows) {
                                            picked = if (picked == column to row) null else column to row
                                        }
                                    }
                                },
                        ) {
                            val step = (cell + gap).toPx()
                            val side = cell.toPx()
                            val corner = CornerRadius(2.dp.toPx())
                            stats.weeks.forEachIndexed { column, days ->
                                days.forEachIndexed { row, day ->
                                    drawRoundRect(
                                        color = Palette.heat[day.level.ordinal],
                                        topLeft = Offset(column * step, row * step),
                                        size = Size(side, side),
                                        cornerRadius = corner,
                                    )
                                }
                            }
                            picked?.let { (column, row) ->
                                val ring = 1.dp.toPx()
                                drawRoundRect(
                                    color = Palette.ink,
                                    topLeft = Offset(column * step - ring, row * step - ring),
                                    size = Size(side + ring * 2, side + ring * 2),
                                    cornerRadius = CornerRadius(3.dp.toPx()),
                                    style = Stroke(width = ring),
                                )
                            }
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = picked?.let { (column, row) -> stats.weeks[column][row].readout() } ?: stats.summary(),
                        style = Type.clock.copy(color = if (picked != null) Palette.ink2 else Palette.ink4),
                    )
                }
            }
        }
    }
}

/** 周标只标一、三、五三行，七行都写反而更读不出来。 */
private val CheckInWeekdays = listOf(0 to "一", 2 to "三", 4 to "五")
