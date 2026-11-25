package com.voca.speaker.ui.chat

import com.voca.speaker.domain.admitMemory
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * 全局记忆的账：起点、排队、改写、落库四步各归各位，设置页的编辑与自动改写写的是同一份。
 *
 * 一轮收尾只把它的标识交进来，队列是 conflated 的——前一条还没写完就只留最后一轮，
 * 同一件事不会在排队里叠几份。每条改写都读当下这份记忆，所以两次改写不会互相覆盖：
 * 后一次看到的一定是前一次收下来的结果。收不收由 [admitMemory] 一处定，空、超长、
 * 一字不差的都不写库，宁可留旧的那份；设置页手写的那份走 [replace]，原样收。
 *
 * 改写的请求要联网，中途可能有人在设置页按下保存，所以落库这一步串成一条：
 * 谁说最后一句谁进库，先算出来的那份不会盖掉后写的那份。
 */
class MemoryLedger(
    seed: String = "",
    private val persist: suspend (String) -> Unit = {},
) {

    private val state = MutableStateFlow(seed)

    /** 当下这份记忆。建连接时接在系统提示词后面进 setup，设置页照着它显示。 */
    val text: StateFlow<String> = state.asStateFlow()

    private val queue = Channel<String>(Channel.CONFLATED)
    private val writing = Mutex()

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
                consume(turnId, materialOf, rewrite)
            }
            return
        }
        for (turnId in queue) {
            consume(turnId, materialOf, rewrite)
        }
    }

    private suspend fun consume(
        turnId: String,
        materialOf: (String) -> String?,
        rewrite: suspend (memory: String, material: String) -> String?,
    ) {
        val material = materialOf(turnId) ?: return
        // 请求发出去时不占着锁，联网回来再按这一刻的正文判收不收。
        val candidate = rewrite(state.value, material)
        writing.withLock {
            val next = admitMemory(state.value, candidate) ?: return
            state.value = next
            persist(next)
        }
    }

    /** 设置页手写的整段：写什么就是什么，空也照收，空就是往后建连接不再带记忆。 */
    suspend fun replace(value: String) {
        val next = value.trim()
        writing.withLock {
            if (state.value == next) return
            state.value = next
            persist(next)
        }
    }

    /**
     * 开场就位：库里留着上一场写下的那份就用它，库里没有这一行才种下出厂的起点。
     * 种下来的那份当场写库，下一次冷启动读到的是它，不再重种一遍。
     */
    suspend fun seedWith(value: String) {
        if (state.value.isNotBlank()) return
        state.value = value
        if (value.isNotBlank()) persist(value)
    }
}
