package com.voca.speaker.audio

import java.util.Collections
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 播放会话的并发边界：退出标记只属于发它的那一段，新建的会话照常拿到自己的数据。
 * 这是「停止之后紧接着投递，新音频被旧退出标记毒死」那条路径的回归测试。
 */
class PlaybackSessionTest {

    @Test
    fun `数据按投递顺序写出`() {
        val written = Collections.synchronizedList(mutableListOf<Byte>())
        val session = PlaybackSession(write = { pcm -> written += pcm[0] }, onFinished = {})
        session.start()
        session.offer(byteArrayOf(1))
        session.offer(byteArrayOf(2))
        session.offer(byteArrayOf(3))

        awaitUntil("三块都写完") { written.size == 3 }
        assertEquals(listOf<Byte>(1, 2, 3), written.toList())
        session.finish()
    }

    @Test
    fun `停止一段会话不会带走下一段的数据`() {
        val first = Collections.synchronizedList(mutableListOf<Byte>())
        val second = Collections.synchronizedList(mutableListOf<Byte>())
        val finished = Collections.synchronizedList(mutableListOf<String>())

        val a = PlaybackSession(write = { pcm -> first += pcm[0] }, onFinished = { finished += "a" })
        a.start()
        a.offer(byteArrayOf(1))
        awaitUntil("第一段先写一块") { first.size == 1 }

        a.finish()
        val b = PlaybackSession(write = { pcm -> second += pcm[0] }, onFinished = { finished += "b" })
        b.start()
        b.offer(byteArrayOf(2))
        b.offer(byteArrayOf(3))

        awaitUntil("第二段两块都写完") { second.size == 2 }
        assertEquals("新会话的数据要全部写出去", listOf<Byte>(2, 3), second.toList())
        assertEquals("旧会话不再写", listOf<Byte>(1), first.toList())
        awaitUntil("旧会话的写入线程收尾") { "a" in finished }
    }

    @Test
    fun `停止之后投进来的数据不会再有写入线程处理`() {
        val written = Collections.synchronizedList(mutableListOf<Byte>())
        val finishedCount = AtomicInteger()
        val session = PlaybackSession(
            write = { pcm -> written += pcm[0] },
            onFinished = { finishedCount.incrementAndGet() },
        )
        session.start()
        session.finish()
        awaitUntil("写入线程收尾") { finishedCount.get() == 1 }

        session.offer(byteArrayOf(9))
        Thread.sleep(80)
        assertTrue("停止之后的投递一律作废", written.isEmpty())
        assertEquals("收尾只走一次", 1, finishedCount.get())
    }

    @Test
    fun `写入抛异常也走同一套收尾`() {
        val finishedCount = AtomicInteger()
        val session = PlaybackSession(
            write = { throw IllegalStateException("track 已经被释放") },
            onFinished = { finishedCount.incrementAndGet() },
        )
        session.start()
        session.offer(byteArrayOf(1))

        awaitUntil("写入失败后收尾") { finishedCount.get() == 1 }
    }

    private fun awaitUntil(what: String, timeoutMs: Long = 2_000, check: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (check()) return
            Thread.sleep(5)
        }
        throw AssertionError("等不到：$what")
    }
}
