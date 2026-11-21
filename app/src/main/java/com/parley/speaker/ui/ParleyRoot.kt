package com.parley.speaker.ui

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.parley.speaker.BuildConfig
import com.parley.speaker.data.SeedContent
import com.parley.speaker.domain.Turn
import com.parley.speaker.ui.chat.ChatScreen
import com.parley.speaker.ui.chat.ChatViewModel
import com.parley.speaker.ui.record.RecordingOverlay
import com.parley.speaker.ui.review.ReviewSheet
import com.parley.speaker.ui.settings.SettingsScreen
import com.parley.speaker.ui.theme.Palette
import com.parley.speaker.ui.theme.Radius
import com.parley.speaker.ui.theme.Spacing
import com.parley.speaker.ui.theme.Type
import com.parley.speaker.ui.vocab.VocabScreen

private enum class Panel { None, Vocab, Settings }

@Composable
fun ParleyRoot(vm: ChatViewModel = viewModel()) {
    val context = LocalContext.current
    val state by vm.state.collectAsStateWithLifecycle()
    val levels by vm.levels.collectAsStateWithLifecycle()
    val elapsedMs by vm.elapsedMs.collectAsStateWithLifecycle()

    val vocab by vm.vocab.collectAsStateWithLifecycle()
    var panel by remember { mutableStateOf(Panel.None) }
    var scenarioOpen by remember { mutableStateOf(false) }
    var reviewTurn by remember { mutableStateOf<Turn?>(null) }
    var micDenied by remember { mutableStateOf(false) }

    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) vm.onRecordPressStart() else micDenied = true
    }

    BackHandler(enabled = panel != Panel.None || reviewTurn != null || scenarioOpen) {
        when {
            reviewTurn != null -> reviewTurn = null
            scenarioOpen -> scenarioOpen = false
            else -> panel = Panel.None
        }
    }

    Box(modifier = Modifier.fillMaxSize().background(Palette.paper)) {
        ChatScreen(
            state = state,
            onScenarioClick = { scenarioOpen = !scenarioOpen },
            onVocabClick = { panel = Panel.Vocab },
            onSettingsClick = { panel = Panel.Settings },
            onReviewClick = { reviewTurn = it },
            onRecordPressStart = {
                val granted = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
                    PackageManager.PERMISSION_GRANTED
                if (granted) vm.onRecordPressStart() else permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
            },
            onRecordArmed = vm::onRecordArmed,
            onRecordPressEnd = vm::onRecordPressEnd,
            onHangup = vm::hangup,
            onRetry = vm::reconnect,
        )

        AnimatedVisibility(visible = scenarioOpen, enter = fadeIn(), exit = fadeOut()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color(0x0D141413))
                    .clickable { scenarioOpen = false },
            ) {
                Column(modifier = Modifier.padding(start = Spacing.screen, top = 72.dp)) {
                    Surface(
                        color = Palette.card,
                        shape = RoundedCornerShape(Radius.card),
                        border = BorderStroke(1.dp, Palette.hair),
                        shadowElevation = 8.dp,
                    ) {
                        Column(modifier = Modifier.padding(vertical = 6.dp)) {
                            SeedContent.Scenarios.forEach { scenario ->
                                Text(
                                    text = scenario,
                                    style = if (scenario == state.scenario) Type.uiStrong else Type.ui,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable {
                                            vm.setScenario(scenario)
                                            scenarioOpen = false
                                        }
                                        .padding(horizontal = 16.dp, vertical = 11.dp),
                                )
                            }
                        }
                    }
                }
            }
        }

        AnimatedVisibility(
            visible = state.recording,
            enter = slideInVertically { it } + fadeIn(),
            exit = slideOutVertically { it } + fadeOut(),
        ) {
            RecordingOverlay(levels = levels, elapsedMs = elapsedMs, cancelArmed = state.cancelArmed)
        }

        AnimatedVisibility(
            visible = reviewTurn != null,
            enter = slideInVertically { it } + fadeIn(),
            exit = slideOutVertically { it } + fadeOut(),
        ) {
            reviewTurn?.let { turn ->
                ReviewSheet(
                    turn = turn,
                    onDismiss = { reviewTurn = null },
                    onSaveToVocab = {
                        reviewTurn?.let { vm.saveTurnVocab(it.id) }
                        reviewTurn = null
                    },
                    onContinue = { reviewTurn = null },
                )
            }
        }

        AnimatedVisibility(
            visible = panel == Panel.Vocab,
            enter = slideInVertically { it },
            exit = slideOutVertically { it },
        ) {
            VocabScreen(groups = vocab, onDismiss = { panel = Panel.None })
        }

        AnimatedVisibility(
            visible = panel == Panel.Settings,
            enter = slideInVertically { it },
            exit = slideOutVertically { it },
        ) {
            SettingsScreen(
                realtime = SeedContent.realtimeProviders(BuildConfig.DEV_GEMINI_API_KEY.isNotBlank()),
                analysis = SeedContent.analysisProviders(),
                prompt = SeedContent.DefaultPrompt,
                lastChecked = null,
                onDismiss = { panel = Panel.None },
                onCheck = {},
            )
        }

        state.notice?.let { notice ->
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
                Surface(
                    color = Palette.ink,
                    shape = RoundedCornerShape(Radius.chip),
                    modifier = Modifier
                        .padding(bottom = 100.dp)
                        .clickable { vm.dismissNotice() },
                ) {
                    Text(
                        notice,
                        style = Type.caption.copy(color = Color.White),
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 9.dp),
                    )
                }
            }
        }

        if (micDenied) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
                Surface(
                    color = Palette.ink,
                    shape = RoundedCornerShape(Radius.chip),
                    modifier = Modifier.padding(bottom = 100.dp),
                ) {
                    Text(
                        "需要麦克风权限才能练习口语",
                        style = Type.caption.copy(color = Color.White),
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 9.dp),
                    )
                }
            }
        }

        Spacer(Modifier.height(0.dp))
    }
}
