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
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.parley.speaker.data.SeedContent
import com.parley.speaker.domain.Turn
import com.parley.speaker.ui.chat.ChatScreen
import com.parley.speaker.ui.chat.ChatViewModel
import com.parley.speaker.ui.component.MenuItem
import com.parley.speaker.ui.component.ParleyMenu
import com.parley.speaker.ui.record.RecordingOverlay
import com.parley.speaker.ui.review.ReviewSheet
import com.parley.speaker.ui.session.SessionListScreen
import com.parley.speaker.ui.session.SessionSummary
import com.parley.speaker.ui.session.SessionSummarySheet
import com.parley.speaker.ui.session.sessionSummary
import com.parley.speaker.ui.session.SessionRecord as SessionEntry
import com.parley.speaker.ui.settings.SettingsScreen
import com.parley.speaker.ui.theme.Palette
import com.parley.speaker.ui.theme.Radius
import com.parley.speaker.ui.theme.Spacing
import com.parley.speaker.ui.theme.Type
import com.parley.speaker.ui.vocab.VocabScreen

private enum class Panel { None, Vocab, Settings, Summary, Sessions }

@Composable
fun ParleyRoot(vm: ChatViewModel = viewModel()) {
    val context = LocalContext.current
    val state by vm.state.collectAsStateWithLifecycle()
    val levels = vm.levels
    val levelFrame by vm.levelFrame.collectAsStateWithLifecycle()
    val elapsedMs by vm.elapsedMs.collectAsStateWithLifecycle()

    val vocab by vm.vocab.collectAsStateWithLifecycle()
    val vocabToday = vocab.firstOrNull { it.label == "今天" }?.cards?.size ?: 0
    val closedSession by vm.lastSession.collectAsStateWithLifecycle()
    val sessions by vm.sessions.collectAsStateWithLifecycle()
    val settings by vm.settings.collectAsStateWithLifecycle()
    val providers by vm.providers.collectAsStateWithLifecycle()
    val report by vm.report.collectAsStateWithLifecycle()
    var panel by remember { mutableStateOf(Panel.None) }
    var scenarioOpen by remember { mutableStateOf(false) }
    var reviewTurn by remember { mutableStateOf<Turn?>(null) }

    // 小结卡：会话已经结束时用签到下来的那条记录，练习中打开就用当前这段对话算。
    val repeatsHint = vocab.flatMap { it.cards }
        .minByOrNull { it.repeats }
        ?.takeIf { it.repeats < RepeatsHintBelow }
        ?.let { "「${it.word}」复现 ${it.repeats} 次" }
    val summary = closedSession?.let { record ->
        SessionSummary(
            name = record.name,
            rounds = record.rounds,
            pronunciation = record.pronunciationAvg,
            grammar = record.grammarAvg,
            newWords = record.newWordCount,
            repetitionHint = repeatsHint,
        )
    } ?: sessionSummary(
        turns = state.turns,
        name = "",
        newWords = vocabToday,
        repetitionHint = repeatsHint,
    )

    LaunchedEffect(panel) {
        if (panel == Panel.Sessions) vm.loadSessions()
    }

    // 授权弹窗会吃掉当前这次按压，手指抬起事件不会回来。授权后只提示，等下一次真实按压再收音。
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) {
            vm.showNotice("麦克风已授权")
        } else {
            vm.showNotice("需要麦克风权限")
        }
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
            onRetryAnalysis = vm::retryAnalysis,
            vocabToday = vocabToday,
            onRecordPressStart = {
                val granted = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
                    PackageManager.PERMISSION_GRANTED
                if (granted) vm.onRecordPressStart() else permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
            },
            onRecordArmed = vm::onRecordArmed,
            onRecordPressEnd = vm::onRecordPressEnd,
            onHangup = {
                vm.hangup()
                // 挂断同时弹出小结卡，底部主按钮换成开始新会话。
                panel = Panel.Summary
            },
            onRetry = vm::reconnect,
            onSendText = vm::sendText,
            onSaveTurn = { id, saved -> vm.setSaved(id, saved) },
            onPlayTurn = vm::playTurn,
            onTranslateTurn = vm::translate,
            onSummaryClick = { panel = Panel.Summary },
            onSessionsClick = { panel = Panel.Sessions },
        )

        AnimatedVisibility(visible = scenarioOpen, enter = fadeIn(), exit = fadeOut()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    // 透明遮罩只用来接住划过菜单外的点击，不再压暗整屏
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                    ) { scenarioOpen = false },
            )
        }

        AnimatedVisibility(
            visible = scenarioOpen,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.align(Alignment.TopStart),
        ) {
            ParleyMenu(
                items = SeedContent.Scenarios.map { scenario ->
                    MenuItem(
                        label = scenario,
                        selected = scenario == state.scenario,
                        onClick = {
                            vm.setScenario(scenario)
                            scenarioOpen = false
                        },
                    )
                },
                modifier = Modifier
                    .windowInsetsPadding(WindowInsets.statusBars)
                    .padding(start = Spacing.screen, top = 48.dp),
            )
        }

        AnimatedVisibility(
            visible = state.recording,
            enter = slideInVertically { it } + fadeIn(),
            exit = slideOutVertically { it } + fadeOut(),
        ) {
            RecordingOverlay(
                levels = levels,
                frame = levelFrame,
                elapsedMs = elapsedMs,
                cancelArmed = state.cancelArmed,
            )
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
                    onReplay = { reviewTurn?.let { vm.playTurn(it.id) } },
                    onSpeak = vm::speakText,
                )
            }
        }

        AnimatedVisibility(
            visible = panel == Panel.Vocab,
            enter = slideInVertically { it },
            exit = slideOutVertically { it },
        ) {
            VocabScreen(
                groups = vocab,
                onDismiss = { panel = Panel.None },
                onSpeak = vm::speakCard,
            )
        }

        SessionSummarySheet(
            summary = summary,
            visible = panel == Panel.Summary,
            onDismiss = { panel = Panel.None },
            onRename = vm::renameSession,
            onStartNew = {
                panel = Panel.None
                vm.startNewSession()
            },
        )

        SessionListScreen(
            sessions = remember(sessions) { sessions.map { it.toEntry() } },
            onDismiss = { panel = Panel.None },
            visible = panel == Panel.Sessions,
        )

        AnimatedVisibility(
            visible = panel == Panel.Settings,
            enter = slideInVertically { it },
            exit = slideOutVertically { it },
        ) {
            SettingsScreen(
                settings = settings,
                providers = providers,
                report = report,
                onDismiss = { panel = Panel.None },
                onProbe = vm::probe,
                onUpdate = vm::updateSettings,
            )
        }

        state.notice?.let { notice ->
            // 轻提示落在顶栏下方八像素，避开手掌常压的底栏；两秒自动消失，点一下立即收起。
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .windowInsetsPadding(WindowInsets.statusBars),
                contentAlignment = Alignment.TopCenter,
            ) {
                Surface(
                    color = Palette.tint,
                    shape = RoundedCornerShape(Radius.pill),
                    border = BorderStroke(1.dp, Palette.hair),
                    modifier = Modifier
                        .padding(top = 52.dp)
                        .clickable { vm.dismissNotice() },
                ) {
                    Text(
                        notice,
                        style = Type.caption.copy(fontSize = 12.5.sp, lineHeight = 18.sp, color = Palette.ink2),
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 9.dp),
                    )
                }
            }
        }

        Spacer(Modifier.height(0.dp))
    }
}

/** 复现次数低于这个数的词条才值得在卡片上提示一次。 */
private const val RepeatsHintBelow = 3

// 记录页自带一份只含展示字段的记录模型，存储层那份在这里挑出它要的四项。
private fun com.parley.speaker.data.SessionRecord.toEntry() = SessionEntry(
    id = id,
    name = name,
    startedAt = startedAt,
    rounds = rounds,
    pronunciation = pronunciationAvg,
)
