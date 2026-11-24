package com.parley.speaker.ui.chat

import com.parley.speaker.domain.TurnEvent

/**
 * 助手转写块的合帧缓冲。模型几十毫秒推来一块，逐块进状态机就是逐块一次整屏重组、
 * 一次整段文字重排与一次落库，回复越长这几样越贵。这里把连续同属一轮的块并成一条放行，
 * 更新频率跟屏幕对齐；换轮或别的回合事件到达之前先把攒着的交出去，顺序与轮次都不变。
 * 只在消费事件的那 1 条协程里用，主线程独享，不设锁。
 */
internal class TranscriptBuffer {

    private val text = StringBuilder()
    private var owner: String? = null

    /** 收下这一块。换了一轮时把上一轮攒着的那段交出来，调用方先把它放行再收这一块。 */
    fun hold(chunk: String, turnId: String?): TurnEvent.AssistantText? {
        val handed = if (owner != turnId) drain() else null
        owner = turnId
        text.append(chunk)
        return handed
    }

    /** 取出攒到现在的那一段，没有就返回 null。 */
    fun drain(): TurnEvent.AssistantText? {
        if (text.isEmpty()) return null
        val taken = TurnEvent.AssistantText(text.toString(), owner)
        text.setLength(0)
        return taken
    }
}
