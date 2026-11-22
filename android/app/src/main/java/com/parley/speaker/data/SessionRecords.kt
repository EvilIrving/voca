package com.parley.speaker.data

import java.time.Instant
import java.time.LocalDate
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
const val WeekLabel = "7天内"
const val EarlierLabel = "更早"

/** 用本地时区的当日零点切分，而不是回溯固定小时数，换时区或跨夏令时都能分进正确的分组。 */
internal fun startOfLocalDay(now: Long): Long = atStartOfDay(Instant.ofEpochMilli(now).atZone(ZoneId.systemDefault()).toLocalDate())

private fun atStartOfDay(date: LocalDate): Long {
    val zone = ZoneId.systemDefault()
    return date.atStartOfDay(zone).toInstant().toEpochMilli()
}

/** 三段切分：今天、前六天、更早。同段内保持传进来的顺序，排序由存储层决定，这里不再动。 */
internal fun List<SessionRecord>.groupedByDay(now: Long): List<SessionGroup> {
    val today = Instant.ofEpochMilli(now).atZone(ZoneId.systemDefault()).toLocalDate()
    val todayStart = atStartOfDay(today)
    val weekStart = atStartOfDay(today.minusDays(6))
    val fresh = filter { it.startedAt >= todayStart }
    val week = filter { it.startedAt >= weekStart && it.startedAt < todayStart }
    val older = filter { it.startedAt < weekStart }
    return buildList {
        if (fresh.isNotEmpty()) add(SessionGroup(TodayLabel, fresh))
        if (week.isNotEmpty()) add(SessionGroup(WeekLabel, week))
        if (older.isNotEmpty()) add(SessionGroup(EarlierLabel, older))
    }
}
