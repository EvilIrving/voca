package com.parley.speaker.data.assessor

import java.time.Instant
import java.util.Base64
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 返回体按线上实测的形状写：末帧的 data.data 是 base64 包的 XML，
 * read_chapter 挂四项分与总分，音节上挂 serr_msg，增漏标记在 dp_message。
 */
class XfyunAssessorTest {

    @Test
    fun `末帧结果从 XML 里取四项分与音节偏误`() {
        val result = parseXfyunResult(xfyunBody(XFYUN_XML))

        assertEquals(100.0, result?.accuracy ?: Double.NaN, 0.001)
        assertEquals(98.77274, result?.fluency ?: Double.NaN, 0.001)
        assertEquals(100.0, result?.completeness ?: Double.NaN, 0.001)
        assertEquals(99.63182, result?.total ?: Double.NaN, 0.001)
        assertNull("讯飞不给韵律，不许拿标准度顶替", result?.prosody)

        val issues = result?.issues.orEmpty()
        assertEquals(1, issues.size)
        assertEquals("the", issues[0].word)
        assertEquals("/dh ax/", issues[0].phonetic)
        assertEquals("误读", issues[0].note)
    }

    @Test
    fun `返回体缺总分时按加权规则补算`() {
        val result = parseXfyunResult(xfyunBody(XFYUN_XML.replace(" total_score=\"99.631820\"", "")))

        assertEquals("准确度零点六、流利度零点三、标准度零点一，再乘完整性系数", 99.63182, result?.total ?: Double.NaN, 0.001)
    }

    @Test
    fun `增漏标记各按文档归成四类`() {
        val body = xfyunBody(XFYUN_XML.replace("serr_msg=\"1\"", "serr_msg=\"0\" dp_message=\"16\""))

        assertEquals("漏读", parseXfyunResult(body)?.issues?.firstOrNull()?.note)
    }

    @Test
    fun `错误码与握手状态各归成界面那一类`() {
        assertEquals(AssessFailure.Key, xfyunFailure(10313))
        assertEquals(AssessFailure.Key, xfyunFailure(11200))
        assertEquals(AssessFailure.Quota, xfyunFailure(11201))
        assertEquals(AssessFailure.Audio, xfyunFailure(40038))
        assertEquals(AssessFailure.Offline, xfyunFailure(10200))
        assertEquals("认不出的码算服务异常", AssessFailure.Service, xfyunFailure(99999))

        assertEquals(AssessFailure.Key, xfyunHandshakeFailure(401))
        assertEquals(AssessFailure.Key, xfyunHandshakeFailure(403))
        assertEquals(AssessFailure.Offline, xfyunHandshakeFailure(null))
        assertEquals(AssessFailure.Offline, xfyunHandshakeFailure(502))
    }

    @Test
    fun `末帧没有分数算失败而不是零分`() {
        val scored = xfyunOutcome(xfyunBody(XFYUN_XML))
        assertTrue(scored is AssessOutcome.Scored)
        assertEquals(99.63182, (scored as AssessOutcome.Scored).result.total ?: Double.NaN, 0.001)

        val empty = xfyunBody(XFYUN_XML.replace("accuracy_score=\"100.000000\"", "").replace(" total_score=\"99.631820\"", ""))
        assertEquals(AssessOutcome.Failed(AssessFailure.Service), xfyunOutcome(empty))
        assertEquals(AssessOutcome.Failed(AssessFailure.Service), xfyunOutcome("""{"code":0,"data":{"status":2,"data":"不是 base64"}}"""))
    }

    @Test
    fun `出错码不解析成分数`() {
        assertNull(parseXfyunResult("""{"code":10009,"message":"invalid parameter","sid":"abc"}"""))
        assertNull(parseXfyunResult("不是 JSON"))
        assertNull("data 不是 base64 时不许崩", parseXfyunResult("""{"code":0,"data":{"status":2,"data":"不是 base64"}}"""))
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
    fun `首帧带 app_id 与英文试卷节点并把原文本补 BOM`() {
        val frame = xfyunFirstFrame("38d4293a", "I would like a cup of coffee.").jsonObject
        val business = frame.getValue("business").jsonObject

        assertEquals("38d4293a", frame.getValue("common").jsonObject.getValue("app_id").jsonPrimitive.content)
        assertEquals("ise", business.getValue("sub").jsonPrimitive.content)
        assertEquals("en_vip", business.getValue("ent").jsonPrimitive.content)
        assertEquals("read_sentence", business.getValue("category").jsonPrimitive.content)
        assertEquals("utf-8", business.getValue("tte").jsonPrimitive.content)
        assertEquals("utf8", business.getValue("rstcd").jsonPrimitive.content)
        assertEquals("1", business.getValue("ise_unite").jsonPrimitive.content)
        assertTrue(business.getValue("ttp_skip").jsonPrimitive.boolean)
        assertEquals("\uFEFF[content]\nI would like a cup of coffee.", business.getValue("text").jsonPrimitive.content)
        assertEquals(0, frame.getValue("data").jsonObject.getValue("status").jsonPrimitive.int)
    }

    @Test
    fun `日期用 GMT 的固定格式`() {
        assertEquals("Wed, 04 Mar 2026 05:06:07 GMT", xfyunDate(Instant.parse("2026-03-04T05:06:07Z")))
    }

    @Test
    fun `音频帧首帧标一中间标二收尾单独一帧空音频`() {
        val pcm = ByteArray(1280 * 2 + 100) { 7 }
        val frames = xfyunAudioFrames(pcm).toList()

        assertEquals("两整帧加一段尾巴，再补一帧空的收尾", listOf(1, 2, 2, 4), frames.map { it.aus })
        assertEquals("音频帧一律中间位，末位只出现在收尾帧上", listOf(1, 1, 1, 2), frames.map { it.status })
        assertEquals(listOf(1280, 1280, 100, 0), frames.map { it.chunk.size })

        val first = xfyunAudioFrame(frames[0]).jsonObject
        assertEquals("auw", first.getValue("business").jsonObject.getValue("cmd").jsonPrimitive.content)
        assertEquals(1, first.getValue("business").jsonObject.getValue("aus").jsonPrimitive.int)
        assertEquals(
            frames[0].chunk.size,
            Base64.getDecoder()
                .decode(first.getValue("data").jsonObject.getValue("data").jsonPrimitive.content)
                .size,
        )

        val last = xfyunAudioFrame(frames.last()).jsonObject
        assertEquals(4, last.getValue("business").jsonObject.getValue("aus").jsonPrimitive.int)
        assertEquals(2, last.getValue("data").jsonObject.getValue("status").jsonPrimitive.int)
        assertEquals("", last.getValue("data").jsonObject.getValue("data").jsonPrimitive.content)
    }

    private fun hmacBase64(text: String, secret: String): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(secret.toByteArray(Charsets.UTF_8), "HmacSHA256"))
        return Base64.getEncoder().encodeToString(mac.doFinal(text.toByteArray(Charsets.UTF_8)))
    }

    private companion object {
        const val XFYUN_XML = """
<?xml version="1.0" encoding="UTF-8"?>
<xml_result>
  <read_sentence lan="en" type="study" version="7.0.0.1020">
    <rec_paper>
      <read_chapter accuracy_score="100.000000" beg_pos="0" content="The quick brown fox jumps over the lazy dog" end_pos="264" except_info="0" fluency_score="98.772740" integrity_score="100.000000" is_rejected="false" score_pattern="strict" standard_score="100.000000" total_score="99.631820" word_count="2">
        <sentence accuracy_score="100.000000" beg_pos="0" content="the quick brown fox jumps over the lazy dog" end_pos="264" fluency_score="98.772740" index="0" standard_score="100.000000" total_score="99.631820" word_count="2">
          <word beg_pos="3" content="the" dp_message="0" end_pos="9" global_index="0" index="0" property="0" total_score="85.984860">
            <syll beg_pos="3" content="dh ax" end_pos="9" serr_msg="1" syll_accent="0" syll_score="68.369140">
              <phone beg_pos="3" content="dh" dp_message="0" end_pos="6" gwpp="-4.610252"></phone>
              <phone beg_pos="6" content="ax" dp_message="0" end_pos="9" gwpp="-0.000497"></phone>
            </syll>
          </word>
          <word beg_pos="9" content="quick" dp_message="0" end_pos="34" global_index="1" index="1" property="0" total_score="100.000000">
            <syll beg_pos="9" content="k w ih k" end_pos="34" serr_msg="0" syll_accent="0" syll_score="99.980180">
              <phone beg_pos="9" content="k" dp_message="0" end_pos="18" gwpp="-0.000407"></phone>
            </syll>
          </word>
        </sentence>
      </read_chapter>
    </rec_paper>
  </read_sentence>
</xml_result>
"""

        fun xfyunBody(xml: String): String =
            """{"code":0,"message":"success","sid":"ise000d6e02","data":{"status":2,"data":"""" +
                Base64.getEncoder().encodeToString(xml.trimIndent().toByteArray()) +
                """"}}"""
    }
}
