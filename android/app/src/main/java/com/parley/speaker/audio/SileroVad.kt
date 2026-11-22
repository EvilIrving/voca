package com.parley.speaker.audio

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import com.parley.speaker.domain.SpeechGate
import java.io.Closeable
import java.nio.FloatBuffer
import java.nio.LongBuffer
import java.util.concurrent.ConcurrentHashMap

/**
 * Silero VAD 的 ONNX 推理：整段十六千赫兹单声道音频进，逐 32 毫秒窗出人声概率。
 *
 * 模型是递归的，state 张量逐窗往下传，所以一次判定必须从头开始、按顺序跑完整段，
 * 中途不能跳帧也不能并发；同一实例上的判定用 [lock] 串起来。
 * 会话创建要读两兆多的模型，第一次调用放在后台预热，别落在松手那一刻。
 */
class SileroVad(private val load: () -> ByteArray) : Closeable {

    private val env: OrtEnvironment = OrtEnvironment.getEnvironment()
    private val lock = Any()

    private var session: OrtSession? = null
    private var inputName = ""
    private var stateName = ""
    private var sampleRateName = ""
    private var state = FloatArray(StateSize)

    /** 预热：读完模型建好会话，之后的判定只付推理的钱。 */
    fun warmUp() = synchronized(lock) { session() }

    /** 整段音频的逐窗人声概率；不足一窗时返回空数组。 */
    fun probabilities(pcm: ShortArray): FloatArray {
        val audio = SpeechGate.normalize(pcm)
        val windows = audio.size / SpeechGate.WindowSamples
        if (windows == 0) return FloatArray(0)
        return synchronized(lock) {
            val active = session()
            state = FloatArray(StateSize)
            val out = FloatArray(windows)
            var offset = 0
            for (window in 0 until windows) {
                out[window] = infer(active, audio, offset)
                offset += SpeechGate.WindowSamples
            }
            out
        }
    }

    private fun session(): OrtSession {
        session?.let { return it }
        val created = env.createSession(load(), OrtSession.SessionOptions())
        created.inputNames.forEach { name ->
            when (name) {
                "state" -> stateName = name
                "sr" -> sampleRateName = name
                else -> inputName = name
            }
        }
        session = created
        return created
    }

    private fun infer(active: OrtSession, audio: FloatArray, offset: Int): Float {
        val window = FloatArray(SpeechGate.WindowSamples)
        System.arraycopy(audio, offset, window, 0, window.size)
        val input3d = Array(1) { window }
        val state3d = Array(2) { c ->
            Array(1) {
                FloatArray(Channels) { i -> state[c * Channels + i] }
            }
        }
        val rate1d = longArrayOf(SpeechGate.SampleRate.toLong())
        val inputs = HashMap<String, OnnxTensor>(3)
        OnnxTensor.createTensor(env, input3d as Any).use { input ->
            OnnxTensor.createTensor(env, state3d as Any).use { carried ->
                OnnxTensor.createTensor(env, rate1d as Any).use { rate ->
                    inputs[inputName] = input
                    inputs[stateName] = carried
                    inputs[sampleRateName] = rate
                    active.run(inputs).use { result ->
                        val probability = (result.get(0) as OnnxTensor).floatBuffer.get(0)
                        val next = result.get(1) as OnnxTensor
                        val buffer = next.floatBuffer
                        for (i in state.indices) state[i] = buffer.get(i)
                        return probability
                    }
                }
            }
        }
    }

    override fun close() = synchronized(lock) {
        session?.close()
        session = null
    }

    companion object {
        /** 模型内部递归状态的形状是 2 x 1 x 128。 */
        private const val Channels = 128
        private const val StateSize = 2 * 1 * Channels

        const val AssetName = "silero_vad.onnx"

        /** 按开着的资产文件名缓存一份模型字节，重开录音不必每次读包。 */
        private val models = ConcurrentHashMap<String, ByteArray>()

        fun modelBytes(read: () -> ByteArray, key: String = AssetName): ByteArray =
            models.getOrPut(key, read)
    }
}
