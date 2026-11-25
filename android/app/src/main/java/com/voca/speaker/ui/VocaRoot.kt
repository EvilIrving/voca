package com.voca.speaker.ui

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.derivedStateOf
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
import com.voca.speaker.data.SeedContent
import com.voca.speaker.data.startOfLocalDay
import com.voca.speaker.domain.Turn
import com.voca.speaker.domain.VocabFilter
import com.voca.speaker.domain.vocabPage
import com.voca.speaker.ui.chat.ChatScreen
import com.voca.speaker.ui.chat.ChatViewModel
import com.voca.speaker.ui.chat.Connection
import com.voca.speaker.ui.review.ReviewSheet
import com.voca.speaker.ui.session.DrawerScaffold
import com.voca.speaker.ui.session.SessionDrawer
import com.voca.speaker.ui.session.rememberDrawerState
import com.voca.speaker.ui.settings.SettingsScreen
import com.voca.speaker.ui.theme.Palette
import com.voca.speaker.ui.theme.Radius
import com.voca.speaker.ui.theme.Spacing
import com.voca.speaker.ui.theme.Type
import com.voca.speaker.ui.vocab.VocabScreen

private enum class Panel { None, Vocab, Settings }

@Composable
fun VocaRoot(vm: ChatViewModel = viewModel()) {
    val context = LocalContext.current
    val state by vm.state.collectAsStateWithLifecycle()
    val levels = vm.levels
    val levelFrame by vm.levelFrame.collectAsStateWithLifecycle()
    val elapsedMs by vm.elapsedMs.collectAsStateWithLifecycle()

    val vocab by vm.vocab.collectAsStateWithLifecycle()
    // 角标与生词页读同一份分档结果，跨零点也不会与列表打架。
    val vocabToday = remember(vocab) {
        vocabPage(vocab, startOfLocalDay(System.currentTimeMillis()), VocabFilter.Today).counts.today
    }
    val sessions by vm.sessions.collectAsStateWithLifecycle()
    val checkIn by vm.checkIn.collectAsStateWithLifecycle()
    val settings by vm.settings.collectAsStateWithLifecycle()
    val providers by vm.providers.collectAsStateWithLifecycle()
    val textProviders by vm.textProviders.collectAsStateWithLifecycle()
    val memory by vm.memoryText.collectAsStateWithLifecycle()
    var panel by remember { mutableStateOf(Panel.None) }
    var reviewTurn by remember { mutableStateOf<Turn?>(null) }
    val drawer = rememberDrawerState()
    val drawerShown by remember(drawer) { derivedStateOf { drawer.current > 0.001f } }
    // 屏幕上是不是接着某条历史会话聊：那一条的名字与标识都由视图模型一处报上来。
    val continuing = state.sessionId != null

    // 抽屉里点一条旧会话就接着它聊：顶栏换成那一条的名字与返回箭头，输入条照旧能用。
    val openSession: (com.voca.speaker.data.SessionRecord) -> Unit = { entry -> vm.resumeSession(entry) }
    val leaveSession: () -> Unit = { vm.leaveSession() }

    LaunchedEffect(drawerShown) {
        if (drawerShown) {
            // 抽屉要占住整条左栏，飘着的轻提示先收掉，免得压住新会话那一行。
            vm.dismissNotice()
            vm.loadSessions()
        }
    }

    LaunchedEffect(panel) {
        // 设置页里的打卡热力图读收段记录，和抽屉用的是同一份加载。
        if (panel == Panel.Settings) vm.loadSessions()
    }

    // 授权弹窗会吃掉当前这次按压，手指抬起事件不会回来。授权后只提示，等下一次真实按压再收音。
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) {
            vm.showNotice("麦克风已授权")
        } else {
            vm.showNotice("请授权麦克风权限")
        }
    }

    BackHandler(enabled = drawerShown || continuing || panel != Panel.None || reviewTurn != null) {
        when {
            reviewTurn != null -> reviewTurn = null
            drawerShown -> drawer.close()
            continuing -> leaveSession()
            else -> panel = Panel.None
        }
    }

    Box(modifier = Modifier.fillMaxSize().background(Palette.paper)) {
        DrawerScaffold(
            state = drawer,
            drawer = {
                SessionDrawer(
                    sessions = sessions,
                    viewingId = state.sessionId,
                    open = drawerShown,
                    onNewSession = {
                        drawer.close()
                        vm.startNewSession()
                    },
                    onPick = { id ->
                        drawer.close()
                        sessions.firstOrNull { it.id == id }?.let(openSession)
                    },
                    onSettings = {
                        drawer.close()
                        panel = Panel.Settings
                    },
                    onDelete = vm::deleteSession,
                    onRename = vm::renameSession,
                    onDeleteMany = vm::deleteSessions,
                )
            },
        ) {
            ChatScreen(
                state = state,
                turns = state.turns,
                sessionName = state.sessionName.orEmpty(),
                elapsedMs = elapsedMs,
                levels = levels,
                levelFrame = levelFrame,
                continuing = continuing,
                // 切会话有两条路：底栏以上向右横滑拉出抽屉；或者看不见的那一块——点会话名。
                onExitSession = leaveSession,
                onNewSession = vm::startNewSession,
                onVocabClick = { panel = Panel.Vocab },
                onSessionNameTap = { drawer.open() },
                onReviewClick = { reviewTurn = it },
                onRetryAnalysis = vm::retryAnalysis,
                vocabToday = vocabToday,
                onRecordPressStart = {
                    val granted = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
                        PackageManager.PERMISSION_GRANTED
                    if (granted) {
                        vm.onRecordPressStart()
                    } else {
                        // 授权弹窗会吃掉这次按压，抬手事件不会回来，所以这一下当场作废。
                        permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                        false
                    }
                },
                onRecordArmed = vm::onRecordArmed,
                onRecordPressEnd = vm::onRecordPressEnd,
                onSendText = vm::sendText,
                onSaveTurn = { id, saved -> vm.setSaved(id, saved) },
                onPlayTurn = vm::playTurn,
                onTranslateTurn = vm::translate,
                onCopyTurn = vm::copyTurn,
                onDeleteTurn = vm::deleteTurn,
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
                cards = vocab,
                onDismiss = { panel = Panel.None },
                onSpeak = vm::speakCard,
                onDelete = vm::deleteVocab,
                onRestore = vm::restoreVocab,
            )
        }

        AnimatedVisibility(
            visible = panel == Panel.Settings,
            enter = slideInHorizontally { it } + fadeIn(),
            exit = slideOutHorizontally { it } + fadeOut(),
        ) {
            SettingsScreen(
                settings = settings,
                providers = providers,
                textProviders = textProviders,
                checkIn = checkIn,
                memory = memory,
                defaultPrompt = vm.defaultPrompt,
                defaultMemory = vm.defaultMemory,
                onDismiss = { panel = Panel.None },
                onUpdate = vm::updateSettings,
                onUpdateMemory = vm::updateMemory,
                onLoadModels = vm::loadModels,
            )
        }

        // 轻提示落在顶栏下方八像素，避开手掌常压的底栏；两秒自动消失，点一下立即收起。
        // 出入场只走透明度与一点纵向位移：从上方八像素淡下来，收起时原路淡回去。
        // 退出那一帧 notice 已经是空，动画得有东西可画，所以上一条文案要留在手里。
        var carried by remember { mutableStateOf<String?>(null) }
        val noticeText = state.notice ?: carried
        SideEffect { state.notice?.let { carried = it } }
        AnimatedVisibility(
            visible = state.notice != null,
            enter = fadeIn(tween(NoticeMs)) + slideInVertically(tween(NoticeMs)) { -it / 2 },
            exit = fadeOut(tween(NoticeMs)) + slideOutVertically(tween(NoticeMs)) { -it / 2 },
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .windowInsetsPadding(WindowInsets.statusBars),
                contentAlignment = Alignment.TopCenter,
            ) {
                noticeText?.let { text ->
                    Surface(
                        color = Palette.tint,
                        shape = RoundedCornerShape(Radius.pill),
                        border = BorderStroke(1.dp, Palette.hairSoft),
                        modifier = Modifier
                            .padding(top = 52.dp)
                            .clickable { vm.dismissNotice() },
                    ) {
                        Text(
                            text,
                            style = Type.caption.copy(fontSize = 12.5.sp, lineHeight = 18.sp, color = Palette.ink2),
                            modifier = Modifier.padding(horizontal = 14.dp, vertical = 9.dp),
                        )
                    }
                }
            }
        }
    }
}

/** 轻提示的进出场时长：只做透明度与位移，与浮层收尾同一档。 */
private const val NoticeMs = 180
