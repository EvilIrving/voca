package com.parley.speaker.ui.component

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.parley.speaker.domain.AnalysisStatus
import com.parley.speaker.domain.Speaker
import com.parley.speaker.domain.Turn
import com.parley.speaker.ui.icon.FluentIcons
import com.parley.speaker.ui.theme.Palette
import com.parley.speaker.ui.theme.Radius
import com.parley.speaker.ui.theme.Spacing
import com.parley.speaker.ui.theme.Type

@Composable
fun ParleyIconButton(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    size: Dp = 36.dp,
    iconSize: Dp = 20.dp,
    tint: Color = Palette.ink3,
    container: Color = Color.Transparent,
    borderColor: Color? = null,
) {
    Box(
        modifier = modifier
            .size(size)
            .clip(CircleShape)
            .background(container)
            .then(if (borderColor != null) Modifier.border(1.dp, borderColor, CircleShape) else Modifier)
            .clickable { onClick() },
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription, modifier = Modifier.size(iconSize), tint = tint)
    }
}

private val BubbleActionIcons = listOf(
    FluentIcons.Speaker to "播放",
    FluentIcons.Translate to "翻译",
    FluentIcons.Bookmark to "收藏",
)

@Composable
private fun BubbleActions(tint: Color = Palette.ink4) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        BubbleActionIcons.forEach { (icon, label) ->
            Icon(icon, label, modifier = Modifier.size(16.dp), tint = tint)
        }
    }
}

@Composable
fun EchoBubble(turn: Turn, onOpenReview: () -> Unit) {
    Row(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.width(258.dp)) {
            Surface(
                color = Palette.card,
                shape = RoundedCornerShape(
                    topStart = Radius.bubbleTail,
                    topEnd = Radius.bubble,
                    bottomEnd = Radius.bubble,
                    bottomStart = Radius.bubble,
                ),
                border = BorderStroke(1.dp, Palette.hair),
            ) {
                Column(Modifier.padding(start = 14.dp, end = 14.dp, top = 13.dp, bottom = 11.dp)) {
                    Text(turn.text, style = Type.prose)
                    Spacer(Modifier.height(10.dp))
                    BubbleActions()
                }
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
        ScoreLine(turn = turn, onOpenReview = onOpenReview, onRetryAnalysis = onRetryAnalysis)
    }
}

/** 分值胶囊：二十七高、白底描边，发音用陶土深色、语法用鼠尾草。 */
@Composable
private fun ScoreLine(turn: Turn, onOpenReview: () -> Unit, onRetryAnalysis: () -> Unit) {
    val failed = turn.analysis == AnalysisStatus.Failed
    val review = turn.review
    if (review == null && !failed) return
    Row(
        modifier = Modifier.padding(top = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Surface(
            color = Palette.card,
            shape = RoundedCornerShape(Radius.pill),
            border = BorderStroke(1.dp, Palette.hair),
            modifier = Modifier
                .height(27.dp)
                .clickable { if (failed && review == null) onRetryAnalysis() else onOpenReview() },
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 11.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (review == null) {
                    Text(
                        "分析失败 · 重试",
                        style = Type.caption.copy(color = Palette.danger),
                    )
                } else {
                    review.pronunciation?.let { score ->
                        Text(
                            "发音 ${score.score}",
                            style = Type.caption.copy(color = Palette.clayDeep),
                        )
                        Spacer(Modifier.width(10.dp))
                    }
                    Text(
                        "语法 ${review.grammar.score}",
                        style = Type.caption.copy(color = Palette.olive),
                    )
                }
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
    Surface(
        color = Palette.clayTint,
        shape = RoundedCornerShape(Radius.chip),
    ) {
        Text(
            text = word,
            style = Type.mono.copy(color = Palette.clayDeep),
            modifier = Modifier
                .then(if (onClick != null) Modifier.clickable { onClick() } else Modifier)
                .padding(horizontal = 10.dp, vertical = 5.dp),
        )
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
    val container = if (selected) Palette.clay else Palette.card
    val content = if (selected) Color.White else Palette.ink3
    Surface(
        color = container,
        shape = RoundedCornerShape(Radius.pill),
        border = if (selected) null else BorderStroke(1.dp, Palette.hair),
        modifier = Modifier.clickable { onClick() },
    ) {
        Text(
            text = label,
            style = Type.caption.copy(color = content),
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
        )
    }
}

@Composable
fun SheetHandle(modifier: Modifier = Modifier, width: Dp = 36.dp) {
    Box(
        modifier = modifier
            .width(width)
            .height(4.dp)
            .clip(RoundedCornerShape(Radius.pill))
            .background(Palette.hairStrong),
    )
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

/** 下拉菜单的一行：有副行时给模型参数用，没有副行时固定四十四高。 */
data class MenuItem(
    val label: String,
    val detail: String? = null,
    val selected: Boolean = false,
    val onClick: () -> Unit,
)

@Composable
fun ParleyMenu(
    items: List<MenuItem>,
    modifier: Modifier = Modifier,
    width: Dp = 232.dp,
) {
    Surface(
        color = Palette.card,
        shape = RoundedCornerShape(Radius.card),
        border = BorderStroke(1.dp, Palette.hair),
        shadowElevation = 6.dp,
        modifier = modifier.width(width),
    ) {
        Column(Modifier.padding(6.dp)) {
            items.forEach { item ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(Radius.chip))
                        .background(if (item.selected) Palette.clayTint else Color.Transparent)
                        .clickable { item.onClick() }
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
                            Text(it, style = Type.mono.copy(fontSize = 10.5.sp, lineHeight = 14.sp))
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
