package com.parley.speaker.ui.chat

import com.parley.speaker.data.SeedContent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 会话边界：挂断没有了之后，切到别的会话、点新会话、静默三十分钟与进程退出
 * 都走同一次收段，每一段只交出去一次，落库因此写不成两遍。
 */
class SessionCycleTest {

    @Test
    fun `四种时机各自收一段，每段只落一次`() {
        var now = 1_000_000L
        val ledger = SessionLedger { now }
        val written = mutableListOf<String>()

        listOf("切到别的会话", "点新会话", "静默三十分钟", "进程退出").forEach { timing ->
            now += 60_000L
            val segment = ledger.closeIfOpen()
            assertNotNull("$timing 该收掉当前这一段", segment)
            written += segment!!.id
            assertNull("$timing 收过的这一段不会再写一遍", ledger.closeIfOpen())
            ledger.begin()
        }

        assertEquals("四段各落一条", 4, written.size)
        assertEquals("四段各是各的标识，历史里认得出谁是谁", 4, written.toSet().size)
    }

    @Test
    fun `收过的这一段不再算静默到点`() {
        var now = 0L
        val ledger = SessionLedger { now }
        now = 31 * 60 * 1000L

        assertTrue(ledger.idleDue(recording = false))
        ledger.closeIfOpen()

        now += 60 * 60 * 1000L
        assertFalse("已经收掉的一段不必再收一次", ledger.idleDue(recording = false))
    }

    @Test
    fun `静默二十九分五十秒不收，三十分钟整与三十一分钟都收`() {
        val last = 5_000_000L

        assertFalse(IdleCut.reached(last, last + 29 * 60_000L + 50_000L, recording = false))
        assertTrue(IdleCut.reached(last, last + 30 * 60_000L, recording = false))
        assertTrue(IdleCut.reached(last, last + 31 * 60_000L, recording = false))
    }

    @Test
    fun `正在收音时不判静默，松手之后才重新数`() {
        val last = 5_000_000L
        val end = last + 31 * 60_000L

        assertFalse("收音中不算静默", IdleCut.reached(last, end, recording = true))
        assertTrue(IdleCut.reached(last, end, recording = false))
    }

    @Test
    fun `摸一下就重新数三十分钟`() {
        var now = 0L
        val ledger = SessionLedger { now }
        now = 31 * 60_000L
        assertTrue(ledger.idleDue(recording = false))

        ledger.touch()
        now += 29 * 60_000L

        assertFalse(ledger.idleDue(recording = false))
    }

    @Test
    fun `开新的一段照当下设置刷新提示词`() {
        val plan = SegmentPlan(prompt = "旧提示词")

        val next = plan.resuming("设置页里改过的提示词")

        assertEquals("设置页里改过的提示词", next.prompt)
        assertNotEquals(SeedContent.DefaultPrompt, next.prompt)
    }

    @Test
    fun `接着聊一条历史会话用那条的标识与起点`() {
        var now = 9_000_000L
        val ledger = SessionLedger { now }

        val adopted = ledger.adopt("session-old", startedAt = 1_000L)

        assertEquals("session-old", adopted.id)
        assertEquals("起点是那条会话的开头，不是接回来的这一刻", 1_000L, adopted.startedAt)
        assertEquals("接回来就算一次活动", now, adopted.lastActivityAt)
        assertEquals("收回去写的是同一条", "session-old", ledger.closeIfOpen()?.id)
        assertNull("同一条只交一次账", ledger.closeIfOpen())
    }
}

/**
 * 新的一段开场要发给模型的第一句：`prompts/session.md` 有材料就用它，空着才用固定问候。
 */
class OpeningTest {

    @Test
    fun `开场材料非空就用它`() {
        assertEquals("今天从没睡好的早晨聊起。", openingOf("  今天从没睡好的早晨聊起。 ", "打个招呼"))
    }

    @Test
    fun `开场材料空着或者只有空白时用固定问候`() {
        assertEquals("打个招呼", openingOf("", "打个招呼"))
        assertEquals("打个招呼", openingOf(" \n  ", "打个招呼"))
    }
}
