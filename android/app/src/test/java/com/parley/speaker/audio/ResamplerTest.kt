package com.parley.speaker.audio

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin
import kotlin.math.sqrt
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 重采样决定上行音频是否还对得上人声：一千赫兹这类语音主频要原样过去，
 * 八千赫兹以上要在降到十六千赫兹之前就压下去，否则折叠回来会变成另一种音色。
 * 跨调用留历史这件事也在这里钉住，块边界不能出现断点。
 */
class ResamplerTest {

    private val rate = 48_000

    @Test
    fun `四十八千赫兹降到十六千赫兹`() {
        val input = sine(1_000.0, 12_000, rate)
        val output = feed(Resampler(), input)
        assertTrue("每三点取一，实测 ${output.size} 点", output.size in (input.size / 3 - 12)..(input.size / 3 + 12))
    }

    @Test
    fun `直流增益约为一`() {
        val output = feed(Resampler(), ShortArray(4_800) { 16_384 })
        for (index in 200 until output.size) {
            assertTrue("第 $index 点 ${output[index]}", abs(output[index].toInt() - 16_384) <= 2)
        }
    }

    @Test
    fun `一千赫兹幅度衰减小于百分之三`() {
        val input = sine(1_000.0, 12_000, rate)
        val output = feed(Resampler(), input)
        val expected = 12_000.0 / sqrt(2.0)
        val actual = rms(output, from = 500, to = output.size - 500)
        assertTrue("实测 $actual，期望 $expected", abs(actual - expected) / expected < 0.03)
    }

    @Test
    fun `八千赫兹明显衰减`() {
        val input = sine(8_000.0, 12_000, rate)
        val output = feed(Resampler(), input)
        val ratio = rms(output, from = 500, to = output.size - 500) / (12_000.0 / sqrt(2.0))
        assertTrue("实测比值 $ratio", ratio < 0.6)
    }

    @Test
    fun `一万赫兹压到百分之五以下`() {
        val input = sine(10_000.0, 12_000, rate)
        val output = feed(Resampler(), input)
        val ratio = rms(output, from = 500, to = output.size - 500) / (12_000.0 / sqrt(2.0))
        assertTrue("实测比值 $ratio", ratio < 0.05)
    }

    @Test
    fun `奈奎斯特以上的音调不会折叠回来`() {
        val input = sine(20_000.0, 12_000, rate)
        val output = feed(Resampler(), input)
        val ratio = rms(output, from = 500, to = output.size - 500) / (12_000.0 / sqrt(2.0))
        assertTrue("实测比值 $ratio", ratio < 0.01)
    }

    @Test
    fun `分块喂与一次喂结果一致`() {
        val input = sine(440.0, 9_000, 4_800)
        val whole = Resampler().process(input)
        val chunked = feed(Resampler(), input, block = 960)
        assertEquals(whole.size, chunked.size)
        assertArrayEquals(whole, chunked)
    }

    @Test
    fun `凑不满一个窗口先不出点`() {
        val resampler = Resampler()
        assertEquals(0, resampler.process(ShortArray(10)).size)
        assertTrue("补够窗口就有输出", resampler.process(ShortArray(100)).isNotEmpty())
    }

    @Test
    fun `reset 之后当作新流`() {
        val input = sine(1_000.0, 8_000, 4_800)
        val fresh = feed(Resampler(), input)

        val reused = Resampler()
        reused.process(ShortArray(960) { 12_345 })
        reused.reset()
        assertArrayEquals(fresh, feed(reused, input))
    }

    private fun feed(resampler: Resampler, input: ShortArray, block: Int = 960): ShortArray {
        val chunks = ArrayList<ShortArray>()
        var total = 0
        var offset = 0
        while (offset < input.size) {
            val end = minOf(offset + block, input.size)
            val chunk = resampler.process(input.copyOfRange(offset, end))
            chunks += chunk
            total += chunk.size
            offset = end
        }
        val output = ShortArray(total)
        var cursor = 0
        for (chunk in chunks) {
            chunk.copyInto(output, cursor)
            cursor += chunk.size
        }
        return output
    }

    private fun rms(samples: ShortArray, from: Int, to: Int): Double {
        var sum = 0.0
        for (index in from until to) {
            val value = samples[index].toDouble()
            sum += value * value
        }
        return sqrt(sum / (to - from).coerceAtLeast(1))
    }

    private fun sine(frequency: Double, amplitude: Int, samples: Int): ShortArray =
        ShortArray(samples) { index ->
            (amplitude * sin(2.0 * PI * frequency * index / rate)).toInt().toShort()
        }
}
