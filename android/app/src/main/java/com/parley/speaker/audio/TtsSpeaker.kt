package com.parley.speaker.audio

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

/** 合成凭据与发音评测共用同一份 Azure 语音资源，两项都填了才算接通。 */
data class AzureCredential(val key: String = "", val region: String = "") {
    val configured: Boolean get() = key.isNotBlank() && region.isNotBlank()
}

interface TtsSpeaker {
    /** 读完或起播成功返回真，调用方据此决定是否换个实现接住。 */
    suspend fun speak(text: String): Boolean
}

/**
 * 朗读入口：只走神经网络语音模型，缺凭据或合成失败直接返回假，绝不调用机械系统音兜底。
 * 每次朗读前都清一次播放队列，正在播的会话音频会让位给朗读。
 */
class Tts(
    private val player: AudioPlayer,
    private val credential: () -> AzureCredential,
) : TtsSpeaker {

    override suspend fun speak(text: String): Boolean {
        if (text.isBlank()) return false
        val current = credential()
        if (current.configured && AzureSpeaker(player, current).speak(text)) return true
        return false
    }
}

/** en-US-AvaMultilingualNeural 一个多语言音色就能把中英混说的整句读顺，不按语种切音色。 */
class AzureSpeaker(
    private val player: AudioPlayer,
    private val credential: AzureCredential,
) : TtsSpeaker {

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
            .header("User-Agent", "parley")
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
