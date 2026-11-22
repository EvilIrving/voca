package com.parley.speaker.audio

/**
 * 一段录完的语音。文件是缓存里那只 OGG，波形是 Telegram 那套 100 根 5 bit 压成的 63 字节。
 * 字节数组不能直接比，判等按内容来，免得同一段录音换了个实例就被当成新的一段。
 */
data class VoiceClip(
    val path: String,
    val durationMs: Long,
    val waveform: ByteArray,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is VoiceClip) return false
        return path == other.path && durationMs == other.durationMs && waveform.contentEquals(other.waveform)
    }

    override fun hashCode(): Int {
        var result = path.hashCode()
        result = 31 * result + durationMs.hashCode()
        result = 31 * result + waveform.contentHashCode()
        return result
    }
}
