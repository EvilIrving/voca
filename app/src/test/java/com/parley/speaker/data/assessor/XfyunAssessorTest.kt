package com.parley.speaker.data.assessor

import okhttp3.HttpUrl.Companion.toHttpUrl

import java.time.Instant
import java.util.Base64
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 返回体是照文档字段名录下来的样例，握手与首帧验的是服务端看得见的那部分参数。
 */
class XfyunAssessorTest {

    @Test
    fun `末帧结果给出四项分与音素偏误`() {
        val result = parseXfyunResult(XFYUN_SUCCESS)

        assertEquals(84.0, result?.accuracy ?: Double.NaN, 0.001)
        assertEquals(90.0, result?.fluency ?: Double.NaN, 0.001)
        assertEquals(100.0, result?.completeness ?: Double.NaN, 0.001)
        assertEquals(86.5, result?.total ?: Double.NaN, 0.001)
        assertNull("讯飞不给韵律，不许拿标准度顶替", result?.prosody)

        val issues = result?.issues.orEmpty()
        assertEquals(1, issues.size)
        assertEquals("cup", issues[0].word)
        assertEquals("/ʌ/", issues[0].phonetic)
        assertEquals("音素偏误", issues[0].note)
    }

    @Test
    fun `返回体缺总分时按加权规则补算`() {
        val result = parseXfyunResult(XFYUN_SUCCESS.replace("\"total_score\":86.5,", ""))

        assertEquals("准确度零点六、流利度零点三、标准度零点一，再乘完整性系数", 86.25, result?.total ?: Double.NaN, 0.001)
    }

    @Test
    fun `出错码不解析成分数`() {
        assertNull(parseXfyunResult("""{"code":10009,"message":"invalid parameter","sid":"abc"}"""))
        assertNull(parseXfyunResult("不是 JSON"))
    }

    @Test
    fun `握手签名按 host date request-line 三行串`() {
        val date = "Wed, 04 Mar 2026 05:06:07 GMT"
        val credentials = XfyunCredentials(appId = "app", apiKey = "key-123", apiSecret = "secret-456")

        // 连接串是 wss，断言只关心 host 与查询参数，用 https 解析同一串即可。
        val url = ("https://" + xfyunUrl(credentials, date).removePrefix("wss://")).toHttpUrl()
        assertEquals("ise-api.xfyun.cn", url.host)
        assertEquals("/v2/open-ise", url.encodedPath)
        assertEquals(date, url.queryParameter("date"))
        assertEquals("ise-api.xfyun.cn", url.queryParameter("host"))

        val authorization = String(
            Base64.getDecoder().decode(url.queryParameter("authorization").orEmpty()),
            Charsets.UTF_8,
        )
        assertTrue(authorization, authorization.contains("""api_key="key-123""""))
        assertTrue(authorization, authorization.contains("""algorithm="hmac-sha256""""))
        assertTrue(authorization, authorization.contains("""headers="host date request-line""""))

        val expected = hmacBase64(
            "host: ise-api.xfyun.cn\ndate: $date\nGET /v2/open-ise HTTP/1.1",
            "secret-456",
        )
        assertTrue(authorization, authorization.contains("""signature="$expected""""))
    }

    @Test
    fun `首帧把原文本带 BOM 放进参数并跳过文本阶段`() {
        val frame = xfyunFirstFrame("I would like a cup of coffee.").jsonObject
        val business = frame.getValue("business").jsonObject

        assertEquals("ise", business.getValue("sub").jsonPrimitive.content)
        assertEquals("en_vip", business.getValue("ent").jsonPrimitive.content)
        assertEquals("read_sentence", business.getValue("category").jsonPrimitive.content)
        assertTrue(business.getValue("ttp_skip").jsonPrimitive.boolean)
        assertEquals("\uFEFFI would like a cup of coffee.", business.getValue("text").jsonPrimitive.content)
        assertEquals(0, frame.getValue("data").jsonObject.getValue("status").jsonPrimitive.int)
    }

    @Test
    fun `日期用 GMT 的固定格式`() {
        assertEquals("Wed, 04 Mar 2026 05:06:07 GMT", xfyunDate(Instant.parse("2026-03-04T05:06:07Z")))
    }

    @Test
    fun `音频帧按首中末三态切块并把裸流装进 base64`() {
        val pcm = ByteArray(1280 * 2 + 100) { 7 }
        val chunks = xfyunAudioChunks(pcm).toList()

        assertEquals("两整帧加一百字节的尾巴该切成三段，末段置结束位", listOf(1, 1, 2), chunks.map { it.first })
        assertEquals(listOf(1280, 1280, 100), chunks.map { it.second.size })

        val frame = xfyunAudioFrame(chunks[0].second, chunks[0].first).jsonObject
        assertEquals("auw", frame.getValue("business").jsonObject.getValue("cmd").jsonPrimitive.content)
        assertEquals(
            chunks[0].second.size,
            Base64.getDecoder()
                .decode(frame.getValue("data").jsonObject.getValue("data").jsonPrimitive.content)
                .size,
        )
    }

    private fun hmacBase64(text: String, secret: String): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(secret.toByteArray(Charsets.UTF_8), "HmacSHA256"))
        return Base64.getEncoder().encodeToString(mac.doFinal(text.toByteArray(Charsets.UTF_8)))
    }

    private companion object {
        const val XFYUN_SUCCESS = """
{"code":0,"message":"success","sid":"ise5f2a1c7b","data":{"status":2,"data":{"read_sentence":{"total_score":86.5,"fluency_score":90,"accuracy_score":84,"standard_score":88.5,"integrity_score":100,"except_info":0,"is_rejected":false,"word":[{"content":"cup","total_score":52,"phone":[{"content":"k","dp_message":0,"total_score":96},{"content":"ʌ","dp_message":2,"total_score":41},{"content":"p","dp_message":0,"total_score":99}]}]}}}}
"""
    }
}
