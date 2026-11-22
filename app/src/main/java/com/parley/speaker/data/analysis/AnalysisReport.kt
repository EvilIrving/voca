package com.parley.speaker.data.analysis

import com.parley.speaker.domain.Register
import com.parley.speaker.domain.VocabCard
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull

/**
 * 分析返回体的解析层：不联网、不打日志，输入是一段模型返回的 JSON 文本。
 * 提示词里的 schema 与这里的字段名是同一份约定，改一处必须改另一处。
 */
private val wire = Json { ignoreUnknownKeys = true; isLenient = true }

/** 从一家供应商的响应体里抠出正文。OpenAI 兼容层给 choices，Gemini 原生层给 candidates。 */
internal fun replyText(raw: String): String? {
    val root = runCatching { wire.parseToJsonElement(raw) }.getOrNull() as? JsonObject ?: return null
    val choice = root.array("choices")?.firstOrNull() as? JsonObject
    val message = choice?.obj("message")
    message?.content()?.let { return it }
    choice?.text("text")?.let { return it }
    val candidate = root.array("candidates")?.firstOrNull() as? JsonObject
    val part = candidate?.obj("content")?.array("parts")
        ?.firstOrNull { (it as? JsonObject)?.flag("thought") != true } as? JsonObject
    return part?.text("text")
}

/** content 可能是纯字符串，也可能是分段数组，两种都读。 */
private fun JsonObject.content(): String? = when (val value = this["content"]) {
    is JsonPrimitive -> value.contentOrNull
    is JsonArray -> value.mapNotNull { (it as? JsonObject)?.text("text") }
        .joinToString("")
        .takeIf { it.isNotBlank() }
    else -> null
}

/**
 * 解析分析报告。score 缺失就整份作废，其余字段读到多少算多少。
 */
fun parseAnalysisReport(raw: String, original: String, turnId: String): TurnAnalysis? {
    val report = objectOf(raw) ?: return null
    val score = report.score() ?: return null
    val rewrites = listOfNotNull(
        rewriteOf(
            Register.Idiomatic,
            report.obj("idiomatic")?.text("text"),
            report.obj("idiomatic")?.text("gloss"),
        ),
        rewriteOf(
            Register.Advanced,
            report.obj("advanced")?.text("text"),
            report.obj("advanced")?.text("gloss"),
        ),
    )
    return TurnAnalysis(
        review = reviewOf(original, score, report.text("note").orEmpty(), rewrites),
        vocab = vocabCards(report.array("vocab"), turnId),
    )
}

/** 只取第一个 { 到最后一个 }，模型偶尔在 JSON 前后带一句客套话或代码块围栏。 */
private fun objectOf(raw: String): JsonObject? {
    val start = raw.indexOf('{')
    val end = raw.lastIndexOf('}')
    if (start < 0 || end <= start) return null
    return runCatching { wire.parseToJsonElement(raw.substring(start, end + 1)) as? JsonObject }.getOrNull()
}

internal fun vocabCards(items: JsonArray?, turnId: String): List<VocabCard> {
    if (items == null) return emptyList()
    return items.mapNotNull { element ->
        val item = element as? JsonObject ?: return@mapNotNull null
        val word = item.text("word") ?: return@mapNotNull null
        val example = item.text("example").orEmpty()
        VocabCard(
            id = "$turnId-$word",
            word = word,
            phonetic = item.text("phonetic").orEmpty(),
            meaning = item.text("meaning").orEmpty(),
            example = example,
            highlight = highlightOf(item.text("highlight"), word, example),
            repeats = 1,
        )
    }.distinctBy { it.word.lowercase() }
}

/**
 * 例句里的陶土高亮按子串匹配，高亮片段不在例句里就标不出来，
 * 这一层把它退回词形，保证字面上一定对得上。
 */
internal fun highlightOf(highlight: String?, word: String, example: String): String {
    val shown = listOfNotNull(highlight, word)
        .filter { it.isNotBlank() }
        .firstOrNull { example.contains(it, ignoreCase = true) }
    return shown ?: highlight?.takeIf { it.isNotBlank() } ?: word
}

private fun JsonObject.score(): Int? {
    primitive("score")?.scoreValue()?.let { return it }
    return obj("grammar")?.primitive("score")?.scoreValue()
}

private fun JsonPrimitive.scoreValue(): Int? =
    intOrNull ?: contentOrNull?.toDoubleOrNull()?.toInt()

private fun JsonObject.primitive(key: String): JsonPrimitive? = this[key] as? JsonPrimitive

private fun JsonObject.obj(key: String): JsonObject? = this[key] as? JsonObject

private fun JsonObject.array(key: String): JsonArray? = this[key] as? JsonArray

private fun JsonObject.text(key: String): String? =
    (this[key] as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }

private fun JsonObject.flag(key: String): Boolean? =
    (this[key] as? JsonPrimitive)?.contentOrNull?.toBooleanStrictOrNull()
