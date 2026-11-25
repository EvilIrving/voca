package com.voca.speaker.data.analysis

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 模型清单的解析、合并、过滤与排序。全部对着返回体下标量断言，不联网也不碰数据库。
 */
class ModelCatalogTest {

    @Test
    fun `Gemini 兼容端点的 models 前缀进来就摘掉`() {
        val models = ModelCatalog.available(
            """{"object": "list", "data": [
                 {"id": "models/gemini-2.5-flash"},
                 {"id": "models/gemini-2.5-pro"}]}""",
        )

        assertEquals(listOf("gemini-2.5-flash", "gemini-2.5-pro"), models.map { it.id })
    }

    @Test
    fun `带前缀的清单与不带前缀的目录按同一个 id 对齐`() {
        val live = ModelCatalog.available("""{"data": [{"id": "models/gemini-2.5-flash"}]}""")
        val directory = ModelCatalog.directory(
            """{"google": {"id": "google", "models": {
                 "gemini-2.5-flash": {"release_date": "2025-06-17",
                   "limit": {"context": 1048576, "output": 65536}}}}}""",
            "google",
        )

        val model = ModelCatalog.merged(live, directory).single()

        assertEquals("gemini-2.5-flash", model.id)
        assertEquals(1048576, model.context)
        assertEquals(65536, model.maxOutput)
        assertEquals("2025-06-17", model.releaseDate)
    }

    @Test
    fun `接口直出的窗口与档位压过目录值`() {
        val live = ModelCatalog.available(
            """{"data": [{"id": "deepseek-chat", "context_window": 131072, "max_output_tokens": 8192,
                 "effort": {"supported_levels": ["low", "medium", "high"]}}]}""",
        )
        val directory = ModelCatalog.directory(
            """{"deepseek": {"id": "deepseek", "models": {
                 "deepseek-chat": {"release_date": "2025-03-24", "reasoning": true,
                   "effort": {"default": "medium", "supported_levels": ["low", "high"]},
                   "limit": {"context": 65536, "output": 4096}}}}}""",
            "deepseek",
        )

        val model = ModelCatalog.merged(live, directory).single()

        assertEquals(131072, model.context)
        assertEquals(8192, model.maxOutput)
        assertEquals(listOf("low", "medium", "high"), model.effortLevels)
        assertEquals("medium", model.defaultEffort)
        assertEquals("2025-03-24", model.releaseDate)
        assertTrue(model.thinking)
    }

    @Test
    fun `目录里多出来的模型不进可用集合`() {
        val live = ModelCatalog.available("""{"data": [{"id": "kimi-k2"}]}""")
        val directory = ModelCatalog.directory(
            """{"moonshotai": {"models": {
                 "kimi-k2": {"release_date": "2025-07-11", "limit": {"context": 262144}},
                 "kimi-k1": {"release_date": "2025-01-01", "limit": {"context": 131072}}}}}""",
            "moonshotai",
        )

        val merged = ModelCatalog.merged(live, directory)

        assertEquals(listOf("kimi-k2"), merged.map { it.id })
        assertEquals(262144, merged.single().context)
    }

    @Test
    fun `目录里查不到日期的条目保留并排在后面`() {
        val models = ModelCatalog.ordered(
            listOf(
                TextModel(id = "undated-a", context = 1000),
                TextModel(id = "old", releaseDate = "2024-01-01"),
                TextModel(id = "undated-b", context = 2000),
                TextModel(id = "new", releaseDate = "2025-08-01"),
            ),
        )

        assertEquals(listOf("new", "old", "undated-a", "undated-b"), models.map { it.id })
    }

    @Test
    fun `每家只留发布日最新的八条`() {
        val models = (1..12).map { index ->
            TextModel(id = "m$index", releaseDate = "2025-%02d-01".format(index))
        }

        val visible = ModelCatalog.visible(models)

        assertEquals(8, visible.size)
        assertEquals(listOf("m12", "m11", "m10", "m9", "m8", "m7", "m6", "m5"), visible.map { it.id })
    }

    @Test
    fun `截断不会把查不到日期的条目顶掉`() {
        val dated = (1..9).map { index -> TextModel(id = "m$index", releaseDate = "2025-%02d-01".format(index)) }
        val undated = listOf(TextModel(id = "no-date-1"), TextModel(id = "no-date-2"))

        val visible = ModelCatalog.visible(dated + undated)

        assertEquals(
            listOf("m9", "m8", "m7", "m6", "m5", "m4", "m3", "m2", "no-date-1", "no-date-2"),
            visible.map { it.id },
        )
    }

    @Test
    fun `非对话模型一律不进清单`() {
        val models = ModelCatalog.available(
            """{"data": [
                 {"id": "gpt-4o"},
                 {"id": "text-embedding-3-small"},
                 {"id": "gpt-4o-mini-tts"},
                 {"id": "whisper-1"},
                 {"id": "gpt-4o-transcribe"},
                 {"id": "gpt-4o-realtime-preview"},
                 {"id": "gpt-image-1"},
                 {"id": "gpt-4o-audio-preview"},
                 {"id": "text-moderation-latest"},
                 {"id": "claude-sonnet-4-5"}]}""",
        )

        assertEquals(listOf("gpt-4o", "claude-sonnet-4-5"), models.map { it.id })
    }

    @Test
    fun `档位表与默认档位都从返回体里读`() {
        val model = ModelsJson.parse(
            """{"data": [{"id": "deepseek-reasoner",
                 "effort": {"default": "high", "supported_levels": ["low", "high"]}}]}""",
        ).single()

        assertEquals(listOf("low", "high"), model.effortLevels)
        assertEquals("high", model.defaultEffort)
        assertEquals("high", model.effort)
        assertTrue("报了档位就算支持思考", model.thinking)
    }

    @Test
    fun `只给档位表没给默认值时取第一档`() {
        val model = ModelsJson.parse(
            """{"data": [{"id": "glm-4.6", "effort_levels": ["low", "medium", "high"]}]}""",
        ).single()

        assertEquals(listOf("low", "medium", "high"), model.effortLevels)
        assertNull(model.defaultEffort)
        assertEquals("low", model.effort)
    }

    @Test
    fun `没有档位字段的供应商退回开关式思考`() {
        val model = ModelsJson.parse("""{"data": [{"id": "claude-x", "reasoning": true}]}""").single()

        assertTrue(model.effortLevels.isEmpty())
        assertNull(model.defaultEffort)
        assertNull("没档位就不发 reasoning_effort", model.effort)
        assertTrue(model.thinking)
    }

    @Test
    fun `清单到位后当前选择不在里面就落到最新一个`() {
        val models = listOf(
            TextModel(id = "new", releaseDate = "2025-08-01"),
            TextModel(id = "old", releaseDate = "2024-01-01"),
        )

        assertEquals("new", ModelCatalog.pick(ModelCatalog.ordered(models), ""))
        assertEquals("old", ModelCatalog.pick(ModelCatalog.ordered(models), "old"))
        assertEquals("", ModelCatalog.pick(emptyList(), "old"))
    }

    @Test
    fun `每家供应商对上 models_dev 自己那一家的目录键`() {
        assertEquals("deepseek", TextProviderCatalog.catalogKeyOf("deepseek"))
        assertEquals("gemini 在目录里叫 google", "google", TextProviderCatalog.catalogKeyOf("gemini"))
        assertEquals("openai", TextProviderCatalog.catalogKeyOf("openai"))
        assertEquals("anthropic", TextProviderCatalog.catalogKeyOf("anthropic"))
        assertEquals("kimi 在目录里叫 moonshotai", "moonshotai", TextProviderCatalog.catalogKeyOf("kimi"))
        assertEquals("glm 在目录里叫 zhipuai", "zhipuai", TextProviderCatalog.catalogKeyOf("glm"))
        assertNull("自定义端点没有目录", TextProviderCatalog.catalogKeyOf(TextProviderCatalog.CustomId))
    }

    @Test
    fun `目录只取指定那一家，转售同一个 id 的别家不串值`() {
        val catalog = """{
            "siliconflow": {"models": {
                "deepseek-chat": {"release_date": "2024-05-06", "limit": {"context": 4096}}}},
            "deepseek": {"models": {
                "deepseek-chat": {"release_date": "2025-03-24", "limit": {"context": 128000}}}}}"""

        val deepseek = ModelCatalog.directory(catalog, "deepseek").single()
        val reseller = ModelCatalog.directory(catalog, "siliconflow").single()

        assertEquals("按 deepseek 这一家取就是它的窗口", 128000, deepseek.context)
        assertEquals("2025-03-24", deepseek.releaseDate)
        assertEquals("按转售那一家取就是转售的值", 4096, reseller.context)
        assertTrue("目录里没有的那家不拿别家的条目顶上", ModelCatalog.directory(catalog, "google").isEmpty())
    }

    @Test
    fun `目录返回体不是对象就当没有`() {
        assertTrue(ModelCatalog.directory("[]", "deepseek").isEmpty())
        assertTrue(ModelCatalog.directory("oops", "deepseek").isEmpty())
    }

    @Test
    fun `可用集合只由清单接口决定，目录缺条目不隐藏模型`() {
        val live = ModelCatalog.available("""{"data": [{"id": "custom-model"}, {"id": "another"}]}""")

        assertEquals(listOf("custom-model", "another"), ModelCatalog.merged(live, emptyList()).map { it.id })
    }

    @Test
    fun `embedding 这类词不当成对话模型`() {
        assertFalse(ModelCatalog.isChatModel("text-embedding-3-large"))
        assertTrue(ModelCatalog.isChatModel("deepseek-chat"))
    }
}
