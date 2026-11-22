package com.parley.speaker.data

import android.app.Application
import com.parley.speaker.data.settings.readPrompt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/**
 * 提示词的出处：`prompts/` 三个文件打包成 assets，代码里只留一份兜底常量。
 * 读不到文件时不能少一段话，读得到时文件就是唯一来源。
 */
@RunWith(RobolectricTestRunner::class)
class PromptsTest {

    private val app: Application = RuntimeEnvironment.getApplication()

    @Test
    fun `系统提示词取自 system 文件而不是兜底常量`() {
        val prompts = Prompts(app)

        assertNotEquals("文件在就不走兜底", SeedContent.DefaultPrompt, prompts.system)
        assertTrue("文件里的这一段要原样读到", prompts.system.contains("可理解性输入"))
        assertTrue(prompts.system.length > SeedContent.DefaultPrompt.length)
    }

    @Test
    fun `开场材料与全局记忆各自就位`() {
        val prompts = Prompts(app)

        // prompts/session.md 现在是空的，空就是没有额外开场，沿用固定问候。
        assertEquals("", prompts.session)
        // prompts/memory.md 也是空的，记忆的起点落回种子那段口吻。
        assertEquals(SeedContent.DefaultMemory, prompts.memory)
    }

    @Test
    fun `存着的提示词还是旧默认时换成新的`() {
        val prefs = app.getSharedPreferences("prompt-legacy", android.content.Context.MODE_PRIVATE)
        prefs.edit().putString("prompt", SeedContent.DefaultPrompt).commit()

        assertEquals("换成新默认", "新的默认提示词", readPrompt(prefs, "新的默认提示词"))
    }

    @Test
    fun `自己改过的提示词不被迁移盖掉`() {
        val prefs = app.getSharedPreferences("prompt-edited", android.content.Context.MODE_PRIVATE)
        prefs.edit().putString("prompt", "我想练商务邮件里的措辞").commit()

        assertEquals("我想练商务邮件里的措辞", readPrompt(prefs, "新的默认提示词"))
    }

    @Test
    fun `没存过提示词就用默认值`() {
        val prefs = app.getSharedPreferences("prompt-fresh", android.content.Context.MODE_PRIVATE)

        assertEquals("新的默认提示词", readPrompt(prefs, "新的默认提示词"))
    }
}
