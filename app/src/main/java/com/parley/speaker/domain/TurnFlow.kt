package com.parley.speaker.domain

/**
 * 回合生命周期。轮次标识只在拥有它的阶段里存在，非法组合无法构造：
 * 收音必然带着草稿轮，等待回复才可能带着用户轮与助手轮。
 */
sealed interface TurnPhase {
    /** 没有可用会话。 */
    data object Offline : TurnPhase

    /** 会话就绪，可以按下说话。 */
    data object Idle : TurnPhase

    /** 正在收音。userTurnId 是本轮的草稿轮，文字到齐才进列表。 */
    data class Recording(val userTurnId: String) : TurnPhase

    /** 已松手，等待服务端收尾。这一阶段再按下就是打断模型输出。 */
    data class Awaiting(
        val userTurnId: String?,
        val echoTurnId: String?,
    ) : TurnPhase
}

data class TurnFlowState(
    val phase: TurnPhase = TurnPhase.Offline,
    val turns: List<Turn> = emptyList(),
    val cancelArmed: Boolean = false,
    /**
     * 取消掉的那几轮还会继续从服务端回吐识别文本与音频，协议里没有轮次号，
     * 由适配器把窗口内到达的事件打上该轮 id，这里认 id 丢弃，保证迟到的内容不建气泡。
     * 只留最近几次，够覆盖迟到时间的往返。
     */
    val cancelledTurnIds: Set<String> = emptySet(),
)

sealed interface TurnEvent {
    data object SessionReady : TurnEvent
    data object SessionLost : TurnEvent
    data object PressStart : TurnEvent
    data class ArmCancel(val armed: Boolean) : TurnEvent
    data object Commit : TurnEvent
    data object Cancel : TurnEvent

    /** 闸门判定这一轮没有语音内容，本地直接回 Idle，不发收尾也不落气泡。 */
    data object SilentTurn : TurnEvent

    /** 用户发文字：跳过收音与 activityStart，直接落一条用户轮再送文本。 */
    data class TextTurn(val text: String) : TurnEvent
    data class UserText(val text: String, val turnId: String? = null) : TurnEvent
    data class AssistantText(val text: String, val turnId: String? = null) : TurnEvent
    data class Audio(val pcm: ByteArray, val turnId: String? = null) : TurnEvent
    data object Interrupted : TurnEvent
    data object TurnComplete : TurnEvent
    data object CaptureFailed : TurnEvent
    data class TranslationDone(val turnId: String, val translation: String) : TurnEvent
    data class AnalysisDone(val turnId: String, val review: Review, val vocab: List<VocabCard>) : TurnEvent
    data class AnalysisFailed(val turnId: String) : TurnEvent
    data class RetryAnalysis(val turnId: String) : TurnEvent
}

sealed interface TurnEffect {
    data class StartCapture(val turnId: String) : TurnEffect
    data object StopCapture : TurnEffect
    data object SendTurnStart : TurnEffect
    data object SendTurnEnd : TurnEffect

    /** 文字回合送文本，不收音也不开新的 activity。 */
    data class SendText(val text: String) : TurnEffect

    /** 取消这一轮，带上它的 id，适配器据此开黑名单窗口。 */
    data class CancelTurn(val turnId: String) : TurnEffect

    /** 下行音频：turnId 是这一轮助手的轮次，播放的同时把 PCM 追加进录音文件。 */
    data class Play(val pcm: ByteArray, val turnId: String? = null) : TurnEffect
    data object StopPlayback : TurnEffect
    data class Persist(val turn: Turn) : TurnEffect
    data class Delete(val turnId: String) : TurnEffect
    data class Analyze(val turnId: String, val text: String) : TurnEffect
    data class LearnVocab(val turnId: String, val cards: List<VocabCard>) : TurnEffect
    data class Notify(val message: String) : TurnEffect
}

data class Reduction(val state: TurnFlowState, val effects: List<TurnEffect>)

object TurnFlow {

    private const val MAX_CANCELLED = 4

    fun reduce(
        state: TurnFlowState,
        event: TurnEvent,
        nextTurnId: (String) -> String,
    ): Reduction {
        if (event.isLateForCancelled(state)) return Reduction(state, emptyList())
        val effects = mutableListOf<TurnEffect>()
        val next = when (event) {
            TurnEvent.SessionReady -> state.copy(phase = TurnPhase.Idle)

            TurnEvent.SessionLost -> loseSession(state, effects)

            TurnEvent.PressStart -> when (val phase = state.phase) {
                TurnPhase.Idle -> startRecording(state, effects, nextTurnId)

                is TurnPhase.Awaiting -> {
                    // 模型正在说，用户按下就是打断：停播、把上一轮的评测补上、立刻开始新的收音。
                    effects += TurnEffect.StopPlayback
                    val settled = settleAwaiting(state, phase, effects, notify = false)
                    startRecording(settled, effects, nextTurnId)
                }

                else -> state
            }

            is TurnEvent.ArmCancel -> when (state.phase) {
                is TurnPhase.Recording -> state.copy(cancelArmed = event.armed)
                else -> state
            }

            TurnEvent.Commit -> when (val phase = state.phase) {
                is TurnPhase.Recording -> {
                    effects += TurnEffect.StopCapture
                    effects += TurnEffect.SendTurnEnd
                    state.copy(
                        phase = TurnPhase.Awaiting(phase.userTurnId, null),
                        cancelArmed = false,
                    )
                }

                else -> state
            }

            TurnEvent.Cancel -> when (val phase = state.phase) {
                is TurnPhase.Recording -> {
                    effects += TurnEffect.StopCapture
                    effects += TurnEffect.CancelTurn(phase.userTurnId)
                    effects += TurnEffect.Delete(phase.userTurnId)
                    state.copy(
                        phase = TurnPhase.Idle,
                        turns = state.turns.filterNot { it.id == phase.userTurnId },
                        cancelArmed = false,
                        cancelledTurnIds = state.blacklist(phase.userTurnId),
                    )
                }

                else -> state
            }

            TurnEvent.SilentTurn -> when (val phase = state.phase) {
                is TurnPhase.Recording -> {
                    effects += TurnEffect.StopCapture
                    // 服务端已经在收这一轮，只发 activityEnd 止损；不发 SendTurnEnd，也没有气泡与提示。
                    effects += TurnEffect.CancelTurn(phase.userTurnId)
                    effects += TurnEffect.Delete(phase.userTurnId)
                    state.copy(
                        phase = TurnPhase.Idle,
                        turns = state.turns.filterNot { it.id == phase.userTurnId },
                        cancelArmed = false,
                        cancelledTurnIds = state.blacklist(phase.userTurnId),
                    )
                }

                else -> state
            }

            is TurnEvent.TextTurn -> when (val phase = state.phase) {
                TurnPhase.Offline -> state
                is TurnPhase.Recording -> state
                is TurnPhase.Awaiting -> {
                    // 模型正在说的时候打字也是打断，停播、把上一轮的评测补上，已经留下的回覆不动。
                    effects += TurnEffect.StopPlayback
                    val settled = settleAwaiting(state, phase, effects, notify = false)
                    startTextTurn(settled, event.text, effects, nextTurnId)
                }

                TurnPhase.Idle -> startTextTurn(state, event.text, effects, nextTurnId)
            }

            is TurnEvent.UserText -> appendUserText(state, event.text, effects)

            is TurnEvent.AssistantText -> appendAssistantText(state, event.text, effects, nextTurnId)

            is TurnEvent.Audio -> when (val phase = state.phase) {
                is TurnPhase.Awaiting -> {
                    // 音频常常早于转写到达，这里先把这一轮助手的轮次定下来，录音才落得到同一个文件上。
                    val echoId = phase.echoTurnId ?: nextTurnId("echo")
                    effects += TurnEffect.Play(event.pcm, echoId)
                    state.copy(phase = phase.copy(echoTurnId = echoId))
                }

                else -> state
            }

            TurnEvent.Interrupted -> {
                effects += TurnEffect.StopPlayback
                state
            }

            TurnEvent.TurnComplete -> completeTurn(state, effects)

            TurnEvent.CaptureFailed -> when (val phase = state.phase) {
                is TurnPhase.Recording -> {
                    // 半截音频已经在服务端手里，按说完收尾会被当成正常收音结束并生成文本，走取消。
                    effects += TurnEffect.StopCapture
                    effects += TurnEffect.CancelTurn(phase.userTurnId)
                    effects += TurnEffect.Delete(phase.userTurnId)
                    effects += TurnEffect.Notify("麦克风不可用")
                    state.copy(
                        phase = TurnPhase.Idle,
                        turns = state.turns.filterNot { it.id == phase.userTurnId },
                        cancelArmed = false,
                        cancelledTurnIds = state.blacklist(phase.userTurnId),
                    )
                }

                else -> state
            }

            is TurnEvent.AnalysisDone -> {
                // 分析回来的语境生词要当场入库，否则词库永远只有空态。
                if (event.vocab.isNotEmpty()) {
                    effects += TurnEffect.LearnVocab(event.turnId, event.vocab)
                }
                markAnalysis(
                    state,
                    event.turnId,
                    effects,
                ) { turn -> turn.copy(review = event.review, analysis = AnalysisStatus.Ready) }
            }

            is TurnEvent.AnalysisFailed -> markAnalysis(
                state,
                event.turnId,
                effects,
            ) { turn -> turn.copy(analysis = AnalysisStatus.Failed) }

            is TurnEvent.RetryAnalysis -> {
                val turn = state.turns.firstOrNull { it.id == event.turnId && it.text.isNotBlank() }
                if (turn == null) {
                    state
                } else {
                    effects += TurnEffect.Analyze(turn.id, turn.text)
                    markAnalysis(state, turn.id, effects) { it.copy(analysis = AnalysisStatus.Pending) }
                }
            }

            is TurnEvent.TranslationDone ->
                markAnalysis(state, event.turnId, effects) { it.copy(translation = event.translation) }
        }
        return Reduction(next, effects)
    }

    private fun startRecording(
        state: TurnFlowState,
        effects: MutableList<TurnEffect>,
        nextTurnId: (String) -> String,
    ): TurnFlowState {
        val id = nextTurnId("user")
        effects += TurnEffect.StartCapture(id)
        effects += TurnEffect.SendTurnStart
        return state.copy(phase = TurnPhase.Recording(id), cancelArmed = false)
    }

    /** 文字回合只走一条路：落用户轮、写库、送文本，然后等回复。 */
    private fun startTextTurn(
        state: TurnFlowState,
        text: String,
        effects: MutableList<TurnEffect>,
        nextTurnId: (String) -> String,
    ): TurnFlowState {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return state
        val id = nextTurnId("user")
        val turn = Turn(id = id, speaker = Speaker.User, text = trimmed)
        effects += TurnEffect.Persist(turn)
        effects += TurnEffect.SendText(trimmed)
        return state.copy(
            phase = TurnPhase.Awaiting(id, null),
            turns = state.turns + turn,
            cancelArmed = false,
        )
    }

    private fun loseSession(state: TurnFlowState, effects: MutableList<TurnEffect>): TurnFlowState {
        return when (val phase = state.phase) {
            is TurnPhase.Recording -> {
                effects += TurnEffect.StopCapture
                effects += TurnEffect.Delete(phase.userTurnId)
                effects += TurnEffect.Notify("连接断了，这一轮作废，重新按一次")
                state.copy(
                    phase = TurnPhase.Offline,
                    turns = state.turns.filterNot { it.id == phase.userTurnId },
                    cancelArmed = false,
                )
            }

            is TurnPhase.Awaiting -> {
                val dropped = setOfNotNull(phase.userTurnId, phase.echoTurnId)
                dropped.forEach { effects += TurnEffect.Delete(it) }
                effects += TurnEffect.Notify("连接断了，这一轮作废，重新按一次")
                state.copy(
                    phase = TurnPhase.Offline,
                    turns = state.turns.filterNot { it.id in dropped },
                    cancelArmed = false,
                )
            }

            else -> state.copy(phase = TurnPhase.Offline)
        }
    }

    private fun appendUserText(
        state: TurnFlowState,
        text: String,
        effects: MutableList<TurnEffect>,
    ): TurnFlowState {
        val phase = state.phase
        val turnId = when (phase) {
            is TurnPhase.Recording -> phase.userTurnId
            is TurnPhase.Awaiting -> phase.userTurnId
            else -> null
        } ?: return state

        val updated = state.turns.upsertText(turnId, Speaker.User, text)
        effects += TurnEffect.Persist(updated.first { it.id == turnId })
        return state.copy(turns = updated)
    }

    private fun appendAssistantText(
        state: TurnFlowState,
        text: String,
        effects: MutableList<TurnEffect>,
        nextTurnId: (String) -> String,
    ): TurnFlowState {
        val phase = state.phase as? TurnPhase.Awaiting ?: return state
        val turnId = phase.echoTurnId ?: nextTurnId("echo")
        val updated = state.turns.upsertText(turnId, Speaker.Echo, text)
        effects += TurnEffect.Persist(updated.first { it.id == turnId })
        return state.copy(turns = updated, phase = phase.copy(echoTurnId = turnId))
    }

    private fun completeTurn(state: TurnFlowState, effects: MutableList<TurnEffect>): TurnFlowState {
        val phase = state.phase as? TurnPhase.Awaiting ?: return state
        val settled = settleAwaiting(state, phase, effects, notify = true)
        return settled.copy(phase = TurnPhase.Idle, cancelArmed = false)
    }

    /**
     * 收尾一轮等待中的回合：用户轮有文字就当场发分析并标成待分析，没文字就把这对草稿轮删掉。
     * 正常说完、生成中被说话打断、生成中被打字打断走同一条路，被打断的那句一样要有语法评测。
     * 提示只在回到 Idle 的路径上发，打断时用户正在说下一句，不应被打断。
     */
    private fun settleAwaiting(
        state: TurnFlowState,
        phase: TurnPhase.Awaiting,
        effects: MutableList<TurnEffect>,
        notify: Boolean,
    ): TurnFlowState {
        var turns = state.turns
        val dropped = mutableSetOf<String>()
        val userTurn = phase.userTurnId?.let { id -> turns.firstOrNull { it.id == id } }
        if (userTurn == null || userTurn.text.isBlank()) {
            phase.userTurnId?.let { dropped += it }
            phase.echoTurnId?.let { dropped += it }
            if (notify) effects += TurnEffect.Notify("没有听清，再来一次")
        } else if (userTurn.analysis == AnalysisStatus.None) {
            effects += TurnEffect.Analyze(userTurn.id, userTurn.text)
            turns = turns.map {
                if (it.id == userTurn.id) it.copy(analysis = AnalysisStatus.Pending) else it
            }
            effects += TurnEffect.Persist(turns.first { it.id == userTurn.id })
        }

        dropped.forEach { effects += TurnEffect.Delete(it) }
        return state.copy(turns = turns.filterNot { it.id in dropped })
    }

    private fun markAnalysis(
        state: TurnFlowState,
        turnId: String,
        effects: MutableList<TurnEffect>,
        transform: (Turn) -> Turn,
    ): TurnFlowState {
        val turn = state.turns.firstOrNull { it.id == turnId } ?: return state
        val updated = transform(turn)
        effects += TurnEffect.Persist(updated)
        return state.copy(turns = state.turns.map { if (it.id == turnId) updated else it })
    }

    private fun TurnEvent.isLateForCancelled(state: TurnFlowState): Boolean {
        val tag = when (this) {
            is TurnEvent.UserText -> turnId
            is TurnEvent.AssistantText -> turnId
            is TurnEvent.Audio -> turnId
            else -> null
        }
        return tag != null && tag in state.cancelledTurnIds
    }

    private fun TurnFlowState.blacklist(turnId: String): Set<String> =
        (cancelledTurnIds + turnId).toList().takeLast(MAX_CANCELLED).toSet()

    private fun List<Turn>.upsertText(id: String, speaker: Speaker, chunk: String): List<Turn> {
        val existing = firstOrNull { it.id == id }
        return if (existing == null) {
            this + Turn(id = id, speaker = speaker, text = chunk)
        } else {
            map { if (it.id == id) it.copy(text = it.text + chunk) else it }
        }
    }
}
