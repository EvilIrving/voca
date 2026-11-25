package com.voca.speaker.audio

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaRecorder
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import com.voca.speaker.domain.CaptureFailure
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.OutputStream
import java.util.UUID
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.sqrt
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 一段语音消息的采集与编码。采集固定四十八千赫兹单声道十六位、音源取 VOICE_RECOGNITION，
 * 边录边用 MediaCodec 的 Opus 编码器压成二十毫秒一包，再封成 OGG 写进缓存；
 * 同一份采集同时降采样成十六千赫兹推给实时会话，按下说话只占用一路麦克风。
 *
 * 读循环在自己的线程里只做这几件事：读一帧、送编码、降采样、算电平与波形。AudioRecord 由读循环自己释放，
 * 停止时先在别的线程调 stop 把阻塞中的 read 唤醒，循环退出后在同一处释放句柄，
 * 正常结束、读异常与作废走的是同一条收尾路径，不会把麦克风留在手里。
 *
 * 暂停态把文件与编码器原样留着，只是不再读麦，恢复时接回同一只；结束才写 EOS 页。
 * 编码器开不起来时照常采集与推送，只是不产出文件：日志留一条，hold 与 finish 返回 null，不假装录成。
 * 跨线程的字段与回调统一由一把锁保护，回调本身在锁外调用，避免界面那边回头调进来造成重入。
 */
class VoiceRecorder(private val scope: CoroutineScope) {

    private val lock = Any()
    private val mainHandler = Handler(Looper.getMainLooper())

    private var session: Session? = null
    private var record: AudioRecord? = null
    private var job: Job? = null
    private var capturing = false
    private var onFrame: ((ShortArray) -> Unit)? = null
    private var onLevel: ((Float) -> Unit)? = null
    private var onInterrupted: ((CaptureFailure) -> Unit)? = null

    /** 上一次 start 起不来的原因，start 返回 false 之后读这一格挑提示语。 */
    var lastFailure: CaptureFailure? = null
        private set
    private var smoothed = 0f

    val isRunning: Boolean get() = synchronized(lock) { capturing }

    fun start(
        cacheDir: File,
        onFrame: (ShortArray) -> Unit,
        onLevel: (Float) -> Unit,
        onInterrupted: (CaptureFailure) -> Unit = {},
    ): Boolean {
        val pressedAt = SystemClock.elapsedRealtime()
        synchronized(lock) {
            if (session != null || record != null || capturing) {
                lastFailure = CaptureFailure.Busy
                return false
            }
            val created = createRecord() ?: return false
            val dir = File(cacheDir, Directory)
            if (!dir.isDirectory && !dir.mkdirs()) {
                Log.w(TAG, "cache dir unavailable: ${dir.absolutePath}")
                releaseRecord(created)
                return false
            }
            val file = File(dir, "${UUID.randomUUID()}.ogg")
            val out = try {
                BufferedOutputStream(FileOutputStream(file))
            } catch (e: IOException) {
                Log.w(TAG, "open cache failed: ${e.message}")
                releaseRecord(created)
                return false
            }
            val writer = OggOpusWriter(out)
            try {
                writer.writeHeaders()
            } catch (e: IOException) {
                Log.w(TAG, "write headers failed: ${e.message}")
                closeQuietly(out)
                file.delete()
                releaseRecord(created)
                return false
            }
            val codec = openEncoder()
            if (codec == null) Log.w(TAG, "opus encoder unavailable, uplink only")
            if (!startRecording(created)) {
                closeCodec(codec)
                closeQuietly(out)
                file.delete()
                releaseRecord(created)
                return false
            }
            val owner = Session(file, out, writer, codec, Resampler(), InputGain()).also { it.trace.opened(pressedAt) }
            session = owner
            active = this
            record = created
            this.onFrame = onFrame
            this.onLevel = onLevel
            this.onInterrupted = onInterrupted
            capturing = true
            smoothed = 0f
            job = scope.launch(Dispatchers.IO) { captureLoop(created, owner) }
            Log.i(TAG, "recording ${file.name}, encoder=${codec != null}")
            return true
        }
    }

    /** 暂停：停采并把编码器里剩下的包抽干写完，不写 EOS，文件与编码器留着等 resume。 */
    suspend fun hold(): VoiceClip? {
        val owner = synchronized(lock) { session } ?: return null
        stopCapture(owner)
        return withContext(Dispatchers.IO) {
            synchronized(lock) {
                when {
                    session !== owner -> null
                    owner.codec == null || owner.broken -> {
                        abandon(owner)
                        null
                    }

                    else -> {
                        drainEncoder(owner, idleWaits = HOLD_IDLE_WAITS)
                        clipOf(owner)
                    }
                }
            }
        }
    }

    fun resume(): Boolean {
        synchronized(lock) {
            val owner = session ?: return false
            if (capturing || record != null || owner.broken) return false
            val created = createRecord() ?: return false
            if (!startRecording(created)) {
                releaseRecord(created)
                return false
            }
            record = created
            capturing = true
            smoothed = 0f
            job = scope.launch(Dispatchers.IO) { captureLoop(created, owner) }
            Log.i(TAG, "recording resumed")
            return true
        }
    }

    /** 结束：停采、抽干、写 EOS 与末页、关文件，返回整段。后续再调 hold 拿不到东西。 */
    suspend fun finish(): VoiceClip? {
        val owner = synchronized(lock) { session } ?: return null
        stopCapture(owner)
        return withContext(Dispatchers.IO) {
            synchronized(lock) {
                if (session !== owner) {
                    null
                } else {
                    val clip = if (owner.codec == null || owner.broken) {
                        null
                    } else {
                        drainEncoder(owner, idleWaits = HOLD_IDLE_WAITS)
                        queueEndOfStream(owner)
                        drainEncoder(owner, idleWaits = IdleWaits)
                        try {
                            owner.writer.finish()
                        } catch (e: IOException) {
                            Log.w(TAG, "write eos failed: ${e.message}")
                        }
                        clipOf(owner)
                    }
                    closeSession(owner)
                    if (clip == null) owner.file.delete()
                    session = null
                    onFrame = null
                    onLevel = null
                    onInterrupted = null
                    clip
                }
            }
        }
    }

    /** 作废：停采、关文件、删掉。编码器与文件只能在读循环退出之后碰，关闭排在它后面。 */
    fun cancel() {
        var owner: Session? = null
        var running: Job? = null
        synchronized(lock) {
            val held = session
            if (held != null) {
                owner = held
                running = job
                session = null
                capturing = false
                onFrame = null
                onLevel = null
                onInterrupted = null
                mainHandler.removeCallbacksAndMessages(null)
                // 句柄留给读循环释放，这里只把阻塞中的 read 唤醒
                try {
                    record?.stop()
                } catch (e: IllegalStateException) {
                    Log.w(TAG, "stop failed: ${e.message}")
                }
                record = null
                job = null
            }
        }
        val target = owner ?: return
        val pending = running
        if (pending == null || pending.isCompleted) {
            closeSession(target)
            target.file.delete()
        } else {
            scope.launch(Dispatchers.IO) {
                pending.join()
                closeSession(target)
                target.file.delete()
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun createRecord(): AudioRecord? {
        lastFailure = CaptureFailure.Device
        val minBuffer = AudioRecord.getMinBufferSize(
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        if (minBuffer <= 0) {
            Log.w(TAG, "min buffer $minBuffer")
            lastFailure = CaptureFailure.Device
            return null
        }
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
            lastFailure = CaptureFailure.Permission
            return null
        }
        if (created.state != AudioRecord.STATE_INITIALIZED) {
            created.release()
            return null
        }
        return created
    }

    private fun startRecording(target: AudioRecord): Boolean = try {
        target.startRecording()
        lastFailure = null
        true
    } catch (e: IllegalStateException) {
        Log.w(TAG, "startRecording failed: ${e.message}")
        lastFailure = CaptureFailure.Busy
        false
    }

    private fun openEncoder(): MediaCodec? {
        val format = MediaFormat.createAudioFormat(MIME_OPUS, SAMPLE_RATE, CHANNELS).apply {
            setInteger(MediaFormat.KEY_BIT_RATE, BIT_RATE)
            setInteger(MediaFormat.KEY_BITRATE_MODE, MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR)
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, FRAME_BYTES * 4)
        }
        val codec = try {
            MediaCodec.createEncoderByType(MIME_OPUS)
        } catch (e: Exception) {
            Log.w(TAG, "no opus encoder: ${e.message}")
            return null
        }
        return try {
            codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            codec.start()
            codec
        } catch (e: Exception) {
            Log.w(TAG, "opus encoder rejected: ${e.message}")
            closeCodec(codec)
            null
        }
    }

    private suspend fun captureLoop(source: AudioRecord, owner: Session) {
        val buffer = ShortArray(FRAME_SAMPLES)
        var broken: CaptureFailure? = null
        try {
            while (true) {
                val keepGoing = synchronized(lock) { capturing && session === owner && record === source } &&
                    currentCoroutineContext().isActive
                if (!keepGoing) break
                val read = try {
                    source.read(buffer, 0, buffer.size)
                } catch (e: IllegalStateException) {
                    Log.w(TAG, "read after release: ${e.message}")
                    break
                }
                if (read < 0) {
                    Log.w(TAG, "read error $read")
                    broken = CaptureFailure.Device
                    break
                }
                if (read == 0) continue
                owner.trace.started(SystemClock.elapsedRealtime(), FRAME_MS.toLong())
                val frame = if (read == buffer.size) buffer.copyOf() else buffer.copyOf(read)

                // 增益之前的电平要单独留一份：闸门判"这一段有没有动静"只能看放大前的电平，
                // 放大后的电平里带着增益爬坡本身，安静的一段也会被判成有人在说话。
                var rawSquares = 0.0
                for (sample in frame) {
                    val value = sample.toDouble()
                    rawSquares += value * value
                }
                // 增益先于编码与上行：小声说话在这里被拉回工作电平，写进 OGG 的那份与送给模型的同一份。
                owner.gain.apply(frame)

                var squares = 0.0
                for (sample in frame) {
                    val value = sample.toDouble()
                    squares += value * value
                }

                var uplink: ShortArray? = null
                var level = 0f
                var frameSink: ((ShortArray) -> Unit)? = null
                var levelSink: ((Float) -> Unit)? = null
                synchronized(lock) {
                    if (session === owner) {
                        // 电平与编码同一把锁：判定线程会来读这一串，跨线程追加不能落在锁外。
                        owner.inputLevels.add(sqrt(rawSquares / frame.size).toFloat())
                        encodeFrame(owner, frame)
                        drainEncoder(owner, idleWaits = 0)
                        uplink = passThrough(owner, frame)
                        accumulateWaveform(owner, frame.size, squares)
                        level = levelOf(rawSquares, frame.size)
                        frameSink = onFrame
                        levelSink = onLevel
                    }
                }

                uplink?.let { pcm -> frameSink?.invoke(pcm) }
                if (levelSink != null) {
                    val measured = level
                    mainHandler.post {
                        val sink = synchronized(lock) { onLevel }
                        sink?.invoke(measured)
                    }
                }
            }
        } catch (t: Throwable) {
            Log.w(TAG, "capture loop failed: ${t.message}")
            broken = CaptureFailure.Device
        } finally {
            // 句柄由持有它的线程释放；字段只在这一段还是当前那一段时才清，免得盖掉后起的一段
            releaseRecord(source)
            val report = synchronized(lock) {
                if (record === source) {
                    record = null
                    job = null
                    capturing = false
                    // 采样线程自己退出说明这一轮已经录不下去了，界面那边要按失败收尾，
                    // 不能让输入条停在录音态等一个不会再来的结束。
                    if (broken != null && session === owner) broken else null
                } else {
                    null
                }
            }
            if (report != null) {
                lastFailure = report
                val sink = synchronized(lock) { onInterrupted }
                mainHandler.post { sink?.invoke(report) }
            }
        }
    }

    /** 送进编码器；输入缓冲按实际容量切，编码器预分配得小也不会丢帧。 */
    private fun encodeFrame(owner: Session, frame: ShortArray) {
        val codec = owner.codec ?: return
        var offset = 0
        while (offset < frame.size) {
            val index = try {
                codec.dequeueInputBuffer(INPUT_TIMEOUT_US)
            } catch (e: IllegalStateException) {
                Log.w(TAG, "encoder lost: ${e.message}")
                owner.broken = true
                return
            }
            if (index < 0) {
                Log.w(TAG, "encoder input starved")
                return
            }
            val input = codec.getInputBuffer(index)
            if (input == null) {
                Log.w(TAG, "encoder input buffer null")
                owner.broken = true
                return
            }
            input.clear()
            val capacity = input.capacity() / 2
            val take = minOf(frame.size - offset, capacity)
            val bytes = owner.pcmBytes
            for (slot in 0 until take) {
                val value = frame[offset + slot].toInt()
                bytes[slot * 2] = (value and 0xFF).toByte()
                bytes[slot * 2 + 1] = ((value shr 8) and 0xFF).toByte()
            }
            input.put(bytes, 0, take * 2)
            codec.queueInputBuffer(index, 0, take * 2, owner.fedSamples * 1_000_000L / SAMPLE_RATE, 0)
            owner.fedSamples += take
            offset += take
        }
    }

    /**
     * 抽干编码器输出。每个输出缓冲就是一个 Opus 包，写成 Ogg 页时 granule 记到已送入的样本数，
     * 播放器按这个数算时长。idleWaits 为零时完全不阻塞读循环，暂停与结束才等几个回合。
     */
    private fun drainEncoder(owner: Session, idleWaits: Int) {
        val codec = owner.codec ?: return
        val info = owner.info
        var idle = 0
        while (true) {
            val index = try {
                codec.dequeueOutputBuffer(info, if (idleWaits == 0) 0L else WAIT_US)
            } catch (e: IllegalStateException) {
                Log.w(TAG, "encoder lost: ${e.message}")
                owner.broken = true
                return
            }
            when {
                index == MediaCodec.INFO_TRY_AGAIN_LATER -> {
                    if (idle++ >= idleWaits) return
                }

                index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> Unit

                index >= 0 -> {
                    val endsStream = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                    if (info.size > 0 && info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0) {
                        writePacket(owner, index, info)
                    }
                    codec.releaseOutputBuffer(index, false)
                    if (endsStream) return
                }

                else -> Unit
            }
        }
    }

    private fun writePacket(owner: Session, index: Int, info: MediaCodec.BufferInfo) {
        val buffer = owner.codec?.getOutputBuffer(index) ?: return
        buffer.position(info.offset)
        val packet = ByteArray(info.size)
        buffer.get(packet, 0, info.size)
        try {
            owner.writer.writePacket(packet, owner.fedSamples)
        } catch (e: IOException) {
            Log.w(TAG, "write packet failed: ${e.message}")
            owner.broken = true
        }
    }

    /** 结束输入：送一个零长度且带 EOS 标志的缓冲，编码器把残留帧吐完之后收尾。 */
    private fun queueEndOfStream(owner: Session) {
        val codec = owner.codec ?: return
        var attempts = 0
        while (attempts++ < IdleWaits) {
            val index = try {
                codec.dequeueInputBuffer(WAIT_US)
            } catch (e: IllegalStateException) {
                Log.w(TAG, "encoder lost: ${e.message}")
                return
            }
            if (index >= 0) {
                codec.queueInputBuffer(
                    index,
                    0,
                    0,
                    owner.fedSamples * 1_000_000L / SAMPLE_RATE,
                    MediaCodec.BUFFER_FLAG_END_OF_STREAM,
                )
                return
            }
        }
    }

    /**
     * 降采样成十六千赫兹并凑满二十毫秒才交出去。降采样在每个块边界上会晚几个样本，
     * 一次读出三百一十五到三百二十个，攒进预分配的那条带里，满了才抬一整帧给实时会话。
     */
    private fun passThrough(owner: Session, frame: ShortArray): ShortArray? {
        val resampled = owner.resampler.process(frame)
        if (resampled.isEmpty()) return null
        var read = 0
        var ready: ShortArray? = null
        while (read < resampled.size) {
            val take = minOf(UPLINK_SAMPLES - owner.carryFill, resampled.size - read)
            resampled.copyInto(owner.carry, owner.carryFill, read, read + take)
            owner.carryFill += take
            read += take
            if (owner.carryFill == UPLINK_SAMPLES) {
                // 一拍最多攒出一帧（输入满窗三百二十，加上留桩也到不了两帧），多的留在带里
                if (ready == null) ready = owner.carry.copyOf()
                owner.carryFill = 0
            }
        }
        return ready
    }

    /**
     * 分贝到 0..1 的感知映射，上升快下降慢，出去的电平喂给光晕与电平柱。
     *
     * 取的是增益之前那一份：增益照着最近三秒的环境声把电平拉到负二十，安静的一轮里
     * 它自己爬到三十，放大后的电平跟着一路顶满，光晕胀到两三倍、电平柱也满格，
     * 真说话的时候反而正常 —— 界面上那圈呼吸就成了在画增益。
     */
    private fun levelOf(rawSquares: Double, samples: Int): Float {
        val rms = sqrt(rawSquares / samples.coerceAtLeast(1))
        val db = 20.0 * log10(max(rms, 1.0) / 32768.0)
        val target = ((db + DB_FLOOR) / DB_SPAN).coerceIn(0.0, 1.0).toFloat()
        smoothed += (target - smoothed) * if (target > smoothed) ATTACK else RELEASE
        return smoothed
    }

    /**
     * 一百毫秒一格的 RMS 攒起来就是收尾时压成波形的那串数。
     * 这一份取增益之后，画出来的高低跟模型听到的一致。
     */
    private fun accumulateWaveform(owner: Session, samples: Int, squares: Double) {
        owner.windowSquares += squares
        owner.windowCount += samples
        if (owner.windowCount >= WAVEFORM_SAMPLES) {
            owner.levels += sqrt(owner.windowSquares / owner.windowCount).toFloat()
            owner.windowSquares = 0.0
            owner.windowCount = 0
        }
    }

    /** 增益之前的逐帧 RMS，收尾判定读一份副本。 */
    fun peekLevels(): FloatArray = synchronized(lock) {
        session?.inputLevels?.toFloatArray() ?: FloatArray(0)
    }

    private fun clipOf(owner: Session): VoiceClip {
        Log.i(TAG, "clip ${owner.file.name} ${owner.gain.describe()} ${owner.trace.describe()}")
        flushQuietly(owner.out)
        return VoiceClip(
            path = owner.file.absolutePath,
            durationMs = owner.fedSamples * 1000L / SAMPLE_RATE,
            waveform = Waveform.pack(Waveform.peaks(owner.levels.toFloatArray(), Waveform.BARS)),
        )
    }

    /** 停采并等读循环退出：先置标记再唤醒阻塞中的 read，收尾全部落在读循环自己的 finally 里。 */
    private suspend fun stopCapture(owner: Session) {
        val running = synchronized(lock) {
            if (session !== owner) return
            capturing = false
            mainHandler.removeCallbacksAndMessages(null)
            try {
                record?.stop()
            } catch (e: IllegalStateException) {
                Log.w(TAG, "stop failed: ${e.message}")
            }
            job
        }
        running?.join()
    }

    private fun abandon(owner: Session) {
        closeSession(owner)
        owner.file.delete()
        session = null
        onFrame = null
        onLevel = null
    }

    private fun closeSession(owner: Session) {
        closeCodec(owner.codec)
        flushQuietly(owner.out)
        closeQuietly(owner.out)
    }

    private fun closeCodec(codec: MediaCodec?) {
        if (codec == null) return
        try {
            codec.stop()
        } catch (e: Exception) {
            Log.w(TAG, "codec stop failed: ${e.message}")
        }
        try {
            codec.release()
        } catch (e: Exception) {
            Log.w(TAG, "codec release failed: ${e.message}")
        }
    }

    private fun releaseRecord(target: AudioRecord) {
        try {
            if (target.recordingState == AudioRecord.RECORDSTATE_RECORDING) target.stop()
        } catch (e: IllegalStateException) {
            Log.w(TAG, "record stop failed: ${e.message}")
        }
        try {
            target.release()
        } catch (e: Exception) {
            Log.w(TAG, "record release failed: ${e.message}")
        }
    }

    private fun flushQuietly(out: OutputStream) {
        try {
            out.flush()
        } catch (e: IOException) {
            Log.w(TAG, "flush failed: ${e.message}")
        }
    }

    private fun closeQuietly(out: OutputStream) {
        try {
            out.close()
        } catch (e: IOException) {
            Log.w(TAG, "close failed: ${e.message}")
        }
    }

    private class Session(
        val file: File,
        val out: OutputStream,
        val writer: OggOpusWriter,
        val codec: MediaCodec?,
        val resampler: Resampler,
        val gain: InputGain,
        val trace: CaptureTrace = CaptureTrace(),
    ) {
        /** 已送进编码器的样本数，时长与 granule 都由它推。 */
        var fedSamples = 0L
        var broken = false
        /** 增益之前逐帧的 RMS，闸门判有没有动静用这一串。 */
        val inputLevels = ArrayList<Float>()
        val pcmBytes = ByteArray(FRAME_BYTES)
        val info = MediaCodec.BufferInfo()
        val levels = ArrayList<Float>()
        val carry = ShortArray(UPLINK_SAMPLES)
        var carryFill = 0
        var windowSquares = 0.0
        var windowCount = 0
    }

    companion object {
        /** 调试探针要问「现在是不是在收音」，与界面无关，留一个进程级入口。 */
        @Volatile
        private var active: VoiceRecorder? = null

        fun isCapturing(): Boolean = active?.isRunning == true

        private const val TAG = "VoiceRecorder"
        const val Directory = "voice"
        const val MIME_OPUS = "audio/opus"
        const val SAMPLE_RATE = 48_000
        const val CHANNELS = 1
        const val BIT_RATE = 32_000
        const val FRAME_MS = 20
        const val FRAME_SAMPLES = SAMPLE_RATE * FRAME_MS / 1000
        const val FRAME_BYTES = FRAME_SAMPLES * 2
        const val UPLINK_SAMPLES = 16_000 * FRAME_MS / 1000
        const val WAVEFORM_SAMPLES = SAMPLE_RATE / 10
        const val INPUT_TIMEOUT_US = 10_000L
        const val WAIT_US = 10_000L
        const val IdleWaits = 50
        const val HOLD_IDLE_WAITS = 2
        const val DB_FLOOR = 55.0
        const val DB_SPAN = 35.0
        const val ATTACK = 0.6f
        const val RELEASE = 0.15f
    }
}
