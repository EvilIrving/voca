package com.parley.speaker.audio

import android.content.Context
import android.media.AudioAttributes
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.Locale
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
 * 朗读入口：凭据齐备走 Azure REST，缺凭据或合成失败交给系统语音，功能不断档。
 * 每次朗读前都清一次播放队列，正在播的会话音频会让位给朗读。
 */
class Tts(
    context: Context,
    private val player: AudioPlayer,
    private val credential: () -> AzureCredential,
) : TtsSpeaker {

    private val system by lazy { SystemSpeaker(context, player) }

    override suspend fun speak(text: String): Boolean {
        if (text.isBlank()) return false
        val current = credential()
        if (current.configured && AzureSpeaker(player, current).speak(text)) return true
        return system.speak(text)
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

/** 没有 Azure 时的本地语音，引擎自己管路由与输出格式，绕开采样率换算带来的走调。 */
class SystemSpeaker(context: Context, private val player: AudioPlayer) : TtsSpeaker {

    private val appContext = context.applicationContext
    private val gate = Mutex()
    private var engine: TextToSpeech? = null
    private var utterance = 0

    // 回调走引擎自己的线程，这两个字段要跨线程可见；按 id 比对，旧朗读的收尾不会认到新朗读头上。
    @Volatile
    private var current: String? = null

    @Volatile
    private var done: CompletableDeferred<Unit>? = null

    private val progress = object : UtteranceProgressListener() {
        override fun onStart(utteranceId: String?) = Unit

        override fun onDone(utteranceId: String?) {
            if (utteranceId == current) done?.complete(Unit)
        }

        @Suppress("OVERRIDE_DEPRECATION")
        override fun onError(utteranceId: String?) {
            if (utteranceId == current) done?.complete(Unit)
        }
    }

    override suspend fun speak(text: String): Boolean {
        if (text.isBlank()) return false
        player.flush()
        return gate.withLock {
            val tts = engine() ?: return@withLock false
            applyLanguage(tts, text)
            val finished = CompletableDeferred<Unit>()
            utterance += 1
            val id = "parley-$utterance"
            current = id
            done = finished
            val started = tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, id) == TextToSpeech.SUCCESS
            if (started) withTimeoutOrNull(SpeakTimeoutMs) { finished.await() }
            current = null
            done = null
            started
        }
    }

    private suspend fun engine(): TextToSpeech? {
        engine?.let { return it }
        val initialized = CompletableDeferred<Boolean>()
        val created = withContext(Dispatchers.Main) {
            TextToSpeech(appContext) { status -> initialized.complete(status == TextToSpeech.SUCCESS) }
        }
        val usable = withTimeoutOrNull(InitTimeoutMs) { initialized.await() } == true
        if (!usable) {
            Log.w(TAG, "system tts unavailable")
            withContext(Dispatchers.Main) { created.shutdown() }
            return null
        }
        created.setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build(),
        )
        created.setOnUtteranceProgressListener(progress)
        engine = created
        return created
    }

    /** 设备未必装齐两个语种，缺哪个先换另一个，都不支持就让引擎按自己的默认读。 */
    private fun applyLanguage(tts: TextToSpeech, text: String) {
        val wanted = localeOf(text)
        val other = if (wanted.language == Locale.SIMPLIFIED_CHINESE.language) Locale.US else Locale.SIMPLIFIED_CHINESE
        for (locale in listOf(wanted, other)) {
            val status = tts.setLanguage(locale)
            val missing = status == TextToSpeech.LANG_MISSING_DATA || status == TextToSpeech.LANG_NOT_SUPPORTED
            if (!missing) return
        }
    }

    /** 汉字数量压过拉丁字母就按中文读，中英混说的句子不会被整句当成英文念。 */
    private fun localeOf(text: String): Locale {
        val han = text.count { it.code in 0x4E00..0x9FFF }
        val latin = text.count { it in 'a'..'z' || it in 'A'..'Z' }
        return if (han > latin) Locale.SIMPLIFIED_CHINESE else Locale.US
    }

    private companion object {
        const val TAG = "SystemSpeaker"
        const val InitTimeoutMs = 5_000L
        const val SpeakTimeoutMs = 30_000L
    }
}
