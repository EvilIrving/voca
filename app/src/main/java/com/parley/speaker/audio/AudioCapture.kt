package com.parley.speaker.audio

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Handler
import android.os.Looper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.sqrt

/**
 * 上行采集：十六千赫兹单声道十六位小端，按二十毫秒一帧产出，同时给出归一化电平驱动波形。
 */
class AudioCapture(private val scope: CoroutineScope) {

    private var record: AudioRecord? = null
    private var job: Job? = null
    private val mainHandler = Handler(Looper.getMainLooper())

    val isRunning: Boolean get() = job?.isActive == true

    @SuppressLint("MissingPermission")
    fun start(onFrame: (ShortArray) -> Unit, onLevel: (Float) -> Unit): Boolean {
        if (isRunning) return true
        val minBuffer = AudioRecord.getMinBufferSize(
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        if (minBuffer <= 0) return false
        val record = try {
            AudioRecord(
                MediaRecorder.AudioSource.VOICE_RECOGNITION,
                SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                maxOf(minBuffer, FRAME_BYTES * 8),
            )
        } catch (e: IllegalArgumentException) {
            return false
        } catch (e: SecurityException) {
            return false
        }
        if (record.state != AudioRecord.STATE_INITIALIZED) {
            record.release()
            return false
        }
        this.record = record
        record.startRecording()
        job = scope.launch(Dispatchers.IO) {
            val buffer = ShortArray(FRAME_SAMPLES)
            while (isActive) {
                val read = record.read(buffer, 0, buffer.size)
                if (read <= 0) {
                    if (read < 0) break else continue
                }
                val frame = buffer.copyOf(read)
                onFrame(frame)
                val level = levelOf(frame)
                mainHandler.post { onLevel(level) }
            }
        }
        return true
    }

    fun stop() {
        job?.cancel()
        mainHandler.removeCallbacksAndMessages(null)
        job = null
        record?.let {
            if (it.recordingState == AudioRecord.RECORDSTATE_RECORDING) it.stop()
            it.release()
        }
        record = null
    }

    private fun levelOf(frame: ShortArray): Float {
        var sum = 0.0
        for (sample in frame) {
            val value = sample.toDouble()
            sum += value * value
        }
        val rms = sqrt(sum / frame.size.coerceAtLeast(1))
        return (rms / LOUD_RMS).coerceIn(0.0, 1.0).toFloat()
    }

    companion object {
        const val SAMPLE_RATE = 16_000
        const val FRAME_MS = 20
        const val FRAME_SAMPLES = SAMPLE_RATE * FRAME_MS / 1000
        const val FRAME_BYTES = FRAME_SAMPLES * 2
        private const val LOUD_RMS = 2_600.0
    }
}
