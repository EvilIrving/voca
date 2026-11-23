package com.parley.speaker.audio

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 采集留痕只看两件事：分段耗时要能分开「麦克风没开」与「开了但没数据」，
 * 空档要能把通话抢占麦克风那一下数出来。
 */
class CaptureTraceTest {

    @Test
    fun `第一帧之前的耗时算开机时间`() {
        val trace = CaptureTrace()
        trace.opened(1000)
        repeat(10) { trace.started(1080 + it * 20L, 20) }

        assertEquals("按下到第一帧八十毫秒", 80L, trace.openMs)
        assertEquals(80L, trace.firstFrameMs)
        assertEquals(10, trace.frames)
        assertEquals(200L, trace.frameMs)
        assertEquals("帧与帧之间是二十毫秒的节奏", 20L, trace.maxGapMs)
        assertEquals(0, trace.interruptions)
    }

    @Test
    fun `超过半秒的空档记一次中断`() {
        val trace = CaptureTrace()
        trace.opened(0)
        trace.started(40, 20)
        trace.started(60, 20)
        trace.started(60 + CaptureTrace.GapLimitMs + 1, 20)
        trace.started(700 + 20, 20)
        trace.started(760, 20)

        assertEquals(1, trace.interruptions)
        assertEquals(CaptureTrace.GapLimitMs + 1, trace.maxGapMs)
        assertEquals(5, trace.frames)
    }

    @Test
    fun `没启动就没数字`() {
        val trace = CaptureTrace()
        trace.opened(500)

        assertEquals(-1L, trace.openMs)
        assertEquals(0, trace.frames)
        assertEquals("open=-1ms first=-1ms frames=0 audio=0ms gapMax=0ms interruptions=0", trace.describe())
    }
}
