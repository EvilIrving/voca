import Foundation

struct AzureCredential: Equatable {
    var key: String = ""
    var region: String = ""

    var configured: Bool {
        !key.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty &&
            !region.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
    }
}

protocol TtsSpeaker: AnyObject {
    func speak(_ text: String) async -> Bool
}

/// 朗读入口：只走神经网络语音模型，缺凭据或合成失败直接返回假，绝不调用系统机械音兜底。
final class Tts: TtsSpeaker, @unchecked Sendable {
    private let player: AudioPlayer
    private let credential: () -> AzureCredential

    @MainActor init(player: AudioPlayer, credential: @escaping () -> AzureCredential) {
        self.player = player
        self.credential = credential
    }

    func speak(_ text: String) async -> Bool {
        let trimmed = text.trimmingCharacters(in: .whitespacesAndNewlines)
        if trimmed.isEmpty { return false }
        let current = credential()
        if current.configured {
            let azure = AzureSpeaker(player: player, credential: current)
            if await azure.speak(trimmed) { return true }
        }
        return false
    }
}

final class AzureSpeaker: TtsSpeaker {
    private let player: AudioPlayer
    private let credential: AzureCredential

    init(player: AudioPlayer, credential: AzureCredential) {
        self.player = player
        self.credential = credential
    }

    func speak(_ text: String) async -> Bool {
        let trimmed = text.trimmingCharacters(in: .whitespacesAndNewlines)
        if trimmed.isEmpty || !credential.configured { return false }
        player.flush()
        guard let pcm = await synthesize(trimmed) else { return false }
        player.enqueue(pcm)
        return true
    }

    private func synthesize(_ text: String) async -> Data? {
        let region = credential.region.trimmingCharacters(in: .whitespacesAndNewlines).lowercased()
        guard let url = URL(string: "https://\(region).tts.speech.microsoft.com/cognitiveservices/v1") else { return nil }
        var request = URLRequest(url: url)
        request.httpMethod = "POST"
        request.setValue(credential.key.trimmingCharacters(in: .whitespacesAndNewlines), forHTTPHeaderField: "Ocp-Apim-Subscription-Key")
        request.setValue("application/ssml+xml", forHTTPHeaderField: "Content-Type")
        request.setValue(Self.outputFormat, forHTTPHeaderField: "X-Microsoft-OutputFormat")
        request.setValue("parley", forHTTPHeaderField: "User-Agent")
        request.httpBody = ssml(text).data(using: .utf8)
        request.timeoutInterval = 20

        do {
            let (data, response) = try await URLSession.shared.data(for: request)
            guard let http = response as? HTTPURLResponse, http.statusCode == 200 else { return nil }
            if data.count <= Self.wavHeader { return nil }
            return data.subdata(in: Self.wavHeader ..< data.count)
        } catch {
            return nil
        }
    }

    private func ssml(_ text: String) -> String {
        let sentences = text
            .components(separatedBy: CharacterSet.sentenceBreak)
            .map { $0.trimmingCharacters(in: .whitespacesAndNewlines) }
            .filter { !$0.isEmpty }
        let body = sentences.map { sentence in
            "<mstts:express-as style=\"chat\" styledegree=\"0.9\">\(escape(sentence))</mstts:express-as>"
        }.joined(separator: Self.pauseTag)
        return """
        <speak version="1.0" xmlns="http://www.w3.org/2001/10/synthesis" \
        xmlns:mstts="https://www.w3.org/2001/mstts" xml:lang="\(Self.language)">\
        <voice name="\(Self.voiceName)">\(body)</voice></speak>
        """
    }

    private func escape(_ text: String) -> String {
        text
            .replacingOccurrences(of: "&", with: "&amp;")
            .replacingOccurrences(of: "<", with: "&lt;")
            .replacingOccurrences(of: ">", with: "&gt;")
    }

    private static let voiceName = "en-US-AvaMultilingualNeural"
    private static let language = "zh-CN"
    private static let outputFormat = "riff-24khz-16bit-mono-pcm"
    private static let wavHeader = 44
    private static let pauseTag = "<break time=\"200ms\"/>"
}

private extension CharacterSet {
    static let sentenceBreak = CharacterSet(charactersIn: "。！？!?；;\n")
}
