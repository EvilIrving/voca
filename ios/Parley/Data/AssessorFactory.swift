import Foundation

func assessorOf(settings: AssessorSettings) -> SpeechAssessor? {
    switch settings.resolvedProvider {
    case .none: nil
    case .azure: AzureAssessor(subscriptionKey: settings.azureKey, region: settings.azureRegion)
    case .xfyun: XfyunAssessor(credentials: settings.xfyunCredentials)
    }
}

extension AssessorSettings {
    var xfyunCredentials: XfyunCredentials {
        XfyunCredentials(appId: xfyunAppId, apiKey: xfyunApiKey, apiSecret: xfyunApiSecret)
    }
}