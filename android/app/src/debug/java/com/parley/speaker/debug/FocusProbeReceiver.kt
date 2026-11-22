package com.parley.speaker.debug

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import java.io.File

/**
 * 自动化验证用：从应用外部抢占音频焦点，模拟来电这类瞬时打断。
 * mode=take 请求瞬时独占，mode=drop 归还；结果写进内部目录供脚本读取。
 */
class FocusProbeReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val manager = context.getSystemService(AudioManager::class.java) ?: return
        val outcome = when (intent.getStringExtra("mode")) {
            "take" -> {
                val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
                    .setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                            .build(),
                    )
                    .setOnAudioFocusChangeListener { }
                    .build()
                held = request
                "take:${manager.requestAudioFocus(request)}"
            }

            "drop" -> {
                val result = held?.let { manager.abandonAudioFocusRequest(it) }
                held = null
                "drop:$result"
            }

            else -> "unknown"
        }
        File(context.filesDir, "focus_state.txt").writeText(outcome)
    }

    private companion object {
        var held: AudioFocusRequest? = null
    }
}
