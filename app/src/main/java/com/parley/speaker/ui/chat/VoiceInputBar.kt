package com.parley.speaker.ui.chat

import android.os.SystemClock
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
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
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.AwaitPointerEventScope
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.airbnb.lottie.LottieComposition
import com.airbnb.lottie.compose.LottieAnimation
import com.airbnb.lottie.compose.LottieCompositionSpec
import com.airbnb.lottie.compose.animateLottieCompositionAsState
import com.airbnb.lottie.compose.rememberLottieComposition
import com.parley.speaker.R
import com.parley.speaker.ui.component.Pressable
import com.parley.speaker.ui.component.pressedTone
import com.parley.speaker.ui.icon.FluentIcons
import com.parley.speaker.ui.theme.InterFamily
import com.parley.speaker.ui.theme.Palette
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.tan
import kotlin.random.Random
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull

/**
 * 画板定稿的几何，单位都是 dp、坐标相对各自的父层。
 * 容器三百六乘一百二十，胶囊行从六十四起（底下留八），五档状态共用这一条基座：
 * 圆钮长到八十二、锁条浮上来，胶囊的宽高与位置一格都不动。
 */
object InputBarSpec {
    val Height = 120.dp
    val SideInset = 7.dp
    val PillHeight = 48.dp
    val PillRadius = 24.dp
    val PillGap = 10.dp
    val ActionSize = 48.dp
    val BaseBottom = 8.dp

    /** 胶囊行的顶边：一百二十减八再减四十八，悬浮件都以它为准往上让。 */
    val PillTop = Height - BaseBottom - PillHeight

    val RecordSize = 82.dp
    val MicGlyph = 24.dp
    val RecordMicGlyph = 34.dp
    val SendGlyph = 22.dp

    val DotSize = 10.dp
    val TrashGlyph = 22.dp
    val TrashSlotTop = 13.dp

    val LockWidth = 36.dp
    val LockHeight = 56.dp
    val LockRadius = 18.dp

    /** 悬浮键底边离胶囊顶的距离，锁条本身也贴着这一条。 */
    val FloatGap = 8.dp
    val TooltipWidth = 96.dp
    val TooltipHeight = 32.dp
    val TooltipGap = 10.dp

    val PauseSize = 40.dp
    val ResumeSize = 36.dp

    val LiveBarCount = 18
    val LiveBarStep = 6.dp
    val LiveBarWidth = 3.dp
    val LiveBarMin = 4.dp
    val LiveBarMax = 20.dp

    val TimelineWidth = 236.dp
    val TimelineHeight = 32.dp
    val TimelineRadius = 16.dp
    val PlaySize = 24.dp
    val PlayGlyph = 12.dp
    val PreviewBarCount = 32
    val PreviewBarWidth = 3.dp
    val PreviewBarMin = 3.dp
    val PreviewBarMax = 21.dp

    /** 三百六的屏上正好二百八十八。 */
    fun pillWidth(band: Dp): Dp = band - SideInset * 2 - PillGap - ActionSize

    /** 圆钮圆心落在右侧那颗四十八的中心，长大与收缩都从这一点算。 */
    fun circleCenter(band: Dp): Dp = band - SideInset - ActionSize / 2
}

/** 圆钮四十八与八十二之间用一百毫秒，其余进出收在一百八十毫秒。 */
private const val GrowMs = 100
private const val SettleMs = 180

/** 锁定后隔这么久浮出暂停键，留出抬手的时间。 */
private const val PauseKeyDelayMs = 560L

/** 收音在别处失败时先宽限这么久再收回输入条：刚开录那一帧 recording 还没翻真。 */
private const val CaptureGraceMs = 250L

/** 垃圾桶那支 json 是五十二帧六十帧每秒，读不到合成时长时按它收尾。 */
private const val BinFallbackMs = 870L

/** 未播的波形柱压到四成五。 */
private const val UnplayedAlpha = 0.45f

private val PillShape = RoundedCornerShape(InputBarSpec.PillRadius)
private val ClockStyle = TextStyle(
    fontFamily = InterFamily,
    fontSize = 15.sp,
    lineHeight = 18.sp,
    fontWeight = FontWeight.SemiBold,
    color = Palette.ink,
)
private val PlaceholderStyle = TextStyle(
    fontFamily = InterFamily,
    fontSize = 15.sp,
    lineHeight = 20.sp,
    color = Palette.ink3,
)
private val DraftStyle = PlaceholderStyle.copy(color = Palette.ink)
private val SlideStyle = TextStyle(
    fontFamily = InterFamily,
    fontSize = 14.sp,
    lineHeight = 18.sp,
    color = Palette.ink3,
)
private val CancelStyle = SlideStyle.copy(fontWeight = FontWeight.SemiBold, color = Palette.clayDeep)
private val TooltipStyle = TextStyle(
    fontFamily = InterFamily,
    fontSize = 12.sp,
    lineHeight = 16.sp,
    color = Color.White,
)
private val DurationStyle = TextStyle(
    fontFamily = InterFamily,
    fontSize = 11.sp,
    lineHeight = 13.sp,
    fontWeight = FontWeight.SemiBold,
    color = Color.White.copy(alpha = 0.8f),
)

/** 垃圾桶动效播放期间冻结的那一帧：胶囊与圆钮停在作废当刻，播完才真去收这一轮。 */
private data class DiscardVisual(val phase: VoicePhase, val scale: Float, val left: Float, val token: Long)

/**
 * 底部语音输入条。外面一只一百二十高的容器，里面三层：最底下是双层 blob 光晕，
 * 中间是贴底的胶囊行与右侧圆钮，最上面是锁条、提示气泡与暂停恢复两枚悬浮键。
 * 三层都按固定坐标摆，任何状态都不让胶囊基座重排。
 */
@Composable
fun VoiceInputBar(
    elapsedMs: Long,
    levels: FloatArray,
    levelFrame: Long,
    recording: Boolean,
    enabled: Boolean,
    preview: VoicePreview? = null,
    onPressStart: () -> Boolean,
    onArmed: (Boolean) -> Unit,
    onPressEnd: (cancelled: Boolean) -> Unit,
    onHold: (Boolean) -> Unit = {},
    onPreviewToggle: () -> Unit = {},
    onSendText: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    var draft by rememberSaveable { mutableStateOf("") }
    // 草稿输入框只在真在打字时挂上去：它一旦在空态就存在，会把按下去的手势当成文字长按吃掉，
    // 底下那层手势永远接不到指针，按住说话就变成弹选中菜单。
    var editing by rememberSaveable { mutableStateOf(false) }
    var gesture by remember { mutableStateOf(VoiceGestureState()) }
    var discard by remember { mutableStateOf<DiscardVisual?>(null) }
    var discardToken by remember { mutableLongStateOf(0L) }
    val focus = remember { FocusRequester() }
    val focusManager = LocalFocusManager.current

    // 指针循环只起一次，中途重建会把按到一半的手势掐断。四个回调借 rememberUpdatedState
    // 拿最新一份，状态本身放在同一个 MutableState 里，老闭包读写的也是它。
    val startRecording = rememberUpdatedState(onPressStart)
    val armed = rememberUpdatedState(onArmed)
    val ended = rememberUpdatedState(onPressEnd)
    val holdChanged = rememberUpdatedState(onHold)

    // 可走距离取整个会话窗口的宽度，与源码里 sizeNotifierLayout 的实测宽度同一个口径。
    val span = VoiceGesture.cancelSpan(LocalConfiguration.current.screenWidthDp.toFloat())

    val dispatch: (VoiceInputEvent) -> Unit = { event ->
        // 上一段作废还在播垃圾桶：新的按压一到就先把它收干净，免得两次作废叠在一起。
        if (discard != null) {
            discard = null
            ended.value(true)
        }
        val before = gesture
        val reduction = before.reduce(event)
        val after = reduction.state
        gesture = after
        if (after.phase == VoicePhase.Paused && before.phase != VoicePhase.Paused) holdChanged.value(true)
        if (before.phase == VoicePhase.Paused && after.phase != VoicePhase.Paused) holdChanged.value(false)
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

                is VoiceEffect.Finish -> if (effect.cancelled) {
                    val paused = before.phase == VoicePhase.Paused
                    val progress = VoiceGesture.cancelProgress(before.shift.left, span)
                    discardToken += 1
                    discard = DiscardVisual(
                        phase = if (paused) VoicePhase.Paused else VoicePhase.Cancelling,
                        scale = if (paused) 1f else VoiceGesture.circleScale(progress),
                        left = if (paused) 0f else before.shift.left,
                        token = discardToken,
                    )
                } else {
                    ended.value(false)
                }

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

    // 垃圾桶跑一遍再真正作废：这一段是丢掉还是发出去，要等杯子把录音条吞下去才算数。
    val binComposition by rememberLottieComposition(
        LottieCompositionSpec.RawRes(R.raw.chat_audio_record_delete),
    )
    LaunchedEffect(discard?.token) {
        if (discard == null) return@LaunchedEffect
        delay(binComposition?.duration?.toLong() ?: BinFallbackMs)
        discard = null
        ended.value(true)
    }

    // 输入条被拿走（切回看、断线重连）时作废还挂在半空：这一轮当场收掉，别留着采集在跑。
    val pendingDiscard = rememberUpdatedState(discard)
    DisposableEffect(Unit) {
        onDispose { if (pendingDiscard.value != null) ended.value(true) }
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

    val sendDraft: () -> Unit = {
        val text = draft.trim()
        if (text.isNotEmpty()) {
            draft = ""
            editing = false
            focusManager.clearFocus()
            onSendText(text)
        }
    }

    // 作废动效播放期间界面读冻结的那一帧，手势机器本身已经回到 Idle。
    val frozen = discard
    val phase = frozen?.phase ?: gesture.phase
    val gestureLive = enabled && (gesture.phase != VoicePhase.Idle || !editing)
    val dragging = phase == VoicePhase.Holding || phase == VoicePhase.Cancelling

    // 圆钮左移每帧吃进目标的 0.3：图形始终落后手指一截，松手自己追上来。
    val leftShift by animateFloatAsState(
        targetValue = when {
            frozen != null -> frozen.left
            dragging -> gesture.shift.left
            else -> 0f
        },
        animationSpec = if (dragging) snap() else tween(SettleMs),
        label = "circleLeft",
    )
    val progress = VoiceGesture.cancelProgress(leftShift, span)
    val scale = if (dragging) frozen?.scale ?: VoiceGesture.circleScale(progress) else 1f
    val diameter by animateDpAsState(
        targetValue = if (dragging) InputBarSpec.RecordSize else InputBarSpec.ActionSize,
        animationSpec = tween(GrowMs),
        label = "circleDiameter",
    )

    val inFlight = phase == VoicePhase.Locked || phase == VoicePhase.Paused
    val draftReady = draft.isNotBlank()
    val plane = inFlight || draftReady

    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(InputBarSpec.Height)
            // 手势必须挂在整条栏的最外层：命中测试碰到圆钮自己那枚 clickable 就停了，
            // 挂在栏里面那层时手指压在圆钮上等于什么都没按。
            .then(
                if (gestureLive) {
                    Modifier.pointerInput(span) { trackVoiceGesture(span, dispatch) }
                } else {
                    Modifier
                },
            ),
    ) {
        BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
            val band = maxWidth
            val centerX = InputBarSpec.circleCenter(band)
            val pillWidth = InputBarSpec.pillWidth(band)

            // 底：双层 blob 光晕，整片一张 Canvas 画完，不进组合树。
            Blobs(
                visible = dragging && frozen == null,
                progress = progress,
                levels = levels,
                levelFrame = levelFrame,
                centerX = centerX,
                modifier = Modifier.fillMaxSize(),
            )

            // 中：胶囊基座。宽度只由可用宽度算出，五档状态共用同一条，永不被圆钮挤动。
            Box(
                modifier = Modifier
                    .offset(x = InputBarSpec.SideInset, y = InputBarSpec.PillTop)
                    .size(pillWidth, InputBarSpec.PillHeight)
                    .clip(PillShape)
                    .background(Palette.card)
                    .border(1.dp, Palette.hair, PillShape),
            ) {
                when (phase) {
                    VoicePhase.Idle -> DraftRow(
                        draft = draft,
                        editing = editing,
                        enabled = enabled,
                        onChange = { draft = it },
                        onSend = sendDraft,
                        onExitEdit = { editing = false },
                        focus = focus,
                    )

                    VoicePhase.Holding, VoicePhase.Cancelling -> RecordingRow(
                        progress = progress,
                        elapsedMs = elapsedMs,
                        binPlaying = frozen != null,
                        binComposition = binComposition,
                    )

                    VoicePhase.Locked -> LockedRow(
                        elapsedMs = elapsedMs,
                        levels = levels,
                        levelFrame = levelFrame,
                        onCancel = { dispatch(VoiceInputEvent.Cancel) },
                    )

                    VoicePhase.Paused -> PausedRow(
                        preview = preview,
                        levels = levels,
                        levelFrame = levelFrame,
                        elapsedMs = elapsedMs,
                        binPlaying = frozen != null,
                        binComposition = binComposition,
                        onDiscard = { dispatch(VoiceInputEvent.Cancel) },
                        onToggle = onPreviewToggle,
                    )
                }
            }

            // 圆钮叠在光晕这一层：尺寸由状态算出，长大时向右溢出条外，胶囊一格不动。
            ActionCircle(
                diameter = diameter,
                scale = scale,
                fill = when {
                    !enabled -> Palette.hairStrong
                    dragging -> Palette.clay
                    plane -> Palette.ink
                    else -> Palette.clay
                },
                glyph = if (dragging || !plane) FluentIcons.Mic else FluentIcons.Send,
                glyphSize = when {
                    dragging -> InputBarSpec.RecordMicGlyph
                    plane -> InputBarSpec.SendGlyph
                    else -> InputBarSpec.MicGlyph
                },
                label = when {
                    dragging -> "松开结束"
                    plane -> "发送"
                    else -> "按住说话"
                },
                onClick = when {
                    !enabled -> null
                    inFlight -> ({ dispatch(VoiceInputEvent.Send) })
                    phase == VoicePhase.Idle && draftReady -> sendDraft
                    else -> null
                },
                modifier = Modifier.offset(
                    x = centerX - diameter / 2 - leftShift.dp,
                    y = InputBarSpec.PillTop + InputBarSpec.PillHeight / 2 - diameter / 2,
                ),
            )

            // 顶：悬浮层。锁条、提示气泡、暂停键、恢复键都按固定纵向偏移漂着，不参与布局。
            val holding = phase == VoicePhase.Holding

            LockChip(
                visible = holding,
                modifier = Modifier.offset(
                    x = centerX - InputBarSpec.LockWidth / 2,
                    y = InputBarSpec.PillTop - InputBarSpec.LockHeight,
                ),
            )

            Tooltip(
                visible = holding,
                modifier = Modifier.offset(
                    x = centerX - InputBarSpec.LockWidth / 2 - InputBarSpec.TooltipGap - InputBarSpec.TooltipWidth,
                    y = InputBarSpec.PillTop - InputBarSpec.LockHeight / 2 - InputBarSpec.TooltipHeight / 2,
                ),
            )

            HoldKey(
                visible = phase == VoicePhase.Locked,
                delayMs = PauseKeyDelayMs,
                size = InputBarSpec.PauseSize,
                glyph = FluentIcons.Pause,
                glyphSize = 18.dp,
                label = "暂停收音",
                onClick = { dispatch(VoiceInputEvent.Pause) },
                modifier = Modifier.offset(
                    x = centerX - InputBarSpec.PauseSize / 2,
                    y = InputBarSpec.PillTop - InputBarSpec.FloatGap - InputBarSpec.PauseSize,
                ),
            )

            HoldKey(
                visible = phase == VoicePhase.Paused,
                delayMs = 0L,
                size = InputBarSpec.ResumeSize,
                glyph = FluentIcons.Mic,
                glyphSize = 18.dp,
                label = "继续收音",
                onClick = { dispatch(VoiceInputEvent.Resume) },
                modifier = Modifier.offset(
                    x = centerX - InputBarSpec.ResumeSize / 2,
                    y = InputBarSpec.PillTop - InputBarSpec.FloatGap - InputBarSpec.ResumeSize,
                ),
            )
        }
    }
}

/**
 * 双层 blob 光晕。顶点数、半径抖动与缩放都照 BlobDrawable：半径在四十七附近抖，
 * 缩放是 0.926 / 0.878 再加一点四倍电平，左滑时整体按进度收回去。
 */
@Suppress("UNUSED_EXPRESSION")
@Composable
private fun Blobs(
    visible: Boolean,
    progress: Float,
    levels: FloatArray,
    levelFrame: Long,
    centerX: Dp,
    modifier: Modifier = Modifier,
) {
    val tiny = remember { BlobRing(vertices = 11, minRadius = 47f, maxRadius = 56f, scaleBase = 0.926f) }
    val big = remember { BlobRing(vertices = 12, minRadius = 50f, maxRadius = 57.2f, scaleBase = 0.878f) }
    Canvas(modifier = modifier) {
        // 读一次帧计数，电平每换一帧就重绘一次；数组在采集侧原地复用，没有别的信号可用。
        levelFrame
        if (!visible) return@Canvas
        val reveal = (progress / VoiceGesture.LockableProgress).coerceIn(0f, 1f)
        if (reveal <= 0f) return@Canvas
        val amplitude = levels.maxOrNull()?.coerceIn(0f, 1f) ?: 0f
        val center = Offset(centerX.toPx(), InputBarSpec.PillTop.toPx() + InputBarSpec.PillHeight.toPx() / 2f)
        big.draw(this, center, levelFrame, amplitude, reveal, Palette.clay.copy(alpha = 0.18f), density)
        tiny.draw(this, center, levelFrame, amplitude, reveal, Palette.clay.copy(alpha = 0.28f), density)
    }
}

/**
 * 一团 blob 的顶点与相位。每个顶点各自独立地在两个半径、两个角度之间插值，
 * 相邻顶点用三次贝塞尔接起来，控制柄按两半径的均值算，轮廓因此一直闭合顺滑。
 */
private class BlobRing(
    private val vertices: Int,
    private val minRadius: Float,
    private val maxRadius: Float,
    private val scaleBase: Float,
) {
    private val radius = FloatArray(vertices)
    private val angle = FloatArray(vertices)
    private val nextRadius = FloatArray(vertices)
    private val nextAngle = FloatArray(vertices)
    private val progress = FloatArray(vertices)
    private val speed = FloatArray(vertices)
    private val random = Random(vertices * 7919)
    private val control = ((4.0 / 3.0) * tan(PI / (2 * vertices))).toFloat()
    private var smoothed = 0f
    private var lastFrame = Long.MIN_VALUE

    init {
        for (i in 0 until vertices) {
            seed(radius, angle, i)
            seed(nextRadius, nextAngle, i)
        }
    }

    private fun seed(intoRadius: FloatArray, intoAngle: FloatArray, index: Int) {
        val wobble = 360f / vertices * 0.05f
        intoRadius[index] = minRadius + random.nextFloat() * (maxRadius - minRadius)
        intoAngle[index] = 360f / vertices * index + (random.nextFloat() - 0.5f) * wobble
        speed[index] = 0.017f + 0.003f * random.nextFloat()
    }

    fun draw(
        scope: DrawScope,
        center: Offset,
        frame: Long,
        amplitude: Float,
        reveal: Float,
        color: Color,
        density: Float,
    ) {
        // 电平每帧只吃进新值的 0.1，二十毫秒一帧的分贝被压成呼吸。
        smoothed = smoothed * 0.9f + amplitude * 0.1f
        if (frame != lastFrame) {
            lastFrame = frame
            for (i in 0 until vertices) {
                progress[i] += speed[i] * 0.8f + smoothed * speed[i] * 8.2f * 1.01f
                if (progress[i] >= 1f) {
                    progress[i] = 0f
                    radius[i] = nextRadius[i]
                    angle[i] = nextAngle[i]
                    seed(nextRadius, nextAngle, i)
                }
            }
        }
        val expand = (scaleBase + 1.4f * smoothed) * reveal
        val path = Path()
        for (i in 0 until vertices) {
            val next = if (i + 1 < vertices) i + 1 else 0
            val r1 = lerp(radius[i], nextRadius[i], progress[i]) * expand * density
            val r2 = lerp(radius[next], nextRadius[next], progress[next]) * expand * density
            val a1 = lerp(angle[i], nextAngle[i], progress[i])
            val a2 = lerp(angle[next], nextAngle[next], progress[next])
            val handle = control * (minOf(r1, r2) + (maxOf(r1, r2) - minOf(r1, r2)) / 2f)
            val start = center + rotate(0f, -r1, a1)
            val startControl = center + rotate(handle, -r1, a1)
            val end = center + rotate(0f, -r2, a2)
            val endControl = center + rotate(-handle, -r2, a2)
            if (i == 0) path.moveTo(start.x, start.y)
            path.cubicTo(startControl.x, startControl.y, endControl.x, endControl.y, end.x, end.y)
        }
        scope.drawPath(path, color)
    }

    private fun lerp(from: Float, to: Float, at: Float): Float = from * (1f - at) + to * at

    private fun rotate(x: Float, y: Float, degrees: Float): Offset {
        val radians = degrees * PI / 180.0
        val c = cos(radians).toFloat()
        val s = sin(radians).toFloat()
        return Offset(x * c - y * s, x * s + y * c)
    }
}

/** 空态与文字态：左边一句提示或一个输入框。右端那颗圆钮不在这只胶囊里。 */
@Composable
private fun DraftRow(
    draft: String,
    editing: Boolean,
    enabled: Boolean,
    onChange: (String) -> Unit,
    onSend: () -> Unit,
    onExitEdit: () -> Unit,
    focus: FocusRequester,
) {
    // 编辑态由焦点决定：输入框真拿到过焦点之后的失焦才算退出。
    // 刚挂上去那一帧报的失焦不算，否则草稿态一进去就自己弹回来。
    var hadFocus by remember { mutableStateOf(false) }
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(start = 20.dp, end = 12.dp),
        contentAlignment = Alignment.CenterStart,
    ) {
        if (editing) {
            BasicTextField(
                value = draft,
                onValueChange = onChange,
                enabled = enabled,
                singleLine = true,
                textStyle = DraftStyle,
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
        } else {
            Text(
                text = draft.ifEmpty { "说点什么..." },
                style = if (draft.isEmpty()) PlaceholderStyle else DraftStyle,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/**
 * 录音态：左端一颗陶土圆点、计时、左向折角箭头与「滑动以取消」。
 * 手指往左一挪，圆点换成垃圾桶、计时右移，滑到四成五以下这三样一起转陶土深色。
 */
@Composable
private fun RecordingRow(
    progress: Float,
    elapsedMs: Long,
    binPlaying: Boolean,
    binComposition: LottieComposition?,
) {
    val staged = progress < VoiceGesture.ReleaseCancelBelow
    val sliding = progress < 1f
    val tone = if (staged) Palette.clayDeep else Palette.ink3
    Box(modifier = Modifier.fillMaxSize()) {
        if (sliding) {
            TrashSlot(
                left = 16.dp,
                tint = tone,
                playing = binPlaying,
                composition = binComposition,
                modifier = Modifier.offset(y = InputBarSpec.TrashSlotTop),
            )
            Text(
                text = formatBarClock(elapsedMs),
                style = ClockStyle.copy(color = if (staged) Palette.clayDeep else Palette.ink),
                modifier = Modifier
                    .align(Alignment.CenterStart)
                    .offset(x = 48.dp),
            )
        } else {
            Box(
                modifier = Modifier
                    .align(Alignment.CenterStart)
                    .offset(x = 16.dp)
                    .size(InputBarSpec.DotSize)
                    .background(Palette.clayDeep, CircleShape),
            )
            Text(
                text = formatBarClock(elapsedMs),
                style = ClockStyle,
                modifier = Modifier
                    .align(Alignment.CenterStart)
                    .offset(x = 36.dp),
            )
        }
        Row(
            modifier = Modifier
                .align(Alignment.CenterStart)
                .offset(x = 156.dp)
                .graphicsLayer { alpha = VoiceGesture.hintAlpha(progress) },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                FluentIcons.ChevRight,
                contentDescription = null,
                modifier = Modifier
                    .size(14.dp)
                    .rotate(180f),
                tint = tone,
            )
            Spacer(modifier = Modifier.width(4.dp))
            Text(
                text = "滑动以取消",
                style = SlideStyle.copy(color = tone),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** 锁定态：可点的「取消」、陶土圆点、计时，右侧一溜实时电平柱。 */
@Composable
private fun LockedRow(
    elapsedMs: Long,
    levels: FloatArray,
    levelFrame: Long,
    onCancel: () -> Unit,
) {
    Box(modifier = Modifier.fillMaxSize()) {
        Pressable(
            onClick = onCancel,
            modifier = Modifier
                .align(Alignment.CenterStart)
                .offset(x = 16.dp),
        ) {
            Text(
                text = "取消",
                style = CancelStyle,
                modifier = Modifier.padding(horizontal = 2.dp, vertical = 6.dp),
            )
        }
        Box(
            modifier = Modifier
                .align(Alignment.CenterStart)
                .offset(x = 74.dp)
                .size(InputBarSpec.DotSize)
                .background(Palette.clayDeep, CircleShape),
        )
        Text(
            text = formatBarClock(elapsedMs),
            style = ClockStyle,
            modifier = Modifier
                .align(Alignment.CenterStart)
                .offset(x = 94.dp),
        )
        LiveBars(
            levels = levels,
            levelFrame = levelFrame,
            modifier = Modifier
                .align(Alignment.CenterStart)
                .offset(x = 162.dp, y = InputBarSpec.PillHeight / 2 - InputBarSpec.LiveBarMax / 2)
                .width(InputBarSpec.LiveBarStep * (InputBarSpec.LiveBarCount - 1) + InputBarSpec.LiveBarWidth)
                .height(InputBarSpec.LiveBarMax),
        )
    }
}

/** 暂停态：左边垃圾桶，中间二百三十六乘三十二的深色时间轴。 */
@Composable
private fun PausedRow(
    preview: VoicePreview?,
    levels: FloatArray,
    levelFrame: Long,
    elapsedMs: Long,
    binPlaying: Boolean,
    binComposition: LottieComposition?,
    onDiscard: () -> Unit,
    onToggle: () -> Unit,
) {
    Box(modifier = Modifier.fillMaxSize()) {
        TrashSlot(
            left = 14.dp,
            tint = Palette.ink3,
            playing = binPlaying,
            composition = binComposition,
            onClick = onDiscard,
            modifier = Modifier.offset(y = InputBarSpec.TrashSlotTop),
        )
        Timeline(
            preview = preview,
            levels = levels,
            levelFrame = levelFrame,
            elapsedMs = elapsedMs,
            onToggle = onToggle,
            modifier = Modifier
                .offset(x = 44.dp, y = InputBarSpec.PillHeight / 2 - InputBarSpec.TimelineHeight / 2)
                .size(InputBarSpec.TimelineWidth, InputBarSpec.TimelineHeight),
        )
    }
}

/** 垃圾桶位：平时是矢量图元，作废时换成 Telegram 那支吞噬动效，播一次就弃。 */
@Composable
private fun TrashSlot(
    left: Dp,
    tint: Color,
    playing: Boolean,
    composition: LottieComposition?,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
) {
    // 动效画布一百见方，垃圾桶本体只占中间四十宽五十五高，铺到四十 dp 才与旁边那颗图元一般大。
    val binSize = 40.dp
    Box(
        modifier = modifier
            .offset(x = left)
            .size(InputBarSpec.TrashGlyph),
    ) {
        if (playing && composition != null) {
            val frame by animateLottieCompositionAsState(
                composition = composition,
                iterations = 1,
                isPlaying = true,
                restartOnPlay = true,
            )
            LottieAnimation(
                composition = composition,
                progress = { frame },
                modifier = Modifier
                    .offset(
                        x = (InputBarSpec.TrashGlyph - binSize) / 2,
                        y = (InputBarSpec.TrashGlyph - binSize) / 2,
                    )
                    .size(binSize),
            )
        } else {
            val glyph: @Composable () -> Unit = {
                Icon(
                    FluentIcons.Delete,
                    contentDescription = null,
                    modifier = Modifier.fillMaxSize(),
                    tint = if (playing) Palette.clayDeep else tint,
                )
            }
            if (onClick != null) {
                Pressable(
                    modifier = Modifier.fillMaxSize(),
                    contentDescription = "丢掉这段录音",
                    onClick = onClick,
                ) { glyph() }
            } else {
                glyph()
            }
        }
    }
}

/**
 * 深色时间轴：左端陶土播放键，中段三十二根柱按预览波形画，已播部分陶土实底、未播压到四成五。
 * 没有预览数据时退回实时电平与已录时长，不留一段空白。
 */
@Composable
private fun Timeline(
    preview: VoicePreview?,
    levels: FloatArray,
    levelFrame: Long,
    elapsedMs: Long,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val previewBars = remember(preview?.waveform) {
        preview?.let { resample(waveAmplitudes(it.waveform), InputBarSpec.PreviewBarCount) }
    }
    val liveBars = remember(levelFrame) { resample(levels, InputBarSpec.PreviewBarCount) }
    val bars = previewBars ?: liveBars
    val played = preview?.progress?.coerceIn(0f, 1f) ?: 0f
    val duration = preview?.durationMs ?: elapsedMs
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(InputBarSpec.TimelineRadius))
            .background(Palette.ink),
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val left = 36.dp.toPx()
            val right = 51.dp.toPx()
            val barWidth = InputBarSpec.PreviewBarWidth.toPx()
            val span = size.width - left - right - barWidth
            val gap = span / (bars.size - 1).coerceAtLeast(1)
            val minHeight = InputBarSpec.PreviewBarMin.toPx()
            val maxHeight = InputBarSpec.PreviewBarMax.toPx()
            val centerY = size.height / 2f
            bars.forEachIndexed { index, level ->
                val height = minHeight + (maxHeight - minHeight) * level.coerceIn(0f, 1f)
                val playedPart = index.toFloat() / (bars.size - 1).coerceAtLeast(1) <= played
                drawRoundRect(
                    color = if (playedPart) Palette.clay else Color.White.copy(alpha = UnplayedAlpha),
                    topLeft = Offset(left + index * gap, centerY - height / 2f),
                    size = Size(barWidth, height),
                    cornerRadius = CornerRadius(barWidth / 2f),
                )
            }
        }
        Pressable(
            modifier = Modifier
                .offset(x = 4.dp, y = 4.dp)
                .size(InputBarSpec.PlaySize),
            shape = CircleShape,
            resting = Palette.clay,
            pressing = pressedTone(Palette.clay),
            contentDescription = if (preview?.playing == true) "暂停试听" else "试听这段录音",
            onClick = onToggle,
        ) {
            Icon(
                if (preview?.playing == true) FluentIcons.Pause else FluentIcons.Play,
                contentDescription = null,
                modifier = Modifier.size(InputBarSpec.PlayGlyph),
                tint = Color.White,
            )
        }
        Text(
            text = formatDuration(duration),
            style = DurationStyle,
            modifier = Modifier
                .align(Alignment.CenterStart)
                .offset(x = 194.dp),
        )
    }
}

/** 实时电平柱：十八根，三宽六距，高度在四到二十之间跟电平走。 */
@Suppress("UNUSED_EXPRESSION")
@Composable
private fun LiveBars(levels: FloatArray, levelFrame: Long, modifier: Modifier = Modifier) {
    Canvas(modifier = modifier) {
        // 电平每换一帧重画一次，数组由采集侧原地复用，没有别的信号可用。
        levelFrame
        val barWidth = InputBarSpec.LiveBarWidth.toPx()
        val step = InputBarSpec.LiveBarStep.toPx()
        val minHeight = InputBarSpec.LiveBarMin.toPx()
        val maxHeight = InputBarSpec.LiveBarMax.toPx()
        val centerY = size.height / 2f
        for (index in 0 until InputBarSpec.LiveBarCount) {
            val at = levels.size - InputBarSpec.LiveBarCount + index
            val level = if (at >= 0 && at < levels.size) levels[at].coerceIn(0f, 1f) else 0f
            val height = minHeight + (maxHeight - minHeight) * level
            drawRoundRect(
                color = Palette.clay.copy(alpha = 0.75f),
                topLeft = Offset(index * step, centerY - height / 2f),
                size = Size(barWidth, height),
                cornerRadius = CornerRadius(barWidth / 2f),
            )
        }
    }
}

/**
 * 圆钮：空态四十八的陶土麦克风，录音时长到八十二，有草稿或锁定时是墨色纸飞机。
 * 左滑时按进度缩到七成并跟手左移，缩放走图形层，直径动画不参与。
 */
@Composable
private fun ActionCircle(
    diameter: Dp,
    scale: Float,
    fill: Color,
    glyph: ImageVector,
    glyphSize: Dp,
    label: String,
    onClick: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    val content: @Composable () -> Unit = {
        Icon(
            glyph,
            contentDescription = null,
            modifier = Modifier.size(glyphSize),
            tint = Color.White,
        )
    }
    val sized = modifier
        .size(diameter)
        .graphicsLayer {
            scaleX = scale
            scaleY = scale
        }
    if (onClick != null) {
        Pressable(
            modifier = sized,
            shape = CircleShape,
            resting = fill,
            pressing = pressedTone(fill),
            contentDescription = label,
            onClick = onClick,
        ) { content() }
    } else {
        Box(
            modifier = sized
                .clip(CircleShape)
                .background(fill)
                .semantics { contentDescription = label },
            contentAlignment = Alignment.Center,
        ) { content() }
    }
}

/** 悬浮键：纸白圆钮加细描边，只做透明度进出，延迟落地之后才可点。 */
@Composable
private fun HoldKey(
    visible: Boolean,
    delayMs: Long,
    size: Dp,
    glyph: ImageVector,
    glyphSize: Dp,
    label: String,
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
        Pressable(
            modifier = Modifier.size(size),
            shape = CircleShape,
            resting = Palette.card,
            border = BorderStroke(1.dp, Palette.hair),
            enabled = live,
            contentDescription = label,
            onClick = onClick,
        ) {
            Icon(glyph, contentDescription = null, modifier = Modifier.size(glyphSize), tint = Palette.clay)
        }
    }
}

/** 锁条：上边一枚折角箭头，下边一把陶土锁，底边贴着胶囊顶。 */
@Composable
private fun LockChip(visible: Boolean, modifier: Modifier = Modifier) {
    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(tween(SettleMs)),
        exit = fadeOut(tween(SettleMs)),
        modifier = modifier,
    ) {
        Column(
            modifier = Modifier
                .size(InputBarSpec.LockWidth, InputBarSpec.LockHeight)
                .clip(RoundedCornerShape(InputBarSpec.LockRadius))
                .background(Palette.card)
                .border(1.dp, Palette.hair, RoundedCornerShape(InputBarSpec.LockRadius))
                .padding(top = 10.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Icon(
                FluentIcons.ChevUp,
                contentDescription = null,
                modifier = Modifier.size(12.dp),
                tint = Palette.ink3,
            )
            Spacer(modifier = Modifier.height(6.dp))
            Icon(
                FluentIcons.LockOpen,
                contentDescription = null,
                modifier = Modifier.size(16.dp),
                tint = Palette.clay,
            )
        }
    }
}

/** 锁条左边的墨色气泡，纵向与锁条同中心，左滑作废时整层清空。 */
@Composable
private fun Tooltip(visible: Boolean, modifier: Modifier = Modifier) {
    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(tween(SettleMs)),
        exit = fadeOut(tween(SettleMs)),
        modifier = modifier,
    ) {
        Box(
            modifier = Modifier
                .size(InputBarSpec.TooltipWidth, InputBarSpec.TooltipHeight)
                .clip(RoundedCornerShape(6.dp))
                .background(Palette.ink),
            contentAlignment = Alignment.Center,
        ) {
            Text(text = "上滑以锁定", style = TooltipStyle, maxLines = 1)
        }
    }
}

/** 一次按压从头管到底：先等一百九十毫秒看是点击还是按住，按住之后就一路跟手。 */
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

/** 录音时长读到秒，形如 0:28。 */
private fun formatDuration(ms: Long): String {
    val seconds = ms / 1000
    return "%d:%02d".format(seconds / 60, seconds % 60)
}

/**
 * 一百根柱挤在六十三字节里，每根五 bit、从高位往低位排。
 * 数组短了就当后面都是静音，不抛也不补零之外的东西。
 */
private fun waveAmplitudes(waveform: ByteArray): FloatArray {
    val count = 100
    val values = FloatArray(count)
    var bit = 0
    for (index in 0 until count) {
        var value = 0
        repeat(5) {
            val byteIndex = bit / 8
            if (byteIndex >= waveform.size) return values
            val byte = waveform[byteIndex].toInt() and 0xFF
            value = (value shl 1) or ((byte shr (7 - bit % 8)) and 1)
            bit++
        }
        values[index] = value / 31f
    }
    return values
}

/** 把任意根数的波形重采样成要画的那几根，端点对齐、中间线性插值。 */
private fun resample(values: FloatArray, count: Int): FloatArray {
    if (values.isEmpty()) return FloatArray(count)
    if (values.size == count) return values
    val out = FloatArray(count)
    val step = (values.size - 1).toFloat() / (count - 1).coerceAtLeast(1)
    for (index in 0 until count) {
        val at = index * step
        val low = at.toInt().coerceIn(0, values.lastIndex)
        val high = (low + 1).coerceAtMost(values.lastIndex)
        val weight = at - low
        out[index] = values[low] * (1f - weight) + values[high] * weight
    }
    return out
}
