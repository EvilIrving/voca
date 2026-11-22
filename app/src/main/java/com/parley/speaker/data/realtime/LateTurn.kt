package com.parley.speaker.data.realtime

/**
 * 取消掉的那一轮还会继续从服务端回吐识别文本与音频，界面按 turnId 丢弃这些迟到的内容，
 * 而 turnId 只有适配器能打上，所以每个适配器都要有一份「现在到达的东西算哪一轮」的记账。
 *
 * 两种协议形态共用这一份账：
 * 有响应号的（OpenAI 的 response.id）按响应号认，窗口内出现的响应号第一次匹配上就挂住，
 * 之后同一个响应号的内容一律算被取消那轮；没有响应号的（豆包）在窗口期内一律算。
 * 窗口在服务端给出这一轮的收尾时关掉，或者自己超时。
 */
internal class LateTurn(
    private val windowMs: Long,
    private val now: () -> Long,
) {
    private var owner: String? = null
    private var until = 0L
    private val responses = LinkedHashSet<String>()

    fun cancel(turnId: String) {
        owner = turnId
        until = now() + windowMs
        responses.clear()
    }

    /**
     * 新一轮开口时调用。窗口里还没挂上任何响应号，说明它分不出迟到内容与新的一轮，
     * 就此作废：否则取消后马上再开口，收尾前到达的第一块内容会把新响应挂进旧窗口，整轮都被当成被取消那轮。
     * 已经挂上响应号的窗口不能丢，那之后的迟到内容还得筛出去。
     */
    fun nextTurn() {
        if (responses.isEmpty()) clear()
    }

    /** 服务端已经给出响应号时把它挂到窗口上，之后只有这个号的内容算那一轮。 */
    fun bind(responseId: String) {
        if (active()) responses.add(responseId)
    }

    /**
     * 当前到达的内容属于哪一轮，窗口之外一律为 null。
     * responseId 为 null 表示这个协议没有响应号，窗口期内都算被取消那轮。
     */
    fun tag(responseId: String?): String? {
        val current = owner ?: return null
        if (now() >= until) {
            clear()
            return null
        }
        if (responseId == null) return current
        if (responses.isEmpty()) {
            responses.add(responseId)
            return current
        }
        return if (responseId in responses) current else null
    }

    fun close() = clear()

    private fun active(): Boolean {
        if (owner == null) return false
        if (now() >= until) {
            clear()
            return false
        }
        return true
    }

    private fun clear() {
        owner = null
        until = 0L
        responses.clear()
    }
}
