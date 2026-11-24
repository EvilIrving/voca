package com.parley.speaker.data.realtime.qwen

import android.util.Log
import com.parley.speaker.data.realtime.Pcm
import com.parley.speaker.data.realtime.ProviderFault
import com.parley.speaker.data.realtime.RealtimeEvent
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
 * 千问实时语音（Qwen-Audio-Realtime）的线上报文。事件名与 OpenAI Realtime 同源，
 * 区别在三处：上行固定十六千赫兹裸 PCM 不用重采样、turn_detection 置 null 就是手动回合制、
 * 服务端不认 resumption 之类的续接句柄，断线之后靠重灌文本历史接上。
 *
 * 端点用老域名 dashscope.aliyuncs.com：官方另推了 {WorkspaceId}.cn-beijing.maas.aliyuncs.com 这层
 * 业务空间专属域名，两套并存且老域名仍可正常调用，留着老域名，设置页那一行就还是密钥与音色两格，
 * 不必多填一个业务空间 ID。
 */
internal object QwenWire {

    const val ENDPOINT = "wss://dashscope.aliyuncs.com/api-ws/v1/realtime"

    const val UPLINK_RATE = 16_000
    const val DOWNLINK_RATE = 24_000

    /**
     * 单次推理参考的历史 QA 轮数，默认 20。半小时的密集对练有三十到四十个来回，
     * 默认值会把开头挤出参考范围，这里拉到上限；音频另有 300 秒与 50 轮的上限，撞到之前先丢声音不丢文本。
     */
    const val HISTORY_TURNS = 50

    /**
     * 一次会话只发这一条 session.update：音色只认第一次下发的值，turn_detection 也只在首次发送音频之前可改，
     * 分开补发反而会被忽略。instructions 带角色提示词，转写语种留空按自动判定走，
     * 中英混说是这条链路的常态，钉死 en 会把插入的中文听成英文。
     */
    fun sessionUpdate(voice: String, instructions: String): JsonObject = buildJsonObject {
        put("type", "session.update")
        putJsonObject("session") {
            putJsonArray("modalities") {
                add("audio")
                add("text")
            }
            put("voice", voice)
            if (instructions.isNotBlank()) put("instructions", instructions)
            // 这一格就是关掉服务端断句，长按说话的整条链路靠它，写成空对象服务端照旧自己判停。
            put("turn_detection", JsonNull)
            put("max_history_turns", HISTORY_TURNS)
        }
    }

    fun appendAudio(base64: String): JsonObject = buildJsonObject {
        put("type", "input_audio_buffer.append")
        put("audio", base64)
    }

    /** 松手时先 commit 把这段音频封成一条用户消息，再 create 让模型开口，两条缺一不可。 */
    fun commit(): JsonObject = buildJsonObject {
        put("type", "input_audio_buffer.commit")
    }

    /** 作废这一轮：缓冲区里还没提交的音频要清掉，否则会跟着下一轮的 commit 一起被当成用户发言。 */
    fun clearBuffer(): JsonObject = buildJsonObject {
        put("type", "input_audio_buffer.clear")
    }

    fun createResponse(): JsonObject = buildJsonObject {
        put("type", "response.create")
    }

    fun cancelResponse(): JsonObject = buildJsonObject {
        put("type", "response.cancel")
    }

    /**
     * 一条对话项。续接与打字都走这一条：角色决定 content 的类型，
     * 用户与系统用 input_text，助手用 output_text，服务端按键名校验，填错直接回无效参数。
     */
    fun message(role: String, text: String): JsonObject = buildJsonObject {
        put("type", "conversation.item.create")
        putJsonObject("item") {
            put("type", "message")
            put("role", role)
            putJsonArray("content") {
                add(
                    buildJsonObject {
                        put("type", if (role == "assistant") "output_text" else "input_text")
                        put("text", text)
                    },
                )
            }
        }
    }

    /**
     * 一条服务端消息带出来的东西。event 为空表示这条消息只是携带响应号或只是回执，
     * 界面上没有对应动作。cancelled 只在 response.done 上出现，会话据此不发 TurnComplete。
     */
    data class Parsed(
        val event: RealtimeEvent? = null,
        val responseId: String? = null,
        val cancelled: Boolean = false,
        val failed: Boolean = false,
    )

    private val json = Json { ignoreUnknownKeys = true }

    fun parse(text: String): Parsed? {
        val root = try {
            json.parseToJsonElement(text).jsonObject
        } catch (e: Exception) {
            Log.w("QwenWire", "非 JSON 的文本帧：${text.take(200)}")
            return null
        }
        val type = root["type"]?.jsonPrimitive?.contentOrNull ?: return null
        val responseId = root["response_id"]?.jsonPrimitive?.contentOrNull
            ?: root["response"]?.let { runCatching { it.jsonObject["id"]?.jsonPrimitive?.contentOrNull }.getOrNull() }

        return when (type) {
            "session.created", "session.updated" -> Parsed(RealtimeEvent.Ready)

            "response.created" -> Parsed(responseId = responseId)

            // 音频是 Base64 的二十四千赫兹裸 PCM，坏一块只丢这一块，不能把异常抛给解析层。
            "response.audio.delta" -> {
                val delta = root["delta"]?.jsonPrimitive?.contentOrNull ?: return null
                val pcm = runCatching { Pcm.decodeBase64(delta) }.getOrNull() ?: return null
                Parsed(RealtimeEvent.Audio(pcm), responseId)
            }

            // 字幕取 audio_transcript：response.text.delta 是纯文本模式那一路，两条一起收会把同一句叠两遍。
            "response.audio_transcript.delta" -> {
                val delta = root["delta"]?.jsonPrimitive?.contentOrNull ?: return null
                Parsed(RealtimeEvent.AssistantText(delta), responseId)
            }

            // 用户转写只在 completed 取一次整句，delta 是同一句的增量。
            "conversation.item.input_audio_transcription.completed" -> {
                val transcript = root["transcript"]?.jsonPrimitive?.contentOrNull ?: return null
                Parsed(RealtimeEvent.UserText(transcript))
            }

            // 转写失败不另造文案：这一轮没出字，本地的静音判定与重说都还在。
            "conversation.item.input_audio_transcription.failed" -> {
                Log.w("QwenWire", "转写失败")
                null
            }

            "response.done" -> {
                val response = root["response"]?.let { runCatching { it.jsonObject }.getOrNull() }
                val status = response?.get("status")?.jsonPrimitive?.contentOrNull
                if (status == "failed") {
                    Log.w("QwenWire", "推理失败：${text.take(300)}")
                }
                Parsed(
                    event = if (status == "failed") RealtimeEvent.Failed("") else RealtimeEvent.TurnComplete,
                    responseId = responseId,
                    cancelled = status == "cancelled",
                    failed = status == "failed",
                )
            }

            "error" -> faultOf(root)
            else -> null
        }
    }

    /**
     * 错误原文进日志，界面只拿归类之后那一句。千问这家的错误码自成一套，
     * 认得出的先按它的码归，归不进类别就交给共用的关键词表，再认不出就是空串、界面不出声。
     */
    private fun faultOf(root: JsonObject): Parsed? {
        val error = root["error"]?.let { runCatching { it.jsonObject }.getOrNull() }
        val code = error?.get("code")?.jsonPrimitive?.contentOrNull
        val message = error?.get("message")?.jsonPrimitive?.contentOrNull.orEmpty()
        val param = error?.get("param")?.jsonPrimitive?.contentOrNull
        Log.w("QwenWire", "服务端报错 code=$code param=$param ${message.take(200)}")
        // 没有正在生成的响应时发取消会被回一句 invalid_value，实测原话是 Conversation has no active response，
        // param 点名 response.cancel。这是竞态不是故障，认成故障会把正在跑的连接当成断线重连。
        if (param == "response.cancel" || message.contains("no active response", ignoreCase = true)) {
            return null
        }
        val byCode = when (code?.lowercase()) {
            "arrearage", "insufficient_quota", "quotaexceeded" -> ProviderFault.Quota
            "throttling", "throttling.ratequota", "limit_exceeded" -> ProviderFault.Busy
            "invalidapikey", "unauthorized" -> ProviderFault.Key
            "modelnotfound", "invalidparameter" -> ProviderFault.Config
            else -> ""
        }
        if (byCode.isNotEmpty()) return Parsed(RealtimeEvent.Failed(byCode))
        return Parsed(RealtimeEvent.Failed(ProviderFault.notice(code, message)))
    }
}
