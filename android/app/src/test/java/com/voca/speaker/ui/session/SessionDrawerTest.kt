package com.voca.speaker.ui.session

import androidx.compose.ui.unit.dp
import com.voca.speaker.data.SessionRecord
import com.voca.speaker.data.startOfLocalDay
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 左侧抽屉：检索按名字过滤、首行新会话、下面按今天与 7天内与更早分组，
 * 正在看的那条高亮；松手落哪一档先看速度、再看推开多少。
 */
class SessionDrawerTest {

    private val now = System.currentTimeMillis()
    private val today = startOfLocalDay(now) + 60 * 60 * 1000L
    private val threeDaysAgo = startOfLocalDay(now) - 3 * 24 * 60 * 60 * 1000L
    private val lastMonth = startOfLocalDay(now) - 40L * 24 * 60 * 60 * 1000L

    private fun record(id: String, name: String, startedAt: Long) = SessionRecord(
        id = id,
        name = name,
        startedAt = startedAt,
        endedAt = startedAt + 60_000L,
        rounds = 4,
        pronunciationAvg = 82,
        grammarAvg = 74,
        newWordCount = 2,
        manualName = false,
    )

    @Test
    fun `按今天与七天内与更早三段分组，组内保持传进来的顺序`() {
        val sessions = listOf(
            record("a", "没睡好的早晨", today),
            record("b", "咖啡店点单", today + 1_000L),
            record("c", "面试自我介绍", threeDaysAgo),
            record("d", "机场问路", lastMonth),
        )

        val model = sessionDrawerModel(sessions, viewingId = null, now = now)

        assertEquals(listOf("今天", "7天内", "更早"), model.groups.map { it.label })
        assertEquals(listOf("a", "b"), model.groups[0].sessions.map { it.id })
        assertEquals(listOf("c"), model.groups[1].sessions.map { it.id })
        assertEquals(listOf("d"), model.groups[2].sessions.map { it.id })
    }

    @Test
    fun `检索按名字过滤，分组跟着只剩命中的那几条`() {
        val sessions = listOf(
            record("a", "咖啡店点单", today),
            record("b", "机场问路", threeDaysAgo),
            record("c", "咖啡馆闲谈", lastMonth),
        )

        val hit = sessionDrawerModel(sessions, viewingId = null, query = " 咖啡 ", now = now)

        assertEquals(listOf("今天", "更早"), hit.groups.map { it.label })
        assertEquals(listOf("a", "c"), hit.groups.flatMap { it.sessions }.map { it.id })

        val miss = sessionDrawerModel(sessions, viewingId = null, query = "面试", now = now)
        assertTrue(miss.groups.isEmpty())
    }

    @Test
    fun `正在看的那条高亮`() {
        val sessions = listOf(record("a", "没睡好的早晨", today), record("b", "咖啡店点单", lastMonth))

        val viewing = sessionDrawerModel(sessions, viewingId = "b", now = now)
        assertTrue(viewing.isCurrent(sessions[1]))
        assertFalse(viewing.isCurrent(sessions[0]))

        val live = sessionDrawerModel(sessions, viewingId = null, now = now)
        assertTrue("没在回看就谁都不标", live.groups.flatMap { it.sessions }.none { live.isCurrent(it) })
    }

    @Test
    fun `抽屉按屏宽八成二收，宽屏封在三百二十`() {
        assertEquals(295.2f, drawerWidth(360.dp).value, 0.01f)
        assertEquals(262.4f, drawerWidth(320.dp).value, 0.01f)
        assertEquals(320f, drawerWidth(520.dp).value, 0.01f)
        // 再窄也留出右侧那一条旧页，不让抽屉糊满整屏。
        assertEquals(229.6f, drawerWidth(280.dp).value, 0.01f)
    }

    @Test
    fun `松手落点先看速度，速度不够就看走过半程没有`() {
        val velocity = 400f

        assertTrue("朝右甩出去就打开", drawerTarget(0.1f, 900f, velocity))
        assertFalse("朝左甩回来就收起", drawerTarget(0.9f, -900f, velocity))
        assertTrue("没速度但走过半程", drawerTarget(0.7f, 0f, velocity))
        assertFalse("没速度也只走了一小半", drawerTarget(0.4f, 0f, velocity))
        assertFalse("正好半程不算打开", drawerTarget(0.5f, 120f, velocity))
    }

    @Test
    fun `卡片圆角前半程收到二十六、推开后定格在三十二`() {
        assertEquals(0.dp, SessionDrawerSpec.cardRadius(0f))
        assertEquals(" 拖到一半就是画板 02 那一帧的值", 26.dp, SessionDrawerSpec.cardRadius(0.5f))
        assertEquals(32.dp, SessionDrawerSpec.cardRadius(1f))
        assertEquals("拖过头不超三十二", 32.dp, SessionDrawerSpec.cardRadius(1.4f))
        assertEquals("往回收不落负值", 0.dp, SessionDrawerSpec.cardRadius(-0.2f))

        var last = -1f
        for (step in 0..10) {
            val radius = SessionDrawerSpec.cardRadius(step / 10f).value
            assertTrue("圆角只涨不回落", radius >= last)
            last = radius
        }
    }

    @Test
    fun `检索三十起、新会话五十二、会话行四十四，设置页收在四十四`() {
        assertEquals(36.dp, SessionDrawerSpec.SearchButton)
        assertEquals("标题与检索同一行，高四十四", 44.dp, SessionDrawerSpec.HeaderHeight)
        assertEquals("新会话那一行单独高一档", 52.dp, SessionDrawerSpec.NewRowHeight)
        assertEquals("新会话只留一枚二十四的气泡加号", 24.dp, SessionDrawerSpec.NewGlyph)
        assertEquals(44.dp, SessionDrawerSpec.RowHeight)
        assertEquals(44.dp, SessionDrawerSpec.SettingsButton)
        assertEquals("底栏那条录音带不参与拉开抽屉", 96.dp, SessionDrawerSpec.SwipeGuardBottom)
        assertEquals("主卡片收 0.94、白蒙层压六成", 0.94f, SessionDrawerSpec.ContentScale, 0.0001f)
        assertEquals(0.6f, SessionDrawerSpec.VeilAlpha, 0.0001f)
    }
}
