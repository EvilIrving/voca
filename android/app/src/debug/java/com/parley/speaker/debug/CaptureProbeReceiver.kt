package com.parley.speaker.debug

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import com.parley.speaker.audio.SileroVad
import com.parley.speaker.audio.VoiceRecorder
import com.parley.speaker.domain.SpeechGate
import java.io.File
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.max
import kotlin.math.sqrt
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking

/**
 * 自动化验证用：把采集状态写进应用内部目录，绕开被 ROM 屏蔽的 logcat。
 * 内部目录不需要任何存储权限，重装后也不会因为外部目录失效而写不进去。
 *
 * 同时兼作人声判定的真机探针：带 `vad` 额外参数时，用真模型跑一遍包里带的语音样本，
 * 把"判成说话还是静音、最长一段人声、推理耗时"写进同一个目录。
 *
 * 带 `live` 参数时走真正的采集链：开一段 [com.parley.speaker.audio.VoiceRecorder]，
 * 拿到的是过了增益、编了码、要上行给实时会话的那一份十六千赫兹音频，
 * 对它跑两条闸门判据并把电平分位写下来。调参数时靠它，不靠界面看波形猜。
 */
class CaptureProbeReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val dir = context.filesDir
        File(dir, "capture_state.txt")
            .writeText(if (com.parley.speaker.audio.VoiceRecorder.isCapturing()) "capturing" else "idle")
        val probe = intent.getStringExtra("probe")
        if (probe == "live") {
            val seconds = intent.getIntExtra("seconds", 5)
            Thread { File(dir, "live_probe.txt").writeText(runLiveProbe(context, seconds)) }.start()
            return
        }
        if (probe != "vad") return
        File(dir, "vad_state.txt").writeText(runVadProbe(context))
    }

    /** 真采集一遍，写出上行电平、两条闸门判据与 OGG 路径。 */
    private fun runLiveProbe(context: Context, seconds: Int): String = runCatching {
        val scope = CoroutineScope(Dispatchers.IO)
        val frames = ArrayList<ShortArray>()
        val recorder = VoiceRecorder(scope)
        val started = recorder.start(
            context.cacheDir,
            onFrame = { frame -> synchronized(frames) { frames += frame } },
            onLevel = {},
        )
        if (!started) return@runCatching "start=failed reason=record-unavailable"
        Thread.sleep(seconds * 1000L)
        // 电平序列要在收尾之前取：finish 之后会话就清掉了，peek 出来是空的。
        val levels = recorder.peekLevels()
        val clip = runBlocking { recorder.finish() }
        val pcm = synchronized(frames) {
            val total = frames.sumOf { it.size }
            val flat = ShortArray(total)
            var at = 0
            for (frame in frames) {
                frame.copyInto(flat, at)
                at += frame.size
            }
            flat
        }
        if (pcm.isEmpty()) return@runCatching "start=$started frames=0"
        var squares = 0.0
        var peak = 0
        for (sample in pcm) {
            val value = sample.toDouble()
            squares += value * value
            if (abs(sample.toInt()) > peak) peak = abs(sample.toInt())
        }
        val rms = sqrt(squares / pcm.size)
        val vad = SileroVad { context.assets.open(SileroVad.AssetName).readBytes() }
        vad.warmUp()
        val speechMs = SpeechGate.longestSpeechMs(vad.probabilities(pcm))
        val loudMs = SpeechGate.longestLoudMs(levels, VoiceRecorder.FRAME_MS.toLong())
        val margins = listOf(3.0, 4.0, 6.0, 8.0, 10.0).joinToString(" ") { margin ->
            "m${margin.toInt()}=${runAbove(levels, margin)}"
        }
        vad.close()
        scope.cancel()
        "start=$started audioMs=${pcm.size * 1000L / SpeechGate.SampleRate} " +
            "rms=${decibel(rms)} peak=${decibel(peak.toDouble())} " +
            "speechMs=$speechMs loudMs=$loudMs " +
            "drop=${SpeechGate.shouldDrop(speechMs) && SpeechGate.shouldDrop(loudMs)} " +
            "levels=${levelStats(levels)} $margins " +
            "clip=${clip?.path ?: "none"}"
    }.getOrElse { "error=${it.javaClass.simpleName}:${it.message}" }

    /** 增益之前那一串电平的分位，单位 dBFS：判据余量够不够，看这几个数就知道。 */
    private fun levelStats(levels: FloatArray): String {
        if (levels.isEmpty()) return "none"
        val sorted = levels.sortedArray()
        fun at(percentile: Double): String =
            decibel(sorted[((sorted.size - 1) * percentile).toInt()].toDouble())
        return "p10=${at(0.1)} p20=${at(0.2)} p50=${at(0.5)} p90=${at(0.9)} max=${decibel(sorted.last().toDouble())}"
    }

    /** 按不同余量各算一遍最长连续段，用来挑那个余量。 */
    private fun runAbove(levels: FloatArray, marginDb: Double): Long {
        if (levels.size < 50) return 0
        val sorted = levels.sortedArray()
        val floor = sorted[(levels.size * 0.2).toInt()].toDouble() * 10.0.pow(marginDb / 20.0)
        val from = if (levels.size > 150) 75 else 0
        var best = 0L
        var run = 0L
        for (index in from until levels.size) {
            if (levels[index].toDouble() >= floor) {
                run += VoiceRecorder.FRAME_MS.toLong()
                if (run > best) best = run
            } else {
                run = 0
            }
        }
        return best
    }

    private fun decibel(amplitude: Double): String =
        String.format("%.1f", 20 * log10(max(amplitude, 1.0) / 32_768.0))

    private fun runVadProbe(context: Context): String {
        return runCatching {
            val pcm = context.assets.open("vad_probe.pcm").use { bytes ->
                val raw = bytes.readBytes()
                ShortArray(raw.size / 2) { index ->
                    val low = raw[index * 2].toInt() and 0xFF
                    val high = raw[index * 2 + 1].toInt()
                    ((high shl 8) or low).toShort()
                }
            }
            val vad = SileroVad { context.assets.open(SileroVad.AssetName).readBytes() }
            val started = SystemClock.elapsedRealtime()
            vad.warmUp()
            val warmed = SystemClock.elapsedRealtime()
            val probabilities = vad.probabilities(pcm)
            val finished = SystemClock.elapsedRealtime()
            val speechMs = SpeechGate.longestSpeechMs(probabilities)
            vad.close()
            "windows=${probabilities.size} speechMs=$speechMs drop=${SpeechGate.shouldDrop(speechMs)} " +
                "warmUpMs=${warmed - started} inferMs=${finished - warmed} audioMs=${pcm.size * 1000L / SpeechGate.SampleRate}"
        }.getOrElse { "error=${it.javaClass.simpleName}:${it.message}" }
    }
}
