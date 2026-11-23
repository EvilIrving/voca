import Foundation

/// 输入增益：压着嗓子、贴着嘴说出来的句子，在交给实时模型之前先拉回工作电平。
///
/// iOS 的 `.voiceChat` 模式会让系统走语音通信的信号处理路径，具体开哪些、开多大由系统定，
/// 代码里查不到也验不了；小声音有没有被当成残余回声压掉，只能自己再兜一层。
/// 这一层与安卓同一套参数：目标负二十分贝满量程、上限三十分贝、半量程以上软限幅。
///
/// 参照取最近三秒内逐帧 RMS 的中位数，不取瞬时电平，也不取峰值：一次碰到机壳的撞击、
/// 一声清嗓子顶到满量程，按峰值做参照就把整段语音压到模型看不见的地方。
/// 不做"这一帧是不是人声"的分类：轻声的帧级电平和环境声本来就只差几个分贝，
/// 分类一旦判错就再也不抬增益；而按下说话这个动作本身就是"有人在说"的先验。
struct InputGain {

    private(set) var gain: Double = 1
    private(set) var peakIn: Int = 0
    private(set) var peakOut: Int = 0

    private var window = [Double](repeating: 0, count: InputGain.windowFrames)
    private var scratch = [Double](repeating: 0, count: InputGain.windowFrames)
    private var filled = 0
    private var cursor = 0

    /// 原地乘增益并按软限幅折回十六位。
    mutating func apply(_ frame: inout [Int16]) {
        guard !frame.isEmpty else { return }
        var sum = 0.0
        var peak = 0
        for sample in frame {
            let value = Int(sample)
            sum += Double(value) * Double(value)
            peak = max(peak, abs(value))
        }
        peakIn = max(peakIn, peak)

        window[cursor] = (sum / Double(frame.count)).squareRoot()
        cursor = (cursor + 1) % InputGain.windowFrames
        filled = min(filled + 1, InputGain.windowFrames)

        step(target())
        var out = 0
        for index in frame.indices {
            let value = limit(Double(frame[index]) * gain)
            frame[index] = Int16(truncatingIfNeeded: value)
            out = max(out, abs(Int(frame[index])))
        }
        peakOut = max(peakOut, out)
    }

    /// 参照电平拉向 `targetRms`；样本太少时窗口里的数不代表性，先原样放过去。
    private mutating func target() -> Double {
        guard filled >= InputGain.minFrames else { return gain }
        let level = percentile()
        guard level >= InputGain.floorRms else { return 1 }
        return min(max(InputGain.targetRms / level, 1), InputGain.maxGain)
    }

    private mutating func percentile() -> Double {
        let size = filled
        for index in 0..<size { scratch[index] = window[index] }
        scratch[0..<size].sort()
        let slot = Int(Double(size - 1) * InputGain.referencePercentile)
        return scratch[slot]
    }

    /// 按分贝步进挪包络，两端都限速，避免一帧之内把增益甩到顶。
    private mutating func step(_ target: Double) {
        let current = 20 * log10(max(gain, 1))
        let wanted = 20 * log10(max(target, 1))
        let next = current + max(-InputGain.stepDb, min(InputGain.stepDb, wanted - current))
        gain = min(max(pow(10, next / 20), 1), InputGain.maxGain)
    }

    /// 软限幅：半量程以内原样通过，以上按双曲正切压向渐近上限。
    private func limit(_ value: Double) -> Int {
        let magnitude = abs(value)
        let shaped: Double
        if magnitude <= InputGain.knee {
            shaped = magnitude
        } else {
            shaped = InputGain.knee
                + (InputGain.ceiling - InputGain.knee) * tanh((magnitude - InputGain.knee) / (InputGain.ceiling - InputGain.knee))
        }
        let signed = value < 0 ? -shaped : shaped
        return Int(min(max(signed.rounded(), Double(Int16.min)), Double(Int16.max)))
    }

    /// 一段话收尾时的一行说明，只进日志。
    var describe: String {
        String(format: "gain=%.1fdB peakIn=%d peakOut=%d", 20 * log10(max(gain, 1)), peakIn, peakOut)
    }

    /// 目标电平取负二十分贝满量程，与 `SpeechGate.normalizeLevelDb` 同一档。
    static let targetRmsDb: Double = -20
    static let targetRms: Double = 32_768 * pow(10, targetRmsDb / 20)
    static let maxGainDb: Double = 30
    static let maxGain: Double = pow(10, maxGainDb / 20)

    /// 二十毫秒一帧，三秒即一百五十帧：短到一次撞击占不满中位，长到盖得住一整句话。
    static let windowFrames = 150
    static let referencePercentile: Double = 0.5
    static let minFrames = 5
    static let floorRms: Double = 6
    static let stepDb: Double = 2
    static let knee: Double = 32_767 * 0.5
    static let ceiling: Double = 32_767 * 0.88
}
