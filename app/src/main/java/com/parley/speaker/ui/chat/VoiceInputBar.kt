package com.parley.speaker.ui.chat

import android.os.SystemClock
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.keyframes
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.AwaitPointerEventScope
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.parley.speaker.ui.component.Pressable
import com.parley.speaker.ui.icon.FluentIcons
import com.parley.speaker.ui.theme.Palette
import com.parley.speaker.ui.theme.Radius
import com.parley.speaker.ui.theme.Type
import kotlin.math.max
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull

/**
 * 画板上的尺寸。底栏让位的高度也从这里算，切到草稿输入时还是 12 + 44 + 20，
 * 列表不会因为底栏换了形态塌一下。
 */
object InputBarSpec {
    val SideInset = 7.dp
    val CapsuleWidth = 346.dp
    val CapsuleHeight = 44.dp
    val TopPad = 12.dp
    val BottomPad = 20.dp

    val TextInset = 14.dp
    val ButtonSize = 44.dp


    val CircleSize = 87.dp

    /**
     * 圆钮与四十四同心：任何宽度的东西要压在按钮上，圆心都落在条的右端往里二十二的位置。
     * 八十七长大之后就左右各自溢出，右边那半截落到屏幕外。
     */
    fun centerOnButton(width: Dp): Dp = width / 2 - ButtonSize / 2

    val DotSize = 28.dp
    val DotInset = 13.dp

    val TrashSize = 44.dp
    val PreviewWidth = 252.dp
    val PreviewHeight = 32.dp

    val LockChipWidth = 30.dp
    val LockChipHeight = 66.dp
    val LockChipNarrowHeight = 36.dp
    val LockChipGap = 21.dp

    val KeySize = 36.dp

    /** 底栏高度：输入条自己这一条带子加上下留白。 */
    val Height = TopPad + CapsuleHeight + BottomPad
}

/** 长到八十七用一百毫秒，其余收尾一律一百八十毫秒，只走透明度与位移。 */
private const val GrowMs = 100
private const val SettleMs = 180

/** 锁定后隔这么久浮出暂停键，留出抬手的时间，免得一巴掌拍在暂停上。 */
private const val PauseKeyDelayMs = 560L

/** 收音在别处失败时先宽限这么久再收回输入条：刚开录那一帧 recording 还没翻真。 */
private const val CaptureGraceMs = 250L

@Composable
fun VoiceInputBar(
    elapsedMs: Long,
    levels: FloatArray,
    levelFrame: Long,
    recording: Boolean,
    enabled: Boolean,
    onPressStart: () -> Boolean,
    onArmed: (Boolean) -> Unit,
    onPressEnd: (cancelled: Boolean) -> Unit,
    onSendText: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    var draft by rememberSaveable { mutableStateOf("") }
    // 草稿输入框只在真在打字时挂上去：它一旦在空态就存在，会把按下去的手势当成文字长按吃掉，
    // 底下那层手势永远接不到指针，按住说话就变成弹选中菜单。
    var editing by rememberSaveable { mutableStateOf(false) }
    var gesture by remember { mutableStateOf(VoiceGestureState()) }
    val focus = remember { FocusRequester() }
    val focusManager = LocalFocusManager.current

    // 指针循环只起一次，中途重建会把按到一半的手势掐断。三个回调借 rememberUpdatedState
    // 拿最新一份，状态本身放在同一个 MutableState 里，老闭包读写的也是它。
    val startRecording = rememberUpdatedState(onPressStart)
    val armed = rememberUpdatedState(onArmed)
    val ended = rememberUpdatedState(onPressEnd)

    val dispatch: (VoiceInputEvent) -> Unit = { event ->
        val reduction = gesture.reduce(event)
        gesture = reduction.state
        reduction.effects.forEach { effect ->
            when (effect) {
                VoiceEffect.StartRecording -> {
                    focusManager.clearFocus()
                    // 线路没就绪时 vm 只弹一条提示，机器当场退回 Idle，界面不装作在收音。
                    if (!startRecording.value()) {
                        gesture = gesture.reduce(VoiceInputEvent.TurnEnded).state
                    }
                }

                is VoiceEffect.StageCancel -> armed.value(effect.staged)
                is VoiceEffect.Finish -> ended.value(effect.cancelled)
                VoiceEffect.OpenDraft -> editing = true
            }
        }
    }

    // 锁定之后手指已经离开条，收音自己继续；这一轮要是在别处结束（断线、麦克风打不开），
    // 条得自己收回来，否则它会停在锁定态，计时冻在那儿。刚落手那一帧 recording 还没翻真，
    // 先宽限一小段再判，免得开始录的那一帧把这一轮自己掐掉。
    LaunchedEffect(recording, gesture.phase) {
        val open = gesture.phase
        if (open == VoicePhase.Idle || recording) return@LaunchedEffect
        delay(CaptureGraceMs)
        if (!recording && gesture.phase == open) dispatch(VoiceInputEvent.TurnEnded)
    }

    LaunchedEffect(editing) {
        if (editing) focus.requestFocus()
    }

    // 返回键先把键盘收掉，焦点未必跟着走：在草稿态里按返回就直接退回空态，
    // 否则进过一次草稿之后长按说话可能再也按不出来。
    BackHandler(enabled = editing) {
        editing = false
        focusManager.clearFocus()
    }

    val send: () -> Unit = {
        val text = draft.trim()
        if (text.isNotEmpty()) {
            draft = ""
            editing = false
            focusManager.clearFocus()
            onSendText(text)
        }
    }

    val phase = gesture.phase
    val cancelling = gesture.cancelling
    val gestureLive = enabled && (phase != VoicePhase.Idle || !editing)
    val dragging = phase == VoicePhase.Holding || phase == VoicePhase.Cancelling

    // 手势挂在整条栏的最外层，它必须是胶囊与圆钮的祖先：Compose 的命中测试碰到圆钮自己那枚
    // clickable 就停了，同一层下面的兄弟收不到事件，挂在栏里面那层时手指压在圆钮上等于什么都没按。
    // 可走距离取整个会话窗口的宽度，与源码里 sizeNotifierLayout 的实测宽度同一个口径。
    val span = VoiceGesture.cancelSpan(LocalConfiguration.current.screenWidthDp.toFloat())

    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(InputBarSpec.Height)
            .then(
                if (gestureLive) {
                    Modifier.pointerInput(span) { trackVoiceGesture(span) { dispatch(it) } }
                } else {
                    Modifier
                },
            )
            .padding(horizontal = InputBarSpec.SideInset),
    ) {
        BoxWithConstraints(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(top = InputBarSpec.TopPad)
                .height(InputBarSpec.CapsuleHeight)
                .widthIn(max = InputBarSpec.CapsuleWidth)
                .fillMaxWidth(),
        ) {
            val band = maxWidth
            // 录音与锁定收成读字区三百零二，左滑作废时圆钮已经缩回四十四，胶囊跟着放回三百四十六。
            val narrowed = phase == VoicePhase.Holding || phase == VoicePhase.Locked
            val capsuleWidth by animateDpAsState(
                targetValue = if (narrowed) band - InputBarSpec.ButtonSize else band,
                animationSpec = tween(GrowMs),
                label = "capsuleWidth",
            )
            val leftShift by animateFloatAsState(
                targetValue = if (dragging) gesture.shift.left else 0f,
                animationSpec = if (dragging) snap() else tween(SettleMs),
                label = "circleLeft",
            )
            val upShift by animateFloatAsState(
                targetValue = if (dragging) gesture.shift.up else 0f,
                animationSpec = if (dragging) snap() else tween(SettleMs),
                label = "circleUp",
            )
            // 圆钮跟手缩：进度从 1 走到 0，直径从 87 收到 0.7 倍，与位置读同一份滤波值。
            val progress = VoiceGesture.cancelProgress(leftShift, span)
            val scale = if (dragging) VoiceGesture.circleScale(progress) else 1f
            val circleSize by animateDpAsState(
                targetValue = if (phase == VoicePhase.Idle) {
                    InputBarSpec.ButtonSize
                } else {
                    InputBarSpec.CircleSize * scale
                },
                // 左滑作废时尺寸与位置一起 snap：位置已经跟手了，尺寸再走一百毫秒的 tween
                // 就成了先缩再挪过去。
                animationSpec = if (phase == VoicePhase.Cancelling) snap() else tween(GrowMs),
                label = "circleSize",
            )

            Capsule(
                phase = phase,
                cancelling = cancelling,
                leftShift = gesture.raw.left,
                hintAlpha = if (dragging) VoiceGesture.hintAlpha(progress) else 1f,
                elapsedMs = elapsedMs,
                levels = levels,
                levelFrame = levelFrame,
                draft = draft,
                editing = editing,
                enabled = enabled,
                onDraftChange = { draft = it },
                onSend = send,
                onCancel = { dispatch(VoiceInputEvent.Cancel) },
                onTapField = { focus.requestFocus() },
                onExitEdit = { editing = false },
                focus = focus,
                modifier = Modifier
                    .align(Alignment.CenterStart)
                    .width(capsuleWidth)
                    .fillMaxHeight(),
            )

            RecordButton(
                phase = phase,
                enabled = enabled,
                draft = draft,
                size = circleSize,
                onSend = if (phase == VoicePhase.Idle) send else { { dispatch(VoiceInputEvent.Send) } },
                modifier = Modifier
                    .align(Alignment.CenterEnd)
                    .offset(
                        x = InputBarSpec.centerOnButton(circleSize) - leftShift.dp,
                        y = -upShift.dp,
                    )
                    .size(circleSize),
            )

            LockChip(
                visible = phase == VoicePhase.Holding,
                progress = VoiceGesture.lockProgress(gesture.raw.up),
                modifier = Modifier
                    .align(Alignment.CenterEnd)
                    .offset(x = InputBarSpec.centerOnButton(InputBarSpec.LockChipWidth)),
            )

            FloatingKey(
                visible = phase == VoicePhase.Locked,
                icon = FluentIcons.Pause,
                label = "暂停收音",
                delayMs = PauseKeyDelayMs,
                onClick = { dispatch(VoiceInputEvent.Pause) },
                modifier = Modifier
                    .align(Alignment.CenterEnd)
                    .offset(x = InputBarSpec.centerOnButton(InputBarSpec.KeySize)),
            )

            FloatingKey(
                visible = phase == VoicePhase.Paused,
                icon = FluentIcons.ChevUp,
                label = "继续收音",
                delayMs = 0L,
                onClick = { dispatch(VoiceInputEvent.Resume) },
                modifier = Modifier
                    .align(Alignment.CenterEnd)
                    .offset(x = InputBarSpec.centerOnButton(InputBarSpec.KeySize)),
            )
        }
    }
}

@Composable
private fun Capsule(
    phase: VoicePhase,
    cancelling: Boolean,
    leftShift: Float,
    hintAlpha: Float,
    elapsedMs: Long,
    levels: FloatArray,
    levelFrame: Long,
    draft: String,
    editing: Boolean,
    enabled: Boolean,
    onDraftChange: (String) -> Unit,
    onSend: () -> Unit,
    onCancel: () -> Unit,
    onTapField: () -> Unit,
    onExitEdit: () -> Unit,
    focus: FocusRequester,
    modifier: Modifier = Modifier,
) {
    val shape = RoundedCornerShape(Radius.pill)
    Box(
        modifier = modifier
            .clip(shape)
            .background(Palette.card)
            .border(1.dp, Palette.hair, shape),
    ) {
        when {
            phase == VoicePhase.Paused -> PreviewContent(
                levels = levels,
                levelFrame = levelFrame,
                elapsedMs = elapsedMs,
                onDiscard = onCancel,
            )

            phase != VoicePhase.Idle -> RecordingContent(
                phase = phase,
                cancelling = cancelling,
                leftShift = leftShift,
                hintAlpha = hintAlpha,
                elapsedMs = elapsedMs,
                onCancel = onCancel,
            )

            else -> DraftContent(
                draft = draft,
                editing = editing,
                enabled = enabled,
                onChange = onDraftChange,
                onSend = onSend,
                onTapField = onTapField,
                onExitEdit = onExitEdit,
                focus = focus,
            )
        }
    }
}

/** 空态与文字态：一句提示或一个输入框，右端那颗四十四收在同一只胶囊里。 */
@Composable
private fun DraftContent(
    draft: String,
    editing: Boolean,
    enabled: Boolean,
    onChange: (String) -> Unit,
    onSend: () -> Unit,
    onTapField: () -> Unit,
    onExitEdit: () -> Unit,
    focus: FocusRequester,
) {
    // 编辑态由焦点决定：输入框真拿到过焦点之后的失焦才算退出。
    // 刚挂上去那一帧报的失焦不算，否则草稿态一进去就自己弹回来。
    var hadFocus by remember { mutableStateOf(false) }
    Row(modifier = Modifier.fillMaxSize()) {
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight()
                .padding(start = InputBarSpec.TextInset),
            contentAlignment = Alignment.CenterStart,
        ) {
            if (editing) {
                Pressable(
                    modifier = Modifier.fillMaxSize(),
                    enabled = enabled,
                    contentAlignment = Alignment.CenterStart,
                    onClick = onTapField,
                ) {
                    BasicTextField(
                        value = draft,
                        onValueChange = onChange,
                        enabled = enabled,
                        singleLine = true,
                        textStyle = Type.uiStrong,
                        cursorBrush = SolidColor(Palette.clayDeep),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                        keyboardActions = KeyboardActions(onSend = { onSend() }),
                        modifier = Modifier
                            .fillMaxWidth()
                            .focusRequester(focus)
                            .onFocusChanged { state ->
                                if (state.isFocused) {
                                    hadFocus = true
                                } else if (hadFocus) {
                                    hadFocus = false
                                    onExitEdit()
                                }
                            },
                    )
                }
            } else {
                Text(
                    text = draft.ifEmpty { "说点什么" },
                    style = if (draft.isEmpty()) Type.uiInk4 else Type.uiStrong,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Spacer(Modifier.width(InputBarSpec.ButtonSize))
    }
}

/** 录音态：红点与计时压在左端，提示居中，左滑时提示原地淡出。 */
@Composable
private fun RecordingContent(
    phase: VoicePhase,
    cancelling: Boolean,
    leftShift: Float,
    hintAlpha: Float,
    elapsedMs: Long,
    onCancel: () -> Unit,
) {
    val dot = if (cancelling) Palette.danger else Palette.clayDeep
    val clock = if (cancelling) Palette.danger else Palette.ink2
    val blink = blinkAlpha()
    Box(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .align(Alignment.CenterStart)
                .padding(start = InputBarSpec.DotInset),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(InputBarSpec.DotSize)
                    .background(if (phase == VoicePhase.Cancelling) dot else dot.copy(alpha = blink), CircleShape),
            )
            Spacer(Modifier.width(6.dp))
            Text(
                text = formatBarClock(elapsedMs),
                style = Type.mono.copy(fontSize = 11.sp, lineHeight = 14.sp, color = clock),
            )
        }
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            when (phase) {
                VoicePhase.Locked -> Pressable(
                    shape = RoundedCornerShape(Radius.pill),
                    onClick = onCancel,
                ) {
                    Text(
                        "取消",
                        style = Type.uiStrongInk2,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                    )
                }

                // 作废当刻提示已经淡到零，不再补一句「已取消」：Telegram 这边直接就收条了。
                VoicePhase.Cancelling -> Unit

                else -> CancelHint(staged = cancelling, alpha = hintAlpha)
            }
        }
    }
}

/** 提示跟进度淡出，滑满一条可走距离正好透明，那一刻也已经作废了。 */
@Composable
private fun CancelHint(staged: Boolean, alpha: Float) {
    val tint = if (staged) Palette.danger else Palette.ink3
    Row(
        modifier = Modifier.alpha(alpha),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            FluentIcons.ChevRight,
            contentDescription = null,
            modifier = Modifier
                .size(12.dp)
                .rotate(180f),
            tint = tint,
        )
        Spacer(Modifier.width(2.dp))
        Text(
            text = if (staged) "松手取消" else "滑动以取消",
            style = Type.ui.copy(color = tint),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** 暂停预览：左端垃圾桶四十四，中间二百五十二乘三十二的深色波形条，右侧还是那颗八十七。 */
@Composable
private fun PreviewContent(
    levels: FloatArray,
    levelFrame: Long,
    elapsedMs: Long,
    onDiscard: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxSize()
            .padding(start = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Pressable(
            modifier = Modifier.size(InputBarSpec.TrashSize),
            shape = CircleShape,
            contentDescription = "丢掉这段录音",
            onClick = onDiscard,
        ) {
            Icon(
                FluentIcons.Delete,
                contentDescription = null,
                modifier = Modifier.size(18.dp),
                tint = Palette.ink3,
            )
        }
        PreviewBar(
            levels = levels,
            levelFrame = levelFrame,
            elapsedMs = elapsedMs,
            modifier = Modifier
                .width(InputBarSpec.PreviewWidth)
                .height(InputBarSpec.PreviewHeight),
        )
    }
}

/**
 * 深色波形条对着真实电平走。三十四根柱子在同一个 Canvas 里画完，不进组合树；
 * 电平每帧只吃进新值的 0.1，播出来的呼吸不带毛刺。
 */
@Suppress("UNUSED_EXPRESSION")
@Composable
private fun PreviewBar(
    levels: FloatArray,
    levelFrame: Long,
    elapsedMs: Long,
    modifier: Modifier = Modifier,
) {
    val smoothed = remember(levels) { FloatArray(levels.size) }
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(Radius.pill))
            .background(Palette.ink),
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            // 读一次帧计数，电平每换一帧就重绘一次；数组在采集侧原地复用，没有别的信号可用。
            levelFrame
            if (smoothed.isEmpty()) return@Canvas
            val barWidth = 2.dp.toPx()
            val start = 46.dp.toPx()
            val end = 8.dp.toPx()
            val span = (size.width - start - end).coerceAtLeast(1f)
            val gap = ((span - smoothed.size * barWidth) / (smoothed.size - 1)).coerceAtLeast(1f)
            val centerY = size.height / 2f
            val minHeight = 3.dp.toPx()
            val maxHeight = size.height - 8.dp.toPx()
            smoothed.indices.forEach { index ->
                smoothed[index] = VoiceGesture.smoothLevel(smoothed[index], levels[index].coerceIn(0f, 1f))
                val height = (minHeight + (maxHeight - minHeight) * smoothed[index]).coerceAtMost(maxHeight)
                drawRoundRect(
                    color = Color.White.copy(alpha = 0.9f),
                    topLeft = Offset(
                        x = start + index * (barWidth + gap),
                        y = centerY - height / 2f,
                    ),
                    size = Size(barWidth, height),
                    cornerRadius = CornerRadius(barWidth / 2f),
                )
            }
        }
        Text(
            text = formatBarClock(elapsedMs),
            style = Type.mono.copy(fontSize = 10.5.sp, lineHeight = 13.sp, color = Color.White),
            modifier = Modifier
                .align(Alignment.CenterStart)
                .padding(start = 8.dp)
                .background(Palette.ink)
                .padding(vertical = 2.dp),
        )
    }
}

/** 圆钮：空态的四十四是麦克风，有草稿换成纸飞机，录音时长大到八十七。 */
@Composable
private fun RecordButton(
    phase: VoicePhase,
    enabled: Boolean,
    draft: String,
    size: Dp,
    onSend: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val sending = phase == VoicePhase.Locked || phase == VoicePhase.Paused || draft.isNotBlank()
    val fill = when {
        !enabled -> Palette.hairStrong
        sending -> Palette.ink
        else -> Palette.clay
    }
    // 深底发送键按下去退一档到 ink2，与陶土实底降到 clayDeep 是同一个方向。
    val pressedFill = if (sending) Palette.ink2 else Palette.clayDeep
    // 禁不禁用只看真的不可用：空态那颗麦克风照样能点，点下去是开草稿，不是发送。
    Pressable(
        modifier = modifier,
        shape = CircleShape,
        resting = fill,
        pressing = pressedFill,
        enabled = enabled,
        onClick = onSend,
    ) {
        Icon(
            imageVector = if (sending) FluentIcons.Send else FluentIcons.Mic,
            contentDescription = when {
                sending -> "发送"
                else -> "按住说话"
            },
            modifier = Modifier.size(if (size > InputBarSpec.ButtonSize) 28.dp else 18.dp),
            tint = Color.White,
        )
    }
}

/** 锁条浮在圆钮上方二十一，上滑时按锁定进度从六十六收到三十六，箭头跟着淡出去。 */
@Composable
private fun LockChip(visible: Boolean, progress: Float, modifier: Modifier = Modifier) {
    val height = InputBarSpec.LockChipHeight -
        (InputBarSpec.LockChipHeight - InputBarSpec.LockChipNarrowHeight) * progress
    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(tween(SettleMs)),
        exit = fadeOut(tween(SettleMs)),
        modifier = modifier,
    ) {
        Box(
            modifier = Modifier.offset(
                y = -(InputBarSpec.CircleSize / 2 + InputBarSpec.LockChipGap + height / 2),
            ),
        ) {
            Box(
                modifier = Modifier
                    .width(InputBarSpec.LockChipWidth)
                    .height(height)
                    .clip(RoundedCornerShape(Radius.chip))
                    .background(Palette.card)
                    .border(1.dp, Palette.hair, RoundedCornerShape(Radius.chip)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    FluentIcons.LockOpen,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                    tint = Palette.ink3,
                )
            }
            Icon(
                FluentIcons.ChevUp,
                contentDescription = null,
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .offset(y = (-14).dp)
                    .size(14.dp),
                tint = Palette.ink3.copy(alpha = (1f - progress).coerceIn(0f, 1f)),
            )
        }
    }
}

/** 暂停键与恢复键都是三十六，贴在圆钮顶上，只做透明度的进出。 */
@Composable
private fun FloatingKey(
    visible: Boolean,
    icon: ImageVector,
    label: String,
    delayMs: Long,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // 入场是延迟淡入，内容却当帧就挂进树：点击要等延迟走完才开，
    // 否则锁定之后第二下会隔着看不见的键静默切进暂停。
    var live by remember { mutableStateOf(false) }
    LaunchedEffect(visible) {
        live = false
        if (!visible) return@LaunchedEffect
        if (delayMs > 0) delay(delayMs)
        live = true
    }
    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(tween(SettleMs, delayMillis = delayMs.toInt())),
        exit = fadeOut(tween(SettleMs)),
        modifier = modifier,
    ) {
        Box(
            modifier = Modifier.offset(
                y = -(InputBarSpec.CircleSize / 2 + InputBarSpec.LockChipGap + InputBarSpec.KeySize / 2),
            ),
        ) {
            ParleyKey(icon = icon, label = label, enabled = live, onClick = onClick)
        }
    }
}

@Composable
private fun ParleyKey(
    icon: ImageVector,
    label: String,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    Pressable(
        modifier = Modifier.size(InputBarSpec.KeySize),
        shape = CircleShape,
        resting = Palette.card,
        border = BorderStroke(1.dp, Palette.hair),
        enabled = enabled,
        contentDescription = label,
        onClick = onClick,
    ) {
        Icon(icon, contentDescription = null, modifier = Modifier.size(16.dp), tint = Palette.ink3)
    }
}

/** 红点一秒一个来回，透明度掉到零再回来，与真机上那只闪法一致。 */
@Composable
private fun blinkAlpha(): Float {
    val transition = rememberInfiniteTransition(label = "recordingDot")
    val alpha by transition.animateFloat(
        initialValue = 1f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = keyframes {
                durationMillis = 500
                1f at 0
                1f at 227
                0f at 454
                0f at 500
            },
            repeatMode = RepeatMode.Reverse,
        ),
        label = "recordingDotAlpha",
    )
    return alpha
}

/**
 * 一次按压从头管到底：先等两百毫秒看是点击还是按住，按住之后就一路跟手，
 * 抬手或中途作废都在机器里判定，界面只把事件喂进去。
 */
private suspend fun PointerInputScope.trackVoiceGesture(
    span: Float,
    dispatch: (VoiceInputEvent) -> Unit,
) {
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false)
        dispatch(VoiceInputEvent.Down(down.uptimeMillis))
        // 双触抑制只在机器里判一次：这里再存一份时间戳会多造一次抬手事件，
        // 机器那边被抑制的按压本来就不认 Held 与 Up。
        val releasedEarly = withTimeoutOrNull(VoiceGesture.HoldMs) { waitForRelease() }
        if (releasedEarly != null) {
            dispatch(VoiceInputEvent.Up(0f, 0f))
            return@awaitEachGesture
        }
        dispatch(VoiceInputEvent.Held(SystemClock.uptimeMillis()))

        val tracker = VelocityTracker()
        var settled = false
        try {
            while (true) {
                val event = awaitPointerEvent()
                val change = event.changes.firstOrNull { it.id == down.id } ?: break
                tracker.addPosition(change.uptimeMillis, change.position)
                val velocity = tracker.calculateVelocity()
                val left = max(0f, -(change.position.x - down.position.x) / density)
                val up = max(0f, -(change.position.y - down.position.y) / density)
                val leftSpeed = max(0f, -velocity.x / density)
                val upSpeed = max(0f, -velocity.y / density)
                dispatch(VoiceInputEvent.Move(left, up, leftSpeed, upSpeed, span))
                if (!change.pressed) {
                    dispatch(VoiceInputEvent.Up(leftSpeed, upSpeed, span))
                    settled = true
                    break
                }
                change.consume()
            }
        } finally {
            // 手势被系统掐断（来电、下拉通知栏、多指）时抬手事件不会回来，这里补一次收尾。
            if (!settled) dispatch(VoiceInputEvent.Up(0f, 0f))
        }
    }
}

private suspend fun AwaitPointerEventScope.waitForRelease() {
    while (true) {
        val event = awaitPointerEvent()
        if (event.changes.none { it.pressed }) return
    }
}
