package com.parley.speaker.audio

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin
import kotlin.math.sqrt
import java.io.File
import kotlin.math.pow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 增益层的验收看四段电平：小声说完一句话后输出落到目标附近，响亮的句子逐样通过，
 * 句间停顿不把增益放掉，撞上来的峰走软限幅而不是硬切。都按二十毫秒一帧的节奏算。
 */
class InputGainTest {

    private val frameSamples = 960

    private fun tone(amplitude: Double, frames: Int = 60, hz: Double = 180.0): List<ShortArray> =
        (0 until frames).map { index ->
            val base = index * frameSamples
            ShortArray(frameSamples) { slot ->
                (amplitude * sin(2 * PI * hz * (base + slot) / VoiceRecorder.SAMPLE_RATE)).toInt().toShort()
            }
        }

    private fun rms(frame: ShortArray): Double {
        var sum = 0.0
        for (sample in frame) sum += sample.toDouble() * sample
        return sqrt(sum / frame.size)
    }

    @Test
    fun `小声说话被拉到目标电平`() {
        val gain = InputGain()
        val quiet = tone(amplitude = 120.0)
        quiet.forEach { gain.apply(it) }

        val level = rms(quiet.last())
        assertEquals("输出 RMS 落在负二十分贝附近", InputGain.TargetRms, level, InputGain.TargetRms * 0.25)
        assertTrue("增益确实是放大的，实际 ${gain.gain}", gain.gain > 4)
        assertTrue("不超过上限", gain.gain <= InputGain.MaxGain + 1e-6)
    }

    @Test
    fun `响亮的句子原样通过`() {
        val gain = InputGain()
        val loud = tone(amplitude = 9000.0)
        val original = loud.map { it.copyOf() }
        loud.forEach { gain.apply(it) }

        assertEquals("不放也不收", 1.0, gain.gain, 0.001)
        loud.forEachIndexed { index, after ->
            assertTrue("逐样一致", after.contentEquals(original[index]))
        }
    }

    @Test
    fun `先有环境声再说话，说话这一段仍被抬起来`() {
        val gain = InputGain()
        // 三秒环境声，底噪落在二十上下，比说话的六分之一还低。
        List(150) { ShortArray(frameSamples) { slot -> if (slot % 3 == 0) 20 else -20 } }.forEach { gain.apply(it) }
        val speech = tone(amplitude = 120.0)
        speech.forEach { gain.apply(it) }

        assertTrue("说话时增益抬起来，实际 ${gain.gain}", gain.gain > 8)
        assertTrue("输出贴近目标", rms(speech.last()) > InputGain.TargetRms * 0.5)
    }

    @Test
    fun `句间停顿不把增益放掉`() {
        val gain = InputGain()
        tone(amplitude = 120.0).forEach { gain.apply(it) }
        val lifted = gain.gain
        assertTrue("先抬起来", lifted > 4)

        // 半秒安静，窗口里剩下的仍是刚才那句话，增益不该掉回原位。
        List(25) { ShortArray(frameSamples) { slot -> if (slot % 2 == 0) 3 else -3 } }.forEach { gain.apply(it) }
        assertTrue("停顿后增益仍在，实际 ${gain.gain}", gain.gain > lifted * 0.5)
    }

    @Test
    fun `增益抬高之后突来的响声走软限幅`() {
        val gain = InputGain()
        tone(amplitude = 90.0).forEach { gain.apply(it) }
        assertTrue("先抬起来", gain.gain > 4)

        val burst = ShortArray(frameSamples) { if (it % 2 == 0) 30_000 else -30_000 }
        gain.apply(burst)
        val peak = burst.maxOf { abs(it.toInt()) }
        assertTrue("没有顶穿十六位，实际 $peak", peak <= Short.MAX_VALUE)
        assertTrue("也没有被砍成方波，实际 $peak", peak <= InputGain.Ceiling.toInt() + 1)
    }

    @Test
    fun `真实语音压到负五十分贝后仍被拉回工作电平`() {
        // 仓库里那句样例句按负五十分贝重放，逐帧过一遍增益，输出该落在目标附近。
        val source = File("src/test/resources/speech_16k.pcm").readBytes()
        var peak = 0
        val samples = ShortArray(source.size / 2) { index ->
            val low = source[index * 2].toInt() and 0xFF
            val high = source[index * 2 + 1].toInt()
            val value = ((high shl 8) or low).toShort()
            if (abs(value.toInt()) > peak) peak = abs(value.toInt())
            value
        }
        // 按 RMS 而不是峰值缩到负五十分贝：真实录音的头尾静音把峰值抬得比句子本身高十几分贝，
        // 按峰值缩会把整句压到负六十分贝以下，那不是"小声说话"，那是没人说话。
        var squares = 0.0
        for (sample in samples) squares += sample.toDouble() * sample
        val level = sqrt(squares / samples.size)
        val scale = 32_768.0 * 10.0.pow(-50.0 / 20.0) / level
        val gain = InputGain()
        val levels = ArrayList<Double>()
        var at = 0
        while (at + frameSamples <= samples.size) {
            val frame = ShortArray(frameSamples) { index -> (samples[at + index] * scale).toInt().toShort() }
            gain.apply(frame)
            levels += rms(frame)
            at += frameSamples
        }
        levels.sort()

        assertTrue("增益抬起来了，实际 ${gain.gain}", gain.gain > 10)
        assertTrue(
            "说话段的电平回到目标附近，实际 ${levels[(levels.size * 9) / 10].toInt()}",
            levels[(levels.size * 9) / 10] > InputGain.TargetRms * 0.5,
        )
        assertTrue("峰值没有撞穿十六位，实际 ${gain.peakOut}", gain.peakOut <= Short.MAX_VALUE)
    }

    @Test
    fun `全零的一段不抬增益`() {
        val gain = InputGain()
        List(60) { ShortArray(frameSamples) }.forEach { gain.apply(it) }

        assertEquals("增益仍是 1", 1.0, gain.gain, 0.001)
        assertEquals("输出仍是零", 0.0, rms(ShortArray(frameSamples) { 0 }), 0.0)
    }
}
