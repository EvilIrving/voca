package com.parley.speaker.data.assessor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 返回体照官方文档的两代形状写：新版 result 是对象，带 Words 与 PhoneInfos；
 * 老版 result 是没引号的字符串，只捞得到顶层四维分。量纲与 -1 的处置都在适配层定死。
 */
class TencentAssessorTest {

    @Test
    fun `有原文按词数选句子或段落，没有原文走自由说`() {
        assertEquals("1", tencentParams("I want to eat an orange")["eval_mode"])
        assertEquals(
            "超过三十词就转段落",
            "2",
            tencentParams((1..31).joinToString(" ") { "word" })["eval_mode"],
        )
        assertEquals("3", tencentParams(null)["eval_mode"])
        assertEquals("3", tencentParams("   ")["eval_mode"])
    }

    @Test
    fun `句子模式把音素转国际音标的指令拼在参考文本前面，段落模式不拼`() {
        assertEquals(
            "{::cmd{F_IPA=true}}hello there",
            tencentParams("hello there")["ref_text"],
        )
        assertEquals(
            "段落模式不支持这条指令，拼了反而出错",
            (1..31).joinToString(" ") { "word" },
            tencentParams((1..31).joinToString(" ") { "word" })["ref_text"],
        )
        assertNull("自由说不带参考文本", tencentParams(null)["ref_text"])
    }

    @Test
    fun `参考文本先清洗掉带语法的字符与汉字`() {
        assertEquals("hello there", tencentRefText("  hello\n\n  there "))
        assertEquals("hello there", tencentRefText("hello{::cmd{F_IPA=true}} there"))
        assertEquals("a b", tencentRefText("a | b"))
        assertEquals("含汉字整段清空", "", tencentRefText("我说 hello"))
    }

    @Test
    fun `签名原串按字典序拼，且不含 signature 自己`() {
        val origin = tencentSignOrigin(
            "125922",
            mapOf("voice_id" to "a", "eval_mode" to "1", "signature" to "x", "ref_text" to "hello"),
        )

        assertEquals("soe.cloud.tencent.com/soe/api/125922?eval_mode=1&ref_text=hello&voice_id=a", origin)
    }

    @Test
    fun `HMAC-SHA1 再 Base64 的结果与 openssl 算的一致`() {
        val origin = "soe.cloud.tencent.com/soe/api/125922?eval_mode=1&ref_text=hello"

        assertEquals("f/DMcxAvEDx51Y2y/ddcuVzdIck=", tencentSignature("testkey", origin))
    }

    @Test
    fun `连接地址把 appid 放在路径段，签名做了 urlencode`() {
        val url = tencentUrl(
            TencentCredentials(appId = "125922", secretId = "sid", secretKey = "skey"),
            tencentParams("hello there"),
            nowSeconds = 1_700_000_000,
        )

        assertTrue(url.startsWith("wss://soe.cloud.tencent.com/soe/api/125922?"))
        assertTrue("空格写成 %20 而不是加号", url.contains("ref_text=%7B%3A%3Acmd%7BF_IPA%3Dtrue%7D%7Dhello%20there"))
        assertTrue("签名最后一段必须转义等号", url.contains("&signature=") && !url.contains("signature=x"))
        assertTrue(url.contains("timestamp=1699999940"))
        assertTrue("有效期落在九十天上限之内", url.contains("expired=1700003540"))
    }

    @Test
    fun `对象形态取四维分并把零点到一换算成百分制`() {
        val result = parseTencentResult(TENCENT_BODY)

        assertEquals(99.05, result?.accuracy ?: Double.NaN, 0.01)
        assertEquals("PronFluency 是零点到一", 98.39, result?.fluency ?: Double.NaN, 0.01)
        assertEquals(100.0, result?.completeness ?: Double.NaN, 0.01)
        assertEquals(99.05, result?.total ?: Double.NaN, 0.01)
        assertNull("腾讯不给韵律，不许拿别的维度顶替", result?.prosody)
    }

    @Test
    fun `音素偏误只留分低的，标点与参考发音不列`() {
        val issues = parseTencentResult(TENCENT_BODY)?.issues.orEmpty()

        assertEquals(1, issues.size)
        assertEquals("orange", issues[0].word)
        assertEquals("/ɪ/", issues[0].phonetic)
        assertEquals("音素偏误", issues[0].note)
    }

    @Test
    fun `老版字符串形态只捞顶层四维，-1 当没有值`() {
        val body = """{"code":0,"final":1,"result":"{SuggestedScore:-0.36 PronAccuracy:-1 PronFluency:-1 PronCompletion:0.2}"}"""
        val result = parseTencentResult(body)

        assertNull("负分不是分", result?.total)
        assertNull(result?.accuracy)
        assertNull(result?.fluency)
        assertEquals(20.0, result?.completeness ?: Double.NaN, 0.01)
        assertTrue(result?.issues.orEmpty().isEmpty())
    }

    @Test
    fun `段落与自由说不返回音素，内部标记不往界面送`() {
        assertTrue(
            "只有句子模式开了国际音标转换",
            parseTencentResult(TENCENT_BODY, withPhonemes = false)?.issues.orEmpty().isEmpty(),
        )
    }

    @Test
    fun `错误码与界面那几类一一对上`() {
        assertEquals(AssessFailure.Key, tencentFailure(4002))
        assertEquals(AssessFailure.Key, tencentFailure(4003))
        assertEquals(AssessFailure.Quota, tencentFailure(4004))
        assertEquals(AssessFailure.Busy, tencentFailure(4006))
        assertEquals(AssessFailure.Audio, tencentFailure(4115))
        assertEquals(AssessFailure.Offline, tencentFailure(4009))
        assertEquals(AssessFailure.Service, tencentFailure(5000))
    }

    @Test
    fun `报文能读但没有分算服务异常`() {
        val outcome = tencentOutcome("""{"code":0,"final":1}""")

        assertEquals(AssessOutcome.Failed(AssessFailure.Service), outcome)
        assertFalse(tencentOutcome(TENCENT_BODY) is AssessOutcome.Failed)
    }

    private companion object {
        /** 句子模式的一次完整返回：整体分词，词分音素，音素是国际音标。 */
        const val TENCENT_BODY = """
        {
          "code": 0,
          "message": "abf42150",
          "voice_id": "abf42150",
          "final": 1,
          "result": {
            "SuggestedScore": 99.05,
            "PronAccuracy": 99.05,
            "PronFluency": 0.9839,
            "PronCompletion": 1,
            "Words": [
              {
                "MemBeginTime": 310,
                "MemEndTime": 580,
                "PronAccuracy": 98.91,
                "Word": "I",
                "MatchTag": 0,
                "PhoneInfos": [
                  {
                    "PronAccuracy": 98.6,
                    "Phone": "aɪ",
                    "ReferencePhone": "",
                    "MatchTag": 0
                  }
                ]
              },
              {
                "MemBeginTime": 580,
                "MemEndTime": 730,
                "PronAccuracy": 71.2,
                "Word": "orange",
                "MatchTag": 0,
                "PhoneInfos": [
                  {
                    "PronAccuracy": 41.2,
                    "Phone": "ɪ",
                    "ReferencePhone": "",
                    "MatchTag": 0
                  },
                  {
                    "PronAccuracy": 88.0,
                    "Phone": "n",
                    "ReferencePhone": "",
                    "MatchTag": 0
                  }
                ]
              },
              {
                "MemBeginTime": 730,
                "MemEndTime": 760,
                "PronAccuracy": -1,
                "Word": ".",
                "MatchTag": 5,
                "PhoneInfos": [
                  {
                    "PronAccuracy": 10.0,
                    "Phone": ".",
                    "ReferencePhone": "",
                    "MatchTag": 5
                  }
                ]
              }
            ],
            "SentenceId": 0,
            "RefTextId": -1
          }
        }
        """
    }
}
