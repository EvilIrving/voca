package com.parley.speaker.data.assessor

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Base64
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 用录制下来的返回体验证解析，不发真请求也不需要密钥。
 * 断言的是适配器对外给出的分值、偏误列表与请求里看得见的头部参数。
 */
class AzureAssessorTest {

    @Test
    fun `解析返回体的五项分与逐词偏误`() {
        val result = parseAzureResult(AZURE_SUCCESS)

        assertEquals(88.5, result?.accuracy ?: Double.NaN, 0.001)
        assertEquals(92.0, result?.fluency ?: Double.NaN, 0.001)
        assertEquals(74.5, result?.prosody ?: Double.NaN, 0.001)
        assertEquals(100.0, result?.completeness ?: Double.NaN, 0.001)
        assertEquals(87.2, result?.total ?: Double.NaN, 0.001)

        val issues = result?.issues.orEmpty()
        assertEquals("读对的 i 不该出现在偏误里，实际是 ${issues.map { it.word }}", 2, issues.size)
        assertEquals("cup", issues[0].word)
        assertEquals("/k ʌ p/", issues[0].phonetic)
        assertEquals("误读", issues[0].note)
        assertEquals("coffee", issues[1].word)
        assertEquals("平调", issues[1].note)
    }

    @Test
    fun `没识别出内容就没有分`() {
        assertNull(parseAzureResult("""{"RecognitionStatus":"InitialSilenceTimeout","NBest":[]}"""))
        assertNull(parseAzureResult("不是 JSON"))
    }

    @Test
    fun `头里的参数按文档构造`() {
        val params = headerParams("I would like a cup of coffee.")

        assertEquals("I would like a cup of coffee.", params.getValue("ReferenceText").jsonPrimitive.content)
        assertEquals("HundredMark", params.getValue("GradingSystem").jsonPrimitive.content)
        assertEquals("Phoneme", params.getValue("Granularity").jsonPrimitive.content)
        assertEquals("Comprehensive", params.getValue("Dimension").jsonPrimitive.content)
        assertTrue(params.getValue("EnableProsodyAssessment").jsonPrimitive.boolean)
    }

    @Test
    fun `无文本模式不送 ReferenceText`() {
        assertFalse(headerParams(null).containsKey("ReferenceText"))
        assertFalse(headerParams("   ").containsKey("ReferenceText"))
    }

    @Test
    fun `裸流装上 wav 头再送`() {
        val pcm = ByteArray(32) { it.toByte() }
        val wav = wavContainer(pcm)

        assertEquals(44 + pcm.size, wav.size)
        assertEquals("RIFF", String(wav, 0, 4, Charsets.US_ASCII))
        assertEquals("WAVE", String(wav, 8, 4, Charsets.US_ASCII))
        assertEquals("fmt ", String(wav, 12, 4, Charsets.US_ASCII))
        assertEquals("data", String(wav, 36, 4, Charsets.US_ASCII))
        assertEquals(pcm.size, ByteBuffer.wrap(wav, 40, 4).order(ByteOrder.LITTLE_ENDIAN).int)
        assertArrayEquals(pcm, wav.copyOfRange(44, wav.size))
    }

    @Test
    fun `上传的媒体类型带上带引号的 codecs 并能被 OkHttp 解析`() {
        val media = WAV_MEDIA_TYPE

        assertEquals("audio", media.type)
        assertEquals("wav", media.subtype)
        assertEquals("codecs 不丢", "audio/pcm", media.parameter("codecs"))
        assertEquals("16000", media.parameter("samplerate"))
        assertNull(
            "旧写法 codecs=audio/pcm 里的斜杠让整串解不开",
            "audio/wav; codecs=audio/pcm; samplerate=16000".toMediaTypeOrNull(),
        )
    }

    private fun headerParams(referenceText: String?) =
        Json.parseToJsonElement(
            String(Base64.getDecoder().decode(azureAssessmentHeader(referenceText)), Charsets.UTF_8),
        ).jsonObject

    private companion object {
        const val AZURE_SUCCESS = """
{
  "RecognitionStatus": "Success",
  "Offset": 3000000,
  "Duration": 12300000,
  "DisplayText": "I would like a cup of coffee.",
  "NBest": [
    {
      "Confidence": 0.92,
      "Lexical": "i would like a cup of coffee",
      "PronunciationAssessment": {
        "AccuracyScore": 88.5,
        "FluencyScore": 92,
        "ProsodyScore": 74.5,
        "CompletenessScore": 100,
        "PronScore": 87.2
      },
      "Words": [
        {
          "Word": "i",
          "Offset": 3000000,
          "Duration": 1200000,
          "PronunciationAssessment": { "AccuracyScore": 96, "ErrorType": "None" },
          "Phonemes": [ { "Phoneme": "aɪ", "Offset": 3000000, "Duration": 1200000 } ]
        },
        {
          "Word": "cup",
          "Offset": 81000000,
          "Duration": 3600000,
          "PronunciationAssessment": { "AccuracyScore": 41, "ErrorType": "Mispronunciation" },
          "Phonemes": [
            { "Phoneme": "k", "Offset": 81000000, "Duration": 1200000 },
            { "Phoneme": "ʌ", "Offset": 82200000, "Duration": 1200000 },
            { "Phoneme": "p", "Offset": 83400000, "Duration": 1200000 }
          ]
        },
        {
          "Word": "coffee",
          "Offset": 84600000,
          "Duration": 3840000,
          "PronunciationAssessment": { "AccuracyScore": 55, "ErrorType": "Monotone" },
          "Phonemes": [
            { "Phoneme": "k", "Offset": 84600000, "Duration": 1200000 },
            { "Phoneme": "ɔ", "Offset": 85800000, "Duration": 1200000 },
            { "Phoneme": "f", "Offset": 87000000, "Duration": 1200000 },
            { "Phoneme": "i", "Offset": 88200000, "Duration": 1200000 }
          ]
        }
      ]
    }
  ]
}
"""
    }
}
