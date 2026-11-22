package com.parley.speaker.ui.record

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.parley.speaker.ui.component.SheetHandle
import com.parley.speaker.ui.icon.FluentIcons
import com.parley.speaker.ui.theme.Palette
import com.parley.speaker.ui.theme.Radius
import com.parley.speaker.ui.theme.Type
import kotlin.math.PI
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin

/**
 * 收音面板：总高 297。自上而下是内边距 20、把手 4、18、标题 30、20、波形 76、14、
 * 圆钮 62、底部留白 53。秒数压进波形中段让出的八十四乘二十二空档，柱身在那里断开，
 * 上下两截各自收圆角。
 */
@Composable
fun RecordSheet(
    levels: FloatArray,
    frame: Long,
    elapsedMs: Long,
    cancelArmed: Boolean,
    modifier: Modifier = Modifier,
) {
    Surface(
        color = Palette.card,
        shape = RoundedCornerShape(topStart = Radius.sheet, topEnd = Radius.sheet),
        modifier = modifier.fillMaxWidth(),
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 20.dp, bottom = 53.dp),
        ) {
            SheetHandle(width = 38.dp)
            Spacer(Modifier.height(18.dp))
            Text(
                text = if (cancelArmed) "松手取消" else "松手发送",
                style = Type.display.copy(
                    lineHeight = 30.sp,
                    color = if (cancelArmed) Palette.danger else Palette.ink,
                ),
            )
            Spacer(Modifier.height(20.dp))
            Waveform(
                levels = levels,
                frame = frame,
                elapsedMs = elapsedMs,
                cancelArmed = cancelArmed,
                modifier = Modifier
                    .width(320.dp)
                    .height(76.dp),
            )
            Spacer(Modifier.height(14.dp))
            PressButton(cancelArmed)
        }
    }
}

@Composable
private fun TimerRow(elapsedMs: Long, cancelArmed: Boolean) {
    val tint = if (cancelArmed) Palette.danger else Palette.clay
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            modifier = Modifier
                .size(5.dp)
                .background(if (elapsedMs > 0) tint else Palette.ink4, CircleShape),
        )
        Spacer(Modifier.width(6.dp))
        Text(
            text = formatElapsed(elapsedMs),
            style = Type.mono.copy(fontSize = 11.sp, lineHeight = 15.sp, color = tint),
        )
    }
}

@Composable
private fun PressButton(cancelArmed: Boolean) {
    Box(
        modifier = Modifier
            .size(62.dp)
            .background(if (cancelArmed) Palette.danger else Palette.clay, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            FluentIcons.Mic,
            contentDescription = null,
            modifier = Modifier.size(24.dp),
            tint = Color.White,
        )
    }
}

@Composable
private fun Waveform(
    levels: FloatArray,
    frame: Long,
    elapsedMs: Long,
    cancelArmed: Boolean,
    modifier: Modifier = Modifier,
) {
    val tint = if (cancelArmed) Palette.danger else Palette.clay
    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            // 读取帧计数，让声波随每一帧电平重绘，数组本身在采集侧复用。
            frame
            val count = levels.size
            if (count == 0) return@Canvas
            val barWidth = 3.dp.toPx()
            val gap = 5.dp.toPx()
            val total = count * barWidth + (count - 1) * gap
            val startX = (size.width - total) / 2f
            val minHeight = 6.dp.toPx()
            val maxHeight = size.height
            val holeHalfWidth = 42.dp.toPx()
            val holeHalfHeight = 11.dp.toPx()
            val centerX = size.width / 2f
            val centerY = size.height / 2f
            levels.forEachIndexed { index, raw ->
                val envelope = sin(PI * (index + 0.5) / count).toFloat()
                val level = raw.coerceIn(0f, 1f)
                val height = (minHeight + (maxHeight - minHeight) * envelope * level.pow(0.8f))
                    .coerceAtLeast(minHeight * 0.6f)
                val x = startX + index * (barWidth + gap)
                val top = centerY - height / 2f
                val bottom = centerY + height / 2f
                val alpha = if (cancelArmed) 0.78f else 0.5f + 0.5f * envelope
                val color = tint.copy(alpha = alpha.coerceIn(0f, 1f))
                val radius = CornerRadius(barWidth / 2f)
                val insideHole = x + barWidth > centerX - holeHalfWidth && x < centerX + holeHalfWidth
                if (!insideHole) {
                    drawRoundRect(color, Offset(x, top), Size(barWidth, height), radius)
                } else {
                    val upperEnd = min(bottom, centerY - holeHalfHeight)
                    if (upperEnd > top) {
                        drawRoundRect(color, Offset(x, top), Size(barWidth, upperEnd - top), radius)
                    }
                    val lowerStart = maxOf(top, centerY + holeHalfHeight)
                    if (bottom > lowerStart) {
                        drawRoundRect(color, Offset(x, lowerStart), Size(barWidth, bottom - lowerStart), radius)
                    }
                }
            }
        }
        TimerRow(elapsedMs = elapsedMs, cancelArmed = cancelArmed)
    }
}

fun formatElapsed(ms: Long): String {
    val total = ms / 1000
    return "%02d:%02d".format(total / 60, total % 60)
}

@Composable
fun RecordingOverlay(
    levels: FloatArray,
    frame: Long,
    elapsedMs: Long,
    cancelArmed: Boolean,
) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color(0x1A141413)),
        )
        RecordSheet(
            levels = levels,
            frame = frame,
            elapsedMs = elapsedMs,
            cancelArmed = cancelArmed,
        )
    }
}
