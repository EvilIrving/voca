package com.parley.speaker.ui.chat

import com.parley.speaker.data.SessionRecord
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 收段之后异步取回来的自动名写给哪一条：自动名是给收口那一段的，
 * 期间换了段或者用户自己改过名，这一次自动名就该丢掉。
 */
class AutoNameTest {

    private fun record(id: String, name: String = "", manualName: Boolean = false) = SessionRecord(
        id = id,
        name = name,
        startedAt = 1_000L,
        endedAt = 2_000L,
        rounds = 3,
        pronunciationAvg = null,
        grammarAvg = null,
        newWordCount = 0,
        manualName = manualName,
    )

    @Test
    fun `手动改过名的那一段收口后自动名被丢掉`() {
        val renamed = record("a", name = "面试热身", manualName = true)

        assertNull("手动名不该被自动名顶掉", autoNameTarget(renamed, "a", "咖啡店点单"))
    }

    @Test
    fun `A 段命名往返期间 B 段收口，A 的名字不会写到 B 上`() {
        val a = record("a")
        val b = record("b")
        // A 段的命名请求已经发出，回来之前 B 段也收了口，顶栏那条记录已经是 B。
        assertNull("A 的名字不能落到 B 上", autoNameTarget(b, "a", "咖啡店点单"))
        assertEquals("B 段的名字原样留着", "", b.name)
        // 同一条轮到自己写回时只改自己那一条。
        val target = autoNameTarget(a, "a", "咖啡店点单")
        assertEquals("a", target?.id)
        assertEquals("咖啡店点单", target?.name)
    }

    @Test
    fun `没被改过名的那一段收口后自动名照旧写回`() {
        val target = autoNameTarget(record("a"), "a", "咖啡店点单")

        assertEquals("咖啡店点单", target?.name)
        assertFalse("自动名不改手动位", target!!.manualName)
        assertNull("还没收到任何一段时没有可写的对象", autoNameTarget(null, "a", "咖啡店点单"))
    }
}
