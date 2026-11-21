package com.parley.speaker.debug

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.util.Log

/**
 * 开发期把一段本地音频从扬声器放出，让麦克风拾取，用来验证真实收音链路。
 */
class PlayProbeReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val path = intent.getStringExtra("path") ?: return
        try {
            MediaPlayer().apply {
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build(),
                )
                setDataSource(path)
                prepare()
                start()
                setOnCompletionListener { it.release() }
            }
            Log.i(TAG, "playing $path")
        } catch (e: Exception) {
            Log.w(TAG, "play failed: ${e.message}")
        }
    }

    private companion object {
        const val TAG = "PlayProbe"
    }
}
