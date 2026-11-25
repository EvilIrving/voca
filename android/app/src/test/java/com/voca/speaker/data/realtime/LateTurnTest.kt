package com.voca.speaker.data.realtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 取消之后迟到的音频与转写只能靠 turnId 认，打错标就等于界面把用户的取消键当成没按。
 */
class LateTurnTest {

    private var clock = 0L
    private val late = LateTurn(1_500L) { clock }

    @Test
    fun `取消之后窗口内的内容算被取消那轮`() {
        late.cancel("user-1")

        assertEquals("user-1", late.tag(null))
        assertEquals("user-1", late.tag(null))
    }

    @Test
    fun `窗口过去之后不再打标`() {
        late.cancel("user-1")
        clock = 1_500L

        assertNull(late.tag(null))
        assertEquals("窗口已经过期，下一次内容不该再被丢掉", null, late.tag(null))
    }

    @Test
    fun `没有取消就没有标`() {
        assertNull(late.tag(null))
        assertNull(late.tag("resp_1"))
    }

    @Test
    fun `有响应号时只有挂上的那个响应算被取消那轮`() {
        late.cancel("user-1")
        late.bind("resp_a")

        assertEquals("被取消那轮的 id 才是要交给界面的标", "user-1", late.tag("resp_a"))
        assertNull("别的响应是下一轮，不能丢", late.tag("resp_b"))
    }

    @Test
    fun `取消之前还没见到响应号时第一块内容把号挂住`() {
        late.cancel("user-1")

        assertEquals("user-1", late.tag("resp_late"))
        assertEquals("user-1", late.tag("resp_late"))
        assertNull(late.tag("resp_next"))
    }

    @Test
    fun `取消后立即再开一轮不会被当成被取消那轮`() {
        late.cancel("user-1")
        late.nextTurn()

        assertNull("窗口还没挂上响应号就作废，新一轮的第一块内容不该被打上旧标", late.tag("resp_new"))
        assertNull(late.tag(null))
    }

    @Test
    fun `窗口已经挂上响应号时跨过下一轮仍然筛掉迟到内容`() {
        late.cancel("user-1")
        late.tag("resp_a")
        late.nextTurn()

        assertEquals("已认准的响应号还要接着筛", "user-1", late.tag("resp_a"))
        assertNull(late.tag("resp_b"))
    }

    @Test
    fun `收尾之后窗口关掉`() {
        late.cancel("user-1")
        late.close()

        assertNull(late.tag(null))
        assertNull(late.tag("resp_a"))
    }

    @Test
    fun `再取消一轮就换成新的 turnId`() {
        late.cancel("user-1")
        late.cancel("user-2")

        assertEquals("user-2", late.tag(null))
    }
}
