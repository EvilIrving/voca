package com.parley.speaker.ui.record

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
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
import com.parley.speaker.ui.component.SheetHandle
import com.parley.speaker.ui.icon.FluentIcons
import com.parley.speaker.ui.theme.Palette
import com.parley.speaker.ui.theme.Radius
import com.parley.speaker.ui.theme.Type
import kotlin.math.PI
import kotlin.math.min
import kotlin.math.sin

@Composable
fun RecordSheet(
    levels: FloatArray,
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
            modifier = Modifier.padding(horizontal = 20.dp),
        ) {
            Spacer(Modifier.height(10.dp))
            SheetHandle()
            Spacer(Modifier.height(36.dp))
            Text(
                text = if (cancelArmed) "上滑取消" else "松手发送",
                style = Type.display,
            )
            Spacer(Modifier.height(48.dp))
            Waveform(levels = levels, tint = if (cancelArmed) Palette.danger else Palette.clay)
            Spacer(Modifier.height(48.dp))
            Box(
                modifier = Modifier
                    .size(64.dp)
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
            Spacer(Modifier.height(18.dp))
            Text(formatElapsed(elapsedMs), style = Type.mono)
            Spacer(Modifier.height(96.dp))
        }
    }
}

@Composable
private fun Waveform(levels: FloatArray, tint: Color) {
    Canvas(
        modifier = Modifier
            .fillMaxWidth()
            .height(52.dp),
    ) {
        val count = levels.size
        if (count == 0) return@Canvas
        val slot = size.width / count
        val barWidth = min(slot * 0.36f, 3.dp.toPx())
        val minHeight = 5.dp.toPx()
        levels.forEachIndexed { index, raw ->
            val envelope = sin(PI * (index + 0.5f) / count).toFloat()
            val level = raw.coerceIn(0f, 1f)
            val height = minHeight + (size.height - minHeight) * envelope * (0.22f + 0.78f * level)
            drawRoundRect(
                color = tint,
                topLeft = Offset(
                    x = index * slot + (slot - barWidth) / 2f,
                    y = (size.height - height) / 2f,
                ),
                size = Size(barWidth, height),
                cornerRadius = CornerRadius(barWidth / 2f),
            )
        }
    }
}

fun formatElapsed(ms: Long): String {
    val total = ms / 1000
    return "%02d:%02d".format(total / 60, total % 60)
}

@Composable
fun RecordingScrim(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color(0x1A141413)),
    )
}

@Composable
fun RecordingOverlay(
    levels: FloatArray,
    elapsedMs: Long,
    cancelArmed: Boolean,
) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
        RecordingScrim()
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(Color.Transparent),
            verticalArrangement = Arrangement.Bottom,
        ) {
            RecordSheet(levels = levels, elapsedMs = elapsedMs, cancelArmed = cancelArmed)
        }
    }
}
