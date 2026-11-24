package com.parley.speaker.ui.chat

import com.parley.speaker.domain.TurnEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 转写块合帧：同属一轮的连续块并成一条，换轮或别的回合事件到之前先交出上一段，一条都不丢。
 */
class TranscriptBufferTest {

    @Test
    fun `同属一轮的连续块并成一条`() {
        val buffer = TranscriptBuffer()
        assertNull(buffer.hold("Good mor", "t1"))
        assertNull(buffer.hold("ning, ", "t1"))
        assertNull(buffer.hold("how are you?", "t1"))
        assertEquals(TurnEvent.AssistantText("Good morning, how are you?", "t1"), buffer.drain())
    }

    @Test
    fun `取走之后缓冲是空的`() {
        val buffer = TranscriptBuffer()
        buffer.hold("one", null)
        assertEquals(TurnEvent.AssistantText("one", null), buffer.drain())
        assertNull(buffer.drain())
    }

    @Test
    fun `换了一轮先交出上一段再收这一块`() {
        val buffer = TranscriptBuffer()
        buffer.hold("first", "t1")
        assertEquals(TurnEvent.AssistantText("first", "t1"), buffer.hold("second", "t2"))
        assertEquals(TurnEvent.AssistantText("second", "t2"), buffer.drain())
    }

    @Test
    fun `什么都还没收到时取不出东西`() {
        assertNull(TranscriptBuffer().drain())
    }
}
