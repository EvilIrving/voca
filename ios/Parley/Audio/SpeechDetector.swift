import AVFoundation
import Foundation
import SoundAnalysis

/// 本地人声判定：把一段十六千赫兹单声道音频写成临时 WAV，交给系统内置的声音分类器逐窗打分，
/// 取每个窗的 speech 置信度，折成时间区间交给 [SpeechGate] 判连续段。
///
/// 用的是 Apple 自带的声音分类模型（`SNClassifySoundRequest.version1`），随系统走，不联网，
/// 也不带第三方运行时。分类器的窗长由模型定死（半秒上下），半重叠让步进缩到四分之一秒；
/// 判定要的是"连续够久"，所以一个窗的咳嗽、一次碰麦都不会被算成说话。
///
/// 走文件分析器而不是流式分析器：流式的要攒够三秒才吐第一个结果，短录音拿不到任何窗；
/// 文件分析器对整段一次铺开，窗与时间边界都在。
enum SpeechDetector {

    static let sampleRate = SpeechGate.sampleRate

    /// 逐窗的人声判定，时间按音频里的毫秒数给。
    static func windows(_ pcm: [Int16]) -> [SpeechGate.Window] {
        guard !pcm.isEmpty else { return [] }
        guard let request = try? SNClassifySoundRequest(classifierIdentifier: .version1) else { return [] }
        // 窗长写小会被模型夹到它自己的最小值（半秒上下），真正决定步进的是重叠系数。
        request.windowDuration = CMTime(value: 1, timescale: 20)
        request.overlapFactor = 0.5

        let file = FileManager.default.temporaryDirectory
            .appendingPathComponent("parley-vad-\(UUID().uuidString).wav")
        defer { try? FileManager.default.removeItem(at: file) }
        guard let wave = wav(levelled(pcm)), (try? wave.write(to: file)) != nil else { return [] }

        let collector = ConfidenceCollector()
        guard let analyzer = try? SNAudioFileAnalyzer(url: file) else { return [] }
        do {
            try analyzer.add(request, withObserver: collector)
        } catch {
            return []
        }
        analyzer.analyze()
        return collector.windows
    }

    /// 按峰值归一化之后折回十六位：分类器看不出来源音量，只看得见送进去的这段波形，
    /// 归一化放在写文件之前做，量化误差就落在归一化之后的电平上，压着嗓子录的音也还在工作点。
    private static func levelled(_ pcm: [Int16]) -> [Int16] {
        let normalized = SpeechGate.normalize(pcm)
        guard normalized.count == pcm.count else { return pcm }
        return normalized.map { sample in
            Int16(max(-32_768, min(32_767, (sample * 32_768).rounded())))
        }
    }

    /// 十六千赫兹单声道十六位的 WAV：文件分析器只吃带头的音频。
    private static func wav(_ pcm: [Int16]) -> Data? {
        var data = Data()
        let byteRate = UInt32(sampleRate) * 2
        let payload = UInt32(pcm.count * 2)
        func put(_ value: UInt32) { withUnsafeBytes(of: value.littleEndian) { data.append(contentsOf: $0) } }
        func put16(_ value: UInt16) { withUnsafeBytes(of: value.littleEndian) { data.append(contentsOf: $0) } }

        data.append(contentsOf: Array("RIFF".utf8))
        put(36 + payload)
        data.append(contentsOf: Array("WAVEfmt ".utf8))
        put(16)
        put16(1)
        put16(1)
        put(UInt32(sampleRate))
        put(byteRate)
        put16(2)
        put16(16)
        data.append(contentsOf: Array("data".utf8))
        put(payload)
        let samples = pcm
        samples.withUnsafeBufferPointer { buffer in
            data.append(UnsafeRawPointer(buffer.baseAddress!).assumingMemoryBound(to: UInt8.self), count: pcm.count * 2)
        }
        return data
    }
}

private final class ConfidenceCollector: NSObject, SNResultsObserving {
    private(set) var windows: [SpeechGate.Window] = []

    func request(_ request: SNRequest, didProduce result: SNResult) {
        guard let classification = result as? SNClassificationResult else { return }
        let speech = classification.classifications
            .first { $0.identifier == "speech" }?
            .confidence ?? 0
        let start = Int64((classification.timeRange.start.seconds * 1_000).rounded())
        let end = Int64(((classification.timeRange.start + classification.timeRange.duration).seconds * 1_000).rounded())
        windows.append(SpeechGate.Window(startMs: start, endMs: end, speech: Float(speech) >= SpeechGate.threshold))
    }

    func request(_ request: SNRequest, didFailWithError error: Error) {
        windows.removeAll()
    }

    func requestDidComplete(_ request: SNRequest) {}
}
