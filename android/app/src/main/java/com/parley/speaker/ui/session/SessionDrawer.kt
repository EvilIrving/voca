package com.parley.speaker.ui.session

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.AwaitPointerEventScope
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.parley.speaker.data.SessionGroup
import com.parley.speaker.data.SessionRecord
import com.parley.speaker.data.groupedByDay
import com.parley.speaker.ui.component.ParleyIconButton
import com.parley.speaker.ui.component.Pressable
import com.parley.speaker.ui.icon.FluentIcons
import com.parley.speaker.ui.theme.Palette
import com.parley.speaker.ui.theme.Radius
import com.parley.speaker.ui.theme.Spacing
import com.parley.speaker.ui.theme.Type
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

private const val UntitledSession = "本次会话"

/**
 * 抽屉照「侧边抽屉交互原型」取：右侧一枚检索，下面一条新建会话的白胶囊，
 * 再往下按今天、7天内、更早摊开会话，右下角只剩设置页。
 * 主卡片右推一个抽屉宽、等比缩到 0.94、圆角跟着进度长到 32 并垫一层暖影，白蒙层压到六成。
 */
object SessionDrawerSpec {
    val WidthRatio = 0.82f
    val MaxWidth = 320.dp
    val Peek = 48.dp

    val SearchButton = 36.dp
    val SearchIcon = 20.dp
    val SearchFieldHeight = 36.dp

    val NewRowHeight = 52.dp
    val NewGlyph = 24.dp

    val RowHeight = 44.dp

    /** 选择态的勾选框与批量条，尺寸取自画板 12c。 */
    val CheckBox = 18.dp
    val CheckBoxRadius = 5.dp
    val CheckGlyph = 12.dp
    val BatchButtonHeight = 32.dp

    val SettingsButton = 44.dp
    val SettingsIcon = 22.dp

    /** 底栏那条录音带不让位：从它上面起手的横滑不算拉开抽屉，免得与长按录音抢。 */
    val SwipeGuardBottom = 96.dp
    val HeaderHeight = 44.dp
    val HeaderGap = 8.dp
    val ListGap = 14.dp
    val DockPadding = 12.dp

    val VelocityThreshold = 250.dp
    val OpenMs = 300
    val CloseMs = 240

    const val ContentScale = 0.94f
    const val VeilAlpha = 0.6f

    val CardShadow = 20.dp

    /**
     * 卡片圆角只跟进度走：前半程零到二十六，后半程二十六到三十二。
     * 中间帧落在二十六，与画板 02 同一档；直接把三十二按进度摊开的话中间帧只有十六，
     * 那是生词卡的档位，推到一半的这张纸看着还不像一张卡。
     */
    fun cardRadius(progress: Float): Dp {
        val p = progress.coerceIn(0f, 1f)
        return if (p <= 0.5f) {
            Radius.drawerCardMid * (p / 0.5f)
        } else {
            Radius.drawerCardMid + (Radius.drawerCard - Radius.drawerCardMid) * ((p - 0.5f) / 0.5f)
        }
    }
}

/** 原型给的曲线：起步快、收尾长，cubic-bezier(0.16, 1, 0.3, 1)。 */
private val DrawerEasing = CubicBezierEasing(0.16f, 1f, 0.3f, 1f)

/** 屏宽决定抽屉宽：比例与上下限一起收，窄屏也不会把抽屉撑满。 */
fun drawerWidth(screen: Dp): Dp = minOf(
    SessionDrawerSpec.MaxWidth,
    screen * SessionDrawerSpec.WidthRatio,
    screen - SessionDrawerSpec.Peek,
)

/**
 * 松手后落哪一档：速度先说话，速度不够就看走过没走过半程。
 * 门限速度由调用方按密度换算成像素每秒；进度两边共用，开与关是同一个判据。
 */
internal fun drawerTarget(
    progress: Float,
    velocityPx: Float,
    velocityPxThreshold: Float,
): Boolean = when {
    velocityPx > velocityPxThreshold -> true
    velocityPx < -velocityPxThreshold -> false
    else -> progress > 0.5f
}

/**
 * 抽屉进度只有一个来源：0 收起、1 展开。拖动直接写这个值，跟手；松手才交给一段动画收尾，
 * 动画中途再次起手会把它掐掉。进度给界面的是 State，位移在布局阶段读取，逐帧不重组。
 */
@Stable
class DrawerState internal constructor(private val scope: CoroutineScope) {

    internal val fraction = mutableFloatStateOf(0f)

    val progress: State<Float> get() = fraction

    val current: Float get() = fraction.floatValue

    private var animation: Job? = null

    fun open() = animateTo(1f, SessionDrawerSpec.OpenMs)

    fun close() = animateTo(0f, SessionDrawerSpec.CloseMs)

    /** 拖动抢在动画前头：原地掐掉在跑的那一段，从当前进度接着走，不回跳也不多跑一帧。 */
    fun dragTo(progress: Float) {
        animation?.cancel()
        animation = null
        fraction.floatValue = progress.coerceIn(0f, 1f)
    }

    private fun animateTo(target: Float, durationMs: Int) {
        animation?.cancel()
        val start = fraction.floatValue
        if (start == target) return
        animation = scope.launch {
            animate(
                start,
                target,
                animationSpec = tween(durationMs, easing = DrawerEasing),
            ) { value, _ -> fraction.floatValue = value }
        }
    }
}

@Composable
fun rememberDrawerState(): DrawerState {
    val scope = rememberCoroutineScope()
    return remember(scope) { DrawerState(scope) }
}

/**
 * 抽屉外壳：抽屉垫在底层，主卡片带着白蒙层向右推，露出左边这一条。
 * 往右拉出来与往左推回去是同一段手势，卡片与抽屉上都算数；点卡片、按返回键也能收起。
 */
@Composable
fun DrawerScaffold(
    state: DrawerState,
    drawer: @Composable () -> Unit,
    content: @Composable () -> Unit,
) {
    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val width = drawerWidth(maxWidth)
        val density = LocalDensity.current
        val widthPx = with(density) { width.toPx() }
        val velocityPx = with(density) { SessionDrawerSpec.VelocityThreshold.toPx() }
        val guardPx = with(density) { SessionDrawerSpec.SwipeGuardBottom.toPx() }
        val heightPx = with(density) { maxHeight.toPx() }
        val shadowPx = with(density) { SessionDrawerSpec.CardShadow.toPx() }
        val controls = remember(state, widthPx, velocityPx) {
            DrawerControls(state, widthPx, velocityPx)
        }
        // 遮罩只跟着抽屉一起生灭：收起状态下不留任何指针节点，主界面的顶栏、气泡与输入条照常响应。
        val shown = remember(state) { derivedStateOf { state.fraction.floatValue > 0f } }

        // 手势层挂在没被变换的这一层上，抽屉与卡片都是它的孩子：指针坐标因此就是屏幕坐标。
        // 挂在卡片自己身上时，坐标要过一遍那一层的位移与缩放，拖得越快卡片跟得越少，
        // 手指与卡片互相拖，手感就是推不动、一碰就弹回去。手指走出一段横滑之前这一层不收事件。
        Box(
            modifier = Modifier
                .fillMaxSize()
                .clipToBounds()
                .pointerInput(controls, guardPx, heightPx) {
                    awaitEachGesture {
                        // 必须先取到按下再判状态：循环体返回后 awaitEachGesture 会去等所有手指抬起，
                        // 抽屉收起时没有手指，这一步立即返回，整段就成了主线程上不释放的死转。
                        val down = awaitFirstDown(requireUnconsumed = false)
                        // 底栏那条带子归录音，从它上面起手不拉抽屉。
                        if (down.position.y > heightPx - guardPx) return@awaitEachGesture
                        var origin = 0f
                        var anchored = false
                        val (dragged, velocity) = trackHorizontal(
                            down = down,
                            canDragLeft = controls.state.current > 0.001f,
                        ) { dx ->
                            // 真正拖起来的那一刻才掐动画：点一下不该把还在走的那一段停死。
                            if (!anchored) {
                                origin = controls.state.current
                                anchored = true
                            }
                            controls.dragTo(origin, dx)
                        }
                        if (dragged) controls.settle(velocity)
                    }
                },
        ) {
            // 抽屉垫在最底下，收起时整个藏在卡片身后；推开时与卡片同一个位移，
            // 露出来的那一条永远正好是卡片右侧让出的宽度。
            Box(
                modifier = Modifier
                    .align(Alignment.CenterStart)
                    .width(width)
                    .fillMaxHeight()
                    .offset { IntOffset((widthPx * (state.fraction.floatValue - 1f)).roundToInt(), 0) },
            ) {
                drawer()
            }

            // 主卡片：右移一个抽屉宽，等比缩到 0.94，圆角与暖影随进度长出来，白蒙层压到六成。
            // 纸底写在图层里面：写在图层外面就是一块不动的整屏纸，把抽屉整个盖掉；
            // 写在里面则跟着缩放与位移一起走，抽屉什么时候也不会从卡片里透出来。
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        val p = state.fraction.floatValue
                        translationX = widthPx * p
                        val scale = 1f - (1f - SessionDrawerSpec.ContentScale) * p
                        scaleX = scale
                        scaleY = scale
                        transformOrigin = TransformOrigin(0.5f, 0.5f)
                        shape = RoundedCornerShape(SessionDrawerSpec.cardRadius(p))
                        clip = p > 0.001f
                        shadowElevation = shadowPx * p
                        spotShadowColor = Palette.shade
                        ambientShadowColor = Palette.shade
                    }
                    .background(Palette.paper),
            ) {
                content()
                if (shown.value) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .drawBehind {
                                val alpha = SessionDrawerSpec.VeilAlpha * state.fraction.floatValue
                                if (alpha > 0f) drawRect(Palette.card.copy(alpha = alpha))
                            }
                            // 抽屉开着时卡片整块让位：点一下收起，卡片里的按钮不再被按到。
                            .clickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = null,
                            ) { state.close() },
                    )
                }
            }
        }
    }
}

@Stable
private class DrawerControls(
    val state: DrawerState,
    private val widthPx: Float,
    private val velocityPx: Float,
) {
    fun dragTo(origin: Float, pixels: Float) {
        if (widthPx <= 0f) return
        state.dragTo(origin + pixels / widthPx)
    }

    fun settle(velocity: Float) {
        val open = drawerTarget(state.current, velocity, velocityPx)
        if (open) state.open() else state.close()
    }
}

/**
 * 一段横滑：判定成横滑之后把位移喂给进度，抬手按速度与行程落档。
 * 竖向上先破的那一下属于列表滚动；抽屉收起时往左推也没有东西可推，两种都当没发生。
 * 抬手那一帧的新坐标也照算——手指快的时候中间一个移动事件都没有，位置只在抬手时换过，
 * 少算这一帧，快甩就会像什么都没发生。
 */
private suspend fun AwaitPointerEventScope.trackHorizontal(
    down: PointerInputChange,
    canDragLeft: Boolean,
    onDrag: (Float) -> Unit,
): Pair<Boolean, Float> {
    val tracker = VelocityTracker().apply { addPosition(down.uptimeMillis, down.position) }
    var last = down.position.x
    var dragged = false
    var velocity = 0f
    while (true) {
        val event = awaitPointerEvent()
        val change = event.changes.firstOrNull { it.id == down.id } ?: break
        val dx = change.position.x - last
        last = change.position.x
        if (!dragged) {
            val travelX = change.position.x - down.position.x
            val travelY = change.position.y - down.position.y
            if (abs(travelX) > viewConfiguration.touchSlop || abs(travelY) > viewConfiguration.touchSlop) {
                if (abs(travelY) > abs(travelX)) return false to 0f
                if (travelX < 0f && !canDragLeft) return false to 0f
                dragged = true
            }
        }
        if (dragged) {
            tracker.addPosition(change.uptimeMillis, change.position)
            if (dx != 0f) {
                onDrag(change.position.x - down.position.x)
                change.consume()
            }
        }
        if (!change.pressed) {
            velocity = tracker.calculateVelocity().x
            break
        }
    }
    return dragged to velocity
}

/**
 * 抽屉正文：右侧检索、新建会话、按天摊开的会话，右下角设置页。
 * 正在看的那条垫陶土浅底；长按可呼出重命名与删除操作菜单。
 */
@Composable
fun SessionDrawer(
    sessions: List<SessionRecord>,
    viewingId: String?,
    open: Boolean,
    onNewSession: () -> Unit,
    onPick: (String) -> Unit,
    onSettings: () -> Unit,
    onDelete: (String) -> Unit = {},
    onRename: (String, String) -> Unit = { _, _ -> },
    onDeleteMany: (List<String>) -> Unit = {},
) {
    var query by remember { mutableStateOf("") }
    var searching by remember { mutableStateOf(false) }
    var activeRecord by remember { mutableStateOf<SessionRecord?>(null) }
    var renamingRecord by remember { mutableStateOf<SessionRecord?>(null) }
    var selecting by remember { mutableStateOf(false) }
    var selected by remember { mutableStateOf(emptySet<String>()) }
    val focus = remember { FocusRequester() }

    // 选择态先于抽屉收：返回键与取消都只退选择，抽屉还停在原处。
    BackHandler(enabled = selecting) {
        selecting = false
        selected = emptySet()
    }

    // 抽屉收起时检索跟着退场，下次拉开不会带着上一条关键字。
    LaunchedEffect(open) {
        if (!open) {
            searching = false
            query = ""
            activeRecord = null
            renamingRecord = null
            selecting = false
            selected = emptySet()
        }
    }
    LaunchedEffect(searching) { if (searching) focus.requestFocus() }

    val model = remember(sessions, viewingId, query) { sessionDrawerModel(sessions, viewingId, query) }

    Column(
        modifier = Modifier
            .fillMaxHeight()
            .background(Palette.paper)
            .windowInsetsPadding(WindowInsets.statusBars)
            .windowInsetsPadding(WindowInsets.navigationBars.union(WindowInsets.ime))
            .padding(horizontal = Spacing.drawer),
    ) {
        if (selecting) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(SessionDrawerSpec.HeaderHeight),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("选择会话", style = Type.display, modifier = Modifier.weight(1f))
                Text(
                    text = "取消",
                    style = Type.uiStrongInk2,
                    modifier = Modifier
                        .clip(RoundedCornerShape(Radius.chip))
                        .clickable {
                            selecting = false
                            selected = emptySet()
                        }
                        .padding(horizontal = 4.dp, vertical = 2.dp),
                )
            }
        } else if (searching) {
            SearchField(
                query = query,
                focus = focus,
                onQueryChange = { query = it },
                onClose = {
                    searching = false
                    query = ""
                },
            )
        } else {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(SessionDrawerSpec.HeaderHeight),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("会话", style = Type.display, modifier = Modifier.weight(1f))
                ParleyIconButton(
                    icon = FluentIcons.Search,
                    contentDescription = "搜索会话",
                    onClick = { searching = true },
                    size = SessionDrawerSpec.SearchButton,
                    iconSize = SessionDrawerSpec.SearchIcon,
                    tint = Palette.ink2,
                )
            }
        }

        if (!selecting) {
            Spacer(Modifier.height(SessionDrawerSpec.HeaderGap))
            NewSessionPill(onNewSession)
        }
        Spacer(Modifier.height(SessionDrawerSpec.ListGap))

        if (model.groups.isEmpty()) {
            Text(
                text = if (query.isBlank()) "还没有会话" else "没有匹配的会话",
                style = Type.captionInk4,
                modifier = Modifier.padding(start = 4.dp, top = 12.dp),
            )
            Spacer(Modifier.weight(1f))
        } else {
            LazyColumn(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
            ) {
                model.groups.forEach { group ->
                    item(key = "group-${group.label}") { GroupLabel(group) }
                    items(group.sessions, key = { it.id }) { record ->
                        SessionRow(
                            record = record,
                            current = model.isCurrent(record),
                            selecting = selecting,
                            checked = record.id in selected,
                            onClick = {
                                if (selecting) {
                                    selected = if (record.id in selected) selected - record.id else selected + record.id
                                } else {
                                    onPick(record.id)
                                }
                            },
                            onLongClick = { if (!selecting) activeRecord = record },
                        )
                    }
                }
            }
        }

        if (selecting) {
            DrawerBatchBar(
                allSelected = model.groups.sumOf { it.sessions.size } > 0 &&
                    selected.size == model.groups.sumOf { it.sessions.size },
                count = selected.size,
                onSelectAll = {
                    val everything = model.groups.flatMap { group -> group.sessions.map { it.id } }.toSet()
                    selected = if (selected.size == everything.size) emptySet() else everything
                },
                onDelete = {
                    val ids = selected.toList()
                    selecting = false
                    selected = emptySet()
                    onDeleteMany(ids)
                },
            )
        } else {
            DrawerDock(onSettings)
        }
    }

    activeRecord?.let { record ->
        SessionActionDialog(
            record = record,
            onRename = {
                activeRecord = null
                renamingRecord = record
            },
            onBatchSelect = {
                activeRecord = null
                selecting = true
                selected = setOf(record.id)
            },
            onDelete = {
                activeRecord = null
                onDelete(record.id)
            },
            onDismiss = { activeRecord = null },
        )
    }

    renamingRecord?.let { record ->
        RenameSessionDialog(
            initialName = record.name,
            onSave = { newName ->
                renamingRecord = null
                onRename(record.id, newName)
            },
            onDismiss = { renamingRecord = null },
        )
    }
}

/** 抽屉里读完一份值：三段分组，正在看的那条标出来；检索先把名字过一遍再分组。 */
internal data class SessionDrawerModel(
    val groups: List<SessionGroup>,
    val currentId: String?,
) {
    fun isCurrent(record: SessionRecord): Boolean = record.id == currentId
}

internal fun sessionDrawerModel(
    sessions: List<SessionRecord>,
    viewingId: String?,
    query: String = "",
    now: Long = System.currentTimeMillis(),
): SessionDrawerModel {
    val keyword = query.trim()
    val matched = if (keyword.isEmpty()) {
        sessions
    } else {
        sessions.filter { it.name.contains(keyword, ignoreCase = true) }
    }
    return SessionDrawerModel(matched.groupedByDay(now), viewingId)
}

@Composable
private fun SearchField(
    query: String,
    focus: FocusRequester,
    onQueryChange: (String) -> Unit,
    onClose: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(SessionDrawerSpec.SearchFieldHeight)
            .background(Palette.card, RoundedCornerShape(Radius.pill))
            .border(BorderStroke(1.dp, Palette.hairSoft), RoundedCornerShape(Radius.pill))
            .padding(start = 12.dp, end = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            FluentIcons.Search,
            contentDescription = null,
            modifier = Modifier.size(16.dp),
            tint = Palette.ink4,
        )
        Spacer(Modifier.width(Spacing.iconText))
        BasicTextField(
            value = query,
            onValueChange = onQueryChange,
            singleLine = true,
            textStyle = Type.ui.copy(fontSize = 14.sp, color = Palette.ink),
            cursorBrush = SolidColor(Palette.clay),
            modifier = Modifier
                .weight(1f)
                .focusRequester(focus),
            decorationBox = { inner ->
                Box(contentAlignment = Alignment.CenterStart) {
                    if (query.isEmpty()) {
                        Text("搜索会话", style = Type.uiInk4.copy(fontSize = 14.sp))
                    }
                    inner()
                }
            },
        )
        ParleyIconButton(
            icon = FluentIcons.Dismiss,
            contentDescription = "收起搜索",
            onClick = onClose,
            size = 28.dp,
            iconSize = 14.dp,
            tint = Palette.ink4,
        )
    }
}

@Composable
private fun NewSessionPill(onClick: () -> Unit) {
    Pressable(
        modifier = Modifier
            .fillMaxWidth()
            .height(SessionDrawerSpec.NewRowHeight),
        shape = RoundedCornerShape(Radius.pill),
        resting = Palette.card,
        border = BorderStroke(1.dp, Palette.hairSoft),
        onClick = onClick,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 18.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                FluentIcons.ChatAdd,
                contentDescription = null,
                modifier = Modifier.size(SessionDrawerSpec.NewGlyph),
                tint = Palette.ink,
            )
            Spacer(Modifier.width(10.dp))
            Text("新会话", style = Type.title)
        }
    }
}

@Composable
private fun GroupLabel(group: SessionGroup) {
    Text(
        group.label,
        style = Type.captionInk4,
        modifier = Modifier.padding(start = 4.dp, top = 12.dp, bottom = 4.dp),
    )
}

@Composable
private fun SessionRow(
    record: SessionRecord,
    current: Boolean,
    selecting: Boolean,
    checked: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    Pressable(
        modifier = Modifier
            .fillMaxWidth()
            .height(SessionDrawerSpec.RowHeight)
            .padding(vertical = 2.dp),
        shape = RoundedCornerShape(Radius.drawerRow),
        resting = if (current && !selecting) Palette.clayTint else Color.Transparent,
        onClick = onClick,
        onLongClick = onLongClick,
        contentAlignment = Alignment.CenterStart,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = if (selecting) 6.dp else 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (selecting) {
                CheckBox(checked = checked)
                Spacer(Modifier.width(10.dp))
            }
            Text(
                text = record.name.ifBlank { UntitledSession },
                style = if (current && !selecting) Type.uiStrong else Type.uiStrongInk2,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** 勾选框：未选是细边空心，选中垫陶土色并把对勾压成纸白。 */
@Composable
private fun CheckBox(checked: Boolean) {
    Box(
        modifier = Modifier
            .size(SessionDrawerSpec.CheckBox)
            .clip(RoundedCornerShape(SessionDrawerSpec.CheckBoxRadius))
            .background(if (checked) Palette.clay else Color.Transparent)
            .border(
                width = if (checked) 0.dp else 1.dp,
                color = if (checked) Palette.clay else Palette.hairStrong,
                shape = RoundedCornerShape(SessionDrawerSpec.CheckBoxRadius),
            ),
        contentAlignment = Alignment.Center,
    ) {
        if (checked) {
            Icon(
                imageVector = FluentIcons.CheckMark,
                contentDescription = null,
                tint = Palette.card,
                modifier = Modifier.size(SessionDrawerSpec.CheckGlyph),
            )
        }
    }
}

/** 选择态的底栏：左边全选，右边那颗红药丸只在有条目被选中时才可点。 */
@Composable
private fun DrawerBatchBar(
    allSelected: Boolean,
    count: Int,
    onSelectAll: () -> Unit,
    onDelete: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(SessionDrawerSpec.RowHeight)
            .padding(top = 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            modifier = Modifier
                .clip(RoundedCornerShape(Radius.chip))
                .clickable(onClick = onSelectAll)
                .padding(horizontal = 6.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            CheckBox(checked = allSelected)
            Spacer(Modifier.width(8.dp))
            Text("全选", style = Type.uiStrong)
        }
        Spacer(Modifier.weight(1f))
        Pressable(
            enabled = count > 0,
            shape = RoundedCornerShape(Radius.pill),
            resting = Palette.danger,
            onClick = onDelete,
        ) {
            Box(
                modifier = Modifier
                    .height(SessionDrawerSpec.BatchButtonHeight)
                    .padding(horizontal = 14.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = "删除 $count 项",
                    style = Type.uiStrongOnDark.copy(fontSize = 13.sp),
                )
            }
        }
    }
}

@Composable
private fun SessionActionDialog(
    record: SessionRecord,
    onRename: () -> Unit,
    onBatchSelect: () -> Unit,
    onDelete: () -> Unit,
    onDismiss: () -> Unit,
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
                    .widthIn(max = 280.dp)
                    .fillMaxWidth(),
            ) {
                Column(Modifier.padding(16.dp)) {
                    Text(
                        text = record.name.ifBlank { UntitledSession },
                        style = Type.title,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Spacer(Modifier.height(14.dp))
                    Pressable(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(Radius.chip),
                        resting = Palette.tint,
                        onClick = onRename,
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(44.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text("重命名", style = Type.uiStrong)
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    Pressable(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(Radius.chip),
                        resting = Palette.tint,
                        onClick = onBatchSelect,
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(44.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text("批量选择", style = Type.uiStrong)
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp)
                            .height(1.dp)
                            .background(Palette.hair.copy(alpha = 0.5f)),
                    )
                    Spacer(Modifier.height(8.dp))
                    Pressable(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(Radius.chip),
                        resting = Palette.tint,
                        onClick = onDelete,
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(44.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text("删除", style = Type.uiStrong.copy(color = Palette.danger))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun RenameSessionDialog(
    initialName: String,
    onSave: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var text by remember(initialName) { mutableStateOf(initialName) }
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
                    .widthIn(max = 300.dp)
                    .fillMaxWidth(),
            ) {
                Column(Modifier.padding(20.dp)) {
                    Text("重命名会话", style = Type.display)
                    Spacer(Modifier.height(14.dp))
                    Surface(
                        color = Palette.tint,
                        shape = RoundedCornerShape(Radius.control),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        BasicTextField(
                            value = text,
                            onValueChange = { text = it },
                            singleLine = true,
                            textStyle = Type.uiInk,
                            cursorBrush = SolidColor(Palette.clay),
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(44.dp)
                                .padding(horizontal = 12.dp, vertical = 12.dp),
                            decorationBox = { inner ->
                                Box(contentAlignment = Alignment.CenterStart) {
                                    if (text.isEmpty()) {
                                        Text("会话名称", style = Type.uiInk4)
                                    }
                                    inner()
                                }
                            },
                        )
                    }
                    Spacer(Modifier.height(16.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Pressable(
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(Radius.pill),
                            resting = Palette.tint,
                            onClick = onDismiss,
                        ) {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(42.dp),
                                contentAlignment = Alignment.Center,
                            ) {
                                Text("取消", style = Type.uiStrongInk2)
                            }
                        }
                        Pressable(
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(Radius.pill),
                            resting = Palette.clay,
                            onClick = { onSave(text.trim()) },
                        ) {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(42.dp),
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
}

@Composable
private fun DrawerDock(onSettings: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = SessionDrawerSpec.DockPadding),
        horizontalArrangement = Arrangement.End,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ParleyIconButton(
            icon = FluentIcons.Settings,
            contentDescription = "设置",
            onClick = onSettings,
            size = SessionDrawerSpec.SettingsButton,
            iconSize = SessionDrawerSpec.SettingsIcon,
            tint = Palette.ink2,
        )
    }
}
