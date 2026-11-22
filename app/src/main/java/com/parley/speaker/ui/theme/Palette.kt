package com.parley.speaker.ui.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

object Palette {
    val paper = Color(0xFFFAF9F5)
    val card = Color(0xFFFFFFFF)
    val tint = Color(0xFFF0EEE6)
    val hair = Color(0xFFE8E3D6)
    val hairStrong = Color(0xFFD8D2C0)
    /** 纸面描边：实底细边线压到一半透明度，白卡落在羊皮纸上只剩一层淡边。 */
    val hairSoft = hair.copy(alpha = 0.5f)

    val ink = Color(0xFF141413)
    val ink2 = Color(0xFF3B3934)
    val ink3 = Color(0xFF6C6860)
    val ink4 = Color(0xFF9C978C)

    val clay = Color(0xFFD97757)
    val clayDeep = Color(0xFFB85B3C)
    val clayTint = Color(0xFFF7ECE5)

    /** 陶土浅底再深一档：已选中的那格按下去用它，与 tint 转 hair 是同一段台阶。 */
    val clayTintPressed = Color(0xFFF2E1D6)

    val olive = Color(0xFF75855A)
    val oliveTint = Color(0xFFEEF1E5)

    val danger = Color(0xFFA8503C)

    /** 浮层唯一那层暖影：陶土偏褐的低透明度，纯黑会在羊皮纸上压出一道灰边。 */
    val shade = Color(0x223A2F26)
}

object Radius {
    val bubbleTail = 5.dp
    val chip = 8.dp
    val control = 12.dp
    val card = 14.dp
    val vocabCard = 16.dp
    val bubble = 18.dp
    val sheet = 26.dp
    val pill = 999.dp
    val drawerRow = 10.dp

    /** 被推走的那张主卡片：推开后定格在三十二，拖到一半是二十六。 */
    val drawerCard = 32.dp
    val drawerCardMid = 26.dp
}

object Spacing {
    val none = 0.dp
    val hairline = 2.dp
    val iconText = 8.dp
    val cardInner = 8.dp
    val avatarGap = 10.dp
    val block = 12.dp
    val bubble = 18.dp
    val screen = 20.dp
    val drawer = 18.dp
    val sheet = 24.dp
}
