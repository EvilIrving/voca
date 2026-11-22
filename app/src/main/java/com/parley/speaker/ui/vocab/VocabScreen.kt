package com.parley.speaker.ui.vocab

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.parley.speaker.domain.VocabCard
import com.parley.speaker.domain.VocabGroup
import com.parley.speaker.ui.component.FlagChip
import com.parley.speaker.ui.component.highlighted
import com.parley.speaker.ui.icon.FluentIcons
import com.parley.speaker.ui.theme.Palette
import com.parley.speaker.ui.theme.Radius
import com.parley.speaker.ui.theme.Spacing
import com.parley.speaker.ui.theme.Type

private const val TodayLabel = "今天"

@Composable
fun VocabScreen(
    groups: List<VocabGroup>,
    onDismiss: () -> Unit,
    onSpeak: (VocabCard) -> Unit = {},
) {
    var scope by remember { mutableStateOf(VocabScope.All) }
    var query by remember { mutableStateOf("") }
    val dated = groups.datedCards()
    val matching = dated.filter { it.card.matches(query) }
    val slots = when (scope) {
        VocabScope.All -> matching.sectioned()
        VocabScope.Collocation -> matching.filter { it.card.isCollocation }.sectioned()
        VocabScope.Word -> matching.filterNot { it.card.isCollocation }.sectioned()
    }
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Palette.paper)
            .windowInsetsPadding(WindowInsets.statusBars),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = Spacing.screen)
                .height(44.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            BackButton(onDismiss)
            Spacer(Modifier.width(4.dp))
            Text("生词库", style = Type.display)
        }
        Spacer(Modifier.height(12.dp))
        Surface(
            color = Palette.card,
            shape = RoundedCornerShape(Radius.control),
            border = BorderStroke(1.dp, Palette.hair),
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = Spacing.screen)
                .height(40.dp),
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    FluentIcons.Search,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                    tint = Palette.ink4,
                )
                Spacer(Modifier.width(Spacing.iconText))
                Box(Modifier.weight(1f)) {
                    if (query.isEmpty()) {
                        Text("搜索词或音标", style = Type.ui.copy(color = Palette.ink4))
                    }
                    BasicTextField(
                        value = query,
                        onValueChange = { query = it },
                        singleLine = true,
                        textStyle = Type.ui.copy(color = Palette.ink),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        }
        Spacer(Modifier.height(14.dp))
        Row(
            modifier = Modifier.padding(horizontal = Spacing.screen),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            FlagChip(
                "全部 ${dated.size}",
                selected = scope == VocabScope.All,
                onClick = { scope = VocabScope.All },
            )
            FlagChip(
                "地道搭配 ${dated.count { it.card.isCollocation }}",
                selected = scope == VocabScope.Collocation,
                onClick = { scope = VocabScope.Collocation },
            )
            FlagChip(
                "生词 ${dated.count { it.card.isCollocation.not() }}",
                selected = scope == VocabScope.Word,
                onClick = { scope = VocabScope.Word },
            )
        }
        LazyColumn(
            modifier = Modifier.weight(1f),
            contentPadding = PaddingValues(
                start = Spacing.screen,
                end = Spacing.screen,
                top = Spacing.screen,
                bottom = Spacing.screen,
            ),
            verticalArrangement = Arrangement.spacedBy(Spacing.block),
        ) {
            if (slots.isEmpty()) {
                item(key = "empty") {
                    Text(
                        text = if (query.isBlank()) "还没有生词，对话里出现的地道搭配会自己进来" else "没有匹配的词",
                        style = Type.caption.copy(color = Palette.ink4),
                        modifier = Modifier.padding(top = Spacing.screen),
                    )
                }
            }
            items(slots, key = { it.key }) { slot ->
                when (slot) {
                    is VocabSlot.Header -> Text(slot.label, style = Type.caption.copy(color = Palette.ink3))
                    is VocabSlot.Card -> VocabRow(slot.dated, onSpeak)
                }
            }
        }
    }
}

private enum class VocabScope { All, Collocation, Word }

/** 词条连同它所在分组的日期位次，位次越小越新。 */
private data class DatedCard(
    val card: VocabCard,
    val group: String,
    val age: Int,
)

private sealed interface VocabSlot {
    val key: String

    data class Header(val label: String) : VocabSlot {
        override val key: String get() = "label-$label"
    }

    data class Card(val dated: DatedCard) : VocabSlot {
        override val key: String get() = "card-${dated.card.id}"
    }
}

// 词条本身不带时间，最近出现日期只能从分组名读：今天最新，其余组按原有先后算更旧。
private fun List<VocabGroup>.datedCards(): List<DatedCard> =
    sortedBy { if (it.label == TodayLabel) 0 else 1 }.flatMapIndexed { index, group ->
        val age = if (group.label == TodayLabel) 0 else index + 1
        group.cards.map { DatedCard(it, group.label, age) }
    }

// 待复习只取今天没再出现过的词，复现次数少的先复习，同次数按出现日期旧的在前。
private fun List<DatedCard>.dueOrder(): List<DatedCard> =
    filter { it.age > 0 && it.card.repeats > 0 }
        .sortedWith(compareBy({ it.card.repeats }, { -it.age }, { it.card.word }))

// 列表按复习节奏分三段：今天新进来的、该回看的、以及更早收下的。带空格的词形算地道搭配。
private val VocabCard.isCollocation: Boolean get() = word.trim().contains(' ')

private fun List<DatedCard>.sectioned(): List<VocabSlot> {
    val today = filter { it.age == 0 }
    val due = dueOrder()
    val resting = filter { it.age > 0 && it.card.repeats <= 0 }
    return buildList {
        if (today.isNotEmpty()) {
            add(VocabSlot.Header(TodayLabel))
            today.forEach { add(VocabSlot.Card(it)) }
        }
        if (due.isNotEmpty()) {
            add(VocabSlot.Header("待复习"))
            due.forEach { add(VocabSlot.Card(it)) }
        }
        if (resting.isNotEmpty()) {
            add(VocabSlot.Header("更早"))
            resting.forEach { add(VocabSlot.Card(it)) }
        }
    }
}

private fun VocabCard.matches(query: String): Boolean =
    query.isBlank() || word.contains(query, ignoreCase = true) || meaning.contains(query)

// FluentIcons 里没有返回图元，这一支按 Fluent 线宽自己画。
private val BackArrow: ImageVector = ImageVector.Builder(
    name = "back",
    defaultWidth = 20.dp,
    defaultHeight = 20.dp,
    viewportWidth = 20f,
    viewportHeight = 20f,
).apply {
    addPath(
        pathData = PathParser().parsePathString("M9.2 4.6 3.6 10.2 9.2 15.8 M3.9 10.2 H16.4").toNodes(),
        stroke = SolidColor(Color.Black),
        strokeLineWidth = 1.6f,
        strokeLineCap = StrokeCap.Round,
        strokeLineJoin = StrokeJoin.Round,
    )
}.build()

@Composable
private fun BackButton(onDismiss: () -> Unit) {
    Box(
        modifier = Modifier
            .size(36.dp)
            .clip(RoundedCornerShape(Radius.pill))
            .clickable(onClick = onDismiss),
        contentAlignment = Alignment.Center,
    ) {
        Icon(BackArrow, "返回对话", modifier = Modifier.size(20.dp), tint = Palette.ink2)
    }
}

@Composable
private fun VocabRow(dated: DatedCard, onSpeak: (VocabCard) -> Unit) {
    val card = dated.card
    Surface(
        color = Palette.card,
        shape = RoundedCornerShape(Radius.vocabCard),
        border = BorderStroke(1.dp, Palette.hair),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = card.word,
                    style = Type.prose.copy(fontSize = 19.sp, lineHeight = 25.sp, fontWeight = FontWeight.Medium),
                    modifier = Modifier.weight(1f),
                )
                SpeakButton { onSpeak(card) }
            }
            Spacer(Modifier.height(2.dp))
            Text(card.phonetic, style = Type.mono.copy(fontSize = 10.5.sp, lineHeight = 14.sp))
            Spacer(Modifier.height(6.dp))
            Text(card.meaning, style = Type.ui.copy(fontSize = 11.5.sp, lineHeight = 16.sp))
            Spacer(Modifier.height(8.dp))
            Text(
                text = highlighted(card.example, card.highlight),
                style = Type.prose.copy(fontSize = 13.5.sp, lineHeight = 20.sp, fontStyle = FontStyle.Italic),
            )
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Tag("复现 ${card.repeats} 次", Palette.oliveTint, Palette.olive)
                Tag(dated.group, Palette.clayTint, Palette.clayDeep)
            }
        }
    }
}

@Composable
private fun SpeakButton(onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(32.dp)
            .clip(RoundedCornerShape(Radius.chip))
            .background(Palette.tint)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(FluentIcons.Speaker, "朗读", modifier = Modifier.size(16.dp), tint = Palette.ink2)
    }
}

@Composable
private fun Tag(text: String, container: Color, content: Color) {
    Box(
        modifier = Modifier
            .background(container, RoundedCornerShape(Radius.chip))
            .padding(horizontal = 8.dp, vertical = 3.dp),
    ) {
        Text(text, style = Type.caption.copy(color = content))
    }
}
