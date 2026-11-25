package com.voca.speaker.data.assessor

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject

/** 两家返回体都是随手改字段的外部数据，读字段一律走安全转型，类型不对就当没有。 */
internal fun jsonOf(raw: String): JsonObject? =
    runCatching { Json.parseToJsonElement(raw).jsonObject }.getOrNull()

internal fun JsonObject.obj(key: String): JsonObject? = this[key] as? JsonObject

internal fun JsonObject.array(key: String): JsonArray? = this[key] as? JsonArray

internal fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull

internal fun JsonObject.double(key: String): Double? = (this[key] as? JsonPrimitive)?.doubleOrNull

internal fun JsonObject.int(key: String): Int? = (this[key] as? JsonPrimitive)?.intOrNull
