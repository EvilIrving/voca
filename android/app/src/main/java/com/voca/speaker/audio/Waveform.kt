package com.voca.speaker.audio

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * 语音消息的波形是 Telegram 那套紧凑格式：一百根柱子，每根五位，共五百位挤进六十三个字节，
 * 每字节从最高位往下填，末字节剩下四位补零。播放端按位反解即可，不必另行传输长度。
 */
object Waveform {

    const val BARS = 100
    const val PACKED_BYTES = 63

    private const val VALUE_BITS = 5
    private const val MAX_VALUE = 31

    /** 一百根柱子写进五百位；多出的忽略，不够的补零，值先夹进 0..31。 */
    fun pack(levels: IntArray): ByteArray {
        val out = ByteArray(PACKED_BYTES)
        var bit = 0
        for (bar in 0 until BARS) {
            val value = if (bar < levels.size) levels[bar].coerceIn(0, MAX_VALUE) else 0
            for (shift in VALUE_BITS - 1 downTo 0) {
                if ((value shr shift) and 1 == 1) {
                    val index = bit ushr 3
                    out[index] = (out[index].toInt() or (1 shl (7 - (bit and 7)))).toByte()
                }
                bit++
            }
        }
        return out
    }

    /** 字节短了按零补，长了不读，始终还原一百根。 */
    fun unpack(bytes: ByteArray): IntArray {
        val out = IntArray(BARS)
        var bit = 0
        for (bar in 0 until BARS) {
            var value = 0
            for (ignored in 0 until VALUE_BITS) {
                val index = bit ushr 3
                val set = if (index < bytes.size) {
                    (bytes[index].toInt() shr (7 - (bit and 7))) and 1
                } else {
                    0
                }
                value = (value shl 1) or set
                bit++
            }
            out[bar] = value
        }
        return out
    }

    /**
     * 把任意长度的 RMS 序列压成 count 根柱子：按比例分段取每段峰值，再按全局峰值缩到 0..31。
     * 段数多于样本数时退化成就近取样，短录音也能铺满整条波形。
     */
    fun peaks(rms: FloatArray, count: Int = BARS): IntArray {
        if (count <= 0) return IntArray(0)
        val out = IntArray(count)
        if (rms.isEmpty()) return out

        val sampled = FloatArray(count)
        var maxPeak = 0f
        for (bar in 0 until count) {
            val start = (bar.toLong() * rms.size / count).toInt()
            val end = ((bar + 1).toLong() * rms.size / count).toInt().coerceAtMost(rms.size)
            var peak = 0f
            if (end > start) {
                for (index in start until end) peak = max(peak, abs(rms[index]))
            } else {
                peak = abs(rms[start.coerceAtMost(rms.size - 1)])
            }
            sampled[bar] = peak
            maxPeak = max(maxPeak, peak)
        }
        if (maxPeak <= 0f) return out

        for (bar in 0 until count) {
            out[bar] = (sampled[bar] / maxPeak * MAX_VALUE).roundToInt().coerceIn(0, MAX_VALUE)
        }
        return out
    }
}
