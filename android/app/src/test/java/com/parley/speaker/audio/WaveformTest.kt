package com.parley.speaker.audio

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 波形打包是跨设备的约定：五位一根、字节高位在前，反解回来必须与压进去的一模一样，
 * 否则同一段录音在两端画出来的柱子位置都不一样。
 */
class WaveformTest {

    @Test
    fun `打包固定六十三字节`() {
        assertEquals(63, Waveform.pack(IntArray(100) { 31 }).size)
        assertEquals(63, Waveform.pack(IntArray(0)).size)
    }

    @Test
    fun `第一位从字节最高位起`() {
        val packed = Waveform.pack(intArrayOf(1))
        assertEquals(0x08.toByte(), packed[0])
        assertEquals(0, packed[1].toInt())
    }

    @Test
    fun `第二根跨字节接在后面`() {
        val packed = Waveform.pack(intArrayOf(0, 31))
        assertEquals(0x07.toByte(), packed[0])
        assertEquals(0xC0.toByte(), packed[1])
    }

    @Test
    fun `一百根满值只剩末字节低四位是零`() {
        val packed = Waveform.pack(IntArray(100) { 31 })
        for (index in 0 until 62) {
            assertEquals("第 $index 字节", 0xFF, packed[index].toInt() and 0xFF)
        }
        assertEquals(0xF0, packed[62].toInt() and 0xFF)
    }

    @Test
    fun `打包再反解回到原值`() {
        val levels = IntArray(100) { index -> (index * 7 + 3) % 32 }
        assertArrayEquals(levels, Waveform.unpack(Waveform.pack(levels)))
    }

    @Test
    fun `超出值夹进五位范围`() {
        val packed = Waveform.pack(intArrayOf(32, -5, 40, 17))
        assertArrayEquals(intArrayOf(31, 0, 31, 17), Waveform.unpack(packed).copyOf(4))
    }

    @Test
    fun `多出的柱子丢掉不够的补零`() {
        val long = IntArray(150) { 31 }
        val unpacked = Waveform.unpack(Waveform.pack(long))
        assertTrue(unpacked.all { it == 31 })

        val short = IntArray(30) { 31 }
        val tail = Waveform.unpack(Waveform.pack(short))
        assertTrue("前三十根留着", tail.copyOf(30).all { it == 31 })
        assertTrue("后面补零", tail.copyOfRange(30, 100).all { it == 0 })
    }

    @Test
    fun `字节短了按零反解`() {
        val unpacked = Waveform.unpack(ByteArray(2))
        assertEquals(100, unpacked.size)
        assertTrue(unpacked.all { it == 0 })
    }

    @Test
    fun `峰值按最大值铺满整条`() {
        val rms = FloatArray(500) { index -> if (index == 250) 0.8f else 0.2f }
        val peaks = Waveform.peaks(rms)
        assertEquals(100, peaks.size)
        assertEquals(31, peaks.max())
        assertEquals("最大值那一段满格", 31, peaks[50])
        assertEquals("其余按比例", 8, peaks[0])
    }

    @Test
    fun `全零给全零`() {
        val peaks = Waveform.peaks(FloatArray(400))
        assertEquals(100, peaks.size)
        assertTrue(peaks.all { it == 0 })
    }

    @Test
    fun `空序列给全零`() {
        assertTrue(Waveform.peaks(FloatArray(0)).all { it == 0 })
        assertEquals(0, Waveform.peaks(FloatArray(10), 0).size)
    }

    @Test
    fun `样本少于柱子时就近取样`() {
        val peaks = Waveform.peaks(floatArrayOf(0.1f, 0.5f, 0.25f), 6)
        assertArrayEquals(intArrayOf(6, 6, 31, 31, 16, 16), peaks)
    }

    @Test
    fun `单样本铺满整条`() {
        val peaks = Waveform.peaks(floatArrayOf(0.3f), 100)
        assertTrue(peaks.all { it == 31 })
    }

    @Test
    fun `峰值取绝对值`() {
        assertArrayEquals(intArrayOf(31, 31), Waveform.peaks(floatArrayOf(-0.5f, 0.5f), 2))
    }
}
