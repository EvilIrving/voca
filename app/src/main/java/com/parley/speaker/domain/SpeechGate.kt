package com.parley.speaker.domain

import kotlin.math.log10
import kotlin.math.max
import kotlin.math.sqrt

/**
 * 静音闸门：收完音之后判断这一轮值不值得送去识别与生成。
 * 只有两个丢弃条件：时长短于 [MinSpeechMs]，或者整段没有一帧高出环境底噪 [FloorMarginDb]。
 * 底噪基线取各帧能量的低分位而不是写死数值，空调声与街道噪声抬起来时基线跟着抬。
 */
object SpeechGate {

    /** 与 AudioCapture 的帧长一致，二十毫秒一帧。 */
    const val FrameMs = 20L

    const val MinSpeechMs = 400L

    /** 高过底噪这么多分贝才算人声，低于它的起伏属于环境自己的抖动。 */
    const val FloorMarginDb = 6.0

    /** 低分位而不是均值：中途一声咳嗽、一次翻页不该把基线拉高。 */
    private const val FloorPercentile = 0.2

    private const val FullScale = 32768.0
    private const val SilenceDb = -120.0

    /** 一帧的均方根电平，单位 dBFS，满量程为零。 */
    fun frameDb(samples: ShortArray): Double {
        if (samples.isEmpty()) return SilenceDb
        var sum = 0.0
        for (sample in samples) {
            val value = sample.toDouble()
            sum += value * value
        }
        val rms = sqrt(sum / samples.size)
        return 20.0 * log10(max(rms, 1.0) / FullScale)
    }

    fun noiseFloor(framesDb: DoubleArray): Double {
        if (framesDb.isEmpty()) return SilenceDb
        val sorted = framesDb.sorted()
        val index = ((sorted.size - 1) * FloorPercentile).toInt()
        return sorted[index]
    }

    /** 只拿到统计量时的入口：时长、峰值、底噪都已经算好。 */
    fun shouldDrop(durationMs: Long, peakDb: Double, floorDb: Double): Boolean =
        durationMs < MinSpeechMs || peakDb < floorDb + FloorMarginDb

    /** 拿到整段帧序列时的入口，底噪从这段帧自己身上取。 */
    fun shouldDrop(framesDb: DoubleArray): Boolean = shouldDrop(
        durationMs = framesDb.size.toLong() * FrameMs,
        peakDb = framesDb.maxOrNull() ?: SilenceDb,
        floorDb = noiseFloor(framesDb),
    )
}
