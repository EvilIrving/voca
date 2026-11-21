package com.parley.speaker.data.realtime

/**
 * 会话适配器对界面只暴露这六类事件，界面不写供应商分支。
 */
sealed interface RealtimeEvent {
    data object Ready : RealtimeEvent
    data class UserText(val text: String) : RealtimeEvent
    data class AssistantText(val text: String) : RealtimeEvent
    data class Audio(val pcm: ByteArray) : RealtimeEvent
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
