package com.parley.speaker.ui.theme

import androidx.compose.foundation.IndicationNodeFactory
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.ContentDrawScope
import androidx.compose.ui.node.DelegatableNode
import androidx.compose.ui.node.DrawModifierNode
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

private val ParleyColors = lightColorScheme(
    primary = Palette.clay,
    onPrimary = Color.White,
    primaryContainer = Palette.clayTint,
    onPrimaryContainer = Palette.clayDeep,
    secondary = Palette.olive,
    onSecondary = Color.White,
    secondaryContainer = Palette.oliveTint,
    onSecondaryContainer = Palette.olive,
    background = Palette.paper,
    onBackground = Palette.ink,
    surface = Palette.card,
    onSurface = Palette.ink,
    surfaceVariant = Palette.tint,
    onSurfaceVariant = Palette.ink2,
    outline = Palette.hairStrong,
    outlineVariant = Palette.hair,
    error = Palette.danger,
    onError = Color.White,
)

/**
 * 全仓唯一那只涟漪替身：什么都不画。按压反馈一律由 Component 里的 Pressable 换一层底色，
 * Material 自带的波纹在纸面上会糊出一圈灰或整块灰底，这里从根上掐掉。
 */
private object NoIndication : IndicationNodeFactory {

    private class Node : Modifier.Node(), DrawModifierNode {
        override fun ContentDrawScope.draw() = drawContent()
    }

    override fun create(interactionSource: InteractionSource): DelegatableNode = Node()

    // 单例，替身节点按它做比较，哈希给个固定值就够。
    override fun hashCode(): Int = javaClass.hashCode()

    override fun equals(other: Any?): Boolean = other === this
}

@Composable
fun ParleyTheme(content: @Composable () -> Unit) {
    val view = LocalView.current
    if (!view.isInEditMode) {
        val window = (view.context as? android.app.Activity)?.window
        if (window != null) {
            WindowCompat.getInsetsController(window, view).apply {
                isAppearanceLightStatusBars = true
                isAppearanceLightNavigationBars = true
            }
        }
    }
    CompositionLocalProvider(LocalIndication provides NoIndication) {
        MaterialTheme(
            colorScheme = ParleyColors,
            typography = ParleyTypography,
            content = content,
        )
    }
}
