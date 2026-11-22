import Foundation

/// 上行采集是四十八千赫兹，实时会话要十六千赫兹单声道。三十三抽头 Blackman-sinc 低通后每三取一。
final class Resampler {
    private static let tapCount = 33
    private static let halfWidth = tapCount / 2
    private static let step = 3
    private static let cutoffHz = 7_200.0
    private static let inputRate = 48_000.0
    private static let initialCapacity = 4_096

    private static let kernel: [Float] = {
        let raw = (0 ..< tapCount).map { index -> Float in
            let offset = index - halfWidth
            let ideal = 2.0 * cutoffHz / inputRate * sinc(2.0 * cutoffHz / inputRate * Double(offset))
            let phase = 2.0 * Double.pi * Double(index) / Double(tapCount - 1)
            let window = 0.42 - 0.5 * cos(phase) + 0.08 * cos(2.0 * phase)
            return Float(ideal * window)
        }
        let total = raw.reduce(0, +)
        return raw.map { $0 / total }
    }()

    private static func sinc(_ value: Double) -> Double {
        value == 0 ? 1.0 : sin(Double.pi * value) / (Double.pi * value)
    }

    private var buffer = [Float](repeating: 0, count: initialCapacity)
    private var count = 0
    private var bufferStart = -halfWidth
    private var nextOutput = 0

    init() {
        reset()
    }

    func reset() {
        buffer = [Float](repeating: 0, count: Self.initialCapacity)
        count = Self.halfWidth
        bufferStart = -Self.halfWidth
        nextOutput = 0
    }

    func process(_ input: [Int16]) -> [Int16] {
        if input.isEmpty { return [] }
        ensure(count + input.count)
        for (index, sample) in input.enumerated() {
            buffer[count + index] = Float(sample)
        }
        count += input.count

        var outputs = [Int16]()
        outputs.reserveCapacity(producible())
        while outputs.count < producible() {
            let center = nextOutput - bufferStart
            var sum: Float = 0
            for tap in 0 ..< Self.tapCount {
                sum += Self.kernel[tap] * buffer[center - Self.halfWidth + tap]
            }
            let rounded = Int(sum.rounded()).clamped(to: Int(Int16.min) ... Int(Int16.max))
            outputs.append(Int16(truncatingIfNeeded: rounded))
            nextOutput += Self.step
        }

        let keepFrom = nextOutput - bufferStart - Self.halfWidth
        if keepFrom > 0 {
            buffer.copy(from: keepFrom, to: 0, count: count - keepFrom)
            count -= keepFrom
            bufferStart += keepFrom
        }
        return outputs
    }

    private func producible() -> Int {
        let center = nextOutput - bufferStart
        let spare = count - Self.halfWidth - center
        if spare < 0 { return 0 }
        return spare / Self.step + 1
    }

    private func ensure(_ capacity: Int) {
        guard capacity > buffer.count else { return }
        var grown = buffer.count
        while grown < capacity { grown *= 2 }
        var next = [Float](repeating: 0, count: grown)
        for i in 0 ..< count { next[i] = buffer[i] }
        buffer = next
    }
}

private extension Array where Element == Float {
    mutating func copy(from: Int, to: Int, count: Int) {
        guard count > 0 else { return }
        for i in 0 ..< count {
            self[to + i] = self[from + i]
        }
    }
}

private extension Int {
    func clamped(to range: ClosedRange<Int>) -> Int {
        Swift.min(Swift.max(self, range.lowerBound), range.upperBound)
    }
}