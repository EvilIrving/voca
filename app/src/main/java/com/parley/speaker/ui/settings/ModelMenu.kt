package com.parley.speaker.ui.settings

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import com.parley.speaker.data.analysis.TextProvider
import com.parley.speaker.domain.AppSettings
import com.parley.speaker.domain.AuthKind
import com.parley.speaker.ui.icon.FluentIcons
import com.parley.speaker.ui.theme.Palette
import com.parley.speaker.ui.theme.Radius
import com.parley.speaker.ui.theme.Type

data class ModelOption(val id: String, val note: String? = null)

data class ModelMenuSpec(val options: List<ModelOption>, val selected: String)

private val LiveModels = listOf(
    ModelOption("gemini-3.8-live", "握手 0.9 秒 · 首包 450 毫秒"),
    ModelOption("gemini-3.8-live-extended-thinking"),
    ModelOption("gemini-3.1-flash-live-preview"),
    ModelOption("gemini-2.5-flash-native-audio-latest"),
    ModelOption("gemini-3.5-transcribe-live"),
)

// 旧版本手填过的模型名不在目录里，补一条回来，菜单才不会丢掉正在用的那一项。
private fun List<ModelOption>.withSelected(current: String): List<ModelOption> =
    if (current.isBlank() || any { it.id == current }) this else this + ModelOption(current)

/** 实时对话的模型菜单。文本分析的清单在供应商目录里，与模型行共用一套。 */
fun modelMenu(key: String, settings: AppSettings): ModelMenuSpec? = when (key) {
    "live" -> ModelMenuSpec(LiveModels.withSelected(settings.live.model), settings.live.model)
    else -> null
}

fun AppSettings.withModel(key: String, id: String): AppSettings = when (key) {
    "live" -> copy(live = live.copy(model = id))
    else -> this
}

/** 换供应商清空密钥与手填项，避免上一家的密钥被带到下一家。 */
fun AppSettings.withAnalysisProvider(provider: TextProvider): AppSettings {
    if (analysis.provider == provider.id) return this
    return copy(
        analysis = analysis.copy(
            provider = provider.id,
            model = provider.models.firstOrNull()?.id.orEmpty(),
            endpoint = "",
            authKind = null,
            authName = "",
            authPrefix = "",
            apiKey = "",
        ),
    )
}

fun AppSettings.withAnalysisModel(provider: TextProvider, model: String): AppSettings {
    val next = withAnalysisProvider(provider)
    return next.copy(analysis = next.analysis.copy(model = model))
}

/** 目录里的供应商只存密钥，端点与鉴权走默认值；自定义端点六项都落盘。 */
fun AppSettings.withAnalysisCredentials(
    provider: TextProvider,
    apiKey: String,
    model: String?,
    endpoint: String,
    authKind: AuthKind?,
    authName: String,
    authPrefix: String,
): AppSettings {
    val switching = analysis.provider != provider.id
    val nextModel = model?.trim()?.takeIf { it.isNotEmpty() }
        ?: if (switching) provider.models.firstOrNull()?.id.orEmpty() else analysis.model
    return copy(
        analysis = analysis.copy(
            provider = provider.id,
            apiKey = apiKey.trim(),
            model = nextModel,
            endpoint = if (provider.custom) endpoint.trim() else "",
            authKind = if (provider.custom) authKind else null,
            authName = if (provider.custom) authName.trim() else "",
            authPrefix = if (provider.custom) authPrefix.trim() else "",
        ),
    )
}
@Composable
fun ModelMenu(menu: ModelMenuSpec, onPick: (String) -> Unit, onDismiss: () -> Unit) {
    val gap = with(LocalDensity.current) { 4.dp.roundToPx() }
    Popup(
        popupPositionProvider = remember(gap) { UnderAnchor(gap) },
        onDismissRequest = onDismiss,
        properties = PopupProperties(focusable = true),
    ) {
        Surface(
            color = Palette.card,
            shape = RoundedCornerShape(Radius.card),
            border = BorderStroke(1.dp, Palette.hair),
            shadowElevation = 6.dp,
            modifier = Modifier.width(320.dp),
        ) {
            Column(Modifier.padding(6.dp)) {
                menu.options.forEach { option ->
                    val selected = option.id == menu.selected
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(Radius.chip))
                            .background(if (selected) Palette.clayTint else Color.Transparent)
                            .clickable { onPick(option.id) }
                            .heightIn(min = 44.dp)
                            .padding(horizontal = 10.dp, vertical = 7.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                text = option.id,
                                style = Type.uiStrong,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            option.note?.let {
                                Spacer(Modifier.height(2.dp))
                                Text(
                                    text = it,
                                    style = Type.mono.copy(fontSize = 10.5.sp, lineHeight = 14.sp),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                        if (selected) {
                            Icon(
                                FluentIcons.Check,
                                contentDescription = null,
                                modifier = Modifier.size(13.dp),
                                tint = Palette.clayDeep,
                            )
                        }
                    }
                }
            }
        }
    }
}

private class UnderAnchor(private val gap: Int) : PopupPositionProvider {
    override fun calculatePosition(
        anchorBounds: IntRect,
        windowSize: IntSize,
        layoutDirection: LayoutDirection,
        popupContentSize: IntSize,
    ): IntOffset {
        val centered = anchorBounds.left + (anchorBounds.width - popupContentSize.width) / 2
        val x = centered.coerceIn(0, (windowSize.width - popupContentSize.width).coerceAtLeast(0))
        val below = anchorBounds.bottom + gap
        val y = if (below + popupContentSize.height > windowSize.height) {
            (anchorBounds.top - gap - popupContentSize.height).coerceAtLeast(0)
        } else {
            below
        }
        return IntOffset(x, y)
    }
}
