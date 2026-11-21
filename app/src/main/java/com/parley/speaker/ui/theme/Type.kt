package com.parley.speaker.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.parley.speaker.R

private fun inter(weight: Int) = Font(
    resId = R.font.inter,
    weight = FontWeight(weight),
    variationSettings = FontVariation.Settings(FontVariation.weight(weight)),
)

private fun notoSerif(weight: Int) = Font(
    resId = R.font.noto_serif_sc,
    weight = FontWeight(weight),
    variationSettings = FontVariation.Settings(FontVariation.weight(weight)),
)

private fun newsreader(weight: Int, italic: Boolean = false) = Font(
    resId = R.font.newsreader,
    weight = FontWeight(weight),
    style = if (italic) FontStyle.Italic else FontStyle.Normal,
    variationSettings = FontVariation.Settings(FontVariation.weight(weight)),
)

val InterFamily = FontFamily(inter(400), inter(500), inter(600))
val NewsreaderFamily = FontFamily(
    newsreader(400),
    newsreader(500),
    newsreader(400, italic = true),
    notoSerif(400),
    notoSerif(500),
)
val MonoFamily = FontFamily(Font(R.font.ibm_plex_mono, FontWeight.Normal))

object Type {
    val display = TextStyle(
        fontFamily = NewsreaderFamily,
        fontSize = 20.sp,
        lineHeight = 26.sp,
        fontWeight = FontWeight.Medium,
        color = Palette.ink,
    )
    val prose = TextStyle(
        fontFamily = NewsreaderFamily,
        fontSize = 15.sp,
        lineHeight = 23.sp,
        fontWeight = FontWeight.Normal,
        color = Palette.ink,
    )
    val proseItalic = prose.copy(fontStyle = FontStyle.Italic)
    val ui = TextStyle(
        fontFamily = InterFamily,
        fontSize = 13.sp,
        lineHeight = 18.sp,
        fontWeight = FontWeight.Normal,
        color = Palette.ink2,
    )
    val uiStrong = ui.copy(fontSize = 14.sp, fontWeight = FontWeight.Medium, color = Palette.ink)
    val title = ui.copy(fontSize = 16.sp, fontWeight = FontWeight.Medium, color = Palette.ink)
    val caption = ui.copy(fontSize = 11.sp, lineHeight = 15.sp, color = Palette.ink3)
    val mono = TextStyle(
        fontFamily = MonoFamily,
        fontSize = 12.sp,
        lineHeight = 16.sp,
        color = Palette.ink3,
    )
    val monoLarge = mono.copy(fontSize = 26.sp, lineHeight = 30.sp, color = Palette.ink)
    val monoScore = mono.copy(fontSize = 15.sp, lineHeight = 18.sp, color = Palette.ink2)
}

val ParleyTypography = Typography(
    displayMedium = Type.display,
    bodyLarge = Type.prose,
    bodyMedium = Type.ui,
    labelLarge = Type.uiStrong,
    labelSmall = Type.caption,
)
