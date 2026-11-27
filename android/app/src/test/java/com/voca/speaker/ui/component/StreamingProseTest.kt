package com.voca.speaker.ui.component

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import com.voca.speaker.ui.theme.Palette
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 流式正文的放字节奏与尾巴淡入：永远追得上、永远不越过全量、放出来的那一截与原文一字不差。
 */
class StreamingProseTest {

    private fun colorAt(styled: AnnotatedString, index: Int): Color =
        styled.spanStyles.lastOrNull { index >= it.start && index < it.end }?.item?.color ?: Color.Unspecified

    @Test
    fun `每一拍都往前走，且不会越过全量`() {
        val clock = RevealClock()
        var shown = 0
        var ticks = 0
        while (shown < 120) {
            val next = clock.advance(shown, 120, 16_000_000L)
            assertTrue("第 $ticks 拍没往前走", next > shown || shown == 120)
            assertTrue("越过全量", next <= 120)
            shown = next
            ticks += 1
            if (ticks > 200) break
        }
        assertEquals(120, shown)
        assertEquals(120, clock.advance(120, 120, 16_000_000L))
    }

    @Test
    fun `两百字两秒上下放完，不会一下倒出来`() {
        val clock = RevealClock()
        var shown = 0
        var ticks = 0
        while (shown < 200 && ticks < 300) {
            shown = clock.advance(shown, 200, 16_000_000L)
            ticks += 1
        }
        assertEquals(200, shown)
        assertTrue("用了 $ticks 拍", ticks in 110..140)
    }

    @Test
    fun `卡顿之后最多补三十二毫秒的字`() {
        val clock = RevealClock()
        val step = clock.advance(0, 200, 500_000_000L)
        assertTrue("一次补了 $step 个字", step <= 4)
    }

    @Test
    fun `流光不丢字，也不改字序`() {
        val text = "Can you hear me now? The feedback you gave me was wrong."
        assertEquals(text, litProse(text, 31f).text)
    }

    @Test
    fun `读过的字取墨色，没读到的取四级灰`() {
        val text = "abcdefghijklmnopqrstuvwxyz0123456789"
        val styled = litProse(text, 20f)
        assertEquals(Palette.ink, colorAt(styled, 0))
        assertEquals(Palette.ink4, colorAt(styled, 35))
    }

    @Test
    fun `游标那一个字掺进陶土色`() {
        val styled = litProse("abcdefghijklmnopqrst", 10f)
        val center = colorAt(styled, 10)
        assertTrue("游标还是本色", center != Palette.ink && center != Palette.ink4)
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
