package com.parley.speaker.audio

import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sqrt
import kotlin.math.tanh

/**
 * 输入增益：压着嗓子、贴着嘴说出来的句子，在交给实时模型之前先拉回工作电平。
 *
 * 采集侧没有自动增益可用：AOSP 里只有 VOICE_COMMUNICATION 承诺带 AGC 与回声消除，本机的音效表
 * 也只给那一档挂了预处理，而那一档实测被回声消除抹成零。我们用的是 VOICE_RECOGNITION，
 * 出来的是麦克风原样的电平，小声说话进到模型里就是负四十到负五十分贝的一层薄声。
 *
 * 参照取最近三秒内逐帧 RMS 的中位数，不取瞬时电平，也不取峰值：一次碰到机壳的撞击、
 * 一声清嗓子顶到满量程，按峰值做参照就把整段语音压到模型看不见的地方（人声闸门那边踩过同一个坑）。
 * 起手就说话时窗口里只有语音，参照落在语音上；先想一会儿再开口时参照落在环境声上，
 * 两句话之间也不会把增益拉下来。
 *
 * 这里刻意不做"这一帧是不是人声"的分类：轻声的帧级电平和环境声本来就只差几个分贝，
 * 分类一旦判错就再也不抬增益，正是这次要修的那个毛病；而推流本身就是按住键在说，
 * 用户按下这一下就已经是"有人在说"的先验。整轮只有环境声时增益也照抬，闸门那边照样判静音，
 * 服务端在 activityEnd 之前不会开口，代价只是多推一段没人听的噪声。
 *
 * 三条边界：只放大不衰减，响亮的句子原样过；放大量上限三十分贝，免得麦克风自噪声被抬成人声；
 * 超过半量程的峰走软限幅，不硬切，撞上来也不会变成方波。
 */
class InputGain {

    /** 当前增益倍数，收尾时 DebugProbe 会读它。 */
    var gain: Double = 1.0
        private set

    /** 整段里见过的输入峰值，与输出峰值一起说明增益有没有把信号放大到出界。 */
    var peakIn: Int = 0
        private set

    var peakOut: Int = 0
        private set

    private val window = DoubleArray(WindowFrames)
    private val scratch = DoubleArray(WindowFrames)
    private var filled = 0
    private var cursor = 0

    /** 原地乘增益并按软限幅折回十六位。 */
    fun apply(frame: ShortArray) {
        if (frame.isEmpty()) return
        var sum = 0.0
        var peak = 0
        for (sample in frame) {
            val value = sample.toInt()
            sum += value.toDouble() * value
            val magnitude = abs(value)
            if (magnitude > peak) peak = magnitude
        }
        if (peak > peakIn) peakIn = peak

        window[cursor] = sqrt(sum / frame.size)
        cursor = (cursor + 1) % WindowFrames
        if (filled < WindowFrames) filled++

        step(target())
        val scale = gain
        for (index in frame.indices) {
            val value = limit(frame[index] * scale)
            frame[index] = value.toShort()
            val magnitude = abs(value)
            if (magnitude > peakOut) peakOut = magnitude
        }
    }

    /**
     * 参照电平拉向 [TargetRms]。样本太少时窗口里的数不代表性，先原样放过去，
     * 免得一个碰到机壳的爆音把开头一百毫秒的增益钉在地板上。
     */
    private fun target(): Double {
        if (filled < MinFrames) return gain
        val level = percentile()
        if (level < FloorRms) return 1.0
        return (TargetRms / level).coerceIn(1.0, MaxGain)
    }

    /** 窗口里各帧 RMS 的 [ReferencePercentile] 分位，排序在预分配的副本上做，逐帧不留垃圾。 */
    private fun percentile(): Double {
        val size = filled
        System.arraycopy(window, 0, scratch, 0, size)
        java.util.Arrays.sort(scratch, 0, size)
        return scratch[((size - 1) * ReferencePercentile).toInt()]
    }

    /** 按分贝步进挪包络，两端都限速，避免一帧之内把增益甩到顶。 */
    private fun step(target: Double) {
        val current = 20 * log10(max(gain, 1.0))
        val wanted = 20 * log10(max(target, 1.0))
        val next = current + max(-StepDb, min(StepDb, wanted - current))
        gain = 10.0.pow(next / 20).coerceIn(1.0, MaxGain)
    }

    /**
     * 软限幅：半量程以内原样通过，以上按双曲正切压向 [Ceiling]。
     * 硬切会让一段撞击变成满量程方波，谐波铺满整个频带，模型听到的是噪声而不是声音。
     */
    private fun limit(value: Double): Int {
        val magnitude = abs(value)
        val shaped = if (magnitude <= Knee) {
            magnitude
        } else {
            Knee + (Ceiling - Knee) * tanh((magnitude - Knee) / (Ceiling - Knee))
        }
        val signed = if (value < 0) -shaped else shaped
        return signed.roundToInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
    }

    /** 一段话收尾时的一行说明，进系统日志，不进界面。 */
    fun describe(): String =
        "gain=${String.format("%.1f", 20 * log10(max(gain, 1.0)))}dB " +
            "peakIn=$peakIn peakOut=$peakOut"

    companion object {
        /** 目标电平取负二十分贝满量程，与 [com.parley.speaker.domain.SpeechGate.NormalizeLevelDb] 同一档。 */
        const val TargetRmsDb = -20.0
        val TargetRms: Double = 32_768.0 * 10.0.pow(TargetRmsDb / 20)

        const val MaxGainDb = 30.0
        val MaxGain: Double = 10.0.pow(MaxGainDb / 20)

        /** 二十毫秒一帧，三秒即一百五十帧：短到一次撞击占不满中位，长到盖得住一整句话。 */
        const val WindowFrames = 150
        const val ReferencePercentile = 0.5

        /** 不足五帧（一百毫秒）时参照还没意义，先不动增益。 */
        const val MinFrames = 5

        /** 分位参照低于这个值就当整段没声音，放大只会把量化噪声抬起来。 */
        const val FloorRms = 6.0

        const val StepDb = 2.0

        /** 半量程以下按线性走，保证正常音量的句子逐样不变。 */
        const val Knee = 32_767.0 * 0.5

        /** 软限幅的渐近上限，留一成余量给层间的抖动。 */
        const val Ceiling = 32_767.0 * 0.88
    }
}
