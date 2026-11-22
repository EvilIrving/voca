package com.parley.speaker.data.analysis

import com.parley.speaker.data.db.ModelCatalogDao
import com.parley.speaker.data.db.ModelCatalogRow
import java.security.MessageDigest
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/**
 * 每家供应商的模型清单缓存。清单连同拉取时间与密钥指纹落库，断网重开仍读得到上一次的结果。
 * 拉取失败保留上一次清单、不清空；没有密钥或密钥换了就不再沿用旧密钥下的清单。
 */
class ModelCatalogStore(
    private val dao: ModelCatalogDao,
    private val fetcher: ModelCatalogFetcher = ModelCatalogFetcher(),
    private val now: () -> Long = System::currentTimeMillis,
) {

    /** 界面展开一家供应商时读的清单：发布日倒序、每家八条。 */
    suspend fun models(providerId: String): List<TextModel> =
        ModelCatalog.visible(entryOf(providerId)?.models.orEmpty())

    /** 装配请求时按 id 取元数据，越出展示截断的模型也能查到。 */
    suspend fun model(providerId: String, modelId: String): TextModel? =
        entryOf(providerId)?.models?.firstOrNull { it.id == modelId }

    /** 上一次拉取成功的时间，没成功过返回 null。 */
    suspend fun fetchedAt(providerId: String): Long? = entryOf(providerId)?.fetchedAt

    /**
     * 拉一次清单并写回缓存。密钥为空就地清掉该家的清单；
     * 拉取失败保留旧清单，但换了密钥之后的那次失败不能再拿旧密钥的清单顶上。
     */
    suspend fun refresh(
        provider: TextProvider,
        apiKey: String,
        endpoint: String = provider.endpoint,
        auth: AuthSpec = provider.auth,
    ): List<TextModel> {
        if (apiKey.isBlank() || endpoint.isBlank()) {
            clear(provider.id)
            return emptyList()
        }
        val tag = keyTag(apiKey)
        val previous = entryOf(provider.id)
        val sameKey = previous?.takeIf { it.keyTag == tag }
        val config = TextEngineConfig(
            provider = provider,
            model = "",
            endpoint = endpoint,
            auth = auth,
            apiKey = apiKey,
        )
        val live = fetcher.models(config)
        val merged = live?.let { models ->
            val key = TextProviderCatalog.catalogKeyOf(provider.id)
            val directory = key?.let { fetcher.directory(it) }.orEmpty()
            ModelCatalog.merged(ModelCatalog.available(models), directory)
        }
        if (merged == null) {
            if (previous != null && sameKey == null) clear(provider.id)
            return ModelCatalog.visible(sameKey?.models.orEmpty())
        }
        val ordered = ModelCatalog.ordered(merged)
        dao.put(
            ModelCatalogRow(
                providerId = provider.id,
                modelsJson = json.encodeToString(Serializer, ordered),
                fetchedAt = now(),
                keyTag = tag,
            ),
        )
        return ModelCatalog.visible(ordered)
    }

    suspend fun clear(providerId: String) = dao.clear(providerId)

    private suspend fun entryOf(providerId: String): Entry? = dao.byProvider(providerId)?.let { row ->
        val models = runCatching { json.decodeFromString(Serializer, row.modelsJson) }.getOrNull()
            ?: return null
        Entry(models, row.fetchedAt, row.keyTag)
    }

    private data class Entry(val models: List<TextModel>, val fetchedAt: Long, val keyTag: String)

    private companion object {
        val Serializer = ListSerializer(TextModel.serializer())
        val json = Json { ignoreUnknownKeys = true }

        /** 只留指纹，密钥本身不进库。 */
        fun keyTag(apiKey: String): String = MessageDigest.getInstance("SHA-256")
            .digest(apiKey.toByteArray())
            .take(8)
            .joinToString("") { "%02x".format(it) }
    }
}
