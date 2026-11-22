import AVFoundation

/// PCM 四十八千赫兹单声道 → Opus 包，二十毫秒一帧，与 MediaCodec 产出形态对齐。
final class OpusEncoder {
    private let framesPerPacket: AVAudioFrameCount
    private let converter: AVAudioConverter
    private let inputFormat: AVAudioFormat

    init?(sampleRate: Double = 48_000, bitRate: Int = 32_000) {
        guard let pcm = AVAudioFormat(
            commonFormat: .pcmFormatInt16,
            sampleRate: sampleRate,
            channels: 1,
            interleaved: true,
        ) else { return nil }

        let frameDuration = 0.02
        framesPerPacket = AVAudioFrameCount(sampleRate * frameDuration)
        var outputDescription = AudioStreamBasicDescription(
            mSampleRate: sampleRate,
            mFormatID: kAudioFormatOpus,
            mFormatFlags: 0,
            mBytesPerPacket: 0,
            mFramesPerPacket: framesPerPacket,
            mBytesPerFrame: 0,
            mChannelsPerFrame: 1,
            mBitsPerChannel: 0,
            mReserved: 0,
        )
        guard let outputFormat = AVAudioFormat(streamDescription: &outputDescription),
              let conv = AVAudioConverter(from: pcm, to: outputFormat) else { return nil }
        conv.bitRate = bitRate
        converter = conv
        inputFormat = pcm
    }

    func encode(samples: [Int16]) throws -> Data? {
        guard samples.count == Int(framesPerPacket) else { return nil }
        guard let buffer = AVAudioPCMBuffer(pcmFormat: inputFormat, frameCapacity: framesPerPacket) else { return nil }
        buffer.frameLength = framesPerPacket
        samples.withUnsafeBufferPointer { src in
            guard let dst = buffer.int16ChannelData?[0] else { return }
            dst.update(from: src.baseAddress!, count: samples.count)
        }

        let compressed = AVAudioCompressedBuffer(
            format: converter.outputFormat,
            packetCapacity: 1,
            maximumPacketSize: 4_096,
        )
        var error: NSError?
        let status = converter.convert(to: compressed, error: &error) { _, outStatus in
            outStatus.pointee = .haveData
            return buffer
        }
        if status == .error, let error { throw error }
        guard compressed.byteLength > 0 else { return nil }
        return Data(bytes: compressed.data, count: Int(compressed.byteLength))
    }
}