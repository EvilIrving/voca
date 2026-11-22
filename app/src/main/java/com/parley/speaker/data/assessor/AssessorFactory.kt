package com.parley.speaker.data.assessor

import com.parley.speaker.domain.AssessorProvider
import com.parley.speaker.domain.AssessorSettings

/**
 * 供应商到实现的唯一映射点，界面与 ViewModel 只拿 SpeechAssessor，不写供应商分支。
 * AssessorProvider 目前只有 None 与 Azure 两个值，讯飞还没有对应枚举，所以讯飞靠传入凭证来选中；
 * domain 补上讯飞那一项之后，把这个 when 换成按 provider 分发即可。
 */
fun assessorOf(settings: AssessorSettings, xfyun: XfyunCredentials? = null): SpeechAssessor? = when {
    settings.provider == AssessorProvider.Azure ->
        if (settings.azureKey.isBlank() || settings.azureRegion.isBlank()) null
        else AzureAssessor(settings.azureKey, settings.azureRegion)

    xfyun != null && xfyun.isComplete -> XfyunAssessor(xfyun)

    else -> null
}
