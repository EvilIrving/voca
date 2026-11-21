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
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.unit.dp
import com.parley.speaker.domain.VocabCard
import com.parley.speaker.domain.VocabGroup
import com.parley.speaker.ui.component.FlagChip
import com.parley.speaker.ui.component.ParleyIconButton
import com.parley.speaker.ui.component.highlighted
import com.parley.speaker.ui.icon.FluentIcons
import com.parley.speaker.ui.theme.Palette
import com.parley.speaker.ui.theme.Radius
import com.parley.speaker.ui.theme.Spacing
import com.parley.speaker.ui.theme.Type

@Composable
fun VocabScreen(
    groups: List<VocabGroup>,
    onDismiss: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Palette.paper)
            .windowInsetsPadding(WindowInsets.statusBars),
    ) {
        Spacer(Modifier.height(16.dp))
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = Spacing.screen)
                .height(44.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("生词库", style = Type.display, modifier = Modifier.weight(1f))
            ParleyIconButton(FluentIcons.Dismiss, "关闭", onDismiss)
        }
        Spacer(Modifier.height(12.dp))
        Surface(
            color = Palette.card,
            shape = RoundedCornerShape(Radius.control),
            border = BorderStroke(1.dp, Palette.hair),
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = Spacing.screen)
                .height(44.dp),
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
                Text("搜索词或音标", style = Type.ui.copy(color = Palette.ink4))
            }
        }
        Spacer(Modifier.height(14.dp))
        Row(
            modifier = Modifier.padding(horizontal = Spacing.screen),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            FlagChip("今天 24", selected = true, onClick = {})
            FlagChip("待复习 9", selected = false, onClick = {})
            FlagChip("全部 15", selected = false, onClick = {})
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
            groups.forEach { group ->
                item(key = "label-${group.label}") {
                    Text(group.label, style = Type.caption.copy(color = Palette.ink3))
                }
                items(group.cards, key = { it.id }) { card ->
                    VocabRow(card)
                }
            }
        }
    }
}

@Composable
private fun VocabRow(card: VocabCard) {
    Surface(
        color = Palette.card,
        shape = RoundedCornerShape(Radius.vocabCard),
        border = BorderStroke(1.dp, Palette.hair),
        modifier = Modifier
            .fillMaxWidth()
            .clickable {},
    ) {
        Column(Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(card.word, style = Type.title, modifier = Modifier.weight(1f))
                Icon(
                    FluentIcons.ChevRight,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                    tint = Palette.ink4,
                )
            }
            Spacer(Modifier.height(2.dp))
            Text(card.phonetic, style = Type.mono)
            Spacer(Modifier.height(6.dp))
            Text(card.meaning, style = Type.ui)
            Spacer(Modifier.height(8.dp))
            Text(
                text = highlighted(card.example, card.highlight),
                style = Type.prose.copy(fontStyle = FontStyle.Italic),
            )
            Spacer(Modifier.height(8.dp))
            Box(
                modifier = Modifier
                    .background(Palette.oliveTint, RoundedCornerShape(Radius.chip))
                    .padding(horizontal = 8.dp, vertical = 3.dp),
            ) {
                Text("复现 ${card.repeats} 次", style = Type.caption.copy(color = Palette.olive))
            }
        }
    }
}
