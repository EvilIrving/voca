package com.parley.speaker.data.assessor

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 送评之前的语言路由：两家引擎都只认英语，中文那一轮不进这条链路。
 * 真机上的表现是讯飞回 48195 与 iSEInputAppend error，句柄作废，连接被服务端按超时关掉，
 * 界面拿到的是「评测服务异常」；所以判断落在发请求之前，而不是等失败回来再归类。
 */
class SpeechAssessorTest {

    @Test
    fun `中文句不送评`() {
        assertFalse(scorableText("你好，你现在能听到我声音吗？"))
        assertFalse(scorableText("中文"))
    }

    @Test
    fun `汉字夹在英文里也不送评`() {
        assertFalse(scorableText("Can you hear my 声音?"))
    }

    @Test
    fun `繁体与扩展区的汉字同样认得出`() {
        assertFalse(scorableText("請問現在幾點"))
        assertFalse(scorableText("㐀")) // 扩展 A 的第一个字
    }

    @Test
    fun `英文照旧送评，弯引号与重音都不拦`() {
        assertTrue(scorableText("Can you hear my voice right now?"))
        assertTrue(scorableText("Can’t you hear my voice?"))
        assertTrue(scorableText("Café au lait, s'il vous plaît."))
    }

    @Test
    fun `空白不送评`() {
        assertFalse(scorableText(""))
        assertFalse(scorableText("   "))
        assertFalse(scorableText("\n\t"))
    }
}
