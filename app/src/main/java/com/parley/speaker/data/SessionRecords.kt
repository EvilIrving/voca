package com.parley.speaker.data

import java.time.Instant
import java.time.ZoneId

/**
 * 一次已经结束的会话在时间线上的存档。除 [name] 与 [manualName] 由命名链路改写，
 * 其余字段在签到那一刻算定，之后保持原值。
 */
data class SessionRecord(
    val id: String,
    val name: String,
    val startedAt: Long,
    val endedAt: Long,
    val rounds: Int,
    val pronunciationAvg: Int?,
    val grammarAvg: Int?,
    val newWordCount: Int,
    val manualName: Boolean,
)

data class SessionGroup(
    val label: String,
    val sessions: List<SessionRecord>,
)

const val TodayLabel = "今天"
const val EarlierLabel = "更早"

/** 用本地时区的当日零点切分，而不是回溯固定小时数，换时区或跨夏令时都能分进正确的分组。 */
internal fun startOfLocalDay(now: Long): Long {
    val zone = ZoneId.systemDefault()
    return Instant.ofEpochMilli(now).atZone(zone).toLocalDate().atStartOfDay(zone).toInstant().toEpochMilli()
}

internal fun List<SessionRecord>.groupedByDay(now: Long): List<SessionGroup> {
    val today = startOfLocalDay(now)
    val fresh = filter { it.startedAt >= today }
    val older = filter { it.startedAt < today }
    return buildList {
        if (fresh.isNotEmpty()) add(SessionGroup(TodayLabel, fresh))
        if (older.isNotEmpty()) add(SessionGroup(EarlierLabel, older))
    }
}
