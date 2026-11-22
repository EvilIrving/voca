package com.parley.speaker.ui.settings

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
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import com.parley.speaker.data.analysis.TextProvider
import com.parley.speaker.domain.AppSettings
import com.parley.speaker.domain.AssessorProvider
import com.parley.speaker.domain.AssessorSettings
import com.parley.speaker.domain.AuthKind
import com.parley.speaker.domain.LiveDefaults
import com.parley.speaker.domain.LiveSettings
import com.parley.speaker.domain.LiveVendor
import com.parley.speaker.domain.VoiceCatalog
import com.parley.speaker.ui.component.FloatingPanel
import com.parley.speaker.ui.component.FloatingShadowInset
import com.parley.speaker.ui.component.Pressable
import com.parley.speaker.ui.icon.FluentIcons
import com.parley.speaker.ui.theme.Palette
import com.parley.speaker.ui.theme.Radius
import com.parley.speaker.ui.theme.Type

/** 配置行固定五十四；选项行不管带不带副行都固定四十四，两行文字也不许把行撑高。 */
internal val ConfigRowHeight = 54.dp
internal val OptionRowHeight = 44.dp

/** 下拉与配置行同宽，压在后续行之上。 */
internal val MenuWidth = 320.dp

/** 菜单最多摊这么高，再长的清单在面板里滚。 */
private val MenuMaxHeight = 340.dp

/**
 * 菜单里的一行。[group] 换了就插一段小标题，音色按族分档时用；
 * [note] 是副行，模型摆参数、音色摆风格词。
 */
data class MenuOption(val id: String, val note: String? = null, val group: String = "")

/** 一份菜单：候选项与当前选中项。选中项不在候选项里就一行都不勾。 */
data class MenuSpec(val options: List<MenuOption>, val selected: String)

/** 音色下拉：清单来自这一家的静态目录，风格词作副行，分族的名字作分段标题。 */
fun voiceMenu(vendor: LiveVendor, selected: String): MenuSpec = MenuSpec(
    options = VoiceCatalog.voices(vendor).map { MenuOption(it.id, it.style, it.group) },
    selected = selected,
)

/** 打开对话框时的音色：这一家的旧值还在目录里就留着，否则回该家的默认音色。 */
fun currentVoice(live: LiveSettings, vendor: LiveVendor): String =
    live.voice.takeIf { live.provider == vendor && VoiceCatalog.isKnown(vendor, it) }
        ?: LiveDefaults.voice(vendor)

/** 音色名下面那一行风格词。 */
fun voiceStyle(vendor: LiveVendor, id: String): String? =
    VoiceCatalog.voices(vendor).firstOrNull { it.id == id }?.style

/** 一家的凭据表单项。key 既是草稿的键，也是 [liveSaved] 认的格子。 */
internal data class LiveField(val key: String, val label: String, val value: String, val secret: Boolean = false)

internal fun liveFields(live: LiveSettings, vendor: LiveVendor): List<LiveField> = when (vendor) {
    LiveVendor.Gemini -> listOf(LiveField("geminiKey", "密钥", live.geminiKey, secret = true))
    LiveVendor.OpenAi -> listOf(LiveField("openAiKey", "密钥", live.openAiKey, secret = true))
    LiveVendor.Doubao -> listOf(
        LiveField("doubaoAppId", "AppID", live.doubaoAppId),
        LiveField("doubaoAppKey", "AppKey", live.doubaoAppKey, secret = true),
        LiveField("doubaoAccessKey", "AccessKey", live.doubaoAccessKey, secret = true),
    )
}

/**
 * 保存这一家的凭据并切过去。模型与音色不在这里定，存储器按目录归位：
 * 换到另一家整体落到那家的默认模型与默认音色，同一家则留住挑好的音色。
 */
fun AppSettings.liveSaved(vendor: LiveVendor, values: Map<String, String>, voice: String): AppSettings {
    val trimmed = values.mapValues { (_, value) -> value.trim() }
    val credentials = when (vendor) {
        LiveVendor.Gemini -> live.copy(geminiKey = trimmed["geminiKey"].orEmpty())
        LiveVendor.OpenAi -> live.copy(openAiKey = trimmed["openAiKey"].orEmpty())
        LiveVendor.Doubao -> live.copy(
            doubaoAppId = trimmed["doubaoAppId"].orEmpty(),
            doubaoAppKey = trimmed["doubaoAppKey"].orEmpty(),
            doubaoAccessKey = trimmed["doubaoAccessKey"].orEmpty(),
        )
    }
    return copy(live = credentials.copy(provider = vendor, voice = voice))
}

/**
 * 目录里的供应商只存密钥，端点与鉴权走默认值；自定义端点那几项都落盘。
 * 模型留空表示沿用旧值，换供应商时由存储器清空等新清单到位，界面不往这里塞空串。
 */
fun AppSettings.withAnalysisCredentials(
    provider: TextProvider,
    apiKey: String,
    model: String,
    endpoint: String,
    authKind: AuthKind?,
    authName: String,
    authPrefix: String,
): AppSettings = copy(
    analysis = analysis.copy(
        provider = provider.id,
        apiKey = apiKey.trim(),
        model = model.trim().ifBlank { analysis.model },
        endpoint = if (provider.custom) endpoint.trim() else "",
        authKind = if (provider.custom) authKind else null,
        authName = if (provider.custom) authName.trim() else "",
        authPrefix = if (provider.custom) authPrefix.trim() else "",
    ),
)

/**
 * 只认这一家清单里的模型 id。清单外的值与空串都不写回去，界面显示未选择等用户挑一个。
 * 返回 null 表示这次点击不改设置。
 */
fun AppSettings.withAnalysisModel(provider: TextProvider, modelId: String): AppSettings? {
    if (provider.id != analysis.provider) return null
    if (provider.models.none { it.id == modelId }) return null
    return copy(analysis = analysis.copy(model = modelId))
}

/** Azure 认密钥与区域两项，填完按齐备情况归位。 */
fun AssessorSettings.withAzure(key: String, region: String): AssessorSettings =
    copy(azureKey = key.trim(), azureRegion = region.trim()).resolvedAfterSaving(AssessorProvider.Azure)

/** 讯飞认 AppID、APIKey、APISecret 三件套，填完按齐备情况归位。 */
fun AssessorSettings.withXfyun(appId: String, apiKey: String, apiSecret: String): AssessorSettings =
    copy(
        xfyunAppId = appId.trim(),
        xfyunApiKey = apiKey.trim(),
        xfyunApiSecret = apiSecret.trim(),
    ).resolvedAfterSaving(AssessorProvider.Xfyun)

/**
 * 保存后归位：刚填的那家作候选，它参数不齐就退回另一家，两家都不齐落到未选择。
 * 两家都齐备时认刚填的那家，免得改完讯飞又被 Azure 那格夺回去。
 */
internal fun AssessorSettings.resolvedAfterSaving(vendor: AssessorProvider): AssessorSettings =
    copy(provider = copy(provider = vendor).resolvedProvider)

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
                    text = option.id,
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
