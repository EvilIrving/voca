package com.voca.speaker.ui.chat

import kotlin.math.max
import kotlin.math.min

/**
 * 输入条的五档。草稿输入是文字模式，不属于手势，所以不在这台机器里：
 * Idle 里按一下就开草稿，按住够久才进 Holding。
 */
enum class VoicePhase { Idle, Holding, Locked, Paused, Cancelling }

/** 手指位移，向左与向上都取正值，未动的方向留着 0。 */
data class Shift(val left: Float = 0f, val up: Float = 0f) {
    companion object {
        val None = Shift()
    }
}

/**
 * 阈值与曲线全是纯函数，位移单位 dp、速度单位 dp 每秒，方位一律取正值：
 * 源码里的「左移 −150、横向速度 −400」到这里就是 left = 150、leftSpeed = 400。
 */
object VoiceGesture {
    /** 点击与长按的分界，抬起时还没走完就是一次点击。 */
    const val HoldMs = 190L

    /** 两次触摸间隔不足这个数直接忽略，防手抖连按出两段录音。 */
    const val DoubleTouchMs = 400L

    /**
     * 左滑可走的距离：屏宽的三成五，上限 140。360 宽的屏等于 126，
     * 与 Telegram Android 的 `distCanMove` 同一套算法（ChatActivityEnterView.java）。
     */
    const val CancelSpanRatio = 0.35f
    const val CancelSpanMax = 140f
    const val CancelSpan = 126f
    fun cancelSpan(bandWidth: Float): Float = min(bandWidth * CancelSpanRatio, CancelSpanMax)

    /**
     * 上移九十六且左滑没走过量才锁，先滑再上滑不给锁。
     * Telegram 那边是五十七，按住时中指拇指都在屏底，五十七太容易蹭上去，这里拉长到接近两倍。
     */
    const val LockSpan = 96f

    /** 左滑超过三成就不许锁，避免边滑边锁。 */
    const val LockableProgress = 0.7f

    /**
     * 抬手时的作废线。源码 ACTION_UP 里滑出这条线就 stopRecording(0)：
     * `alpha = 1 + dist / distCanMove`，alpha 低于 0.45 作废，其余照常发出去。
     */
    const val ReleaseCancelBelow = 0.45f

    /** 圆钮随进度从 1 缩到 0.7，取消点停在 0.7。 */
    const val ShrinkFloor = 0.7f

    /** 位置每次指针事件只吃进目标的 0.3，图形永远落后手指一截，松手后自己追上来。 */
    private const val Follow = 0.3f

    /** 电平每帧吃进新值的 0.1。 */
    private const val LevelFollow = 0.1f

    fun startsRecordingAfter(heldMs: Long): Boolean = heldMs >= HoldMs

    /**
     * 上一次按下到这一次的间隔。还没按过时给 [Long.MAX_VALUE]，
     * 不靠 `atMs - Long.MIN_VALUE` 那一下溢出凑出「很久以前」。
     */
    fun pressGap(atMs: Long, lastPressAtMs: Long): Long =
        if (lastPressAtMs == Long.MIN_VALUE) Long.MAX_VALUE else atMs - lastPressAtMs

    fun ignoredAsDoubleTouch(gapMs: Long): Boolean = gapMs >= 0 && gapMs < DoubleTouchMs

    /** 左滑进度：静止是 1，滑满一条可走距离归零，归零当刻作废。 */
    fun cancelProgress(left: Float, span: Float = CancelSpan): Float =
        (1f - left / span).coerceIn(0f, 1f)

    fun cancelsOnRelease(progress: Float): Boolean = progress < ReleaseCancelBelow

    fun cancelsWhileDragging(left: Float, span: Float = CancelSpan): Boolean = left >= span

    /** 抬手时的锁定补判，门槛与拖动时同一套：纵向五十七，左滑没超过三成。 */
    fun locksOnRelease(up: Float, progress: Float): Boolean =
        up >= LockSpan && progress >= LockableProgress

    fun locksWhileDragging(up: Float, progress: Float): Boolean =
        up >= LockSpan && progress >= LockableProgress

    fun lockProgress(up: Float): Float = (up / LockSpan).coerceIn(0f, 1f)

    /** 圆钮直径的缩放比：跟进度走，静止一，取消点零点七。 */
    fun circleScale(progress: Float): Float =
        (ShrinkFloor + (1f - ShrinkFloor) * progress).coerceIn(ShrinkFloor, 1f)

    /** 提示与进度同源地淡出，滑到底正好透明。 */
    fun hintAlpha(progress: Float): Float = progress.coerceIn(0f, 1f)

    fun follow(current: Float, target: Float): Float =
        max(0f, current * (1f - Follow) + target * Follow)

    fun smoothLevel(current: Float, input: Float): Float =
        current * (1f - LevelFollow) + input * LevelFollow
}

/** 录音计时：分、两位数秒、逗号、十分位，实机上读作 0:04,6。 */
fun formatBarClock(ms: Long): String {
    val total = ms / 1000
    return "%d:%02d,%d".format(total / 60, total % 60, (ms % 1000) / 100)
}

sealed interface VoiceInputEvent {
    /** 手指落下，带上时刻，双触抑制与长按都按它算。 */
    data class Down(val atMs: Long) : VoiceInputEvent

    /** 一百九十毫秒的计时器走完，此时还在按着才算按住。 */
    data class Held(val atMs: Long) : VoiceInputEvent

    data class Move(
        val left: Float,
        val up: Float,
        val leftSpeed: Float,
        val upSpeed: Float,
        /** 这一条手势可走的左滑距离，由界面按屏宽算好传进来，默认按 360 宽屏的 126。 */
        val span: Float = VoiceGesture.CancelSpan,
    ) : VoiceInputEvent

    data class Up(
        val leftSpeed: Float,
        val upSpeed: Float,
        val span: Float = VoiceGesture.CancelSpan,
    ) : VoiceInputEvent

    /** 锁定后点圆钮上的发送键。 */
    data object Send : VoiceInputEvent

    /** 锁定后点「取消」或预览态的垃圾桶。 */
    data object Cancel : VoiceInputEvent

    data object Pause : VoiceInputEvent
    data object Resume : VoiceInputEvent

    /** 收音在别处结束了：连接断了、麦克风打不开、或者回合被作废。 */
    data object TurnEnded : VoiceInputEvent
}

/** 机器要外面做的事：抬手、挂起取消、真正开录与停录、开草稿输入。 */
sealed interface VoiceEffect {
    data object StartRecording : VoiceEffect

    /** 取消已经挂起，抬手就作废。 */
    data class StageCancel(val staged: Boolean) : VoiceEffect

    data class Finish(val cancelled: Boolean) : VoiceEffect

    data object OpenDraft : VoiceEffect
}

data class VoiceGestureState(
    val phase: VoicePhase = VoicePhase.Idle,
    /** 手指此刻是否还按在条上。锁定之后仍然是录音，但手指已经抬起来了。 */
    val pressed: Boolean = false,
    val pressedAtMs: Long = 0L,
    val lastPressAtMs: Long = Long.MIN_VALUE,
    /** 判定用的位移，抬手就归零，下一段手势从原点起算。 */
    val raw: Shift = Shift.None,
    /** 绘制用的位移，0.7/0.3 滤波之后，只在拖动时为真。 */
    val shift: Shift = Shift.None,
    val cancelStaged: Boolean = false,
) {
    /** 取消态与作废态共用一套深红：圆钮缩小、提示转色、计时不再装没事。 */
    val cancelling: Boolean
        get() = phase == VoicePhase.Cancelling || cancelStaged
}

data class VoiceReduction(
    val state: VoiceGestureState,
    val effects: List<VoiceEffect> = emptyList(),
)

fun VoiceGestureState.reduce(event: VoiceInputEvent): VoiceReduction = when (event) {
    is VoiceInputEvent.Down -> down(event.atMs)
    is VoiceInputEvent.Held -> held(event.atMs)
    is VoiceInputEvent.Move -> move(event)
    is VoiceInputEvent.Up -> up(event)
    VoiceInputEvent.Send -> finish(cancelled = false)
    VoiceInputEvent.Cancel -> finish(cancelled = true)
    VoiceInputEvent.Pause -> when (phase) {
        VoicePhase.Locked -> VoiceReduction(copy(phase = VoicePhase.Paused, pressed = false))
        else -> VoiceReduction(this)
    }

    VoiceInputEvent.Resume -> when (phase) {
        VoicePhase.Paused -> VoiceReduction(copy(phase = VoicePhase.Locked))
        else -> VoiceReduction(this)
    }

    VoiceInputEvent.TurnEnded -> when (phase) {
        VoicePhase.Idle -> VoiceReduction(this)
        else -> VoiceReduction(VoiceGestureState(lastPressAtMs = lastPressAtMs))
    }
}

private fun VoiceGestureState.down(atMs: Long): VoiceReduction {
    // 手指还按着的时候又来一次 Down（多指、指针交错）：这一次不参与，
    // 否则 pressed 被写成假而 phase 还留在 Holding，之后的 Move 与 Up 全部早退，机器卡死。
    if (pressed) return VoiceReduction(this)
    // 锁定态里点圆钮会先落到这里：那次按压归按钮用，之后的抬手不再另作解释。
    val usable = phase == VoicePhase.Idle &&
        !VoiceGesture.ignoredAsDoubleTouch(VoiceGesture.pressGap(atMs, lastPressAtMs))
    return VoiceReduction(
        copy(
            pressed = usable,
            pressedAtMs = atMs,
            lastPressAtMs = atMs,
            raw = Shift.None,
            shift = Shift.None,
            cancelStaged = false,
        ),
    )
}

private fun VoiceGestureState.held(atMs: Long): VoiceReduction = when {
    !pressed || phase != VoicePhase.Idle -> VoiceReduction(this)
    !VoiceGesture.startsRecordingAfter(atMs - pressedAtMs) -> VoiceReduction(this)
    else -> VoiceReduction(copy(phase = VoicePhase.Holding), listOf(VoiceEffect.StartRecording))
}

private fun VoiceGestureState.move(event: VoiceInputEvent.Move): VoiceReduction {
    if (!pressed || phase == VoicePhase.Idle) return VoiceReduction(this)
    val raw = Shift(max(0f, event.left), max(0f, event.up))
    val followed = Shift(
        VoiceGesture.follow(shift.left, raw.left),
        VoiceGesture.follow(shift.up, raw.up),
    )
    if (phase != VoicePhase.Holding) {
        return VoiceReduction(copy(raw = raw, shift = followed))
    }
    // 纵向优先：两条线同时越过时按锁上办，锁上还能点取消，作废就捞不回来。
    val progress = VoiceGesture.cancelProgress(raw.left, event.span)
    val lockNow = VoiceGesture.locksWhileDragging(raw.up, progress)
    val cancelNow = !lockNow && VoiceGesture.cancelsWhileDragging(raw.left, event.span)
    val staged = !lockNow && VoiceGesture.cancelsOnRelease(progress)
    val next = when {
        lockNow -> copy(phase = VoicePhase.Locked, raw = raw, shift = followed, cancelStaged = false)
        cancelNow -> copy(phase = VoicePhase.Cancelling, raw = raw, shift = followed, cancelStaged = true)
        else -> copy(raw = raw, shift = followed, cancelStaged = staged)
    }
    val effects = mutableListOf<VoiceEffect>()
    if (!cancelNow && staged != cancelStaged) effects += VoiceEffect.StageCancel(staged)
    if (cancelNow) effects += VoiceEffect.Finish(cancelled = true)
    return VoiceReduction(next, effects)
}

private fun VoiceGestureState.up(event: VoiceInputEvent.Up): VoiceReduction {
    if (!pressed) return VoiceReduction(this)
    // 抬手就把位移清干净，回到 Idle 时不带走任何一格上一段手势留下的数。
    val released = copy(pressed = false, raw = Shift.None, shift = Shift.None)
    return when (phase) {
        VoicePhase.Idle -> VoiceReduction(released, listOf(VoiceEffect.OpenDraft))

        VoicePhase.Holding -> {
            val progress = VoiceGesture.cancelProgress(raw.left, event.span)
            val lock = VoiceGesture.locksOnRelease(raw.up, progress)
            val cancel = !lock && VoiceGesture.cancelsOnRelease(progress)
            val settle = released.copy(cancelStaged = false)
            when {
                lock -> VoiceReduction(
                    settle.copy(phase = VoicePhase.Locked),
                    if (cancelStaged) listOf(VoiceEffect.StageCancel(false)) else emptyList(),
                )

                cancel -> VoiceReduction(
                    settle.copy(phase = VoicePhase.Idle),
                    listOf(VoiceEffect.Finish(cancelled = true)),
                )

                else -> VoiceReduction(
                    settle.copy(phase = VoicePhase.Idle),
                    listOf(VoiceEffect.Finish(cancelled = false)),
                )
            }
        }

        VoicePhase.Cancelling -> VoiceReduction(released.copy(phase = VoicePhase.Idle, cancelStaged = false))
        VoicePhase.Locked, VoicePhase.Paused -> VoiceReduction(released)
    }
}

private fun VoiceGestureState.finish(cancelled: Boolean): VoiceReduction = when (phase) {
    VoicePhase.Locked, VoicePhase.Paused -> VoiceReduction(
        copy(
            phase = VoicePhase.Idle,
            pressed = false,
            cancelStaged = false,
            raw = Shift.None,
            shift = Shift.None,
        ),
        listOf(VoiceEffect.Finish(cancelled)),
    )

    else -> VoiceReduction(this)
}
