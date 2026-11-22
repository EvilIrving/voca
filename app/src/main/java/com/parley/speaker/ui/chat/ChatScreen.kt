package com.parley.speaker.ui.chat

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.interaction.DragInteraction
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
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
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.parley.speaker.domain.Speaker
import com.parley.speaker.domain.Turn
import com.parley.speaker.ui.component.HintCard
import com.parley.speaker.ui.component.ParleyIconButton
import com.parley.speaker.ui.component.UserBubble
import com.parley.speaker.ui.icon.FluentIcons
import com.parley.speaker.ui.session.ActionSheet
import com.parley.speaker.ui.session.TypingBar
import com.parley.speaker.ui.theme.Palette
import com.parley.speaker.ui.theme.Radius
import com.parley.speaker.ui.theme.Spacing
import com.parley.speaker.ui.theme.Type
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

// 列表底部只留这一道 18，底栏 48 之外不再叠白，末条气泡才不会被压住。
private val ListBottomPadding = 18.dp

private val BubbleWidth = 258.dp

@Composable
fun ChatScreen(
    state: ChatState,
    onScenarioClick: () -> Unit,
    onVocabClick: () -> Unit,
    onSettingsClick: () -> Unit,
    onReviewClick: (Turn) -> Unit,
    onRetryAnalysis: (String) -> Unit,
    vocabToday: Int,
    onRecordPressStart: () -> Unit,
    onRecordArmed: (Boolean) -> Unit,
    onRecordPressEnd: (cancelled: Boolean) -> Unit,
    onHangup: () -> Unit,
    onRetry: () -> Unit,
    onSendText: (String) -> Unit = {},
    onSaveTurn: (String, Boolean) -> Unit = { _, _ -> },
    onSummaryClick: () -> Unit = {},
    onSessionsClick: () -> Unit = {},
    onPlayTurn: (String) -> Unit = {},
    onTranslateTurn: (suspend (String) -> String?) = { null },
) {
    val listState = rememberLazyListState()
    var typing by rememberSaveable { mutableStateOf(false) }
    var actionsOpen by rememberSaveable { mutableStateOf(false) }
    var draft by rememberSaveable { mutableStateOf("") }

    // 译文按轮留在组合里，收起只改可见性，同一条再点不会重发请求。
    val translations = remember { mutableStateMapOf<String, String>() }
    val expanded = remember { mutableStateMapOf<String, Boolean>() }
    val asking = remember { mutableSetOf<String>() }
    val scope = rememberCoroutineScope()

    val translate: (Turn) -> Unit = { turn ->
        when {
            expanded[turn.id] == true -> expanded[turn.id] = false
            translations[turn.id] != null -> expanded[turn.id] = true
            turn.id in asking -> Unit
            else -> {
                asking += turn.id
                scope.launch {
                    val translated = onTranslateTurn(turn.text)
                    asking -= turn.id
                    if (translated != null) {
                        translations[turn.id] = translated
                        expanded[turn.id] = true
                    }
                }
            }
        }
    }

    // 回复是一段段流进来的，条目高度一直在长。只按条目数量滚一次会停在气泡还矮的那一刻，
    // 等它长过视口，结尾几行就压在底栏下面。跟住内容高度到底，用户自己拖开之后不再抢。
    var follow by remember { mutableStateOf(true) }
    LaunchedEffect(listState) {
        listState.interactionSource.interactions.collect { interaction ->
            if (interaction is DragInteraction.Start) follow = false
        }
    }
    LaunchedEffect(listState) {
        snapshotFlow { listState.isScrollInProgress }.collect { scrolling ->
            if (!scrolling && !listState.canScrollForward) follow = true
        }
    }
    LaunchedEffect(listState) {
        snapshotFlow {
            val info = listState.layoutInfo
            val tail = info.visibleItemsInfo.lastOrNull()
            Triple(info.totalItemsCount, tail?.index ?: -1, tail?.size ?: 0)
        }
            .distinctUntilChanged()
            .collect { (count, index, _) ->
                if (!follow || count == 0) return@collect
                // 末尾已经在屏内就顺着增长补动画，隔着一屏的距离直接落到位置。
                if (index == count - 1) {
                    listState.animateScrollToItem(index)
                } else {
                    listState.scrollToItem(count - 1)
                }
            }
    }

    // 收音面板从底部盖上来时动作面板留在下面会挡着，按下去的瞬间就收掉。
    LaunchedEffect(state.recording) {
        if (state.recording) actionsOpen = false
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Palette.paper)
            .windowInsetsPadding(WindowInsets.statusBars)
            // 录音胶囊与输入框贴着系统导航条会叠在一起，取导航条与键盘两者的较大值抬起来。
            .windowInsetsPadding(WindowInsets.navigationBars.union(WindowInsets.ime)),
    ) {
        TopBar(
            state = state,
            vocabToday = vocabToday,
            onScenarioClick = onScenarioClick,
            onVocabClick = onVocabClick,
            onSettingsClick = onSettingsClick,
            onRetry = onRetry,
        )

        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
        ) {
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(
                    start = Spacing.screen,
                    end = Spacing.screen,
                    top = Spacing.bubble,
                    bottom = ListBottomPadding,
                ),
                verticalArrangement = Arrangement.spacedBy(Spacing.bubble),
            ) {
                items(state.turns, key = { it.id }) { turn ->
                    when (turn.speaker) {
                        Speaker.Echo -> EchoTurn(
                            turn = turn,
                            translation = translations[turn.id],
                            expanded = expanded[turn.id] == true,
                            onPlay = { onPlayTurn(turn.id) },
                            onTranslate = { translate(turn) },
                            onSave = { onSaveTurn(turn.id, !turn.saved) },
                        )

                        Speaker.User -> UserBubble(turn, { onReviewClick(turn) }, { onRetryAnalysis(turn.id) })
                    }
                }
                state.hint?.let { hint ->
                    item(key = "hint") { HintCard(hint) }
                }
            }

            ActionSheet(
                visible = actionsOpen,
                onDismiss = { actionsOpen = false },
                onTyping = {
                    actionsOpen = false
                    typing = true
                },
                onSummary = {
                    actionsOpen = false
                    onSummaryClick()
                },
                onSessions = {
                    actionsOpen = false
                    onSessionsClick()
                },
            )
        }

        if (typing) {
            TypingBar(
                text = draft,
                onTextChange = { draft = it },
                onSend = {
                    val text = draft.trim()
                    if (text.isNotEmpty()) {
                        draft = ""
                        onSendText(text)
                    }
                },
                onBackToVoice = { typing = false },
            )
        } else {
            BottomBar(
                plusActive = actionsOpen,
                onPlusClick = { actionsOpen = !actionsOpen },
                onRecordPressStart = onRecordPressStart,
                onRecordArmed = onRecordArmed,
                onRecordPressEnd = onRecordPressEnd,
                onHangup = onHangup,
            )
        }
    }
}

@Composable
private fun EchoTurn(
    turn: Turn,
    translation: String?,
    expanded: Boolean,
    onPlay: () -> Unit,
    onTranslate: () -> Unit,
    onSave: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Surface(
            color = Palette.card,
            shape = RoundedCornerShape(
                topStart = Radius.bubbleTail,
                topEnd = Radius.bubble,
                bottomEnd = Radius.bubble,
                bottomStart = Radius.bubble,
            ),
            border = BorderStroke(1.dp, Palette.hair),
            modifier = Modifier.width(BubbleWidth),
        ) {
            Column(Modifier.padding(start = 14.dp, end = 14.dp, top = 13.dp, bottom = 11.dp)) {
                Text(turn.text, style = Type.prose)
                Spacer(Modifier.height(10.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    BubbleAction(FluentIcons.Speaker, "播放这一轮的录音", onPlay)
                    Spacer(Modifier.width(Spacing.iconText))
                    BubbleAction(
                        icon = FluentIcons.Translate,
                        label = "翻译",
                        onClick = onTranslate,
                        active = translation != null && expanded,
                    )
                    Spacer(Modifier.width(Spacing.iconText))
                    BubbleAction(
                        icon = if (turn.saved) FluentIcons.BookmarkFilled else FluentIcons.Bookmark,
                        label = "收藏",
                        onClick = onSave,
                        active = turn.saved,
                    )
                }
            }
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
                    style = Type.ui.copy(fontSize = 12.5.sp, lineHeight = 19.sp, color = Palette.ink3),
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                )
            }
        }
    }
}

// 十六像素的图标太小，接不住指肚，拆一圈二十四的点击区，中心距与原先的十六加十六一致。
@Composable
private fun BubbleAction(icon: ImageVector, label: String, onClick: () -> Unit, active: Boolean = false) {
    Box(
        modifier = Modifier
            .size(24.dp)
            .clip(CircleShape)
            .clickable { onClick() },
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            icon,
            contentDescription = label,
            modifier = Modifier.size(16.dp),
            tint = if (active) Palette.clayDeep else Palette.ink4,
        )
    }
}

@Composable
private fun TopBar(
    state: ChatState,
    vocabToday: Int,
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
            border = BorderStroke(1.dp, Palette.hair),
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
        Box {
            ParleyIconButton(
                icon = FluentIcons.Book,
                contentDescription = "生词库",
                onClick = onVocabClick,
                tint = Palette.ink3,
            )
            if (vocabToday > 0) {
                Box(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .size(16.dp)
                        .background(Palette.clay, CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = if (vocabToday > 99) "99" else "$vocabToday",
                        style = Type.caption.copy(
                            fontSize = 9.sp,
                            lineHeight = 9.sp,
                            color = Color.White,
                        ),
                    )
                }
            }
        }
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
        Connection.Ready -> Text("已连接", style = Type.caption.copy(color = Palette.ink4))
        Connection.Connecting -> Text("连接中", style = Type.caption.copy(color = Palette.ink4))
        Connection.Failed -> Text(
            text = "未连接 · 重试",
            style = Type.caption.copy(color = Palette.clayDeep),
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
    plusActive: Boolean,
    onPlusClick: () -> Unit,
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
        horizontalArrangement = Arrangement.spacedBy(Spacing.iconText),
    ) {
        PlusButton(open = plusActive, onClick = onPlusClick)
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
private fun PlusButton(open: Boolean, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val active = pressed || open
    Box(
        modifier = Modifier
            .size(48.dp)
            .clip(CircleShape)
            .background(if (active) Palette.clayTint else Palette.card)
            .border(1.dp, Palette.hair, CircleShape)
            .clickable(
                interactionSource = interaction,
                indication = null,
                onClick = onClick,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            FluentIcons.Add,
            contentDescription = "更多",
            modifier = Modifier.size(20.dp),
            tint = Palette.ink2,
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
                    var finished = false
                    try {
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
                                finished = true
                                onPressEnd(armed)
                                break
                            }
                            change.consume()
                        }
                    } finally {
                        // 手势被系统打断（下拉通知栏、来电、多指）时也要收尾，否则麦克风一直开着。
                        if (!finished) onPressEnd(armed)
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
