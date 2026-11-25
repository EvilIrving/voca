package com.voca.speaker.ui.vocab

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.voca.speaker.data.startOfLocalDay
import com.voca.speaker.domain.VocabCard
import com.voca.speaker.domain.VocabFilter
import com.voca.speaker.domain.vocabPage
import com.voca.speaker.ui.component.FlagChip
import com.voca.speaker.ui.component.Pressable
import com.voca.speaker.ui.component.highlighted
import com.voca.speaker.ui.icon.FluentIcons
import com.voca.speaker.ui.session.BackButton
import com.voca.speaker.ui.theme.Palette
import com.voca.speaker.ui.theme.Radius
import com.voca.speaker.ui.theme.Spacing
import com.voca.speaker.ui.theme.Type
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt

private const val SNAP_TRANSLATION_DP = 40f
private const val SNAP_VELOCITY_DPS = 650f
private const val FULL_SWIPE_FRACTION = 0.75f
private const val FULL_SWIPE_VELOCITY_DPS = 1000f
private val DELETE_BLOCK_WIDTH = 80.dp

@Composable
fun VocabScreen(
    cards: List<VocabCard>,
    onDismiss: () -> Unit,
    onSpeak: (VocabCard) -> Unit = {},
    onDelete: (String) -> Unit = {},
    onRestore: (VocabCard) -> Unit = {},
) {
    var filter by remember { mutableStateOf(VocabFilter.All) }
    var query by remember { mutableStateOf("") }
    var openWord by remember { mutableStateOf<String?>(null) }

    var undoCard by remember { mutableStateOf<VocabCard?>(null) }
    val scope = rememberCoroutineScope()
    var undoJob by remember { mutableStateOf<Job?>(null) }

    fun scheduleDelete(card: VocabCard) {
        if (openWord == card.word) openWord = null
        onDelete(card.word)
        undoCard = card
        undoJob?.cancel()
        undoJob = scope.launch {
            delay(5000L)
            undoCard = null
        }
    }

    fun performUndo() {
        undoJob?.cancel()
        undoJob = null
        val restored = undoCard ?: return
        undoCard = null
        onRestore(restored)
    }

    val page = vocabPage(
        cards = cards,
        todayStart = startOfLocalDay(System.currentTimeMillis()),
        filter = filter,
        query = query,
    )

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Palette.paper)
            .windowInsetsPadding(WindowInsets.statusBars),
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = Spacing.screen)
                    .height(44.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                BackButton(onDismiss)
                Spacer(Modifier.width(4.dp))
                Text("生词库", style = Type.display)
            }
            Spacer(Modifier.height(12.dp))
            Surface(
                color = Palette.card,
                shape = RoundedCornerShape(Radius.control),
                border = BorderStroke(1.dp, Palette.hairSoft),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = Spacing.screen)
                    .height(40.dp),
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        FluentIcons.Search,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                        tint = Palette.ink4,
                    )
                    Spacer(Modifier.width(Spacing.iconText))
                    Box(Modifier.weight(1f)) {
                        if (query.isEmpty()) {
                            Text("搜索词或音标", style = Type.ui.copy(color = Palette.ink4))
                        }
                        BasicTextField(
                            value = query,
                            onValueChange = { query = it },
                            singleLine = true,
                            textStyle = Type.ui.copy(color = Palette.ink),
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
            }
            Spacer(Modifier.height(14.dp))
            Row(
                modifier = Modifier.padding(horizontal = Spacing.screen),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                VocabFilter.entries.forEach { option ->
                    FlagChip(
                        "${option.label} ${page.counts[option]}",
                        selected = filter == option,
                        onClick = { filter = option },
                    )
                }
            }
            LazyColumn(
                modifier = Modifier.weight(1f),
                contentPadding = PaddingValues(
                    start = Spacing.screen,
                    end = Spacing.screen,
                    top = Spacing.screen,
                    bottom = Spacing.screen,
                ),
                verticalArrangement = Arrangement.spacedBy(Spacing.block),
            ) {
                if (page.sections.isEmpty()) {
                    item(key = "empty") {
                        Text(
                            text = emptyHint(query, filter),
                            style = Type.caption.copy(color = Palette.ink4),
                            modifier = Modifier.padding(top = Spacing.screen),
                        )
                    }
                }
                page.sections.forEach { section ->
                    item(key = "label-${section.bucket.name}") {
                        Text(section.bucket.label, style = Type.caption.copy(color = Palette.ink3))
                    }
                    items(section.cards, key = { "card-${it.id}" }) { card ->
                        SwipeableVocabCard(
                            card = card,
                            isOpen = openWord == card.word,
                            onOpen = { openWord = card.word },
                            onClose = { if (openWord == card.word) openWord = null },
                            onDelete = { scheduleDelete(card) },
                            onSpeak = onSpeak,
                        )
                    }
                }
            }
        }

        // 乐观删除撤销胶囊浮层（5秒窗口）：居中浮在顶栏下方
        AnimatedVisibility(
            visible = undoCard != null,
            enter = fadeIn() + slideInVertically { -it },
            exit = fadeOut() + slideOutVertically { -it },
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(top = 52.dp),
        ) {
            Surface(
                color = Palette.card,
                shape = RoundedCornerShape(Radius.pill),
                border = BorderStroke(1.dp, Palette.hairSoft),
                shadowElevation = 4.dp,
                modifier = Modifier.clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = { performUndo() },
                ),
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 18.dp, vertical = 9.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        "已删除生词",
                        style = Type.caption.copy(fontSize = 12.5.sp, color = Palette.ink2),
                    )
                    Spacer(Modifier.width(12.dp))
                    Text(
                        "撤销",
                        style = Type.caption.copy(
                            fontSize = 12.5.sp,
                            fontWeight = FontWeight.Bold,
                            color = Palette.clay,
                        ),
                    )
                }
            }
        }
    }
}

/** 空结果的说明：带搜索时把当前档位名一起说出来，不然筛选在「今天」而命中在「待复习」时说不清为什么空。 */
internal fun emptyHint(query: String, filter: VocabFilter): String = when {
    query.isNotBlank() -> "${filter.label}里没有匹配的词"
    filter == VocabFilter.Today -> "今天还没有新词"
    filter == VocabFilter.Due -> "没有待复习的词"
    else -> "还没有生词，对话里出现的地道搭配会自己进来"
}

@Composable
private fun SwipeableVocabCard(
    card: VocabCard,
    isOpen: Boolean,
    onOpen: () -> Unit,
    onClose: () -> Unit,
    onDelete: () -> Unit,
    onSpeak: (VocabCard) -> Unit,
) {
    val density = LocalDensity.current
    val scope = rememberCoroutineScope()
    val deleteWidthPx = with(density) { DELETE_BLOCK_WIDTH.toPx() }
    val offsetAnim = remember { Animatable(0f) }
    var cardWidthPx by remember { mutableFloatStateOf(0f) }

    var dragBase by remember { mutableFloatStateOf(0f) }
    var dragDelta by remember { mutableFloatStateOf(0f) }
    var dragging by remember { mutableStateOf(false) }

    LaunchedEffect(isOpen) {
        if (!isOpen && (offsetAnim.value != 0f || dragging)) {
            dragging = false
            dragDelta = 0f
            offsetAnim.animateTo(0f, spring(stiffness = 600f, dampingRatio = 1f))
        }
    }

    val shape = RoundedCornerShape(Radius.vocabCard)

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .onSizeChanged { cardWidthPx = it.width.toFloat() }
            .pointerInput(card.word) {
                val tracker = VelocityTracker()
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    dragBase = offsetAnim.value
                    dragDelta = 0f
                    scope.launch { offsetAnim.stop() }
                    tracker.resetTracking()

                    var horizontal = false
                    var vertical = false
                    val slopPx = 10.dp.toPx()

                    while (true) {
                        val event = awaitPointerEvent()
                        val change = event.changes.firstOrNull { it.id == down.id } ?: break
                        if (!change.pressed) break

                        val dx = change.position.x - down.position.x
                        val dy = change.position.y - down.position.y

                        if (!horizontal && !vertical) {
                            val ax = abs(dx)
                            val ay = abs(dy)
                            if (max(ax, ay) >= slopPx) {
                                if (ax > ay) {
                                    horizontal = true
                                    dragging = true
                                } else {
                                    vertical = true
                                    if (isOpen) onClose()
                                }
                            }
                        }

                        if (horizontal) {
                            dragDelta = dx
                            tracker.addPosition(change.uptimeMillis, change.position)
                            change.consume()
                        }
                    }

                    if (!horizontal) return@awaitEachGesture

                    val currentOffset = (dragBase + dragDelta).coerceIn(-deleteWidthPx * 1.5f, 0f)
                    val translationDp = currentOffset / density.density
                    val velocityDpPerSec = tracker.calculateVelocity().x / density.density
                    val cardWidthDp = cardWidthPx / density.density

                    val magnitude = abs(translationDp)
                    val speed = if (velocityDpPerSec < 0) abs(velocityDpPerSec) else 0f

                    when {
                        magnitude >= cardWidthDp * FULL_SWIPE_FRACTION || speed >= FULL_SWIPE_VELOCITY_DPS -> {
                            // 全滑执行
                            dragging = false
                            dragDelta = 0f
                            onDelete()
                        }
                        magnitude >= SNAP_TRANSLATION_DP || speed >= SNAP_VELOCITY_DPS -> {
                            // 吸附展开
                            dragging = false
                            dragDelta = 0f
                            onOpen()
                            scope.launch {
                                offsetAnim.snapTo(currentOffset)
                                offsetAnim.animateTo(-deleteWidthPx, spring(stiffness = 600f, dampingRatio = 1f))
                            }
                        }
                        else -> {
                            // 归零关闭
                            dragging = false
                            dragDelta = 0f
                            onClose()
                            scope.launch {
                                offsetAnim.snapTo(currentOffset)
                                offsetAnim.animateTo(0f, spring(stiffness = 600f, dampingRatio = 1f))
                            }
                        }
                    }
                }
            },
    ) {
        // 底层红色删除按钮
        Box(
            modifier = Modifier
                .matchParentSize()
                .clip(shape)
                .background(Palette.danger),
            contentAlignment = Alignment.CenterEnd,
        ) {
            Box(
                modifier = Modifier
                    .width(DELETE_BLOCK_WIDTH)
                    .fillMaxHeight()
                    .clickable { onDelete() },
                contentAlignment = Alignment.Center,
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center,
                ) {
                    Icon(
                        FluentIcons.Delete,
                        contentDescription = "删除",
                        tint = Color.White,
                        modifier = Modifier.size(16.dp),
                    )
                    Spacer(Modifier.width(4.dp))
                    Text(
                        "删除",
                        style = Type.caption.copy(color = Color.White, fontWeight = FontWeight.Bold),
                    )
                }
            }
        }

        // 顶层生词内容卡片
        val displayOffset = if (dragging) {
            (dragBase + dragDelta).coerceIn(-deleteWidthPx * 1.5f, 0f)
        } else {
            offsetAnim.value
        }

        Surface(
            color = Palette.card,
            shape = shape,
            border = BorderStroke(1.dp, Palette.hairSoft),
            modifier = Modifier
                .fillMaxWidth()
                .offset { IntOffset(displayOffset.roundToInt(), 0) },
        ) {
            VocabRow(card, onSpeak)
        }
    }
}

@Composable
private fun VocabRow(card: VocabCard, onSpeak: (VocabCard) -> Unit) {
    Column(Modifier.padding(horizontal = 14.dp, vertical = 11.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Row(Modifier.weight(1f)) {
                Text(
                    text = card.word,
                    style = Type.prose.copy(fontSize = 19.sp, lineHeight = 25.sp, fontWeight = FontWeight.Medium),
                    modifier = Modifier.alignByBaseline(),
                )
                Spacer(Modifier.width(7.dp))
                Text(
                    text = card.phonetic,
                    style = Type.mono.copy(fontSize = 10.5.sp, lineHeight = 14.sp),
                    modifier = Modifier.alignByBaseline(),
                )
            }
            SpeakButton { onSpeak(card) }
        }
        Spacer(Modifier.height(3.dp))
        Text(
            text = card.meaning,
            style = Type.ui.copy(fontSize = 11.5.sp, lineHeight = 16.sp),
        )
        Spacer(Modifier.height(6.dp))
        Text(
            text = highlighted(card.example, card.highlight),
            style = Type.prose.copy(fontSize = 13.5.sp, lineHeight = 20.sp, fontStyle = FontStyle.Italic),
        )
    }
}

@Composable
private fun SpeakButton(onClick: () -> Unit) {
    Pressable(
        modifier = Modifier.size(32.dp),
        shape = RoundedCornerShape(Radius.chip),
        onClick = onClick,
    ) {
        Icon(FluentIcons.Speaker, "朗读", modifier = Modifier.size(16.dp), tint = Palette.ink2)
    }
}
