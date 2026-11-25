package com.voca.speaker.data.realtime.doubao

import com.voca.speaker.data.realtime.ProviderFault
import com.voca.speaker.data.realtime.RealtimeEvent
import java.util.Base64
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 3.0 全双工协议的报文验收：上行四个事件的形状、下行七类事件怎么落到界面那六类上。
 * 下行音频格式只在 session.create 里定得下来，所以 pcm_s16le 与二十四千赫兹是这里最要紧的两条。
 */
class DoubaoWireTest {

    @Test
    fun `开会话定下模型号与下行裸 PCM 十六位二十四千赫兹`() {
        val root = Json.parseToJsonElement(
            DoubaoWire.sessionCreate("s-1", "zh_female_vv_jupiter_bigtts", "你是口语搭档"),
        ).jsonObject
        val session = root.getValue("session").jsonObject
        val audio = session.getValue("audio").jsonObject
        val input = audio.getValue("input").jsonObject.getValue("format").jsonObject
        val output = audio.getValue("output").jsonObject
        val out = output.getValue("format").jsonObject

        assertEquals("session.create", root.getValue("type").jsonPrimitive.content)
        assertEquals("s-1", session.getValue("id").jsonPrimitive.content)
        assertEquals("1.2.6.1", session.getValue("model").jsonPrimitive.content)
        assertEquals("你是口语搭档", session.getValue("instructions").jsonPrimitive.content)
        assertEquals("pcm", input.getValue("type").jsonPrimitive.content)
        assertEquals(16_000, input.getValue("rate").jsonPrimitive.int)
        assertEquals("pcm_s16le", out.getValue("type").jsonPrimitive.content)
        assertEquals(24_000, out.getValue("rate").jsonPrimitive.int)
        assertEquals("zh_female_vv_jupiter_bigtts", output.getValue("voice").jsonPrimitive.content)

        val dialog = session.getValue("extension").jsonObject.getValue("dialog").jsonObject
        assertEquals(false, dialog.getValue("strict_audit").jsonPrimitive.boolean)
        assertNull("没有历史时不传空的 dialog_context", dialog["dialog_context"])
    }

    @Test
    fun `成对流水走 dialog_context，不进系统提示`() {
        val root = Json.parseToJsonElement(
            DoubaoWire.sessionCreate(
                "s-1",
                "v",
                "你是口语搭档",
                listOf("user" to "I went to the shop.", "assistant" to "How was it?"),
            ),
        ).jsonObject
        val session = root.getValue("session").jsonObject
        val dialog = session.getValue("extension").jsonObject.getValue("dialog").jsonObject
        val context = dialog.getValue("dialog_context")

        assertEquals("你是口语搭档", session.getValue("instructions").jsonPrimitive.content)
        assertEquals(
            """[{"role":"user","text":"I went to the shop."},{"role":"assistant","text":"How was it?"}]""",
            context.toString(),
        )
    }

    @Test
    fun `空提示词不送 instructions`() {
        val root = Json.parseToJsonElement(DoubaoWire.sessionCreate("s-1", "v", "")).jsonObject
        val session = root.getValue("session").jsonObject

        assertTrue("空串会被服务端当成一句空的系统提示", session["instructions"] !is JsonNull)
        assertNull(session["instructions"])
    }

    @Test
    fun `音频上行是 Base64 的 append，松手发 commit 与关麦`() {
        val pcm = byteArrayOf(0, 1, 2, 3, -1)
        val append = Json.parseToJsonElement(DoubaoWire.audioAppend(pcm)).jsonObject
        val commit = Json.parseToJsonElement(DoubaoWire.commit()).jsonObject
        val mute = Json.parseToJsonElement(DoubaoWire.audioMute()).jsonObject
        val unmute = Json.parseToJsonElement(DoubaoWire.audioUnmute()).jsonObject

        assertEquals("input_audio_buffer.append", append.getValue("type").jsonPrimitive.content)
        assertArrayEquals(
            pcm,
            Base64.getDecoder().decode(append.getValue("audio").jsonPrimitive.content),
        )
        assertEquals("input_audio_buffer.commit", commit.getValue("type").jsonPrimitive.content)
        assertEquals("关麦通知与 commit 是同一步里的两条", "input_audio_mute.commit", mute.getValue("type").jsonPrimitive.content)
        assertEquals("input_audio_unmute.commit", unmute.getValue("type").jsonPrimitive.content)
    }

    @Test
    fun `会话就绪投影成 Ready，会话号用服务端回的那个`() {
        val inbound = DoubaoWire.parse("""{"type":"session.created","session":{"id":"srv-7"}}""")

        assertEquals(DoubaoInbound.Created("srv-7"), inbound)
        assertEquals(RealtimeEvent.Ready, inbound.event())
    }

    @Test
    fun `转写只在 completed 取一次，delta 不进气泡`() {
        val delta = DoubaoWire.parse(
            """{"type":"conversation.item.input_audio_transcription.delta","delta":"我要一"}""",
        )
        val done = DoubaoWire.parse(
            """{"type":"conversation.item.input_audio_transcription.completed","text":"我要一杯咖啡"}""",
        )

        assertEquals("气泡文本是追加语义，逐字回填会把同一句叠几遍", DoubaoInbound.Ignored, delta)
        assertEquals("豆包这张服务的整句在 text 字段里", RealtimeEvent.UserText("我要一杯咖啡"), done.event())
    }

    @Test
    fun `模型文本取 delta，done 不再补一遍`() {
        val delta = DoubaoWire.parse(
            """{"type":"response.output_text.delta","response_id":"r-1","delta":"Sure,"}""",
        )
        val done = DoubaoWire.parse(
            """{"type":"response.output_text.done","response_id":"r-1","text":"Sure, one coffee."}""",
        )

        assertEquals(RealtimeEvent.AssistantText("Sure,"), delta.event())
        assertEquals("整段原文再落一次就把这句说两遍", null, done.event())
    }

    @Test
    fun `下行音频解出裸 PCM 并带上响应号`() {
        val pcm = byteArrayOf(9, 8, 7, 6, 5, 4)
        val frame = """{"type":"response.output_audio.delta","response_id":"r-1","delta":"${b64(pcm)}"}"""

        val inbound = DoubaoWire.parse(frame) as DoubaoInbound.Audio
        assertArrayEquals(pcm, inbound.pcm)
        assertEquals("r-1", inbound.responseId)
    }

    @Test
    fun `空音频块不进播放队列`() {
        assertEquals(
            DoubaoInbound.Ignored,
            DoubaoWire.parse("""{"type":"response.output_audio.delta","response_id":"r-1","delta":""}"""),
        )
    }

    @Test
    fun `一轮音频说完是 TurnComplete，提交回执与打断回执都不到界面`() {
        val ended = DoubaoWire.parse("""{"type":"response.output_audio.done","response_id":"r-1"}""")
        val committed = DoubaoWire.parse("""{"type":"input_audio_buffer.committed","event_id":"e-1"}""")
        val canceled = DoubaoWire.parse("""{"type":"response.canceled","event_id":"e-2"}""")

        assertEquals(RealtimeEvent.TurnComplete, ended.event())
        assertEquals(DoubaoInbound.Committed, committed)
        assertNull(committed.event())
        assertEquals(DoubaoInbound.Canceled, canceled)
        assertNull(canceled.event())
    }

    @Test
    fun `报错认得出类别就给那一句，认不出就是空串`() {
        val quota = DoubaoWire.parse(
            """{"type":"error","error":{"code":"45000002","message":"quota exceeded"}}""",
        )
        val noise = DoubaoWire.parse(
            """{"type":"error","error":{"code":"9","message":"something odd"}}""",
        )

        assertEquals(RealtimeEvent.Failed(ProviderFault.Quota), quota.event())
        assertEquals(RealtimeEvent.Failed(""), noise.event())
    }

    @Test
    fun `按住时模型开口算抢话，用户转写不算`() {
        assertTrue(
            DoubaoInbound.Audio(byteArrayOf(1), "r-1").isModelTalk(),
        )
        assertTrue(DoubaoInbound.AssistantText("Hi", "r-1").isModelTalk())
        assertTrue(DoubaoInbound.TurnComplete("r-1").isModelTalk())
        assertFalse(DoubaoInbound.UserText("I went").isModelTalk())
        assertFalse(DoubaoInbound.Committed.isModelTalk())
    }

    @Test
    fun `认不出的事件与坏帧一律忽略`() {
        assertEquals(DoubaoInbound.Ignored, DoubaoWire.parse("""{"type":"response.done"}"""))
        assertEquals(DoubaoInbound.Ignored, DoubaoWire.parse("""{"type":"session.updated"}"""))
        assertEquals(DoubaoInbound.Ignored, DoubaoWire.parse("不是 JSON"))
    }

    private fun b64(bytes: ByteArray): String = Base64.getEncoder().encodeToString(bytes)
}
