package com.parley.speaker.ui.session

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.parley.speaker.ui.theme.Palette
import com.parley.speaker.ui.theme.Radius
import com.parley.speaker.ui.theme.Spacing
import com.parley.speaker.ui.theme.Type
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

data class SessionRecord(
    val id: String,
    val name: String,
    val startedAt: Long,
    val rounds: Int,
    val pronunciation: Int? = null,
)

private data class SessionDay(val label: String, val records: List<SessionRecord>)

private val ClockFormat: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")
private val DayClockFormat: DateTimeFormatter = DateTimeFormatter.ofPattern("MM-dd HH:mm")

@Composable
fun SessionListScreen(
    sessions: List<SessionRecord>,
    onDismiss: () -> Unit,
    onOpen: (SessionRecord) -> Unit = {},
    visible: Boolean = true,
) {
    AnimatedVisibility(
        visible = visible,
        enter = slideInVertically { it } + fadeIn(),
        exit = slideOutVertically { it } + fadeOut(),
    ) {
        val days = remember(sessions) { sessions.byDay() }
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
                BackButton(onDismiss)
                Spacer(Modifier.width(4.dp))
                Text("会话记录", style = Type.display)
            }
            Spacer(Modifier.height(Spacing.block))
            if (days.isEmpty()) {
                Text(
                    "还没有会话",
                    style = Type.ui.copy(color = Palette.ink4),
                    modifier = Modifier.padding(horizontal = Spacing.screen),
                )
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(
                        start = Spacing.screen,
                        end = Spacing.screen,
                        bottom = Spacing.bubble,
                    ),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    days.forEach { day ->
                        item(key = "day-${day.label}") {
                            Text(
                                day.label,
                                style = Type.caption.copy(color = Palette.ink4),
                                modifier = Modifier.padding(top = 8.dp, bottom = 4.dp),
                            )
                        }
                        items(day.records, key = { it.id }) { record ->
                            SessionRow(record, onOpen)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SessionRow(record: SessionRecord, onOpen: (SessionRecord) -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(Radius.chip))
            .clickable { onOpen(record) }
            .padding(horizontal = 4.dp, vertical = 10.dp),
    ) {
        Text(
            text = record.name,
            style = Type.uiStrong,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.height(3.dp))
        Text(record.subtitle(), style = Type.caption)
    }
}

private fun SessionRecord.subtitle(): String = buildString {
    val zoned = Instant.ofEpochMilli(startedAt).atZone(ZoneId.systemDefault())
    append(zoned.format(if (zoned.toLocalDate() == LocalDate.now()) ClockFormat else DayClockFormat))
    append(" · ")
    append("$rounds 轮")
    pronunciation?.let {
        append(" · 发音 ")
        append(it)
    }
}

private fun List<SessionRecord>.byDay(): List<SessionDay> {
    val zone = ZoneId.systemDefault()
    val today = LocalDate.now(zone)
    return sortedByDescending { it.startedAt }
        .groupBy { record ->
            val date = Instant.ofEpochMilli(record.startedAt).atZone(zone).toLocalDate()
            if (date == today) "今天" else "更早"
        }
        .map { (label, records) -> SessionDay(label, records) }
}

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
private fun BackButton(onDismiss: () -> Unit) {
    Box(
        modifier = Modifier
            .size(36.dp)
            .clip(RoundedCornerShape(Radius.pill))
            .clickable(onClick = onDismiss),
        contentAlignment = Alignment.Center,
    ) {
        Icon(BackArrow, "返回对话", modifier = Modifier.size(20.dp), tint = Palette.ink2)
    }
}
