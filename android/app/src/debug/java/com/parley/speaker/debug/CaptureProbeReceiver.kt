package com.parley.speaker.debug

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.parley.speaker.audio.VoiceRecorder
import java.io.File

/**
 * 自动化验证用：把采集状态写进应用内部目录，绕开被 ROM 屏蔽的 logcat。
 * 内部目录不需要任何存储权限，重装后也不会因为外部目录失效而写不进去。
 */
class CaptureProbeReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        File(context.filesDir, "capture_state.txt")
            .writeText(if (VoiceRecorder.isCapturing()) "capturing" else "idle")
    }
}
