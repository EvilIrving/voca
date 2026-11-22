package com.parley.speaker.ui.chat

/**
 * 暂停态要摊开的那一段：波形、时长与播到哪儿。
 * 输入条只负责按这份数据画，播放本身在 ViewModel 那边，界面不碰解码器。
 */
data class VoicePreview(
    val waveform: ByteArray,
    val durationMs: Long,
    val progress: Float,
    val playing: Boolean,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is VoicePreview) return false
        return waveform.contentEquals(other.waveform) &&
            durationMs == other.durationMs &&
            progress == other.progress &&
            playing == other.playing
    }

    override fun hashCode(): Int {
        var result = waveform.contentHashCode()
        result = 31 * result + durationMs.hashCode()
        result = 31 * result + progress.hashCode()
        result = 31 * result + playing.hashCode()
        return result
    }
}
