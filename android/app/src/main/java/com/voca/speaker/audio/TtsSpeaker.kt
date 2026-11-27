package com.voca.speaker.audio

import android.util.Log
import com.voca.speaker.domain.AppSettings
import com.voca.speaker.domain.AssessorProvider
import com.voca.speaker.domain.LiveVendor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

/** 合成凭据与发音评测共用同一份 Azure 语音资源，两项都填了才算接通。 */
data class AzureCredential(val key: String = "", val region: String = "") {
    val configured: Boolean get() = key.isNotBlank() && region.isNotBlank()
}

enum class TtsOutcome { Played, Unconfigured, Failed }

interface TtsSpeaker {
    /** 严格按当前选中供应商朗读：成功返回 [TtsOutcome.Played]，未配置或合成失败分别返回对应结果。 */
    suspend fun speak(text: String): TtsOutcome
}

/**
 * 朗读入口：只走神经网络语音模型，严格跟随设置中当前激活的供应商（Azure 评测或千问实时），
 * 不做跨供应商顺序兜底，绝不调用机械系统音。外部调用方只认 [TtsSpeaker] 与 [TtsOutcome]。
 */
class Tts(
    private val player: AudioPlayer,
    private val settings: () -> AppSettings,
) : TtsSpeaker {

    override suspend fun speak(text: String): TtsOutcome {
        if (text.isBlank()) return TtsOutcome.Failed
        val speaker = resolveSpeaker(settings()) ?: return TtsOutcome.Unconfigured
        return if (speaker.speak(text)) TtsOutcome.Played else TtsOutcome.Failed
    }

    private fun resolveSpeaker(current: AppSettings): ProviderSpeaker? {
        if (current.assessor.resolvedProvider == AssessorProvider.Azure) {
            val azure = AzureCredential(current.assessor.azureKey, current.assessor.azureRegion)
            if (azure.configured) return AzureSpeaker(player, azure)
        }
        if (current.live.provider == LiveVendor.Qwen && current.live.ready(LiveVendor.Qwen)) {
            return QwenSpeaker(player, current.live.qwenApiKey)
        }
        return null
    }
}

private interface ProviderSpeaker {
    suspend fun speak(text: String): Boolean
}

/** 千问 qwen3-tts-flash 朗读：返回 24kHz 单声道 16 位 WAV，剥掉四十四字节头直接送播放器。 */
class QwenSpeaker(
    private val player: AudioPlayer,
    private val apiKey: String,
) : ProviderSpeaker {

    override suspend fun speak(text: String): Boolean {
        if (text.isBlank() || apiKey.isBlank()) return false
        player.flush()
        val pcm = withContext(Dispatchers.IO) { synthesize(text) } ?: return false
        player.enqueue(pcm)
        return true
    }

    private fun synthesize(text: String): ByteArray? {
        val body = buildJsonObject {
            put("model", MODEL)
            putJsonObject("input") {
                put("text", text)
                put("voice", VOICE)
            }
        }.toString()
        val request = Request.Builder()
            .url(ENDPOINT)
            .header("Authorization", "Bearer ${apiKey.trim()}")
            .header("Content-Type", "application/json")
            .post(body.toRequestBody(JsonMedia))
            .build()
        val audioUrl = try {
            client.newCall(request).execute().use { response ->
                val raw = response.body.string()
                if (!response.isSuccessful) {
                    Log.w(TAG, "qwen tts ${response.code}: ${raw.take(200)}")
                    return null
                }
                Json.parseToJsonElement(raw).jsonObject["output"]
                    ?.jsonObject?.get("audio")
                    ?.jsonObject?.get("url")
                    ?.jsonPrimitive?.contentOrNull
            }
        } catch (e: Exception) {
            Log.w(TAG, "qwen tts failed: ${e.message}")
            return null
        } ?: return null

        val wav = try {
            client.newCall(Request.Builder().url(audioUrl).build()).execute().use { response ->
                if (!response.isSuccessful) {
                    Log.w(TAG, "qwen wav ${response.code}")
                    return null
                }
                response.body.bytes()
            }
        } catch (e: Exception) {
            Log.w(TAG, "qwen wav failed: ${e.message}")
            return null
        }
        if (wav.size <= WAV_HEADER) return null
        return wav.copyOfRange(WAV_HEADER, wav.size)
    }

    private companion object {
        const val TAG = "QwenSpeaker"
        const val ENDPOINT = "https://dashscope.aliyuncs.com/api/v1/services/aigc/multimodal-generation/generation"
        const val MODEL = "qwen3-tts-flash"
        const val VOICE = "Cherry"
        const val WAV_HEADER = 44
        val JsonMedia = "application/json".toMediaType()
        val client: OkHttpClient by lazy {
            OkHttpClient.Builder()
                .connectTimeout(8, TimeUnit.SECONDS)
                .writeTimeout(10, TimeUnit.SECONDS)
                .readTimeout(20, TimeUnit.SECONDS)
                .build()
        }
    }
}

/** en-US-AvaMultilingualNeural 一个多语言音色就能把中英混说的整句读顺，不按语种切音色。 */
class AzureSpeaker(
    private val player: AudioPlayer,
    private val credential: AzureCredential,
) : ProviderSpeaker {

    override suspend fun speak(text: String): Boolean {
        if (text.isBlank() || !credential.configured) return false
        player.flush()
        val pcm = withContext(Dispatchers.IO) { synthesize(text) } ?: return false
        player.enqueue(pcm)
        return true
    }

    private fun synthesize(text: String): ByteArray? {
        val request = Request.Builder()
            .url("https://${credential.region.trim().lowercase()}.tts.speech.microsoft.com/cognitiveservices/v1")
            .header("Ocp-Apim-Subscription-Key", credential.key.trim())
            .header("Content-Type", "application/ssml+xml")
            .header("X-Microsoft-OutputFormat", OUTPUT_FORMAT)
            .header("User-Agent", "voca")
            .post(ssml(text).toRequestBody(SsmlMedia))
            .build()
        val riff = try {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    Log.w(TAG, "synth ${response.code}: ${response.body.string().take(200)}")
                    return null
                }
                response.body.bytes()
            }
        } catch (e: Exception) {
            Log.w(TAG, "synth failed: ${e.message}")
            return null
        }
        // riff-24khz-16bit-mono-pcm 的容器头固定四十四字节，剥掉正好是播放器要的裸流。
        if (riff.size <= WavHeader) {
            Log.w(TAG, "short body ${riff.size}")
            return null
        }
        return riff.copyOfRange(WavHeader, riff.size)
    }

    private fun ssml(text: String): String {
        val sentences = text.split(SentenceBreak).map { it.trim() }.filter { it.isNotEmpty() }
        val body = sentences.joinToString(PauseTag) { sentence ->
            "<mstts:express-as style=\"chat\" styledegree=\"0.9\">${escape(sentence)}</mstts:express-as>"
        }
        return "<speak version=\"1.0\" xmlns=\"http://www.w3.org/2001/10/synthesis\" " +
            "xmlns:mstts=\"https://www.w3.org/2001/mstts\" xml:lang=\"$LANGUAGE\">" +
            "<voice name=\"$VOICE_NAME\">$body</voice></speak>"
    }

    private fun escape(text: String): String = text
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")

    private companion object {
        const val TAG = "AzureSpeaker"
        const val VOICE_NAME = "en-US-AvaMultilingualNeural"
        const val LANGUAGE = "zh-CN"
        const val OUTPUT_FORMAT = "riff-24khz-16bit-mono-pcm"
        const val WavHeader = 44
        const val PauseTag = "<break time=\"200ms\"/>"
        val SsmlMedia = "application/ssml+xml".toMediaType()

        // 句末标点不参与切分数字里的小数点，句停顿交给引擎自己那一下。
        val SentenceBreak = Regex("[。！？!?；;\\n]+")

        /** 连接池跨朗读复用，连续读几条词条不重复握手。 */
        val client: OkHttpClient by lazy {
            OkHttpClient.Builder()
                .connectTimeout(8, TimeUnit.SECONDS)
                .writeTimeout(10, TimeUnit.SECONDS)
                .readTimeout(20, TimeUnit.SECONDS)
                .build()
        }
    }
}
