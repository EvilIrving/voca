import Foundation

/// 静音闸门：收完音之后判断这一轮里有没有人说话，判据是声音分类器给出的逐窗人声置信度，
/// 不是音量。分类器由 [SpeechDetector] 提供，这里只管两件事：把音量从判据里抹掉，以及把
/// 逐窗结果折成一个结论。
///
/// 归一化是整个过程的关键：整段先抹掉直流、把一个分位电平拉到 `normalizeLevelDb` 再送去分类。
/// 实测同一句压到负六十分贝的语音，不归一化时置信度掉到零点五五以下，归一化之后回到零点九以上；
/// 白噪声与纯音归一化之后仍然接近零，因为分类器看的是频谱形状而不是响度。
///
/// 电平参照取各窗 RMS 的 `referencePercentile` 分位，不取峰值：按住手机时的一声撞击、
/// 指尖碰到机壳、桌面振动都能把峰值顶到满量程，整段语音跟着被压到负五十分贝以下，
/// 分类器只听得到噪声底，判出来是零毫秒。
///
/// 判定看最长的一段连续人声，不是累计窗数：键盘敲击、放下杯子、桌面振动都是零散尖峰，
/// 累计能凑够时长，却没有一段是连续的。一句话里词与词之间的换气短过 `gapMs`，算同一段。
enum SpeechGate {

    /// 上行音频的采样率。
    static let sampleRate: Int = 16_000

    /// 分类器按五十毫秒一个窗给置信度。
    static let windowMs: Double = 50

    /// 置信度过这条线算这一窗里有人声。
    static let threshold: Float = 0.5

    /// 连续人声够这么久才算说了一句，短促的咳嗽与清嗓子不算。
    /// 挡住噪声的是分类器的置信度阈值，不是这条时长：分类器说不是人声，多长都不算。
    static let minSpeechMs: Int64 = 300

    /// 一句话里的换气不会超过这么久，短过它的空档仍算同一段人声。
    static let gapMs: Int64 = 100

    /// 各窗 RMS 的这个分位代表这一段的语音电平，送到 `normalizeLevelDb`。
    static let referencePercentile: Double = 0.9

    /// 统计电平的窗长，与安卓的判定窗一致。
    static let levelWindowSamples: Int = 512

    /// 参照电平拉到这里，把音量从判据里抹掉。
    static let normalizeLevelDb: Double = -20

    private static let fullScale: Double = 32_768

    /// 一段被判成人声的时间区间。
    struct Window {
        let startMs: Int64
        let endMs: Int64
        let speech: Bool
    }

    /// 抹掉直流，按分位电平归一并折成分类器要的浮点幅度；
    /// 整段都是零、或者短到凑不满一窗时返回全零，走同一条路。
    static func normalize(_ pcm: [Int16]) -> [Float] {
        guard !pcm.isEmpty else { return [] }
        let windows = pcm.count / levelWindowSamples
        guard windows > 0 else { return Array(repeating: 0, count: pcm.count) }
        var sum = 0.0
        for sample in pcm { sum += Double(sample) }
        let mean = sum / Double(pcm.count)
        var levels = (0..<windows).map { window -> Double in
            let start = window * levelWindowSamples
            var squares = 0.0
            for index in start..<(start + levelWindowSamples) {
                let value = Double(pcm[index]) - mean
                squares += value * value
            }
            return (squares / Double(levelWindowSamples)).squareRoot()
        }
        levels.sort()
        let reference = levels[Int(Double(windows - 1) * referencePercentile)]
        guard reference >= 1 else { return Array(repeating: 0, count: pcm.count) }
        let gain = fullScale * pow(10, normalizeLevelDb / 20) / reference
        return pcm.map { sample in
            let scaled = ((Double(sample) - mean) * gain).clamped(to: -fullScale...fullScale - 1)
            return Float(scaled / fullScale)
        }
    }

    /// 最长的一段连续人声，跨度里允许 `gapMs` 以内的空档。
    static func longestSpeechMs(_ windows: [Window]) -> Int64 {
        var best: Int64 = 0
        var runStart: Int64?
        var lastEnd: Int64 = 0
        for window in windows.sorted(by: { $0.startMs < $1.startMs }) {
            guard window.speech else { continue }
            if let start = runStart, window.startMs - lastEnd <= gapMs {
                best = max(best, window.endMs - start)
            } else {
                runStart = window.startMs
            }
            lastEnd = window.endMs
            if let start = runStart {
                best = max(best, window.endMs - start)
            }
        }
        return best
    }

    static func hasSpeech(_ windows: [Window]) -> Bool {
        longestSpeechMs(windows) >= minSpeechMs
    }

    static func shouldDrop(speechMs: Int64) -> Bool {
        speechMs < minSpeechMs
    }
}

private extension Double {
    func clamped(to range: ClosedRange<Double>) -> Double {
        min(max(self, range.lowerBound), range.upperBound)
    }
}
