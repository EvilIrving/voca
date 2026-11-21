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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
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

@Composable
fun Avatar(letter: String, size: Dp = 32.dp) {
    Box(
        modifier = Modifier
            .size(size)
            .clip(CircleShape)
            .background(Palette.clayTint)
            .border(1.dp, Palette.clay, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = letter,
            style = Type.uiStrong.copy(color = Palette.clayDeep, fontWeight = FontWeight.Medium),
        )
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
        modifier = Modifier.height(24.dp),
        horizontalArrangement = Arrangement.spacedBy(Spacing.block),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        BubbleActionIcons.forEach { (icon, label) ->
            Icon(icon, label, modifier = Modifier.size(18.dp), tint = tint)
        }
    }
}

@Composable
fun EchoBubble(turn: Turn, onOpenReview: () -> Unit) {
    Row(modifier = Modifier.fillMaxWidth()) {
        Avatar("E")
        Spacer(Modifier.width(Spacing.avatarGap))
        Column(modifier = Modifier.width(262.dp)) {
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
                Column(Modifier.padding(horizontal = 16.dp, vertical = 14.dp)) {
                    Text(turn.text, style = Type.prose)
                    Spacer(Modifier.height(10.dp))
                    BubbleActions()
                }
            }
        }
    }
}

@Composable
fun UserBubble(turn: Turn, onOpenReview: () -> Unit) {
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
            Column(Modifier.padding(horizontal = 16.dp, vertical = 14.dp)) {
                Text(turn.text, style = Type.prose)
                Spacer(Modifier.height(10.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    BubbleActions()
                }
            }
        }
        ScoreLine(turn = turn, onOpenReview = onOpenReview)
    }
}

@Composable
private fun ScoreLine(turn: Turn, onOpenReview: () -> Unit) {
    val review = turn.review ?: return
    val text = buildString {
        review.pronunciation?.let { append("发音 ${it.score}  ·  ") }
        append("语法 ${review.grammar.score}")
    }
    Row(
        modifier = Modifier
            .padding(top = 6.dp, end = 4.dp)
            .clickable { onOpenReview() },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text, style = Type.caption)
        Spacer(Modifier.width(2.dp))
        Icon(
            FluentIcons.ChevRight,
            contentDescription = "查看评测",
            modifier = Modifier.size(14.dp),
            tint = Palette.ink4,
        )
    }
}

@Composable
fun HintCard(text: String) {
    Surface(
        color = Palette.oliveTint,
        shape = RoundedCornerShape(Radius.card),
    ) {
        Column(Modifier.padding(horizontal = 14.dp, vertical = 12.dp)) {
            Row {
                Icon(
                    FluentIcons.Bulb,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                    tint = Palette.olive,
                )
                Spacer(Modifier.width(Spacing.iconText))
                Text(text, style = Type.prose)
            }
            Spacer(Modifier.height(10.dp))
            BubbleActions(tint = Palette.olive)
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
fun SheetHandle(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .width(36.dp)
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
