package com.parley.speaker.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 接着一条历史会话聊的开场材料：只取最近那段流水，装不下的早轮次丢掉，
 * 一轮话都没有就不带材料，新的一段照常开场。
 */
class ContinuationTest {

    private fun turn(id: Int, speaker: Speaker, text: String) =
        Turn(id = "t$id", speaker = speaker, text = text)

    @Test
    fun `一轮都没有时不带材料`() {
        assertNull(continuationHistory(emptyList()))
        assertNull(continuationHistory(listOf(turn(1, Speaker.User, "   "))))
    }

    @Test
    fun `流水按学生与助教带名字，开头交代接着聊`() {
        val history = continuationHistory(
            listOf(
                turn(1, Speaker.User, "I went to the coffee shop yesterday."),
                turn(2, Speaker.Echo, "How was the coffee there?"),
            ),
        ).orEmpty()

        assertTrue(history.contains("接着往下聊"))
        assertTrue(history.contains("学生：I went to the coffee shop yesterday."))
        assertTrue(history.contains("助教：How was the coffee there?"))
        assertTrue("学生那句在助教那句之前", history.indexOf("学生：") < history.indexOf("助教："))
    }

    @Test
    fun `超出预算时留最近的几轮，早的丢掉`() {
        val long = "a".repeat(ContinuationChars / 2)
        val history = continuationHistory(
            listOf(
                turn(1, Speaker.User, "最早的那一句"),
                turn(2, Speaker.Echo, long),
                turn(3, Speaker.User, long),
            ),
        ).orEmpty()

        assertTrue(history.isNotEmpty())
        assertFalse("装不下的早轮次不再带", history.contains("最早的那一句"))
        assertTrue(history.length < ContinuationChars + 200)
    }

    @Test
    fun `系统提示后面接材料，没有材料就原样返回`() {
        assertEquals("角色", promptWithHistory("角色", null))
        assertEquals("角色", promptWithHistory("角色", "  "))
        assertEquals("角色\n\n以下是这场对话之前的内容", promptWithHistory("角色", "以下是这场对话之前的内容"))
        assertEquals("以下是这场对话之前的内容", promptWithHistory("", "以下是这场对话之前的内容"))
    }
}
