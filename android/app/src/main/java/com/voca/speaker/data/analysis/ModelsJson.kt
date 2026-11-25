package com.voca.speaker.data.analysis

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * models.json 与各家 /models 清单的解析。三种外壳都认：data 数组、models 数组、按 id 作键的对象。
 * 字段名各厂不一致，按别名依次尝试；类型不对一律当没有，不抛异常。
 * Gemini 的兼容端点在 id 上带 models/ 前缀，进来就摘掉，与目录按同一个 id 对齐。
 */
object ModelsJson {

    private val wire = Json { ignoreUnknownKeys = true; isLenient = true }

    fun parse(raw: String): List<TextModel> {
        val root = runCatching { wire.parseToJsonElement(raw) }.getOrNull() ?: return emptyList()
        return entries(root).mapNotNull { (id, entry) -> model(id, entry) }
    }

    private fun entries(root: JsonElement): List<Pair<String, JsonObject>> =
        when (root) {
            is JsonArray -> arrayEntries(root)
            is JsonObject -> when (val boxed = root["data"] ?: root["models"]) {
                is JsonArray -> arrayEntries(boxed)
                is JsonObject -> boxed.entries.mapNotNull { (id, value) ->
                    (value as? JsonObject)?.let { id to it }
                }
                null -> mapEntries(root)
                else -> emptyList()
            }
            else -> emptyList()
        }

    private fun arrayEntries(array: JsonArray): List<Pair<String, JsonObject>> =
        array.mapNotNull { element ->
            val entry = element as? JsonObject ?: return@mapNotNull null
            entry.text("id")?.let { it to entry }
        }

    /** 按 id 作键的对象只在每个值都是对象时才算模型清单，否则是别的东西。 */
    private fun mapEntries(root: JsonObject): List<Pair<String, JsonObject>> {
        if (root.isEmpty() || root.values.any { it !is JsonObject }) return emptyList()
        return root.entries.map { (id, value) -> id to (value as JsonObject) }
    }

    /** 一条模型条目 → 元数据。id 在调用方给的和条目自带的里取，tools 目录用键当 id。 */
    internal fun model(id: String, entry: JsonObject): TextModel? {
        val modelId = normalize(id.ifBlank { entry.text("id").orEmpty() })
        if (modelId.isBlank()) return null
        val limit = entry.obj("limit")
        val top = entry.obj("top_provider")
        return TextModel(
            id = modelId,
            context = entry.int("context_window")
                ?: entry.int("context_length")
                ?: entry.int("context")
                ?: entry.int("input_token_limit")
                ?: entry.int("max_context_length")
                ?: limit?.int("context")
                ?: 0,
            maxOutput = entry.int("max_output_tokens")
                ?: entry.int("max_tokens")
                ?: entry.int("max_completion_tokens")
                ?: entry.int("output_token_limit")
                ?: limit?.int("output")
                ?: top?.int("max_completion_tokens")
                ?: 0,
            thinking = entry.thinking() ?: false,
            defaultEffort = entry.defaultEffort(),
            effortLevels = entry.effortLevels(),
            releaseDate = entry.text("release_date")
                ?: entry.text("released")
                ?: entry.text("releaseDate"),
        )
    }

    fun normalize(id: String): String = id.trim().removePrefix("models/")

    private fun JsonObject.defaultEffort(): String? =
        text("reasoning_effort")
            ?: obj("effort")?.text("default")
            ?: obj("reasoning")?.text("effort")
            ?: obj("thinking")?.text("effort")

    /** 档位表各家挂在 effort、reasoning 或顶层，字段名 supported_levels / effort_levels / levels 都见过。 */
    private fun JsonObject.effortLevels(): List<String> {
        val holders = listOf(this, obj("effort"), obj("reasoning"), obj("thinking")).filterNotNull()
        holders.forEach { holder ->
            listOf("supported_levels", "effort_levels", "levels", "supported_efforts").forEach { key ->
                (holder[key] as? JsonArray)?.strings()?.takeIf { it.isNotEmpty() }?.let { return it }
            }
        }
        return emptyList()
    }

    private fun JsonArray.strings(): List<String> =
        mapNotNull { (it as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf { text -> text.isNotEmpty() } }

    private fun JsonObject.thinking(): Boolean? =
        flag("reasoning")
            ?: flag("supports_reasoning")
            ?: flag("thinking")
            ?: obj("thinking")?.let { it.flag("enabled") ?: true }
            ?: obj("reasoning")?.let { it.flag("enabled") ?: true }
            ?: obj("capabilities")?.flag("reasoning")
            ?: effortLevels().takeIf { it.isNotEmpty() }?.let { true }
            ?: parameters()?.let { it.contains("reasoning") || it.contains("include_reasoning") }

    private fun JsonObject.parameters(): List<String>? =
        (this["supported_parameters"] as? JsonArray)?.strings()

    private fun JsonObject.obj(key: String): JsonObject? = this[key] as? JsonObject

    private fun JsonObject.text(key: String): String? =
        (this[key] as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }

    private fun JsonObject.int(key: String): Int? =
        (this[key] as? JsonPrimitive)?.contentOrNull?.trim()?.toIntOrNull()

    private fun JsonObject.flag(key: String): Boolean? = when (val value = this[key]) {
        is JsonPrimitive -> when (value.contentOrNull?.lowercase()) {
            "true", "1", "yes" -> true
            "false", "0", "no" -> false
            else -> null
        }
        else -> null
    }
}
