package com.voca.speaker.domain

import com.voca.speaker.data.SeedContent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 记忆的三条规矩：系统提示怎么拼、改写回来的东西收不收、一次改写该看哪几句话。
 * 记忆是内部机制，这里断言的是它的边界，不是它在界面上长什么样。
 */
class MemoryTest {

    private fun user(id: String, text: String) = Turn(id = id, speaker = Speaker.User, text = text)

    private fun echo(id: String, text: String) = Turn(id = id, speaker = Speaker.Echo, text = text)

    @Test
    fun `角色提示词在前，记忆在后`() {
        val prompt = systemPromptOf("你是陪练。", "他下周三要去面试。")

        assertTrue("角色原样留在最前面", prompt.startsWith("你是陪练。"))
        assertTrue("记忆接着说", prompt.indexOf("他下周三要去面试。") > prompt.indexOf("你是陪练。"))
        assertTrue("记忆前面有一句交代", prompt.contains("当作你自己的记忆"))
    }

    @Test
    fun `没有记忆就只剩角色提示词`() {
        assertEquals("你是陪练。", systemPromptOf("你是陪练。", ""))
        assertEquals("你是陪练。", systemPromptOf("你是陪练。", "   \n "))
        assertEquals("", systemPromptOf("", ""))
    }

    @Test
    fun `没有角色提示词时记忆照旧进系统提示`() {
        val prompt = systemPromptOf("", "他习惯先想十秒再开口。")

        assertTrue(prompt.contains("他习惯先想十秒再开口。"))
        assertFalse("空角色不留一段空行", prompt.startsWith("\n"))
    }

    @Test
    fun `空文本与没变的那份都不收`() {
        assertNull("改写失败就当这一轮没记住新东西", admitMemory("他叫李明。", null))
        assertNull(admitMemory("他叫李明。", "  "))
        assertNull("一字不差的不必写一遍", admitMemory("他叫李明。", "他叫李明。"))
    }

    @Test
    fun `收下来的记忆去掉首尾空白`() {
        assertEquals("他叫李明，准备雅思。", admitMemory("他叫李明。", "  他叫李明，准备雅思。\n"))
    }

    @Test
    fun `抄了原文的长文本整份退回`() {
        val copied = "他叫李明。".repeat(MemoryMaxChars)
        assertNull("超过上限说明它在抄对话", admitMemory("他叫李明。", copied))
        assertEquals("正好到上限还收", "字".repeat(MemoryMaxChars), admitMemory("他叫李明。", "字".repeat(MemoryMaxChars)))
    }

    @Test
    fun `材料取这一轮前后各一句`() {
        val turns = listOf(
            user("u1", "I goed to the park."),
            echo("e1", "You went to the park?"),
            user("u2", "Yes, with my sister."),
            echo("e2", "Nice, what did you do there?"),
        )

        assertEquals(
            "助教：You went to the park?\n学生：Yes, with my sister.\n助教：Nice, what did you do there?",
            memoryMaterial(turns, "u2"),
        )
    }

    @Test
    fun `第一轮没有前一句，就只带它自己与回话`() {
        val turns = listOf(user("u1", "Hi"), echo("e1", "Hello!"))

        assertEquals("学生：Hi\n助教：Hello!", memoryMaterial(turns, "u1"))
    }

    @Test
    fun `空白的轮次不进材料`() {
        val turns = listOf(echo("e0", " "), user("u1", "Hi"))

        assertEquals("学生：Hi", memoryMaterial(turns, "u1"))
    }

    @Test
    fun `这一轮已经不在列表里就什么都不给`() {
        val turns = listOf(user("u1", "Hi"), echo("e1", "Hello"))

        assertNull("换了段之后那一轮已经不在手上", memoryMaterial(turns, "u9"))
        assertNull(memoryMaterial(emptyList(), "u1"))
    }

    @Test
    fun `默认记忆是叙述，不是带标记的待办`() {
        val seed = SeedContent.DefaultMemory

        assertTrue("默认记忆是这一场的起点，不能空", seed.isNotBlank())
        assertTrue("整段在一行的长度里", seed.length <= MemoryMaxChars)
        listOf("TODO", "- ", "【", "<!--", "时间戳：", "（待补）").forEach {
            assertFalse("默认记忆里不该出现 $it", seed.contains(it))
        }
    }
}
