package com.parley.speaker.data.realtime.doubao

import java.io.ByteArrayOutputStream
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 帧编解码的验收：头四个字节要按位对上版本、消息类型、序列化与 gzip，
 * 事件号、会话号、载荷长度三段不能串位，裸音频帧与错误帧各有各的走法。
 */
class DoubaoFrameTest {

    @Test
    fun `请求帧的头标出版本、消息类型、JSON 与 gzip`() {
        val frame = DoubaoFrameCodec.request(
            messageType = DoubaoProto.MSG_FULL_CLIENT,
            event = DoubaoEvents.START_CONNECTION,
            sessionId = "",
            payload = """{"a":1}""".toByteArray(),
        )

        assertEquals("版本 1 加一个四字节头就是 0x11", 0x11, frame[0].toInt() and 0xFF)
        assertEquals("完整客户端请求，flags 全零", 0x10, frame[1].toInt() and 0xFF)
        assertEquals("JSON 序列化加 gzip 压缩", 0x11, frame[2].toInt() and 0xFF)
        assertEquals("保留字节", 0x00, frame[3].toInt() and 0xFF)
    }

    @Test
    fun `带会话号的请求帧能解回事件、会话号与载荷`() {
        val payload = """{"dialog":{"input_mod":"push_to_talk"}}""".toByteArray()

        val frame = DoubaoFrameCodec.decode(
            DoubaoFrameCodec.request(DoubaoProto.MSG_FULL_CLIENT, DoubaoEvents.START_SESSION, "s-1", payload),
        )

        assertEquals(DoubaoProto.MSG_FULL_CLIENT, frame.messageType)
        assertEquals(DoubaoEvents.START_SESSION, frame.event)
        assertEquals("s-1", frame.sessionId)
        assertEquals(DoubaoProto.COMPRESS_GZIP, frame.compression)
        assertArrayEquals(payload, frame.payload)
    }

    @Test
    fun `连接级事件不带会话号`() {
        val frame = DoubaoFrameCodec.decode(
            DoubaoFrameCodec.request(DoubaoProto.MSG_FULL_CLIENT, DoubaoEvents.START_CONNECTION, "", "{}".toByteArray()),
        )

        assertEquals(DoubaoEvents.START_CONNECTION, frame.event)
        assertEquals("", frame.sessionId)
        assertEquals("{}", frame.payload.toString(Charsets.UTF_8))
    }

    @Test
    fun `关掉 gzip 时头里写零而不压载荷`() {
        val payload = """{"audio_end":true}""".toByteArray()

        val raw = DoubaoFrameCodec.request(
            messageType = DoubaoProto.MSG_FULL_CLIENT,
            event = DoubaoEvents.TASK_REQUEST,
            sessionId = "s-1",
            payload = payload,
            gzip = false,
        )

        assertEquals(0x10, raw[2].toInt() and 0xFF)
        val frame = DoubaoFrameCodec.decode(raw)
        assertEquals(DoubaoProto.COMPRESS_NONE, frame.compression)
        assertArrayEquals(payload, frame.payload)
    }

    @Test
    fun `上行音频帧是裸 PCM 不套 JSON`() {
        val pcm = ByteArray(640) { (it % 128).toByte() }

        val raw = DoubaoFrameCodec.audio(DoubaoEvents.TASK_REQUEST, "s-1", pcm)

        assertEquals(0x20, raw[1].toInt() and 0xFF)
        assertEquals("裸数据不序列化也不压", 0x00, raw[2].toInt() and 0xFF)
        val frame = DoubaoFrameCodec.decode(raw)
        assertEquals(DoubaoProto.MSG_AUDIO_CLIENT, frame.messageType)
        assertEquals("s-1", frame.sessionId)
        assertArrayEquals(pcm, frame.payload)
    }

    @Test
    fun `带序列号的下行音频帧把序列号读在事件号前面`() {
        val pcm = byteArrayOf(1, 2, 3, 4, 5, 6)

        val frame = DoubaoFrameCodec.decode(
            assemble(
                messageType = DoubaoProto.MSG_AUDIO_SERVER,
                flags = DoubaoProto.FLAG_POSITIVE,
                serialization = DoubaoProto.SERIAL_RAW,
                compression = DoubaoProto.COMPRESS_NONE,
                blocks = listOf(
                    int32(7),
                    int32(DoubaoEvents.TTS_RESPONSE),
                    block("s-9".toByteArray()),
                    int32(pcm.size),
                    pcm,
                ),
            ),
        )

        assertEquals(7, frame.sequence)
        assertEquals(DoubaoEvents.TTS_RESPONSE, frame.event)
        assertEquals("s-9", frame.sessionId)
        assertArrayEquals(pcm, frame.payload)
    }

    @Test
    fun `最后一包没有序列号`() {
        val frame = DoubaoFrameCodec.decode(
            assemble(
                messageType = DoubaoProto.MSG_AUDIO_SERVER,
                flags = DoubaoProto.FLAG_LAST,
                serialization = DoubaoProto.SERIAL_RAW,
                compression = DoubaoProto.COMPRESS_NONE,
                blocks = listOf(int32(DoubaoEvents.TTS_RESPONSE), block("s-9".toByteArray()), int32(0)),
            ),
        )

        assertNull(frame.sequence)
        assertEquals(DoubaoProto.FLAG_LAST, frame.flags)
        assertEquals(0, frame.payload.size)
    }

    @Test
    fun `错误帧先给错误码再给压过的载荷`() {
        val body = DoubaoFrameCodec.gzip("""{"error":"invalid app key"}""".toByteArray())

        val frame = DoubaoFrameCodec.decode(
            assemble(
                messageType = DoubaoProto.MSG_ERROR,
                flags = DoubaoProto.FLAG_NONE,
                serialization = DoubaoProto.SERIAL_JSON,
                compression = DoubaoProto.COMPRESS_GZIP,
                blocks = listOf(int32(45_000_001), int32(body.size), body),
            ),
        )

        assertEquals(45_000_001, frame.errorCode)
        assertEquals("""{"error":"invalid app key"}""", frame.payload.toString(Charsets.UTF_8))
    }

    @Test
    fun `gzip 往返不丢字节`() {
        val payload = ByteArray(4_096) { (it * 13 % 251).toByte() }

        assertArrayEquals(payload, DoubaoFrameCodec.gunzip(DoubaoFrameCodec.gzip(payload)))
        assertTrue("压过的 JSON 应当比明文短", DoubaoFrameCodec.gzip(ByteArray(1_024)).size < 1_024 + 32)
    }

    @Test
    fun `长度对不上的帧直接拒掉`() {
        val cases = listOf(
            ByteArray(4),
            byteArrayOf(0x11, 0x10, 0x00, 0x00, 0x00, 0x00, 0x00, 0x01, 0x02),
            assemble(DoubaoProto.MSG_FULL_SERVER, 0, DoubaoProto.SERIAL_JSON, DoubaoProto.COMPRESS_NONE, listOf(int32(1), block("s-1".toByteArray()), int32(9))),
        )

        cases.forEach { bytes ->
            val failed = runCatching { DoubaoFrameCodec.decode(bytes) }.isFailure
            assertTrue("长度矛盾应当抛出来，实际吃下去了：${bytes.size} 字节", failed)
        }
    }

    private fun block(bytes: ByteArray): ByteArray = int32(bytes.size) + bytes

    private fun int32(value: Int): ByteArray = byteArrayOf(
        (value ushr 24).toByte(),
        (value ushr 16).toByte(),
        (value ushr 8).toByte(),
        value.toByte(),
    )

    private fun assemble(
        messageType: Int,
        flags: Int,
        serialization: Int,
        compression: Int,
        blocks: List<ByteArray>,
    ): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(
            byteArrayOf(
                ((DoubaoProto.VERSION shl 4) or DoubaoProto.HEADER_WORDS).toByte(),
                ((messageType shl 4) or flags).toByte(),
                ((serialization shl 4) or compression).toByte(),
                0x00,
            ),
        )
        blocks.forEach { out.write(it) }
        return out.toByteArray()
    }
}
