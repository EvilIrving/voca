package com.parley.speaker.ui.chat

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.DragInteraction
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
import androidx.compose.foundation.layout.widthIn
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
import androidx.compose.runtime.mutableStateSetOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.parley.speaker.domain.Speaker
import com.parley.speaker.domain.Turn
import com.parley.speaker.ui.component.EchoTurn
import com.parley.speaker.ui.component.HintCard
import com.parley.speaker.ui.component.ParleyIconButton
import com.parley.speaker.ui.component.Pressable
import com.parley.speaker.ui.component.UserBubble
import com.parley.speaker.ui.icon.FluentIcons
import com.parley.speaker.ui.session.BackButton
import com.parley.speaker.ui.theme.Palette
import com.parley.speaker.ui.theme.Radius
import com.parley.speaker.ui.theme.Spacing
import com.parley.speaker.ui.theme.Type
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

// 列表底只留这一道 18 加胶囊行的高度：输入条浮在列表之上，只占底下 56，条上方那段留给列表看。
// 这条 18 是气泡之间的呼吸，不是底栏的占位。
private val ListBottomPadding = InputBarSpec.RowHeight + 18.dp

@Composable
fun ChatScreen(
    state: ChatState,
    turns: List<Turn>,
    onVocabClick: () -> Unit,
    onSessionNameTap: () -> Unit = {},
    onReviewClick: (Turn) -> Unit,
    onRetryAnalysis: (String) -> Unit,
    vocabToday: Int,
    elapsedMs: Long,
    levels: FloatArray,
    levelFrame: Long,
    onRecordPressStart: () -> Boolean,
    onRecordArmed: (Boolean) -> Unit,
    onRecordPressEnd: (cancelled: Boolean) -> Unit,
    sessionName: String = "",
    continuing: Boolean = false,
    onExitSession: () -> Unit = {},
    onNewSession: () -> Unit = {},
    onSendText: (String) -> Unit = {},
    onSaveTurn: (String, Boolean) -> Unit = { _, _ -> },
    onPlayTurn: (String) -> Unit = {},
    onTranslateTurn: (suspend (String) -> String?) = { null },
    onCopyTurn: (String) -> Unit = {},
    onDeleteTurn: (String) -> Unit = {},
) {
    val listState = rememberLazyListState()

    // 译文按轮留在组合里，收起只改可见性，同一条再点不会重发请求。
    val translations = remember { mutableStateMapOf<String, String>() }
    val expanded = remember { mutableStateMapOf<String, Boolean>() }
    // 快照集合而不是普通 Set：翻译在飞时 translating 要跟着变，否则整行不会重组。
    val asking = remember { mutableStateSetOf<String>() }
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
    val tailTurn = turns.lastOrNull()
    val scrollSignature = remember(turns.size, tailTurn?.id, tailTurn?.text?.length, state.hint) {
        "${turns.size}_${tailTurn?.id.orEmpty()}_${tailTurn?.text?.length ?: 0}_${state.hint != null}"
    }
    LaunchedEffect(scrollSignature, follow, continuing) {
        if (!follow || turns.isEmpty()) return@LaunchedEffect
        val count = listState.layoutInfo.totalItemsCount
        if (count > 0) {
            val lastVisible = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1
            if (lastVisible >= count - 2) {
                listState.animateScrollToItem(count - 1)
            } else {
                listState.scrollToItem(count - 1)
            }
        }
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
            sessionName = sessionName,
            continuing = continuing,
            canStartNew = showNewSessionAction(continuing, turns.size),
            vocabToday = vocabToday,
            onExitSession = onExitSession,
            onNewSession = onNewSession,
            onVocabClick = onVocabClick,
            onSessionNameTap = onSessionNameTap,
        )

        // 列表与输入条叠在同一块里：输入条只占底下胶囊那一条，上方那七十几的留白归列表，
        // 滚到末尾时气泡停在胶囊上沿外十八，不再按整块一百二给底栏让位。
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
        ) {
            // 空态留给首屏那三段字，有了第一轮就换成列表。
            if (turns.isEmpty()) {
                NewSessionHero(Modifier.fillMaxSize())
            } else {
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
                    items(
                        items = turns,
                        key = { it.id },
                        contentType = { it.speaker },
                    ) { turn ->
                        when (turn.speaker) {
                            Speaker.Echo -> EchoRow(
                                turn = turn,
                                translation = translations[turn.id] ?: turn.translation,
                                expanded = expanded[turn.id] == true,
                                translating = turn.id in asking,
                                retryable = retryTargetOf(turns, turn.id) != null,
                                onPlay = { onPlayTurn(turn.id) },
                                onTranslate = { translate(turn) },
                                onSave = { onSaveTurn(turn.id, !turn.saved) },
                                onCopy = { onCopyTurn(turn.id) },
                                onRetry = { retryTargetOf(turns, turn.id)?.let(onRetryAnalysis) },
                                onDelete = { onDeleteTurn(turn.id) },
                            )

                            Speaker.User -> UserBubble(
                                turn = turn,
                                onOpenReview = { onReviewClick(turn) },
                                onRetryAnalysis = { onRetryAnalysis(turn.id) },
                            )
                        }
                    }
                    state.hint?.let { hint ->
                        item(key = "hint", contentType = "hint") { HintCard(hint) }
                    }
                }
            }

            // 一条输入条顶掉原来那三段式底栏：挂断、加号、动作面板、整屏收音面板都不在了。
            // 浮在列表底上，按压、锁定与溢出的悬浮件都在这层，不把列表顶上去。
            VoiceInputBar(
                elapsedMs = elapsedMs,
                levels = levels,
                levelFrame = levelFrame,
                recording = state.recording,
                onPressStart = onRecordPressStart,
                onArmed = onRecordArmed,
                onPressEnd = onRecordPressEnd,
                onSendText = onSendText,
                modifier = Modifier.align(Alignment.BottomCenter),
            )
        }
    }
}

/** 新会话首屏：产品名与双语标语居中上调，下方以双箭头循环往复下跳动效牵引底部输入区。 */
@Composable
private fun NewSessionHero(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(modifier = Modifier.weight(0.38f))
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(text = "Parley", style = Type.hero)
            Text(
                text = "Speak freely, learn naturally.",
                style = Type.heroTagline,
                textAlign = TextAlign.Center,
            )
            Text(text = "自由开口，自然习得", style = Type.heroCn)
        }
        Spacer(modifier = Modifier.weight(0.62f))
        DoubleChevronDownIndicator()
        // 箭头压在胶囊行上沿外一百：输入条浮在列表底上，不再占掉一百二。
        Spacer(modifier = Modifier.height(InputBarSpec.RowHeight + 100.dp))
    }
}

@Composable
private fun DoubleChevronDownIndicator(modifier: Modifier = Modifier) {
    val infiniteTransition = rememberInfiniteTransition(label = "doubleChevronBounce")
    val bounceProgress by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 650, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "chevronBounce",
    )

    val bounceOffset = bounceProgress * 6f
    val topAlpha = 0.35f + bounceProgress * 0.40f
    val bottomAlpha = 0.85f + bounceProgress * 0.15f

    Canvas(
        modifier = modifier.size(width = 20.dp, height = 32.dp),
    ) {
        val stroke = Stroke(
            width = 2.dp.toPx(),
            cap = StrokeCap.Round,
            join = StrokeJoin.Round,
        )

        fun drawChevron(yOffset: Float, alpha: Float) {
            val path = Path().apply {
                moveTo(3.dp.toPx(), (yOffset + 3f).dp.toPx())
                lineTo(10.dp.toPx(), (yOffset + 10f).dp.toPx())
                lineTo(17.dp.toPx(), (yOffset + 3f).dp.toPx())
            }
            drawPath(
                path = path,
                color = Palette.clay.copy(alpha = alpha),
                style = stroke,
            )
        }

        drawChevron(yOffset = bounceOffset, alpha = topAlpha)
        drawChevron(yOffset = bounceOffset + 8f, alpha = bottomAlpha)
    }
}

/**
 * 一轮智能体消息：气泡在上，动作条以画板那道四像素的缝紧贴在下，两行都靠左。
 * 动作条常驻，不再靠长按拉出浮层，所以气泡本身不接指针。
 */
@Composable
private fun EchoRow(
    turn: Turn,
    translation: String?,
    expanded: Boolean,
    translating: Boolean,
    retryable: Boolean,
    onPlay: () -> Unit,
    onTranslate: () -> Unit,
    onSave: () -> Unit,
    onCopy: () -> Unit,
    onRetry: () -> Unit,
    onDelete: () -> Unit,
) {
    Column(Modifier.fillMaxWidth()) {
        EchoTurn(
            turn = turn,
            translation = translation,
            expanded = expanded,
        )
        Spacer(Modifier.height(MessageActionsSpec.BubbleGap))
        MessageActionBar(
            actions = messageActions(
                MessageActionsState(
                    text = turn.text,
                    saved = turn.saved,
                    expanded = expanded,
                    translating = translating,
                    retryable = retryable,
                ),
            ),
            onPick = { command ->
                when (command) {
                    MessageCommand.Speak -> onPlay()
                    MessageCommand.Translate -> onTranslate()
                    MessageCommand.Save -> onSave()
                    MessageCommand.Copy -> onCopy()
                    MessageCommand.Retry -> onRetry()
                    MessageCommand.Delete -> onDelete()
                }
            },
        )
    }
}

@Composable
private fun TopBar(
    sessionName: String,
    continuing: Boolean,
    canStartNew: Boolean,
    vocabToday: Int,
    onExitSession: () -> Unit,
    onNewSession: () -> Unit,
    onVocabClick: () -> Unit,
    onSessionNameTap: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = Spacing.screen)
            .height(44.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // 接着聊一条历史会话时左上角是返回箭头；对话进行中换成气泡加号，点一下收掉这一段并开新的一段。
        // 空着的新会话已经站在起点，左上角不重复放同一枚。
        when {
            continuing -> BackButton(onExitSession)
            canStartNew -> ParleyIconButton(
                icon = FluentIcons.ChatAdd,
                contentDescription = "新会话",
                onClick = onNewSession,
                size = TopBarSpec.IconButton,
                iconSize = TopBarSpec.Icon,
                tint = Palette.ink2,
            )
        }
        Spacer(Modifier.width(4.dp))
        if (sessionName.isNotBlank()) {
            // 会话名看着就是一段文字：没有箭头、按下也不换底色，那一小块仍收得下点击拉出抽屉。
            Pressable(
                modifier = Modifier
                    .widthIn(min = TopBarSpec.NameMin, max = TopBarSpec.NameMax)
                    .padding(horizontal = 2.dp, vertical = 5.dp),
                shape = RoundedCornerShape(Radius.pill),
                resting = Color.Transparent,
                pressing = Color.Transparent,
                contentDescription = "会话记录",
                onClick = onSessionNameTap,
            ) {
                Text(
                    text = sessionName,
                    style = Type.uiStrong,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Spacer(Modifier.weight(1f))
        // 生词本靠最右，设置页收在左侧抽屉右下角。
        Box {
            ParleyIconButton(
                icon = FluentIcons.Book,
                contentDescription = "生词库",
                onClick = onVocabClick,
                size = TopBarSpec.IconButton,
                iconSize = TopBarSpec.Icon,
                tint = Palette.ink2,
            )
            if (vocabToday > 0) {
                Box(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .size(TopBarSpec.Badge)
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
    }
}

/** 对话进行中才出气泡加号；空着的新会话与接着聊的历史会话都不放。 */
internal fun showNewSessionAction(continuing: Boolean, turnCount: Int): Boolean =
    !continuing && turnCount > 0

/** 顶栏这一排的尺寸：左右两枚图标各占三十二，会话名单行省略、宽度上限一百零八。 */
object TopBarSpec {
    val IconButton = 32.dp
    val Icon = 20.dp
    val NameMax = 108.dp
    val NameMin = 76.dp
    val Badge = 16.dp
}
