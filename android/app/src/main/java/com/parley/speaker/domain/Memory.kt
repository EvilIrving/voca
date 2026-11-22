package com.parley.speaker.domain

/**
 * 会话记忆的规矩。记忆属于这一场对话本身：每一段开场从默认记忆起头，轮末由文本模型把新内容
 * 并进去，下一次建连接时整段进系统提示。这里只放纯粹的判定，读写编排在 ChatViewModel。
 * 记忆是内部机制，界面上没有入口，设置页也没有对应的一行。
 */

/** 记忆正文字数上限。超出来的部分说明模型在抄对话原文，那种东西下一轮就会被自己撑爆。 */
const val MemoryMaxChars = 800

/**
 * 系统提示的拼法：角色提示词在前，记忆在后。记忆是一段叙述而不是指令清单，
 * 记忆块前面那句把它交出去，模型才不会把记住的事当成需要复述给对方听的材料。
 */
fun systemPromptOf(rolePrompt: String, memory: String): String {
    val role = rolePrompt.trim()
    val remembered = memoryBlock(memory)
    return when {
        remembered.isEmpty() -> role
        role.isEmpty() -> remembered
        else -> "$role\n\n$remembered"
    }
}

private fun memoryBlock(memory: String): String {
    val text = memory.trim()
    return if (text.isEmpty()) "" else "以下是这场对话已经记住的事，当作你自己的记忆，不要向对方复述：\n$text"
}

/**
 * 改写回到手上时收不收：空文本、抄了原文的超长文本、与手上这份一字不差的都退回。
 * 返回 null 表示这一轮的记忆保持不变，宁可留旧的那份，也不收一份说不清来处的东西。
 */
fun admitMemory(current: String, candidate: String?): String? {
    val next = candidate?.trim().orEmpty()
    if (next.isEmpty() || next == current.trim()) return null
    if (next.length > MemoryMaxChars) return null
    return next
}

/**
 * 一次改写要看的材料：这一轮学生说的话，连同它前后各一句。事实常常一半在问句里、
 * 一半在回答里，只取学生那一句会丢掉半句。这一轮已经不在列表里（换了段）就返回 null。
 */
fun memoryMaterial(turns: List<Turn>, turnId: String): String? {
    val index = turns.indexOfFirst { it.id == turnId }
    if (index < 0) return null
    val window = turns.subList((index - 1).coerceAtLeast(0), (index + 2).coerceAtMost(turns.size))
    val text = window.filter { it.text.isNotBlank() }.joinToString("\n", transform = ::transcriptLine)
    return text.takeIf { it.isNotBlank() }
}

/** 对话流水的一行：谁说的、说了什么。会话名与记忆的材料共用这一份写法。 */
fun transcriptLine(turn: Turn): String =
    "${if (turn.speaker == Speaker.User) "学生" else "助教"}：${turn.text}"
