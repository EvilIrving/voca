package com.parley.speaker.audio

import java.io.OutputStream

/**
 * 把 Opus 包封成一份 OGG 文件，供暂停试听与发送后的回放共用。
 *
 * 页头按 RFC 3533：`OggS`、版本 0、头类型（0x02 起始页、0x00 中间页、0x04 末页）、八字节小端 granule position、
 * 四字节序列号、四字节页序号、四字节 CRC、一个段数与段表。CRC 用 0x04C11DB7 直接算，初值 0，
 * 输入输出都不反射、结尾不取反，计算前把 CRC 字段本身置零，覆盖页头后四字节加段表加正文整页。
 *
 * 编码器头按 RFC 7845：OpusHead 带版本 1、声道数、pre-skip、原始采样率、输出增益与声道映射族，
 * 放在 BOS 页；OpusTags 带厂商串与零条注释。声道映射族 0 只覆盖单声道与立体声，本项目的采集固定单声道。
 *
 * 一个包在段表里按 255 字节切分，末值小于 255 表示包到此为止，包长正好是 255 的整数倍时补一个 0 段；
 * 一个页最多放 255 个段，超出的部分续到下一页并带上 0x01 连续标志，续页的 granule 记 -1。
 */
class OggOpusWriter(
    private val out: OutputStream,
    private val channels: Int = 1,
    private val inputRate: Int = 48_000,
    private val preSkip: Int = 0,
) {

    private var sequence = 0
    private var lastGranule = 0L
    private var headersWritten = false

    fun writeHeaders() {
        if (headersWritten) return
        headersWritten = true

        val head = ByteArray(19)
        MAGIC_HEAD.copyInto(head, 0)
        head[8] = 1
        head[9] = channels.toByte()
        putShort(head, 10, preSkip)
        putInt(head, 12, inputRate)
        putShort(head, 16, 0)
        head[18] = 0
        writePage(FLAG_BOS, 0L, head, byteArrayOf(head.size.toByte()))

        val vendor = VENDOR.toByteArray(Charsets.UTF_8)
        val tags = ByteArray(8 + 4 + vendor.size + 4)
        MAGIC_TAGS.copyInto(tags, 0)
        putInt(tags, 8, vendor.size)
        vendor.copyInto(tags, 12)
        putInt(tags, 12 + vendor.size, 0)
        writePage(FLAG_NONE, 0L, tags, byteArrayOf(tags.size.toByte()))
    }

    fun writePacket(packet: ByteArray, granulePosition: Long) {
        lastGranule = granulePosition
        // 组成这个包的段数：每 255 字节一段，另加末尾那段（整倍数时就是那个 0 段）
        val segments = packet.size / 255 + 1
        var segmentIndex = 0
        var offset = 0
        var continued = false
        while (segmentIndex < segments) {
            val take = minOf(segments - segmentIndex, MAX_SEGMENTS)
            val lacing = ByteArray(take)
            var bodySize = 0
            for (slot in 0 until take) {
                val value = minOf(255, packet.size - offset)
                lacing[slot] = value.toByte()
                offset += value
                bodySize += value
            }
            val body = packet.copyOfRange(offset - bodySize, offset)
            val endsPacket = segmentIndex + take >= segments
            writePage(
                headerType = if (continued) FLAG_CONTINUED else FLAG_NONE,
                granule = if (endsPacket) granulePosition else NO_GRANULE,
                body = body,
                lacing = lacing,
            )
            segmentIndex += take
            continued = !endsPacket
        }
    }

    /** 末页是空的 EOS 页，granule 记到最后一个包为止的样本数，播放器据此判断流结束。 */
    fun finish() {
        writePage(FLAG_EOS, lastGranule, EMPTY, EMPTY_LACING)
        out.flush()
    }

    private fun writePage(headerType: Int, granule: Long, body: ByteArray, lacing: ByteArray) {
        val header = ByteArray(PAGE_HEADER_BYTES + lacing.size)
        MAGIC_PAGE.copyInto(header, 0)
        header[4] = 0
        header[5] = headerType.toByte()
        putLong(header, 6, granule)
        putInt(header, 14, SERIAL)
        putInt(header, 18, sequence)
        putInt(header, 22, 0)
        header[26] = lacing.size.toByte()
        lacing.copyInto(header, PAGE_HEADER_BYTES)

        val page = ByteArray(header.size + body.size)
        header.copyInto(page, 0)
        body.copyInto(page, header.size)
        putInt(page, 22, crc(page))
        out.write(page)
        sequence++
    }

    private fun crc(bytes: ByteArray): Int {
        var value = 0
        for (byte in bytes) {
            value = (value shl 8) xor TABLE[((value ushr 24) xor (byte.toInt() and 0xFF)) and 0xFF]
        }
        return value
    }

    private companion object {
        val MAGIC_PAGE = byteArrayOf('O'.code.toByte(), 'g'.code.toByte(), 'g'.code.toByte(), 'S'.code.toByte())
        val MAGIC_HEAD = "OpusHead".toByteArray(Charsets.US_ASCII)
        val MAGIC_TAGS = "OpusTags".toByteArray(Charsets.US_ASCII)
        const val VENDOR = "parley"

        const val PAGE_HEADER_BYTES = 27
        const val MAX_SEGMENTS = 255
        const val FLAG_NONE = 0x00
        const val FLAG_CONTINUED = 0x01
        const val FLAG_BOS = 0x02
        const val FLAG_EOS = 0x04
        const val NO_GRANULE = -1L

        /** 序列号在一份文件里保持不变，播放器靠它与页序号排页。 */
        const val SERIAL = 0x7061726C

        val EMPTY = ByteArray(0)
        val EMPTY_LACING = ByteArray(0)

        /** 0x04C11DB7 的查表，表项是寄存器左移八位后的余数，和 OGG 的约定一致（不反射、不取反）。 */
        val TABLE = IntArray(256) { index ->
            var register = index shl 24
            repeat(8) {
                register = if (register and Int.MIN_VALUE != 0) {
                    (register shl 1) xor 0x04C11DB7
                } else {
                    register shl 1
                }
            }
            register
        }

        fun putShort(target: ByteArray, offset: Int, value: Int) {
            target[offset] = (value and 0xFF).toByte()
            target[offset + 1] = ((value ushr 8) and 0xFF).toByte()
        }

        fun putInt(target: ByteArray, offset: Int, value: Int) {
            for (index in 0 until 4) {
                target[offset + index] = ((value ushr (index * 8)) and 0xFF).toByte()
            }
        }

        fun putLong(target: ByteArray, offset: Int, value: Long) {
            for (index in 0 until 8) {
                target[offset + index] = ((value ushr (index * 8)) and 0xFF).toByte()
            }
        }
    }
}
