package com.parley.speaker.data.realtime

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 重采样与编解码是三家适配器共用的唯一一层纯逻辑，长度对不上就是上行帧被切歪、下行放出来变速。
 */
class PcmTest {

    @Test
    fun `二十毫秒一帧从十六千赫兹换到二十四千赫兹是四百八十个采样`() {
        val frame = ShortArray(320) { (it % 200).toShort() }

        val out = Pcm.resampleToDownlink(frame)

        assertEquals("320 乘 24000 除 16000 应当正好是 480", 480, out.size)
        assertEquals("字节数跟着采样数翻倍", 960, Pcm.uplinkFrameToDownlink(frame).size)
    }

    @Test
    fun `降采样的长度同样按比例取整`() {
        val frame = ShortArray(480) { (it % 200).toShort() }

        assertEquals(320, Pcm.resample(frame, Pcm.DOWNLINK_RATE, Pcm.UPLINK_RATE).size)
    }

    @Test
    fun `采样率相同就原样返回`() {
        val frame = shortArrayOf(1, -2, 3, -4)

        val out = Pcm.resample(frame, Pcm.UPLINK_RATE, Pcm.UPLINK_RATE)

        assertArrayEquals(frame, out)
        assertEquals(0, Pcm.resample(ShortArray(0), Pcm.UPLINK_RATE, Pcm.DOWNLINK_RATE).size)
    }

    @Test
    fun `直流信号重采样之后还是同一条直流`() {
        val frame = ShortArray(320) { 1_000 }

        val out = Pcm.resampleToDownlink(frame)

        assertTrue("不该出现插值抖动，实际 ${out.toSet()}", out.all { it == 1_000.toShort() })
    }

    @Test
    fun `线性插值落在两个采样之间`() {
        val frame = shortArrayOf(0, 300, 600, 900)

        val out = Pcm.resample(frame, 2, 3)

        assertEquals(6, out.size)
        assertEquals(0, out[0].toInt())
        assertEquals(200, out[1].toInt())
        assertEquals(400, out[2].toInt())
        assertEquals(600, out[3].toInt())
        assertEquals(800, out[4].toInt())
        assertEquals(900, out[5].toInt())
    }

    @Test
    fun `短整型与字节按小端互转`() {
        val frame = shortArrayOf(0x0102, -1, 0, Short.MAX_VALUE, Short.MIN_VALUE)

        val bytes = Pcm.shortsToBytes(frame)

        assertEquals(frame.size * 2, bytes.size)
        assertEquals("小端低字节在前", 0x02.toByte(), bytes[0])
        assertEquals(0x01.toByte(), bytes[1])
        assertArrayEquals(frame, Pcm.bytesToShorts(bytes))
    }

    @Test
    fun `Base64 往返不丢字节`() {
        val bytes = ByteArray(256) { it.toByte() }

        val text = Pcm.encodeBase64(bytes)

        assertArrayEquals(bytes, Pcm.decodeBase64(text))
        assertEquals("", Pcm.encodeBase64(ByteArray(0)))
    }

    @Test
    fun `服务端折过行宽的 Base64 也能解`() {
        val bytes = ByteArray(120) { (it * 7).toByte() }
        val wrapped = Pcm.encodeBase64(bytes).chunked(24).joinToString("\r\n")

        assertArrayEquals(bytes, Pcm.decodeBase64(wrapped))
    }
}
