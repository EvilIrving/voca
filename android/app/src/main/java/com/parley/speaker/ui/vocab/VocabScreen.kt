package com.parley.speaker.ui.vocab

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.parley.speaker.data.startOfLocalDay
import com.parley.speaker.domain.VocabCard
import com.parley.speaker.domain.VocabFilter
import com.parley.speaker.domain.VocabTagKind
import com.parley.speaker.domain.tags
import com.parley.speaker.domain.vocabPage
import com.parley.speaker.ui.component.FlagChip
import com.parley.speaker.ui.component.Pressable
import com.parley.speaker.ui.component.highlighted
import com.parley.speaker.ui.icon.FluentIcons
import com.parley.speaker.ui.session.BackButton
import com.parley.speaker.ui.theme.Palette
import com.parley.speaker.ui.theme.Radius
import com.parley.speaker.ui.theme.Spacing
import com.parley.speaker.ui.theme.Type

@Composable
fun VocabScreen(
    cards: List<VocabCard>,
    onDismiss: () -> Unit,
    onSpeak: (VocabCard) -> Unit = {},
) {
    var filter by remember { mutableStateOf(VocabFilter.All) }
    var query by remember { mutableStateOf("") }
    val page = vocabPage(
        cards = cards,
        todayStart = startOfLocalDay(System.currentTimeMillis()),
        filter = filter,
        query = query,
    )
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
            border = BorderStroke(1.dp, Palette.hairSoft),
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
            VocabFilter.entries.forEach { option ->
                FlagChip(
                    "${option.label} ${page.counts[option]}",
                    selected = filter == option,
                    onClick = { filter = option },
                )
            }
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
            if (page.sections.isEmpty()) {
                item(key = "empty") {
                    Text(
                        text = emptyHint(query, filter),
                        style = Type.caption.copy(color = Palette.ink4),
                        modifier = Modifier.padding(top = Spacing.screen),
                    )
                }
            }
            page.sections.forEach { section ->
                item(key = "label-${section.bucket.name}") {
                    Text(section.bucket.label, style = Type.caption.copy(color = Palette.ink3))
                }
                items(section.cards, key = { "card-${it.id}" }) { card ->
                    VocabRow(card, onSpeak)
                }
            }
        }
    }
}

/** 空结果的说明：带搜索时把当前档位名一起说出来，不然筛选在「今天」而命中在「待复习」时说不清为什么空。 */
internal fun emptyHint(query: String, filter: VocabFilter): String = when {
    query.isNotBlank() -> "${filter.label}里没有匹配的词"
    filter == VocabFilter.Today -> "今天还没有新词"
    filter == VocabFilter.Due -> "没有待复习的词"
    else -> "还没有生词，对话里出现的地道搭配会自己进来"
}

@Composable
private fun VocabRow(card: VocabCard, onSpeak: (VocabCard) -> Unit) {
    Surface(
        color = Palette.card,
        shape = RoundedCornerShape(Radius.vocabCard),
        border = BorderStroke(1.dp, Palette.hairSoft),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(horizontal = 14.dp, vertical = 11.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Row(Modifier.weight(1f)) {
                    Text(
                        text = card.word,
                        style = Type.prose.copy(fontSize = 19.sp, lineHeight = 25.sp, fontWeight = FontWeight.Medium),
                        modifier = Modifier.alignByBaseline(),
                    )
                    Spacer(Modifier.width(7.dp))
                    Text(
                        text = card.phonetic,
                        style = Type.mono.copy(fontSize = 10.5.sp, lineHeight = 14.sp),
                        modifier = Modifier.alignByBaseline(),
                    )
                }
                SpeakButton { onSpeak(card) }
            }
            Spacer(Modifier.height(3.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = card.meaning,
                    style = Type.ui.copy(fontSize = 11.5.sp, lineHeight = 16.sp),
                    modifier = Modifier.weight(1f),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    card.tags().forEach { tag ->
                        when (tag.kind) {
                            VocabTagKind.Type -> Tag(tag.text, Palette.clayTint, Palette.clayDeep)
                            VocabTagKind.Repeats -> Tag(tag.text, Palette.oliveTint, Palette.olive)
                        }
                    }
                }
            }
            Spacer(Modifier.height(6.dp))
            Text(
                text = highlighted(card.example, card.highlight),
                style = Type.prose.copy(fontSize = 13.5.sp, lineHeight = 20.sp, fontStyle = FontStyle.Italic),
            )
        }
    }
}

@Composable
private fun SpeakButton(onClick: () -> Unit) {
    Pressable(
        modifier = Modifier.size(32.dp),
        shape = RoundedCornerShape(Radius.chip),
        onClick = onClick,
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
