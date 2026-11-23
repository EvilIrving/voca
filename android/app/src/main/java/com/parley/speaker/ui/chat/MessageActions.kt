package com.parley.speaker.ui.chat

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import com.parley.speaker.domain.AnalysisStatus
import com.parley.speaker.domain.Speaker
import com.parley.speaker.domain.Turn
import com.parley.speaker.ui.component.Pressable
import com.parley.speaker.ui.icon.FluentIcons
import com.parley.speaker.ui.theme.Palette

/** 动作条的六枚，顺序就是画板 07 屏从左到右的顺序。 */
enum class MessageCommand { Speak, Translate, Save, Copy, Retry, Delete }

/** 一枚动作：图形、无障碍读出来的动作词，以及能不能点。 */
data class MessageAction(
    val command: MessageCommand,
    val icon: ImageVector,
    val label: String,
    val enabled: Boolean = true,
)

/**
 * 生成动作条要读到的那点状态，全部从这一轮自己身上取。
 * [retryable] 由调用方按同一轮里用户那句的分析状态算出，重新分析只在还有东西可评时亮起。
 */
data class MessageActionsState(
    val text: String = "",
    val saved: Boolean = false,
    val expanded: Boolean = false,
    val translating: Boolean = false,
    val retryable: Boolean = false,
)

/**
 * 六枚常驻：重写与收起只在译文展开时换词，翻译还没回来或没有正文时压到四成，
 * 重新分析跟着 [MessageActionsState.retryable] 亮灭。顺序与画板一致，不随状态增减。
 */
fun messageActions(state: MessageActionsState): List<MessageAction> = listOf(
    MessageAction(MessageCommand.Speak, FluentIcons.Speaker, "朗读"),
    MessageAction(
        command = MessageCommand.Translate,
        icon = FluentIcons.Translate,
        label = if (state.expanded) "收起译文" else "翻译",
        enabled = state.text.isNotBlank() && !state.translating,
    ),
    MessageAction(
        command = MessageCommand.Save,
        icon = FluentIcons.Bookmark,
        label = if (state.saved) "取消收藏" else "收进生词本",
    ),
    MessageAction(
        command = MessageCommand.Copy,
        icon = FluentIcons.Copy,
        label = "复制原文",
        enabled = state.text.isNotBlank(),
    ),
    MessageAction(
        command = MessageCommand.Retry,
        icon = FluentIcons.Sync,
        label = "重新分析",
        enabled = state.retryable,
    ),
    MessageAction(MessageCommand.Delete, FluentIcons.Delete, "删除"),
)

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

object MessageActionsSpec {
    /** 一枚动作的方框：画板给二十八，圆角六，图形十五落在正中。 */
    val Button = 28.dp
    val Icon = 15.dp
    val Gap = 4.dp
    val Radius = 6.dp

    /** 动作条与气泡之间那一道缝，取自画板 msg AI 的块间距。 */
    val BubbleGap = 4.dp
}

/**
 * 智能体气泡下面那条常驻动作条：六枚纯图标平铺，不带底板也不带文字。
 * 按压只换底色，禁用按归一的规矩压到四成，删除用浅墨与其余区分。
 */
@Composable
fun MessageActionBar(
    actions: List<MessageAction>,
    onPick: (MessageCommand) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(MessageActionsSpec.Gap),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        actions.forEach { action ->
            Pressable(
                modifier = Modifier.size(MessageActionsSpec.Button),
                shape = RoundedCornerShape(MessageActionsSpec.Radius),
                enabled = action.enabled,
                contentDescription = action.label,
                onClick = { onPick(action.command) },
            ) {
                Icon(
                    imageVector = action.icon,
                    contentDescription = null,
                    modifier = Modifier.size(MessageActionsSpec.Icon),
                    tint = if (action.command == MessageCommand.Delete) Palette.ink4 else Palette.ink3,
                )
            }
        }
    }
}
