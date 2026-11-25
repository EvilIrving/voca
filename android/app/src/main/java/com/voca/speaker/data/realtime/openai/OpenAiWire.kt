package com.voca.speaker.data.realtime.openai

import android.util.Log
import com.voca.speaker.data.realtime.Pcm
import com.voca.speaker.data.realtime.ProviderFault
import com.voca.speaker.data.realtime.RealtimeEvent
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * OpenAI Realtime 的线上报文。字段名取自现行 GA 的会话结构：audio.input / audio.output 两段，
 * 格式写成 {type: audio/pcm, rate: 24000}。早期 beta 那套 input_audio_format: "pcm16" 的平铺写法已经不同，
 * 真机握手时若 session.update 被拒，多半是服务端还在认旧结构，改这一层即可。
 *
 * 手动回合制靠 turn_detection 置 null：服务端不再自己断句，松手时由客户端 commit 再 create。
 * 输入也按二十四千赫兹声明，上行帧因此在适配器里从十六千赫兹重采样过来。
 */
internal object OpenAiWire {

    /**
     * 现行文档另给了一个不带查询参数的入口 `wss://api.openai.com/v1/live/sessions`，
     * 两家说法并存，这里先按经典入口连，真机握手确认之后再决定要不要换。
     */
    const val ENDPOINT = "wss://api.openai.com/v1/realtime"

    /** 输入输出都用裸 PCM 二十四千赫兹单声道十六位。 */
    const val PCM_RATE = 24_000

    /** 转写走便宜的流式小模型，中文英文都吃得下。 */
    const val TRANSCRIPTION_MODEL = "gpt-4o-mini-transcribe"

    fun sessionUpdate(model: String, voice: String, instructions: String): JsonObject = buildJsonObject {
        put("type", "session.update")
        putJsonObject("session") {
            put("type", "realtime")
            put("model", model)
            put("instructions", instructions)
            putJsonArray("output_modalities") { add("audio") }
            putJsonObject("audio") {
                putJsonObject("input") {
                    putJsonObject("format") {
                        put("type", "audio/pcm")
                        put("rate", PCM_RATE)
                    }
                    // 置 null 就是关掉服务端断句，这是长按说话的整条链路的根。
                    put("turn_detection", JsonNull)
                    putJsonObject("transcription") { put("model", TRANSCRIPTION_MODEL) }
                }
                putJsonObject("output") {
                    putJsonObject("format") {
                        put("type", "audio/pcm")
                        put("rate", PCM_RATE)
                    }
                    put("voice", voice)
                }
            }
        }
    }

    fun appendAudio(base64: String): JsonObject = buildJsonObject {
        put("type", "input_audio_buffer.append")
        put("audio", base64)
    }

    /** 松手时先 commit 把这段音频封口，再 create 让模型开口，两条缺一不可。 */
    fun commit(): JsonObject = buildJsonObject {
        put("type", "input_audio_buffer.commit")
    }

    fun createResponse(): JsonObject = buildJsonObject {
        put("type", "response.create")
    }

    /** 取消：让服务端别再生成。它不保证已经发出去的那几块音频不回来，本地还要按 turnId 丢。 */
    fun cancelResponse(): JsonObject = buildJsonObject {
        put("type", "response.cancel")
    }

    /** 打字输入：先落一条用户消息，再让模型开口。 */
    fun userText(text: String): JsonObject = buildJsonObject {
        put("type", "conversation.item.create")
        putJsonObject("item") {
            put("type", "message")
            put("role", "user")
            putJsonArray("content") {
                add(buildJsonObject {
                    put("type", "input_text")
                    put("text", text)
                })
            }
        }
    }

    /**
     * 一条服务端消息带出来的东西。event 为空表示这条消息只是携带响应号，界面上没有对应动作。
     * cancelled 只在 response.done 上出现，会话据此不发 TurnComplete。
     */
    data class Parsed(
        val event: RealtimeEvent?,
        val responseId: String? = null,
        val cancelled: Boolean = false,
    )

    private val json = Json { ignoreUnknownKeys = true }

    fun parse(text: String): Parsed? {
        val root = try {
            json.parseToJsonElement(text).jsonObject
        } catch (e: Exception) {
            return null
        }
        val type = root["type"]?.jsonPrimitive?.contentOrNull ?: return null
        val responseId = root["response_id"]?.jsonPrimitive?.contentOrNull
            ?: root["response"]?.asObjectOrNull()?.get("id")?.jsonPrimitive?.contentOrNull

        return when (type) {
            "session.created", "session.updated" -> Parsed(RealtimeEvent.Ready)

            // 响应号先挂上，取消之后才能认得出哪些块属于被取消那轮。
            "response.created" -> Parsed(event = null, responseId = responseId)

            "response.audio.delta", "response.output_audio.delta" -> {
                val delta = root["delta"]?.jsonPrimitive?.contentOrNull ?: return null
                // 坏 base64 只丢这一块，不能把异常抛给解析层把整条连接带下去。
                val pcm = try {
                    Pcm.decodeBase64(delta)
                } catch (e: Exception) {
                    return null
                }
                Parsed(RealtimeEvent.Audio(pcm), responseId)
            }

            "response.audio_transcript.delta", "response.output_audio_transcript.delta" -> {
                val delta = root["delta"]?.jsonPrimitive?.contentOrNull ?: return null
                Parsed(RealtimeEvent.AssistantText(delta), responseId)
            }

            // 输入转写用 completed 这一条给整段文本；delta 是同一段的增量，一起收会重复。
            "conversation.item.input_audio_transcription.completed" -> {
                val transcript = root["transcript"]?.jsonPrimitive?.contentOrNull ?: return null
                Parsed(RealtimeEvent.UserText(transcript))
            }

            "conversation.item.input_audio_transcription.failed" ->
                Parsed(RealtimeEvent.Failed("没有听清，请重说"))

            "response.done" -> {
                val status = root["response"]?.asObjectOrNull()?.get("status")?.jsonPrimitive?.contentOrNull
                Parsed(RealtimeEvent.TurnComplete, responseId, cancelled = status == "cancelled")
            }

            "error" -> {
                val error = root["error"]?.asObjectOrNull()
                val message = error?.get("message")?.jsonPrimitive?.contentOrNull ?: "服务端返回错误"
                val code = error?.get("code")?.jsonPrimitive?.contentOrNull
                val type = error?.get("type")?.jsonPrimitive?.contentOrNull
                // 没有正在生成的响应时取消会被回一句错，这不是故障。
                if (message.contains("no active response")) {
                    null
                } else {
                    Log.w("OpenAiRealtime", "server error $code $type ${message.take(200)}")
                    Parsed(RealtimeEvent.Failed(ProviderFault.notice(code, type, message)))
                }
            }

            else -> null
        }
    }

    private fun kotlinx.serialization.json.JsonElement.asObjectOrNull(): JsonObject? =
        runCatching { jsonObject }.getOrNull()
}
