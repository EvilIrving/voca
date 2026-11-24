package com.parley.speaker.ui.component

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.dropShadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.shadow.Shadow
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.parley.speaker.domain.AnalysisStatus
import com.parley.speaker.domain.Speaker
import com.parley.speaker.domain.overallScore
import com.parley.speaker.domain.Turn
import com.parley.speaker.ui.icon.FluentIcons
import com.parley.speaker.ui.theme.Palette
import com.parley.speaker.ui.theme.Radius
import com.parley.speaker.ui.theme.Spacing
import com.parley.speaker.ui.theme.Type

/** 禁用的元素不另加灰，只把内容降到四成，底色与描边都保持原样。 */
private const val DisabledAlpha = 0.4f

/**
 * 按压底色的唯一一份定义：白卡与透明底转 tint，陶土浅底加深一档，陶土实底降到 clayDeep，
 * 中间那层 tint 再按下去落到 hair。涟漪、按压阴影、缩放三样一律不用。
 */
fun pressedTone(resting: Color): Color = when (resting) {
    Palette.clay -> Palette.clayDeep
    Palette.clayTint -> Palette.clayTintPressed
    Palette.tint -> Palette.hair
    else -> Palette.tint
}

/**
 * 全仓唯一一套按压反馈：按下只换一层底色，底色由静息色按 [pressedTone] 推出来，调用点不用各挑一次。
 * 按压态只在绘制阶段读，不触发重组；点击语义关掉涟漪，由 [ParleyTheme] 那边统一掐断。
 * 禁用时底色与描边不动，内容降到四成透明度，可点语义一并撤掉。
 */
/**
 * 压在浮层下面的透明遮罩：只负责接住划过菜单外的点击。
 * 按压反馈在这里没有意义，全仓唯一一处无涟漪点击就收在它里面。
 */
@Composable
fun Scrim(onDismiss: () -> Unit, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onDismiss,
            ),
    )
}

@Composable
fun Pressable(
    modifier: Modifier = Modifier,
    shape: Shape = RectangleShape,
    resting: Color = Color.Transparent,
    pressing: Color = pressedTone(resting),
    border: BorderStroke? = null,
    enabled: Boolean = true,
    contentDescription: String? = null,
    contentAlignment: Alignment = Alignment.Center,
    onLongClick: (() -> Unit)? = null,
    longClickLabel: String? = null,
    onClick: () -> Unit,
    content: @Composable () -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val held = interaction.collectIsPressedAsState()
    Box(
        modifier = modifier
            .clip(shape)
            .then(if (border != null) Modifier.border(border, shape) else Modifier)
            .drawBehind { drawRect(if (held.value) pressing else resting) }
            .then(
                if (onLongClick == null) {
                    Modifier.clickable(
                        interactionSource = interaction,
                        indication = null,
                        enabled = enabled,
                        onClick = onClick,
                    )
                } else {
                    // 长按走同一套：底色照旧由按压态换，读数给无障碍标签，涟漪一样不出现。
                    Modifier.combinedClickable(
                        interactionSource = interaction,
                        indication = null,
                        enabled = enabled,
                        onLongClickLabel = longClickLabel,
                        onLongClick = onLongClick,
                        onClick = onClick,
                    )
                },
            )
            .then(
                if (contentDescription == null) Modifier
                else Modifier.semantics { this.contentDescription = contentDescription },
            ),
        contentAlignment = contentAlignment,
    ) {
        Box(Modifier.then(if (enabled) Modifier else Modifier.alpha(DisabledAlpha))) { content() }
    }
}

@Composable
fun ParleyIconButton(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    size: Dp = 36.dp,
    iconSize: Dp = 20.dp,
    enabled: Boolean = true,
    tint: Color = Palette.ink3,
    container: Color = Color.Transparent,
    borderColor: Color? = null,
) {
    Pressable(
        modifier = modifier.size(size),
        shape = CircleShape,
        resting = container,
        border = borderColor?.let { BorderStroke(1.dp, it) },
        enabled = enabled,
        onClick = onClick,
    ) {
        Icon(icon, contentDescription, modifier = Modifier.size(iconSize), tint = tint)
    }
}

/** 转写一块块推来，三帧上下放一批：一批的字数跟着还差多少走，欠得多步子大、欠得少步子小。 */
private const val StreamTickNanos = 48_000_000L

/** 放出来的尾巴这一段做淡入，字看着是长出来的，不是一块贴上去的。 */
private const val StreamFadeChars = 10

/**
 * 气泡正文。只画已经放出来的那一截，剩下的按欠账往前赶，永远追得上又不会一下跳出一大块；
 * 尾巴那几个字从四成淡到实色。放完就换成整段纯文本，不留任何附加样式。
 * 历史消息进组合时文本早就齐了，第一次就按全量画，不倒回去重放。
 */
@Composable
fun StreamingProse(
    text: String,
    style: TextStyle,
    modifier: Modifier = Modifier,
) {
    var shown by remember { mutableIntStateOf(text.length) }
    LaunchedEffect(text) {
        if (shown >= text.length) return@LaunchedEffect
        var last = 0L
        while (shown < text.length) {
            val now = withFrameNanos { it }
            if (now - last < StreamTickNanos) continue
            last = now
            shown = nextShown(shown, text.length)
        }
    }
    if (shown >= text.length) {
        Text(text, style = style, modifier = modifier)
    } else {
        Text(fadingTail(text, shown), style = style, modifier = modifier)
    }
}

/** 下一拍画到第几个字：按还差多少的三分之一往前赶，至少往前走 1 个，不会越过全量。 */
internal fun nextShown(shown: Int, total: Int): Int {
    if (shown >= total) return total
    val backlog = total - shown
    return (shown + (backlog / 3).coerceAtLeast(1)).coerceAtMost(total)
}

/** 已经放出来的那截加一道尾巴：末几个字从四成淡上来，最后一个字就是实色。 */
internal fun fadingTail(text: String, shown: Int): AnnotatedString {
    val tailStart = (shown - StreamFadeChars).coerceAtLeast(0)
    val span = (shown - tailStart).coerceAtLeast(1)
    val base = Palette.ink
    return buildAnnotatedString {
        append(text.substring(0, tailStart))
        for (index in tailStart until shown) {
            val share = (index - tailStart + 1).toFloat() / span
            withStyle(SpanStyle(color = base.copy(alpha = 0.4f + 0.6f * share))) {
                append(text[index])
            }
        }
    }
}

val BubbleWidth = 258.dp

/**
 * 智能体气泡只剩正文与译文两种元素，朗读、翻译、收藏、复制重试删除平铺在它下面那条动作条里。
 * 气泡本身不接指针，正文就没有按下的底色与长按菜单，读它不改变任何状态。
 */
@Composable
fun EchoTurn(
    turn: Turn,
    translation: String?,
    expanded: Boolean,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Surface(
            modifier = Modifier.width(BubbleWidth),
            shape = RoundedCornerShape(
                topStart = Radius.bubbleTail,
                topEnd = Radius.bubble,
                bottomEnd = Radius.bubble,
                bottomStart = Radius.bubble,
            ),
            color = Palette.card,
            border = BorderStroke(1.dp, Palette.hair),
        ) {
            StreamingProse(
                text = turn.text,
                style = Type.prose,
                modifier = Modifier.padding(start = 14.dp, end = 14.dp, top = 13.dp, bottom = 12.dp),
            )
        }
        if (expanded && translation != null) {
            Spacer(Modifier.height(Spacing.iconText))
            Surface(
                color = Palette.tint,
                shape = RoundedCornerShape(Radius.chip),
                modifier = Modifier.width(BubbleWidth),
            ) {
                Text(
                    translation,
                    style = Type.translation,
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                )
            }
        }
    }
}

@Composable
fun UserBubble(turn: Turn, onOpenReview: () -> Unit, onRetryAnalysis: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.End,
    ) {
        Box {
            Surface(
                color = Palette.tint,
                shape = RoundedCornerShape(
                    topStart = Radius.bubble,
                    topEnd = Radius.bubbleTail,
                    bottomEnd = Radius.bubble,
                    bottomStart = Radius.bubble,
                ),
                modifier = Modifier.widthIn(max = 268.dp),
            ) {
                Text(
                    turn.text,
                    style = Type.prose,
                    modifier = Modifier.padding(start = 14.dp, end = 14.dp, top = 13.dp, bottom = 12.dp),
                )
            }
            ScoreBadge(
                turn = turn,
                onOpenReview = onOpenReview,
                onRetryAnalysis = onRetryAnalysis,
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .offset(x = BadgeTouchOut, y = BadgeTouchDrop),
            )
        }
    }
}

/**
 * 综合分角标：白纸小片骑在气泡右下角的底边上，右沿内缩 2、下半 8 落在气泡外，
 * 用绝对定位的偏移画在气泡轮廓之外，因此不参与测量、不把气泡或列表撑高。
 * 数字按分数换色：85 以上鼠尾草、70 到 84 陶土深、70 以下危险色；分析失败时改写成「重试」。
 * 视觉框 35×21，外面那圈 11 的透明内缩把命中区撑到 57×43。
 */
private val BadgeShape = RoundedCornerShape(Radius.pill)
private val BadgeTouch = 11.dp
private val BadgeTouchOut = BadgeTouch - 2.dp
private val BadgeTouchDrop = BadgeTouch + 8.dp
private val BadgeShadow = Shadow(
    radius = 4.dp,
    color = Palette.shade,
    offset = DpOffset(0.dp, 1.dp),
)

@Composable
private fun ScoreBadge(
    turn: Turn,
    onOpenReview: () -> Unit,
    onRetryAnalysis: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val failed = turn.analysis == AnalysisStatus.Failed
    val score = turn.review?.overallScore
    if (score == null && !failed) return
    val ink = when {
        failed -> Palette.danger
        score!! >= 85 -> Palette.olive
        score >= 70 -> Palette.clayDeep
        else -> Palette.danger
    }
    Pressable(
        modifier = modifier,
        contentDescription = if (failed) "重新分析" else "本轮评测",
        onClick = { if (failed) onRetryAnalysis() else onOpenReview() },
    ) {
        Box(Modifier.padding(BadgeTouch)) {
            Box(
                modifier = Modifier
                    .dropShadow(BadgeShape, BadgeShadow)
                    .clip(BadgeShape)
                    .background(Palette.card)
                    .border(1.dp, Palette.hair, BadgeShape)
                    .height(21.dp)
                    .padding(horizontal = 9.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = if (failed) "重试" else score.toString(),
                    style = Type.mono.copy(fontSize = 12.5.sp, lineHeight = 17.sp, color = ink),
                )
            }
        }
    }
}

@Composable
fun HintCard(text: String) {
    Surface(
        color = Palette.oliveTint,
        shape = RoundedCornerShape(Radius.card),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                FluentIcons.Bulb,
                contentDescription = null,
                modifier = Modifier.size(16.dp),
                tint = Palette.olive,
            )
            Spacer(Modifier.width(Spacing.iconText))
            Text(
                text,
                style = Type.prose.copy(fontSize = 14.sp, lineHeight = 21.sp).let {
                    it.copy(fontStyle = FontStyle.Italic)
                },
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
fun WordChip(word: String, onClick: (() -> Unit)? = null) {
    val label: @Composable () -> Unit = {
        Text(
            text = word,
            style = Type.mono.copy(color = Palette.clayDeep),
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
        )
    }
    if (onClick == null) {
        Surface(
            color = Palette.clayTint,
            shape = RoundedCornerShape(Radius.chip),
        ) {
            label()
        }
    } else {
        Pressable(
            shape = RoundedCornerShape(Radius.chip),
            resting = Palette.clayTint,
            onClick = onClick,
        ) {
            label()
        }
    }
}

@Composable
fun SectionLabel(icon: ImageVector, text: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, contentDescription = null, modifier = Modifier.size(14.dp), tint = Palette.ink3)
        Spacer(Modifier.width(Spacing.iconText))
        Text(text, style = Type.caption.copy(color = Palette.ink2))
    }
}

@Composable
fun SoftCard(
    modifier: Modifier = Modifier,
    color: Color = Palette.tint,
    radius: Dp = Radius.card,
    content: @Composable () -> Unit,
) {
    Surface(color = color, shape = RoundedCornerShape(radius), modifier = modifier) {
        Box(Modifier.padding(14.dp)) { content() }
    }
}

@Composable
fun FlagChip(label: String, selected: Boolean, onClick: () -> Unit) {
    Pressable(
        shape = RoundedCornerShape(Radius.pill),
        resting = if (selected) Palette.clay else Palette.card,
        border = if (selected) null else BorderStroke(1.dp, Palette.hairSoft),
        onClick = onClick,
    ) {
        Text(
            text = label,
            style = Type.caption.copy(color = if (selected) Color.White else Palette.ink3),
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
        )
    }
}

fun highlighted(text: String, highlight: String): AnnotatedString = buildAnnotatedString {
    val start = text.indexOf(highlight, ignoreCase = true)
    if (start < 0) {
        append(text)
        return@buildAnnotatedString
    }
    append(text.substring(0, start))
    withStyle(SpanStyle(color = Palette.clayDeep)) {
        append(text.substring(start, start + highlight.length))
    }
    append(text.substring(start + highlight.length))
}

// 浮层只这一层阴影：向外扩散、暖调、极淡，高度与颜色都不从这里以外的地方来。
private val FloatingShadow = Shadow(
    radius = 16.dp,
    color = Palette.shade,
    offset = DpOffset(0.dp, 4.dp),
)

/** 阴影向外铺开的距离。弹窗的窗口会把越界的绘制裁掉，Pop 里的浮层要先用它让出这块。 */
val FloatingShadowInset = FloatingShadow.radius + FloatingShadow.offset.y

/**
 * 浮层的统一外壳：纸白底、给定圆角、一层向外的暖影，不描边也不叠第二层。
 * 半屏纸面与下拉菜单都走它；页面内的卡片、气泡、列表行一律不投影。
 */
@Composable
fun FloatingPanel(
    shape: Shape,
    modifier: Modifier = Modifier,
    color: Color = Palette.card,
    content: @Composable () -> Unit,
) {
    Surface(
        color = color,
        shape = shape,
        modifier = modifier.dropShadow(shape, FloatingShadow),
        content = content,
    )
}

/** 下拉菜单与长按菜单共用的一行：有副行时给模型参数用，没有副行时固定四十四高。 */
data class MenuItem(
    val label: String,
    val detail: String? = null,
    val selected: Boolean = false,
    val enabled: Boolean = true,
    val onClick: () -> Unit,
)

@Composable
fun ParleyMenu(
    items: List<MenuItem>,
    modifier: Modifier = Modifier,
    width: Dp = 232.dp,
) {
    FloatingPanel(
        shape = RoundedCornerShape(Radius.card),
        modifier = modifier.width(width),
    ) {
        Column(Modifier.padding(6.dp)) {
            items.forEach { item ->
                Pressable(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(Radius.chip),
                    resting = if (item.selected) Palette.clayTint else Color.Transparent,
                    enabled = item.enabled,
                    onClick = item.onClick,
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .then(
                                if (item.detail == null) Modifier.height(44.dp)
                                else Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                            )
                            .padding(horizontal = if (item.detail == null) 10.dp else 0.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(item.label, style = Type.uiStrong)
                            item.detail?.let {
                                Spacer(Modifier.height(2.dp))
                                Text(it, style = Type.clock)
                            }
                        }
                        if (item.selected) {
                            Icon(
                                FluentIcons.Check,
                                contentDescription = null,
                                modifier = Modifier.size(13.dp),
                                tint = Palette.clayDeep,
                            )
                        }
                    }
                }
            }
        }
    }
}
