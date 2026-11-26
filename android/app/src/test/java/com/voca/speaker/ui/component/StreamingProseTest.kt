package com.voca.speaker.ui.component

import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 流式正文的放字节奏与尾巴淡入：永远追得上、永远不越过全量、放出来的那一截与原文一字不差。
 */
class StreamingProseTest {

    @Test
    fun `每一拍都往前走，且不会越过全量`() {
        var shown = 0
        var ticks = 0
        while (shown < 120) {
            val next = nextShown(shown, 120)
            assertTrue("第 $ticks 拍没往前走", next > shown)
            assertTrue("越过全量", next <= 120)
            shown = next
            ticks += 1
            if (ticks > 200) break
        }
        assertEquals(120, shown)
        assertEquals(120, nextShown(120, 120))
    }

    @Test
    fun `欠得多步子大，欠得少步子小`() {
        assertTrue(nextShown(0, 300) - 0 > nextShown(0, 30) - 0)
        assertEquals(120, nextShown(120, 120))
    }

    @Test
    fun `单拍步长有封顶，一次推来两百个字也不会整块砸出来`() {
        var shown = 0
        var ticks = 0
        while (shown < 200) {
            val next = nextShown(shown, 200)
            assertTrue("单拍最多走 3 个字，实际走了 ${next - shown}", next - shown <= 3)
            shown = next
            ticks += 1
            if (ticks > 200) break
        }
        assertEquals(200, shown)
        assertTrue("用了 $ticks 拍", ticks in 60..100)
    }

    @Test
    fun `尾巴淡入只咬在放出来的那一截上`() {
        val text = "Can you hear me now? The feedback you gave me was wrong."
        val shown = 30
        val styled = fadingTail(text, shown)
        assertEquals(text.take(shown), styled.text)
        val last = styled.spanStyles.last()
        assertEquals(1f, last.item.color.alpha, 0.001f)
    }

    @Test
    fun `短尾巴也淡得匀，第一个字最淡`() {
        val alphas = fadingTail("ok", 2).spanStyles.map { it.item.color.alpha }
        assertEquals(2, alphas.size)
        assertEquals(0.7f, alphas[0], 0.01f)
        assertEquals(1f, alphas[1], 0.001f)
    }

    @Test
    fun `尾巴起点之前那段不涂任何颜色`() {
        val styled = fadingTail("abcdefghijklmnopqrstuvwxyz", 20)
        assertTrue(styled.spanStyles.all { it.start >= 10 })
        assertTrue(styled.spanStyles.all { it.item.color != Color.Unspecified })
    }
}
