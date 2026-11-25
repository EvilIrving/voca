package com.voca.speaker.data.realtime.gemini

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 打字打断在跑的生成时，先到的那段尾巴与收尾属于被打断那一轮。
 * 收尾要是漏过去，界面会把新的一句当成说完，随后真正的回复全落在空闲态里被丢掉。
 */
class InterruptedGenerationTest {

    private var clock = 0L
    private val generation = InterruptedGeneration(2_000L) { clock }

    @Test
    fun `没打断过就什么都不吞`() {
        assertFalse("没打字之前没有尾巴要拦", generation.swallowing())
        assertFalse("没有收尾要吞", generation.boundary())
    }

    @Test
    fun `打断之后旧的尾巴与收尾都吞掉`() {
        generation.arm()
        generation.interrupt()

        assertTrue("打断之后到达的旧轮转写与音频要丢掉", generation.swallowing())
        assertTrue("被打断那一轮的收尾不能当成新的一句说完", generation.boundary())
        assertFalse("吞掉一条收尾就停手", generation.boundary())
    }

    @Test
    fun `服务端没说打断就放行收尾`() {
        generation.arm()

        assertTrue("旧轮的尾巴照旧丢掉", generation.swallowing())
        assertFalse("这一轮其实没被打断，收尾是新的一句的，要放行", generation.boundary())
    }

    @Test
    fun `窗口过去之后不再吞`() {
        generation.arm()
        generation.interrupt()
        clock = 2_000L

        assertFalse("窗口到点就不再拦新的一句", generation.swallowing())
        assertFalse("过期的收尾照旧放行", generation.boundary())
    }

    @Test
    fun `吞掉收尾之后再来的一轮不受影响`() {
        generation.arm()
        generation.interrupt()
        generation.boundary()

        assertFalse("下一轮是新的一句，不该再被拦", generation.swallowing())
        assertFalse("下一轮的收尾要正常送上去", generation.boundary())
    }
}
