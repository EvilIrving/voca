package com.parley.speaker.data.realtime.openai

import com.parley.speaker.data.realtime.Pcm
import com.parley.speaker.data.realtime.ProviderFault
import com.parley.speaker.data.realtime.RealtimeEvent
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 报文形状与事件映射的验收。请求侧盯的是手动回合制那两个关键位：turn_detection 必须是 null、
 * 音频格式必须是二十四千赫兹裸 PCM；事件侧盯的是六类事件与迟到内容的响应号。
 */
class OpenAiWireTest {

    @Test
    fun `session_update 关掉服务端断句并声明二十四千赫兹裸 PCM`() {
        val payload = OpenAiWire.sessionUpdate("gpt-realtime-2.1", "alloy", "你是口语搭档")
        val session = payload.getValue("session").jsonObject

        assertEquals("session.update", payload.getValue("type").jsonPrimitive.content)
        assertEquals("realtime", session.getValue("type").jsonPrimitive.content)
        assertEquals("gpt-realtime-2.1", session.getValue("model").jsonPrimitive.content)
        assertEquals("你是口语搭档", session.getValue("instructions").jsonPrimitive.content)
        assertEquals("audio", session.getValue("output_modalities").jsonArray.first().jsonPrimitive.content)

        val audio = session.getValue("audio").jsonObject
        val input = audio.getValue("input").jsonObject
        val output = audio.getValue("output").jsonObject

        assertEquals(
            "turn_detection 必须是 null，写成空对象服务端还会自己断句",
            JsonNull,
            input.getValue("turn_detection"),
        )
        assertEquals("audio/pcm", input.getValue("format").jsonObject.getValue("type").jsonPrimitive.content)
        assertEquals(24_000, input.getValue("format").jsonObject.getValue("rate").jsonPrimitive.int)
        assertEquals("audio/pcm", output.getValue("format").jsonObject.getValue("type").jsonPrimitive.content)
        assertEquals(24_000, output.getValue("format").jsonObject.getValue("rate").jsonPrimitive.int)
        assertEquals("alloy", output.getValue("voice").jsonPrimitive.content)
        assertTrue(
            "转写模型要显式给，缺了就没有用户文本",
            input.getValue("transcription").jsonObject.containsKey("model"),
        )
    }

    @Test
    fun `上行请求的四种类型`() {
        assertEquals("input_audio_buffer.append", OpenAiWire.appendAudio("AAAA").getValue("type").jsonPrimitive.content)
        assertEquals("AAAA", OpenAiWire.appendAudio("AAAA").getValue("audio").jsonPrimitive.content)
        assertEquals("input_audio_buffer.commit", OpenAiWire.commit().getValue("type").jsonPrimitive.content)
        assertEquals("response.create", OpenAiWire.createResponse().getValue("type").jsonPrimitive.content)
        assertEquals("response.cancel", OpenAiWire.cancelResponse().getValue("type").jsonPrimitive.content)
    }

    @Test
    fun `打字输入落一条用户消息再让模型开口`() {
        val payload = OpenAiWire.userText("先聊天气")
        val item = payload.getValue("item").jsonObject
        val content = item.getValue("content").toString()

        assertEquals("conversation.item.create", payload.getValue("type").jsonPrimitive.content)
        assertEquals("user", item.getValue("role").jsonPrimitive.content)
        assertTrue("内容块要写成 input_text，实际是 $content", content.contains("input_text"))
        assertTrue(content.contains("先聊天气"))
    }

    @Test
    fun `音频块解回原始字节并带上响应号`() {
        val pcm = ByteArray(64) { (it * 3).toByte() }

        val parsed = OpenAiWire.parse(
            """{"type":"response.audio.delta","response_id":"resp_1","delta":"${Pcm.encodeBase64(pcm)}"}""",
        )

        val event = parsed?.event as? RealtimeEvent.Audio
        assertArrayEquals(pcm, event?.pcm)
        assertEquals("resp_1", parsed?.responseId)
    }

    @Test
    fun `助手转写按增量出事件`() {
        val parsed = OpenAiWire.parse(
            """{"type":"response.audio_transcript.delta","response_id":"resp_1","delta":"I would like"}""",
        )

        assertEquals(RealtimeEvent.AssistantText("I would like"), parsed?.event)
        assertEquals("resp_1", parsed?.responseId)
    }

    @Test
    fun `用户转写只取 completed 那一条`() {
        val completed = OpenAiWire.parse(
            """{"type":"conversation.item.input_audio_transcription.completed","item_id":"item_1","transcript":"我想要一杯咖啡"}""",
        )
        val delta = OpenAiWire.parse(
            """{"type":"conversation.item.input_audio_transcription.delta","item_id":"item_1","delta":"我想要"}""",
        )

        assertEquals(RealtimeEvent.UserText("我想要一杯咖啡"), completed?.event)
        assertNull("delta 与 completed 一起收会把同一段文字记两遍", delta)
    }

    @Test
    fun `收尾事件区分正常结束与被取消`() {
        val done = OpenAiWire.parse(
            """{"type":"response.done","response":{"id":"resp_1","status":"completed"}}""",
        )
        val cancelled = OpenAiWire.parse(
            """{"type":"response.done","response":{"id":"resp_2","status":"cancelled"}}""",
        )

        assertEquals(RealtimeEvent.TurnComplete, done?.event)
        assertEquals(false, done?.cancelled)
        assertEquals("resp_2", cancelled?.responseId)
        assertEquals("被取消那轮的收尾不能再当回合结束", true, cancelled?.cancelled)
    }

    @Test
    fun `响应创建只带响应号`() {
        val parsed = OpenAiWire.parse("""{"type":"response.created","response":{"id":"resp_9"}}""")

        assertNull(parsed?.event)
        assertEquals("resp_9", parsed?.responseId)
    }

    @Test
    fun `会话就绪`() {
        assertEquals(RealtimeEvent.Ready, OpenAiWire.parse("""{"type":"session.created"}""")?.event)
        assertEquals(RealtimeEvent.Ready, OpenAiWire.parse("""{"type":"session.updated"}""")?.event)
    }

    @Test
    fun `错误事件只给归类后的那一句，供应商原文不上界面`() {
        val failure = OpenAiWire.parse(
            """{"type":"error","error":{"type":"invalid_request_error","message":"Invalid voice"}}""",
        )
        val capped = OpenAiWire.parse(
            """{"type":"error","error":{"code":"insufficient_quota","message":"You exceeded your current quota, please check your plan and billing details."}}""",
        )
        val unknown = OpenAiWire.parse(
            """{"type":"error","error":{"message":"something odd happened upstream"}}""",
        )
        val noResponse = OpenAiWire.parse(
            """{"type":"error","error":{"message":"Cancellation failed: no active response found"}}""",
        )

        assertEquals(RealtimeEvent.Failed(ProviderFault.Config), failure?.event)
        assertEquals(RealtimeEvent.Failed(ProviderFault.Quota), capped?.event)
        assertEquals(RealtimeEvent.Failed("服务异常，正在重连"), unknown?.event)
        assertNull(noResponse)
    }

    @Test
    fun `认不出的消息与坏 JSON 都不抛`() {
        assertNull(OpenAiWire.parse("""{"type":"rate_limits.updated"}"""))
        assertNull(OpenAiWire.parse("不是 JSON"))
        assertNull(OpenAiWire.parse("""{"type":"response.audio.delta"}"""))
    }

    @Test
    fun `坏 base64 的音频块返回空而不是抛给连接`() {
        assertNull(
            "一块坏 base64 只该丢这一块",
            OpenAiWire.parse(
                """{"type":"response.audio.delta","response_id":"resp_1","delta":"AAAAA"}""",
            ),
        )
    }

    @Test
    fun `空音频块的 base64 是空串`() {
        assertEquals("", Pcm.encodeBase64(ByteArray(0)))
    }
}
