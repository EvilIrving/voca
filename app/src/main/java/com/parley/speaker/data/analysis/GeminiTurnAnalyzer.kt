package com.parley.speaker.data.analysis

import android.util.Log
import com.parley.speaker.domain.Register
import com.parley.speaker.domain.VocabCard
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

class GeminiTurnAnalyzer(
    private val apiKey: String,
    private val model: String,
) : TurnAnalyzer {

    private val client = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(45, TimeUnit.SECONDS)
        .build()

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    override suspend fun analyze(text: String, turnId: String): TurnAnalysis? = withContext(Dispatchers.IO) {
        if (apiKey.isBlank() || text.isBlank()) return@withContext null
        val payload = buildJsonObject {
            putJsonArray("contents") {
                add(buildJsonObject {
                    put("role", "user")
                    putJsonArray("parts") { add(buildJsonObject { put("text", prompt(text)) }) }
                })
            }
            putJsonObject("generationConfig") {
                put("responseMimeType", "application/json")
                put("temperature", 0.3)
            }
        }
        val request = Request.Builder()
            .url("https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent?key=$apiKey")
            .post(payload.toString().toRequestBody("application/json".toMediaType()))
            .build()
        try {
            client.newCall(request).execute().use { response ->
                val body = response.body.string()
                if (!response.isSuccessful) {
                    Log.w(TAG, "analyze ${response.code}: ${body.take(200)}")
                    return@withContext null
                }
                parse(body, text, turnId)
            }
        } catch (e: Exception) {
            Log.w(TAG, "analyze failed: ${e.message}")
            null
        }
    }

    private fun parse(raw: String, original: String, turnId: String): TurnAnalysis? {
        val root = try {
            json.parseToJsonElement(raw).jsonObject
        } catch (e: Exception) {
            return null
        }
        val content = root["candidates"]?.jsonArray?.firstOrNull()
            ?.jsonObject?.get("content")?.jsonObject
            ?: return null
        val text = content["parts"]?.jsonArray?.firstOrNull()
            ?.jsonObject?.get("text")?.jsonPrimitive?.contentOrNull
            ?: return null
        val report = try {
            json.parseToJsonElement(text.trim().removePrefix("```json").removeSuffix("```")).jsonObject
        } catch (e: Exception) {
            Log.w(TAG, "bad report: ${text.take(200)}")
            return null
        }

        val score = report["score"]?.jsonPrimitive?.intOrNull ?: return null
        val note = report["note"]?.jsonPrimitive?.contentOrNull.orEmpty()
        val rewrites = listOfNotNull(
            rewriteOf(
                Register.Idiomatic,
                report["idiomatic"]?.jsonObject?.get("text")?.jsonPrimitive?.contentOrNull,
                report["idiomatic"]?.jsonObject?.get("gloss")?.jsonPrimitive?.contentOrNull,
            ),
            rewriteOf(
                Register.Advanced,
                report["advanced"]?.jsonObject?.get("text")?.jsonPrimitive?.contentOrNull,
                report["advanced"]?.jsonObject?.get("gloss")?.jsonPrimitive?.contentOrNull,
            ),
        )

        val vocab = report["vocab"]?.jsonArray?.mapNotNull { element ->
            val item = element.jsonObject
            val word = item["word"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
            VocabCard(
                id = "$turnId-$word",
                word = word,
                phonetic = item["phonetic"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                meaning = item["meaning"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                example = item["example"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                highlight = item["highlight"]?.jsonPrimitive?.contentOrNull ?: word,
                repeats = 1,
            )
        }.orEmpty()

        return TurnAnalysis(review = reviewOf(original, score, note, rewrites), vocab = vocab)
    }

    private fun prompt(text: String): String = """
        你是一位英语口语教练。学生说了下面这句话，其中可能夹带中文。只输出 JSON，不要任何解释：
        {
          "score": 0 到 100 的语法与表达准确度,
          "note": "一句中文点评，指出最关键的一处问题，不超过二十字",
          "fixed": "学生这句话的语法修正版",
          "idiomatic": {"text": "母语者日常会这么说的一句", "gloss": "对应的中文意思"},
          "advanced": {"text": "更讲究的一种说法", "gloss": "对应的中文意思"},
          "vocab": [{"word": "值得记下的地道搭配", "phonetic": "/音标/", "meaning": "中文释义", "example": "包含该搭配的英文例句", "highlight": "例句中要突出的片段"}]
        }
        学生原句：$text
    """.trimIndent()

    companion object {
        private const val TAG = "TurnAnalyzer"
    }
}
