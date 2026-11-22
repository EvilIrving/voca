package com.parley.speaker.data.realtime.gemini

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * BidiGenerateContent 的线上报文。字段名取自 v1beta 的官方 proto
 * (google/ai/generativelanguage/v1beta/generative_service.proto) 与 discovery 文档，
 * 搬运之外的逻辑不写在这一层，方便单独核对报文形状。
 */
internal object GeminiWire {

    /**
     * sessionResumption 只要出现，服务端就会推送 SessionResumptionUpdate；不带 handle 表示开新会话。
     * historyConfig.initialHistoryInClientContent 让服务端在 setupComplete 之后先吃 clientContent，
     * 这段历史不触发模型调用，吃完 turnComplete 才转入实时对话。
     */
    fun setup(
        model: String,
        voice: String,
        systemPrompt: String,
        resumeHandle: String?,
        seeding: Boolean,
    ): JsonObject = buildJsonObject {
        putJsonObject("setup") {
            put("model", "models/$model")
            putJsonObject("generationConfig") {
                putJsonArray("responseModalities") { add("AUDIO") }
                putJsonObject("speechConfig") {
                    putJsonObject("voiceConfig") {
                        putJsonObject("prebuiltVoiceConfig") {
                            put("voiceName", voice)
                        }
                    }
                }
            }
            putJsonObject("systemInstruction") {
                putJsonArray("parts") { add(buildJsonObject { put("text", systemPrompt) }) }
            }
            putJsonObject("realtimeInputConfig") {
                putJsonObject("automaticActivityDetection") { put("disabled", true) }
            }
            putJsonObject("inputAudioTranscription") {}
            putJsonObject("outputAudioTranscription") {}
            putJsonObject("sessionResumption") {
                if (resumeHandle != null) put("handle", resumeHandle)
            }
            if (seeding) {
                putJsonObject("historyConfig") { put("initialHistoryInClientContent", true) }
            }
        }
    }

    fun activityStart() = buildJsonObject {
        putJsonObject("realtimeInput") { putJsonObject("activityStart") {} }
    }

    /** 收尾但不开生成：不先发 audioStreamEnd 的话，服务端不把这段音频送去推理。 */
    fun activityEnd() = buildJsonObject {
        putJsonObject("realtimeInput") { putJsonObject("activityEnd") {} }
    }

    /** 服务端要求这个字段显式传 true，传空对象会被拒。 */
    fun audioStreamEnd() = buildJsonObject {
        putJsonObject("realtimeInput") { put("audioStreamEnd", true) }
    }

    fun audio(base64: String) = buildJsonObject {
        putJsonObject("realtimeInput") {
            putJsonObject("audio") {
                put("data", base64)
                put("mimeType", "audio/pcm;rate=16000")
            }
        }
    }

    /** turnComplete=true 才会让模型开口，开场白与打字输入走这条，历史注入也靠它收尾。 */
    fun clientContent(text: String) = buildJsonObject {
        putJsonObject("clientContent") {
            putJsonArray("turns") {
                add(buildJsonObject {
                    put("role", "user")
                    putJsonArray("parts") { add(buildJsonObject { put("text", text) }) }
                })
            }
            put("turnComplete", true)
        }
    }
}
