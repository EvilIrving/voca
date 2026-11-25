package com.voca.speaker.audio

import java.io.ByteArrayOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * OGG 是外部格式，单测不读实现只读字节：自己按 RFC 3533 解页头、自己重算 CRC，
 * 与写入端对上才算封得对。头页字段对齐 RFC 7845，末页必须带 EOS 标志。
 */
class OggOpusWriterTest {

    private class Page(
        val headerType: Int,
        val granule: Long,
        val serial: Int,
        val sequence: Int,
        val crc: Int,
        val lacing: IntArray,
        val body: ByteArray,
        val bytes: ByteArray,
    )

    @Test
    fun `头两页是 OpusHead 与 OpusTags`() {
        val pages = pagesOf { writer -> writer.writeHeaders() }
        assertEquals("头页加标签页", 2, pages.size)

        val head = pages[0]
        assertEquals("起始页", 0x02, head.headerType)
        assertEquals(0L, head.granule)
        assertEquals(0, head.sequence)
        assertEquals("头信息一个段", 1, head.lacing.size)
        assertEquals(19, head.lacing[0])
        assertEquals(19, head.body.size)
        assertEquals("OpusHead", String(head.body, 0, 8, Charsets.US_ASCII))
        assertEquals("版本", 1, head.body[8].toInt())
        assertEquals("声道", 1, head.body[9].toInt())
        assertEquals("pre-skip", 0, readShort(head.body, 10))
        assertEquals("采样率", 48_000, readInt(head.body, 12))
        assertEquals("输出增益", 0, readShort(head.body, 16))
        assertEquals("声道映射族", 0, head.body[18].toInt())

        val tags = pages[1]
        assertEquals("中间页", 0x00, tags.headerType)
        assertEquals("OpusTags", String(tags.body, 0, 8, Charsets.US_ASCII))
        val vendorLength = readInt(tags.body, 8)
        assertEquals("voca", String(tags.body, 12, vendorLength, Charsets.UTF_8))
        assertEquals("没有注释", 0, readInt(tags.body, 12 + vendorLength))
    }

    @Test
    fun `页序号从零起逐个自增`() {
        val pages = pagesOf { writer ->
            writer.writeHeaders()
            writer.writePacket(ByteArray(80) { 1 }, 960)
            writer.writePacket(ByteArray(80) { 2 }, 1920)
            writer.finish()
        }
        assertEquals(listOf(0, 1, 2, 3, 4), pages.map { it.sequence })
        assertEquals("一份文件固定一个序列号", 1, pages.map { it.serial }.distinct().size)
    }

    @Test
    fun `每页 CRC 与重算一致`() {
        val pages = pagesOf { writer ->
            writer.writeHeaders()
            writer.writePacket(ByteArray(300) { 7 }, 960)
            writer.writePacket(ByteArray(1) { 9 }, 1920)
            writer.finish()
        }
        for (page in pages) {
            val zeroed = page.bytes.copyOf()
            zeroed[22] = 0
            zeroed[23] = 0
            zeroed[24] = 0
            zeroed[25] = 0
            assertEquals("第 ${page.sequence} 页", crc(zeroed), page.crc)
        }
    }

    @Test
    fun `大于二百五十五字节的包 lacing 分两段`() {
        val pages = pagesOf { writer ->
            writer.writeHeaders()
            writer.writePacket(ByteArray(400) { 3 }, 960)
        }
        val packet = pages.last()
        assertEquals(2, packet.lacing.size)
        assertEquals(255, packet.lacing[0])
        assertEquals(145, packet.lacing[1])
        assertEquals(400, packet.body.size)
        assertEquals(960L, packet.granule)
    }

    @Test
    fun `正好整倍数时补一个零段收尾`() {
        val pages = pagesOf { writer ->
            writer.writeHeaders()
            writer.writePacket(ByteArray(255) { 4 }, 960)
        }
        val packet = pages.last()
        assertEquals(2, packet.lacing.size)
        assertEquals(255, packet.lacing[0])
        assertEquals("末段为零表示包结束", 0, packet.lacing[1])
        assertEquals(255, packet.body.size)
    }

    @Test
    fun `小包只占一个段`() {
        val pages = pagesOf { writer ->
            writer.writeHeaders()
            writer.writePacket(ByteArray(80) { 5 }, 960)
        }
        val packet = pages.last()
        assertEquals(1, packet.lacing.size)
        assertEquals(80, packet.lacing[0])
        assertEquals(0x00, packet.headerType)
    }

    @Test
    fun `末页是空的 EOS 页`() {
        val pages = pagesOf { writer ->
            writer.writeHeaders()
            writer.writePacket(ByteArray(80) { 6 }, 960)
            writer.writePacket(ByteArray(80) { 7 }, 1920)
            writer.finish()
        }
        val last = pages.last()
        assertEquals("EOS 标志", 0x04, last.headerType)
        assertEquals("granule 记到最后一只包", 1920L, last.granule)
        assertEquals("空页没有段", 0, last.lacing.size)
        assertEquals(0, last.body.size)
    }

    @Test
    fun `granule 按小端写进页头`() {
        val granule = 0x0102030405L
        val pages = pagesOf { writer ->
            writer.writeHeaders()
            writer.writePacket(ByteArray(80), granule)
        }
        assertEquals(granule, pages.last().granule)
    }

    @Test
    fun `全量解析刚好到文件末尾`() {
        val out = ByteArrayOutputStream()
        val writer = OggOpusWriter(out)
        writer.writeHeaders()
        writer.writePacket(ByteArray(600) { 8 }, 960)
        writer.finish()
        val bytes = out.toByteArray()
        val pages = parse(bytes)
        assertEquals("页首尾相接", bytes.size, pages.sumOf { it.bytes.size })
        assertTrue("解析得到四页", pages.size == 4)
    }

    private fun pagesOf(block: (OggOpusWriter) -> Unit): List<Page> {
        val out = ByteArrayOutputStream()
        val writer = OggOpusWriter(out)
        block(writer)
        return parse(out.toByteArray())
    }

    private fun parse(bytes: ByteArray): List<Page> {
        val pages = mutableListOf<Page>()
        var offset = 0
        while (offset < bytes.size) {
            assertEquals('O'.code.toByte(), bytes[offset])
            assertEquals('g'.code.toByte(), bytes[offset + 1])
            assertEquals('g'.code.toByte(), bytes[offset + 2])
            assertEquals('S'.code.toByte(), bytes[offset + 3])
            assertEquals("版本", 0, bytes[offset + 4].toInt())
            val headerType = bytes[offset + 5].toInt() and 0xFF
            val granule = readLong(bytes, offset + 6)
            val serial = readInt(bytes, offset + 14)
            val sequence = readInt(bytes, offset + 18)
            val crc = readInt(bytes, offset + 22)
            val segments = bytes[offset + 26].toInt() and 0xFF
            val lacing = IntArray(segments) { index -> bytes[offset + 27 + index].toInt() and 0xFF }
            val bodySize = lacing.sum()
            val bodyStart = offset + 27 + segments
            val body = bytes.copyOfRange(bodyStart, bodyStart + bodySize)
            val end = bodyStart + bodySize
            pages += Page(
                headerType = headerType,
                granule = granule,
                serial = serial,
                sequence = sequence,
                crc = crc,
                lacing = lacing,
                body = body,
                bytes = bytes.copyOfRange(offset, end),
            )
            offset = end
        }
        return pages
    }

    /** 逐位算的 0x04C11DB7，和写入端的查表实现互为校验。 */
    private fun crc(bytes: ByteArray): Int {
        var value = 0
        for (byte in bytes) {
            value = value xor ((byte.toInt() and 0xFF) shl 24)
            repeat(8) {
                value = if (value and Int.MIN_VALUE != 0) (value shl 1) xor 0x04C11DB7 else value shl 1
            }
        }
        return value
    }

    private fun readShort(bytes: ByteArray, offset: Int): Int =
        (bytes[offset].toInt() and 0xFF) or ((bytes[offset + 1].toInt() and 0xFF) shl 8)

    private fun readInt(bytes: ByteArray, offset: Int): Int {
        var value = 0
        for (index in 0 until 4) {
            value = value or ((bytes[offset + index].toInt() and 0xFF) shl (index * 8))
        }
        return value
    }

    private fun readLong(bytes: ByteArray, offset: Int): Long {
        var value = 0L
        for (index in 0 until 8) {
            value = value or ((bytes[offset + index].toLong() and 0xFF) shl (index * 8))
        }
        return value
    }
}
