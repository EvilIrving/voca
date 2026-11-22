package com.parley.speaker.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 输入条的手势机器。数值来自 Telegram Android 的 `ChatActivityEnterView`：
 * 左滑可走距离 min(屏宽×0.35, 140dp)，进度 1→0，滑满当场作废，圆钮随进度缩到 0.7，
 * 锁定要纵向走满一条 LockSpan 且左滑没超过三成。每条用例都从一次真实按压走起，断言档位与副作用。
 */
class VoiceGestureTest {

    // ---- 五档转移 ----

    @Test
    fun `按住够一百九十毫秒进 Holding，差一毫秒还在等`() {
        val pressed = start().play(VoiceInputEvent.Down(1_000))

        val almost = pressed.reduce(VoiceInputEvent.Held(1_189))
        assertEquals(VoicePhase.Idle, almost.state.phase)
        assertTrue(almost.effects.isEmpty())

        val onTime = pressed.reduce(VoiceInputEvent.Held(1_190))
        assertEquals(VoicePhase.Holding, onTime.state.phase)
        assertEquals(listOf(VoiceEffect.StartRecording), onTime.effects)
    }

    @Test
    fun `没走完一百九十毫秒就松手算一次点击，开草稿不收音`() {
        val tap = start()
            .play(VoiceInputEvent.Down(1_000))
            .reduce(VoiceInputEvent.Up(0f, 0f))

        assertEquals(VoicePhase.Idle, tap.state.phase)
        assertEquals(listOf(VoiceEffect.OpenDraft), tap.effects)
    }

    @Test
    fun `锁定之后抬手不改档位，收音自己继续`() {
        val locked = holding().play(VoiceInputEvent.Move(0f, VoiceGesture.LockSpan, 0f, 0f))
        assertEquals(VoicePhase.Locked, locked.phase)

        val lifted = locked.reduce(VoiceInputEvent.Up(0f, 0f))
        assertEquals(VoicePhase.Locked, lifted.state.phase)
        assertTrue(lifted.effects.isEmpty())
    }

    @Test
    fun `锁定态点纸飞机收尾，点取消作废，两条都回 Idle`() {
        val locked = holding().play(VoiceInputEvent.Move(0f, VoiceGesture.LockSpan, 0f, 0f))
        val sent = locked.reduce(VoiceInputEvent.Send)
        assertEquals(listOf(VoiceEffect.Finish(cancelled = false)), sent.effects)
        assertEquals(VoicePhase.Idle, sent.state.phase)

        val dropped = locked.reduce(VoiceInputEvent.Cancel)
        assertEquals(listOf(VoiceEffect.Finish(cancelled = true)), dropped.effects)
        assertEquals(VoicePhase.Idle, dropped.state.phase)
    }

    @Test
    fun `收音在别处结束时锁定态当场收回 Idle，不再补一次收尾`() {
        val locked = holding().play(VoiceInputEvent.Move(0f, VoiceGesture.LockSpan, 0f, 0f))
        val ended = locked.reduce(VoiceInputEvent.TurnEnded)

        assertEquals(VoiceGestureState(lastPressAtMs = ended.state.lastPressAtMs), ended.state)
        assertTrue(ended.effects.isEmpty())
    }

    // ---- 左滑取消 ----

    @Test
    fun `滑满一条可走距离就当场作废，不用抬手`() {
        val void = holding().reduce(VoiceInputEvent.Move(126f, 0f, 0f, 0f))

        assertEquals(VoicePhase.Cancelling, void.state.phase)
        assertEquals(listOf(VoiceEffect.Finish(cancelled = true)), void.effects)
    }

    @Test
    fun `滑到一半多点松手照常发出去`() {
        // 左移 60，进度 1 − 60/126 ≈ 0.52，还在 0.45 之上。
        val still = holding().reduce(VoiceInputEvent.Move(60f, 0f, 0f, 0f))
        assertEquals(VoicePhase.Holding, still.state.phase)
        assertTrue(still.effects.none { it is VoiceEffect.Finish })

        val sent = still.state.reduce(VoiceInputEvent.Up(0f, 0f))
        assertEquals(listOf(VoiceEffect.Finish(cancelled = false)), sent.effects)
    }

    @Test
    fun `滑过一半再松手就作废，不用滑满`() {
        // 左移 70，进度 1 − 70/126 ≈ 0.44，已经过线。
        val over = holding().reduce(VoiceInputEvent.Move(70f, 0f, 0f, 0f))
        assertEquals(VoicePhase.Holding, over.state.phase)

        val dropped = over.state.reduce(VoiceInputEvent.Up(0f, 0f))
        assertEquals(listOf(VoiceEffect.Finish(cancelled = true)), dropped.effects)
        assertEquals(VoicePhase.Idle, dropped.state.phase)
    }

    @Test
    fun `滑过三成先把取消挂起，滑回来再摘掉`() {
        // 进度 1 − 100/126 ≈ 0.21，已经低于锁定允许的 0.7，进入取消预备。
        val staged = holding().reduce(VoiceInputEvent.Move(100f, 0f, 0f, 0f))
        assertEquals(listOf(VoiceEffect.StageCancel(true)), staged.effects)
        assertTrue(staged.state.cancelStaged)

        val back = staged.state.reduce(VoiceInputEvent.Move(20f, 0f, 0f, 0f))
        assertEquals(listOf(VoiceEffect.StageCancel(false)), back.effects)
        assertFalse(back.state.cancelStaged)
    }

    @Test
    fun `可走距离随屏宽走，宽屏封顶一百四十`() {
        assertEquals(126f, VoiceGesture.cancelSpan(360f), 0.001f)
        assertEquals(140f, VoiceGesture.cancelSpan(600f), 0.001f)
        // 窄屏按屏宽算：320 宽屏是 112。
        val narrow = holding().reduce(VoiceInputEvent.Move(112f, 0f, 0f, 0f, span = 112f))
        assertEquals(VoicePhase.Cancelling, narrow.state.phase)
    }

    @Test
    fun `作废之后抬手只是回 Idle，不会再补一刀`() {
        val voided = holding().play(VoiceInputEvent.Move(126f, 0f, 0f, 0f))
        val lifted = voided.reduce(VoiceInputEvent.Up(0f, 0f))

        assertEquals(VoicePhase.Idle, lifted.state.phase)
        assertTrue(lifted.effects.isEmpty())
    }

    // ---- 上滑锁定 ----

    @Test
    fun `上移五十七就锁上，五十六还在录`() {
        val locked = holding().reduce(VoiceInputEvent.Move(0f, VoiceGesture.LockSpan, 0f, 0f))
        assertEquals(VoicePhase.Locked, locked.state.phase)

        val waiting = holding().reduce(VoiceInputEvent.Move(0f, 56f, 0f, 0f))
        assertEquals(VoicePhase.Holding, waiting.state.phase)
    }

    @Test
    fun `先滑过一半再上滑不给锁，锁上还能点取消`() {
        // 左移 70 时进度已经掉到 0.44，低于锁定要的 0.7。
        val refused = holding().reduce(VoiceInputEvent.Move(70f, 80f, 0f, 0f))
        assertEquals(VoicePhase.Holding, refused.state.phase)
    }

    @Test
    fun `锁定后抬手不锁第二遍，档位留着`() {
        val locked = holding()
            .play(VoiceInputEvent.Move(0f, VoiceGesture.LockSpan, 0f, 0f))
            .reduce(VoiceInputEvent.Up(0f, 0f))
        assertEquals(VoicePhase.Locked, locked.state.phase)
        assertTrue(locked.effects.isEmpty())
    }

    // ---- 双触抑制 ----

    @Test
    fun `两次触摸隔不到四百毫秒，后一次整个手势都不作解释`() {
        val first = start().play(VoiceInputEvent.Down(1_000)).play(VoiceInputEvent.Up(0f, 0f))
        val again = first.reduce(VoiceInputEvent.Down(1_399))

        assertFalse(again.state.pressed)
        assertTrue(again.state.reduce(VoiceInputEvent.Held(1_600)).effects.isEmpty())
    }

    @Test
    fun `隔够四百毫秒手再按下去照常受理`() {
        val first = start().play(VoiceInputEvent.Down(1_000)).play(VoiceInputEvent.Up(0f, 0f))
        val again = first.reduce(VoiceInputEvent.Down(1_400))

        assertTrue(again.state.pressed)
        assertEquals(
            listOf(VoiceEffect.StartRecording),
            again.state.reduce(VoiceInputEvent.Held(1_600)).effects,
        )
    }

    @Test
    fun `还没按过时双触间隔不靠溢出凑，按无穷远算`() {
        assertEquals(Long.MAX_VALUE, VoiceGesture.pressGap(1_000, Long.MIN_VALUE))
        assertEquals(400L, VoiceGesture.pressGap(1_400, 1_000))
        assertFalse("第一次按下去永远不会被当成双触", VoiceGesture.ignoredAsDoubleTouch(Long.MAX_VALUE))
        assertTrue(VoiceGesture.ignoredAsDoubleTouch(VoiceGesture.pressGap(1_399, 1_000)))
    }

    @Test
    fun `按住期间又来一次 Down 不会把机器卡在 Holding`() {
        val again = holding().play(VoiceInputEvent.Down(1_300))

        assertEquals(VoicePhase.Holding, again.phase)
        assertTrue("手指还按着，按压位不该被写成假", again.pressed)

        val voided = again.reduce(VoiceInputEvent.Move(126f, 0f, 0f, 0f))
        assertEquals(VoicePhase.Cancelling, voided.state.phase)
        assertEquals(VoicePhase.Idle, again.reduce(VoiceInputEvent.Up(0f, 0f)).state.phase)
    }

    // ---- 暂停与恢复 ----

    @Test
    fun `锁定之后能暂停，暂停能恢复`() {
        val locked = holding().play(VoiceInputEvent.Move(0f, VoiceGesture.LockSpan, 0f, 0f))
        val paused = locked.reduce(VoiceInputEvent.Pause)
        assertEquals(VoicePhase.Paused, paused.state.phase)

        val resumed = paused.state.reduce(VoiceInputEvent.Resume)
        assertEquals(VoicePhase.Locked, resumed.state.phase)
    }

    @Test
    fun `没锁就按暂停不动，暂停两次也不动`() {
        assertEquals(VoicePhase.Holding, holding().reduce(VoiceInputEvent.Pause).state.phase)

        val paused = holding()
            .play(VoiceInputEvent.Move(0f, VoiceGesture.LockSpan, 0f, 0f))
            .play(VoiceInputEvent.Pause)
        assertEquals(VoicePhase.Paused, paused.play(VoiceInputEvent.Pause).phase)
    }

    @Test
    fun `暂停之后能发送，也能把这段扔掉`() {
        val paused = holding()
            .play(VoiceInputEvent.Move(0f, VoiceGesture.LockSpan, 0f, 0f))
            .play(VoiceInputEvent.Pause)

        assertEquals(listOf(VoiceEffect.Finish(cancelled = false)), paused.reduce(VoiceInputEvent.Send).effects)
        assertEquals(listOf(VoiceEffect.Finish(cancelled = true)), paused.reduce(VoiceInputEvent.Cancel).effects)
    }

    // ---- 收场不留残留 ----

    @Test
    fun `作废收场之后除了上次按下时刻什么都不剩`() {
        val cancelled = holding()
            .play(VoiceInputEvent.Move(80f, 20f, 0f, 0f))
            .play(VoiceInputEvent.Move(126f, 20f, 0f, 0f))
            .reduce(VoiceInputEvent.Up(0f, 0f))

        assertEquals(VoicePhase.Idle, cancelled.state.phase)
        assertEquals(Shift.None, cancelled.state.raw)
        assertEquals(Shift.None, cancelled.state.shift)
        assertFalse(cancelled.state.pressed)
        assertFalse(cancelled.state.cancelStaged)
    }

    @Test
    fun `说完收场之后同样干净，重新按下去是一段新手势`() {
        val once = holding()
            .play(VoiceInputEvent.Move(20f, 10f, 0f, 0f))
            .reduce(VoiceInputEvent.Up(0f, 0f))
        assertEquals(Shift.None, once.state.shift)

        val twice = once.state.play(VoiceInputEvent.Down(5_000))
        assertTrue(twice.pressed)
        assertEquals(Shift.None, twice.raw)
    }

    @Test
    fun `锁定取消也把位移清干净`() {
        val locked = holding().play(VoiceInputEvent.Move(0f, VoiceGesture.LockSpan, 0f, 0f))
        val cancelled = locked.reduce(VoiceInputEvent.Cancel)

        assertEquals(Shift.None, cancelled.state.raw)
        assertEquals(Shift.None, cancelled.state.shift)
        assertEquals(0f, VoiceGesture.lockProgress(cancelled.state.raw.up))
    }

    // ---- 公式 ----

    @Test
    fun `进度从一走到零，静止为一，滑满为零`() {
        assertEquals(1f, VoiceGesture.cancelProgress(0f, 126f), 0f)
        assertEquals(0.5f, VoiceGesture.cancelProgress(63f, 126f), 0.0001f)
        assertEquals(0f, VoiceGesture.cancelProgress(126f, 126f), 0f)
        assertEquals(0f, VoiceGesture.cancelProgress(200f, 126f), 0f)
    }

    @Test
    fun `圆钮随进度从八十二收到七成下限`() {
        assertEquals(1f, VoiceGesture.circleScale(1f), 0f)
        assertEquals(0.85f, VoiceGesture.circleScale(0.5f), 0.0001f)
        assertEquals(0.7f, VoiceGesture.circleScale(0f), 0.0001f)
        // 八十二乘零点七，作废那一刻停在五十七点四。
        assertEquals(57.4f, InputBarSpec.RecordSize.value * VoiceGesture.circleScale(0f), 0.01f)
    }

    @Test
    fun `提示与进度同源地淡出`() {
        assertEquals(1f, VoiceGesture.hintAlpha(1f), 0f)
        assertEquals(0.5f, VoiceGesture.hintAlpha(0.5f), 0f)
        assertEquals(0f, VoiceGesture.hintAlpha(0f), 0f)
    }

    @Test
    fun `作废线就在四成五，与源码的 alpha 阈值一致`() {
        assertFalse(VoiceGesture.cancelsOnRelease(0.45f))
        assertTrue(VoiceGesture.cancelsOnRelease(0.44f))
        assertFalse(VoiceGesture.cancelsOnRelease(1f))
    }

    @Test
    fun `锁定进度按整条行程算，到顶封住`() {
        assertEquals(0f, VoiceGesture.lockProgress(0f), 0f)
        assertEquals(0.5f, VoiceGesture.lockProgress(VoiceGesture.LockSpan / 2f), 0.0001f)
        assertEquals(1f, VoiceGesture.lockProgress(VoiceGesture.LockSpan), 0f)
        assertEquals(1f, VoiceGesture.lockProgress(120f), 0f)
    }

    @Test
    fun `位置每次只吃进目标的零点三，往回不跑`() {
        assertEquals(30f, VoiceGesture.follow(0f, 100f), 0.0001f)
        assertEquals(51f, VoiceGesture.follow(30f, 100f), 0.0001f)
        assertEquals(0f, VoiceGesture.follow(0f, -20f), 0.0001f)
    }

    @Test
    fun `电平每帧只吃进新值的零点一`() {
        assertEquals(0.1f, VoiceGesture.smoothLevel(0f, 1f), 0.0001f)
        assertEquals(0.9f, VoiceGesture.smoothLevel(1f, 0f), 0.0001f)
    }

    @Test
    fun `计时读作分、两位数秒、逗号、十分位`() {
        assertEquals("0:00,0", formatBarClock(0))
        assertEquals("0:04,6", formatBarClock(4_600))
        assertEquals("1:05,0", formatBarClock(65_000))
    }

    private fun start() = VoiceGestureState()

    /** 一次已经按住并进了 Holding 的手势，后面接拖动或抬手。 */
    private fun holding(): VoiceGestureState =
        start().play(VoiceInputEvent.Down(1_000)).play(VoiceInputEvent.Held(1_190))

    private fun VoiceGestureState.play(vararg events: VoiceInputEvent): VoiceGestureState =
        events.fold(this) { state, event -> state.reduce(event).state }
}
