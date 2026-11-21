package com.parley.speaker.ui.settings

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.parley.speaker.domain.LinkStatus
import com.parley.speaker.domain.ProviderLine
import com.parley.speaker.ui.component.ParleyIconButton
import com.parley.speaker.ui.icon.FluentIcons
import com.parley.speaker.ui.theme.Palette
import com.parley.speaker.ui.theme.Radius
import com.parley.speaker.ui.theme.Spacing
import com.parley.speaker.ui.theme.Type

@Composable
fun SettingsScreen(
    realtime: List<ProviderLine>,
    analysis: List<ProviderLine>,
    prompt: String,
    lastChecked: String?,
    onDismiss: () -> Unit,
    onCheck: () -> Unit,
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
            Text("配置中心", style = Type.display, modifier = Modifier.weight(1f))
            ParleyIconButton(FluentIcons.Dismiss, "关闭", onDismiss)
        }
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = Spacing.screen),
        ) {
            Spacer(Modifier.height(8.dp))
            GroupLabel("实时对话")
            realtime.forEach { line -> ProviderRow(line) }
            Spacer(Modifier.height(18.dp))
            GroupLabel("识别与分析")
            analysis.forEach { line -> ProviderRow(line) }
            Spacer(Modifier.height(18.dp))
            GroupLabel("角色提示词")
            Spacer(Modifier.height(8.dp))
            Surface(
                color = Palette.card,
                shape = RoundedCornerShape(Radius.card),
                border = BorderStroke(1.dp, Palette.hair),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    text = prompt,
                    style = Type.ui.copy(color = Palette.ink2),
                    modifier = Modifier.padding(14.dp),
                )
            }
            Spacer(Modifier.height(22.dp))
            Surface(
                color = Palette.clay,
                shape = RoundedCornerShape(Radius.pill),
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onCheck() },
            ) {
                Row(
                    modifier = Modifier.padding(vertical = 13.dp),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        FluentIcons.Plug,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                        tint = Color.White,
                    )
                    Spacer(Modifier.width(Spacing.iconText))
                    Text("连接自检", style = Type.uiStrong.copy(color = Color.White))
                }
            }
            if (lastChecked != null) {
                Spacer(Modifier.height(8.dp))
                Text(
                    text = lastChecked,
                    style = Type.caption.copy(color = Palette.ink4),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun GroupLabel(text: String) {
    Text(
        text = text,
        style = Type.caption.copy(color = Palette.ink3),
        modifier = Modifier.padding(bottom = 8.dp),
    )
}

@Composable
private fun ProviderRow(line: ProviderLine) {
    Surface(
        color = Palette.card,
        shape = RoundedCornerShape(Radius.card),
        border = BorderStroke(1.dp, Palette.hair),
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 8.dp)
            .clickable {},
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(line.name, style = Type.uiStrong)
                Spacer(Modifier.height(2.dp))
                Text(line.detail, style = Type.mono.copy(fontSize = Type.mono.fontSize))
            }
            Spacer(Modifier.width(Spacing.block))
            Text(
                text = when (line.status) {
                    LinkStatus.Connected -> "已连通"
                    LinkStatus.Configured -> "已配置"
                    LinkStatus.Unconfigured -> "未配置"
                    LinkStatus.Failed -> "连接失败"
                },
                style = Type.caption.copy(
                    color = when (line.status) {
                        LinkStatus.Connected -> Palette.olive
                        LinkStatus.Configured -> Palette.ink2
                        LinkStatus.Unconfigured -> Palette.ink4
                        LinkStatus.Failed -> Palette.danger
                    },
                ),
            )
        }
    }
}
