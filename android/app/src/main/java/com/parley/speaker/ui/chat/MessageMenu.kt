package com.parley.speaker.ui.chat

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import com.parley.speaker.domain.AnalysisStatus
import com.parley.speaker.domain.Speaker
import com.parley.speaker.domain.Turn
import com.parley.speaker.ui.component.FloatingShadowInset
import com.parley.speaker.ui.component.MenuItem
import com.parley.speaker.ui.component.ParleyMenu

/** 长按菜单的六行，顺序就是画板 07 屏从上到下的顺序。 */
enum class MessageCommand { Speak, Translate, Save, Copy, Retry, Delete }

/** 菜单的一行：动作词随状态改写，禁用时只把内容降到四成。 */
data class MessageAction(
    val command: MessageCommand,
    val label: String,
    val enabled: Boolean = true,
)

/**
 * 生成菜单要读到的那点状态，全部从这一轮自己身上取。
 * [retryable] 由调用方按同一轮里用户那句的分析状态算出，重试那一行只在需要时出现。
 */
data class MessageMenuState(
    val text: String = "",
    val saved: Boolean = false,
    val expanded: Boolean = false,
    val translating: Boolean = false,
    val retryable: Boolean = false,
)

/**
 * 六行的生成规则收在这一处：朗读与删除常在，翻译与收藏按状态改词，
 * 翻译还没回来或没有正文时禁用，重试只在需要时插在删除前面。
 */
fun messageMenu(state: MessageMenuState): List<MessageAction> = buildList {
    add(MessageAction(MessageCommand.Speak, "朗读"))
    add(
        MessageAction(
            command = MessageCommand.Translate,
            label = if (state.expanded) "收起译文" else "翻译",
            enabled = state.text.isNotBlank() && !state.translating,
        ),
    )
    add(MessageAction(MessageCommand.Save, if (state.saved) "取消收藏" else "收藏"))
    add(MessageAction(MessageCommand.Copy, "复制", enabled = state.text.isNotBlank()))
    if (state.retryable) add(MessageAction(MessageCommand.Retry, "重试"))
    add(MessageAction(MessageCommand.Delete, "删除"))
}

/**
 * 重试落在同一轮里用户那句上：有评测对象的是用户说的话，助手那句没有发音与语法可评。
 * 只认紧挨在助手那句前面的一位，隔了别的轮就不算同一轮：那一轮被整条删掉时
 * 更早的用户句不是这句的提问者，重试过去会评错人。
 */
internal fun retryTargetOf(turns: List<Turn>, echoTurnId: String): String? {
    val index = turns.indexOfLast { it.id == echoTurnId }
    if (index <= 0) return null
    val user = turns[index - 1].takeIf { it.speaker == Speaker.User } ?: return null
    val needs = user.analysis == AnalysisStatus.Failed || user.analysis == AnalysisStatus.None
    return user.id.takeIf { needs }
}

/** 复制只取正文：译文是正文下面那一行辅助，贴出去要的是能直接用的话。 */
internal fun copiedText(turn: Turn): String = turn.text

object MessageMenuSpec {
    /** 二百四十宽：比气泡的二百五十八窄一点，压在气泡旁边不会横向顶到屏幕边。 */
    val Width = 240.dp

    /** 菜单与气泡之间留的一点空。 */
    val Gap = 6.dp
}

/**
 * 长按气泡弹出来的消息菜单。行与下拉选项同构（四十四高、八圆角、按压换底色），
 * 外壳与下拉共用 [ParleyMenu] 那一套纸白底、十四圆角与一层暖影。
 * 位置由 [NearBubble] 算，落在气泡下沿附近而不是盖满屏；点菜单外面收起。
 */
@Composable
fun MessageMenu(
    actions: List<MessageAction>,
    onPick: (MessageCommand) -> Unit,
    onDismiss: () -> Unit,
) {
    // 弹窗窗口的尺寸就是内容尺寸，阴影与气泡之间的间距都用内边距换，位置按同一块抵消。
    val inset = FloatingShadowInset
    val gap = with(LocalDensity.current) { (MessageMenuSpec.Gap - inset).roundToPx() }
    Popup(
        popupPositionProvider = remember(gap) { NearBubble(gap) },
        onDismissRequest = onDismiss,
        properties = PopupProperties(focusable = true),
    ) {
        Box(Modifier.padding(inset)) {
            ParleyMenu(
                items = actions.map { action ->
                    MenuItem(label = action.label, enabled = action.enabled) { onPick(action.command) }
                },
                width = MessageMenuSpec.Width,
            )
        }
    }
}

/** 默认贴气泡下沿，下面摆不下就翻到上沿，横向与气泡左边对齐后夹进窗口。 */
private class NearBubble(private val gap: Int) : PopupPositionProvider {
    override fun calculatePosition(
        anchorBounds: IntRect,
        windowSize: IntSize,
        layoutDirection: LayoutDirection,
        popupContentSize: IntSize,
    ): IntOffset {
        val x = anchorBounds.left.coerceIn(0, (windowSize.width - popupContentSize.width).coerceAtLeast(0))
        val below = anchorBounds.bottom + gap
        val y = if (below + popupContentSize.height > windowSize.height) {
            (anchorBounds.top - gap - popupContentSize.height).coerceAtLeast(0)
        } else {
            below
        }
        return IntOffset(x, y)
    }
}
