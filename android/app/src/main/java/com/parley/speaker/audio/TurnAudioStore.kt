package com.parley.speaker.audio

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.IOException

/**
 * 每轮的助手音频落成一份裸 PCM，二十四千赫兹单声道十六位。
 * 写入走单线程调度，同一轮的音频块按到达顺序追加，清理与删除也排在同一条队列上。
 */
class TurnAudioStore(context: Context) {

    private val root = File(context.applicationContext.filesDir, Directory).apply { mkdirs() }
    private val writes = Dispatchers.IO.limitedParallelism(parallelism = 1)

    suspend fun append(turnId: String, pcm: ByteArray) {
        if (pcm.isEmpty()) return
        withContext(writes) {
            try {
                FileOutputStream(fileOf(turnId), true).use { it.write(pcm) }
            } catch (e: IOException) {
                Log.w(TAG, "append failed: ${e.message}")
            }
        }
    }

    fun path(turnId: String): String = fileOf(turnId).absolutePath

    suspend fun read(turnId: String): ByteArray? = withContext(writes) {
        val file = fileOf(turnId)
        if (!file.isFile || file.length() == 0L) return@withContext null
        runCatching { file.readBytes() }.getOrNull()
    }

    /** 按最后写入时间清掉超过天数的录音，删不掉的留着下次再试。 */
    suspend fun prune(days: Int = KeepDays) {
        withContext(writes) {
            val cutoff = System.currentTimeMillis() - days * DayMs
            root.listFiles()?.forEach { file ->
                if (file.lastModified() < cutoff) file.delete()
            }
        }
    }

    suspend fun delete(turnId: String) {
        withContext(writes) { fileOf(turnId).delete() }
    }

    private fun fileOf(turnId: String): File = File(root, "${safeName(turnId)}.pcm")

    // 轮次标识里带冒号这类字符，各文件系统的处理不一致，落盘前收敛成一种写法。
    private fun safeName(turnId: String): String = turnId.replace(UnsafeChars, "_")

    private companion object {
        const val TAG = "TurnAudioStore"
        const val Directory = "turns"
        const val KeepDays = 7
        const val DayMs = 24 * 60 * 60 * 1000L
        val UnsafeChars = Regex("[^A-Za-z0-9_-]")
    }
}
