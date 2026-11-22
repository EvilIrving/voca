import Foundation

/// Telegram 紧凑波形：一百根柱子，每根五位，共六十三字节。
enum Waveform {
    static let bars = 100
    static let packedBytes = 63

    private static let valueBits = 5
    private static let maxValue = 31

    static func pack(_ levels: [Int]) -> Data {
        var out = [UInt8](repeating: 0, count: packedBytes)
        var bit = 0
        for bar in 0 ..< bars {
            let value = bar < levels.count ? levels[bar].clamped(to: 0 ... maxValue) : 0
            for shift in stride(from: valueBits - 1, through: 0, by: -1) {
                if (value >> shift) & 1 == 1 {
                    let index = bit >> 3
                    out[index] |= UInt8(1 << (7 - (bit & 7)))
                }
                bit += 1
            }
        }
        return Data(out)
    }

    static func unpack(_ bytes: Data) -> [Int] {
        var out = [Int](repeating: 0, count: bars)
        let raw = [UInt8](bytes)
        var bit = 0
        for bar in 0 ..< bars {
            var value = 0
            for _ in 0 ..< valueBits {
                let index = bit >> 3
                let set: Int
                if index < raw.count {
                    set = (Int(raw[index]) >> (7 - (bit & 7))) & 1
                } else {
                    set = 0
                }
                value = (value << 1) | set
                bit += 1
            }
            out[bar] = value
        }
        return out
    }

    static func peaks(_ rms: [Float], count: Int = bars) -> [Int] {
        if count <= 0 { return [] }
        var out = [Int](repeating: 0, count: count)
        if rms.isEmpty { return out }

        var sampled = [Float](repeating: 0, count: count)
        var maxPeak: Float = 0
        for bar in 0 ..< count {
            let start = Int(Int64(bar) * Int64(rms.count) / Int64(count))
            let end = min(Int(Int64(bar + 1) * Int64(rms.count) / Int64(count)), rms.count)
            var peak: Float = 0
            if end > start {
                for index in start ..< end {
                    peak = max(peak, abs(rms[index]))
                }
            } else {
                peak = abs(rms[min(start, rms.count - 1)])
            }
            sampled[bar] = peak
            maxPeak = max(maxPeak, peak)
        }
        if maxPeak <= 0 { return out }

        for bar in 0 ..< count {
            out[bar] = Int((sampled[bar] / maxPeak * Float(maxValue)).rounded()).clamped(to: 0 ... maxValue)
        }
        return out
    }
}

private extension Int {
    func clamped(to range: ClosedRange<Int>) -> Int {
        Swift.min(Swift.max(self, range.lowerBound), range.upperBound)
    }
}