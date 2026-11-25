package com.voca.speaker.ui.session

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.voca.speaker.ui.icon.FluentIcons
import com.voca.speaker.ui.theme.Palette
import com.voca.speaker.ui.theme.Radius

@Composable
internal fun BackButton(onDismiss: () -> Unit) {
    Box(
        modifier = Modifier
            .size(36.dp)
            .clip(RoundedCornerShape(Radius.pill))
            .clickable(onClick = onDismiss),
        contentAlignment = Alignment.Center,
    ) {
        Icon(FluentIcons.ArrowLeft, "返回对话", modifier = Modifier.size(20.dp), tint = Palette.ink2)
    }
}
