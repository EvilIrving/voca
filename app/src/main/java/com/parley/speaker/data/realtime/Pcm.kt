package com.parley.speaker.data.realtime

import java.util.Base64
import kotlin.math.roundToInt

/**
 * 适配层里反复要做的四件事：短整型与字节互转、重采样、Base64。
 * 全部是 JVM 上的纯逻辑，不碰 Android 类，单测直接跑，不需要真机也不需要密钥。
 *
 * 上行采集一律十六千赫兹二十毫秒一帧（三百二十个采样），下行一律二十四千赫兹。
 * 重采样只用线性插值，逐帧独立、不带跨帧相位：拼接处那一点相移在二十毫秒的边界上听不出来，
 * 换来的是任意一帧都能单独算完，不必等上一帧，也不会在连接中断时留下半截状态。
 */
object Pcm {

    /** 采集与上行帧的采样率。 */
    const val UPLINK_RATE = 16_000

    /** 播放与下行帧的采样率。 */
    const val DOWNLINK_RATE = 24_000

    /** 小端序在有符号短整型与字节之间互转，协议里三家都是小端。 */
    fun shortsToBytes(samples: ShortArray): ByteArray {
        val bytes = ByteArray(samples.size * 2)
        for (i in samples.indices) {
            val value = samples[i].toInt()
            bytes[i * 2] = (value and 0xFF).toByte()
            bytes[i * 2 + 1] = ((value shr 8) and 0xFF).toByte()
        }
        return bytes
    }

    fun bytesToShorts(bytes: ByteArray): ShortArray {
        val samples = ShortArray(bytes.size / 2)
        for (i in samples.indices) {
            val low = bytes[i * 2].toInt() and 0xFF
            val high = (bytes[i * 2 + 1].toInt() and 0xFF) shl 8
            samples[i] = (low or high).toShort()
        }
        return samples
    }

    /** 输出长度按输入长度乘目标比取整，320 个采样从 16k 到 24k 正好是 480 个。 */
    fun resample(samples: ShortArray, fromRate: Int, toRate: Int): ShortArray {
        require(fromRate > 0 && toRate > 0) { "采样率必须为正" }
        if (samples.isEmpty() || fromRate == toRate) return samples
        val size = (samples.size.toLong() * toRate / fromRate).toInt()
        val out = ShortArray(size)
        for (i in 0 until size) {
            val position = i.toDouble() * fromRate / toRate
            val index = position.toInt()
            val fraction = position - index
            val left = samples[index].toDouble()
            val right = if (index + 1 < samples.size) samples[index + 1].toDouble() else left
            out[i] = (left + (right - left) * fraction).roundToInt().coerceIn(MIN_SAMPLE, MAX_SAMPLE).toShort()
        }
        return out
    }

    fun resampleToDownlink(samples: ShortArray): ShortArray = resample(samples, UPLINK_RATE, DOWNLINK_RATE)

    /** 上行帧转字节再重采样到二十四千赫兹，OpenAI 那边的 append 与豆包那边的整段回灌都用这一条。 */
    fun uplinkFrameToDownlink(frame: ShortArray): ByteArray = shortsToBytes(resampleToDownlink(frame))

    fun encodeBase64(bytes: ByteArray): String = Base64.getEncoder().encodeToString(bytes)

    /** 解码用 MIME 解码器，服务端把行宽折过也能吃下。 */
    fun decodeBase64(text: String): ByteArray = Base64.getMimeDecoder().decode(text)

    private const val MIN_SAMPLE = -32_768
    private const val MAX_SAMPLE = 32_767
}
