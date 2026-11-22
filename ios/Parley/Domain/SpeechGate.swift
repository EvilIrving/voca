import Foundation

enum SpeechGate {
    static let frameMs: Int64 = 20
    static let minSpeechMs: Int64 = 400
    static let floorMarginDb = 6.0
    private static let floorPercentile = 0.2
    private static let fullScale = 32768.0
    private static let silenceDb = -120.0

    static func frameDb(_ samples: [Int16]) -> Double {
        if samples.isEmpty { return silenceDb }
        var sum = 0.0
        for sample in samples {
            let value = Double(sample)
            sum += value * value
        }
        let rms = sqrt(sum / Double(samples.count))
        return 20.0 * log10(max(rms, 1.0) / fullScale)
    }

    static func noiseFloor(_ framesDb: [Double]) -> Double {
        if framesDb.isEmpty { return silenceDb }
        let sorted = framesDb.sorted()
        let index = Int(Double(sorted.count - 1) * floorPercentile)
        return sorted[index]
    }

    static func voicedMs(_ framesDb: [Double], floorDb: Double) -> Int64 {
        let bar = floorDb + floorMarginDb
        return Int64(framesDb.filter { $0 >= bar }.count) * frameMs
    }

    static func shouldDrop(durationMs: Int64, peakDb: Double, floorDb: Double) -> Bool {
        durationMs < minSpeechMs || peakDb < floorDb + floorMarginDb
    }

    static func shouldDrop(_ framesDb: [Double]) -> Bool {
        if framesDb.isEmpty { return true }
        let floor = noiseFloor(framesDb)
        return Int64(framesDb.count) * frameMs < minSpeechMs || voicedMs(framesDb, floorDb: floor) < minSpeechMs
    }
}
