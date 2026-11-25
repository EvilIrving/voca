package com.voca.speaker.audio

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * 上行采集是四十八千赫兹，实时会话要十六千赫兹单声道。直接每三取一会把八千赫兹以上的内容折叠回语音带内，
 * 所以先做三十三抽头窗函数 sinc 低通，截止取七点二千赫兹，再每三点取一：直流增益归一为一，
 * 一千赫兹处偏差在千分之一量级，八千赫兹约降十一分贝，十千赫兹以下三十五分贝，折叠回来的高频不再可闻。
 * 降采样是整数倍、滤波核对称，卷积相位恒为零，不需要分数延迟插值。
 * 窗宽一半是十六个样本，跨调用留着这段历史，切块边界也能拿到完整卷积；reset 清掉历史，下次当作新流。
 */
class Resampler {

    private var buffer = FloatArray(InitialCapacity)
    private var count = 0
    private var bufferStart = -HalfWidth
    private var nextOutput = 0L

    init {
        reset()
    }

    fun reset() {
        buffer = FloatArray(InitialCapacity)
        count = HalfWidth
        bufferStart = -HalfWidth
        nextOutput = 0L
    }

    fun process(input: ShortArray): ShortArray {
        if (input.isEmpty()) return ShortArray(0)
        ensure(count + input.size)
        for (index in input.indices) {
            buffer[count + index] = input[index].toFloat()
        }
        count += input.size

        val outputs = ShortArray(producible())
        for (slot in outputs.indices) {
            val center = (nextOutput - bufferStart).toInt()
            var sum = 0f
            for (tap in 0 until TapCount) {
                sum += Kernel[tap] * buffer[center - HalfWidth + tap]
            }
            outputs[slot] = sum.roundToInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
            nextOutput += Step
        }

        val keepFrom = (nextOutput - bufferStart).toInt() - HalfWidth
        if (keepFrom > 0) {
            buffer.copyInto(buffer, 0, keepFrom, count)
            count -= keepFrom
            bufferStart += keepFrom
        }
        return outputs
    }

    /** 右半窗还没收齐的样本留到下次，凑满一个窗口才出一个点。 */
    private fun producible(): Int {
        val center = (nextOutput - bufferStart).toInt()
        val spare = count - HalfWidth - center
        if (spare < 0) return 0
        return spare / Step + 1
    }

    private fun ensure(capacity: Int) {
        if (capacity <= buffer.size) return
        var grown = buffer.size
        while (grown < capacity) grown *= 2
        val next = FloatArray(grown)
        buffer.copyInto(next, 0, 0, count)
        buffer = next
    }

    private companion object {
        const val TapCount = 33
        const val HalfWidth = TapCount / 2
        const val Step = 3
        const val CutoffHz = 7_200.0
        const val InputRate = 48_000.0
        const val InitialCapacity = 4_096

        /** Blackman 窗压旁瓣，系数按直流和为一只做一次归一，之后不再做动态补偿。 */
        val Kernel: FloatArray = FloatArray(TapCount) { index ->
            val offset = index - HalfWidth
            val ideal = 2.0 * CutoffHz / InputRate * sinc(2.0 * CutoffHz / InputRate * offset)
            val phase = 2.0 * PI * index / (TapCount - 1)
            val window = 0.42 - 0.5 * cos(phase) + 0.08 * cos(2.0 * phase)
            (ideal * window).toFloat()
        }.let { raw ->
            val total = raw.sumOf { it.toDouble() }
            FloatArray(raw.size) { index -> (raw[index] / total).toFloat() }
        }

        fun sinc(value: Double): Double = if (value == 0.0) 1.0 else sin(PI * value) / (PI * value)
    }
}
