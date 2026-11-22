package com.parley.speaker.ui.chat

/**
 * 静默收段：界面没有操作、也没有在收音，距上次活动满三十分钟就该收。
 * 判定是纯函数，走表只放在 ViewModel，界面不自己起定时器。
 */
object IdleCut {
    const val WindowMs = 30 * 60 * 1000L

    /** 走表间隔：三十秒查一次，收段最多晚半分钟，对半小时这条线没有意义。 */
    const val CheckMs = 30_000L

    fun reached(lastActivityAt: Long, now: Long, recording: Boolean): Boolean =
        !recording && now - lastActivityAt >= WindowMs
}

/**
 * 一段会话的账。起点定了就不改，[closed] 一立起来这一段就只交出去一次，
 * 切场景与静默判定同时到点也只落一条记录。
 */
data class Segment(
    val id: String,
    val startedAt: Long,
    val lastActivityAt: Long,
    val closed: Boolean = false,
)

/**
 * 当前这一段的账本。四种时机都走 [closeIfOpen]，交出来的永远是还没收过的那一段，
 * 收完停在闭合态，要接着开就调 [begin] 换一段新的。
 * 收段与开段是两步：切到别的会话先只收，退出回看再开；切场景、点新会话、静默到点收完就开。
 */
class SessionLedger(private val clock: () -> Long = System::currentTimeMillis) {

    private var opened = 0

    var current: Segment = newSegment()
        private set

    fun begin(): Segment {
        current = newSegment()
        return current
    }

    /** 用户这一手算作活动：静默三十分钟从这一刻重新数。 */
    fun touch() {
        val now = clock()
        if (now > current.lastActivityAt) current = current.copy(lastActivityAt = now)
    }

    /** 要落库的那一段；已经收过了就给 null，落库因此在触发层就写不成两遍。 */
    fun closeIfOpen(): Segment? {
        if (current.closed) return null
        current = current.copy(closed = true)
        return current
    }

    fun idleDue(recording: Boolean): Boolean =
        !current.closed && IdleCut.reached(current.lastActivityAt, clock(), recording)

    // 同一个毫秒里连开两段也要各是各的，落库按 id 认人。
    private fun newSegment(): Segment {
        val now = clock()
        opened += 1
        return Segment("session-$now-$opened", now, now)
    }
}

/**
 * 一段会话开场时定下的两样东西。换会话只换场景，角色提示词原样带走，
 * 设置里改过的角色要等下一段开门才生效。
 */
data class SegmentPlan(val scenario: String, val prompt: String) {

    /** 切场景：场景换成新选的，提示词一个字不动。 */
    fun withScenario(picked: String): SegmentPlan = copy(scenario = picked)

    /** 开新的一段：提示词按当下设置刷新，场景照旧。 */
    fun resuming(prompt: String): SegmentPlan = copy(prompt = prompt)
}
