package com.parley.speaker.data

import android.content.Context

/**
 * 三段提示词的取法。源就是仓库根目录的 `prompts/`，整个目录当 assets 打包进来，
 * 改文字只改那三个文件，不必翻代码找字符串。
 *
 * `system.md` 是全局系统提示词，配置中心可见可改，文件内容只是出厂默认值；
 * `session.md` 是新的一段开场要发给模型的第一句材料，空着就沿用固定问候；
 * `memory.md` 是全局记忆的起点，空着就取 [SeedContent.DefaultMemory] 那段口吻。
 * 三个文件都读不到时一律退回 [SeedContent] 里的常量，界面不会因为少一个文件就少一段话。
 */
class Prompts(context: Context) {

    private val files = runCatching {
        val assets = context.assets
        listOf("system.md", "session.md", "memory.md").associateWith { name ->
            runCatching { assets.open(name).bufferedReader().use { it.readText() } }
                .getOrNull()
                .orEmpty()
                .trim()
        }
    }.getOrDefault(emptyMap())

    /** 出厂的系统提示词。用户在配置中心改过之后，这里不再参与，只有恢复默认与全新安装才用得上。 */
    val system: String = files["system.md"].orEmpty().ifBlank { SeedContent.DefaultPrompt }

    /** 开场加载的材料。空表示没有额外开场，用那一句固定问候。 */
    val session: String = files["session.md"].orEmpty()

    /** 全局记忆的起点。它是记忆的口吻，不是记住了什么。 */
    val memory: String = files["memory.md"].orEmpty().ifBlank { SeedContent.DefaultMemory }
}
