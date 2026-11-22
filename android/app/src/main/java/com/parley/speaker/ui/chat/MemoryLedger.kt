package com.parley.speaker.ui.chat

import com.parley.speaker.domain.admitMemory
import kotlinx.coroutines.channels.Channel

/**
 * 全局记忆的账：起点、排队、改写、落库四步各归各位，界面看不见也改不了。
 *
 * 一轮收尾只把它的标识交进来，队列是 conflated 的——前一条还没写完就只留最后一轮，
 * 同一件事不会在排队里叠几份。每条改写都读当下这份记忆，所以两次改写不会互相覆盖：
 * 后一次看到的一定是前一次收下来的结果。收不收由 [admitMemory] 一处定，空、超长、
 * 一字不差的都不写库，宁可留旧的那份。
 */
class MemoryLedger(
    seed: String = "",
    private val persist: suspend (String) -> Unit = {},
) {

    /** 当下这份记忆。建连接时接在系统提示词后面进 setup，别处只读。 */
    var text: String = seed
        private set

    private val queue = Channel<String>(Channel.CONFLATED)

    /** 这一轮收尾了，把它排进改写队列。收段之后也照排：记忆是全局的，不跟着一段消失。 */
    fun offer(turnId: String) {
        queue.trySend(turnId)
    }

    /**
     * 队列消费者。[materialOf] 取这一轮的材料，取不到（这一轮已经不在列表里）就跳过；
     * [rewrite] 带上当下这份记忆换新的整段，回来才判收不收。
     */
    suspend fun drain(
        materialOf: (String) -> String?,
        rewrite: suspend (memory: String, material: String) -> String?,
        continuous: Boolean = false,
    ) {
        if (!continuous) {
            while (true) {
                val turnId = queue.tryReceive().getOrNull() ?: break
                val material = materialOf(turnId) ?: continue
                val next = admitMemory(text, rewrite(text, material)) ?: continue
                text = next
                persist(next)
            }
            return
        }
        for (turnId in queue) {
            val material = materialOf(turnId) ?: continue
            val next = admitMemory(text, rewrite(text, material)) ?: continue
            text = next
            persist(next)
        }
    }

    /**
     * 开场就位：库里留着上一场写下的那份就用它，库是空的时候才种下出厂的起点。
     * 种下来的那份当场写库，下一次冷启动读到的是它，不再重种一遍。
     */
    suspend fun seedWith(value: String) {
        if (text.isNotBlank()) return
        text = value
        if (value.isNotBlank()) persist(value)
    }
}
