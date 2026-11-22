package com.parley.speaker.data.realtime.doubao

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

/**
 * 豆包实时语音的二进制帧。四字节头之后依次是事件号、会话号与载荷长度：
 *
 * ```
 * byte0  version(高四位) | headerWords(低四位，单位是四字节)
 * byte1  messageType(高四位) | flags(低四位)
 * byte2  serialization(高四位) | compression(低四位)
 * byte3  保留
 * 之后   [错误码 4B，只有错误响应有] [序列号 4B，只有带序列号的音频响应有]
 *        事件号 4B  [会话号长度 4B + 会话号]  载荷长度 4B  载荷
 * ```
 *
 * 版本、消息类型、序列化与 gzip 标志都压在头里，所以同一条 socket 上能同时跑 JSON 事件与裸 PCM。
 * 连接级事件（StartConnection 与它的回应）不带会话号，其余都带，解码据此判断那一段在不在。
 * 序列号只出现在音频响应上，按 flags 判断：0 无序列号、1 正序列号、2 最后一包、3 负序列号。
 */
internal object DoubaoProto {
    const val VERSION = 0x1
    const val HEADER_WORDS = 0x1

    const val MSG_FULL_CLIENT = 0x1
    const val MSG_AUDIO_CLIENT = 0x2
    const val MSG_FULL_SERVER = 0x9
    const val MSG_AUDIO_SERVER = 0xB
    const val MSG_ERROR = 0xF

    const val FLAG_NONE = 0x0
    const val FLAG_POSITIVE = 0x1
    const val FLAG_LAST = 0x2
    const val FLAG_NEGATIVE = 0x3

    const val SERIAL_RAW = 0x0
    const val SERIAL_JSON = 0x1

    const val COMPRESS_NONE = 0x0
    const val COMPRESS_GZIP = 0x1
}

/** 解出来的一帧。错误响应的 event 为 0，错误码放在 errorCode 里；载荷已经按 gzip 标志解压过。 */
internal data class DoubaoFrame(
    val messageType: Int,
    val flags: Int,
    val serialization: Int,
    val compression: Int,
    val event: Int,
    val sessionId: String = "",
    val payload: ByteArray = ByteArray(0),
    val sequence: Int? = null,
    val errorCode: Int? = null,
)

internal object DoubaoFrameCodec {

    /** JSON 事件帧。gzip 标志同时写进头里，服务端按这个标志决定回应要不要压。 */
    fun request(
        messageType: Int,
        event: Int,
        sessionId: String,
        payload: ByteArray,
        gzip: Boolean = true,
    ): ByteArray {
        val body = if (gzip) gzip(payload) else payload
        val out = ByteArrayOutputStream()
        out.write(header(messageType, DoubaoProto.FLAG_NONE, DoubaoProto.SERIAL_JSON, if (gzip) DoubaoProto.COMPRESS_GZIP else DoubaoProto.COMPRESS_NONE))
        out.writeInt32(event)
        if (sessionId.isNotEmpty()) {
            val id = sessionId.toByteArray(Charsets.UTF_8)
            out.writeInt32(id.size)
            out.write(id)
        }
        out.writeInt32(body.size)
        out.write(body)
        return out.toByteArray()
    }

    /** 上行音频帧：裸 PCM，不做 JSON 也不压，压了反而不划算。 */
    fun audio(event: Int, sessionId: String, pcm: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(header(DoubaoProto.MSG_AUDIO_CLIENT, DoubaoProto.FLAG_NONE, DoubaoProto.SERIAL_RAW, DoubaoProto.COMPRESS_NONE))
        out.writeInt32(event)
        if (sessionId.isNotEmpty()) {
            val id = sessionId.toByteArray(Charsets.UTF_8)
            out.writeInt32(id.size)
            out.write(id)
        }
        out.writeInt32(pcm.size)
        out.write(pcm)
        return out.toByteArray()
    }

    /**
     * 解一帧。会话号那一段在不在按事件判断，先按文档规矩试一次，
     * 长度对不上再换成另一种假设重试，两种都不成说明帧本身坏了。
     */
    fun decode(bytes: ByteArray): DoubaoFrame {
        require(bytes.size >= 8) { "帧太短" }
        val headerWords = bytes[0].toInt() and 0x0F
        val messageType = (bytes[1].toInt() shr 4) and 0x0F
        val flags = bytes[1].toInt() and 0x0F
        val serialization = (bytes[2].toInt() shr 4) and 0x0F
        val compression = bytes[2].toInt() and 0x0F
        var offset = headerWords * 4
        require(offset <= bytes.size) { "头长度越界" }

        if (messageType == DoubaoProto.MSG_ERROR) {
            require(offset + 8 <= bytes.size) { "错误帧太短" }
            val code = readInt32(bytes, offset)
            offset += 4
            val size = readInt32(bytes, offset)
            offset += 4
            require(size >= 0 && offset + size == bytes.size) { "错误帧载荷长度对不上" }
            return DoubaoFrame(
                messageType = messageType,
                flags = flags,
                serialization = serialization,
                compression = compression,
                event = 0,
                payload = body(bytes, offset, size, compression),
                errorCode = code,
            )
        }

        val sequence = if (messageType == DoubaoProto.MSG_AUDIO_SERVER &&
            (flags == DoubaoProto.FLAG_POSITIVE || flags == DoubaoProto.FLAG_NEGATIVE)
        ) {
            require(offset + 4 <= bytes.size) { "序列号越界" }
            readInt32(bytes, offset).also { offset += 4 }
        } else {
            null
        }

        require(offset + 4 <= bytes.size) { "事件号越界" }
        val event = readInt32(bytes, offset)
        offset += 4
        val sessionScoped = event !in DoubaoEvents.CONNECTION_LEVEL
        val order = if (sessionScoped) listOf(true, false) else listOf(false, true)
        for (withSession in order) {
            attempt(bytes, offset, messageType, flags, serialization, compression, event, sequence, withSession)
                ?.let { return it }
        }
        throw IllegalArgumentException("帧结构对不上，event=$event 长度=${bytes.size}")
    }

    private fun attempt(
        bytes: ByteArray,
        start: Int,
        messageType: Int,
        flags: Int,
        serialization: Int,
        compression: Int,
        event: Int,
        sequence: Int?,
        withSession: Boolean,
    ): DoubaoFrame? {
        var offset = start
        var sessionId = ""
        if (withSession) {
            if (offset + 4 > bytes.size) return null
            val length = readInt32(bytes, offset)
            if (length < 0 || length > MAX_SESSION_ID) return null
            if (offset + 4 + length + 4 > bytes.size) return null
            sessionId = String(bytes, offset + 4, length, Charsets.UTF_8)
            offset += 4 + length
        }
        if (offset + 4 > bytes.size) return null
        val size = readInt32(bytes, offset)
        offset += 4
        if (size < 0 || offset + size != bytes.size) return null
        return DoubaoFrame(
            messageType = messageType,
            flags = flags,
            serialization = serialization,
            compression = compression,
            event = event,
            sessionId = sessionId,
            payload = body(bytes, offset, size, compression),
            sequence = sequence,
        )
    }

    fun gzip(bytes: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        GZIPOutputStream(out).use { it.write(bytes) }
        return out.toByteArray()
    }

    fun gunzip(bytes: ByteArray): ByteArray =
        GZIPInputStream(ByteArrayInputStream(bytes)).use { it.readBytes() }

    private fun header(messageType: Int, flags: Int, serialization: Int, compression: Int): ByteArray = byteArrayOf(
        ((DoubaoProto.VERSION shl 4) or DoubaoProto.HEADER_WORDS).toByte(),
        ((messageType shl 4) or flags).toByte(),
        ((serialization shl 4) or compression).toByte(),
        0x00,
    )

    private fun body(bytes: ByteArray, offset: Int, size: Int, compression: Int): ByteArray {
        if (size == 0) return ByteArray(0)
        val raw = bytes.copyOfRange(offset, offset + size)
        return if (compression == DoubaoProto.COMPRESS_GZIP) gunzip(raw) else raw
    }

    private fun readInt32(bytes: ByteArray, offset: Int): Int =
        ((bytes[offset].toInt() and 0xFF) shl 24) or
            ((bytes[offset + 1].toInt() and 0xFF) shl 16) or
            ((bytes[offset + 2].toInt() and 0xFF) shl 8) or
            (bytes[offset + 3].toInt() and 0xFF)

    private fun ByteArrayOutputStream.writeInt32(value: Int) {
        write((value ushr 24) and 0xFF)
        write((value ushr 16) and 0xFF)
        write((value ushr 8) and 0xFF)
        write(value and 0xFF)
    }

    /** 会话号是 UUID 一类的短串，超过这个长度一定是把载荷长度错当了会话号长度。 */
    private const val MAX_SESSION_ID = 256
}
