package com.voca.speaker.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 行高补位：长出来的那一格先让回来再逐帧收掉，一屏级别的跳变不让位，滚动时直接对齐。
 */
class ContentSlideTest {

    private val frameNanos = 16_666_667L

    private fun settled(frames: Int, from: ContentSlide, height: Int, start: Long): Pair<Float, Long> {
        var now = start
        var shift = 0f
        repeat(frames) {
            now += frameNanos
            shift = from.settle(height, now)
        }
        return shift to now
    }

    @Test
    fun `第一帧只对齐，不让位`() {
        assertEquals(0f, ContentSlide().settle(1200, frameNanos), 0.01f)
    }

    @Test
    fun `长出一行就让回来一行，再逐帧收掉`() {
        val slide = ContentSlide()
        val start = frameNanos
        slide.settle(1200, start)
        val grown = slide.settle(1269, start + frameNanos)
        // 让回来的是整格行高，只被这一帧的衰减吃掉两成
        assertTrue("长出 69 像素只让回 $grown", grown in 45f..69f)
        val (after, _) = settled(frames = 22, from = slide, height = 1269, start = start + frameNanos)
        assertEquals(0f, after, 0.01f)
    }

    @Test
    fun `一屏级别的跳变不让位，直接从当下开始`() {
        val slide = ContentSlide()
        val start = frameNanos
        slide.settle(1200, start)
        assertEquals(0f, slide.settle(4000, start + frameNanos), 0.01f)
    }

    @Test
    fun `对齐之后不再补位`() {
        val slide = ContentSlide()
        val start = frameNanos
        slide.settle(1200, start)
        slide.settle(1269, start + frameNanos)
        slide.sync(1269)
        assertEquals(0f, slide.settle(1269, start + 2 * frameNanos), 0.01f)
    }

    @Test
    fun `内容变矮不当成补位`() {
        val slide = ContentSlide()
        val start = frameNanos
        slide.settle(1200, start)
        assertEquals(0f, slide.settle(900, start + frameNanos), 0.01f)
    }
}
