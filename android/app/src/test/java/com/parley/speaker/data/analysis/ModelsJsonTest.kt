package com.parley.speaker.data.analysis

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * models.json 与各家 /models 清单的解析。断言的是取到的 id、上下文窗口、生成上限与思考标记。
 */
class ModelsJsonTest {

    @Test
    fun `data 数组外壳`() {
        val models = ModelsJson.parse(
            """{"object": "list", "data": [
                 {"id": "gpt-4o", "context_window": 128000, "max_output_tokens": 16384},
                 {"id": "gpt-4o-mini", "context_length": 128000, "max_tokens": 16384, "reasoning": false}]}""",
        )

        assertEquals(listOf("gpt-4o", "gpt-4o-mini"), models.map { it.id })
        assertEquals(128000, models[0].context)
        assertEquals(16384, models[0].maxOutput)
        assertEquals(128000, models[1].context)
        assertTrue(!models[1].thinking)
    }

    @Test
    fun `models 数组里的 limit 对象`() {
        val models = ModelsJson.parse(
            """{"models": [
                 {"id": "claude-sonnet-4-5", "limit": {"context": 200000, "output": 64000},
                  "reasoning": {"effort": "high"}}]}""",
        )

        val model = models.single()
        assertEquals(200000, model.context)
        assertEquals(64000, model.maxOutput)
        assertTrue(model.thinking)
        assertEquals("high", model.effort)
    }

    @Test
    fun `按 id 作键的对象外壳`() {
        val models = ModelsJson.parse(
            """{"deepseek-flash": {"limit": {"context": 131072, "output": 8192}, "thinking": {"enabled": true}},
                "deepseek-chat": {"limit": {"context": 65536, "output": 4096}}}""",
        )

        assertEquals(listOf("deepseek-flash", "deepseek-chat"), models.map { it.id })
        assertEquals(131072, models[0].context)
        assertEquals(8192, models[0].maxOutput)
        assertTrue(models[0].thinking)
        assertTrue(!models[1].thinking)
    }

    @Test
    fun `OpenRouter 那种把生成上限放在 top_provider 里`() {
        val models = ModelsJson.parse(
            """{"data": [{"id": "deepseek/deepseek-chat-v3", "context_length": 65536,
                         "top_provider": {"max_completion_tokens": 8192},
                         "supported_parameters": ["tools", "reasoning"]}]}""",
        )

        val model = models.single()
        assertEquals(65536, model.context)
        assertEquals(8192, model.maxOutput)
        assertTrue(model.thinking)
    }

    @Test
    fun `Anthropic 的清单没有窗口字段`() {
        val models = ModelsJson.parse(
            """{"data": [{"id": "claude-x", "display_name": "Claude X", "type": "model"}], "has_more": false}""",
        )

        assertEquals(listOf("claude-x"), models.map { it.id })
        assertEquals(0, models[0].context)
        assertEquals(0, models[0].maxOutput)
    }

    @Test
    fun `不是模型清单就当空`() {
        assertTrue(ModelsJson.parse("不是 JSON").isEmpty())
        assertTrue(ModelsJson.parse("""{"object": "list", "has_more": false}""").isEmpty())
        assertTrue(ModelsJson.parse("""{"data": []}""").isEmpty())
    }
}
