package com.parley.speaker.debug

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import com.parley.speaker.audio.SileroVad
import com.parley.speaker.domain.SpeechGate
import java.io.File

/**
 * 自动化验证用：把采集状态写进应用内部目录，绕开被 ROM 屏蔽的 logcat。
 * 内部目录不需要任何存储权限，重装后也不会因为外部目录失效而写不进去。
 *
 * 同时兼作人声判定的真机探针：带 `vad` 额外参数时，用真模型跑一遍包里带的语音样本，
 * 把"判成说话还是静音、最长一段人声、推理耗时"写进同一个目录。
 */
class CaptureProbeReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val dir = context.filesDir
        File(dir, "capture_state.txt")
            .writeText(if (com.parley.speaker.audio.VoiceRecorder.isCapturing()) "capturing" else "idle")
        if (intent.getStringExtra("probe") != "vad") return
        File(dir, "vad_state.txt").writeText(runVadProbe(context))
    }

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
