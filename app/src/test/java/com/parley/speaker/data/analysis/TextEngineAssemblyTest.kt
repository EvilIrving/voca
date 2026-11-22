package com.parley.speaker.data.analysis

import com.parley.speaker.domain.AnalysisSettings
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
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
 * 设置与清单条目怎样一路拼进请求体：档位、输出上限与输入窗口都从清单那条元数据来。
 * 模型名空着时边界自己挡住，一个请求都不发。网络由拦截器顶掉，请求全部留在内存里。
 */
class TextEngineAssemblyTest {

    private val recorder = Recorder()
    private val client = OkHttpClient.Builder().addInterceptor(recorder).build()

    private val reasoner = TextModel(
        id = "deepseek-reasoner",
        context = 1_000,
        maxOutput = 256,
        thinking = true,
        defaultEffort = "high",
        effortLevels = listOf("low", "medium", "high"),
    )

    @Test
    fun `模型名空着时一个请求都不发`() = runBlocking {
        val config = TextEngineConfig(
            provider = TextProviderCatalog.deepseek,
            model = "",
            endpoint = TextProviderCatalog.deepseek.endpoint,
            auth = AuthSpec.bearer(),
            apiKey = "sk-deep",
        )
        val engine = OpenAiCompatibleEngine(config, client)

        assertNull(engine.analyze("I goed there yesterday", "user-1"))
        assertNull(engine.translate("我昨天去了那里"))
        assertNull(engine.name("学生：hi\n助教：hello"))

        assertTrue("没有模型名就不该有条请求出去", recorder.requests.isEmpty())
        assertFalse("空模型名不算配置齐备", config.ready)
    }

    @Test
    fun `清单条目把档位与输出上限带进请求体`() = runBlocking {
        engineFor(thinking = true).analyze("I goed there yesterday", "user-1")

        val body = bodyOf(recorder.requests.single())
        assertEquals("deepseek-reasoner", body["model"]!!.jsonPrimitive.content)
        assertEquals("清单自报的默认档位", "high", body["reasoning_effort"]!!.jsonPrimitive.content)
        assertEquals("清单自报的输出上限", 256, body["max_tokens"]!!.jsonPrimitive.int)
    }

    @Test
    fun `没有默认档位就发清单里的第一档`() = runBlocking {
        engineFor(thinking = true, meta = reasoner.copy(defaultEffort = null))
            .analyze("I goed there yesterday", "user-1")

        val body = bodyOf(recorder.requests.single())
        assertEquals("low", body["reasoning_effort"]!!.jsonPrimitive.content)
    }

    @Test
    fun `思考开关关着就不发档位`() = runBlocking {
        engineFor(thinking = false).analyze("I goed there yesterday", "user-1")

        assertFalse("开关是唯一裁决点", bodyOf(recorder.requests.single()).containsKey("reasoning_effort"))
    }

    @Test
    fun `整段对话取名的材料按窗口裁过再发`() = runBlocking {
        val transcript = "学生：hi\n".repeat(400)
        engineFor(thinking = false).name(transcript)

        val content = userContent(recorder.requests.single())
        val budget = (reasoner.context - reasoner.maxOutput) * CharsPerToken
        assertTrue("材料比窗口长", transcript.length > budget)
        assertEquals("整段材料截到窗口预算", budget, content.length)
    }

    @Test
    fun `窗口比材料宽就一个字都不裁`() {
        val prompt = sessionNamePrompt("学生：hi\n助教：hello")

        assertEquals(prompt, engineOf(thinking = false).promptWithinContext(prompt))
    }

    @Test
    fun `没带清单条目就不带上限也不裁`() = runBlocking {
        val settings = AnalysisSettings(
            apiKey = "sk-deep",
            model = "deepseek-chat",
            provider = TextProviderCatalog.deepseek.id,
        )
        OpenAiCompatibleEngine(TextEngineConfig.of(settings), client).translate("我昨天去了那里")

        val body = bodyOf(recorder.requests.single())
        assertFalse("清单没到位就不猜上限", body.containsKey("max_tokens"))
        assertFalse(body.containsKey("reasoning_effort"))
    }

    private fun engineFor(thinking: Boolean, meta: TextModel = reasoner): OpenAiCompatibleEngine {
        val settings = AnalysisSettings(
            apiKey = "sk-deep",
            model = meta.id,
            provider = TextProviderCatalog.deepseek.id,
            thinking = thinking,
        )
        return OpenAiCompatibleEngine(TextEngineConfig.of(settings, listOf(meta)), client)
    }

    private fun engineOf(thinking: Boolean): TextEngineConfig {
        val settings = AnalysisSettings(
            apiKey = "sk-deep",
            model = reasoner.id,
            provider = TextProviderCatalog.deepseek.id,
            thinking = thinking,
        )
        return TextEngineConfig.of(settings, listOf(reasoner))
    }

    private fun bodyOf(request: Request): JsonObject {
        val buffer = Buffer()
        request.body?.writeTo(buffer)
        return Json.parseToJsonElement(buffer.readUtf8()).jsonObject
    }

    private fun userContent(request: Request): String =
        bodyOf(request)["messages"]!!.jsonArray[1].jsonObject["content"]!!.jsonPrimitive.content
}

/** 顶掉网络的拦截器：每条请求原样记下来，回一份能解析的助手消息。 */
private class Recorder : Interceptor {
    val requests = mutableListOf<Request>()

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        requests += request
        return Response.Builder()
            .request(request)
            .protocol(Protocol.HTTP_1_1)
            .code(200)
            .message("OK")
            .body(Reply.toResponseBody("application/json".toMediaType()))
            .build()
    }

    private companion object {
        const val Reply = """{"choices": [{"message": {"role": "assistant", "content": "练习"}}]}"""
    }
}
