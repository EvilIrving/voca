package com.parley.speaker.audio

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.util.Log
import java.util.concurrent.LinkedBlockingQueue
import kotlin.concurrent.thread

/**
 * 下行播放：二十四千赫兹单声道十六位，写入走独立线程，界面线程只投递数据块。
 */
class AudioPlayer {

    private val queue = LinkedBlockingQueue<ByteArray>()
    private var track: AudioTrack? = null
    private var worker: Thread? = null

    @Volatile
    private var running = false

    fun start() {
        if (running) return
        val minBuffer = AudioTrack.getMinBufferSize(
            SAMPLE_RATE,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        val bufferSize = maxOf(minBuffer, SAMPLE_RATE * 2 / 4)
        val track = try {
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
            return
        }
        this.track = track
        track.play()
        running = true
        worker = thread(name = "parley-audio-out") {
            while (true) {
                val chunk = try {
                    queue.take()
                } catch (e: InterruptedException) {
                    break
                }
                if (chunk.isEmpty()) break
                try {
                    track.write(chunk, 0, chunk.size)
                } catch (e: Exception) {
                    Log.w(TAG, "write failed: ${e.message}")
                    break
                }
            }
        }
    }

    fun enqueue(pcm: ByteArray) {
        if (!running) start()
        queue.offer(pcm)
    }

    fun flush() {
        queue.clear()
        track?.let {
            it.pause()
            it.flush()
            it.play()
        }
    }

    fun stop() {
        running = false
        queue.offer(ByteArray(0))
        worker?.join(500)
        worker = null
        track?.let {
            try {
                it.pause()
                it.flush()
            } catch (e: IllegalStateException) {
                Log.w(TAG, "flush on dead track")
            }
            it.release()
        }
        track = null
        queue.clear()
    }

    companion object {
        const val SAMPLE_RATE = 24_000
        private const val TAG = "AudioPlayer"
    }
}
