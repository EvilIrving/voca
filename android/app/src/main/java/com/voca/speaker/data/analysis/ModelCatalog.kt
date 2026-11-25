package com.voca.speaker.data.analysis

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

/**
 * 模型清单的可用集合、目录补齐与展示顺序。全部是无副作用的纯函数，
 * 网络在 [ModelCatalogFetcher]，落库在 [ModelCatalogStore]。
 */
object ModelCatalog {

    /** 每家展示最近八个。 */
    const val PerProvider = 8

    private val catalogWire = Json { ignoreUnknownKeys = true; isLenient = true }

    /** 非对话模型一律不进清单：清单只服务于语法修正、地道建议与生词归集。 */
    private val NonChat = listOf(
        "embed",
        "tts",
        "whisper",
        "transcribe",
        "realtime",
        "image",
        "imagen",
        "audio",
        "dall-e",
        "moderation",
        "rerank",
        "ocr",
        "sora",
        "veo",
    )

    fun isChatModel(id: String): Boolean {
        val name = id.lowercase()
        return NonChat.none { name.contains(it) }
    }

    /** 一家供应商 /models 的返回体 → 可用集合。可用与否只看这份返回，目录缺条目不隐藏模型。 */
    fun available(raw: String): List<TextModel> = available(ModelsJson.parse(raw))

    fun available(models: List<TextModel>): List<TextModel> =
        models.filter { isChatModel(it.id) }.distinctBy { it.id }

    /**
     * models.dev 的 api.json → key 那一家供应商的目录清单，只用来补字段。
     * 只取一家：目录里同一个 id 被好几家转售，展平之后按 id 去重会让别家的值先到先得。
     */
    fun directory(raw: String, key: String): List<TextModel> {
        val root = runCatching { catalogWire.parseToJsonElement(raw) }.getOrNull() as? JsonObject
            ?: return emptyList()
        val models = (root[key] as? JsonObject)?.get("models") as? JsonObject ?: return emptyList()
        return models.entries.mapNotNull { (id, entry) -> (entry as? JsonObject)?.let { ModelsJson.model(id, it) } }
    }

    /** 以接口清单为准，目录只补空缺的窗口、输出上限、思考位、档位与发布日。 */
    fun merged(available: List<TextModel>, directory: List<TextModel>): List<TextModel> {
        if (directory.isEmpty()) return available
        val index = directory.associateBy { it.id }
        return available.map { live ->
            val ref = index[live.id] ?: return@map live
            live.copy(
                context = live.context.takeIf { it > 0 } ?: ref.context,
                maxOutput = live.maxOutput.takeIf { it > 0 } ?: ref.maxOutput,
                thinking = live.thinking || ref.thinking,
                defaultEffort = live.defaultEffort ?: ref.defaultEffort,
                effortLevels = live.effortLevels.ifEmpty { ref.effortLevels },
                releaseDate = live.releaseDate ?: ref.releaseDate,
            )
        }
    }

    /** 发布日新的在前，查不到日期的保留并按原序随后。 */
    fun ordered(models: List<TextModel>): List<TextModel> = models.sortedWith(
        compareBy<TextModel> { it.releaseDate == null }
            .thenByDescending { it.releaseDate.orEmpty() },
    )

    /** 截断只截有发布日的那一段，查不到日期的全部保留在后面。 */
    fun visible(models: List<TextModel>, limit: Int = PerProvider): List<TextModel> {
        val sorted = ordered(models)
        return sorted.filter { it.releaseDate != null }.take(limit) + sorted.filter { it.releaseDate == null }
    }

    /** 清单到位后当前选择不在清单里就落到最新的一个，空清单仍是空。 */
    fun pick(models: List<TextModel>, current: String): String =
        models.firstOrNull { it.id == current }?.id ?: models.firstOrNull()?.id.orEmpty()
}
