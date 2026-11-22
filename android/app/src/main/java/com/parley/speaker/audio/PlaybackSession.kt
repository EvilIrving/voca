package com.parley.speaker.audio

import java.util.concurrent.LinkedBlockingQueue
import kotlin.concurrent.thread

/**
 * 一段播放会话：队列、写入线程与退出标记都属于它自己。
 *
 * 停止只作用于这一段，退出标记投进的也是这一段自己的队列，所以紧随其后新建的会话
 * 不可能读到上一代留下的标记，也不会被上一代的写入线程抢先取走数据。
 */
internal class PlaybackSession(
    private val write: (ByteArray) -> Unit,
    private val onFinished: () -> Unit,
) {

    private val queue = LinkedBlockingQueue<ByteArray>()
    private var worker: Thread? = null

    fun start() {
        if (worker != null) return
        worker = thread(isDaemon = true, name = "parley-audio-out") { loop() }
    }

    fun offer(pcm: ByteArray) {
        if (pcm.isEmpty()) return
        queue.offer(pcm)
    }

    fun clear() {
        queue.clear()
    }

    /** 发停止信号，句柄由写入线程自己收尾，调用方不做 join。 */
    fun finish() {
        queue.clear()
        queue.offer(EXIT)
    }

    private fun loop() {
        try {
            while (true) {
                val chunk = try {
                    queue.take()
                } catch (e: InterruptedException) {
                    break
                }
                if (chunk.isEmpty()) break
                try {
                    write(chunk)
                } catch (e: Exception) {
                    break
                }
            }
        } finally {
            onFinished()
        }
    }

    private companion object {
        val EXIT = ByteArray(0)
    }
}
