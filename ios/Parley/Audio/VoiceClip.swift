import Foundation

/// 一段录完的语音：缓存里的 OGG 与 Telegram 波形六十三字节。
struct VoiceClip: Equatable {
    let path: String
    let durationMs: Int64
    let waveform: Data
}