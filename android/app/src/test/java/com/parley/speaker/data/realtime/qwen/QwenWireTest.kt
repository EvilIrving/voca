package com.parley.speaker.data.realtime.qwen

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
 * 报文形状与事件映射的验收。请求侧盯的是手动回合制那一格 turn_detection 必须是 null、
 * 上行不提重采样、历史与打字都走 conversation.item.create 且角色决定 content 类型；
 * 事件侧盯的是六类事件、response.done 的三种收尾，以及千问那套错误码的归类。
 */
class QwenWireTest {

    @Test
    fun `session_update 关掉服务端断句并只发这一条`() {
        val payload = QwenWire.sessionUpdate("longanlingxin_v3.1", "你是口语搭档")
        val session = payload.getValue("session").jsonObject

        assertEquals("session.update", payload.getValue("type").jsonPrimitive.content)
        assertEquals("longanlingxin_v3.1", session.getValue("voice").jsonPrimitive.content)
        assertEquals("你是口语搭档", session.getValue("instructions").jsonPrimitive.content)
        assertEquals(
            "turn_detection 必须是 null，写成空对象服务端照旧自己判停",
            JsonNull,
            session.getValue("turn_detection"),
        )
        assertEquals(
            "音频与文本两路都要，字幕靠文本那一路回来",
            listOf("audio", "text"),
            session.getValue("modalities").jsonArray.map { it.jsonPrimitive.content },
        )
        assertEquals(50, session.getValue("max_history_turns").jsonPrimitive.int)
        assertTrue(
            "上行语种不钉死，钉子 en 会把混说的中文听成英文",
            !session.containsKey("input_audio_transcription"),
        )
    }

    @Test
    fun `上行请求的六种类型`() {
        assertEquals("input_audio_buffer.append", QwenWire.appendAudio("AAAA").getValue("type").jsonPrimitive.content)
        assertEquals("AAAA", QwenWire.appendAudio("AAAA").getValue("audio").jsonPrimitive.content)
        assertEquals("input_audio_buffer.commit", QwenWire.commit().getValue("type").jsonPrimitive.content)
        assertEquals("input_audio_buffer.clear", QwenWire.clearBuffer().getValue("type").jsonPrimitive.content)
        assertEquals("response.create", QwenWire.createResponse().getValue("type").jsonPrimitive.content)
        assertEquals("response.cancel", QwenWire.cancelResponse().getValue("type").jsonPrimitive.content)
    }

    @Test
    fun `对话项的角色决定 content 类型`() {
        val user = QwenWire.message("user", "先聊天气").getValue("item").jsonObject
        val assistant = QwenWire.message("assistant", "Sure, go ahead").getValue("item").jsonObject

        assertEquals("conversation.item.create", QwenWire.message("user", "x").getValue("type").jsonPrimitive.content)
        assertEquals("user", user.getValue("role").jsonPrimitive.content)
        assertEquals("assistant", assistant.getValue("role").jsonPrimitive.content)
        assertEquals(
            "用户与系统用 input_text，助手用 output_text，服务端按键名校验",
            "input_text",
            user.getValue("content").jsonArray.first().jsonObject.getValue("type").jsonPrimitive.content,
        )
        assertEquals(
            "output_text",
            assistant.getValue("content").jsonArray.first().jsonObject.getValue("type").jsonPrimitive.content,
        )
        assertEquals(
            "先聊天气",
            user.getValue("content").jsonArray.first().jsonObject.getValue("text").jsonPrimitive.content,
        )
    }

    @Test
    fun `音频块解回原始字节并带上响应号`() {
        val pcm = ByteArray(64) { (it * 3).toByte() }

        val parsed = QwenWire.parse(
            """{"type":"response.audio.delta","response_id":"resp_1","delta":"${Pcm.encodeBase64(pcm)}"}""",
        )

        val event = parsed?.event as? RealtimeEvent.Audio
        assertArrayEquals(pcm, event?.pcm)
        assertEquals("resp_1", parsed?.responseId)
    }

    @Test
    fun `助手字幕按增量出事件，纯文本那一路不收`() {
        val delta = QwenWire.parse(
            """{"type":"response.audio_transcript.delta","response_id":"resp_1","delta":"I would like"}""",
        )

        assertEquals(RealtimeEvent.AssistantText("I would like"), delta?.event)
        assertEquals("resp_1", delta?.responseId)
        assertNull(
            "response.text.delta 与字幕是同一条内容，两条一起收会把同一句叠两遍",
            QwenWire.parse("""{"type":"response.text.delta","response_id":"resp_1","delta":"I would"}"""),
        )
    }

    @Test
    fun `用户转写只取 completed 那一条`() {
        val completed = QwenWire.parse(
            """{"type":"conversation.item.input_audio_transcription.completed","item_id":"item_1","transcript":"我想要一杯咖啡"}""",
        )
        val delta = QwenWire.parse(
            """{"type":"conversation.item.input_audio_transcription.delta","item_id":"item_1","text":"我想要","stash":"一杯"}""",
        )

        assertEquals(RealtimeEvent.UserText("我想要一杯咖啡"), completed?.event)
        assertNull("delta 与 completed 一起收会把同一段文字记两遍", delta)
    }

    @Test
    fun `收尾三种状态分开处理`() {
        val completed = QwenWire.parse(
            """{"type":"response.done","response":{"id":"resp_1","status":"completed"}}""",
        )
        val cancelled = QwenWire.parse(
            """{"type":"response.done","response":{"id":"resp_2","status":"cancelled"}}""",
        )
        val failed = QwenWire.parse(
            """{"type":"response.done","response":{"id":"resp_3","status":"failed"}}""",
        )

        assertEquals(RealtimeEvent.TurnComplete, completed?.event)
        assertEquals(false, completed?.cancelled)
        assertEquals("resp_2", cancelled?.responseId)
        assertEquals("被取消那轮的收尾不能再当回合结束", true, cancelled?.cancelled)
        assertEquals("推理失败只报空串，原文进日志", RealtimeEvent.Failed(""), failed?.event)
    }

    @Test
    fun `响应创建只带响应号，会话就绪两条都算`() {
        val created = QwenWire.parse("""{"type":"response.created","response":{"id":"resp_9"}}""")

        assertNull(created?.event)
        assertEquals("resp_9", created?.responseId)
        assertEquals(RealtimeEvent.Ready, QwenWire.parse("""{"type":"session.created"}""")?.event)
        assertEquals(RealtimeEvent.Ready, QwenWire.parse("""{"type":"session.updated"}""")?.event)
    }

    @Test
    fun `千问那套错误码归成界面那一句`() {
        assertEquals(
            RealtimeEvent.Failed(ProviderFault.Quota),
            QwenWire.parse("""{"type":"error","error":{"code":"Arrearage","message":"Access denied"}}""")?.event,
        )
        assertEquals(
            RealtimeEvent.Failed(ProviderFault.Busy),
            QwenWire.parse("""{"type":"error","error":{"code":"Throttling","message":"Requests rate limit exceeded"}}""")?.event,
        )
        assertEquals(
            RealtimeEvent.Failed(ProviderFault.Key),
            QwenWire.parse("""{"type":"error","error":{"code":"InvalidApiKey","message":"Invalid API-key provided."}}""")?.event,
        )
        assertEquals(
            RealtimeEvent.Failed(ProviderFault.Config),
            QwenWire.parse("""{"type":"error","error":{"code":"invalid_parameter","message":"voice not found"}}""")?.event,
        )
        assertEquals(
            "认不出类别就只给空串，界面不出声",
            RealtimeEvent.Failed(""),
            QwenWire.parse("""{"type":"error","error":{"code":"InternalError","message":"something odd upstream"}}""")?.event,
        )
    }

    @Test
    fun `没有在生成的响应时发取消被回错，不当故障`() {
        // 实测原样：{type:invalid_request_error, code:invalid_value, message:Conversation has no active response., param:response.cancel}
        val parsed = QwenWire.parse(
            """{"type":"error","error":{"type":"invalid_request_error","code":"invalid_value","message":"Conversation has no active response.","param":"response.cancel"}}""",
        )

        assertNull("取消扑空是竞态，接着连，不打断会话", parsed)
        assertNull(
            "同一个错误没了 param 也要认得出来",
            QwenWire.parse("""{"type":"error","error":{"code":"invalid_value","message":"Conversation has no active response."}}"""),
        )
    }

    @Test
    fun `认不出的消息与坏 JSON 都不抛`() {
        assertNull(QwenWire.parse("""{"type":"input_audio_buffer.committed"}"""))
        assertNull(QwenWire.parse("""{"type":"response.audio.done","response_id":"resp_1"}"""))
        assertNull(QwenWire.parse("不是 JSON"))
        assertNull(QwenWire.parse("""{"type":"response.audio.delta"}"""))
    }

    @Test
    fun `坏 base64 的音频块只丢这一块`() {
        assertNull(
            QwenWire.parse("""{"type":"response.audio.delta","response_id":"resp_1","delta":"AAAAA"}"""),
        )
    }
}
