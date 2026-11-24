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
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateSetOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.ContentDrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.node.DrawModifierNode
import androidx.compose.ui.node.ModifierNodeElement
import androidx.compose.ui.node.invalidateDraw
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
    // 译文按轮留在组合里，收起只改可见性，同一条再点不会重发请求。
    val translations = remember { mutableStateMapOf<String, String>() }
    val expanded = remember { mutableStateMapOf<String, Boolean>() }
    // 快照集合而不是普通 Set：翻译在飞时 translating 要跟着变，否则整行不会重组。
    val asking = remember { mutableStateSetOf<String>() }
    val scope = rememberCoroutineScope()

    // 还没出字的草稿轮占的是轮次的位置，不是界面上的一格。
    // 识别文本回来得比回复晚时，它已经带着自己的位置站在那一轮前面了。
    val shown = remember(turns) { turns.filter { it.text.isNotBlank() } }

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
            canStartNew = showNewSessionAction(continuing, shown.size),
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
            if (shown.isEmpty()) {
                NewSessionHero(Modifier.fillMaxSize())
            } else {
                // 列表反序铺：末尾那一轮由结构钉在底部，回复一段段长出来时新字往上走。
                // 正的铺法每长出一块都要拿滚动去追，追的那一下总比内容晚一帧，那一帧就是闪。
                val listState = rememberLazyListState()
                val slide = remember { ContentSlide() }
                LazyColumn(
                    state = listState,
                    reverseLayout = true,
                    modifier = Modifier
                        .fillMaxSize()
                        .rowSlide(listState, slide),
                    contentPadding = PaddingValues(
                        start = Spacing.screen,
                        end = Spacing.screen,
                        top = Spacing.bubble,
                        bottom = ListBottomPadding,
                    ),
                    // 内容不够满屏时按竖直方向居底：默认那套对齐把短会话堆在顶部，末尾那条便站不到
                    // 输入条上沿外，内容一长过一屏还会从对顶跳到钉底，白留一次跳。
                    verticalArrangement = Arrangement.spacedBy(Spacing.bubble, Alignment.Bottom),
                ) {
                    // 反序之后第 1 项画在最底下，所以提示卡给在前、轮次倒着给。
                    state.hint?.let { hint ->
                        item(key = "hint", contentType = "hint") { HintCard(hint) }
                    }
                    items(
                        items = shown.asReversed(),
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

/**
 * 行高在正文里是一格一格跳的：长出一行，布局当帧就把整块内容顶上去一格。
 * 这里在画这一拍读这一帧量好的内容高度，把长出来的那段先让回来、再逐帧收掉，
 * 新行于是从输入条后面滑上来的。读与写同在画这一拍里，不等下一帧，也就不会先硬跳一帧。
 */
private class RowSlideNode(
    var listState: LazyListState,
    var slide: ContentSlide,
) : Modifier.Node(), DrawModifierNode {

    override fun ContentDrawScope.draw() {
        val height = listState.layoutInfo.visibleItemsInfo.sumOf { it.size }
        val shift = if (listState.isScrollInProgress) {
            slide.sync(height)
            0f
        } else {
            slide.settle(height, System.nanoTime())
        }
        if (shift > 0f) {
            translate(top = shift) { this@draw.drawContent() }
            invalidateDraw()
        } else {
            drawContent()
        }
    }
}

private data class RowSlideElement(
    val listState: LazyListState,
    val slide: ContentSlide,
) : ModifierNodeElement<RowSlideNode>() {
    override fun create() = RowSlideNode(listState, slide)

    override fun update(node: RowSlideNode) {
        node.listState = listState
        node.slide = slide
    }
}

private fun Modifier.rowSlide(listState: LazyListState, slide: ContentSlide): Modifier =
    this then RowSlideElement(listState, slide)

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
