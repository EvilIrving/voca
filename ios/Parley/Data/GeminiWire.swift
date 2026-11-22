import Foundation

/// BidiGenerateContent 的线上报文。字段名取自 v1beta 的官方 proto
/// (google/ai/generativelanguage/v1beta/generative_service.proto) 与 discovery 文档，
enum GeminiWire {
    static func setup(
        model: String,
        voice: String,
        systemPrompt: String,
        resumeHandle: String?,
        seeding: Bool
    ) -> [String: Any] {
        var setup: [String: Any] = [
            "model": "models/\(model)",
            "generationConfig": [
                "responseModalities": ["AUDIO"],
                "speechConfig": [
                    "voiceConfig": [
                        "prebuiltVoiceConfig": ["voiceName": voice],
                    ],
                ],
            ],
            "systemInstruction": [
                "parts": [["text": systemPrompt]],
            ],
            "realtimeInputConfig": [
                "automaticActivityDetection": ["disabled": true],
            ],
            "inputAudioTranscription": [String: Any](),
            "outputAudioTranscription": [String: Any](),
        ]
        var resumption: [String: Any] = [:]
        if let resumeHandle { resumption["handle"] = resumeHandle }
        setup["sessionResumption"] = resumption
        if seeding {
            setup["historyConfig"] = ["initialHistoryInClientContent": true]
        }
        return ["setup": setup]
    }

    static func activityStart() -> [String: Any] {
        ["realtimeInput": ["activityStart": [String: Any]()]]
    }

    static func activityEnd() -> [String: Any] {
        ["realtimeInput": ["activityEnd": [String: Any]()]]
    }

    static func audioStreamEnd() -> [String: Any] {
        ["realtimeInput": ["audioStreamEnd": true]]
    }

    static func audio(base64: String) -> [String: Any] {
        [
            "realtimeInput": [
                "audio": [
                    "data": base64,
                    "mimeType": "audio/pcm;rate=16000",
                ],
            ],
        ]
    }

    static func clientContent(text: String) -> [String: Any] {
        [
            "clientContent": [
                "turns": [
                    [
                        "role": "user",
                        "parts": [["text": text]],
                    ],
                ],
                "turnComplete": true,
            ],
        ]
    }
}