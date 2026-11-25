package com.voca.speaker.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import com.voca.speaker.data.analysis.TextModel
import com.voca.speaker.data.analysis.TextProvider
import com.voca.speaker.domain.AnalysisCredentials
import com.voca.speaker.domain.AppSettings
import com.voca.speaker.domain.AuthKind
import com.voca.speaker.domain.LiveDefaults
import com.voca.speaker.domain.LiveSettings
import com.voca.speaker.domain.LiveVendor
import com.voca.speaker.domain.VoiceCatalog
import com.voca.speaker.ui.component.FloatingPanel
import com.voca.speaker.ui.component.FloatingShadowInset
import com.voca.speaker.ui.component.Pressable
import com.voca.speaker.ui.icon.FluentIcons
import com.voca.speaker.ui.theme.Palette
import com.voca.speaker.ui.theme.Radius
import com.voca.speaker.ui.theme.Type

/** 配置行固定五十四；选项行不管带不带副行都固定四十四，两行文字也不许把行撑高。 */
internal val ConfigRowHeight = 54.dp
internal val OptionRowHeight = 44.dp

/** 下拉与配置行同宽，压在后续行之上。 */
internal val MenuWidth = 320.dp

/** 菜单最多摊这么高，再长的清单在面板里滚。 */
private val MenuMaxHeight = 340.dp

/**
 * 菜单里的一行。[label] 是显示的名字，音色这类中文名与协议里那个 id 不是一回事；
 * [note] 是副行，模型摆参数、音色摆风格词，[group] 换了就插一段小标题。
 */
data class MenuOption(
    val id: String,
    val label: String = id,
    val note: String? = null,
    val group: String = "",
)

/** 一份菜单：候选项与当前选中项。选中项不在候选项里就一行都不勾。 */
data class MenuSpec(val options: List<MenuOption>, val selected: String)

/** 音色下拉：清单来自这一家的静态目录，名字作行名、风格词作副行，分族的名字作分段标题。 */
fun voiceMenu(vendor: LiveVendor, selected: String): MenuSpec = MenuSpec(
    options = VoiceCatalog.voices(vendor).map {
        MenuOption(id = it.id, label = it.name, note = it.style, group = it.group)
    },
    selected = selected,
)

/** 界面上写的那只音色：目录里的名字，目录外的旧值原样写回去。 */
fun voiceName(vendor: LiveVendor, id: String): String =
    VoiceCatalog.voices(vendor).firstOrNull { it.id == id }?.name ?: id

/** 这一家挑好的音色，不在目录里就回默认。 */
fun currentVoice(live: LiveSettings, vendor: LiveVendor): String =
    live.tuningOf(vendor).voice.takeIf { VoiceCatalog.isKnown(vendor, it) }
        ?: LiveDefaults.voice(vendor)

/** 模型空着或不在这一家的清单里时，下拉的值就这么写。 */
internal const val Unpicked = "未选择"

private fun tokens(value: Int): String = when {
    value >= 1_000_000 -> "${value / 1_000_000}M"
    value >= 1_000 -> "${value / 1_000}K"
    else -> value.toString()
}

/** 模型行副行只摆真实元数据，没同步到的留空，不编数字。 */
private fun metaLine(model: TextModel): String? = buildList {
    if (model.context > 0) add("${tokens(model.context)} 上下文")
    if (model.maxOutput > 0) add("${tokens(model.maxOutput)} 输出")
    if (model.thinking) add("思考")
}.joinToString(" · ").ifBlank { null }

/** 模型下拉的候选只来自这一家拉到的清单，选中项不在清单里就一行都不勾。 */
internal fun modelChoices(provider: TextProvider, selected: String): MenuSpec = MenuSpec(
    options = provider.models.map { MenuOption(id = it.id, note = metaLine(it)) },
    selected = selected,
)

/**
 * 保存这一家的凭据并切过去。目录里的供应商只存密钥，端点与鉴权走默认值；自定义端点那几项都落盘。
 * 每家各写自己那一格，配第二家不会动第一家的密钥；存完齐备就把这家设为生效那家。
 * 模型留空表示沿用这一家旧值，界面不往这里塞空串。
 */
fun AppSettings.withAnalysisCredentials(
    provider: TextProvider,
    apiKey: String,
    model: String,
    endpoint: String,
    authKind: AuthKind?,
    authName: String,
    authPrefix: String,
): AppSettings {
    val current = analysis.of(provider.id)
    val next = AnalysisCredentials(
        apiKey = apiKey.trim(),
        model = model.trim().ifBlank { current.model },
        endpoint = if (provider.custom) endpoint.trim() else "",
        authKind = if (provider.custom) authKind else null,
        authName = if (provider.custom) authName.trim() else "",
        authPrefix = if (provider.custom) authPrefix.trim() else "",
    )
    return copy(analysis = analysis.amend(provider.id) { next }.selectIfReady(provider.id))
}

/**
 * 只认这一家清单里的模型 id。清单外的值与空串都不写回去。
 * 挑谁家的模型就用谁家，因此写模型的同时把这家设为生效。返回 null 表示这次点击不改设置。
 */
fun AppSettings.withAnalysisModel(provider: TextProvider, modelId: String): AppSettings? {
    if (provider.models.none { it.id == modelId }) return null
    return copy(analysis = analysis.amend(provider.id) { it.copy(model = modelId) }.selectIfReady(provider.id))
}

/** 鉴权落点的三档名字，只给设置页那排小钮用。 */
internal fun AuthKind.label(): String = when (this) {
    AuthKind.Bearer -> "Bearer"
    AuthKind.Header -> "请求头"
    AuthKind.Query -> "查询参数"
}

@Composable
fun ModelMenu(spec: MenuSpec, onPick: (String) -> Unit, onDismiss: () -> Unit) {
    // 窗口尺寸就是内容尺寸，阴影只能用内边距换，锚点间距按同一块抵消掉，菜单位置不变。
    val inset = FloatingShadowInset
    val gap = with(LocalDensity.current) { (4.dp - inset).roundToPx() }
    Popup(
        popupPositionProvider = remember(gap) { UnderAnchor(gap) },
        onDismissRequest = onDismiss,
        properties = PopupProperties(focusable = true),
    ) {
        Box(Modifier.padding(inset)) {
            FloatingPanel(
                shape = RoundedCornerShape(Radius.card),
                modifier = Modifier.width(MenuWidth),
            ) {
                val scroll = rememberScrollState()
                Column(
                    Modifier
                        .padding(6.dp)
                        .heightIn(max = MenuMaxHeight)
                        .verticalScroll(scroll),
                ) {
                    spec.options.forEachIndexed { index, option ->
                        val newGroup = option.group.isNotBlank() &&
                            option.group != spec.options.getOrNull(index - 1)?.group
                        if (newGroup) {
                            Text(
                                text = option.group,
                                style = Type.captionInk4,
                                modifier = Modifier.padding(start = 10.dp, top = 6.dp, bottom = 2.dp),
                            )
                        }
                        MenuRow(option, option.id == spec.selected, onPick)
                    }
                }
            }
        }
    }
}

@Composable
private fun MenuRow(option: MenuOption, selected: Boolean, onPick: (String) -> Unit) {
    Pressable(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(Radius.chip),
        resting = if (selected) Palette.clayTint else Color.Transparent,
        onClick = { onPick(option.id) },
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(OptionRowHeight)
                .padding(horizontal = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.Center) {
                Text(
                    text = option.label,
                    style = Type.uiStrong,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                option.note?.let {
                    Spacer(Modifier.height(2.dp))
                    Text(
                        text = it,
                        style = Type.clock,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            if (selected) {
                Spacer(Modifier.width(8.dp))
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
