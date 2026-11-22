package com.parley.speaker.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 音频的闸：一轮被删掉时它还在流式到达，排着队的分片会在删除之后才轮到。
 * 闸不拦下来，刚抹掉的录音文件就会被重写出一份残段，声音也接着放。
 */
class AudioGateTest {

    private fun chunk(turnId: String) = AudioChunk(turnId, ByteArray(4))

    /** 消费侧的形状：队列里的分片按到达顺序过闸，删掉那一轮的丢掉。 */
    private fun written(gate: AudioGate, chunks: List<AudioChunk>): List<String> =
        chunks.filter { gate.accepts(it.turnId) }.map { it.turnId }

    @Test
    fun `删除之后才轮到的那几块分片不再写盘`() {
        val gate = AudioGate()
        val queued = listOf(chunk("e1"), chunk("e1"), chunk("e1"))

        assertEquals("删除之前到的照常写", listOf("e1"), written(gate, queued.take(1)))

        gate.delete("e1")

        assertTrue("剩下的分片全部丢掉", written(gate, queued.drop(1)).isEmpty())
        assertFalse(gate.accepts("e1"))
    }

    @Test
    fun `只拦删掉那一条，别的轮次照旧写`() {
        val gate = AudioGate()
        gate.delete("e1")

        assertEquals(listOf("e2"), written(gate, listOf(chunk("e1"), chunk("e2"))))
        assertTrue(gate.accepts("u1"))
    }

    @Test
    fun `开新的一段时闸整份清空`() {
        val gate = AudioGate()
        gate.delete("e1")

        gate.reset()

        assertTrue("新的一段轮次标识带纳秒，不会与上一段撞上", gate.accepts("e1"))
    }
}
