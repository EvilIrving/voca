package com.parley.speaker.ui.settings

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.toMutableStateList
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
import androidx.compose.ui.window.Dialog
import com.parley.speaker.data.analysis.AnalysisProbe
import com.parley.speaker.data.analysis.TextEngineConfig
import com.parley.speaker.data.analysis.TextModel
import com.parley.speaker.data.analysis.TextProvider
import com.parley.speaker.data.analysis.TextProviderCatalog
import com.parley.speaker.domain.AnalysisSettings
import com.parley.speaker.domain.AppSettings
import com.parley.speaker.domain.AuthKind
import com.parley.speaker.domain.LinkStatus
import com.parley.speaker.domain.ProviderLine
import com.parley.speaker.ui.component.ParleyIconButton
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

@Composable
fun SettingsScreen(
    settings: AppSettings,
    providers: List<ProviderLine>,
    report: String?,
    onDismiss: () -> Unit,
    onProbe: () -> Unit,
    onUpdate: ((AppSettings) -> AppSettings) -> Unit,
) {
    var editing by remember { mutableStateOf<String?>(null) }
    var openMenu by remember { mutableStateOf<String?>(null) }
    var openProvider by remember { mutableStateOf<String?>(settings.analysis.provider) }
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
                lines = providers.filter { it.group == "realtime" },
                openMenu = openMenu,
                settings = settings,
                onEdit = { editing = it },
                onToggleMenu = { openMenu = if (openMenu == it) null else it },
                onPickModel = { key, id ->
                    onUpdate { it.withModel(key, id) }
                    openMenu = null
                },
            )
            Spacer(Modifier.height(18.dp))
            GroupCard(
                label = "发音评测",
                lines = providers.filter { it.group == "assessor" },
                openMenu = openMenu,
                settings = settings,
                onEdit = { editing = it },
                onToggleMenu = { openMenu = if (openMenu == it) null else it },
                onPickModel = { key, id ->
                    onUpdate { it.withModel(key, id) }
                    openMenu = null
                },
            )
            Spacer(Modifier.height(18.dp))
            AnalysisGroup(
                settings = settings.analysis,
                lines = providers.filter { it.group == "analysis" },
                expanded = openProvider,
                onExpand = { openProvider = if (openProvider == it) null else it },
                onEdit = { editing = it },
                onPickModel = { provider, model ->
                    onUpdate { it.withAnalysisModel(provider, model.id) }
                    openProvider = provider.id
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
        "live" -> EditDialog(
            title = "Gemini Live",
            specs = listOf(
                FieldSpec("密钥", settings.live.apiKey, secret = true),
                FieldSpec("音色", settings.live.voice),
            ),
            onSave = { values ->
                onUpdate {
                    it.copy(live = it.live.copy(apiKey = values[0], voice = values[1]))
                }
                editing = null
            },
            onDismiss = { editing = null },
        )

        "azure" -> EditDialog(
            title = "微软 Azure 语音评测",
            specs = listOf(
                FieldSpec("密钥", settings.assessor.azureKey, secret = true),
                FieldSpec("区域", settings.assessor.azureRegion),
            ),
            onSave = { values ->
                onUpdate {
                    it.copy(assessor = it.assessor.copy(azureKey = values[0], azureRegion = values[1]))
                }
                editing = null
            },
            onDismiss = { editing = null },
        )

        "prompt" -> EditDialog(
            title = "角色提示词",
            specs = listOf(FieldSpec("提示词", settings.prompt, singleLine = false)),
            onSave = { values ->
                onUpdate { it.copy(prompt = values[0]) }
                editing = null
            },
            onDismiss = { editing = null },
        )

        else -> {
            val provider = editing
                ?.takeIf { it.startsWith(ProviderPrefix) }
                ?.removePrefix(ProviderPrefix)
                ?.let { TextProviderCatalog.of(it) }
            if (provider != null) {
                val active = settings.analysis
                // 密钥只存一份，给未启用的供应商开对话框时不能把当前那家的密钥预填进去。
                val seed = if (active.provider == provider.id) {
                    active
                } else {
                    active.copy(
                        apiKey = "",
                        endpoint = "",
                        authKind = null,
                        authName = "",
                        authPrefix = "",
                        model = provider.models.firstOrNull()?.id.orEmpty(),
                    )
                }
                ProviderDialog(
                    provider = provider,
                    settings = seed,
                    onSave = { values ->
                        onUpdate {
                            it.withAnalysisCredentials(
                                provider = provider,
                                apiKey = values.getValue("apiKey"),
                                model = values["model"],
                                endpoint = values.getValue("endpoint"),
                                authKind = values["authKind"]?.let { name ->
                                    AuthKind.entries.firstOrNull { it.name == name }
                                },
                                authName = values.getValue("name"),
                                authPrefix = values.getValue("prefix"),
                            )
                        }
                        openProvider = provider.id
                        editing = null
                    },
                    onDismiss = { editing = null },
                )
            }
        }
    }
}

// 与 ChatViewModel 里探针在途时的 report 文案对齐，用来判定这一次自检何时落地。
private const val ProbePending = "正在自检"

@Composable
private fun GroupLabel(text: String) {
    Text(
        text = text,
        style = Type.caption.copy(color = Palette.ink3),
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
private fun GroupCard(
    label: String,
    lines: List<ProviderLine>,
    openMenu: String?,
    settings: AppSettings,
    onEdit: (String) -> Unit,
    onToggleMenu: (String) -> Unit,
    onPickModel: (String, String) -> Unit,
) {
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
                ProviderRow(
                    line = line,
                    menu = modelMenu(line.key, settings),
                    menuOpen = openMenu == line.key,
                    onEdit = { onEdit(line.key) },
                    onToggleMenu = { onToggleMenu(line.key) },
                    onPick = { onPickModel(line.key, it) },
                )
            }
        }
    }
}

@Composable
private fun ProviderRow(
    line: ProviderLine,
    menu: ModelMenuSpec?,
    menuOpen: Boolean,
    onEdit: () -> Unit,
    onToggleMenu: () -> Unit,
    onPick: (String) -> Unit,
) {
    val linked = line.status == LinkStatus.Connected || line.status == LinkStatus.Configured
    Box {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(54.dp)
                .clickable(enabled = line.editable, onClick = onEdit)
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
                Text(
                    text = line.detail,
                    style = Type.mono.copy(color = if (menuOpen) Palette.clayDeep else Palette.ink3),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.width(Spacing.block))
            Text(line.status.label(), style = Type.caption.copy(color = line.status.tint()))
            Spacer(Modifier.width(10.dp))
            StatusDot(linked)
            if (menu != null) {
                Spacer(Modifier.width(6.dp))
                Box(
                    modifier = Modifier
                        .size(24.dp)
                        .clip(CircleShape)
                        .clickable(onClick = onToggleMenu),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        FluentIcons.ChevRight,
                        contentDescription = if (menuOpen) "收起模型" else "选择模型",
                        modifier = Modifier
                            .size(14.dp)
                            .rotate(if (menuOpen) -90f else 90f),
                        tint = if (menuOpen) Palette.clayDeep else Palette.ink4,
                    )
                }
            }
        }
        if (menu != null && menuOpen) {
            ModelMenu(menu = menu, onPick = onPick, onDismiss = onToggleMenu)
        }
    }
}

private fun LinkStatus.label(): String = when (this) {
    LinkStatus.Connected -> "已连通"
    LinkStatus.Configured -> "已配置"
    LinkStatus.Unconfigured -> "未配置"
    LinkStatus.Failed -> "连接失败"
    LinkStatus.NotSupported -> "未接入"
}

private fun LinkStatus.tint(): Color = when (this) {
    LinkStatus.Connected -> Palette.olive
    LinkStatus.Configured -> Palette.ink2
    LinkStatus.Unconfigured -> Palette.ink4
    LinkStatus.Failed -> Palette.danger
    LinkStatus.NotSupported -> Palette.ink4
}

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
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onEdit),
    ) {
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
                style = Type.uiStrong.copy(color = Palette.clayDeep),
                modifier = Modifier.align(Alignment.TopEnd),
            )
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
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onProbe),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 54.dp)
                .padding(horizontal = 14.dp, vertical = 6.dp),
            verticalArrangement = Arrangement.Center,
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    FluentIcons.Plug,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                    tint = Palette.ink3,
                )
                Spacer(Modifier.width(Spacing.iconText))
                Text("连接自检", style = Type.uiStrong, modifier = Modifier.weight(1f))
                elapsed?.let {
                    Text(formatElapsed(it), style = Type.mono)
                    Spacer(Modifier.width(6.dp))
                }
                Icon(
                    FluentIcons.ChevRight,
                    contentDescription = null,
                    modifier = Modifier.size(14.dp),
                    tint = Palette.ink4,
                )
            }
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(16.dp),
                contentAlignment = Alignment.CenterStart,
            ) {
                shown?.let {
                    Text(
                        text = it,
                        style = Type.caption.copy(color = Palette.ink3),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

private fun formatElapsed(ms: Long): String = "%.1f 秒".format(ms / 1000f)

private const val ProviderPrefix = "provider:"

/** ChatViewModel 注入的两行沿用旧 key，把它对到目录 id 上。 */
private fun injectedKey(providerId: String): String =
    if (providerId == TextProviderCatalog.GeminiId) "analysis" else providerId

private fun hostOf(provider: TextProvider): String =
    provider.endpoint.substringAfter("://").substringBefore('/').ifBlank { "未填写" }

private fun statusOf(
    provider: TextProvider,
    settings: AnalysisSettings,
    probe: Map<String, LinkStatus>,
): LinkStatus = when {
    settings.apiKey.isBlank() -> LinkStatus.Unconfigured
    probe[injectedKey(provider.id)] == LinkStatus.Connected -> LinkStatus.Connected
    else -> LinkStatus.Configured
}

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
 * 文本分析组：供应商一级，模型一级。行内只留姓名、状态与真实参数。
 */
@Composable
private fun AnalysisGroup(
    settings: AnalysisSettings,
    lines: List<ProviderLine>,
    expanded: String?,
    onExpand: (String) -> Unit,
    onEdit: (String) -> Unit,
    onPickModel: (TextProvider, TextModel) -> Unit,
) {
    val probe = lines.associate { it.key to it.status }
    GroupLabel("文本分析")
    Surface(
        color = Palette.card,
        shape = RoundedCornerShape(Radius.card),
        border = BorderStroke(1.dp, Palette.hair),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column {
            TextProviderCatalog.all.forEachIndexed { index, provider ->
                if (index > 0) GroupDivider()
                val active = provider.id == settings.provider
                Box {
                    SupplierRow(
                        provider = provider,
                        detail = if (active) settings.model.ifBlank { "未填模型" } else hostOf(provider),
                        status = if (active) statusOf(provider, settings, probe) else null,
                        expanded = expanded == provider.id,
                        canExpand = provider.models.isNotEmpty(),
                        onToggle = { onExpand(provider.id) },
                        onEdit = { onEdit("$ProviderPrefix${provider.id}") },
                    )
                    // 模型清单跟实时对话那组共用同一个 320 宽浮层，压在后续行之上，不把分组撑高。
                    if (expanded == provider.id) {
                        ModelMenu(
                            menu = ModelMenuSpec(
                                options = provider.models.map { model ->
                                    ModelOption(model.id, metaLine(model).ifBlank { null })
                                },
                                selected = if (active) settings.model else "",
                            ),
                            onPick = { id ->
                                provider.models.firstOrNull { it.id == id }?.let { onPickModel(provider, it) }
                            },
                            onDismiss = { onExpand(provider.id) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SupplierRow(
    provider: TextProvider,
    detail: String,
    status: LinkStatus?,
    expanded: Boolean,
    canExpand: Boolean,
    onToggle: () -> Unit,
    onEdit: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(54.dp)
            .clickable(onClick = onEdit)
            .padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                text = provider.label,
                style = Type.uiStrong,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = detail,
                style = Type.mono.copy(color = if (expanded) Palette.clayDeep else Palette.ink3),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        status?.let {
            Spacer(Modifier.width(Spacing.block))
            Text(it.label(), style = Type.caption.copy(color = it.tint()))
            Spacer(Modifier.width(10.dp))
            StatusDot(it == LinkStatus.Connected || it == LinkStatus.Configured)
        }
        if (canExpand) {
            Spacer(Modifier.width(6.dp))
            Box(
                modifier = Modifier
                    .size(24.dp)
                    .clip(CircleShape)
                    .clickable(onClick = onToggle),
                contentAlignment = Alignment.Center,
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

@Composable
private fun ProviderDialog(
    provider: TextProvider,
    settings: AnalysisSettings,
    onSave: (Map<String, String>) -> Unit,
    onDismiss: () -> Unit,
) {
    var kind by remember(provider.id) {
        mutableStateOf(settings.authKind ?: provider.auth.kind)
    }
    val drafts = remember(provider.id) {
        listOf(
            "endpoint" to settings.endpoint,
            "model" to settings.model,
            "apiKey" to settings.apiKey,
            "name" to settings.authName.ifBlank { provider.auth.name },
            "prefix" to settings.authPrefix.ifBlank { provider.auth.prefix },
        ).associate { (key, value) -> key to mutableStateOf(value) }
    }
    Dialog(onDismissRequest = onDismiss) {
        Surface(
            color = Palette.card,
            shape = RoundedCornerShape(Radius.sheet),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(Modifier.padding(20.dp)) {
                Text(provider.label, style = Type.display)
                Spacer(Modifier.height(16.dp))
                if (provider.custom) {
                    AuthKindChips(kind) { kind = it }
                    Spacer(Modifier.height(12.dp))
                }
                fieldsOf(provider, kind).forEach { field ->
                    val state = drafts.getValue(field.key)
                    EditField(
                        label = field.label,
                        value = state.value,
                        secret = field.secret,
                        singleLine = true,
                        onChange = { state.value = it },
                    )
                    Spacer(Modifier.height(12.dp))
                }
                Spacer(Modifier.height(8.dp))
                Surface(
                    color = Palette.clay,
                    shape = RoundedCornerShape(Radius.pill),
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable {
                            onSave(
                                drafts.mapValues { (_, state) -> state.value } + ("authKind" to kind.name),
                            )
                        },
                ) {
                    Box(Modifier.padding(vertical = 13.dp), contentAlignment = Alignment.Center) {
                        Text("保存", style = Type.uiStrong.copy(color = Color.White))
                    }
                }
            }
        }
    }
}

private data class DialogField(val key: String, val label: String, val secret: Boolean = false)

private fun fieldsOf(provider: TextProvider, kind: AuthKind): List<DialogField> = buildList {
    if (provider.custom) {
        add(DialogField("endpoint", "端点"))
        add(DialogField("model", "模型"))
    }
    add(DialogField("apiKey", "密钥", secret = true))
    if (provider.custom && kind != AuthKind.Bearer) {
        add(DialogField("name", if (kind == AuthKind.Header) "请求头" else "查询参数"))
    }
    if (provider.custom && kind == AuthKind.Header) {
        add(DialogField("prefix", "前缀"))
    }
}

@Composable
private fun AuthKindChips(selected: AuthKind, onPick: (AuthKind) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        AuthKind.entries.forEach { kind ->
            val on = kind == selected
            Surface(
                color = if (on) Palette.clayTint else Palette.tint,
                shape = RoundedCornerShape(Radius.chip),
                modifier = Modifier.clickable { onPick(kind) },
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

private data class FieldSpec(
    val label: String,
    val value: String,
    val secret: Boolean = false,
    val singleLine: Boolean = true,
)

@Composable
private fun EditDialog(
    title: String,
    specs: List<FieldSpec>,
    onSave: (List<String>) -> Unit,
    onDismiss: () -> Unit,
) {
    val drafts = remember(specs) { specs.map { it.value }.toMutableStateList() }
    Dialog(onDismissRequest = onDismiss) {
        Surface(
            color = Palette.card,
            shape = RoundedCornerShape(Radius.sheet),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(Modifier.padding(20.dp)) {
                Text(title, style = Type.display)
                Spacer(Modifier.height(16.dp))
                specs.forEachIndexed { index, spec ->
                    EditField(
                        label = spec.label,
                        value = drafts[index],
                        secret = spec.secret,
                        singleLine = spec.singleLine,
                        onChange = { drafts[index] = it },
                    )
                    Spacer(Modifier.height(12.dp))
                }
                Spacer(Modifier.height(8.dp))
                Surface(
                    color = Palette.clay,
                    shape = RoundedCornerShape(Radius.pill),
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onSave(drafts.toList()) },
                ) {
                    Box(Modifier.padding(vertical = 13.dp), contentAlignment = Alignment.Center) {
                        Text("保存", style = Type.uiStrong.copy(color = Color.White))
                    }
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
        Text(label, style = Type.caption.copy(color = Palette.ink3))
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
                textStyle = Type.ui.copy(color = Palette.ink),
                visualTransformation = if (secret) PasswordVisualTransformation() else VisualTransformation.None,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = if (singleLine) 44.dp else 120.dp)
                    .padding(horizontal = 12.dp, vertical = 12.dp),
            )
        }
    }
}
