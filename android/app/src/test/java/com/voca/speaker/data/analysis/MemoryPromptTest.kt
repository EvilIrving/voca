package com.voca.speaker.data.analysis

import com.voca.speaker.domain.MemoryMaxChars
import com.voca.speaker.domain.analysisOf
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 记忆改写的这一次请求：整段旧记忆与这一轮的材料一并送去，回来的是自由文本而不是 JSON。
 * 提示词一段是机制的一部分，上限与合并规矩都在里面，界面改不到。
 */
class MemoryPromptTest {

    private val recorder = MemoryRecorder()
    private val client = OkHttpClient.Builder().addInterceptor(recorder).build()

    @Test
    fun `提示词带着旧记忆与这一轮的材料`() {
        val prompt = memoryPrompt("他叫李明，准备雅思。", "学生：I goed to the gym.\n助教：You went to the gym?")

        assertTrue(prompt.contains("他叫李明，准备雅思。"))
        assertTrue(prompt.contains("学生：I goed to the gym."))
        assertTrue("只输出记忆正文，不写修改说明", prompt.contains("不写标题、编号、条目、时间戳"))
        assertTrue("上限写在提示词里", prompt.contains("$MemoryMaxChars"))
    }

    @Test
    fun `改写走自由文本，不开 JSON 模式`() = runBlocking {
        engine().memorize("他叫李明。", "学生：I went to the gym.")

        val request = recorder.requests.single()
        val body = Json.parseToJsonElement(bodyOf(request)).jsonObject
        assertEquals("deepseek-chat", body["model"]!!.jsonPrimitive.content)
        assertFalse("记忆是一段叙述，不是一份报告", body.containsKey("response_format"))
        val sent = userContent(request)
        assertTrue("旧记忆也一起送去", sent.contains("他叫李明。"))
        assertTrue(sent.contains("学生：I went to the gym."))
    }

    @Test
    fun `改写回来的空白与围栏都清掉`() = runBlocking {
        val engine = engine(reply = "```\n他叫李明，下周三面试。\n```")

        assertEquals("他叫李明，下周三面试。", engine.memorize("他叫李明。", "学生：面试在下周三。"))
    }

    @Test
    fun `包成 JSON 的记忆取正文而不是整段 JSON`() = runBlocking {
        val wrapped = """{"memory": "他叫李明，准备雅思，周四面试。"}"""

        assertEquals(
            "他叫李明，准备雅思，周四面试。",
            engine(reply = wrapped).memorize("他叫李明。", "学生：面试改到周四了。"),
        )
        assertEquals("他叫李明。", parseMemory("他叫李明。"))
        assertEquals("他叫李明。", parseMemory("{\"text\": \"他叫李明。\"}"))
        assertEquals("他叫李明。", parseMemory("{\"content\": \"他叫李明。\"}"))
        assertNull(parseMemory("   "))
    }

    @Test
    fun `没有材料就不发请求`() = runBlocking {
        assertNull(engine().memorize("他叫李明。", "  "))

        assertTrue("没话可说就不必改写", recorder.requests.isEmpty())
    }

    @Test
    fun `没配密钥就不发请求`() = runBlocking {
        val settings = analysisOf(
            apiKey = "",
            model = "deepseek-chat",
            provider = TextProviderCatalog.deepseek.id,
        )

        assertNull(OpenAiCompatibleEngine(TextEngineConfig.of(settings), client).memorize("他叫李明。", "学生：hi"))
        assertTrue(recorder.requests.isEmpty())
    }

    private fun engine(reply: String = MemoryReply): OpenAiCompatibleEngine {
        recorder.reply = reply
        val settings = analysisOf(
            apiKey = "sk-deep",
            model = "deepseek-chat",
            provider = TextProviderCatalog.deepseek.id,
        )
        return OpenAiCompatibleEngine(TextEngineConfig.of(settings), client)
    }

    private fun bodyOf(request: Request): String {
        val buffer = Buffer()
        request.body?.writeTo(buffer)
        return buffer.readUtf8()
    }

    private fun userContent(request: Request): String =
        Json.parseToJsonElement(bodyOf(request)).jsonObject["messages"]!!
            .jsonArray[1].jsonObject["content"]!!.jsonPrimitive.content
}

/** 顶掉网络的拦截器：请求留在内存里，回一份能解析的助手消息。 */
private class MemoryRecorder : Interceptor {
    val requests = mutableListOf<Request>()
    var reply: String = MemoryReply

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        requests += request
        val body = """{"choices": [{"message": {"role": "assistant", "content": ${JsonPrimitive(reply)}}}]}"""
        return Response.Builder()
            .request(request)
            .protocol(Protocol.HTTP_1_1)
            .code(200)
            .message("OK")
            .body(body.toResponseBody("application/json".toMediaType()))
            .build()
    }
}

private const val MemoryReply = "他叫李明，准备雅思，下周三面试。"
