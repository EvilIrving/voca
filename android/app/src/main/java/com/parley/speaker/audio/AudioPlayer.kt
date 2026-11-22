package com.parley.speaker.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.util.Log

/**
 * 下行播放：二十四千赫兹单声道十六位，写入走独立线程，界面线程只投递数据块。
 * 播放器持有音频焦点，来电或其他应用抢占时暂停，拿回焦点后继续。
 *
 * 音轨与它那一段 [PlaybackSession] 绑在一起，播放状态只有「当前这一段是谁」一个变量：
 * 停止只作用于当前这一段，句柄由持有它的写入线程释放，下一代会话读不到上一代的退出标记。
 */
class AudioPlayer(context: Context) {

    private val audioManager = context.getSystemService(AudioManager::class.java)
    private val lock = Any()

    /** 当前播放会话，null 表示没有在播。 */
    private var output: Output? = null

    private var focusRequest: AudioFocusRequest? = null

    private inner class Output(val track: AudioTrack) {
        val session = PlaybackSession(
            write = { pcm -> track.write(pcm, 0, pcm.size) },
            onFinished = { finish(this) },
        )
    }

    private val focusListener = AudioManager.OnAudioFocusChangeListener { change ->
        synchronized(lock) {
            val current = output?.track ?: return@OnAudioFocusChangeListener
            try {
                when (change) {
                    AudioManager.AUDIOFOCUS_LOSS,
                    AudioManager.AUDIOFOCUS_LOSS_TRANSIENT,
                    AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK,
                    -> current.pause()

                    AudioManager.AUDIOFOCUS_GAIN -> current.play()
                }
            } catch (e: IllegalStateException) {
                Log.w(TAG, "focus change on dead track: ${e.message}")
            }
        }
    }

    fun enqueue(pcm: ByteArray) {
        if (pcm.isEmpty()) return
        val current = synchronized(lock) { output ?: startLocked() } ?: return
        current.session.offer(pcm)
    }

    fun flush() {
        synchronized(lock) {
            val current = output ?: return
            current.session.clear()
            try {
                current.track.pause()
                current.track.flush()
                current.track.play()
            } catch (e: IllegalStateException) {
                Log.w(TAG, "flush on dead track: ${e.message}")
            }
        }
    }

    private fun startLocked(): Output? {
        val minBuffer = AudioTrack.getMinBufferSize(
            SAMPLE_RATE,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        val bufferSize = maxOf(minBuffer, SAMPLE_RATE * 2 / 4)
        val created = try {
            AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build(),
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(SAMPLE_RATE)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build(),
                )
                .setTransferMode(AudioTrack.MODE_STREAM)
                .setBufferSizeInBytes(bufferSize)
                .build()
        } catch (e: Exception) {
            Log.w(TAG, "无法创建播放器: ${e.message}")
            return null
        }
        requestFocusLocked()
        val fresh = Output(created)
        output = fresh
        try {
            created.play()
        } catch (e: IllegalStateException) {
            Log.w(TAG, "play failed: ${e.message}")
        }
        fresh.session.start()
        return fresh
    }

    /** 一段会话的写入线程收尾：还是当前这一段才撤焦点，音轨一律由它自己释放。 */
    private fun finish(finished: Output) {
        synchronized(lock) {
            if (output === finished) {
                output = null
                abandonFocusLocked()
            }
        }
        release(finished.track)
    }

    /** 只发停止信号，句柄由写入线程自己释放，主线程不做 join。 */
    fun stop() {
        val stopped = synchronized(lock) {
            val current = output
            if (current != null) {
                output = null
                abandonFocusLocked()
            }
            current
        } ?: return
        stopped.session.finish()
    }

    private fun release(target: AudioTrack) {
        try {
            target.pause()
            target.flush()
        } catch (e: IllegalStateException) {
            Log.w(TAG, "pause on dead track: ${e.message}")
        }
        try {
            target.release()
        } catch (e: Exception) {
            Log.w(TAG, "release failed: ${e.message}")
        }
    }

    private fun requestFocusLocked() {
        val manager = audioManager ?: return
        val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build(),
            )
            .setOnAudioFocusChangeListener(focusListener)
            .build()
        focusRequest = request
        manager.requestAudioFocus(request)
    }

    private fun abandonFocusLocked() {
        val manager = audioManager ?: return
        focusRequest?.let { manager.abandonAudioFocusRequest(it) }
        focusRequest = null
    }

    companion object {
        const val SAMPLE_RATE = 24_000
        private const val TAG = "AudioPlayer"
    }
}
