package com.parley.speaker.data.analysis

import android.util.Log
import com.parley.speaker.domain.AnalysisSettings
import com.parley.speaker.domain.AuthKind
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

private val JsonMedia = "application/json".toMediaType()

private const val SystemRole =
    "你是英语口语教练，只输出调用方要求的 JSON，不输出解释、代码块标记或多余字段。"

internal val defaultEngineClient: OkHttpClient = OkHttpClient.Builder()
    .connectTimeout(8, TimeUnit.SECONDS)
    .readTimeout(45, TimeUnit.SECONDS)
    .build()

/**
 * 一次文本请求需要的全部参数。供应商差异只落在端点、鉴权与请求体的可选段上，
 * 管道里没有按供应商名字分叉的代码。
 */
data class TextEngineConfig(
    val provider: TextProvider,
    val model: String,
    val endpoint: String,
    val auth: AuthSpec,
    val apiKey: String,
    val thinking: Boolean = false,
    val headers: Map<String, String> = provider.headers,
) {
    val chatUrl: String get() = appendPath(endpoint, "chat/completions")

    val ready: Boolean get() = apiKey.isNotBlank() && endpoint.isNotBlank()

    val modelMeta: TextModel? get() = TextProviderCatalog.modelOf(provider, model)

    companion object {
        fun of(
            provider: TextProvider,
            model: String,
            apiKey: String,
            thinking: Boolean = false,
        ): TextEngineConfig = TextEngineConfig(
            provider = provider,
            model = model.ifBlank { provider.models.firstOrNull()?.id.orEmpty() },
            endpoint = provider.endpoint,
            auth = provider.auth,
            apiKey = apiKey,
            thinking = thinking,
        )

        /** 配置中心的设置是唯一来源：供应商、端点、鉴权、思考参数都从这里进管道。 */
        fun of(settings: AnalysisSettings): TextEngineConfig {
            val provider = TextProviderCatalog.of(settings.provider) ?: TextProviderCatalog.gemini
            return TextEngineConfig(
                provider = provider,
                model = settings.model.ifBlank { provider.models.firstOrNull()?.id.orEmpty() },
                endpoint = settings.endpoint.ifBlank { provider.endpoint },
                auth = settings.authSpec(provider),
                apiKey = settings.apiKey,
                thinking = settings.thinking,
            )
        }
    }
}

/** 自定义端点手填的鉴权压过供应商默认值，没填的那一段继续用目录里的。 */
internal fun AnalysisSettings.authSpec(provider: TextProvider): AuthSpec = when (authKind) {
    null -> provider.auth
    AuthKind.Bearer -> AuthSpec.bearer()
    AuthKind.Header -> AuthSpec.header(
        name = authName.ifBlank { provider.auth.name },
        prefix = authPrefix,
    )
    AuthKind.Query -> AuthSpec.query(authName.ifBlank { "key" })
}

/** 请求体：JSON 输出、温度、思考段都由模型元数据与配置决定，不写死在某一家身上。 */
internal fun TextEngineConfig.request(
    prompt: String,
    structured: Boolean,
    maxTokens: Int = 0,
): Request? {
    val url = signed(chatUrl) ?: return null
    val body = buildJsonObject {
        put("model", model)
        putJsonArray("messages") {
            add(buildJsonObject {
                put("role", "system")
                put("content", SystemRole)
            })
            add(buildJsonObject {
                put("role", "user")
                put("content", prompt)
            })
        }
        put("temperature", 0.3)
        put("stream", false)
        if (maxTokens > 0) put("max_tokens", maxTokens)
        if (structured && provider.jsonMode) {
            putJsonObject("response_format") { put("type", "json_object") }
        }
        if (thinking && modelMeta?.thinking != false) {
            putJsonObject("thinking") { put("type", "enabled") }
            modelMeta?.effort?.let { put("reasoning_effort", it) }
        }
    }
    return builder(url).post(body.toString().toRequestBody(JsonMedia)).build()
}

/** 探针要的最小请求：能取模型清单就只要一次清单，端点是完整 chat 地址时改发一条一 token 的生成。 */
internal fun TextEngineConfig.probeRequest(): Request? {
    val fullChat = endpoint.trimEnd('/').endsWith("/chat/completions")
    val url = signed(if (fullChat) chatUrl else appendPath(endpoint, "models")) ?: return null
    if (!fullChat) return builder(url).get().build()
    val body = buildJsonObject {
        put("model", model)
        putJsonArray("messages") {
            add(buildJsonObject {
                put("role", "user")
                put("content", "hi")
            })
        }
        put("max_tokens", 1)
        put("stream", false)
    }
    return builder(url).post(body.toString().toRequestBody(JsonMedia)).build()
}

private fun TextEngineConfig.builder(url: HttpUrl): Request.Builder {
    val builder = Request.Builder().url(url)
    headers.forEach { (name, value) -> builder.header(name, value) }
    authHeader()?.let { (name, value) -> builder.header(name, value) }
    return builder
}

/** Query 鉴权把密钥拼在地址上，其余两种走头部。 */
internal fun TextEngineConfig.signed(raw: String): HttpUrl? {
    val url = raw.toHttpUrlOrNull() ?: return null
    if (auth.kind != AuthKind.Query) return url
    return url.newBuilder().addQueryParameter(auth.name.ifBlank { "key" }, apiKey).build()
}

internal fun TextEngineConfig.authHeader(): Pair<String, String>? = when (auth.kind) {
    AuthKind.Bearer -> (auth.name.ifBlank { "Authorization" }) to (auth.prefix.ifBlank { "Bearer " } + apiKey)
    AuthKind.Header -> (auth.name.ifBlank { "x-api-key" }) to (auth.prefix + apiKey)
    AuthKind.Query -> null
}

/**
 * 一套 OpenAI 兼容的文本管道，六家预置供应商与自定义端点共用。
 * 分析、译文与会话名都从这里出，供应商差异只存在配置里。
 */
class OpenAiCompatibleEngine(
    private val config: TextEngineConfig,
    private val client: OkHttpClient = defaultEngineClient,
) : TurnAnalyzer {

    override suspend fun analyze(text: String, turnId: String): TurnAnalysis? = withContext(Dispatchers.IO) {
        if (!config.ready || text.isBlank()) return@withContext null
        val request = config.request(analysisPrompt(text), structured = true) ?: return@withContext null
        val content = post(request)?.let { replyText(it) } ?: return@withContext null
        parseAnalysisReport(content, text, turnId)
    }

    override suspend fun translate(text: String): String? = withContext(Dispatchers.IO) {
        if (!config.ready || text.isBlank()) return@withContext null
        val request = config.request(translatePrompt(text), structured = false) ?: return@withContext null
        post(request)?.let { replyText(it) }?.trim()?.takeIf { it.isNotEmpty() }
    }

    override suspend fun name(transcript: String): String? = withContext(Dispatchers.IO) {
        if (!config.ready || transcript.isBlank()) return@withContext null
        val request = config.request(sessionNamePrompt(transcript), structured = false) ?: return@withContext null
        post(request)
            ?.let { replyText(it) }
            ?.trim()
            ?.trim('"', '「', '」', '。', '\n')
            ?.takeIf { it.isNotEmpty() }
            ?.take(MaxNameChars)
    }

    private fun post(request: Request): String? = try {
        client.newCall(request).execute().use { response ->
            val body = response.body.string()
            if (response.isSuccessful) {
                body
            } else {
                Log.w(TAG, "${config.provider.id} ${response.code}: ${body.take(200)}")
                null
            }
        }
    } catch (e: Exception) {
        Log.w(TAG, "${config.provider.id} request failed: ${e.message}")
        null
    }

    private companion object {
        const val TAG = "TurnAnalyzer"
        const val MaxNameChars = 12
    }
}

/** 组装入口：配置中心的文本分析设置直接换成一台引擎。 */
object TurnAnalyzerFactory {
    fun of(settings: AnalysisSettings): TurnAnalyzer =
        OpenAiCompatibleEngine(TextEngineConfig.of(settings))
}

/**
 * 分析提示词。vocab 每条字段与 VocabCard 一一对应，highlight 必须能在 example 里原样找到，
 * 否则词卡上的陶土高亮标不出来。
 */
internal fun analysisPrompt(text: String): String = """
    学生说了一句话，可能中英混说。只输出一个 JSON 对象。

    硬性要求：
    1. vocab 至少一条、最多三条，取学生这句话里真实出现的表达，或它对应的地道英文说法；不许留空数组。
    2. 学生用中文说的部分，把最自然的英文说法收进 vocab。
    3. 每条 highlight 必须是同一条 example 的子串，逐字一致。
    4. phonetic 写在两个斜杠之间，meaning 用中文。

    {"score": 0 到 100 的整数,
     "note": "一句中文点评，指出最关键的一处问题，不超过二十字",
     "idiomatic": {"text": "母语者日常会这么说的英文", "gloss": "对应中文"},
     "advanced": {"text": "更讲究的一种英文说法", "gloss": "对应中文"},
     "vocab": [{"word": "地道搭配或生词", "phonetic": "/音标/", "meaning": "中文释义",
                "example": "含该搭配的英文例句", "highlight": "example 里要突出的片段"}]}

    学生原句：$text
""".trimIndent()

internal fun translatePrompt(text: String): String = """
    把下面这句话译成自然的中文，只输出译文，不要引号，不要解释：
    $text
""".trimIndent()

/** 取名只要一个短语，材料是整段对话，不走结构化解析。 */
internal fun sessionNamePrompt(transcript: String): String = """
    给下面这段英语口语练习取一个能指认内容的中文短语，不超过十个字，只输出短语本身，不要引号，不要解释：
    $transcript
""".trimIndent()
