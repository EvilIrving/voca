package com.voca.speaker.ui.chat

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 顶栏左上角气泡加号：只在对话进行中出现，空着的新会话与接着聊的历史会话都不放。
 */
class TopBarTest {

    @Test
    fun `对话进行中才出新会话图标`() {
        assertTrue(showNewSessionAction(continuing = false, turnCount = 1))
        assertTrue(showNewSessionAction(continuing = false, turnCount = 4))
        assertFalse("空着的新会话已经站在起点", showNewSessionAction(continuing = false, turnCount = 0))
        assertFalse("接着聊时左上角是返回箭头", showNewSessionAction(continuing = true, turnCount = 3))
        assertFalse(showNewSessionAction(continuing = true, turnCount = 0))
    }
}
