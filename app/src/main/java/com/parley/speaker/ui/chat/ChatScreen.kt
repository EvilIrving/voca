package com.parley.speaker.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.input.pointer.positionChange
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
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import com.parley.speaker.domain.Speaker
import com.parley.speaker.domain.Turn
import com.parley.speaker.ui.component.EchoBubble
import com.parley.speaker.ui.component.HintCard
import com.parley.speaker.ui.component.ParleyIconButton
import com.parley.speaker.ui.component.UserBubble
import com.parley.speaker.ui.icon.FluentIcons
import com.parley.speaker.ui.theme.Palette
import com.parley.speaker.ui.theme.Radius
import com.parley.speaker.ui.theme.Spacing
import com.parley.speaker.ui.theme.Type

@Composable
fun ChatScreen(
    state: ChatState,
    onScenarioClick: () -> Unit,
    onVocabClick: () -> Unit,
    onSettingsClick: () -> Unit,
    onReviewClick: (Turn) -> Unit,
    onRecordPressStart: () -> Unit,
    onRecordArmed: (Boolean) -> Unit,
    onRecordPressEnd: (cancelled: Boolean) -> Unit,
    onHangup: () -> Unit,
    onRetry: () -> Unit,
) {
    val listState = rememberLazyListState()

    LaunchedEffect(state.turns.size) {
        val last = state.turns.lastIndex + if (state.hint != null) 1 else 0
        if (last >= 0) listState.animateScrollToItem(last)
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Palette.paper)
            .windowInsetsPadding(WindowInsets.statusBars),
    ) {
        Spacer(Modifier.height(16.dp))
        TopBar(
            state = state,
            onScenarioClick = onScenarioClick,
            onVocabClick = onVocabClick,
            onSettingsClick = onSettingsClick,
            onRetry = onRetry,
        )

        LazyColumn(
            state = listState,
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
            contentPadding = PaddingValues(start = Spacing.screen, end = Spacing.screen, top = Spacing.bubble, bottom = Spacing.bubble),
            verticalArrangement = Arrangement.spacedBy(Spacing.bubble),
        ) {
            items(state.turns, key = { it.id }) { turn ->
                when (turn.speaker) {
                    Speaker.Echo -> EchoBubble(turn) { onReviewClick(turn) }
                    Speaker.User -> UserBubble(turn) { onReviewClick(turn) }
                }
            }
            state.hint?.let { hint ->
                item(key = "hint") { HintCard(hint) }
            }
        }

        BottomBar(
            onRecordPressStart = onRecordPressStart,
            onRecordArmed = onRecordArmed,
            onRecordPressEnd = onRecordPressEnd,
            onHangup = onHangup,
        )
        Spacer(Modifier.height(16.dp))
    }
}

@Composable
private fun TopBar(
    state: ChatState,
    onScenarioClick: () -> Unit,
    onVocabClick: () -> Unit,
    onSettingsClick: () -> Unit,
    onRetry: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = Spacing.screen)
            .height(44.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Surface(
            color = Palette.card,
            shape = RoundedCornerShape(Radius.pill),
            border = androidx.compose.foundation.BorderStroke(1.dp, Palette.hair),
            modifier = Modifier.clickable { onScenarioClick() },
        ) {
            Row(
                modifier = Modifier.padding(start = 12.dp, end = 8.dp, top = 7.dp, bottom = 7.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(state.scenario, style = Type.uiStrong)
                Spacer(Modifier.width(4.dp))
                Icon(
                    FluentIcons.ChevDown,
                    contentDescription = "切换场景",
                    modifier = Modifier.size(14.dp),
                    tint = Palette.ink4,
                )
            }
        }
        Spacer(Modifier.weight(1f))
        StatusLabel(state = state, onRetry = onRetry)
        Spacer(Modifier.weight(1f))
        ParleyIconButton(
            icon = FluentIcons.Book,
            contentDescription = "生词库",
            onClick = onVocabClick,
            tint = Palette.ink3,
        )
        ParleyIconButton(
            icon = FluentIcons.Settings,
            contentDescription = "配置中心",
            onClick = onSettingsClick,
            tint = Palette.ink3,
        )
    }
}

@Composable
private fun StatusLabel(state: ChatState, onRetry: () -> Unit) {
    when (state.connection) {
        Connection.Ready -> Text("第 ${state.round} 轮", style = Type.caption)
        Connection.Connecting -> Text("连接中", style = Type.caption.copy(color = Palette.ink4))
        Connection.Failed -> Text(
            text = "未连接 · 重试",
            style = Type.caption.copy(color = Palette.danger),
            modifier = Modifier.clickable { onRetry() },
        )
        Connection.Unconfigured -> Text(
            text = "未配置",
            style = Type.caption.copy(color = Palette.ink4),
            modifier = Modifier.clickable { onRetry() },
        )
    }
}

@Composable
private fun BottomBar(
    onRecordPressStart: () -> Unit,
    onRecordArmed: (Boolean) -> Unit,
    onRecordPressEnd: (cancelled: Boolean) -> Unit,
    onHangup: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = Spacing.screen)
            .height(48.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        ParleyIconButton(
            icon = FluentIcons.Add,
            contentDescription = "更多",
            onClick = {},
            size = 48.dp,
            iconSize = 20.dp,
            tint = Palette.ink2,
            container = Palette.card,
            borderColor = Palette.hair,
        )
        Box(modifier = Modifier.weight(1f)) {
            RecordButton(
                onPressStart = onRecordPressStart,
                onArmed = onRecordArmed,
                onPressEnd = onRecordPressEnd,
            )
        }
        ParleyIconButton(
            icon = FluentIcons.CallEnd,
            contentDescription = "结束会话",
            onClick = onHangup,
            size = 48.dp,
            iconSize = 20.dp,
            tint = Palette.ink3,
            container = Palette.card,
            borderColor = Palette.hair,
        )
    }
}

@Composable
private fun RecordButton(
    onPressStart: () -> Unit,
    onArmed: (Boolean) -> Unit,
    onPressEnd: (cancelled: Boolean) -> Unit,
) {
    val cancelDistance = with(LocalDensity.current) { 64.dp.toPx() }
    Surface(
        color = Palette.clay,
        shape = RoundedCornerShape(Radius.pill),
        modifier = Modifier
            .fillMaxWidth()
            .height(48.dp)
            .pointerInput(Unit) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    onPressStart()
                    var travel = 0f
                    var armed = false
                    while (true) {
                        val event = awaitPointerEvent()
                        val change = event.changes.firstOrNull() ?: break
                        travel += change.positionChange().y
                        val nowArmed = travel < -cancelDistance
                        if (nowArmed != armed) {
                            armed = nowArmed
                            onArmed(nowArmed)
                        }
                        if (!change.pressed) {
                            onPressEnd(armed)
                            break
                        }
                        change.consume()
                    }
                }
            },
    ) {
        Row(
            modifier = Modifier.fillMaxSize(),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                FluentIcons.Mic,
                contentDescription = null,
                modifier = Modifier.size(18.dp),
                tint = Color.White,
            )
            Spacer(Modifier.width(Spacing.iconText))
            Text("按住说话", style = Type.uiStrong.copy(color = Color.White))
        }
    }
}
