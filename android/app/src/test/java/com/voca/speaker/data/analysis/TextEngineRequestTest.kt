package com.voca.speaker.data.analysis

import com.voca.speaker.domain.AuthKind
import com.voca.speaker.domain.analysisOf
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Request
import okio.Buffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 请求装配的断言：预设、端点、三种鉴权、探针判定与思考参数。
 * 模型元数据一律由用例自己给，目录不再带种子。全程不发请求，只看拼出来的 Request。
 */
class TextEngineRequestTest {

    private val reasoning = TextModel(
        id = "deepseek-reasoner",
        thinking = true,
        defaultEffort = "high",
        effortLevels = listOf("low", "medium", "high"),
    )

    private val plain = TextModel(id = "deepseek-chat")

    @Test
    fun `目录只带端点与鉴权，不带模型种子`() {
        val provider = TextProviderCatalog.deepseek

        assertEquals("https://api.deepseek.com/v1", provider.endpoint)
        assertEquals("Bearer ", provider.auth.prefix)
        assertTrue("模型清单只从缓存来", TextProviderCatalog.all.all { it.models.isEmpty() })
    }

    @Test
    fun `选了哪家就走哪家，与模型 id 属于谁的清单无关`() {
        val settings = analysisOf(
            apiKey = "sk-deep",
            model = "gemini-flash-latest",
            provider = TextProviderCatalog.deepseek.id,
        )
        val config = TextEngineConfig.of(settings)

        assertEquals("deepseek", config.provider.id)
        assertEquals("https://api.deepseek.com/v1/chat/completions", config.chatUrl)
        assertEquals("Bearer sk-deep", config.authHeader()?.second)
    }

    @Test
    fun `清单里的元数据按模型 id 挂到请求配置上`() {
        val settings = analysisOf(
            apiKey = "sk-deep",
            model = "deepseek-reasoner",
            provider = TextProviderCatalog.deepseek.id,
            thinking = true,
        )
        val config = TextEngineConfig.of(settings, listOf(reasoning, plain))

        assertEquals(reasoning, config.modelMeta)
        val body = Json.parseToJsonElement(bodyOf(config.request("hi", structured = true)!!)).jsonObject
        assertEquals("high", body["reasoning_effort"]!!.jsonPrimitive.content)
    }

    @Test
    fun `会话名的请求也走配置里的端点与鉴权`() {
        val settings = analysisOf(
            apiKey = "sk-kimi",
            model = "kimi-k2",
            provider = TextProviderCatalog.kimi.id,
        )
        val config = TextEngineConfig.of(settings)
        val request = config.request(sessionNamePrompt("学生：hi\n助教：hello"), structured = false)!!

        assertEquals("https://api.moonshot.cn/v1/chat/completions", request.url.toString())
        assertEquals("Bearer sk-kimi", request.header("Authorization"))
        val body = Json.parseToJsonElement(bodyOf(request)).jsonObject
        assertEquals("kimi-k2", body["model"]!!.jsonPrimitive.content)
        assertNull("取名只需要一段自由文本，不开 JSON 模式", body["response_format"])
    }

    @Test
    fun `三种鉴权各落各处`() {
        val bearer = TextEngineConfig.of(TextProviderCatalog.deepseek, "deepseek-chat", "sk-1")
        val header = TextEngineConfig.of(TextProviderCatalog.anthropic, "claude-sonnet-4-5", "sk-2")
        val custom = TextEngineConfig(
            provider = TextProviderCatalog.custom,
            model = "local",
            endpoint = "https://llm.example.com/v1",
            auth = AuthSpec.query("api_key"),
            apiKey = "sk-3",
        )

        assertEquals("Bearer sk-1", bearer.authHeader()?.second)
        assertEquals("x-api-key", header.authHeader()?.first)
        assertEquals("sk-2", header.authHeader()?.second)
        assertNull(custom.authHeader())
        assertEquals("sk-3", custom.signed(custom.chatUrl)?.queryParameter("api_key"))
        assertNull(bearer.signed(bearer.chatUrl)?.queryParameter("api_key"))
    }

    @Test
    fun `Anthropic 的版本头随请求带上`() {
        val config = TextEngineConfig.of(TextProviderCatalog.anthropic, "claude-sonnet-4-5", "sk-2")
        val request = config.request("hi", structured = true)!!

        assertEquals("2023-06-01", request.header("anthropic-version"))
        assertEquals("sk-2", request.header("x-api-key"))
        assertFalse("这家不认 response_format", bodyOf(request).contains("response_format"))
    }

    @Test
    fun `结构化输出按供应商能力加`() {
        val deepseek = TextEngineConfig.of(TextProviderCatalog.deepseek, "deepseek-chat", "sk-1")
        val body = Json.parseToJsonElement(bodyOf(deepseek.request("hi", structured = true)!!)).jsonObject

        assertEquals("deepseek-chat", body["model"]!!.jsonPrimitive.content)
        assertEquals("json_object", body["response_format"]!!.jsonObject["type"]!!.jsonPrimitive.content)
    }

    @Test
    fun `思考参数只在模型支持且开关打开时才发`() {
        val on = TextEngineConfig.of(
            TextProviderCatalog.deepseek,
            "deepseek-reasoner",
            "sk-1",
            thinking = true,
            meta = reasoning,
        )
        val chat = TextEngineConfig.of(
            TextProviderCatalog.deepseek,
            "deepseek-chat",
            "sk-1",
            thinking = true,
            meta = plain,
        )
        val off = TextEngineConfig.of(
            TextProviderCatalog.deepseek,
            "deepseek-reasoner",
            "sk-1",
            thinking = false,
            meta = reasoning,
        )

        assertTrue(bodyOf(on.request("hi", structured = true)!!).contains("thinking"))
        assertFalse(bodyOf(chat.request("hi", structured = true)!!).contains("thinking"))
        assertFalse(bodyOf(off.request("hi", structured = true)!!).contains("thinking"))
    }

    @Test
    fun `没有元数据时退回开关式思考，不带档位`() {
        val config = TextEngineConfig.of(TextProviderCatalog.deepseek, "deepseek-chat", "sk-1", thinking = true)
        val body = bodyOf(config.request("hi", structured = true)!!)

        assertTrue(body.contains("thinking"))
        assertFalse("清单没到位就不猜档位", body.contains("reasoning_effort"))
    }

    @Test
    fun `自定义端点的完整地址不再拼二级路径`() {
        val config = TextEngineConfig(
            provider = TextProviderCatalog.custom,
            model = "local",
            endpoint = "https://llm.example.com/v1/chat/completions",
            auth = AuthSpec.bearer(),
            apiKey = "sk-3",
        )

        assertEquals("https://llm.example.com/v1/chat/completions", config.chatUrl)
        assertEquals("https://llm.example.com/v1/models", modelsUrl(config.endpoint))
        assertFalse(bodyOf(config.request("hi", structured = true)!!).contains("max_tokens"))
    }

    @Test
    fun `设置里的自定义鉴权压过目录默认值`() {
        val settings = analysisOf(
            apiKey = "sk-9",
            model = "local",
            provider = TextProviderCatalog.CustomId,
            endpoint = "https://llm.example.com/v1",
            authKind = AuthKind.Header,
            authName = "X-Auth",
            authPrefix = "Token ",
        )
        val config = TextEngineConfig.of(settings)

        assertEquals("https://llm.example.com/v1/chat/completions", config.chatUrl)
        assertEquals("X-Auth" to "Token sk-9", config.authHeader())
    }

    @Test
    fun `没填自定义项时回落到目录默认`() {
        val settings = analysisOf(apiKey = "sk-gem", model = "gemini-flash-latest")
        val config = TextEngineConfig.of(settings)

        assertEquals("gemini", config.provider.id)
        assertEquals("https://generativelanguage.googleapis.com/v1beta/openai/chat/completions", config.chatUrl)
        assertEquals("Bearer sk-gem", config.authHeader()?.second)
    }

    private fun bodyOf(request: Request): String {
        val buffer = Buffer()
        request.body?.writeTo(buffer)
        return buffer.readUtf8()
    }
}
