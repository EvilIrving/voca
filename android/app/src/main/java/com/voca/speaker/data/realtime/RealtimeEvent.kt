package com.voca.speaker.data.realtime

/**
 * 会话适配器对界面只暴露这六类事件，界面不写供应商分支。
 * 带着 turnId 的事件来自已经被取消的那一轮，界面按这个 id 丢掉，不建气泡。
 */
sealed interface RealtimeEvent {
    data object Ready : RealtimeEvent
    data class UserText(val text: String, val turnId: String? = null) : RealtimeEvent
    data class AssistantText(val text: String, val turnId: String? = null) : RealtimeEvent
    data class Audio(val pcm: ByteArray, val turnId: String? = null) : RealtimeEvent
    data object Interrupted : RealtimeEvent
    data object TurnComplete : RealtimeEvent
    data class Failed(val message: String) : RealtimeEvent
}

data class Capabilities(
    val canInterrupt: Boolean,
    val acceptsText: Boolean,
    val streamsTranscript: Boolean,
    val phonemeScores: Boolean,
    val maxSessionMinutes: Int?,
)
