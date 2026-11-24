package com.parley.speaker.data.analysis

import android.util.Log
import com.parley.speaker.domain.AnalysisCredentials
import com.parley.speaker.domain.AnalysisSettings
import com.parley.speaker.domain.AuthKind
import com.parley.speaker.domain.MemoryMaxChars
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

/** 估算窗口时一个 token 折两字符，中英混说偏保守，宁可少裁不裁过头。 */
internal const val CharsPerToken = 2

/** 一份分析报告加三条词卡不到一千 token，译文与取名更短，四千已绦绦有余。 */
private const val AnalysisMaxOutput = 4_096

private const val SystemRole =
    "你是英语口语教练，只输出调用方要求的 JSON，不输出解释、代码块标记或多余字段。"

internal val defaultEngineClient: OkHttpClient = OkHttpClient.Builder()
    .connectTimeout(8, TimeUnit.SECONDS)
    .readTimeout(45, TimeUnit.SECONDS)
    .build()

/**
 * 一次文本请求需要的全部参数。供应商差异只落在端点、鉴权与请求体的可选段上，
 * 管道里没有按供应商名字分叉的代码。modelMeta 是清单缓存里这一条模型的元数据，
 * 没带就按未知处理：思考参数退成开关式，输出上限与输入窗口都不裁。
 */
data class TextEngineConfig(
    val provider: TextProvider,
    val model: String,
    val endpoint: String,
    val auth: AuthSpec,
    val apiKey: String,
    val thinking: Boolean = false,
    val headers: Map<String, String> = provider.headers,
    val modelMeta: TextModel? = null,
) {
    val chatUrl: String get() = appendPath(endpoint, "chat/completions")

    /** 模型名空着不是一条能发的请求，边界只在这里判一次。 */
    val ready: Boolean get() = apiKey.isNotBlank() && endpoint.isNotBlank() && model.isNotBlank()

    companion object {
        fun of(
            provider: TextProvider,
            model: String,
            apiKey: String,
            thinking: Boolean = false,
            meta: TextModel? = null,
        ): TextEngineConfig = TextEngineConfig(
            provider = provider,
            model = model.ifBlank { meta?.id.orEmpty() },
            endpoint = provider.endpoint,
            auth = provider.auth,
            apiKey = apiKey,
            thinking = thinking,
            modelMeta = meta,
        )

        /** 设置页的设置是唯一来源：供应商、端点、鉴权、思考参数都从这里进管道。 */
        fun of(settings: AnalysisSettings, models: List<TextModel> = emptyList()): TextEngineConfig {
            val provider = TextProviderCatalog.of(settings.provider) ?: TextProviderCatalog.gemini
            val model = settings.model.ifBlank { models.firstOrNull()?.id.orEmpty() }
            return TextEngineConfig(
                provider = provider,
                model = model,
                endpoint = settings.endpoint.ifBlank { provider.endpoint },
                auth = settings.authSpec(provider),
                apiKey = settings.apiKey,
                thinking = settings.thinking,
                modelMeta = models.firstOrNull { it.id == model },
            )
        }
    }
}

/** 自定义端点手填的鉴权压过供应商默认值，没填的那一段继续用目录里的。 */
internal fun AnalysisSettings.authSpec(provider: TextProvider): AuthSpec = active.authSpec(provider)

/** 同一套判定按供应商名下的那一格来一次，拉清单时不必先把设置切成那一家。 */
internal fun AnalysisCredentials.authSpec(provider: TextProvider): AuthSpec = when (authKind) {
    null -> provider.auth
    AuthKind.Bearer -> AuthSpec.bearer()
    AuthKind.Header -> AuthSpec.header(
        name = authName.ifBlank { provider.auth.name },
        prefix = authPrefix,
    )
    AuthKind.Query -> AuthSpec.query(authName.ifBlank { "key" })
}

/**
 * 请求体：JSON 输出、温度、思考段、输出上限与输入窗口都由模型元数据与配置决定，不写死在某一家身上。
 * 输出上限取清单里的 maxOutput，输入按上下文窗口裁剪，档位取清单自报的默认值。
 */
internal fun TextEngineConfig.request(prompt: String, structured: Boolean): Request? {
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
                put("content", promptWithinContext(prompt))
            })
        }
        put("temperature", 0.3)
        put("stream", false)
        if (maxOutputTokens > 0) put("max_tokens", maxOutputTokens)
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

/** 输出上限只认清单里的 maxOutput，没同步到就不带，交给服务端默认值。 */
internal val TextEngineConfig.maxOutputTokens: Int
    get() = modelMeta?.maxOutput?.takeIf { it > 0 }?.coerceAtMost(AnalysisMaxOutput) ?: 0

/**
 * 输入按窗口裁剪：窗口先扣掉输出预留，再按两字符一 token 保守折算成字符数。
 * 窗口未知就不裁；分析与译文的提示只有几百字符，会撞上限的只有整段对话取名的材料。
 */
internal fun TextEngineConfig.promptWithinContext(prompt: String): String {
    val context = modelMeta?.context?.takeIf { it > 0 } ?: return prompt
    val budget = (context - maxOutputTokens).coerceAtLeast(context / 2) * CharsPerToken
    return if (prompt.length <= budget) prompt else prompt.take(budget)
}

/** 清单请求：带该供应商自己的密钥取 GET /models，没有密钥时拼不出签过名的地址。 */
internal fun TextEngineConfig.modelsRequest(): Request? {
    val url = signed(modelsUrl(endpoint)) ?: return null
    return builder(url).get().build()
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

    override suspend fun memorize(memory: String, material: String): String? = withContext(Dispatchers.IO) {
        if (!config.ready || material.isBlank()) return@withContext null
        val request = config.request(memoryPrompt(memory, material), structured = false) ?: return@withContext null
        // 收不收由 admitMemory 一处定，这里只把包装、围栏与引号拆掉，不自己判长短。
        post(request)
            ?.let { replyText(it) }
            ?.let(::parseMemory)
            ?.trim('"', '「', '」', '\n', '`')
            ?.takeIf { it.isNotEmpty() }
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

/**
 * 组装入口：设置页的文本分析设置加该模型在清单缓存里的元数据换成一台引擎。
 * 清单可以不给，那样请求只按设置的开关发思考参数，不带上限与档位。
 */
object TurnAnalyzerFactory {
    fun of(settings: AnalysisSettings, models: List<TextModel> = emptyList()): TurnAnalyzer =
        OpenAiCompatibleEngine(TextEngineConfig.of(settings, models))
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
    4. phonetic 写在两个斜杠之间，meaning 用中文，只写释义本身，不加括号注释，不写语体、场景与用法说明。

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

/**
 * 记忆维护的提示词。这一段是产品机制的一部分，不跟角色提示词走、不进设置页：
 * 记忆怎么合并、什么该留、留多长都由这里定；设置页能改的是角色提示词与记忆正文本身。
 */
internal fun memoryPrompt(memory: String, material: String): String = """
    你在维护一场英语口语练习的记忆。下面是现在的记忆与刚刚发生的一轮对话。
    把这一轮的新内容并进记忆，只输出改写后的记忆正文。

    硬性要求：
    1. 输出的是整段记忆，不是追加的片段；不写标题、编号、条目、时间戳，也不写修改说明。
    2. 被新内容推翻的说法直接改写或删掉，前后矛盾时以最近一次为准。
    3. 只留后面还用得上的事：身份、水平、想练的方向、提过的人与地点、正在准备的事、卡住过的说法。
    4. 用连贯的中文叙述，不分行，不超过 $MemoryMaxChars 个字符。
    5. 只给正文本身，不要 JSON、不要字段名、不要引号，也不要代码块围栏。

    现在的记忆：
    $memory

    刚刚这一轮：
    $material
""".trimIndent()
