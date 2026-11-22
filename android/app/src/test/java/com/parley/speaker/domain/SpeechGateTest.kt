package com.parley.speaker.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 静音闸门的两个丢弃条件：太短，或者整段没高过环境底噪。
 * 底噪由这段帧自己算，所以同样的峰值在安静房间是说话、在嘈杂环境只是噪声。
 */
class SpeechGateTest {

    private fun frame(amplitude: Int) = ShortArray(320) { amplitude.toShort() }

    @Test
    fun `不足四百毫秒的一轮直接丢弃`() {
        // 十九帧三百八十毫秒，哪怕每一帧都很响也不够长。
        val frames = DoubleArray(19) { -20.0 }

        assertTrue(SpeechGate.shouldDrop(frames))
    }

    @Test
    fun `时长够但整段没高过底噪就丢弃`() {
        val frames = DoubleArray(50) { -60.0 + (it % 3) }

        assertTrue("全是环境噪声，不该送去识别，实际底噪 ${SpeechGate.noiseFloor(frames)}", SpeechGate.shouldDrop(frames))
    }

    @Test
    fun `高过底噪的帧凑够四百毫秒才送去识别`() {
        // 五十帧里只有一半冲高，正好是四百毫秒的说话声。
        val frames = DoubleArray(50) { -60.0 }
        for (i in 10 until 30) frames[i] = -30.0

        assertFalse(SpeechGate.shouldDrop(frames))
    }

    @Test
    fun `环境噪声里夹一两下敲击不算说话`() {
        // 三十秒底噪，中间夹三帧一声碰麦：单帧峰值很高，但它们只占六十毫秒。
        val frames = DoubleArray(1_500) { -62.0 + (it % 4) }
        frames[700] = -20.0
        frames[701] = -22.0
        frames[702] = -28.0

        assertTrue("只有六十毫秒高过底噪，不该送去识别", SpeechGate.shouldDrop(frames))
    }

    @Test
    fun `底噪随环境抬升，同样响度在嘈杂处不算说话`() {
        val quiet = DoubleArray(60) { -70.0 }
        for (i in 10 until 40) quiet[i] = -40.0
        val noisy = DoubleArray(60) { -30.0 }

        assertFalse("安静房间里高过底噪的那六百毫秒属于说话", SpeechGate.shouldDrop(quiet))
        assertTrue("整段就停在三十，没有高过它自己的底噪", SpeechGate.shouldDrop(noisy))
    }

    @Test
    fun `统计量入口只看时长与峰值`() {
        assertTrue(SpeechGate.shouldDrop(durationMs = 380, peakDb = -10.0, floorDb = -70.0))
        assertTrue(SpeechGate.shouldDrop(durationMs = 5_000, peakDb = -66.0, floorDb = -70.0))
        assertFalse("刚好高出底噪六分贝就算说话", SpeechGate.shouldDrop(durationMs = 5_000, peakDb = -64.0, floorDb = -70.0))
        assertFalse(SpeechGate.shouldDrop(durationMs = 5_000, peakDb = -50.0, floorDb = -70.0))
    }

    @Test
    fun `帧电平按满量程折算成分贝`() {
        assertEquals(-6.02, SpeechGate.frameDb(frame(16_384)), 0.05)
        assertEquals(-120.0, SpeechGate.frameDb(ShortArray(0)), 0.0)
        assertTrue("静音帧远低于任何环境底噪", SpeechGate.frameDb(frame(0)) < -80.0)
    }

    @Test
    fun `空的一轮不会漏进识别`() {
        assertTrue(SpeechGate.shouldDrop(DoubleArray(0)))
    }
}
