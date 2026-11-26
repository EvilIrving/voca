package com.voca.speaker.domain

/** 评测凭据表单的一项字段描述：把字段名、密文标志与读写边界收进领域模型，界面不再按供应商写分支。 */
data class CredentialField(
    val key: String,
    val label: String,
    val secret: Boolean = false,
    val read: (AssessorSettings) -> String,
)

enum class AssessorProvider(val label: String) {
    None("未选择"),
    Azure("微软 Azure 语音评测"),
    Tencent("腾讯智聆口语评测"),
    Xfyun("讯飞评测流式版");

    val fields: List<CredentialField>
        get() = when (this) {
            None -> emptyList()
            Azure -> listOf(
                CredentialField("key", "密钥", secret = true) { it.azureKey },
                CredentialField("region", "区域") { it.azureRegion },
            )
            Tencent -> listOf(
                CredentialField("appId", "AppID") { it.tencentAppId },
                CredentialField("secretId", "SecretID", secret = true) { it.tencentSecretId },
                CredentialField("secretKey", "SecretKey", secret = true) { it.tencentSecretKey },
            )
            Xfyun -> listOf(
                CredentialField("appId", "AppID") { it.xfyunAppId },
                CredentialField("apiKey", "APIKey", secret = true) { it.xfyunApiKey },
                CredentialField("apiSecret", "APISecret", secret = true) { it.xfyunApiSecret },
            )
        }

    fun detail(assessor: AssessorSettings): String = when (this) {
        None -> ""
        Azure -> assessor.azureRegion.ifBlank { "密钥与区域" }
        Tencent, Xfyun -> fields.joinToString("、") { it.label }
    }
}

/** 文本分析请求把密钥放在哪里，三档对应 Bearer 头、自定义头与查询参数。 */
enum class AuthKind { Bearer, Header, Query }

/** 一家挑好的模型号与音色。三家的各存一格，切走再切回来还是原先那一对。 */
data class LiveTuning(val model: String, val voice: String) {

    /** 模型空着取这一家的默认，音色不在这一家的目录里拉回默认。 */
    fun settled(vendor: LiveVendor): LiveTuning = LiveTuning(
        model = model.ifBlank { LiveDefaults.model(vendor) },
        voice = voice.takeIf { VoiceCatalog.isKnown(vendor, it) } ?: LiveDefaults.voice(vendor),
    )
}

/**
 * 实时对话设置按供应商分档：四家的凭据各自一格，模型与音色也按家各存一格。
 * provider 是生效的那家，为空表示还没选过；谁被选中谁生效，不再按凭据齐备推导。
 * 齐备条件四家一致，各认一把密钥：豆包 3.0 全双工版与千问实时语音都只吃一个 API Key。
 */
data class LiveSettings(
    val provider: LiveVendor? = null,
    val geminiKey: String = "",
    val openAiKey: String = "",
    val doubaoApiKey: String = "",
    val qwenApiKey: String = "",
    val tuning: Map<LiveVendor, LiveTuning> = emptyMap(),
) {
    fun keyOf(vendor: LiveVendor): String = when (vendor) {
        LiveVendor.Gemini -> geminiKey
        LiveVendor.OpenAi -> openAiKey
        LiveVendor.Doubao -> doubaoApiKey
        LiveVendor.Qwen -> qwenApiKey
    }

    fun ready(vendor: LiveVendor): Boolean = keyOf(vendor).isNotBlank()

    /** 当前选中的这家能不能建连接。界面与建连判定都读这一格，不再各自数密钥。 */
    val ready: Boolean get() = provider?.let(::ready) == true

    /** 这一家挑好的那一对，没挑过就是目录默认的模型号与默认音色。 */
    fun tuningOf(vendor: LiveVendor): LiveTuning = tuning[vendor]?.settled(vendor)
        ?: LiveTuning(LiveDefaults.model(vendor), LiveDefaults.voice(vendor))

    /** 生效那家挑好的模型与音色，适配器建连接时读这两格。 */
    val model: String get() = provider?.let { tuningOf(it).model }.orEmpty()
    val voice: String get() = provider?.let { tuningOf(it).voice }.orEmpty()

    fun withKey(vendor: LiveVendor, key: String): LiveSettings = when (vendor) {
        LiveVendor.Gemini -> copy(geminiKey = key.trim())
        LiveVendor.OpenAi -> copy(openAiKey = key.trim())
        LiveVendor.Doubao -> copy(doubaoApiKey = key.trim())
        LiveVendor.Qwen -> copy(qwenApiKey = key.trim())
    }

    fun withTuning(
        vendor: LiveVendor,
        model: String = tuningOf(vendor).model,
        voice: String = tuningOf(vendor).voice,
    ): LiveSettings = copy(tuning = tuning + (vendor to LiveTuning(model, voice)))

    /** 存一家：写凭据与音色，参数齐了才把它设为生效那家，空着保存不动别人。 */
    fun saved(vendor: LiveVendor, key: String, voice: String): LiveSettings =
        withKey(vendor, key).withTuning(vendor, voice = voice).selectIfReady(vendor)

    fun select(vendor: LiveVendor): LiveSettings = copy(provider = vendor)

    /** 参数齐了才切过去：没填密钥那家选了也建不起连接。 */
    fun selectIfReady(vendor: LiveVendor): LiveSettings =
        if (ready(vendor)) select(vendor) else this

    /**
     * 清掉一家的凭据。清的是生效那家就落到同组另一个还配着的家，一家不剩就空着。
     * 挑好的音色不清，重填密钥时还是原先那只。
     */
    fun cleared(vendor: LiveVendor): LiveSettings {
        val rest = withKey(vendor, "")
        if (provider != vendor) return rest
        return rest.copy(provider = LiveVendor.entries.firstOrNull { it != vendor && rest.ready(it) })
    }
}

/**
 * 四家的默认模型与默认音色。音色统一取 [VoiceCatalog] 的默认项，模型在这里定：
 * 豆包 3.0 的模型号只有一个取值，协议里必传，所以设置页那一行显示的就是它；
 * 千问选 flash 档，同一价位里多语种够用，plus 档贵六到十二倍，等语音自然度不够再换。
 */
object LiveDefaults {
    fun model(vendor: LiveVendor): String = when (vendor) {
        LiveVendor.Gemini -> "gemini-3.8-live"
        LiveVendor.OpenAi -> "gpt-realtime-2.1"
        LiveVendor.Doubao -> "1.2.6.1"
        LiveVendor.Qwen -> "qwen-audio-3.1-realtime-flash"
    }

    fun voice(vendor: LiveVendor): String = VoiceCatalog.defaultId(vendor)
}

/**
 * 一次设置变更对进行中会话的影响。凭据或供应商换了才重建连接；
 * 模型、音色与角色提示词只在会话开始时进 setup，改了留到下一次开新会话，不掐断正在说的一轮。
 */
enum class LiveChange { None, Rebuild, Voice, Model, Prompt }

fun liveChange(before: AppSettings, after: AppSettings): LiveChange = when {
    before.live.provider != after.live.provider -> LiveChange.Rebuild
    before.live.credentials() != after.live.credentials() -> LiveChange.Rebuild
    before.live.model != after.live.model -> LiveChange.Model
    before.live.voice != after.live.voice -> LiveChange.Voice
    before.prompt != after.prompt -> LiveChange.Prompt
    else -> LiveChange.None
}

/** 建连接真正吃的那几格，逐格比一次。 */
private fun LiveSettings.credentials(): List<String> =
    listOf(geminiKey, openAiKey, doubaoApiKey, qwenApiKey)

/**
 * 一家供应商自己的一份配置：密钥、挑好的模型、自定义端点与鉴权落点。
 * endpoint 与 auth 留空表示用目录里的默认值，只有自定义端点需要把这几样手填。
 */
data class AnalysisCredentials(
    val apiKey: String = "",
    val model: String = "",
    val endpoint: String = "",
    val authKind: AuthKind? = null,
    val authName: String = "",
    val authPrefix: String = "",
) {
    val ready: Boolean get() = apiKey.isNotBlank()
}

/**
 * 文本分析设置。provider 是当前生效的那家，saved 按供应商 id 各存一份，
 * 配第二家不会把第一家顶掉，换回来时密钥与挑好的模型都还在。空串表示一家都没选。
 * 下面那几个读的是当前这家的格子，请求装配与界面取的都是同一份。
 */
data class AnalysisSettings(
    val provider: String = "",
    val saved: Map<String, AnalysisCredentials> = emptyMap(),
    val thinking: Boolean = false,
) {
    fun of(id: String): AnalysisCredentials = saved[id] ?: AnalysisCredentials()

    val active: AnalysisCredentials get() = of(provider)

    /** 只动一家那几格，别家的原样留着。 */
    fun amend(id: String, transform: (AnalysisCredentials) -> AnalysisCredentials): AnalysisSettings =
        copy(saved = saved + (id to transform(of(id))))

    fun select(id: String): AnalysisSettings = copy(provider = id)

    /** 配好的那家才算生效：没填密钥的家选了也建不起请求。 */
    fun selectIfReady(id: String): AnalysisSettings = if (of(id).ready) select(id) else this

    /** 清掉一家的凭据。清的是生效那家就落到同组另一个还配着的家，一家不剩就空着。 */
    fun cleared(id: String): AnalysisSettings {
        val rest = copy(saved = saved - id)
        if (provider != id) return rest
        return rest.copy(provider = rest.saved.entries.firstOrNull { it.value.ready }?.key.orEmpty())
    }

    val apiKey: String get() = active.apiKey
    val model: String get() = active.model
    val endpoint: String get() = active.endpoint
    val authKind: AuthKind? get() = active.authKind
    val authName: String get() = active.authName
    val authPrefix: String get() = active.authPrefix
}

/**
 * 三家的凭据各占一组。Azure 认密钥与区域两项，腾讯与讯飞同是 AppID 加一对密钥的三件套，字段各存各的。
 * resolvedProvider 只认显式选择：选中的那家参数齐备才生效，别家齐备也不再顶上来，
 * 因此三家都填好时到底用哪家由单选点说了算。
 */
data class AssessorSettings(
    val provider: AssessorProvider = AssessorProvider.None,
    val azureKey: String = "",
    val azureRegion: String = "",
    val tencentAppId: String = "",
    val tencentSecretId: String = "",
    val tencentSecretKey: String = "",
    val xfyunAppId: String = "",
    val xfyunApiKey: String = "",
    val xfyunApiSecret: String = "",
) {
    fun ready(vendor: AssessorProvider): Boolean = when (vendor) {
        AssessorProvider.Azure -> azureKey.isNotBlank() && azureRegion.isNotBlank()
        AssessorProvider.Tencent ->
            tencentAppId.isNotBlank() && tencentSecretId.isNotBlank() && tencentSecretKey.isNotBlank()

        AssessorProvider.Xfyun ->
            xfyunAppId.isNotBlank() && xfyunApiKey.isNotBlank() && xfyunApiSecret.isNotBlank()

        AssessorProvider.None -> false
    }

    val resolvedProvider: AssessorProvider
        get() = provider.takeIf(::ready) ?: AssessorProvider.None

    fun select(vendor: AssessorProvider): AssessorSettings = if (ready(vendor)) copy(provider = vendor) else this

    /** Azure 认密钥与区域两项，填完齐备就切到它。 */
    fun withAzure(key: String, region: String): AssessorSettings =
        copy(azureKey = key.trim(), azureRegion = region.trim()).select(AssessorProvider.Azure)

    /** 腾讯认 AppID、SecretID、SecretKey 三件套，填完齐备就切到它。 */
    fun withTencent(appId: String, secretId: String, secretKey: String): AssessorSettings =
        copy(
            tencentAppId = appId.trim(),
            tencentSecretId = secretId.trim(),
            tencentSecretKey = secretKey.trim(),
        ).select(AssessorProvider.Tencent)

    /** 讯飞认 AppID、APIKey、APISecret 三件套，填完齐备就切到它。 */
    fun withXfyun(appId: String, apiKey: String, apiSecret: String): AssessorSettings =
        copy(
            xfyunAppId = appId.trim(),
            xfyunApiKey = apiKey.trim(),
            xfyunApiSecret = apiSecret.trim(),
        ).select(AssessorProvider.Xfyun)

    fun apply(vendor: AssessorProvider, values: Map<String, String>): AssessorSettings = when (vendor) {
        AssessorProvider.Azure -> withAzure(values["key"].orEmpty(), values["region"].orEmpty())
        AssessorProvider.Tencent -> withTencent(
            appId = values["appId"].orEmpty(),
            secretId = values["secretId"].orEmpty(),
            secretKey = values["secretKey"].orEmpty(),
        )
        AssessorProvider.Xfyun -> withXfyun(
            appId = values["appId"].orEmpty(),
            apiKey = values["apiKey"].orEmpty(),
            apiSecret = values["apiSecret"].orEmpty(),
        )
        AssessorProvider.None -> this
    }

    /** 清掉一家的凭据。清的是生效那家就落到另一家还配着的，一家不剩就空着。 */
    fun cleared(vendor: AssessorProvider): AssessorSettings {
        val rest = when (vendor) {
            AssessorProvider.Azure -> copy(azureKey = "", azureRegion = "")
            AssessorProvider.Tencent -> copy(tencentAppId = "", tencentSecretId = "", tencentSecretKey = "")
            AssessorProvider.Xfyun -> copy(xfyunAppId = "", xfyunApiKey = "", xfyunApiSecret = "")
            AssessorProvider.None -> this
        }
        if (provider != vendor) return rest
        return rest.copy(
            provider = AssessorProvider.entries.firstOrNull { it != vendor && rest.ready(it) }
                ?: AssessorProvider.None,
        )
    }
}

data class AppSettings(
    val live: LiveSettings = LiveSettings(),
    val analysis: AnalysisSettings = AnalysisSettings(),
    val assessor: AssessorSettings = AssessorSettings(),
    val prompt: String = "",
)
