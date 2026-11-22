package com.parley.speaker.ui.chat

import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 画板上的几组数在这里钉住：改尺寸时先看这几条会不会红，再去真机上量。
 * 参照的是 docs/design 规范页的输入条一节，屏幕按 360×792、左右各留 7 算。
 */
class InputBarSpecTest {

    @Test
    fun `胶囊三百四十六，右侧那颗四十四收在条内，读字区剩下三百零二`() {
        assertEquals(346.dp, InputBarSpec.CapsuleWidth)
        assertEquals(44.dp, InputBarSpec.ButtonSize)
        assertEquals(302.dp, InputBarSpec.CapsuleWidth - InputBarSpec.ButtonSize)
    }

    @Test
    fun `圆钮与四十四同心，长大后右端溢出条外二十一`() {
        assertEquals(0.dp, InputBarSpec.centerOnButton(InputBarSpec.ButtonSize))
        assertEquals(21.5.dp, InputBarSpec.centerOnButton(InputBarSpec.CircleSize))
        // 三百六的屏幕、左右各七，条右端落在三百五十三，八十七的圆心在三百三十一，
        // 右边缘因此越过屏幕十四点五，与实机上右侧被切掉那一小块对得上。
        val screen = 360.dp
        val circleCentre = InputBarSpec.SideInset + InputBarSpec.CapsuleWidth - InputBarSpec.ButtonSize / 2
        val overflow = circleCentre + InputBarSpec.CircleSize / 2 - screen
        assertEquals(14.5.dp, overflow)
    }

    @Test
    fun `暂停预览里垃圾桶加波形条还塞得进三百零二`() {
        val used = InputBarSpec.TrashSize + InputBarSpec.PreviewWidth
        assertEquals(296.dp, used)
        assertTrue(used <= InputBarSpec.CapsuleWidth - InputBarSpec.ButtonSize)
    }

    @Test
    fun `底栏高度由十二加四十四加二十算出，草稿输入同高`() {
        assertEquals(76.dp, InputBarSpec.Height)
        // 三段各自钉住：只把 Height 的定义拄一遍验不出东西，改哪一段都得让这里红。
        assertEquals(12.dp, InputBarSpec.TopPad)
        assertEquals(44.dp, InputBarSpec.CapsuleHeight)
        assertEquals(20.dp, InputBarSpec.BottomPad)
        assertEquals("胶囊与右端那颗按钮同高，底栏只有上下两道留白", InputBarSpec.ButtonSize, InputBarSpec.CapsuleHeight)
    }
}
