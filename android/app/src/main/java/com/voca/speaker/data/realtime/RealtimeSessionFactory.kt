package com.voca.speaker.data.realtime

import com.voca.speaker.data.realtime.doubao.DoubaoRealtimeSession
import com.voca.speaker.data.realtime.gemini.GeminiLiveSession
import com.voca.speaker.data.realtime.openai.OpenAiRealtimeSession
import com.voca.speaker.data.realtime.qwen.QwenRealtimeSession
import com.voca.speaker.domain.DialogTurn
import com.voca.speaker.domain.LiveSettings
import com.voca.speaker.domain.LiveVendor

/**
 * 实时语音会话工厂：根据设置里的供应商与凭据创建对应适配器，
 * 业务层只认 [RealtimeSession] 接口，不感知具体供应商分支与参数拼装。
 */
object RealtimeSessionFactory {

    fun create(
        live: LiveSettings,
        instructions: String,
        history: String?,
        context: List<DialogTurn>,
        onEvent: (RealtimeEvent) -> Unit,
    ): RealtimeSession? {
        val vendor = live.provider ?: return null
        if (!live.ready(vendor)) return null
        val tuning = live.tuningOf(vendor)
        return when (vendor) {
            LiveVendor.Gemini -> GeminiLiveSession(
                apiKey = live.geminiKey,
                model = tuning.model,
                voice = tuning.voice,
                systemPrompt = instructions,
                onEvent = onEvent,
                history = history,
            )

            LiveVendor.OpenAi -> OpenAiRealtimeSession(
                apiKey = live.openAiKey,
                model = tuning.model,
                voice = tuning.voice,
                instructions = instructions,
                onEvent = onEvent,
                history = history,
            )

            LiveVendor.Doubao -> DoubaoRealtimeSession(
                apiKey = live.doubaoApiKey,
                voice = tuning.voice,
                systemPrompt = instructions,
                onEvent = onEvent,
                context = context,
            )

            LiveVendor.Qwen -> QwenRealtimeSession(
                apiKey = live.qwenApiKey,
                model = tuning.model,
                voice = tuning.voice,
                instructions = instructions,
                onEvent = onEvent,
                context = context,
            )
        }
    }
}
