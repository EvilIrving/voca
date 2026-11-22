package com.parley.speaker.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.graphics.Color
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

private fun newsreader(weight: Int) = Font(
    resId = R.font.newsreader,
    weight = FontWeight(weight),
    variationSettings = FontVariation.Settings(FontVariation.weight(weight)),
)

// 罗马体那只可变字重里没有斜体字形，标成 italic 只会原样直立。斜体另取一只 Newsreader Italic。
private fun newsreaderItalic(weight: Int) = Font(
    resId = R.font.newsreader_italic,
    weight = FontWeight(weight),
    style = FontStyle.Italic,
    variationSettings = FontVariation.Settings(FontVariation.weight(weight)),
)

val InterFamily = FontFamily(inter(400), inter(500), inter(600))
val NewsreaderFamily = FontFamily(
    newsreader(400),
    newsreader(500),
    newsreaderItalic(400),
    notoSerif(400),
    notoSerif(500),
)

// Newsreader 里没有一个汉字，中文行单独指名 Noto Serif SC，靠系统兜底会掉到无衬线。
val NotoSerifFamily = FontFamily(notoSerif(400), notoSerif(500))
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

    /** 新会话首屏那三段：产品名二十八、英文标语十六斜体、中文标语十三，行高照画板。 */
    val hero = TextStyle(
        fontFamily = NewsreaderFamily,
        fontSize = 28.sp,
        lineHeight = 34.sp,
        fontWeight = FontWeight.Medium,
        color = Palette.ink,
    )
    val heroTagline = TextStyle(
        fontFamily = NewsreaderFamily,
        fontSize = 16.sp,
        lineHeight = 22.sp,
        fontStyle = FontStyle.Italic,
        color = Palette.ink2,
    )
    val heroCn = TextStyle(
        fontFamily = NotoSerifFamily,
        fontSize = 13.sp,
        lineHeight = 18.sp,
        color = Palette.ink4,
    )

    /** 系统提示词与全局记忆都是中文叙述，比正文小一号、行高紧一点。 */
    val promptProse = prose.copy(fontSize = 13.sp, lineHeight = 21.sp, color = Palette.ink2)
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

    // 界面上反复出现的颜色与字号组合在这里定成常量：组合期不再逐处 copy 新建 TextStyle。
    val captionInk3 = caption.copy(color = Palette.ink3)
    val captionInk4 = caption.copy(color = Palette.ink4)
    val captionClay = caption.copy(color = Palette.clayDeep)
    val uiInk = ui.copy(color = Palette.ink)
    val uiInk3 = ui.copy(color = Palette.ink3)
    val uiInk4 = ui.copy(color = Palette.ink4)
    val uiStrongClay = uiStrong.copy(color = Palette.clayDeep)
    val uiStrongInk2 = uiStrong.copy(color = Palette.ink2)
    val uiStrongDanger = uiStrong.copy(color = Palette.danger)
    val uiStrongOnDark = uiStrong.copy(color = Color.White)
    val clock = mono.copy(fontSize = 10.5.sp, lineHeight = 14.sp)

    /** 打卡热力图的月份与周标：八点五号，只在轴上一行，字号再大就把格子挤走。 */
    val heatAxis = mono.copy(fontSize = 8.5.sp, lineHeight = 12.sp, color = Palette.ink4)
    val translation = ui.copy(fontSize = 12.5.sp, lineHeight = 19.sp, color = Palette.ink3)
    val translationTight = ui.copy(fontSize = 12.5.sp, lineHeight = 18.sp, color = Palette.ink3)
    val badge = ui.copy(fontSize = 11.5.sp, lineHeight = 16.sp)
}

val ParleyTypography = Typography(
    displayMedium = Type.display,
    bodyLarge = Type.prose,
    bodyMedium = Type.ui,
    labelLarge = Type.uiStrong,
    labelSmall = Type.caption,
)
