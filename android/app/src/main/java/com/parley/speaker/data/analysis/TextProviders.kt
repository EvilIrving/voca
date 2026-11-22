package com.parley.speaker.data.analysis

import com.parley.speaker.domain.AuthKind
import kotlinx.serialization.Serializable

/**
 * 鉴权在请求上的落点。目录给每个供应商定一份默认值，自定义端点可以三样都手填。
 */
data class AuthSpec(
    val kind: AuthKind,
    val name: String = "",
    val prefix: String = "",
) {
    companion object {
        fun bearer(): AuthSpec = AuthSpec(AuthKind.Bearer, "Authorization", "Bearer ")
        fun header(name: String, prefix: String = ""): AuthSpec = AuthSpec(AuthKind.Header, name, prefix)
        fun query(name: String): AuthSpec = AuthSpec(AuthKind.Query, name)
    }
}

/**
 * 一个模型的元数据。context 与 maxOutput 为 0 表示还没同步到真实值，界面据此显示待同步。
 * thinking 表示请求可以带思考参数，defaultEffort 是默认发出的 reasoning_effort 档位，
 * effortLevels 是接口自报的全部档位，releaseDate 取 ISO 日期、缺失表示目录里也查不到。
 */
@Serializable
data class TextModel(
    val id: String,
    val context: Int = 0,
    val maxOutput: Int = 0,
    val thinking: Boolean = false,
    val defaultEffort: String? = null,
    val effortLevels: List<String> = emptyList(),
    val releaseDate: String? = null,
) {
    /** 请求里要发的档位：接口给了默认值就用它，只给了档位表就取第一档。 */
    val effort: String? get() = defaultEffort ?: effortLevels.firstOrNull()
}

/**
 * 一个供应商的接入参数。endpoint 是基地址，请求地址由 [chatUrl] 补路径得到。
 * models 不再带出厂种子，界面从 [ModelCatalogStore] 的缓存填进来。
 */
data class TextProvider(
    val id: String,
    val label: String,
    val endpoint: String,
    val auth: AuthSpec,
    val models: List<TextModel> = emptyList(),
    val headers: Map<String, String> = emptyMap(),
    val jsonMode: Boolean = true,
    val custom: Boolean = false,
) {
    val chatUrl: String get() = appendPath(endpoint, "chat/completions")
    val modelsUrl: String get() = modelsUrl(endpoint)
}

/** 端点可能已经是完整的 chat 地址，重复补路径会拼出 chat/completions/chat/completions。 */
internal fun appendPath(base: String, path: String): String {
    val trimmed = base.trimEnd('/')
    return if (trimmed.endsWith("/$path")) trimmed else "$trimmed/$path"
}

/** 清单地址落在基地址上：手填成完整 chat 地址时先摘掉那一段，免得拼出 chat/completions/models。 */
internal fun modelsUrl(base: String): String =
    appendPath(base.trimEnd('/').removeSuffix("/chat/completions"), "models")

/**
 * 六家预置供应商加一个自定义端点。目录只管端点、鉴权与展示名，模型清单全部来自接口与缓存。
 */
object TextProviderCatalog {

    const val CustomId = "custom"
    const val GeminiId = "gemini"

    val deepseek = TextProvider(
        id = "deepseek",
        label = "DeepSeek",
        endpoint = "https://api.deepseek.com/v1",
        auth = AuthSpec.bearer(),
    )

    val gemini = TextProvider(
        id = GeminiId,
        label = "Gemini",
        endpoint = "https://generativelanguage.googleapis.com/v1beta/openai",
        auth = AuthSpec.bearer(),
    )

    val openai = TextProvider(
        id = "openai",
        label = "OpenAI",
        endpoint = "https://api.openai.com/v1",
        auth = AuthSpec.bearer(),
    )

    val anthropic = TextProvider(
        id = "anthropic",
        label = "Anthropic",
        endpoint = "https://api.anthropic.com/v1",
        auth = AuthSpec.header("x-api-key"),
        headers = mapOf("anthropic-version" to "2023-06-01"),
        jsonMode = false,
    )

    val kimi = TextProvider(
        id = "kimi",
        label = "Kimi",
        endpoint = "https://api.moonshot.cn/v1",
        auth = AuthSpec.bearer(),
    )

    val glm = TextProvider(
        id = "glm",
        label = "GLM",
        endpoint = "https://open.bigmodel.cn/api/paas/v4",
        auth = AuthSpec.bearer(),
    )

    val custom = TextProvider(
        id = CustomId,
        label = "自定义端点",
        endpoint = "",
        auth = AuthSpec.bearer(),
        custom = true,
    )

    val all: List<TextProvider> = listOf(deepseek, gemini, openai, anthropic, kimi, glm, custom)

    fun of(id: String): TextProvider? = all.firstOrNull { it.id == id }

    /**
     * 界面上的供应商 id 对 models.dev 目录里那一家的键。Kimi 指的是月之暗面（moonshotai），
     * GLM 指的是智谱（zhipuai），Gemini 挂在谷歌（google）名下；自定义端点没有目录。
     */
    private val catalogKeys: Map<String, String> = mapOf(
        deepseek.id to "deepseek",
        gemini.id to "google",
        openai.id to "openai",
        anthropic.id to "anthropic",
        kimi.id to "moonshotai",
        glm.id to "zhipuai",
    )

    /** 目录里查哪一家，自定义端点返回 null。 */
    fun catalogKeyOf(providerId: String): String? = catalogKeys[providerId]
}
