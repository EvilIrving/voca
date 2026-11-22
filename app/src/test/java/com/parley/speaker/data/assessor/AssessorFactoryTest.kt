package com.parley.speaker.data.assessor

import com.parley.speaker.domain.AssessorProvider
import com.parley.speaker.domain.AssessorSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 工厂是供应商与实现之间的唯一开关，断言的是调用方拿到哪一家、拿不到时是不是 null，
 * 以及两家凭据同时齐备时只会选中一家。
 */
class AssessorFactoryTest {

    @Test
    fun `Azure 密钥与区域齐备就启用`() {
        val assessor = assessorOf(AssessorSettings(azureKey = key, azureRegion = region))

        assertTrue(assessor is AzureAssessor)
        assertFalse("Azure 允许无参考文本", assessor!!.requiresReferenceText)
    }

    @Test
    fun `Azure 缺一项就不启用`() {
        assertNull(assessorOf(AssessorSettings(azureKey = key)))
        assertNull(assessorOf(AssessorSettings(azureRegion = region)))
        assertNull(assessorOf(AssessorSettings(provider = AssessorProvider.Azure, azureKey = key)))
    }

    @Test
    fun `讯飞三件套齐备走 XfyunAssessor`() {
        val settings = AssessorSettings(
            provider = AssessorProvider.Xfyun,
            xfyunAppId = appId,
            xfyunApiKey = key,
            xfyunApiSecret = secret,
        )

        val assessor = assessorOf(settings)
        assertTrue(assessor is XfyunAssessor)
        assertTrue("讯飞是朗读型评测", assessor!!.requiresReferenceText)
        assertEquals(
            XfyunCredentials(appId = appId, apiKey = key, apiSecret = secret),
            settings.xfyunCredentials,
        )
    }

    @Test
    fun `讯飞缺一件就不启用`() {
        assertNull(
            assessorOf(AssessorSettings(provider = AssessorProvider.Xfyun, xfyunAppId = appId, xfyunApiKey = key)),
        )
        assertNull(assessorOf(AssessorSettings(xfyunApiKey = key, xfyunApiSecret = secret)))
    }

    @Test
    fun `两家同时齐备时按显式 provider 互斥`() {
        val both = AssessorSettings(
            azureKey = key,
            azureRegion = region,
            xfyunAppId = appId,
            xfyunApiKey = key,
            xfyunApiSecret = secret,
        )

        assertTrue(assessorOf(both.copy(provider = AssessorProvider.Azure)) is AzureAssessor)
        assertTrue(assessorOf(both.copy(provider = AssessorProvider.Xfyun)) is XfyunAssessor)
        assertTrue("没显式选过就用 Azure", assessorOf(both) is AzureAssessor)
        assertEquals(AssessorProvider.Azure, both.resolvedProvider)
    }

    @Test
    fun `没配任何一家返回 null`() {
        assertNull(assessorOf(AssessorSettings()))
        assertNull(assessorOf(AssessorSettings(provider = AssessorProvider.None)))
    }

    private companion object {
        const val key = "k-000"
        const val region = "eastasia"
        const val appId = "app-000"
        const val secret = "s-000"
    }
}
