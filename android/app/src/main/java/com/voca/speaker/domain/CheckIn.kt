package com.voca.speaker.domain

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * 一天练了多长落哪一档。热力图的颜色只由这一档决定，界面不再按分钟另算：
 * 有练习就点亮，五分钟以内最浅，五到十四分钟一档，十五到二十九分钟一档，三十分钟以上最深。
 */
enum class CheckInLevel { None, Light, Steady, Solid, Deep }

/** 热力图上的一格：本地日期、当日算出的练习时长，以及由这个长度推出来的档位。 */
data class CheckInDay(val date: LocalDate, val activeMs: Long, val level: CheckInLevel) {
    val minutes: Int get() = (activeMs / 60_000L).toInt()
}

/**
 * 二十六周乘七天的一块热力图。weeks 按列存，一列一周、行首是周一；
 * 末列走过今天的那些格子照画，但不进 [activeDays] 与 [totalMs]。
 */
data class CheckInWeeks(
    val weeks: List<List<CheckInDay>>,
    val activeDays: Int,
    val totalMs: Long,
) {
    /** 卡片宽三百二十、去掉左右各十四的内缩，正好铺二十六列七点八五的格子。 */
    companion object {
        const val Columns = 26
        const val Rows = 7
    }
}

/** 一轮说过的音频：这一轮落库的时刻，以及这段录音本身有多长。 */
data class SpokenTurn(val endedAt: Long, val durationMs: Long)

/** 一段会话里说过的那些轮次。分段的意义在于：跨会话的两段录音永远不会被合并成一块。 */
data class PracticeSession(val turns: List<SpokenTurn>)

/**
 * 两段录音之间不超过这一截就算人还在练：中间在听、在想、在复述，这段空档一并计入。
 * 超过这一截说明已经走开，前后断成两块，空档不算。
 */
const val IdleGapMs = 5 * 60_000L

/** 一段连着的练习：从这一块第一段录音开口，到最后一个字说完，中间只允许五分钟以内的空档。 */
data class PracticeBlock(val from: Long, val to: Long) {
    val durationMs: Long get() = to - from
}

/**
 * 把一段会话合成若干块练习。每轮录音是一个区间（落库时刻往前推录音长度就是开口的时刻），
 * 相邻两个区间之间的空档不超过五分钟就并进同一块，超过就断开。
 *
 * 为什么按区间而不是按录音相加：一次对练里说话只占一部分，听模型说、想下一句都在练；
 * 只要没走开，这段就整段算练习。反过来，走开两小时再回来，那两小时没有录音区间，
 * 前后隔得太远也就不会并成一块，两小时不会被算成练习。
 */
fun PracticeSession.practiceBlocks(): List<PracticeBlock> {
    val spans = turns
        .filter { it.durationMs > 0L }
        .map { (it.endedAt - it.durationMs) to it.endedAt }
        .sortedBy { it.first }
    val blocks = ArrayList<PracticeBlock>(spans.size)
    spans.forEach { (from, to) ->
        val last = blocks.lastOrNull()
        if (last != null && from - last.to <= IdleGapMs) {
            blocks[blocks.lastIndex] = PracticeBlock(last.from, maxOf(last.to, to))
        } else {
            blocks += PracticeBlock(from, to)
        }
    }
    return blocks
}

fun checkInLevel(durationMs: Long): CheckInLevel = when {
    durationMs <= 0L -> CheckInLevel.None
    durationMs < 5 * 60_000L -> CheckInLevel.Light
    durationMs < 15 * 60_000L -> CheckInLevel.Steady
    durationMs < 30 * 60_000L -> CheckInLevel.Solid
    else -> CheckInLevel.Deep
}

/** 网格首列那个周一的零点：读库从这一刻起就够，早于它的一格都不会亮。 */
fun checkInWindowStart(
    now: Long,
    zone: ZoneId = ZoneId.systemDefault(),
    columns: Int = CheckInWeeks.Columns,
): Long = firstMonday(now, zone, columns).atStartOfDay(zone).toInstant().toEpochMilli()

private fun firstMonday(now: Long, zone: ZoneId, columns: Int): LocalDate {
    val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
    return today.minusDays((today.dayOfWeek.value - 1).toLong()).minusWeeks((columns - 1).toLong())
}

fun checkInWeeks(
    sessions: List<PracticeSession>,
    now: Long,
    zone: ZoneId = ZoneId.systemDefault(),
    columns: Int = CheckInWeeks.Columns,
): CheckInWeeks {
    val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
    val firstMonday = firstMonday(now, zone, columns)
    val windowStart = firstMonday.atStartOfDay(zone).toInstant().toEpochMilli()

    // 一块练习跨过零点就按日界切开，各归各家；先按毫秒累加再折成分钟，逐段取整会白扔掉。
    val millisByDay = HashMap<LocalDate, Long>()
    sessions.forEach { session ->
        session.practiceBlocks().forEach { block ->
            var cursor = maxOf(block.from, windowStart)
            val until = minOf(block.to, now)
            while (cursor < until) {
                val date = Instant.ofEpochMilli(cursor).atZone(zone).toLocalDate()
                val midnight = date.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
                val end = minOf(until, midnight)
                millisByDay[date] = (millisByDay[date] ?: 0L) + (end - cursor)
                cursor = end
            }
        }
    }

    var activeDays = 0
    var totalMs = 0L
    val weeks = (0 until columns).map { index ->
        val monday = firstMonday.plusWeeks(index.toLong())
        (0 until CheckInWeeks.Rows).map { row ->
            val date = monday.plusDays(row.toLong())
            val active = if (date > today) 0L else millisByDay[date] ?: 0L
            val level = checkInLevel(active)
            if (level != CheckInLevel.None) {
                activeDays += 1
                totalMs += active
            }
            CheckInDay(date, active, level)
        }
    }
    return CheckInWeeks(weeks, activeDays, totalMs)
}

/** 月份行的一条：第几列起字，写几个月。 */
data class CheckInMonthLabel(val column: Int, val label: String)

/**
 * 月份行只在跨月那一列起字；与上一条离得不足四列就跳过这一条，
 * 两三个字挤在十来像素一列上谁也读不出来。
 */
fun CheckInWeeks.monthLabels(): List<CheckInMonthLabel> {
    val labels = mutableListOf<CheckInMonthLabel>()
    var lastMonth = -1
    var lastColumn = -MonthLabelGap
    weeks.forEachIndexed { column, days ->
        val month = days.first().date.monthValue
        if (month == lastMonth) return@forEachIndexed
        lastMonth = month
        if (column - lastColumn < MonthLabelGap) return@forEachIndexed
        labels += CheckInMonthLabel(column, "${month}月")
        lastColumn = column
    }
    return labels
}

private const val MonthLabelGap = 4

/** 点中某一格时那一行的读数：日期加当天说过的长度，不足一分钟就写秒。 */
fun CheckInDay.readout(): String {
    val head = "${date.monthValue}月${date.dayOfMonth}日 · "
    if (activeMs <= 0L) return "${head}0 分"
    return if (activeMs < 60_000L) "${head}${activeMs / 1_000L} 秒" else "$head$minutes 分"
}

/** 卡片底下那一行汇总：几个真数，不写解释。 */
fun CheckInWeeks.summary(): String {
    val hours = totalMs / 3_600_000L
    val minutes = (totalMs % 3_600_000L) / 60_000L
    val seconds = (totalMs % 60_000L) / 1_000L
    val body = when {
        hours > 0L -> "$hours 时 $minutes 分"
        minutes > 0L -> "$minutes 分"
        else -> "$seconds 秒"
    }
    return "共 $activeDays 天 · $body"
}
