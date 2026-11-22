package com.parley.speaker.ui.component

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import com.parley.speaker.ui.theme.Palette
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 规范页交互状态那一格「按压」：只换底色，颜色由静息底色推出来。
 * 这几条钉住台阶的方向，改配色时先看这里会不会红。
 */
class PressToneTest {

    @Test
    fun `白卡与透明底按下都转 tint`() {
        assertEquals(Palette.tint, pressedTone(Palette.card))
        assertEquals(Palette.tint, pressedTone(Color.Transparent))
    }

    @Test
    fun `中间那层 tint 再按下去落到 hair`() {
        assertEquals(Palette.hair, pressedTone(Palette.tint))
    }

    @Test
    fun `陶土浅底加深一档，陶土实底降到 clayDeep`() {
        assertEquals(Palette.clayTintPressed, pressedTone(Palette.clayTint))
        assertEquals(Palette.clayDeep, pressedTone(Palette.clay))
        assertTrue(
            "浅底要更深，不然按下去看不出来",
            Palette.clayTintPressed.luminance() < Palette.clayTint.luminance(),
        )
        assertTrue(
            "实底要更沉，陶土不许越按越亮",
            Palette.clayDeep.luminance() < Palette.clay.luminance(),
        )
    }

    @Test
    fun `每种静息底色都推得出一格不同的按压色`() {
        val restings = listOf(
            Palette.card,
            Color.Transparent,
            Palette.tint,
            Palette.clayTint,
            Palette.clay,
        )
        restings.forEach { resting ->
            assertTrue("$resting 按下没有变化", pressedTone(resting) != resting)
        }
    }
}
