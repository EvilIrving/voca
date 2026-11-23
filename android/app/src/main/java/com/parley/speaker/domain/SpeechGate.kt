package com.parley.speaker.domain

import kotlin.math.pow
import kotlin.math.sqrt

/**
 * 静音闸门：收完音之后判断这一轮里有没有人说话，判据是本地 VAD 模型给出的逐窗人声概率，
 * 不是音量。模型（Silero VAD，2.2 MB，MIT）随包走，不联网。
 *
 * 归一化这一步是整个过程的关键：整段先抹掉直流、把一个分位电平拉到 [NormalizeLevelDb]，再送模型。
 * 实测同一个模型、同一句压到负六十分贝的语音，不归一化时人声概率是零，归一化之后是九成；
 * 白噪声与量化噪声归一化之后仍然接近零，因为模型看的是频谱形状而不是响度。
 * 手机麦克风拾音、压着嗓子说话、离得远，都会被这一行拉回同一个工作点。
 *
 * 电平参照取各窗 RMS 的 [ReferencePercentile] 分位，不取峰值：按住手机时的一声撞击、
 * 指尖碰到机壳、桌面振动都能把峰值顶到满量程，整段语音跟着被压到负五十分贝以下，
 * 模型只看得见噪声底，判出来是零毫秒。实测同一个模型，峰值归一在撞击之后整段人声概率
 * 掉到零点一，分位归一之后仍是一，白噪声照旧过不了阈值。
 *
 * 判定看最长的一段连续人声，不是累计概率之和：键盘敲击、放下杯子、桌面振动都是零散尖峰，
 * 累计能凑够时长，却没有一段是连续的。一句话里词与词之间的换气短过 [GapMs]，算同一段。
 */
object SpeechGate {

    /** 上行音频的采样率，与 models 的输入一致。 */
    const val SampleRate = 16_000

    /** 模型按 32 毫秒给一个概率，也就是 512 个采样。 */
    const val WindowSamples = 512

    const val WindowMs = WindowSamples * 1_000L / SampleRate

    /** 概率过这条线算这一窗里有人声。 */
    const val Threshold = 0.5f

    /**
     * 连续人声够这么久才算说了一句，短促的咳嗽与清嗓子不算。
     * 挡住噪声的是模型的置信度阈值，不是这条时长：模型说不是人声，多长都不算。
     */
    const val MinSpeechMs = 300L

    /** 一句话里的换气不会超过这么久，短过它的空档仍算同一段人声。 */
    const val GapMs = 100L

    /** 各窗 RMS 的这个分位代表这一段的语音电平，送到 [NormalizeLevelDb]。 */
    const val ReferencePercentile = 0.9

    /** 参照电平拉到这里，把音量从判据里抹掉。 */
    const val NormalizeLevelDb = -20.0

    private const val FullScale = 32768.0

    /** 低于这个 RMS 的整段按全零处理，放大只会把量化噪声抬成满量程。 */
    private const val SilentLevel = 1.0

    /**
     * 抹掉直流，按分位电平归一并折成模型要的浮点幅度。
     * 整段都是零、或者短到凑不满一窗时返回全零，模型对全零会给出接近零的概率，走同一条路。
     */
    fun normalize(pcm: ShortArray): FloatArray {
        val out = FloatArray(pcm.size)
        val windows = pcm.size / WindowSamples
        if (windows == 0) return out
        var sum = 0.0
        for (sample in pcm) sum += sample
        val mean = sum / pcm.size
        val levels = DoubleArray(windows) { window ->
            var squares = 0.0
            val start = window * WindowSamples
            for (index in start until start + WindowSamples) {
                val value = pcm[index] - mean
                squares += value * value
            }
            sqrt(squares / WindowSamples)
        }
        levels.sort()
        val reference = levels[((windows - 1) * ReferencePercentile).toInt()]
        if (reference < SilentLevel) return out
        val gain = FullScale * 10.0.pow(NormalizeLevelDb / 20.0) / reference
        for (index in pcm.indices) {
            val value = (pcm[index] - mean) * gain
            out[index] = (value.coerceIn(-FullScale, FullScale - 1) / FullScale).toFloat()
        }
        return out
    }

    /** 最长的一段连续人声，跨度里允许 [GapMs] 以内的空档。 */
    fun longestSpeechMs(probabilities: FloatArray): Long {
        var best = 0L
        var run = 0L
        var gap = 0L
        for (probability in probabilities) {
            if (probability >= Threshold) {
                run += WindowMs + gap
                gap = 0
                if (run > best) best = run
            } else if (run > 0) {
                gap += WindowMs
                if (gap > GapMs) {
                    run = 0
                    gap = 0
                }
            }
        }
        return best
    }

    /**
     * 兜底判据：不看模型，只问这一段里有没有连续 [MinSpeechMs] 的时间明显高出自己的底噪。
     *
     * 模型看的是频谱形状，气声贴着麦克风时高频被近场效应压掉一截，形状已经不典型；
     * 电平这条线不关心形状，只要有一段持续抬起来就算说了话。实测同一条粉噪按这个方法判，
     * 最长的一段在几十到一百毫秒之间，而说一句耳语至少有四百毫秒，门槛放在 [MinSpeechMs]
     * 上两边分得开。两条判据取其一：宁可多送一段噪声给服务端，也不要默默吞掉一句真话——
     * 服务端在 activityEnd 之前不会开口，误收的噪声最多让这一轮白跑。
     *
     * 电平必须取增益之前的：放大后的电平里带着增益自己爬坡的那一段，安静的办公室也会被判成
     * 一直在说话（实测一条七秒的安静录音，放大后全程都在门槛之上）。
     */
    fun longestLoudMs(levels: FloatArray, frameMs: Long): Long {
        if (levels.size < LoudFrames) return 0
        val sorted = levels.clone()
        sorted.sort()
        val floor = sorted[(levels.size * FloorPercentile).toInt().coerceAtMost(levels.size - 1)]
        val threshold = maxOf(floor.toDouble() * LoudOverFloor, MinLoudLevel.toDouble())
        // 采集开头那一秒半是麦克风上电的噪声，比底噪高三十分贝，先从序列里切掉再判。
        val from = if (levels.size * frameMs > HeadMs + KeepMs) (HeadMs / frameMs).toInt() else 0
        var best = 0L
        var run = 0L
        for (index in from until levels.size) {
            if (levels[index].toDouble() >= threshold) {
                run += frameMs
                if (run > best) best = run
            } else {
                run = 0
            }
        }
        return best
    }

    /** 底噪取各帧电平的这个分位；说话占到七成也还落在底噪上。 */
    private const val FloorPercentile = 0.2f

    /** 高出底噪多少算有动静，八个分贝；稳态噪声的起伏在这个量级以下。 */
    private const val LoudOverFloorDb = 8.0
    private val LoudOverFloor = 10.0.pow(LoudOverFloorDb / 20.0)

    /** 低于这个 RMS 的电平一律不算，量化噪声放大后不该被当成声音。 */
    private const val MinLoudLevel = 3.0

    /** 少于一秒的电平序列谈不上分位，直接不判。 */
    private const val LoudFrames = 50

    /** 采集开头这么久的电平是麦克风上电噪声。 */
    const val HeadMs = 1_500L

    /** 切掉开头之后至少还要留下这么久，短句整段照判。 */
    private const val KeepMs = 1_500L

    /** 只看统计量时的入口：最长连续人声的毫秒数已经算好。 */
    fun shouldDrop(speechMs: Long): Boolean = speechMs < MinSpeechMs

}
