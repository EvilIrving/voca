package com.parley.speaker.data.assessor

import android.util.Log
import com.parley.speaker.domain.PhonemeIssue
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Base64
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * 微软 Azure 短音频评测。走 REST 一次性上传，单次上限三十秒，多出来的尾巴直接扔掉：
 * 超限整条请求会被拒掉，扔尾巴至少还能拿到前半段的分数。
 * 按区域拼主机名属于密钥不绑端点的那种用法，配置里只有密钥与区域两项也够。
 */
class AzureAssessor(
    private val subscriptionKey: String,
    private val region: String,
) : SpeechAssessor {

    override val requiresReferenceText = false
    override val maxAudioMillis = MAX_AUDIO_MILLIS

    private val client = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    override suspend fun assess(pcm16k: ByteArray, referenceText: String?, locale: String): AssessOutcome =
        withContext(Dispatchers.IO) {
            if (subscriptionKey.isBlank() || region.isBlank()) return@withContext AssessOutcome.Failed(AssessFailure.Key)
            if (pcm16k.isEmpty()) return@withContext AssessOutcome.Failed(AssessFailure.Audio)
            val request = Request.Builder()
                .url(azureEndpoint(region, locale))
                .header("Ocp-Apim-Subscription-Key", subscriptionKey)
                .header("Pronunciation-Assessment", azureAssessmentHeader(referenceText))
                .post(wavContainer(pcm16k.pcmSlice(MAX_AUDIO_MILLIS)).toRequestBody(WAV_MEDIA_TYPE))
                .build()
            try {
                client.newCall(request).execute().use { response ->
                    val body = response.body.string()
                    if (!response.isSuccessful) {
                        Log.w(TAG, "assess ${response.code}: ${body.take(200)}")
                        return@withContext AssessOutcome.Failed(azureFailure(response.code))
                    }
                    azureOutcome(body)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: IOException) {
                Log.w(TAG, "assess failed: ${e.message}")
                AssessOutcome.Failed(AssessFailure.Offline)
            } catch (e: Exception) {
                Log.w(TAG, "assess failed: ${e.message}")
                AssessOutcome.Failed(AssessFailure.Service)
            }
        }

    private companion object {
        const val TAG = "AzureAssessor"
        const val MAX_AUDIO_MILLIS = 30_000
    }
}

/**
 * HTTP 状态归成类：401 与 403 是密钥或资源授权，429 是限流，400 说明音频与请求本身评不了，
 * 其余状态（含 5xx）算服务异常。响应体不进界面的任何一格。
 */
internal fun azureFailure(status: Int): AssessFailure = when {
    status == 401 || status == 403 -> AssessFailure.Key
    status == 429 -> AssessFailure.Busy
    status == 400 || status == 413 -> AssessFailure.Audio
    else -> AssessFailure.Service
}

/** 返回体能解析出分才算出了结果，能读但没有分说明这一轮没评出来。 */
internal fun azureOutcome(body: String): AssessOutcome {
    // 静音、纯噪声与认不出的发音属于音频这一类，其余非 Success 是服务侧的问题。
    when (jsonOf(body)?.string("RecognitionStatus")) {
        "InitialSilenceTimeout", "BabbleTimeout", "NoMatch" -> return AssessOutcome.Failed(AssessFailure.Audio)
        "Success" -> Unit
        else -> if (jsonOf(body)?.string("RecognitionStatus") != null) return AssessOutcome.Failed(AssessFailure.Service)
    }
    val result = parseAzureResult(body) ?: return AssessOutcome.Failed(AssessFailure.Service)
    return if (result.total != null || result.accuracy != null) {
        AssessOutcome.Scored(result)
    } else {
        AssessOutcome.Failed(AssessFailure.Service)
    }
}

/**
 * 上传的音频媒体类型。OkHttp 的参数值不允许出现裸斜杠，codecs 不带引号整串就解不开，
 * 看起来有值实际是 null，请求头因此落到默认的 application/json。
 */
internal val WAV_MEDIA_TYPE = "audio/wav; codecs=\"audio/pcm\"; samplerate=16000".toMediaType()

internal fun azureEndpoint(region: String, locale: String): String =
    "https://$region.stt.speech.microsoft.com/speech/recognition/conversation/cognitiveservices/v1?language=$locale"

/**
 * 头里这串是 Base64 包的 JSON。GradingSystem 取百分制，Granularity 取音素，
 * Dimension 取综合维度才会带上韵律那一层；韵律只有 en-US 出分，别的语种照样送开关，服务端自己忽略。
 * 没开 EnableMiscue：项目约定的偏误只有误读、多停、少停、平调四类，开了才会多出漏读与多读。
 */
internal fun azureAssessmentHeader(referenceText: String?): String {
    val params = buildJsonObject {
        if (!referenceText.isNullOrBlank()) put("ReferenceText", referenceText)
        put("GradingSystem", "HundredMark")
        put("Granularity", "Phoneme")
        put("Dimension", "Comprehensive")
        put("EnableProsodyAssessment", true)
    }
    return Base64.getEncoder().encodeToString(params.toString().toByteArray(Charsets.UTF_8))
}

/** PCM 裸流装上 44 字节 RIFF 头再送，短音频端点收的是 wav 文件。 */
internal fun wavContainer(pcm: ByteArray, sampleRate: Int = 16_000): ByteArray {
    val bitsPerSample = 16
    val channels = 1
    val blockAlign = channels * bitsPerSample / 8
    val header = ByteBuffer.allocate(WAV_HEADER_BYTES).order(ByteOrder.LITTLE_ENDIAN)
    header.put("RIFF".toByteArray())
    header.putInt(36 + pcm.size)
    header.put("WAVE".toByteArray())
    header.put("fmt ".toByteArray())
    header.putInt(16)
    header.putShort(1)
    header.putShort(channels.toShort())
    header.putInt(sampleRate)
    header.putInt(sampleRate * blockAlign)
    header.putShort(blockAlign.toShort())
    header.putShort(bitsPerSample.toShort())
    header.put("data".toByteArray())
    header.putInt(pcm.size)
    return header.array() + pcm
}

internal fun parseAzureResult(raw: String): AssessResult? {
    val root = jsonOf(raw) ?: return null
    val status = root.string("RecognitionStatus")
    if (status != null && status != "Success") return null
    val best = root.array("NBest")?.firstOrNull() as? JsonObject ?: return null
    val assessment = best.obj("PronunciationAssessment")

    val issues = best.array("Words").orEmpty().mapNotNull { element ->
        val word = element as? JsonObject ?: return@mapNotNull null
        val wordAssessment = word.obj("PronunciationAssessment") ?: return@mapNotNull null
        val errorType = wordAssessment.string("ErrorType") ?: "None"
        if (errorType == "None") return@mapNotNull null
        PhonemeIssue(
            word = word.string("Word").orEmpty(),
            phonetic = ipaOf(word),
            note = errorLabel(errorType),
        )
    }

    return AssessResult(
        accuracy = assessment?.double("AccuracyScore"),
        fluency = assessment?.double("FluencyScore"),
        prosody = assessment?.double("ProsodyScore"),
        completeness = assessment?.double("CompletenessScore"),
        total = assessment?.double("PronScore"),
        issues = issues,
    )
}

private fun ipaOf(word: JsonObject): String {
    val phonemes = word.array("Phonemes").orEmpty().mapNotNull { element ->
        (element as? JsonObject)?.string("Phoneme")
    }
    return if (phonemes.isEmpty()) "" else "/" + phonemes.joinToString(" ") + "/"
}

/** 项目文档只列了误读、多停、少停、平调四类，其余值原样透出，不做猜测翻译。 */
private fun errorLabel(errorType: String): String = when (errorType) {
    "Mispronunciation" -> "误读"
    "UnexpectedBreak" -> "多停"
    "MissingBreak" -> "少停"
    "Monotone" -> "平调"
    else -> errorType
}

private const val WAV_HEADER_BYTES = 44
