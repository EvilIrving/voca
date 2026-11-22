package com.parley.speaker.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 出厂文字只有代码里这一份，运行时不读仓库里的 `prompts/`：那三个文件是调改时的工作副本。
 * 这里断言的是代码里那份的内容与边界，改动时同步改工作副本。
 */
class SeedContentTest {

    @Test
    fun `出厂系统提示词就是二语习得那一段`() {
        val prompt = SeedContent.DefaultPrompt

        assertTrue("这段是给陪练的行为说明", prompt.contains("可理解性输入"))
        assertTrue(prompt.contains("雅思六点五"))
        assertTrue("末尾要落在反馈方式上", prompt.contains("通俗易懂的中文点明差异"))
        assertFalse("出厂值不该带换行", prompt.contains("\n"))
    }

    @Test
    fun `开场材料现在是空的，空就沿用固定问候`() {
        assertEquals("", SeedContent.SessionOpening)
        assertEquals("你好", com.parley.speaker.ui.chat.openingOf(SeedContent.SessionOpening, "你好"))
    }

    @Test
    fun `全局记忆的起点讲的是怎么记住`() {
        val seed = SeedContent.DefaultMemory

        assertTrue(seed.contains("记住"))
        assertTrue("还要说得清怎么改", seed.contains("旧说法被推翻就直接改写"))
        assertTrue("记忆是一段叙述", seed.contains("连贯的中文叙述"))
    }
}
