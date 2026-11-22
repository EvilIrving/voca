package com.parley.speaker.ui.session

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.parley.speaker.ui.icon.FluentIcons
import com.parley.speaker.ui.theme.Palette
import com.parley.speaker.ui.theme.Radius
import com.parley.speaker.ui.theme.Spacing
import com.parley.speaker.ui.theme.Type

@Composable
fun TypingBar(
    text: String,
    onTextChange: (String) -> Unit,
    onSend: () -> Unit,
    onBackToVoice: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val canSend = text.isNotBlank()
    val focus = remember { FocusRequester() }
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = Spacing.screen)
            .height(44.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.iconText),
    ) {
        Box(
            modifier = Modifier
                .size(44.dp)
                .clip(CircleShape)
                .background(Palette.card)
                .border(1.dp, Palette.hair, CircleShape)
                .clickable(onClick = onBackToVoice),
            contentAlignment = Alignment.Center,
        ) {
            Icon(FluentIcons.Mic, "切回语音", Modifier.size(18.dp), tint = Palette.ink3)
        }
        Surface(
            color = Palette.card,
            shape = RoundedCornerShape(Radius.control),
            border = BorderStroke(1.dp, Palette.hair),
            modifier = Modifier
                // 360 栅格下正好 216：320 减去两个 44 与两道 8，换设备宽度跟着收而不是溢出。
                .weight(1f)
                .height(44.dp),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    // 输入框只有 18 高的字，点行内其余位置也要能落焦。
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                    ) { focus.requestFocus() }
                    .padding(horizontal = 12.dp),
                contentAlignment = Alignment.CenterStart,
            ) {
                if (text.isEmpty()) {
                    Text("输入文字", style = Type.ui.copy(color = Palette.ink4))
                }
                BasicTextField(
                    value = text,
                    onValueChange = onTextChange,
                    singleLine = true,
                    textStyle = Type.uiStrong,
                    cursorBrush = SolidColor(Palette.clayDeep),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                    keyboardActions = KeyboardActions(onSend = { onSend() }),
                    modifier = Modifier
                        .fillMaxWidth()
                        .focusRequester(focus),
                )
            }
        }
        Box(
            modifier = Modifier
                .size(44.dp)
                .clip(CircleShape)
                .background(if (canSend) Palette.clay else Palette.hairStrong)
                .clickable(enabled = canSend, onClick = onSend),
            contentAlignment = Alignment.Center,
        ) {
            Icon(FluentIcons.ArrowUp, "发送", Modifier.size(18.dp), tint = Color.White)
        }
    }
}
