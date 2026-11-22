package com.parley.speaker.ui.chat

import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 画板定稿的几组数在这里钉住：改尺寸时先看这几条会不会红，再去真机上量。
 * 参照 Figma「Parley 语音输入条」那一页，屏幕按 360 宽、左右各留 7 算。
 */
class InputBarSpecTest {

    @Test
    fun `胶囊三百六的屏上二百八十八，容器一百二十高，行从六十四起`() {
        assertEquals(288.dp, InputBarSpec.pillWidth(360.dp))
        assertEquals(120.dp, InputBarSpec.Height)
        assertEquals(64.dp, InputBarSpec.PillTop)
        // 三段各自钉住：只把 PillTop 的定义拄一遍验不出东西，改哪一段都得让这里红。
        assertEquals(8.dp, InputBarSpec.BaseBottom)
        assertEquals(48.dp, InputBarSpec.PillHeight)
        assertEquals(InputBarSpec.Height - InputBarSpec.BaseBottom, InputBarSpec.PillTop + InputBarSpec.PillHeight)
    }

    @Test
    fun `圆钮与四十八同心，长到八十二右缘越过屏幕十四`() {
        val band = 360.dp
        val center = InputBarSpec.circleCenter(band)
        assertEquals(329.dp, center)
        assertEquals(center, band - InputBarSpec.SideInset - InputBarSpec.ActionSize / 2)
        assertEquals(14.dp, center + InputBarSpec.RecordSize / 2 - band)
    }

    @Test
    fun `暂停预览里垃圾桶加时间轴还塞得进二百八十八`() {
        val used = 44.dp + InputBarSpec.TimelineWidth
        assertEquals(280.dp, used)
        assertTrue(used <= InputBarSpec.pillWidth(360.dp))
        assertEquals(InputBarSpec.PillHeight - 8.dp * 2, InputBarSpec.TimelineHeight)
    }

    @Test
    fun `悬浮件都从胶囊顶上让出来，锁条贴顶、暂停键留八`() {
        // 锁条底边贴着胶囊顶，顶边落在容器里第八行。
        assertEquals(8.dp, InputBarSpec.PillTop - InputBarSpec.LockHeight)
        assertEquals(8.dp, InputBarSpec.PillTop - InputBarSpec.FloatGap - InputBarSpec.PauseSize)
        assertEquals(16.dp, InputBarSpec.PillTop - InputBarSpec.FloatGap - InputBarSpec.ResumeSize)
        assertEquals(36.dp, InputBarSpec.LockWidth)
        assertEquals(96.dp, InputBarSpec.TooltipWidth)
    }

    @Test
    fun `锁定态的十八根电平柱从一百六十二铺到二百六十七`() {
        val width = InputBarSpec.LiveBarStep * (InputBarSpec.LiveBarCount - 1) + InputBarSpec.LiveBarWidth
        assertEquals(105.dp, width)
        assertTrue(162.dp + width <= InputBarSpec.pillWidth(360.dp))
    }
}
