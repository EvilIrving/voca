package com.voca.speaker.audio

import com.voca.speaker.domain.SpeechGate
import java.io.File
import kotlin.math.abs
import kotlin.math.PI
import kotlin.math.sin
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 拿真模型跑真音频：语音要过，静音、白噪声、量化噪声都要被拦下，
 * 压到负六十分贝的语音归一化之后仍要过——这条就是"压着嗓子说话"的场景。
 * 撞一下就掉的场景单列：整段的电平参照不能跟着一声撞击走，不然真机上手一摩、
 * 桌面一碰，后面说出来的一整句就被压在模型看不见的地方。
 */
class SileroVadTest {

    private val model = File("src/main/assets/silero_vad.onnx")
    private val speech = File("src/test/resources/speech_16k.pcm")

    private val vad = SileroVad { model.readBytes() }

    private fun pcm(file: File): ShortArray {
        val bytes = file.readBytes()
        return ShortArray(bytes.size / 2) { index ->
            val low = bytes[index * 2].toInt() and 0xFF
            val high = bytes[index * 2 + 1].toInt()
            ((high shl 8) or low).toShort()
        }
    }

    private fun scaled(source: ShortArray, peakDb: Double): ShortArray {
        var peak = 0
        for (sample in source) {
            val value = abs(sample.toInt())
            if (value > peak) peak = value
        }
        if (peak == 0) return source
        val gain = 32768.0 * Math.pow(10.0, peakDb / 20.0) / peak
        return ShortArray(source.size) {
            (source[it] * gain).coerceIn(-32768.0, 32767.0).toInt().toShort()
        }
    }

    private fun noise(size: Int, peakDb: Double, seed: Long): ShortArray {
        val random = java.util.Random(seed)
        val amplitude = 32768.0 * Math.pow(10.0, peakDb / 20.0)
        return ShortArray(size) {
            ((random.nextDouble() * 2 - 1) * amplitude).toInt().toShort()
        }
    }

    private fun tone(size: Int, hz: Double, peakDb: Double): ShortArray {
        val amplitude = 32768.0 * Math.pow(10.0, peakDb / 20.0)
        return ShortArray(size) { (sin(2 * PI * hz * it / SpeechGate.SampleRate) * amplitude).toInt().toShort() }
    }

    @Test
    fun `真实语音判为人声`() {
        val speechMs = SpeechGate.longestSpeechMs(vad.probabilities(pcm(speech)))

        assertTrue("三秒语音里最长的人声段只有 ${speechMs}ms", speechMs >= SpeechGate.MinSpeechMs)
    }

    @Test
    fun `压到负六十分贝的语音照样判成人声`() {
        val quiet = scaled(pcm(speech), -60.0)
        val speechMs = SpeechGate.longestSpeechMs(vad.probabilities(quiet))

        assertTrue("归一化之后应当仍是人声，实际 ${speechMs}ms", speechMs >= SpeechGate.MinSpeechMs)
    }

    @Test
    fun `纯静音判为静音`() {
        val speechMs = SpeechGate.longestSpeechMs(vad.probabilities(ShortArray(SpeechGate.SampleRate * 3)))

        assertTrue(SpeechGate.shouldDrop(speechMs))
    }

    @Test
    fun `白噪声判为静音`() {
        for (db in listOf(-40.0, -55.0, -65.0)) {
            val speechMs = SpeechGate.longestSpeechMs(vad.probabilities(noise(SpeechGate.SampleRate * 3, db, 7)))
            assertFalse("${db}dBFS 的白噪声不该当成人声，实际 ${speechMs}ms", speechMs >= SpeechGate.MinSpeechMs)
        }
    }

    @Test
    fun `单音正弦判为静音`() {
        val speechMs = SpeechGate.longestSpeechMs(vad.probabilities(tone(SpeechGate.SampleRate * 3, 220.0, -20.0)))

        assertFalse("纯音不是人声，实际 ${speechMs}ms", speechMs >= SpeechGate.MinSpeechMs)
    }

    @Test
    fun `开头一声撞击照样判成人声`() {
        val pcm = scaled(pcm(speech), -30.0)
        val transient = noise(320, -3.0, 11)
        transient.copyInto(pcm, 0)

        val speechMs = SpeechGate.longestSpeechMs(vad.probabilities(pcm))

        assertTrue("撞击之后仍应判成人声，实际 ${speechMs}ms", speechMs >= SpeechGate.MinSpeechMs)
    }

    @Test
    fun `很轻的语音加一声撞击照样判成人声`() {
        val pcm = scaled(pcm(speech), -55.0)
        val transient = noise(320, -3.0, 13)
        transient.copyInto(pcm, 0)

        val speechMs = SpeechGate.longestSpeechMs(vad.probabilities(pcm))

        assertTrue("实际 ${speechMs}ms", speechMs >= SpeechGate.MinSpeechMs)
    }

    @Test
    fun `长段底噪里的一段语音照样判成人声`() {
        val speechFrames = scaled(pcm(speech), -30.0)
        val pcm = noise(SpeechGate.SampleRate * 6, -55.0, 17)
        speechFrames.copyInto(pcm, SpeechGate.SampleRate * 3 / 2)

        val speechMs = SpeechGate.longestSpeechMs(vad.probabilities(pcm))

        assertTrue("实际 ${speechMs}ms", speechMs >= SpeechGate.MinSpeechMs)
    }

    @Test
    fun `不足一窗的音频给不出概率`() {
        assertTrue(vad.probabilities(ShortArray(SpeechGate.WindowSamples - 1)).isEmpty())
    }
}
