package com.parley.speaker.ui.session

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.parley.speaker.domain.Speaker
import com.parley.speaker.domain.Turn
import com.parley.speaker.ui.component.ParleyIconButton
import com.parley.speaker.ui.icon.FluentIcons
import com.parley.speaker.ui.theme.Palette
import com.parley.speaker.ui.theme.Radius
import com.parley.speaker.ui.theme.Spacing
import com.parley.speaker.ui.theme.Type
import kotlin.math.roundToInt

data class SessionSummary(
    val name: String,
    val rounds: Int,
    val pronunciation: Int? = null,
    val grammar: Int? = null,
    val newWords: Int = 0,
    val repetitionHint: String? = null,
)

fun sessionSummary(
    turns: List<Turn>,
    name: String,
    newWords: Int,
    repetitionHint: String? = null,
): SessionSummary {
    val pronunciation = turns.mapNotNull { it.review?.pronunciation?.score }
    val grammar = turns.mapNotNull { it.review?.grammar?.score }
    return SessionSummary(
        name = name,
        rounds = turns.count { it.speaker == Speaker.User },
        pronunciation = pronunciation.takeIf { it.isNotEmpty() }?.average()?.roundToInt(),
        grammar = grammar.takeIf { it.isNotEmpty() }?.average()?.roundToInt(),
        newWords = newWords,
        repetitionHint = repetitionHint,
    )
}

@Composable
fun SessionSummarySheet(
    summary: SessionSummary,
    visible: Boolean,
    onDismiss: () -> Unit,
    onRename: (String) -> Unit,
    onStartNew: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var renaming by remember(summary.name) { mutableStateOf(false) }
    var draft by remember(summary.name) { mutableStateOf(summary.name) }
    val focus = remember { FocusRequester() }

    LaunchedEffect(renaming) {
        if (renaming) focus.requestFocus()
    }

    Box(modifier = modifier.fillMaxSize()) {
        AnimatedVisibility(visible = visible, enter = fadeIn(), exit = fadeOut()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = onDismiss,
                    ),
            )
        }
        AnimatedVisibility(
            visible = visible,
            enter = slideInVertically { it } + fadeIn(),
            exit = slideOutVertically { it } + fadeOut(),
            modifier = Modifier.align(Alignment.BottomCenter),
        ) {
            Surface(
                color = Palette.card,
                shape = RoundedCornerShape(Radius.sheet),
                border = BorderStroke(1.dp, Palette.hair),
                shadowElevation = 10.dp,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = Spacing.screen, vertical = Spacing.screen),
            ) {
                Column(Modifier.padding(20.dp)) {
                    // 会话名在挂断或自动命名之后才有；还没名字时不摆标题与铅笔，免得留一块空白。
                    if (summary.name.isNotBlank()) {
                    TitleRow(
                        name = summary.name,
                        renaming = renaming,
                        draft = draft,
                        focus = focus,
                        onDraftChange = { draft = it },
                        onEdit = {
                            draft = summary.name
                            renaming = true
                        },
                        onConfirm = {
                            val name = draft.trim()
                            if (name.isNotEmpty()) onRename(name)
                            renaming = false
                        },
                        onCancel = {
                            draft = summary.name
                            renaming = false
                        },
                    )
                    Spacer(Modifier.height(Spacing.bubble))
                    }
                    Row(Modifier.fillMaxWidth()) {
                        StatCell("轮次", "${summary.rounds}", Modifier.weight(1f))
                        StatCell("发音", summary.pronunciation?.toString() ?: "—", Modifier.weight(1f))
                        StatCell("语法", summary.grammar?.toString() ?: "—", Modifier.weight(1f))
                        StatCell("新词", "${summary.newWords}", Modifier.weight(1f))
                    }
                    summary.repetitionHint?.let { hint ->
                        Spacer(Modifier.height(Spacing.block))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                FluentIcons.Bulb,
                                contentDescription = null,
                                modifier = Modifier.size(14.dp),
                                tint = Palette.olive,
                            )
                            Spacer(Modifier.width(Spacing.iconText))
                            Text(
                                hint,
                                style = Type.ui.copy(fontSize = 12.5.sp, lineHeight = 18.sp, color = Palette.ink3),
                            )
                        }
                    }
                    Spacer(Modifier.height(Spacing.bubble))
                    Surface(
                        color = Palette.clay,
                        shape = RoundedCornerShape(Radius.pill),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(44.dp)
                            .clickable(onClick = onStartNew),
                    ) {
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Text("开始新会话", style = Type.uiStrong.copy(color = Color.White))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun TitleRow(
    name: String,
    renaming: Boolean,
    draft: String,
    focus: FocusRequester,
    onDraftChange: (String) -> Unit,
    onEdit: () -> Unit,
    onConfirm: () -> Unit,
    onCancel: () -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        if (renaming) {
            Surface(
                color = Palette.card,
                shape = RoundedCornerShape(Radius.control),
                border = BorderStroke(1.dp, Palette.hairStrong),
                modifier = Modifier
                    .weight(1f)
                    .height(40.dp),
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 10.dp),
                    contentAlignment = Alignment.CenterStart,
                ) {
                    BasicTextField(
                        value = draft,
                        onValueChange = onDraftChange,
                        singleLine = true,
                        textStyle = Type.display.copy(fontSize = 17.sp, lineHeight = 21.sp),
                        cursorBrush = SolidColor(Palette.clayDeep),
                        modifier = Modifier
                            .fillMaxWidth()
                            .focusRequester(focus),
                    )
                }
            }
            Spacer(Modifier.width(Spacing.iconText))
            ParleyIconButton(FluentIcons.Check, "确认改名", onConfirm, size = 32.dp, iconSize = 16.dp, tint = Palette.clay)
            ParleyIconButton(FluentIcons.Dismiss, "取消改名", onCancel, size = 32.dp, iconSize = 16.dp, tint = Palette.ink3)
        } else {
            Text(
                text = name,
                style = Type.display,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            ParleyIconButton(RenamePencil, "改名", onEdit, size = 32.dp, iconSize = 16.dp)
        }
    }
}

@Composable
private fun StatCell(label: String, value: String, modifier: Modifier = Modifier) {
    Column(modifier) {
        Text(value, style = Type.display.copy(fontSize = 27.sp, lineHeight = 31.sp))
        Text(label, style = Type.caption.copy(fontSize = 11.5.sp, lineHeight = 15.sp, color = Palette.ink3))
    }
}

// FluentIcons 里没有铅笔图元，这一支按 Fluent 线宽自己画。
private val RenamePencil: ImageVector = ImageVector.Builder(
    name = "rename",
    defaultWidth = 20.dp,
    defaultHeight = 20.dp,
    viewportWidth = 20f,
    viewportHeight = 20f,
).apply {
    addPath(
        pathData = PathParser()
            .parsePathString(
                "M4.1 16.9 3.2 13.4 11.9 4.7a1.9 1.9 0 0 1 2.7 0l.7.7a1.9 1.9 0 0 1 0 2.7l-8.7 8.7z " +
                    "M10.7 5.9 14.1 9.3",
            )
            .toNodes(),
        stroke = SolidColor(Color.Black),
        strokeLineWidth = 1.6f,
        strokeLineCap = StrokeCap.Round,
        strokeLineJoin = StrokeJoin.Round,
    )
}.build()
