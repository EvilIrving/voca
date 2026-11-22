package com.parley.speaker.data.assessor

import com.parley.speaker.domain.AssessorProvider
import com.parley.speaker.domain.AssessorSettings

/**
 * 供应商到实现的唯一映射点，界面与 ViewModel 只拿 SpeechAssessor，不写供应商分支。
 * provider 由设置自身按凭据齐备推导，两家互斥，参数不全的那家在这里就是 null，
 * 调用方不必自己判断密钥填没填齐。
 */
fun assessorOf(settings: AssessorSettings): SpeechAssessor? = when (settings.resolvedProvider) {
    AssessorProvider.None -> null
    AssessorProvider.Azure -> AzureAssessor(settings.azureKey, settings.azureRegion)
    AssessorProvider.Xfyun -> XfyunAssessor(settings.xfyunCredentials)
}

/** 讯飞的握手只吃 apiKey 与 apiSecret，appId 随凭据一起带过去供排查。 */
val AssessorSettings.xfyunCredentials: XfyunCredentials
    get() = XfyunCredentials(
        appId = xfyunAppId,
        apiKey = xfyunApiKey,
        apiSecret = xfyunApiSecret,
    )
