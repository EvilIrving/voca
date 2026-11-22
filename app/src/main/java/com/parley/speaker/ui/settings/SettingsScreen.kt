package com.parley.speaker.ui.settings

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
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
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateMap
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.parley.speaker.data.analysis.AnalysisProbe
import com.parley.speaker.data.analysis.TextEngineConfig
import com.parley.speaker.data.analysis.TextModel
import com.parley.speaker.data.analysis.TextProvider
import com.parley.speaker.data.analysis.TextProviderCatalog
import com.parley.speaker.domain.AnalysisSettings
import com.parley.speaker.domain.AppSettings
import com.parley.speaker.domain.AssessorProvider
import com.parley.speaker.domain.AssessorSettings
import com.parley.speaker.domain.AuthKind
import com.parley.speaker.domain.LinkStatus
import com.parley.speaker.domain.LiveSettings
import com.parley.speaker.domain.LiveVendor
import com.parley.speaker.domain.ProviderLine
import com.parley.speaker.ui.component.ParleyIconButton
import com.parley.speaker.ui.component.Pressable
import com.parley.speaker.ui.icon.FluentIcons
import com.parley.speaker.ui.theme.Palette
import com.parley.speaker.ui.theme.Radius
import com.parley.speaker.ui.theme.Spacing
import com.parley.speaker.ui.theme.Type

// FluentIcons 里没有返回图元，这一支按 Fluent 线宽自己画。
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

@Composable
fun SettingsScreen(
    settings: AppSettings,
    providers: List<ProviderLine>,
    textProviders: List<TextProvider>,
    report: String?,
    onDismiss: () -> Unit,
    onProbe: () -> Unit,
    onUpdate: ((AppSettings) -> AppSettings) -> Unit,
    onLoadModels: (String) -> Unit,
) {
    var editing by remember { mutableStateOf<String?>(null) }
    var openModels by remember { mutableStateOf<String?>(null) }
    var probeStart by remember { mutableStateOf(0L) }
    var probeElapsed by remember { mutableStateOf<Long?>(null) }
    var probeVerdict by remember { mutableStateOf<String?>(null) }

    // 自检没有完成回调，report 离开在途文案那一刻就是这次探针的收尾时刻。
    LaunchedEffect(report) {
        if (probeStart != 0L && report != null && report != ProbePending) {
            probeElapsed = System.currentTimeMillis() - probeStart
            probeStart = 0L
        }
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
            ParleyIconButton(
                icon = BackArrow,
                contentDescription = "返回对话",
                onClick = onDismiss,
                iconSize = 20.dp,
                tint = Palette.ink2,
            )
            Spacer(Modifier.width(4.dp))
            Text("配置中心", style = Type.display, modifier = Modifier.weight(1f))
        }
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = Spacing.screen),
        ) {
            Spacer(Modifier.height(8.dp))
            GroupCard(
                label = "实时对话",
                lines = providers.filter { it.group == RealtimeGroup },
                onEdit = { editing = it },
            )
            Spacer(Modifier.height(18.dp))
            GroupCard(
                label = "发音评测",
                lines = providers.filter { it.group == AssessorGroup },
                onEdit = { editing = it },
            )
            Spacer(Modifier.height(18.dp))
            AnalysisGroup(
                analysis = settings.analysis,
                providers = textProviders,
                lines = providers.filter { it.group == AnalysisGroupKey },
                open = openModels,
                onOpen = { openModels = if (openModels == it) null else it },
                onEdit = { editing = "$ProviderPrefix$it" },
                onLoadModels = onLoadModels,
                onPick = { provider, id ->
                    onUpdate { current -> current.withAnalysisModel(provider, id) ?: current }
                    openModels = null
                },
            )
            Spacer(Modifier.height(18.dp))
            GroupLabel("角色提示词")
            PromptCard(prompt = settings.prompt, onEdit = { editing = "prompt" })
            Spacer(Modifier.height(18.dp))
            ProbeCard(
                report = report,
                verdict = probeVerdict,
                preferReport = settings.analysis.provider == TextProviderCatalog.GeminiId,
                elapsed = probeElapsed,
                onProbe = {
                    probeVerdict = analysisVerdict(settings.analysis)
                    probeStart = System.currentTimeMillis()
                    onProbe()
                },
            )
            Spacer(Modifier.height(24.dp))
        }
    }

    when (editing) {
        "gemini" -> LiveDialog(LiveVendor.Gemini, settings.live, onUpdate) { editing = null }
        "openai" -> LiveDialog(LiveVendor.OpenAi, settings.live, onUpdate) { editing = null }
        "doubao" -> LiveDialog(LiveVendor.Doubao, settings.live, onUpdate) { editing = null }
        "azure" -> AzureDialog(settings.assessor, onUpdate) { editing = null }
        "xfyun" -> XfyunDialog(settings.assessor, onUpdate) { editing = null }
        "prompt" -> PromptDialog(settings.prompt, onUpdate) { editing = null }
        else -> editing
            ?.takeIf { it.startsWith(ProviderPrefix) }
            ?.removePrefix(ProviderPrefix)
            ?.let { TextProviderCatalog.of(it) }
            ?.let { provider ->
                ProviderDialog(provider, settings.analysis, onUpdate) { editing = null }
            }
    }
}

// 与 ChatViewModel 里探针在途时的 report 文案对齐，用来判定这一次自检何时落地。
private const val ProbePending = "正在自检"

/** ChatViewModel 给三组行填的 group 名，界面按同一份名字分组。 */
private const val RealtimeGroup = "realtime"
private const val AssessorGroup = "assessor"
private const val AnalysisGroupKey = "analysis"

@Composable
private fun GroupLabel(text: String) {
    Text(
        text = text,
        style = Type.captionInk3,
        modifier = Modifier.padding(bottom = 8.dp),
    )
}

@Composable
private fun GroupDivider() {
    Box(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp)
            .height(1.dp)
            .background(Palette.hair),
    )
}

@Composable
private fun GroupCard(label: String, lines: List<ProviderLine>, onEdit: (String) -> Unit) {
    GroupLabel(label)
    Surface(
        color = Palette.card,
        shape = RoundedCornerShape(Radius.card),
        border = BorderStroke(1.dp, Palette.hair),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column {
            lines.forEachIndexed { index, line ->
                if (index > 0) GroupDivider()
                ProviderRow(line) { onEdit(line.key) }
            }
        }
    }
}

/** 一行一家：姓名、第二行读数、状态与状态点。整行都能点开去填凭据。 */
@Composable
private fun ProviderRow(line: ProviderLine, onClick: () -> Unit) {
    Pressable(
        modifier = Modifier.fillMaxWidth(),
        enabled = line.editable,
        onClick = onClick,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(ConfigRowHeight)
                .padding(horizontal = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    text = line.name,
                    style = Type.uiStrong,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (line.detail.isNotBlank()) {
                    Text(
                        text = line.detail,
                        style = Type.mono,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            Spacer(Modifier.width(Spacing.block))
            Text(line.status.label(), style = Type.caption.copy(color = line.status.tint()))
            Spacer(Modifier.width(10.dp))
            StatusDot(line.status.linked())
        }
    }
}

/** 已经没有行会产出未接入，显示分支只留实际会出现的四种。 */
private fun LinkStatus.label(): String = when (this) {
    LinkStatus.Connected -> "已连通"
    LinkStatus.Configured -> "已配置"
    LinkStatus.Failed -> "连接失败"
    else -> "未配置"
}

private fun LinkStatus.tint(): Color = when (this) {
    LinkStatus.Connected -> Palette.olive
    LinkStatus.Configured -> Palette.ink2
    LinkStatus.Failed -> Palette.danger
    else -> Palette.ink4
}

private fun LinkStatus.linked(): Boolean = this == LinkStatus.Connected || this == LinkStatus.Configured

@Composable
private fun StatusDot(linked: Boolean) {
    Box(
        modifier = Modifier
            .size(14.dp)
            .background(if (linked) Palette.clayTint else Color.Transparent, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier
                .size(if (linked) 8.dp else 10.dp)
                .then(
                    if (linked) {
                        Modifier.background(Palette.clay, CircleShape)
                    } else {
                        Modifier.border(1.5.dp, Palette.ink4, CircleShape)
                    },
                ),
        )
    }
}

@Composable
private fun PromptCard(prompt: String, onEdit: () -> Unit) {
    Surface(
        color = Palette.card,
        shape = RoundedCornerShape(Radius.card),
        border = BorderStroke(1.dp, Palette.hair),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Pressable(modifier = Modifier.fillMaxWidth(), onClick = onEdit) {
            Box(Modifier.padding(14.dp)) {
                Text(
                    text = prompt,
                    style = Type.proseItalic,
                    maxLines = 4,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(end = 76.dp),
                )
                Text(
                    text = "编辑提示词",
                    style = Type.uiStrongClay,
                    modifier = Modifier.align(Alignment.TopEnd),
                )
            }
        }
    }
}

@Composable
private fun ProbeCard(
    report: String?,
    verdict: String?,
    preferReport: Boolean,
    elapsed: Long?,
    onProbe: () -> Unit,
) {
    // Gemini 走主链路自检，回报最真；其余供应商的探针结果只能来自本地那一份计划。
    val shown = if (preferReport) report ?: verdict else verdict ?: report
    Surface(
        color = Palette.card,
        shape = RoundedCornerShape(Radius.card),
        border = BorderStroke(1.dp, Palette.hair),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Pressable(modifier = Modifier.fillMaxWidth(), onClick = onProbe) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(ConfigRowHeight)
                    .padding(horizontal = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    FluentIcons.Plug,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                    tint = Palette.ink3,
                )
                Spacer(Modifier.width(Spacing.iconText))
                Column(Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("连接自检", style = Type.uiStrong, modifier = Modifier.weight(1f))
                        elapsed?.let { Text(formatElapsed(it), style = Type.mono) }
                    }
                    shown?.let {
                        Text(
                            text = it,
                            style = Type.captionInk3,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                Spacer(Modifier.width(6.dp))
                Icon(
                    FluentIcons.ChevRight,
                    contentDescription = null,
                    modifier = Modifier.size(14.dp),
                    tint = Palette.ink4,
                )
            }
        }
    }
}

private fun formatElapsed(ms: Long): String = "%.1f 秒".format(ms / 1000f)

private const val ProviderPrefix = "provider:"

/** ChatViewModel 给文本分析那几行用的 key，Gemini 那条沿用旧名。 */
private fun injectedKey(providerId: String): String =
    if (providerId == TextProviderCatalog.GeminiId) "analysis" else providerId

private fun hostOf(provider: TextProvider): String =
    provider.endpoint.substringAfter("://").substringBefore('/').ifBlank { "未填写" }

/** 本地探针的结论：参数能不能拼成一次最小请求，一个字节都不发。 */
private fun analysisVerdict(settings: AnalysisSettings): String {
    val config = TextEngineConfig.of(settings)
    val plan = AnalysisProbe.plan(config)
    return when {
        settings.apiKey.isBlank() -> "未填密钥"
        config.endpoint.isBlank() -> "未填端点"
        plan.status == LinkStatus.Failed -> "端点无效"
        else -> "配置可用"
    }
}

private fun tokens(value: Int): String = when {
    value >= 1_000_000 -> "${value / 1_000_000}M"
    value >= 1_000 -> "${value / 1_000}K"
    else -> value.toString()
}

/** 模型行下行只摆真实元数据，没同步到的显示状态，不编数字。 */
private fun metaLine(model: TextModel): String {
    val parts = buildList {
        if (model.context > 0) add("${tokens(model.context)} 上下文")
        if (model.maxOutput > 0) add("${tokens(model.maxOutput)} 输出")
        if (model.thinking) add("思考")
    }
    return parts.joinToString(" · ")
}

private fun AuthKind.label(): String = when (this) {
    AuthKind.Bearer -> "Bearer"
    AuthKind.Header -> "请求头"
    AuthKind.Query -> "查询参数"
}

/**
 * 文本分析的一行：第二行读数与状态。每家读自己那一格，有密钥且有模型就写模型名，
 * 模型空着或不在清单里就写未选择，没填密钥就写端点域名。
 */
internal data class AnalysisRow(val provider: TextProvider, val detail: String, val status: LinkStatus)

/** 模型空着或不在这一家的清单里时行内就这么写，点开挑一个才落定。 */
internal const val Unpicked = "未选择，点开挑一个"

internal fun analysisRow(
    analysis: AnalysisSettings,
    provider: TextProvider,
    lines: List<ProviderLine>,
): AnalysisRow {
    val own = analysis.of(provider.id)
    val detail = when {
        !own.ready -> hostOf(provider)
        own.model.isBlank() -> Unpicked
        provider.models.isNotEmpty() && provider.models.none { it.id == own.model } -> Unpicked
        else -> own.model
    }
    val status = lines.firstOrNull { it.key == injectedKey(provider.id) }?.status ?: LinkStatus.Unconfigured
    return AnalysisRow(provider, detail, status)
}

/**
 * 展开一行时该拉的清单。空表示这行不可展开：这一家自己没填密钥。
 * 界面按这一格决定要不要画箭头、要不要发请求，两处不各自判一次。
 */
internal fun modelsToLoad(analysis: AnalysisSettings, providerId: String): List<String> =
    if (analysis.of(providerId).ready) listOf(providerId) else emptyList()

/** 模型下拉的候选只来自这一家拉到的清单，选中项不在清单里就一行都不勾。 */
internal fun modelChoices(provider: TextProvider, analysis: AnalysisSettings): MenuSpec = MenuSpec(
    options = provider.models.map { MenuOption(it.id, metaLine(it).ifBlank { null }) },
    selected = analysis.of(provider.id).model,
)

@Composable
private fun AnalysisGroup(
    analysis: AnalysisSettings,
    providers: List<TextProvider>,
    lines: List<ProviderLine>,
    open: String?,
    onOpen: (String) -> Unit,
    onEdit: (String) -> Unit,
    onLoadModels: (String) -> Unit,
    onPick: (TextProvider, String) -> Unit,
) {
    GroupLabel("文本分析")
    Surface(
        color = Palette.card,
        shape = RoundedCornerShape(Radius.card),
        border = BorderStroke(1.dp, Palette.hair),
        // 展开的模型清单要盖住后面的角色提示词与自检卡，靠 zIndex 把它整体抬到同层兄弟之上。
        modifier = Modifier
            .fillMaxWidth()
            .zIndex(if (open != null) 1f else 0f),
    ) {
        Column {
            providers.forEachIndexed { index, provider ->
                if (index > 0) GroupDivider()
                val row = analysisRow(analysis, provider, lines)
                val loadable = modelsToLoad(analysis, provider.id)
                Box(Modifier.zIndex(if (open == provider.id) 1f else 0f)) {
                    SupplierRow(
                        row = row,
                        expanded = open == provider.id,
                        expandable = loadable.isNotEmpty(),
                        // 展开先拉一次清单，缓存命中的先上屏，再拿密钥刷一次。
                        onToggle = {
                            if (open != provider.id) loadable.forEach(onLoadModels)
                            onOpen(provider.id)
                        },
                        onEdit = { onEdit(provider.id) },
                    )
                    // 模型清单是压在行上的三百二十宽浮层，不把分组撑高。
                    if (open == provider.id && loadable.isNotEmpty()) {
                        ModelMenu(
                            spec = modelChoices(provider, analysis),
                            onPick = { onPick(provider, it) },
                            onDismiss = { onOpen(provider.id) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SupplierRow(
    row: AnalysisRow,
    expanded: Boolean,
    expandable: Boolean,
    onToggle: () -> Unit,
    onEdit: () -> Unit,
) {
    Pressable(modifier = Modifier.fillMaxWidth(), onClick = onEdit) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(ConfigRowHeight)
                .padding(horizontal = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    text = row.provider.label,
                    style = Type.uiStrong,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = row.detail,
                    style = Type.mono.copy(color = if (expanded) Palette.clayDeep else Palette.ink3),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.width(Spacing.block))
            Text(row.status.label(), style = Type.caption.copy(color = row.status.tint()))
            Spacer(Modifier.width(10.dp))
            StatusDot(row.status.linked())
            if (expandable) {
                Spacer(Modifier.width(6.dp))
                Pressable(
                    modifier = Modifier.size(24.dp),
                    shape = CircleShape,
                    onClick = onToggle,
                ) {
                    Icon(
                        FluentIcons.ChevRight,
                        contentDescription = if (expanded) "收起模型" else "选择模型",
                        modifier = Modifier
                            .size(14.dp)
                            .rotate(if (expanded) -90f else 90f),
                        tint = if (expanded) Palette.clayDeep else Palette.ink4,
                    )
                }
            }
        }
    }
}

/** 对话框的统一外壳：纸白面板、标题、内容，底部一颗保存。 */
@Composable
private fun SettingsDialog(
    title: String,
    onSave: () -> Unit,
    onDismiss: () -> Unit,
    content: @Composable ColumnScope.() -> Unit,
) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = Spacing.screen),
            contentAlignment = Alignment.Center,
        ) {
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
                        .padding(20.dp),
                ) {
                    Text(title, style = Type.display)
                    Spacer(Modifier.height(16.dp))
                    content()
                    Spacer(Modifier.height(8.dp))
                    Pressable(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(Radius.pill),
                        resting = Palette.clay,
                        onClick = onSave,
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(OptionRowHeight),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text("保存", style = Type.uiStrongOnDark)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun rememberDrafts(seed: Map<String, String>): SnapshotStateMap<String, String> =
    remember(seed) { mutableStateMapOf<String, String>().apply { putAll(seed) } }

/**
 * 实时对话一家一个对话框：只摆这一家的凭据，音色从这一家的静态目录里挑，
 * 不接受手打。模型与音色换家后落到那家的默认值，由存储器归位。
 */
@Composable
private fun LiveDialog(
    vendor: LiveVendor,
    live: LiveSettings,
    onUpdate: ((AppSettings) -> AppSettings) -> Unit,
    onDismiss: () -> Unit,
) {
    val fields = remember(vendor, live) { liveFields(live, vendor) }
    val drafts = rememberDrafts(fields.associate { it.key to it.value })
    var voice by remember(vendor, live) { mutableStateOf(currentVoice(live, vendor)) }
    var voiceOpen by remember { mutableStateOf(false) }
    SettingsDialog(
        title = vendor.label,
        onSave = {
            onUpdate { it.liveSaved(vendor, drafts, voice) }
            onDismiss()
        },
        onDismiss = onDismiss,
    ) {
        fields.forEach { field ->
            EditField(
                label = field.label,
                value = drafts[field.key].orEmpty(),
                secret = field.secret,
                singleLine = true,
            ) { drafts[field.key] = it }
            Spacer(Modifier.height(12.dp))
        }
        Text("音色", style = Type.captionInk3)
        Spacer(Modifier.height(6.dp))
        Box {
            Pressable(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(Radius.control),
                resting = Palette.tint,
                onClick = { voiceOpen = !voiceOpen },
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(OptionRowHeight)
                        .padding(horizontal = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.Center) {
                        Text(
                            text = voice,
                            style = Type.uiStrong,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        voiceStyle(vendor, voice)?.let {
                            Text(
                                text = it,
                                style = Type.clock,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                    Icon(
                        FluentIcons.ChevRight,
                        contentDescription = if (voiceOpen) "收起音色" else "选择音色",
                        modifier = Modifier
                            .size(14.dp)
                            .rotate(if (voiceOpen) -90f else 90f),
                        tint = if (voiceOpen) Palette.clayDeep else Palette.ink4,
                    )
                }
            }
            if (voiceOpen) {
                ModelMenu(
                    spec = voiceMenu(vendor, voice),
                    onPick = {
                        voice = it
                        voiceOpen = false
                    },
                    onDismiss = { voiceOpen = false },
                )
            }
        }
    }
}

@Composable
private fun AzureDialog(
    assessor: AssessorSettings,
    onUpdate: ((AppSettings) -> AppSettings) -> Unit,
    onDismiss: () -> Unit,
) {
    val drafts = rememberDrafts(mapOf("key" to assessor.azureKey, "region" to assessor.azureRegion))
    SettingsDialog(
        title = AssessorProvider.Azure.label,
        onSave = {
            onUpdate {
                it.copy(assessor = it.assessor.withAzure(drafts.getValue("key"), drafts.getValue("region")))
            }
            onDismiss()
        },
        onDismiss = onDismiss,
    ) {
        EditField("密钥", drafts.getValue("key"), secret = true, singleLine = true) { drafts["key"] = it }
        Spacer(Modifier.height(12.dp))
        EditField("区域", drafts.getValue("region"), secret = false, singleLine = true) { drafts["region"] = it }
        Spacer(Modifier.height(12.dp))
    }
}

@Composable
private fun XfyunDialog(
    assessor: AssessorSettings,
    onUpdate: ((AppSettings) -> AppSettings) -> Unit,
    onDismiss: () -> Unit,
) {
    val drafts = rememberDrafts(
        mapOf(
            "appId" to assessor.xfyunAppId,
            "apiKey" to assessor.xfyunApiKey,
            "apiSecret" to assessor.xfyunApiSecret,
        ),
    )
    SettingsDialog(
        title = AssessorProvider.Xfyun.label,
        onSave = {
            onUpdate {
                it.copy(
                    assessor = it.assessor.withXfyun(
                        appId = drafts.getValue("appId"),
                        apiKey = drafts.getValue("apiKey"),
                        apiSecret = drafts.getValue("apiSecret"),
                    ),
                )
            }
            onDismiss()
        },
        onDismiss = onDismiss,
    ) {
        EditField("AppID", drafts.getValue("appId"), secret = false, singleLine = true) { drafts["appId"] = it }
        Spacer(Modifier.height(12.dp))
        EditField("APIKey", drafts.getValue("apiKey"), secret = true, singleLine = true) { drafts["apiKey"] = it }
        Spacer(Modifier.height(12.dp))
        EditField("APISecret", drafts.getValue("apiSecret"), secret = true, singleLine = true) { drafts["apiSecret"] = it }
        Spacer(Modifier.height(12.dp))
    }
}

@Composable
private fun PromptDialog(
    prompt: String,
    onUpdate: ((AppSettings) -> AppSettings) -> Unit,
    onDismiss: () -> Unit,
) {
    var text by remember(prompt) { mutableStateOf(prompt) }
    SettingsDialog(
        title = "角色提示词",
        onSave = {
            onUpdate { it.copy(prompt = text) }
            onDismiss()
        },
        onDismiss = onDismiss,
    ) {
        EditField("提示词", text, secret = false, singleLine = false) { text = it }
        Spacer(Modifier.height(12.dp))
    }
}

/** 目录里的供应商只填密钥，自定义端点还要填端点、模型与鉴权落点。 */
@Composable
private fun ProviderDialog(
    provider: TextProvider,
    analysis: AnalysisSettings,
    onUpdate: ((AppSettings) -> AppSettings) -> Unit,
    onDismiss: () -> Unit,
) {
    // 每家各存一格，开谁的对话框就预填谁自己的密钥与端点，别家的不会串进来。
    val own = analysis.of(provider.id)
    val drafts = rememberDrafts(
        remember(provider.id, analysis) {
            mapOf(
                "apiKey" to own.apiKey,
                "endpoint" to own.endpoint,
                "model" to own.model,
                "name" to own.authName.ifBlank { provider.auth.name },
                "prefix" to own.authPrefix.ifBlank { provider.auth.prefix },
            )
        },
    )
    var kind by remember(provider.id) { mutableStateOf(own.authKind ?: provider.auth.kind) }
    SettingsDialog(
        title = provider.label,
        onSave = {
            onUpdate {
                it.withAnalysisCredentials(
                    provider = provider,
                    apiKey = drafts.getValue("apiKey"),
                    model = drafts.getValue("model"),
                    endpoint = drafts.getValue("endpoint"),
                    authKind = kind,
                    authName = drafts.getValue("name"),
                    authPrefix = drafts.getValue("prefix"),
                )
            }
            onDismiss()
        },
        onDismiss = onDismiss,
    ) {
        if (provider.custom) {
            EditField("端点", drafts.getValue("endpoint"), secret = false, singleLine = true) { drafts["endpoint"] = it }
            Spacer(Modifier.height(12.dp))
            EditField("模型", drafts.getValue("model"), secret = false, singleLine = true) { drafts["model"] = it }
            Spacer(Modifier.height(12.dp))
            AuthKindChips(kind) { kind = it }
            Spacer(Modifier.height(12.dp))
        }
        EditField("密钥", drafts.getValue("apiKey"), secret = true, singleLine = true) { drafts["apiKey"] = it }
        Spacer(Modifier.height(12.dp))
        if (provider.custom && kind != AuthKind.Bearer) {
            EditField(
                label = if (kind == AuthKind.Header) "请求头" else "查询参数",
                value = drafts.getValue("name"),
                secret = false,
                singleLine = true,
            ) { drafts["name"] = it }
            Spacer(Modifier.height(12.dp))
        }
        if (provider.custom && kind == AuthKind.Header) {
            EditField("前缀", drafts.getValue("prefix"), secret = false, singleLine = true) { drafts["prefix"] = it }
            Spacer(Modifier.height(12.dp))
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

@Composable
private fun EditField(
    label: String,
    value: String,
    secret: Boolean,
    singleLine: Boolean,
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
                singleLine = singleLine,
                textStyle = Type.uiInk,
                visualTransformation = if (secret) PasswordVisualTransformation() else VisualTransformation.None,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = if (singleLine) OptionRowHeight else 120.dp)
                    .padding(horizontal = 12.dp, vertical = 12.dp),
            )
        }
    }
}
