import Foundation

/// 适配层里反复要做的四件事：短整型与字节互转、重采样、Base64。
/// 全部是纯逻辑，单测直接跑，不需要真机也不需要密钥。
///
/// 上行采集一律十六千赫兹二十毫秒一帧（三百二十个采样），下行一律二十四千赫兹。
enum Pcm {
    static let uplinkRate = 16_000
    static let downlinkRate = 24_000

  private static let minSample = -32_768
  private static let maxSample = 32_767

    static func shortsToBytes(_ samples: [Int16]) -> Data {
        var bytes = Data(count: samples.count * 2)
        bytes.withUnsafeMutableBytes { raw in
            let ptr = raw.bindMemory(to: UInt8.self)
            for i in samples.indices {
                let value = Int(samples[i])
                ptr[i * 2] = UInt8(value & 0xFF)
                ptr[i * 2 + 1] = UInt8((value >> 8) & 0xFF)
            }
        }
        return bytes
    }

    static func bytesToShorts(_ bytes: Data) -> [Int16] {
        let count = bytes.count / 2
        return bytes.withUnsafeBytes { raw in
            let ptr = raw.bindMemory(to: UInt8.self)
            return (0..<count).map { i in
                let low = Int(ptr[i * 2])
                let high = Int(ptr[i * 2 + 1]) << 8
                return Int16(truncatingIfNeeded: low | high)
            }
        }
    }

    static func resample(_ samples: [Int16], fromRate: Int, toRate: Int) -> [Int16] {
        precondition(fromRate > 0 && toRate > 0, "采样率必须为正")
        if samples.isEmpty || fromRate == toRate { return samples }
        let size = Int(Double(samples.count) * Double(toRate) / Double(fromRate))
        var out = [Int16]()
        out.reserveCapacity(size)
        for i in 0..<size {
            let position = Double(i) * Double(fromRate) / Double(toRate)
            let index = Int(position)
            let fraction = position - Double(index)
            let left = Double(samples[index])
            let right = index + 1 < samples.count ? Double(samples[index + 1]) : left
            let v = (left + (right - left) * fraction).rounded()
            out.append(Int16(clamping: Int(v).clamped(to: minSample...maxSample)))
        }
        return out
    }

    static func resampleToDownlink(_ samples: [Int16]) -> [Int16] {
        resample(samples, fromRate: uplinkRate, toRate: downlinkRate)
    }

    static func uplinkFrameToDownlink(_ frame: [Int16]) -> Data {
        shortsToBytes(resampleToDownlink(frame))
    }

    static func encodeBase64(_ bytes: Data) -> String {
        bytes.base64EncodedString()
    }

    static func decodeBase64(_ text: String) throws -> Data {
        let cleaned = text.filter { !$0.isWhitespace }
        guard let data = Data(base64Encoded: cleaned, options: .ignoreUnknownCharacters) else {
            throw PcmError.badBase64
        }
        return data
    }
}

enum PcmError: Error {
    case badBase64
}

private extension Int {
    func clamped(to range: ClosedRange<Int>) -> Int {
        Swift.min(Swift.max(self, range.lowerBound), range.upperBound)
    }
}