package com.parley.speaker.domain

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
