package com.voca.speaker.data.analysis

import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 用录制下来的模型返回体验证解析，不联网也不需要密钥。
 * 断言的是分析结果本身：语法分、两档改写、以及每条词卡的六个字段。
 */
class AnalysisReportTest {

    @Test
    fun `中英混说的一句解析出语境生词且字段齐全`() {
        val analysis = parseAnalysisReport(REPORT, ORIGINAL, "turn-7")

        assertTrue("vocab 不该为空，实际 ${analysis?.vocab}", (analysis?.vocab?.size ?: 0) >= 1)
        val card = analysis!!.vocab.first()
        assertEquals("slept badly", card.word)
        assertEquals("/slept ˈbædli/", card.phonetic)
        assertEquals("睡得很差", card.meaning)
        assertTrue("例句要含搭配", card.example.contains("slept badly"))
        assertEquals("slept badly", card.highlight)
        assertEquals(1, card.repeats)
        assertEquals("turn-7-slept badly", card.id)
    }

    @Test
    fun `同一条返回里语法分与两档改写都在`() {
        val review = parseAnalysisReport(REPORT, ORIGINAL, "turn-7")!!.review

        assertEquals(ORIGINAL, review.original)
        assertEquals(72, review.grammar.score)
        assertEquals("时态与介词都要修", review.grammar.note)
        assertEquals(2, review.rewrites.size)
        assertEquals("I slept badly because the renovation was so noisy.", review.rewrites[0].text)
        assertEquals("昨晚没睡好", review.rewrites[1].gloss)
    }

    @Test
    fun `返回体被包在外壳里也认得出来`() {
        val content = replyText(openAiEnvelope(REPORT))
        val analysis = parseAnalysisReport(content.orEmpty(), ORIGINAL, "turn-7")

        assertEquals(2, analysis?.vocab?.size)
        assertEquals(72, analysis?.review?.grammar?.score)
    }

    @Test
    fun `思考段排在正文前面时跳过它`() {
        val envelope = buildJsonObject {
            putJsonArray("candidates") {
                add(buildJsonObject {
                    putJsonObject("content") {
                        putJsonArray("parts") {
                            add(buildJsonObject {
                                put("thought", true)
                                put("text", "先想一下学生说了什么")
                            })
                            add(buildJsonObject { put("text", REPORT) })
                        }
                    }
                })
            }
        }.toString()

        assertEquals(72, parseAnalysisReport(replyText(envelope).orEmpty(), ORIGINAL, "t")?.review?.grammar?.score)
    }

    @Test
    fun `高亮片段不在例句里就退回词形`() {
        val analysis = parseAnalysisReport(
            """{"score": 60, "vocab": [{"word": "under the weather", "phonetic": "/ˈweðə/",
               "meaning": "不舒服", "example": "I am feeling under the weather today.",
               "highlight": "a bit ill"}]}""",
            ORIGINAL,
            "turn-8",
        )

        val card = analysis!!.vocab.single()
        assertEquals("under the weather", card.highlight)
        assertTrue(card.example.contains(card.highlight, ignoreCase = true))
    }

    @Test
    fun `同一轮里重复的搭配只留一条`() {
        val analysis = parseAnalysisReport(
            """{"score": 60, "vocab": [
                 {"word": "Slept badly", "meaning": "a", "example": "I slept badly.", "highlight": "slept badly"},
                 {"word": "slept badly", "meaning": "b", "example": "I slept badly.", "highlight": "slept badly"}]}""",
            ORIGINAL,
            "turn-9",
        )

        assertEquals(1, analysis!!.vocab.size)
    }

    @Test
    fun `没有分值整份作废`() {
        assertNull(parseAnalysisReport("""{"vocab": []}""", ORIGINAL, "t"))
        assertNull(parseAnalysisReport("不是 JSON", ORIGINAL, "t"))
        assertNull(replyText("""{"choices": []}"""))
    }

    @Test
    fun `分值写成字符串也认`() {
        val analysis = parseAnalysisReport("""{"score": "81", "note": "词序"}""", ORIGINAL, "t")

        assertEquals(81, analysis?.review?.grammar?.score)
        assertTrue(analysis!!.vocab.isEmpty())
    }

    private fun openAiEnvelope(content: String): String = buildJsonObject {
        putJsonArray("choices") {
            add(buildJsonObject {
                putJsonObject("message") {
                    put("role", "assistant")
                    put("content", content)
                }
                put("finish_reason", "stop")
            })
        }
    }.toString()

    private companion object {
        const val ORIGINAL = "我昨天 slept badly 因为楼下装修太吵"

        val REPORT = """
{
  "score": 72,
  "note": "时态与介词都要修",
  "idiomatic": {"text": "I slept badly because the renovation was so noisy.", "gloss": "装修太吵，没睡好"},
  "advanced": {"text": "The renovation kept me up all night, so I slept badly.", "gloss": "昨晚没睡好"},
  "vocab": [
    {
      "word": "slept badly",
      "phonetic": "/slept ˈbædli/",
      "meaning": "睡得很差",
      "example": "I slept badly last night because of the noise.",
      "highlight": "slept badly"
    },
    {
      "word": "renovation",
      "phonetic": "/ˌrenəˈveɪʃn/",
      "meaning": "装修",
      "example": "The renovation next door is driving me crazy.",
      "highlight": "renovation"
    }
  ]
}
"""
    }
}
