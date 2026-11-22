import Foundation
import zlib

/// 豆包实时语音的二进制帧。
enum DoubaoProto {
    static let version = 0x1
    static let headerWords = 0x1

    static let msgFullClient = 0x1
    static let msgAudioClient = 0x2
    static let msgFullServer = 0x9
    static let msgAudioServer = 0xB
    static let msgError = 0xF

    static let flagNone = 0x0
    static let flagPositive = 0x1
    static let flagLast = 0x2
    static let flagNegative = 0x3

    static let serialRaw = 0x0
    static let serialJson = 0x1

    static let compressNone = 0x0
    static let compressGzip = 0x1
}

struct DoubaoFrame: Equatable {
    let messageType: Int
    let flags: Int
    let serialization: Int
    let compression: Int
    let event: Int
    var sessionId: String = ""
    var payload: Data = Data()
    var sequence: Int?
    var errorCode: Int?
}

enum DoubaoFrameCodec {
    private static let maxSessionId = 256

    static func request(
        messageType: Int,
        event: Int,
        sessionId: String,
        payload: Data,
        gzip: Bool = true
    ) -> Data {
        let body = gzip ? Self.gzip(payload) : payload
        var out = Data()
        out.append(
            header(
                messageType: messageType,
                flags: DoubaoProto.flagNone,
                serialization: DoubaoProto.serialJson,
                compression: gzip ? DoubaoProto.compressGzip : DoubaoProto.compressNone
            )
        )
        out.appendInt32BE(event)
        if !sessionId.isEmpty {
            let id = Data(sessionId.utf8)
            out.appendInt32BE(id.count)
            out.append(id)
        }
        out.appendInt32BE(body.count)
        out.append(body)
        return out
    }

    static func audio(event: Int, sessionId: String, pcm: Data) -> Data {
        var out = Data()
        out.append(
            header(
                messageType: DoubaoProto.msgAudioClient,
                flags: DoubaoProto.flagNone,
                serialization: DoubaoProto.serialRaw,
                compression: DoubaoProto.compressNone
            )
        )
        out.appendInt32BE(event)
        if !sessionId.isEmpty {
            let id = Data(sessionId.utf8)
            out.appendInt32BE(id.count)
            out.append(id)
        }
        out.appendInt32BE(pcm.count)
        out.append(pcm)
        return out
    }

    static func decode(_ bytes: Data) throws -> DoubaoFrame {
        guard bytes.count >= 8 else { throw DoubaoFrameError.frameTooShort }
        let headerWords = Int(bytes[0]) & 0x0F
        let messageType = (Int(bytes[1]) >> 4) & 0x0F
        let flags = Int(bytes[1]) & 0x0F
        let serialization = (Int(bytes[2]) >> 4) & 0x0F
        let compression = Int(bytes[2]) & 0x0F
        var offset = headerWords * 4
        guard offset <= bytes.count else { throw DoubaoFrameError.headerOutOfRange }

        if messageType == DoubaoProto.msgError {
            guard offset + 8 <= bytes.count else { throw DoubaoFrameError.errorFrameTooShort }
            let code = bytes.readInt32BE(at: offset)
            offset += 4
            let size = bytes.readInt32BE(at: offset)
            offset += 4
            guard size >= 0, offset + size == bytes.count else { throw DoubaoFrameError.errorPayloadSize }
            return DoubaoFrame(
                messageType: messageType,
                flags: flags,
                serialization: serialization,
                compression: compression,
                event: 0,
                payload: body(bytes, offset: offset, size: size, compression: compression),
                errorCode: code
            )
        }

        var sequence: Int?
        if messageType == DoubaoProto.msgAudioServer,
           flags == DoubaoProto.flagPositive || flags == DoubaoProto.flagNegative
        {
            guard offset + 4 <= bytes.count else { throw DoubaoFrameError.sequenceOutOfRange }
            sequence = bytes.readInt32BE(at: offset)
            offset += 4
        }

        guard offset + 4 <= bytes.count else { throw DoubaoFrameError.eventOutOfRange }
        let event = bytes.readInt32BE(at: offset)
        offset += 4
        let sessionScoped = !DoubaoEvents.connectionLevel.contains(event)
        let order = sessionScoped ? [true, false] : [false, true]
        for withSession in order {
            if let frame = attempt(
                bytes: bytes,
                start: offset,
                messageType: messageType,
                flags: flags,
                serialization: serialization,
                compression: compression,
                event: event,
                sequence: sequence,
                withSession: withSession
            ) {
                return frame
            }
        }
        throw DoubaoFrameError.badStructure(event: event, length: bytes.count)
    }

    static func gzip(_ bytes: Data) -> Data {
        guard !bytes.isEmpty else { return Data() }
        var stream = z_stream()
        var status: Int32 = Z_OK
        var output = Data()
        let chunk = 16_384
        bytes.withUnsafeBytes { input in
            stream.next_in = UnsafeMutablePointer(mutating: input.bindMemory(to: Bytef.self).baseAddress)
            stream.avail_in = uInt(bytes.count)
            status = deflateInit2_(
                &stream,
                Z_DEFAULT_COMPRESSION,
                Z_DEFLATED,
                MAX_WBITS + 16,
                8,
                Z_DEFAULT_STRATEGY,
                ZLIB_VERSION,
                Int32(MemoryLayout<z_stream>.size)
            )
            guard status == Z_OK else { return }
            defer { deflateEnd(&stream) }
            var buffer = [UInt8](repeating: 0, count: chunk)
            repeat {
                let produced: Int = buffer.withUnsafeMutableBufferPointer { buf in
                    stream.avail_out = uInt(chunk)
                    stream.next_out = buf.baseAddress
                    status = deflate(&stream, Z_FINISH)
                    guard status != Z_BUF_ERROR else { return 0 }
                    return chunk - Int(stream.avail_out)
                }
                if produced > 0 { output.append(buffer, count: produced) }
            } while status == Z_OK
        }
        return output
    }

    static func gunzip(_ bytes: Data) -> Data {
        guard !bytes.isEmpty else { return Data() }
        var stream = z_stream()
        var output = Data()
        let chunk = 16_384
        bytes.withUnsafeBytes { input in
            stream.next_in = UnsafeMutablePointer(mutating: input.bindMemory(to: Bytef.self).baseAddress)
            stream.avail_in = uInt(bytes.count)
            let status = inflateInit2_(&stream, MAX_WBITS + 16, ZLIB_VERSION, Int32(MemoryLayout<z_stream>.size))
            guard status == Z_OK else { return }
            defer { inflateEnd(&stream) }
            var buffer = [UInt8](repeating: 0, count: chunk)
            var inflateStatus: Int32 = Z_OK
            repeat {
                let produced: Int = buffer.withUnsafeMutableBufferPointer { buf in
                    stream.avail_out = uInt(chunk)
                    stream.next_out = buf.baseAddress
                    inflateStatus = inflate(&stream, Z_NO_FLUSH)
                    guard inflateStatus != Z_BUF_ERROR else { return 0 }
                    return chunk - Int(stream.avail_out)
                }
                if produced > 0 { output.append(buffer, count: produced) }
            } while inflateStatus == Z_OK
        }
        return output
    }

    private static func header(messageType: Int, flags: Int, serialization: Int, compression: Int) -> Data {
        Data([
            UInt8((DoubaoProto.version << 4) | DoubaoProto.headerWords),
            UInt8((messageType << 4) | flags),
            UInt8((serialization << 4) | compression),
            0x00,
        ])
    }

    private static func body(_ bytes: Data, offset: Int, size: Int, compression: Int) -> Data {
        if size == 0 { return Data() }
        let raw = bytes.subdata(in: offset..<(offset + size))
        return compression == DoubaoProto.compressGzip ? gunzip(raw) : raw
    }

    private static func attempt(
        bytes: Data,
        start: Int,
        messageType: Int,
        flags: Int,
        serialization: Int,
        compression: Int,
        event: Int,
        sequence: Int?,
        withSession: Bool
    ) -> DoubaoFrame? {
        var offset = start
        var sessionId = ""
        if withSession {
            guard offset + 4 <= bytes.count else { return nil }
            let length = bytes.readInt32BE(at: offset)
            if length < 0 || length > maxSessionId { return nil }
            if offset + 4 + length + 4 > bytes.count { return nil }
            sessionId = String(data: bytes.subdata(in: (offset + 4)..<(offset + 4 + length)), encoding: .utf8) ?? ""
            offset += 4 + length
        }
        guard offset + 4 <= bytes.count else { return nil }
        let size = bytes.readInt32BE(at: offset)
        offset += 4
        if size < 0 || offset + size != bytes.count { return nil }
        return DoubaoFrame(
            messageType: messageType,
            flags: flags,
            serialization: serialization,
            compression: compression,
            event: event,
            sessionId: sessionId,
            payload: body(bytes, offset: offset, size: size, compression: compression),
            sequence: sequence
        )
    }
}

enum DoubaoFrameError: Error {
    case frameTooShort
    case headerOutOfRange
    case errorFrameTooShort
    case errorPayloadSize
    case sequenceOutOfRange
    case eventOutOfRange
    case badStructure(event: Int, length: Int)
}

private extension Data {
    mutating func appendInt32BE(_ value: Int) {
        var v = UInt32(truncatingIfNeeded: value).bigEndian
        append(Data(bytes: &v, count: 4))
    }

    func readInt32BE(at offset: Int) -> Int {
        guard offset + 4 <= count else { return 0 }
        let b0 = Int(self[offset]) << 24
        let b1 = Int(self[offset + 1]) << 16
        let b2 = Int(self[offset + 2]) << 8
        let b3 = Int(self[offset + 3])
        return b0 | b1 | b2 | b3
    }
}