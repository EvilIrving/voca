package com.parley.speaker.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 人声概率序列到"这一轮值不值得发出去"的换算：看最长的一段连续人声，不看累计。
 * 概率本身由模型给，这里只测后处理与归一化。
 */
class SpeechGateTest {

    private fun probs(vararg values: Float) = values

    @Test
    fun `整整一段都判成人声`() {
        val window = SpeechGate.WindowMs

        assertFalse(SpeechGate.shouldDrop(SpeechGate.longestSpeechMs(FloatArray(20) { 0.9f })))
        assertEquals(20 * window, SpeechGate.longestSpeechMs(FloatArray(20) { 0.9f }))
    }

    @Test
    fun `整段没人声的一轮丢弃`() {
        assertTrue(SpeechGate.shouldDrop(SpeechGate.longestSpeechMs(FloatArray(50) { 0.02f })))
    }

    @Test
    fun `零散尖峰凑够时长也不算说话`() {
        // 每十窗才有一窗过线，间隔三百多毫秒，各自成段。
        val values = FloatArray(100) { 0.01f }
        for (hit in 0 until 10) values[hit * 10] = 0.95f

        val speech = SpeechGate.longestSpeechMs(values)
        assertEquals("最长的一段就是单独一窗", SpeechGate.WindowMs, speech)
        assertTrue(SpeechGate.shouldDrop(speech))
    }

    @Test
    fun `换气空档不把一句话切碎`() {
        // 连续人声里空三窗，不到一百毫秒，仍算同一段。
        val values = FloatArray(40) { 0.9f }
        for (i in 20 until 23) values[i] = 0.1f

        val speech = SpeechGate.longestSpeechMs(values)
        assertTrue("换气之后应当接上，实际 $speech", speech > 20 * SpeechGate.WindowMs)
        assertFalse(SpeechGate.shouldDrop(speech))
    }

    @Test
    fun `空档超过一百毫秒就另起一段`() {
        // 两百五十六毫秒人声、一百九十二毫秒空档、两百二十四毫秒人声：分开算就都不够。
        val values = FloatArray(60) { 0.02f }
        for (i in 0 until 8) values[i] = 0.9f
        for (i in 14 until 21) values[i] = 0.9f

        val speech = SpeechGate.longestSpeechMs(values)
        assertEquals(8 * SpeechGate.WindowMs, speech)
        assertTrue(SpeechGate.shouldDrop(speech))
    }

    @Test
    fun `太短的一段人声丢弃`() {
        val values = FloatArray(200) { 0.02f }
        for (i in 40 until 46) values[i] = 0.9f

        assertTrue("六窗才一百九十二毫秒", SpeechGate.shouldDrop(SpeechGate.longestSpeechMs(values)))
    }

    @Test
    fun `整段静音归一化之后仍是零`() {
        val flat = SpeechGate.normalize(ShortArray(1_000))

        assertTrue(flat.all { it == 0f })
    }

    @Test
    fun `归一化把整段的电平平到负二十分贝`() {
        val pcm = ShortArray(1_000) { if (it % 2 == 0) 3_000 else -3_000 }

        val peak = SpeechGate.normalize(pcm).maxOf { kotlin.math.abs(it) }

        assertEquals(0.1f, peak, 0.001f)
    }

    @Test
    fun `极低电平的语音归一化之后幅度回到工作点`() {
        // 峰值只有三十分贝的小声：归一化把它拉回同一档，模型才看得见频谱。
        val pcm = ShortArray(1_000) { if (it % 2 == 0) 32 else -32 }

        val peak = SpeechGate.normalize(pcm).maxOf { kotlin.math.abs(it) }

        assertEquals(0.1f, peak, 0.001f)
    }

    @Test
    fun `开头一声撞击不把后面的话压下去`() {
        // 手指碰到机壳那一下能顶到满量程，电平参照按分位取，不跟着峰值走。
        val pcm = ShortArray(SpeechGate.SampleRate * 3) {
            val value = when {
                it < SpeechGate.WindowSamples * 5 / 8 -> 30_000
                else -> 3_000
            }
            if (it % 2 == 0) value.toShort() else (-value).toShort()
        }

        val level = SpeechGate.normalize(pcm).last()

        assertEquals(0.1f, kotlin.math.abs(level), 0.002f)
    }

    @Test
    fun `直流被抹掉`() {
        val pcm = ShortArray(SpeechGate.SampleRate) { (12_000 + if (it % 2 == 0) 3_000 else -3_000).toShort() }

        val normalized = SpeechGate.normalize(pcm)

        assertEquals(0.0, normalized.average(), 0.001)
        assertEquals(0.1f, normalized.maxOf { kotlin.math.abs(it) }, 0.002f)
    }

    @Test
    fun `空数组不会漏进识别`() {
        assertTrue(SpeechGate.shouldDrop(SpeechGate.longestSpeechMs(FloatArray(0))))
        assertEquals(0, SpeechGate.normalize(ShortArray(0)).size)
    }

    @Test
    fun `阈值之上的概率才算人声`() {
        val values = probs(0.49f, 0.5f, 0.51f)

        assertEquals(2 * SpeechGate.WindowMs, SpeechGate.longestSpeechMs(values))
    }
}
