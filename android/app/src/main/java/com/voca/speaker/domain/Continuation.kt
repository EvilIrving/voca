package com.voca.speaker.domain

/**
 * 接着一条历史会话往下聊时，开场要交给模型的那段材料。
 * 交出去的是一段文字流水而不是录音，早于预算的轮次就此丢掉。
 */

/** 带进新连接的流水上限，与换连接时那份摘要同一量级。 */
const val ContinuationChars = 2_400

/**
 * 取最近 [ContinuationChars] 之内的对话流水：从最后一句往前数，装不下就停。
 * 一轮话都没有、或者整段都超预算时返回 null，调用方照常按新的一段开场。
 */
fun continuationHistory(turns: List<Turn>): String? {
    val lines = turns.filter { it.text.isNotBlank() }.map(::transcriptLine)
    if (lines.isEmpty()) return null
    val kept = mutableListOf<String>()
    var budget = ContinuationChars
    for (line in lines.reversed()) {
        if (line.length + 1 > budget) break
        kept.add(0, line)
        budget -= line.length + 1
    }
    if (kept.isEmpty()) return null
    return "以下是这场对话之前的内容，接着往下聊，不要复述，也不要重新自我介绍。\n" +
        kept.joinToString("\n")
}

/** 没有可跨连接续上的上下文句柄的供应商，这段材料接在系统提示后面一起进 setup。 */
fun promptWithHistory(prompt: String, history: String?): String {
    val block = history?.trim().orEmpty()
    if (block.isEmpty()) return prompt
    return if (prompt.isBlank()) block else "$prompt\n\n$block"
}

/**
 * 豆包开会话时的成对上下文。官方要求 user / assistant 成对传入，
 * 角色写在字段里，正文不再加「学生」「助教」前缀。
 */
data class DialogTurn(val role: String, val text: String)

/**
 * 从现有轮次抽出豆包能读的成对上下文。超预算从最早的轮次丢，
 * 开头若是助教半句、末尾若是还没回的学生半句，一起去掉，凑不成一对就空着。
 */
fun dialogContextOf(turns: List<Turn>, budget: Int = ContinuationChars): List<DialogTurn> {
    val items = turns.mapNotNull { turn ->
        val text = turn.text.trim()
        if (text.isEmpty()) return@mapNotNull null
        val role = if (turn.speaker == Speaker.User) "user" else "assistant"
        DialogTurn(role, text)
    }
    val kept = mutableListOf<DialogTurn>()
    var remaining = budget
    for (item in items.reversed()) {
        if (item.text.length > remaining) break
        kept.add(0, item)
        remaining -= item.text.length
    }
    while (kept.isNotEmpty() && kept.first().role != "user") kept.removeAt(0)
    while (kept.isNotEmpty() && kept.last().role != "assistant") kept.removeAt(kept.lastIndex)
    if (kept.size < 2) return emptyList()
    return kept
}
