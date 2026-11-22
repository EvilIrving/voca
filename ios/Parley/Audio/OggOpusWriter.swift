import Foundation

/// RFC 3533 OGG 封装，OpusHead / OpusTags 后接数据页；与 Android 字节布局一致。
final class OggOpusWriter {
    private let out: FileHandle
    private let channels: Int
    private let inputRate: Int
    private let preSkip: Int

    private var sequence = 0
    private var lastGranule: Int64 = 0
    private var headersWritten = false

    init(fileURL: URL, channels: Int = 1, inputRate: Int = 48_000, preSkip: Int = 0) throws {
        FileManager.default.createFile(atPath: fileURL.path, contents: nil)
        guard let handle = FileHandle(forWritingAtPath: fileURL.path) else {
            throw NSError(domain: "OggOpusWriter", code: 1)
        }
        self.out = handle
        self.channels = channels
        self.inputRate = inputRate
        self.preSkip = preSkip
    }

    func writeHeaders() throws {
        if headersWritten { return }
        headersWritten = true

        var head = [UInt8](repeating: 0, count: 19)
        Self.magicHead.withUnsafeBytes { head.replaceSubrange(0 ..< 8, with: $0) }
        head[8] = 1
        head[9] = UInt8(channels)
        Self.putShort(&head, 10, preSkip)
        Self.putInt(&head, 12, inputRate)
        Self.putShort(&head, 16, 0)
        head[18] = 0
        try writePage(headerType: Self.flagBOS, granule: 0, body: head, lacing: [UInt8(head.count)])

        let vendor = Array(Self.vendor.utf8)
        var tags = [UInt8](repeating: 0, count: 8 + 4 + vendor.count + 4)
        Self.magicTags.withUnsafeBytes { tags.replaceSubrange(0 ..< 8, with: $0) }
        Self.putInt(&tags, 8, vendor.count)
        for (i, byte) in vendor.enumerated() {
            tags[12 + i] = byte
        }
        Self.putInt(&tags, 12 + vendor.count, 0)
        try writePage(headerType: Self.flagNone, granule: 0, body: tags, lacing: [UInt8(tags.count)])
    }

    func writePacket(_ packet: Data, granulePosition: Int64) throws {
        lastGranule = granulePosition
        let packetBytes = [UInt8](packet)
        let segments = packetBytes.count / 255 + 1
        var segmentIndex = 0
        var offset = 0
        var continued = false
        while segmentIndex < segments {
            let take = min(segments - segmentIndex, Self.maxSegments)
            var lacing = [UInt8]()
            lacing.reserveCapacity(take)
            var bodySize = 0
            for _ in 0 ..< take {
                let value = min(255, packetBytes.count - offset)
                lacing.append(UInt8(value))
                offset += value
                bodySize += value
            }
            let bodyStart = offset - bodySize
            let body = Array(packetBytes[bodyStart ..< offset])
            let endsPacket = segmentIndex + take >= segments
            try writePage(
                headerType: continued ? Self.flagContinued : Self.flagNone,
                granule: endsPacket ? granulePosition : Self.noGranule,
                body: body,
                lacing: lacing,
            )
            segmentIndex += take
            continued = !endsPacket
        }
    }

    func finish() throws {
        try writePage(headerType: Self.flagEOS, granule: lastGranule, body: [], lacing: [])
        try out.synchronize()
    }

    func close() {
        try? out.close()
    }

    private func writePage(headerType: Int, granule: Int64, body: [UInt8], lacing: [UInt8]) throws {
        var header = [UInt8](repeating: 0, count: Self.pageHeaderBytes + lacing.count)
        Self.magicPage.withUnsafeBytes { header.replaceSubrange(0 ..< 4, with: $0) }
        header[4] = 0
        header[5] = UInt8(headerType)
        Self.putLong(&header, 6, granule)
        Self.putInt(&header, 14, Int(bitPattern: UInt(Self.serial)))
        Self.putInt(&header, 18, sequence)
        Self.putInt(&header, 22, 0)
        header[26] = UInt8(lacing.count)
        for (i, byte) in lacing.enumerated() {
            header[Self.pageHeaderBytes + i] = byte
        }

        var page = header
        page.append(contentsOf: body)
        Self.putInt(&page, 22, Int(bitPattern: UInt(Self.crc(page))))
        try out.write(contentsOf: page)
        sequence += 1
    }

    private static func crc(_ bytes: [UInt8]) -> UInt32 {
        var value: UInt32 = 0
        for byte in bytes {
            let idx = Int(((value >> 24) ^ UInt32(byte)) & 0xFF)
            value = (value << 8) ^ table[idx]
        }
        return value
    }

    private static let magicPage: [UInt8] = [0x4F, 0x67, 0x67, 0x53]
    private static let magicHead = Array("OpusHead".utf8)
    private static let magicTags = Array("OpusTags".utf8)
    private static let vendor = "parley"

    private static let pageHeaderBytes = 27
    private static let maxSegments = 255
    private static let flagNone = 0x00
    private static let flagContinued = 0x01
    private static let flagBOS = 0x02
    private static let flagEOS = 0x04
    private static let noGranule: Int64 = -1
    private static let serial: UInt32 = UInt32(truncatingIfNeeded: 0x706_1726)

    private static let table: [UInt32] = (0 ..< 256).map { index in
        var register = UInt32(index) << 24
        for _ in 0 ..< 8 {
            if register & UInt32(bitPattern: Int32.min) != 0 {
                register = (register << 1) ^ 0x04C1_1DB7
            } else {
                register <<= 1
            }
        }
        return register
    }

    private static func putShort(_ target: inout [UInt8], _ offset: Int, _ value: Int) {
        target[offset] = UInt8(value & 0xFF)
        target[offset + 1] = UInt8((value >> 8) & 0xFF)
    }

    private static func putInt(_ target: inout [UInt8], _ offset: Int, _ value: Int) {
        for index in 0 ..< 4 {
            target[offset + index] = UInt8((value >> (index * 8)) & 0xFF)
        }
    }

    private static func putLong(_ target: inout [UInt8], _ offset: Int, _ value: Int64) {
        for index in 0 ..< 8 {
            target[offset + index] = UInt8((value >> (index * 8)) & 0xFF)
        }
    }
}