package com.parley.speaker.ui.review

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.parley.speaker.domain.Register
import com.parley.speaker.domain.Turn
import com.parley.speaker.ui.component.FloatingPanel
import com.parley.speaker.ui.component.ParleyIconButton
import com.parley.speaker.ui.component.Pressable
import com.parley.speaker.ui.component.SectionLabel
import com.parley.speaker.ui.component.SheetHandle
import com.parley.speaker.ui.component.SoftCard
import com.parley.speaker.ui.component.WordChip
import com.parley.speaker.ui.icon.FluentIcons
import com.parley.speaker.ui.theme.Palette
import com.parley.speaker.ui.theme.Radius
import com.parley.speaker.ui.theme.Spacing
import com.parley.speaker.ui.theme.Type

/** 抽屉外面那层遮罩：十八百分比的黑，压暗纸面但不吞掉下面的字。 */
private val Scrim = Color(0x2E141413)

@Composable
fun ReviewSheet(
    turn: Turn,
    onDismiss: () -> Unit,
    onSaveToVocab: () -> Unit,
    onContinue: () -> Unit,
    onReplay: () -> Unit = {},
    onSpeak: (String) -> Unit = {},
) {
    val review = turn.review ?: return
    var register by remember(turn.id) { mutableStateOf(Register.Idiomatic) }
    val rewrite = review.rewrites.firstOrNull { it.register == register } ?: review.rewrites.firstOrNull()

    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
        // 遮罩只接住划过纸面外的点击，底色保持不变，也没有按压反馈。
        Pressable(
            modifier = Modifier.fillMaxSize(),
            resting = Scrim,
            pressing = Scrim,
            onClick = onDismiss,
        ) {}
        FloatingPanel(
            shape = RoundedCornerShape(topStart = Radius.sheet, topEnd = Radius.sheet),
            modifier = Modifier
                .fillMaxWidth()
                .height(602.dp),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = Spacing.screen),
            ) {
                Spacer(Modifier.height(10.dp))
                Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) { SheetHandle() }
                Spacer(Modifier.height(14.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("本轮评测", style = Type.display, modifier = Modifier.weight(1f))
                    ParleyIconButton(
                        icon = FluentIcons.Dismiss,
                        contentDescription = "关闭",
                        onClick = onDismiss,
                    )
                }
                Spacer(Modifier.height(14.dp))
                SoftCard(modifier = Modifier.fillMaxWidth()) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        PlayDot(onReplay)
                        Spacer(Modifier.width(Spacing.block))
                        Text(
                            text = review.original,
                            style = Type.prose,
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
                Spacer(Modifier.height(20.dp))
                ScoreRow(
                    pronunciation = review.pronunciation?.let { it.score to it.note },
                    grammar = review.grammar.score to review.grammar.note,
                )

                review.pronunciation?.let { pronunciation ->
                    val issue = pronunciation.phonemes.firstOrNull()
                    if (issue != null) {
                        Spacer(Modifier.height(22.dp))
                        SectionLabel(FluentIcons.Mic, "音素建议")
                        Spacer(Modifier.height(10.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            WordChip("${issue.word}  ${issue.phonetic}")
                            Spacer(Modifier.width(Spacing.block))
                            Text(issue.note, style = Type.caption, modifier = Modifier.weight(1f))
                        }
                    }
                }

                if (rewrite != null) {
                    Spacer(Modifier.height(22.dp))
                    SectionLabel(FluentIcons.Bulb, "母语者表达")
                    Spacer(Modifier.height(10.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Register.entries.forEach { entry ->
                            RegisterTab(
                                label = entry.label,
                                selected = entry == register,
                                onClick = { register = entry },
                            )
                        }
                    }
                    Spacer(Modifier.height(12.dp))
                    SoftCard(modifier = Modifier.fillMaxWidth()) {
                        Column {
                            Text(rewrite.text, style = Type.prose)
                            Spacer(Modifier.height(4.dp))
                            Text(rewrite.gloss, style = Type.caption)
                            Spacer(Modifier.height(10.dp))
                            Row(horizontalArrangement = Arrangement.spacedBy(Spacing.block)) {
                                // 朗读键压在 tint 的改写卡上，底色的台阶在这里落到 hair，按下才看得出来。
                                Pressable(
                                    modifier = Modifier.size(18.dp),
                                    pressing = Palette.hair,
                                    onClick = { onSpeak(rewrite.text) },
                                ) {
                                    Icon(
                                        FluentIcons.Speaker,
                                        contentDescription = "朗读改写",
                                        modifier = Modifier.size(18.dp),
                                        tint = Palette.ink3,
                                    )
                                }
                            }
                        }
                    }
                }

                Spacer(Modifier.height(22.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(Spacing.block),
                ) {
                    OutlinedAction(
                        label = "继续对话",
                        onClick = onContinue,
                    )
                    OutlinedAction(
                        label = "收进生词本",
                        primary = true,
                        onClick = onSaveToVocab,
                        modifier = Modifier.weight(1f),
                    )
                }
                Spacer(Modifier.height(24.dp))
            }
        }
    }
}

@Composable
private fun PlayDot(onClick: () -> Unit) {
    Pressable(
        modifier = Modifier.size(32.dp),
        shape = CircleShape,
        border = BorderStroke(1.dp, Palette.hairStrong),
        onClick = onClick,
    ) {
        Icon(
            FluentIcons.Play,
            contentDescription = "复听原文",
            modifier = Modifier.size(12.dp),
            tint = Palette.ink,
        )
    }
}

@Composable
private fun ScoreRow(pronunciation: Pair<Int, String>?, grammar: Pair<Int, String>) {
    Row(modifier = Modifier.fillMaxWidth()) {
        if (pronunciation != null) {
            ScoreColumn(
                label = "发音",
                score = pronunciation.first,
                note = pronunciation.second,
                color = Palette.clayDeep,
                modifier = Modifier.weight(1f),
            )
            Box(
                modifier = Modifier
                    .width(1.dp)
                    .height(54.dp)
                    .background(Palette.hair),
            )
        }
        ScoreColumn(
            label = "语法",
            score = grammar.first,
            note = grammar.second,
            color = Palette.olive,
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun ScoreColumn(
    label: String,
    score: Int,
    note: String,
    color: Color,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier) {
        Text(label, style = Type.caption)
        Spacer(Modifier.height(2.dp))
        Row(verticalAlignment = Alignment.Bottom) {
            Text("$score", style = Type.prose.copy(fontSize = 27.sp, lineHeight = 30.sp, color = color))
            Spacer(Modifier.width(4.dp))
            Text("分", style = Type.caption, modifier = Modifier.padding(bottom = 5.dp))
        }
        Spacer(Modifier.height(4.dp))
        Text(note, style = Type.caption)
    }
}

@Composable
private fun RegisterTab(label: String, selected: Boolean, onClick: () -> Unit) {
    Pressable(
        shape = RoundedCornerShape(Radius.pill),
        resting = if (selected) Palette.clay else Palette.card,
        border = if (selected) null else BorderStroke(1.dp, Palette.hair),
        onClick = onClick,
    ) {
        Text(
            text = label,
            style = Type.caption.copy(color = if (selected) Color.White else Palette.ink3),
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 7.dp),
        )
    }
}

@Composable
private fun OutlinedAction(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    primary: Boolean = false,
) {
    Pressable(
        modifier = modifier,
        shape = RoundedCornerShape(Radius.pill),
        resting = if (primary) Palette.clay else Palette.card,
        border = if (primary) null else BorderStroke(1.dp, Palette.hair),
        onClick = onClick,
    ) {
        Row(
            modifier = Modifier
                .then(if (primary) Modifier.fillMaxWidth() else Modifier)
                .padding(horizontal = 18.dp, vertical = 13.dp),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = label,
                style = Type.uiStrong.copy(color = if (primary) Color.White else Palette.ink2),
            )
        }
    }
}
