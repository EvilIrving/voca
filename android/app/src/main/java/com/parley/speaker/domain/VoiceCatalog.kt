package com.parley.speaker.domain

/** 实时对话的三家供应商。音色目录按它分档，界面据此取清单，不写供应商分支。 */
enum class LiveVendor(val label: String) {
    Gemini("Gemini Live"),
    OpenAi("OpenAI Realtime"),
    Doubao("豆包实时语音"),
}

/**
 * 一条音色。id 是要原样填进协议里的那个值：Gemini 的 voiceName、OpenAI 的 voice、豆包的 voice_type。
 * name 是界面上的名字：Gemini 与 OpenAI 的音色本来就是英文名，名字就是 id；
 * 豆包那串 voice_type 换成音色表上的中文名，行内与下拉都只写名字。
 * style 是下拉里第二行的风格词，豆包这档把它写成「语种 · 场景」，因为豆包的清单本来就这么分。
 * group 是分族标签，单族供应商给一个固定组名，界面按 group 折成一段。
 */
data class VoiceOption(
    val id: String,
    val style: String,
    val name: String = id,
    val default: Boolean = false,
    val group: String = "",
)

/**
 * 实时对话的静态音色目录。三家都没有能枚举会话音色的接口，清单只能内置：
 * Gemini 的 GET /v1beta/voices 列的是语音库目录，和 Live 认的三十个只重合十个，拿它当清单会塞进不认的名字；
 * OpenAI 的 Voices 资源只有创建与删除，没有 list；豆包的 voice_type 是从音色表格里抄，表格还随模型版本分族。
 *
 * 风格词只有 Gemini 那三十个来自官方给每个名字配的描述，OpenAI 与豆包的描述是按语种、场景与听感自拟的。
 */
object VoiceCatalog {

    fun voices(vendor: LiveVendor): List<VoiceOption> = when (vendor) {
        LiveVendor.Gemini -> GEMINI
        LiveVendor.OpenAi -> OPENAI
        LiveVendor.Doubao -> DOUBAO
    }

    /** 默认音色。目录里每条清单都保证恰好一条标了 default。 */
    fun defaultId(vendor: LiveVendor): String =
        voices(vendor).firstOrNull { it.default }?.id ?: voices(vendor).first().id

    /** 按 group 折成段，段序按目录里第一次出现的顺序，界面照着铺就是分族下拉。 */
    fun groups(vendor: LiveVendor): List<Pair<String, List<VoiceOption>>> {
        val ordered = LinkedHashMap<String, MutableList<VoiceOption>>()
        voices(vendor).forEach { option ->
            ordered.getOrPut(option.group) { mutableListOf() }.add(option)
        }
        return ordered.map { (group, list) -> group to list.toList() }
    }

    fun isKnown(vendor: LiveVendor, id: String): Boolean = voices(vendor).any { it.id == id }

    private val GEMINI_GROUP = "Live 预置"

    /**
     * 三十个预置音色，名字与风格词取自 Live 的音色表。顺序照官方排列，
     * Aoede 是项目默认，真机在 gemini-3.8-live 上实测可用。
     */
    private val GEMINI: List<VoiceOption> = listOf(
        VoiceOption("Zephyr", "明亮", group = GEMINI_GROUP),
        VoiceOption("Puck", "活泼", group = GEMINI_GROUP),
        VoiceOption("Charon", "稳重", group = GEMINI_GROUP),
        VoiceOption("Kore", "坚定", group = GEMINI_GROUP),
        VoiceOption("Fenrir", "激昂", group = GEMINI_GROUP),
        VoiceOption("Leda", "年轻", group = GEMINI_GROUP),
        VoiceOption("Orus", "坚定", group = GEMINI_GROUP),
        VoiceOption("Aoede", "轻快", default = true, group = GEMINI_GROUP),
        VoiceOption("Callirrhoe", "随和", group = GEMINI_GROUP),
        VoiceOption("Autonoe", "明亮", group = GEMINI_GROUP),
        VoiceOption("Enceladus", "气声", group = GEMINI_GROUP),
        VoiceOption("Iapetus", "清晰", group = GEMINI_GROUP),
        VoiceOption("Umbriel", "随和", group = GEMINI_GROUP),
        VoiceOption("Algieba", "顺滑", group = GEMINI_GROUP),
        VoiceOption("Despina", "顺滑", group = GEMINI_GROUP),
        VoiceOption("Erinome", "清晰", group = GEMINI_GROUP),
        VoiceOption("Algenib", "沙哑", group = GEMINI_GROUP),
        VoiceOption("Rasalgethi", "信息型", group = GEMINI_GROUP),
        VoiceOption("Laomedeia", "活泼", group = GEMINI_GROUP),
        VoiceOption("Achernar", "柔和", group = GEMINI_GROUP),
        VoiceOption("Alnilam", "坚定", group = GEMINI_GROUP),
        VoiceOption("Schedar", "平稳", group = GEMINI_GROUP),
        VoiceOption("Gacrux", "成熟", group = GEMINI_GROUP),
        VoiceOption("Pulcherrima", "外向", group = GEMINI_GROUP),
        VoiceOption("Achird", "友好", group = GEMINI_GROUP),
        VoiceOption("Zubenelgenubi", "随性", group = GEMINI_GROUP),
        VoiceOption("Vindemiatrix", "温柔", group = GEMINI_GROUP),
        VoiceOption("Sadachbia", "生动", group = GEMINI_GROUP),
        VoiceOption("Sadaltager", "博学", group = GEMINI_GROUP),
        VoiceOption("Sulafat", "温暖", group = GEMINI_GROUP),
    )

    private val OPENAI_GROUP = "内置音色"

    /** 现役十个内置音色，alloy 到 cedar。风格词按听感自拟，官方没给逐个描述。 */
    private val OPENAI: List<VoiceOption> = listOf(
        VoiceOption("alloy", "中性", default = true, group = OPENAI_GROUP),
        VoiceOption("ash", "沉稳", group = OPENAI_GROUP),
        VoiceOption("ballad", "叙事", group = OPENAI_GROUP),
        VoiceOption("coral", "明亮", group = OPENAI_GROUP),
        VoiceOption("echo", "低沉", group = OPENAI_GROUP),
        VoiceOption("sage", "温和", group = OPENAI_GROUP),
        VoiceOption("shimmer", "轻软", group = OPENAI_GROUP),
        VoiceOption("verse", "亲切", group = OPENAI_GROUP),
        VoiceOption("marin", "自然", group = OPENAI_GROUP),
        VoiceOption("cedar", "厚实", group = OPENAI_GROUP),
    )

    private val DOUBAO_O = "O 系列"
    private val DOUBAO_SC = "SC 系列"
    private val DOUBAO_SC2 = "SC 2.0 系列"

    /**
     * 豆包 3.0 的发音人，填进 session 的 audio.output.voice。
     * 这十把都在 duplex 端点上跑过完整一轮，识别出字、模型作答、下行出音频；
     * 另有 zh_female_shuangkuaisisi_moon_bigtts、saturn_zh_male_tiancaitongsheng_tob、
     * saturn_zh_male_lengjundashu_tob 三把，开会话不报错、文本也生成了，但一块音频都不出，没有收进目录。
     */
    private val DOUBAO: List<VoiceOption> = listOf(
        VoiceOption("zh_female_vv_jupiter_bigtts", "中文女声 · 多情感，日常对话", name = "vivi", default = true, group = DOUBAO_O),
        VoiceOption("zh_female_xiaohe_jupiter_bigtts", "中文女声 · 温柔，长对话陪伴", name = "小何", group = DOUBAO_O),
        VoiceOption("zh_male_yunzhou_jupiter_bigtts", "中文男声 · 沉稳，讲解与叙事", name = "云舟", group = DOUBAO_O),
        VoiceOption("zh_male_xiaotian_jupiter_bigtts", "中文男声 · 活泼，日常闲聊", name = "小天", group = DOUBAO_O),
        VoiceOption("en_male_tim_uranus_bigtts", "英文男声 · 自然，英文对练", name = "Tim", group = DOUBAO_O),

        VoiceOption("zh_female_cancan_mars_bigtts", "中文女声 · 通用，播报与客服", name = "灿灿", group = DOUBAO_SC),
        VoiceOption("ICL_zh_female_keainvsheng_tob", "中文女声 · 声音复刻，SC 独有", name = "可爱女生", group = DOUBAO_SC),

        VoiceOption("saturn_zh_female_keainvsheng_tob", "中文女声 · 甜美人设，角色扮演", name = "甜美女声", group = DOUBAO_SC2),
        VoiceOption("saturn_zh_female_tiaopigongzhu_tob", "中文女声 · 俏皮公主", name = "调皮公主", group = DOUBAO_SC2),
    )
}
