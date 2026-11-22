package com.parley.speaker.data.realtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 供应商报错原文不进界面这条线的验收。真机撞到的那句「Your project has exceeded its monthly
 * spending cap」原本连状态码带正文一起落进了轻提示，这里把它固定成产品自己那一句。
 */
class ProviderFaultTest {

    @Test
    fun `超支上限换成额度那一句`() {
        val said = "Your project has exceeded its monthly spending cap. " +
            "Please go to AI Studio at https://ai.studio/spend to manage your project."

        assertEquals(ProviderFault.Quota, ProviderFault.message(said))
        assertEquals(ProviderFault.Quota, ProviderFault.message("429", "RESOURCE_EXHAUSTED", said))
    }

    @Test
    fun `配额用尽与余额不足都算额度`() {
        assertEquals(
            ProviderFault.Quota,
            ProviderFault.message("insufficient_quota", "You exceeded your current quota."),
        )
        assertEquals(ProviderFault.Quota, ProviderFault.message("账户余额不足，请充值"))
    }

    @Test
    fun `限流比裸 429 更具体时才认出请求太密`() {
        assertEquals(ProviderFault.Busy, ProviderFault.message("429", "Too many requests"))
        assertEquals(ProviderFault.Busy, ProviderFault.message("并发超限"))
    }

    @Test
    fun `密钥被拒认得出三种写法`() {
        assertEquals(ProviderFault.Key, ProviderFault.message("401"))
        assertEquals(ProviderFault.Key, ProviderFault.message("PERMISSION_DENIED"))
        assertEquals(ProviderFault.Key, ProviderFault.message("Incorrect API key provided: sk-xxx"))
    }

    @Test
    fun `地区与配置各归各的`() {
        assertEquals(ProviderFault.Region, ProviderFault.message("User location is not supported for the API use"))
        assertEquals(ProviderFault.Config, ProviderFault.message("invalid_request_error", "Invalid voice"))
        assertEquals(ProviderFault.Config, ProviderFault.message("400", "Model not found"))
    }

    @Test
    fun `认不出就不猜，交给调用方`() {
        assertNull(ProviderFault.message("something odd happened upstream"))
        assertNull(ProviderFault.message())
        assertNull(ProviderFault.message(null, " "))
    }
}
