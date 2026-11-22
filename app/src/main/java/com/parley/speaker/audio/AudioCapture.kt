package com.parley.speaker.audio

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Handler
import android.os.Looper
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.sqrt

/**
 * 上行采集：十六千赫兹单声道十六位小端，按二十毫秒一帧产出，同时给出归一化电平驱动波形。
 * 读循环无论正常结束还是读异常，都走同一条释放路径，不留下占用麦克风的句柄。
 */
class AudioCapture(private val scope: CoroutineScope) {

    private val lock = Any()
    private var record: AudioRecord? = null
    private var job: Job? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    private var smoothed = 0f

    val isRunning: Boolean get() = synchronized(lock) { record != null }

    fun start(onFrame: (ShortArray) -> Unit, onLevel: (Float) -> Unit): Boolean {
        synchronized(lock) {
            record?.let { stale ->
                job?.cancel()
                job = null
                releaseRecord(stale)
                record = null
            }
            val minBuffer = AudioRecord.getMinBufferSize(
                SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
            )
            if (minBuffer <= 0) return false
            val created = createRecord(minBuffer) ?: return false
            try {
                created.startRecording()
            } catch (e: IllegalStateException) {
                Log.w(TAG, "startRecording failed: ${e.message}")
                releaseRecord(created)
                return false
            }
            record = created
            active = this
            Log.i(TAG, "capture started")
            job = scope.launch(Dispatchers.IO) {
                readLoop(created, onFrame, onLevel) { isActive }
            }
            return true
        }
    }

    @SuppressLint("MissingPermission")
    private fun createRecord(minBuffer: Int): AudioRecord? {
        val created = try {
            AudioRecord(
                MediaRecorder.AudioSource.VOICE_RECOGNITION,
                SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                maxOf(minBuffer, FRAME_BYTES * 8),
            )
        } catch (e: IllegalArgumentException) {
            Log.w(TAG, "AudioRecord rejected: ${e.message}")
            return null
        } catch (e: SecurityException) {
            Log.w(TAG, "no permission: ${e.message}")
            return null
        }
        if (created.state != AudioRecord.STATE_INITIALIZED) {
            created.release()
            return null
        }
        return created
    }

    private fun readLoop(
        source: AudioRecord,
        onFrame: (ShortArray) -> Unit,
        onLevel: (Float) -> Unit,
        isActive: () -> Boolean,
    ) {
        val buffer = ShortArray(FRAME_SAMPLES)
        try {
            while (isActive()) {
                val read = try {
                    source.read(buffer, 0, buffer.size)
                } catch (e: IllegalStateException) {
                    Log.w(TAG, "read after release: ${e.message}")
                    break
                }
                if (read < 0) {
                    Log.w(TAG, "read error $read")
                    break
                }
                if (read == 0) continue
                val frame = buffer.copyOf(read)
                onFrame(frame)
                val level = levelOf(frame)
                mainHandler.post { onLevel(level) }
            }
        } finally {
            synchronized(lock) {
                if (record === source) {
                    record = null
                    job = null
                    active = null
                    releaseRecord(source)
                    Log.i(TAG, "capture released by read loop")
                }
            }
        }
    }

    fun stop() {
        val current = synchronized(lock) {
            val held = record
            record = null
            job?.cancel()
            job = null
            held
        }
        mainHandler.removeCallbacksAndMessages(null)
        active = null
        current?.let {
            releaseRecord(it)
            Log.i(TAG, "capture stopped")
        }
    }

    private fun releaseRecord(target: AudioRecord) {
        try {
            if (target.recordingState == AudioRecord.RECORDSTATE_RECORDING) target.stop()
        } catch (e: IllegalStateException) {
            Log.w(TAG, "stop failed: ${e.message}")
        }
        try {
            target.release()
        } catch (e: Exception) {
            Log.w(TAG, "release failed: ${e.message}")
        }
    }

    /** 分贝到 0..1 的感知映射，并做上升快下降慢的双系数平滑，出去的 level 可直接绘制。 */
    private fun levelOf(frame: ShortArray): Float {
        var sum = 0.0
        for (sample in frame) {
            val value = sample.toDouble()
            sum += value * value
        }
        val rms = sqrt(sum / frame.size.coerceAtLeast(1))
        val db = 20.0 * log10(max(rms, 1.0) / 32768.0)
        val target = ((db + DB_FLOOR) / DB_SPAN).coerceIn(0.0, 1.0).toFloat()
        smoothed += (target - smoothed) * if (target > smoothed) ATTACK else RELEASE
        return smoothed
    }

    companion object {
        @Volatile
        private var active: AudioCapture? = null

        /** 供自动化验证读取的采集状态，避免依赖被 ROM 屏蔽的 logcat。 */
        fun isCapturing(): Boolean = active?.isRunning == true

        const val SAMPLE_RATE = 16_000
        const val FRAME_MS = 20
        const val FRAME_SAMPLES = SAMPLE_RATE * FRAME_MS / 1000
        const val FRAME_BYTES = FRAME_SAMPLES * 2
        private const val DB_FLOOR = 55.0
        private const val DB_SPAN = 35.0
        private const val ATTACK = 0.6f
        private const val RELEASE = 0.15f
        private const val TAG = "AudioCapture"
    }
}
