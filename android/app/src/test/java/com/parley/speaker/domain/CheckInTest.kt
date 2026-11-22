package com.parley.speaker.domain

import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CheckInTest {

    private val zone = ZoneId.of("Asia/Shanghai")
    private val today = LocalDate.of(2025, 3, 12) // 周三

    private fun at(date: LocalDate, hour: Int = 21, minute: Int = 0, second: Int = 0): Long =
        date.atTime(LocalTime.of(hour, minute, second)).atZone(zone).toInstant().toEpochMilli()

    /** 一轮：在 [start] 开口，说 [seconds] 秒，落库记在说完那一刻。 */
    private fun turn(start: Long, seconds: Long): SpokenTurn =
        SpokenTurn(endedAt = start + seconds * 1000L, durationMs = seconds * 1000L)

    private fun sec(seconds: Long): Long = seconds * 1000L

    private fun session(vararg turns: SpokenTurn) = PracticeSession(turns.toList())

    private fun stats(sessions: List<PracticeSession>) = checkInWeeks(sessions, at(today, 23, 30), zone)

    private fun todayCell(sessions: List<PracticeSession>) = stats(sessions).weeks.last()[today.dayOfWeek.value - 1]

    @Test
    fun `一句话说多久就是多久`() {
        assertEquals(3_000L, todayCell(listOf(session(turn(at(today), 3)))).activeMs)
        assertEquals(43_000L, todayCell(listOf(session(turn(at(today), 43)))).activeMs)
        assertEquals(60_000L, todayCell(listOf(session(turn(at(today), 60)))).activeMs)
    }

    @Test
    fun `听着想着没走开就整段算练习`() {
        // 说三十秒、停四十秒、再说三十秒：空档在五分钟以内，并成一块一百秒。
        val start = at(today, 9)
        val cell = todayCell(listOf(session(turn(start, 30), turn(start + sec(70), 30))))
        assertEquals(100_000L, cell.activeMs)
        assertEquals(1, cell.minutes)
    }

    @Test
    fun `连着对练一小时记一小时`() {
        // 五轮各说一分钟、轮间空档四十秒：整块是 5*60 + 4*40 = 460 秒。
        val start = at(today, 9)
        val turns = (0 until 5).map { turn(start + sec(it * 100L), 60) }
        assertEquals(460_000L, todayCell(listOf(session(*turns.toTypedArray()))).activeMs)
    }

    @Test
    fun `走开两小时只留两段录音`() {
        val start = at(today, 9)
        val grid = stats(listOf(session(turn(start, 3), turn(start + sec(7_200), 3))))
        assertEquals(6_000L, grid.totalMs)
        assertEquals("共 1 天 · 6 秒", grid.summary())
    }

    @Test
    fun `五分钟以内并成一块 超过就断开`() {
        val start = at(today, 9)
        // 第一轮说完到第二轮开口正好五分钟：并成一块三百二十秒。
        assertEquals(320_000L, todayCell(listOf(session(turn(start, 10), turn(start + sec(310), 10)))).activeMs)
        // 多一秒就断开，只留两段录音。
        assertEquals(20_000L, todayCell(listOf(session(turn(start, 10), turn(start + sec(311), 10)))).activeMs)
    }

    @Test
    fun `打字那几轮不打断合并`() {
        val start = at(today, 9)
        // 中间夹一轮打字（没有录音），前后两段录音照旧并成一块。
        val cell = todayCell(listOf(session(turn(start, 20), SpokenTurn(start + sec(40), 0), turn(start + sec(80), 20))))
        assertEquals(100_000L, cell.activeMs)
    }

    @Test
    fun `跨零点的块按日界切开`() {
        val start = at(today.minusDays(1), 23, 59, second = 40)
        val grid = stats(listOf(session(turn(start, 40))))
        assertEquals(20_000L, grid.weeks.last()[today.dayOfWeek.value - 2].activeMs)
        assertEquals(20_000L, grid.weeks.last()[today.dayOfWeek.value - 1].activeMs)
        assertEquals(2, grid.activeDays)
    }

    @Test
    fun `档位按分钟走`() {
        assertEquals(CheckInLevel.None, checkInLevel(0))
        assertEquals(CheckInLevel.Light, checkInLevel(1))
        assertEquals(CheckInLevel.Light, checkInLevel(4 * 60_000L + 59_000L))
        assertEquals(CheckInLevel.Steady, checkInLevel(5 * 60_000L))
        assertEquals(CheckInLevel.Steady, checkInLevel(14 * 60_000L + 59_000L))
        assertEquals(CheckInLevel.Solid, checkInLevel(15 * 60_000L))
        assertEquals(CheckInLevel.Solid, checkInLevel(29 * 60_000L + 59_000L))
        assertEquals(CheckInLevel.Deep, checkInLevel(30 * 60_000L))
    }

    @Test
    fun `二十六列一周一行首为周一`() {
        val grid = stats(emptyList())
        assertEquals(CheckInWeeks.Columns, grid.weeks.size)
        grid.weeks.forEach { column ->
            assertEquals(CheckInWeeks.Rows, column.size)
            column.forEachIndexed { row, day ->
                assertEquals(java.time.DayOfWeek.of(row + 1), day.date.dayOfWeek)
            }
        }
        assertEquals(today, grid.weeks.last()[today.dayOfWeek.value - 1].date)
        val thisMonday = today.minusDays((today.dayOfWeek.value - 1).toLong())
        assertEquals(thisMonday.minusWeeks(25), grid.weeks.first().first().date)
    }

    @Test
    fun `不同会话不会并成一块`() {
        // 两段录音只隔一分钟，但分属两段会话：各算各的，合计仍是两段录音。
        val start = at(today, 9)
        val grid = stats(listOf(session(turn(start, 20)), session(turn(start + sec(80), 20))))
        assertEquals(40_000L, grid.totalMs)
    }

    @Test
    fun `本周没到的日子不进统计`() {
        val future = today.plusDays(2)
        val grid = stats(listOf(session(turn(at(future), 40))))
        assertEquals(CheckInLevel.None, grid.weeks.last()[future.dayOfWeek.value - 1].level)
        assertEquals(0, grid.activeDays)
    }

    @Test
    fun `窗口外的练习不进统计`() {
        val grid = stats(listOf(session(turn(at(today.minusWeeks(27)), 30 * 60))))
        assertEquals(0, grid.activeDays)
        assertEquals(0L, grid.totalMs)
    }

    @Test
    fun `没有录音的一段不点亮`() {
        val grid = stats(listOf(PracticeSession(emptyList())))
        assertEquals(0, grid.activeDays)
        assertEquals("共 0 天 · 0 秒", grid.summary())
    }

    @Test
    fun `汇总只写几个真数`() {
        val grid = stats(
            listOf(
                session(turn(at(today, 9), 26 * 60)),
                session(turn(at(today, 15), 31 * 60)),
                session(turn(at(today.minusDays(1), 21), 40 * 60)),
            ),
        )
        assertEquals(2, grid.activeDays)
        assertEquals((26 + 31 + 40) * 60_000L, grid.totalMs)
        assertEquals("共 2 天 · 1 时 37 分", grid.summary())
    }

    @Test
    fun `月份行在跨月那一列起字`() {
        val labels = stats(emptyList()).monthLabels()
        assertEquals(listOf(0, 7, 11, 16, 20, 24), labels.map { it.column })
        assertEquals(listOf("9月", "11月", "12月", "1月", "2月", "3月"), labels.map { it.label })
        assertTrue(labels.none { it.label == "10月" })
    }

    @Test
    fun `点中一格读日期与长度`() {
        val grid = stats(listOf(session(turn(at(today), 20 * 60))))
        assertEquals("3月12日 · 20 分", grid.weeks.last()[today.dayOfWeek.value - 1].readout())
        assertEquals("3月12日 · 40 秒", todayCell(listOf(session(turn(at(today), 40)))).readout())
        assertEquals("1月1日 · 0 分", CheckInDay(LocalDate.of(2026, 1, 1), 0, CheckInLevel.None).readout())
    }

    @Test
    fun `整块不会超过当天的墙钟`() {
        // 一块练习的长度顶多等于它首尾按时钟走的距离，合并只会填中间的短空档。
        val start = at(today, 9)
        val session = session(turn(start, 60), turn(start + sec(100), 60), turn(start + sec(200), 60))
        session.practiceBlocks().forEach { block ->
            assertTrue(block.durationMs <= 200_000L + 60_000L)
        }
    }

    @Test
    fun `分钟数的边界与填充都齐`() {
        val grid = stats(listOf(session(turn(at(today), 30 * 60))))
        val flat = grid.weeks.flatten()
        assertEquals(CheckInWeeks.Columns * CheckInWeeks.Rows, flat.size)
        assertTrue(flat.all { it.activeMs >= 0 })
    }
}
