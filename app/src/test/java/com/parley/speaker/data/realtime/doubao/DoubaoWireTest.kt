package com.parley.speaker.data.realtime.doubao

import com.parley.speaker.data.realtime.ProviderFault
import com.parley.speaker.data.realtime.RealtimeEvent
import java.io.ByteArrayOutputStream
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 载荷与事件映射的验收。下行音频的格式只在 StartSession 里定得下来，所以那一段的
 * pcm、二十四千赫兹、十六位三项是这里最要紧的断言；事件侧盯六类事件怎么落。
 */
class DoubaoWireTest {

    @Test
    fun `开会话定下裸 PCM 二十四千赫兹十六位与音色`() {
        val frame = DoubaoFrameCodec.decode(
            DoubaoWire.startSession("s-1", "zh_female_vv_jupiter_bigtts", "你是口语搭档", pushToTalk = true),
        )
        val root = Json.parseToJsonElement(frame.payload.toString(Charsets.UTF_8)).jsonObject
        val tts = root.getValue("tts").jsonObject
        val audio = tts.getValue("audio_config").jsonObject
        val dialog = root.getValue("dialog").jsonObject

        assertEquals(DoubaoEvents.START_SESSION, frame.event)
        assertEquals("s-1", frame.sessionId)
        assertEquals("zh_female_vv_jupiter_bigtts", tts.getValue("speaker").jsonPrimitive.content)
        assertEquals("pcm", audio.getValue("format").jsonPrimitive.content)
        assertEquals(24_000, audio.getValue("sample_rate").jsonPrimitive.int)
        assertEquals(16, audio.getValue("bit_width").jsonPrimitive.int)
        assertEquals(1, audio.getValue("channel").jsonPrimitive.int)
        assertEquals("push_to_talk", dialog.getValue("input_mod").jsonPrimitive.content)
        assertTrue(dialog.getValue("enable_custom_vad").jsonPrimitive.boolean)
        assertEquals("你是口语搭档", dialog.getValue("system_role").jsonPrimitive.content)
    }

    @Test
    fun `关掉手动回合制就不带那两个开关`() {
        val frame = DoubaoFrameCodec.decode(
            DoubaoWire.startSession("s-1", "zh_female_vv_jupiter_bigtts", "", pushToTalk = false),
        )
        val dialog = Json.parseToJsonElement(frame.payload.toString(Charsets.UTF_8))
            .jsonObject.getValue("dialog").jsonObject

        assertNull("交给服务端判停时不该声明 push_to_talk", dialog["input_mod"])
        assertNull(dialog["enable_custom_vad"])
        assertNull("空提示词不送 system_role", dialog["system_role"])
    }

    @Test
    fun `连接级请求与音频帧各走各的消息类型`() {
        val start = DoubaoFrameCodec.decode(DoubaoWire.startConnection())
        val pcm = ByteArray(320) { it.toByte() }
        val up = DoubaoFrameCodec.decode(DoubaoWire.audio("s-1", pcm))
        val end = DoubaoFrameCodec.decode(DoubaoWire.turnEnd("s-1"))

        assertEquals(DoubaoEvents.START_CONNECTION, start.event)
        assertEquals("", start.sessionId)
        assertEquals(DoubaoProto.MSG_AUDIO_CLIENT, up.messageType)
        assertArrayEquals(pcm, up.payload)
        assertEquals(DoubaoEvents.TASK_REQUEST, end.event)
        assertEquals(
            true,
            Json.parseToJsonElement(end.payload.toString(Charsets.UTF_8))
                .jsonObject.getValue("audio_end").jsonPrimitive.boolean,
        )
    }

    @Test
    fun `会话就绪投影成 Ready`() {
        val inbound = DoubaoWire.parse(server(DoubaoEvents.SESSION_STARTED, """{"session_id":"srv-7"}"""))

        assertEquals(DoubaoInbound.SessionStarted, inbound)
        assertEquals(RealtimeEvent.Ready, inbound.event())
        assertEquals("服务端给了会话号就以它的为准", "srv-7", DoubaoWire.sessionIdOf(server(DoubaoEvents.SESSION_STARTED, """{"session_id":"srv-7"}""")))
    }

    @Test
    fun `连接建立本身不投影成事件`() {
        val inbound = DoubaoWire.parse(server(DoubaoEvents.CONNECTION_STARTED, "{}"))

        assertEquals(DoubaoInbound.ConnectionStarted, inbound)
        assertNull(inbound.event())
    }

    @Test
    fun `识别文本与模型文本各归各`() {
        val user = DoubaoWire.parse(
            server(DoubaoEvents.ASR_RESPONSE, """{"results":[{"text":"我想要一杯咖啡","is_interim":false}]}"""),
        )
        val assistant = DoubaoWire.parse(server(DoubaoEvents.CHAT_RESPONSE, """{"content":"Sure, one coffee."}"""))

        assertEquals(RealtimeEvent.UserText("我想要一杯咖啡"), user.event())
        assertEquals(RealtimeEvent.AssistantText("Sure, one coffee."), assistant.event())
    }

    @Test
    fun `一行结束是 TurnComplete，整场结束不能当回合结束`() {
        assertEquals(RealtimeEvent.TurnComplete, DoubaoWire.parse(server(DoubaoEvents.CHAT_ENDED, "{}")).event())
        assertNull(
            "整场结束由会话层当断线报掉，投影成 TurnComplete 就报第二次了",
            DoubaoWire.parse(server(DoubaoEvents.SESSION_FINISHED, "{}")).event(),
        )
    }

    @Test
    fun `下行音频帧给出裸 PCM`() {
        val pcm = byteArrayOf(9, 8, 7, 6, 5, 4)
        val frame = DoubaoFrameCodec.decode(
            assemble(
                messageType = DoubaoProto.MSG_AUDIO_SERVER,
                flags = DoubaoProto.FLAG_NONE,
                serialization = DoubaoProto.SERIAL_RAW,
                blocks = listOf(
                    int32(DoubaoEvents.TTS_RESPONSE),
                    block("s-1".toByteArray()),
                    int32(pcm.size),
                    pcm,
                ),
            ),
        )

        val event = DoubaoWire.parse(frame).event() as? RealtimeEvent.Audio
        assertArrayEquals(pcm, event?.pcm)
    }

    @Test
    fun `下行帧里塞的是 JSON 就不当 PCM 播`() {
        val body = """{"error":"quota exceeded"}""".toByteArray(Charsets.UTF_8)
        val frame = DoubaoFrameCodec.decode(
            assemble(
                messageType = DoubaoProto.MSG_AUDIO_SERVER,
                flags = DoubaoProto.FLAG_NONE,
                serialization = DoubaoProto.SERIAL_JSON,
                blocks = listOf(
                    int32(DoubaoEvents.TTS_RESPONSE),
                    block("s-1".toByteArray()),
                    int32(body.size),
                    body,
                ),
            ),
        )

        assertNull("JSON 文本拿去当 PCM 就是噪声", DoubaoWire.parse(frame).event())
    }

    @Test
    fun `会话失败与错误帧都给出原因`() {
        val sessionFailed = DoubaoWire.parse(
            server(DoubaoEvents.SESSION_FAILED, """{"status":"failed","message":"quota exceeded"}"""),
        )
        val error = DoubaoFrameCodec.decode(
            assemble(
                messageType = DoubaoProto.MSG_ERROR,
                flags = DoubaoProto.FLAG_NONE,
                blocks = listOf(int32(45_000_001), int32(0)),
            ),
        )

        assertEquals(
            "会话失败只给归类后的那一句",
            RealtimeEvent.Failed(ProviderFault.Quota),
            sessionFailed.event(),
        )
        assertEquals(
            "认不出类别就只给空串，界面不出声，原文留给日志",
            RealtimeEvent.Failed(""),
            DoubaoWire.parse(error).event(),
        )
    }

    @Test
    fun `认不出的事件一律忽略`() {
        assertEquals(DoubaoInbound.Ignored, DoubaoWire.parse(server(9_999, """{"whatever":1}""")))
        assertEquals(DoubaoInbound.Ignored, DoubaoWire.parse(server(DoubaoEvents.TTS_SENTENCE_START, "{}")))
        assertEquals(DoubaoInbound.Ignored, DoubaoWire.parse(server(DoubaoEvents.CHAT_RESPONSE, "不是 JSON")))
    }

    private fun server(event: Int, payload: String): DoubaoFrame = DoubaoFrameCodec.decode(
        DoubaoFrameCodec.request(DoubaoProto.MSG_FULL_SERVER, event, "s-1", payload.toByteArray(Charsets.UTF_8)),
    )

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
        serialization: Int = DoubaoProto.SERIAL_JSON,
        blocks: List<ByteArray>,
    ): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(
            byteArrayOf(
                ((DoubaoProto.VERSION shl 4) or DoubaoProto.HEADER_WORDS).toByte(),
                ((messageType shl 4) or flags).toByte(),
                ((serialization shl 4) or DoubaoProto.COMPRESS_NONE).toByte(),
                0x00,
            ),
        )
        blocks.forEach { out.write(it) }
        return out.toByteArray()
    }
}
