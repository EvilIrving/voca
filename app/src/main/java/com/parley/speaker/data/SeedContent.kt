package com.parley.speaker.data

import com.parley.speaker.domain.ProviderLine
import com.parley.speaker.domain.VocabCard
import com.parley.speaker.domain.VocabGroup

object SeedContent {

    const val DefaultPrompt: String =
        "你是 Parley，一位语速自然、情绪饱满的母语者陪练。始终用英文口语回应，即使对方说了别的语言也把话题带回英文；一次只问一个问题，不讲解语法，也不在语音里给改写建议，课程反馈由后台单独处理。"

    val Scenarios = listOf("随便聊", "点单与购物", "面试热身", "雅思口语", "出差问路")

    fun vocabGroups(): List<VocabGroup> = listOf(
        VocabGroup(
            label = "今天",
            cards = listOf(
                VocabCard(
                    id = "v1",
                    word = "slept badly",
                    phonetic = "/slept ˈbædli/",
                    meaning = "睡得不好，睡眠质量差",
                    example = "I got up early, but I slept badly — so a hot Americano is exactly what I need.",
                    highlight = "slept badly",
                    repeats = 3,
                ),
                VocabCard(
                    id = "v2",
                    word = "crave",
                    phonetic = "/kreɪv/",
                    meaning = "强烈渴望，嘴馋",
                    example = "Are you craving an Americano or something else?",
                    highlight = "craving",
                    repeats = 2,
                ),
            ),
        ),
        VocabGroup(
            label = "昨天",
            cards = listOf(
                VocabCard(
                    id = "v3",
                    word = "keep going back to",
                    phonetic = "/kiːp ˈɡoʊɪŋ bæk tuː/",
                    meaning = "反复回到，常去常选",
                    example = "Is there a cafe you keep going back to?",
                    highlight = "keep going back to",
                    repeats = 1,
                ),
            ),
        ),
    )

    fun realtimeProviders(apiKeyPresent: Boolean): List<ProviderLine> = listOf(
        ProviderLine(
            name = "Gemini Live",
            detail = "gemini-3.8-live",
            status = if (apiKeyPresent) com.parley.speaker.domain.LinkStatus.Configured else com.parley.speaker.domain.LinkStatus.Unconfigured,
        ),
        ProviderLine(
            name = "OpenAI Realtime",
            detail = "gpt-realtime",
            status = com.parley.speaker.domain.LinkStatus.Unconfigured,
        ),
        ProviderLine(
            name = "豆包端到端实时语音",
            detail = "volc.speech.dialog",
            status = com.parley.speaker.domain.LinkStatus.Unconfigured,
        ),
    )

    fun analysisProviders(): List<ProviderLine> = listOf(
        ProviderLine(
            name = "微软 Azure 语音评测",
            detail = "发音 · 音素级",
            status = com.parley.speaker.domain.LinkStatus.Unconfigured,
        ),
        ProviderLine(
            name = "讯飞评测流式版",
            detail = "发音 · 朗读题",
            status = com.parley.speaker.domain.LinkStatus.Unconfigured,
        ),
        ProviderLine(
            name = "DeepSeek",
            detail = "deepseek-chat",
            status = com.parley.speaker.domain.LinkStatus.Unconfigured,
        ),
    )
}
