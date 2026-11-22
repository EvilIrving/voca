package com.parley.speaker.ui.session

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
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
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Icon
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
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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
 * 再往下按今天、7天内、更早摊开会话，右下角只剩配置中心。
 * 主卡片右推一个抽屉宽、等比缩到 0.94、圆角长到 32 并垫一层暖影，白蒙层压到六成。
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
    val RowIcon = 16.dp

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

    /** 收起时抽屉只提前让出半个身位，推开过程中它跟着卡片一起走，不是原地等露出。 */
    const val Parallax = 0.5f

    val CardRadius = 32.dp
    val CardShadow = 20.dp
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

    fun drag(deltaFraction: Float) {
        animation?.cancel()
        fraction.floatValue = (fraction.floatValue + deltaFraction).coerceIn(0f, 1f)
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
 * 点卡片或朝左拖都能收起；收起状态下不留任何整屏的手势层。
 */
@Composable
fun DrawerScaffold(
    state: DrawerState,
    drawer: @Composable () -> Unit,
    content: @Composable () -> Unit,
) {
    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .clipToBounds(),
    ) {
        val width = drawerWidth(maxWidth)
        val density = LocalDensity.current
        val widthPx = with(density) { width.toPx() }
        val velocityPx = with(density) { SessionDrawerSpec.VelocityThreshold.toPx() }
        val radiusPx = with(density) { SessionDrawerSpec.CardRadius.toPx() }
        val guardPx = with(density) { SessionDrawerSpec.SwipeGuardBottom.toPx() }
        val heightPx = with(density) { maxHeight.toPx() }
        val shadowPx = with(density) { SessionDrawerSpec.CardShadow.toPx() }
        val controls = remember(state, widthPx, velocityPx) {
            DrawerControls(state, widthPx, velocityPx)
        }
        // 遮罩只跟着抽屉一起生灭：收起状态下不留任何指针节点，主界面的顶栏、气泡与输入条照常响应。
        val shown = remember(state) { derivedStateOf { state.fraction.floatValue > 0f } }

        // 抽屉垫在最底下，收起时只让出半个身位，推开过程中跟着卡片一起挪。
        Box(
            modifier = Modifier
                .align(Alignment.CenterStart)
                .width(width)
                .fillMaxHeight()
                .offset {
                    val p = state.fraction.floatValue
                    IntOffset((widthPx * (p - 1f) * SessionDrawerSpec.Parallax).roundToInt(), 0)
                },
        ) {
            drawer()
        }

        // 主卡片：右移一个抽屉宽，等比缩到 0.94，圆角与暖影随进度长出来，白蒙层压到六成。
        // 拉开抽屉的横滑挂在这张卡片的祖先上：它是内容自己的父节点，不是盖在上面的兄弟，
        // 所以底下的气泡、列表照常收到事件，抽屉里的行也不受它牵连。
        Box(
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(controls, guardPx, heightPx) {
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        // 底栏那条带子留给录音，抽屉开着时也不归这一层管。
                        if (down.position.y > heightPx - guardPx) return@awaitEachGesture
                        if (controls.state.current > 0.001f) return@awaitEachGesture
                        val tracker = VelocityTracker().apply { addPosition(down.uptimeMillis, down.position) }
                        var last = down.position.x
                        var dragged = false
                        while (true) {
                            val event = awaitPointerEvent()
                            val change = event.changes.firstOrNull { it.id == down.id } ?: break
                            if (!change.pressed) {
                                if (dragged) change.consume()
                                break
                            }
                            val dx = change.position.x - last
                            last = change.position.x
                            if (!dragged && change.position.x - down.position.x > viewConfiguration.touchSlop) {
                                dragged = true
                            }
                            if (dragged) {
                                tracker.addPosition(change.uptimeMillis, change.position)
                                controls.drag(dx)
                                change.consume()
                            }
                        }
                        if (dragged) controls.settle(tracker.calculateVelocity().x)
                    }
                }
                .graphicsLayer {
                    val p = state.fraction.floatValue
                    translationX = widthPx * p
                    val scale = 1f - (1f - SessionDrawerSpec.ContentScale) * p
                    scaleX = scale
                    scaleY = scale
                    transformOrigin = TransformOrigin(0.5f, 0.5f)
                    shape = RoundedCornerShape(radiusPx * p)
                    clip = p > 0.001f
                    shadowElevation = shadowPx * p
                    spotShadowColor = Palette.shade
                    ambientShadowColor = Palette.shade
                },
        ) {
            content()
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .drawBehind {
                        val alpha = SessionDrawerSpec.VeilAlpha * state.fraction.floatValue
                        if (alpha > 0f) drawRect(Palette.card.copy(alpha = alpha))
                    }
                    .then(if (shown.value) Modifier.drawerGesture(controls) else Modifier),
            )
        }
    }
}

@Stable
private class DrawerControls(
    val state: DrawerState,
    private val widthPx: Float,
    private val velocityPx: Float,
) {
    fun drag(pixels: Float) {
        if (widthPx <= 0f) return
        state.drag(pixels / widthPx)
    }

    fun settle(velocity: Float) {
        val open = drawerTarget(state.current, velocity, velocityPx)
        if (open) state.open() else state.close()
    }
}

/**
 * 卡片上的一层手势：点一下收起，横向拖动跟手把抽屉拖回去，松手按速度与行程落档。
 * 抽屉里的行与卡片不相交，这里不抢它们的按压。
 */
private fun Modifier.drawerGesture(controls: DrawerControls): Modifier = pointerInput(controls) {
    awaitEachGesture {
        // 必须先等一次按下再判，不能先看状态就返回：awaitEachGesture 的循环体返回后会去等所有手指抬起，
        // 抽屉收起时没有手指，这一步立即返回，整个循环变成主线程上不释放的旋转。
        val down = awaitFirstDown(requireUnconsumed = false)
        if (controls.state.current <= 0.001f) return@awaitEachGesture
        down.consume()
        val tracker = VelocityTracker().apply { addPosition(down.uptimeMillis, down.position) }
        var last = down.position.x
        var dragged = false
        while (true) {
            val event = awaitPointerEvent()
            val change = event.changes.firstOrNull { it.id == down.id } ?: break
            if (!change.pressed) {
                change.consume()
                break
            }
            val dx = change.position.x - last
            last = change.position.x
            if (!dragged && abs(change.position.x - down.position.x) > viewConfiguration.touchSlop) {
                dragged = true
            }
            if (dragged) {
                tracker.addPosition(change.uptimeMillis, change.position)
                controls.drag(dx)
                change.consume()
            }
        }
        if (dragged) {
            controls.settle(tracker.calculateVelocity().x)
        } else {
            controls.state.close()
        }
    }
}

/**
 * 抽屉正文：右侧检索、新建会话、按天摊开的会话，右下角配置中心。
 * 正在看的那条垫陶土浅底；这里不放改名、删除这类管理动作。
 */
@Composable
fun SessionDrawer(
    sessions: List<SessionRecord>,
    viewingId: String?,
    open: Boolean,
    onNewSession: () -> Unit,
    onPick: (String) -> Unit,
    onSettings: () -> Unit,
) {
    var query by remember { mutableStateOf("") }
    var searching by remember { mutableStateOf(false) }
    val focus = remember { FocusRequester() }

    // 抽屉收起时检索跟着退场，下次拉开不会带着上一条关键字。
    LaunchedEffect(open) {
        if (!open) {
            searching = false
            query = ""
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
        if (searching) {
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

        Spacer(Modifier.height(SessionDrawerSpec.HeaderGap))
        NewSessionPill(onNewSession)
        Spacer(Modifier.height(SessionDrawerSpec.ListGap))

        if (model.groups.isEmpty()) {
            Text(
                text = if (query.isBlank()) "还没有会话" else "没有匹配的会话",
                style = Type.captionInk4,
                modifier = Modifier.padding(start = 6.dp, top = 12.dp),
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
                        SessionRow(record, model.isCurrent(record)) { onPick(record.id) }
                    }
                }
            }
        }

        DrawerDock(onSettings)
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
            .border(BorderStroke(1.dp, Palette.hair), RoundedCornerShape(Radius.pill))
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
        border = BorderStroke(1.dp, Palette.hair),
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
        modifier = Modifier.padding(start = 6.dp, top = 12.dp, bottom = 4.dp),
    )
}

@Composable
private fun SessionRow(record: SessionRecord, current: Boolean, onClick: () -> Unit) {
    Pressable(
        modifier = Modifier
            .fillMaxWidth()
            .height(SessionDrawerSpec.RowHeight),
        shape = RoundedCornerShape(Radius.drawerRow),
        resting = if (current) Palette.clayTint else Color.Transparent,
        onClick = onClick,
        contentAlignment = Alignment.CenterStart,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 6.dp, end = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                FluentIcons.Chat,
                contentDescription = null,
                modifier = Modifier.size(SessionDrawerSpec.RowIcon),
                tint = if (current) Palette.clayDeep else Palette.ink4,
            )
            Spacer(Modifier.width(Spacing.iconText))
            Text(
                text = record.name.ifBlank { UntitledSession },
                style = if (current) Type.uiStrong else Type.uiStrongInk2,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
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
            contentDescription = "配置中心",
            onClick = onSettings,
            size = SessionDrawerSpec.SettingsButton,
            iconSize = SessionDrawerSpec.SettingsIcon,
            tint = Palette.ink2,
            container = Palette.card,
            borderColor = Palette.hair,
        )
    }
}
