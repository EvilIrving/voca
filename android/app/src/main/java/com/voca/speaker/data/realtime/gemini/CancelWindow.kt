package com.voca.speaker.data.realtime.gemini

/**
 * 取消掉的那一轮还会从服务端吐出识别文本与音频，协议里没有轮次号可以对照，
 * 唯一能用的信号是时间窗口与收尾标记：取消那一刻起的这段时间里到达的内容都算被取消那轮的。
 *
 * 窗口在这几处关闭：服务端给了 turnComplete、interrupted 或 waitingForInput，
 * 或者窗口自己超时。按下一次说话不关窗口，因为新一轮刚开时迟到的那几句仍然属于旧轮。
 */
internal class CancelWindow(
    private val windowMs: Long,
    private val now: () -> Long,
) {
    private val ids = LinkedHashSet<String>()
    private var owner: String? = null
    private var until = 0L

    fun cancel(turnId: String) {
        ids.add(turnId)
        while (ids.size > MAX_IDS) ids.remove(ids.first())
        owner = turnId
        until = now() + windowMs
    }

    fun boundary() {
        owner = null
        until = 0L
    }

    /** 当前到达的内容属于哪一轮，窗口之外一律为 null。 */
    fun tag(): String? {
        val current = owner ?: return null
        if (now() >= until) {
            owner = null
            return null
        }
        return current
    }

    fun blacklisted(turnId: String?): Boolean = turnId != null && turnId in ids

    private companion object {
        const val MAX_IDS = 4
    }
}
