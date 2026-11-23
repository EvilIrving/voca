package com.parley.speaker.ui.chat

import com.parley.speaker.domain.AnalysisStatus
import com.parley.speaker.domain.GrammarReview
import com.parley.speaker.domain.Review
import com.parley.speaker.domain.Speaker
import com.parley.speaker.domain.Turn
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 智能体气泡下面那条常驻动作条：六枚的次序、哪一枚按状态改动作词、哪一枚按状态压成禁用，
 * 以及复制与重新分析各自落到哪一段文字上。
 */
class MessageActionsTest {

    private fun echo(
        id: String = "e1",
        text: String = "Good morning! Are you craving an Americano?",
        saved: Boolean = false,
        translation: String? = null,
    ) = Turn(id = id, speaker = Speaker.Echo, text = text, saved = saved, translation = translation)

    private fun user(id: String, analysis: AnalysisStatus) = Turn(
        id = id,
        speaker = Speaker.User,
        text = "I get up early today",
        analysis = analysis,
        review = if (analysis == AnalysisStatus.Ready) {
            Review("I get up early today", GrammarReview(82, "时态"), null, emptyList())
        } else {
            null
        },
    )

    @Test
    fun `六枚永远都在且顺序与画板一致`() {
        val bar = messageActions(MessageActionsState(text = echo().text, retryable = true))

        assertEquals(MessageCommand.entries.toList(), bar.map { it.command })
        assertEquals(6, bar.size)
        assertEquals(
            listOf("朗读", "翻译", "收进生词本", "复制原文", "重新分析", "删除"),
            bar.map { it.label },
        )
    }

    @Test
    fun `分析过的轮没有可重新分析的东西，那一枚压成禁用而不是消失`() {
        val todo = messageActions(MessageActionsState(text = echo().text, retryable = true))
        assertTrue("还没分析的一轮该能重新分析", todo.first { it.command == MessageCommand.Retry }.enabled)

        val done = messageActions(MessageActionsState(text = echo().text, retryable = false))
        assertEquals("禁用不等于换位置", 6, done.size)
        assertFalse("分析成功的一轮不给重新分析", done.first { it.command == MessageCommand.Retry }.enabled)
        assertEquals(MessageCommand.Delete, done.last().command)
    }

    @Test
    fun `收藏过的动作词改成取消收藏`() {
        val saved = messageActions(MessageActionsState(text = echo().text, saved = true))
        assertEquals("取消收藏", saved.first { it.command == MessageCommand.Save }.label)

        val fresh = messageActions(MessageActionsState(text = echo().text, saved = false))
        assertEquals("收进生词本", fresh.first { it.command == MessageCommand.Save }.label)
    }

    @Test
    fun `译文展开时翻译那枚的动作词变成收起译文`() {
        val open = messageActions(MessageActionsState(text = echo().text, expanded = true))
        assertEquals("收起译文", open.first { it.command == MessageCommand.Translate }.label)

        val closed = messageActions(MessageActionsState(text = echo().text, expanded = false))
        assertEquals("翻译", closed.first { it.command == MessageCommand.Translate }.label)
    }

    @Test
    fun `翻译还在路上或没有正文时翻译禁用，朗读一直能点`() {
        val flying = messageActions(MessageActionsState(text = echo().text, translating = true))
        assertFalse(flying.first { it.command == MessageCommand.Translate }.enabled)
        assertTrue(flying.first { it.command == MessageCommand.Speak }.enabled)

        val empty = messageActions(MessageActionsState(text = ""))
        assertFalse(empty.first { it.command == MessageCommand.Translate }.enabled)
        assertFalse(empty.first { it.command == MessageCommand.Copy }.enabled)
    }

    @Test
    fun `复制拿到的是正文而不是译文`() {
        val turn = echo(text = "I'd love a flat white.", translation = "我想要一杯馥芮白。")

        assertEquals("I'd love a flat white.", copiedText(turn))
    }

    @Test
    fun `重新分析落到同一轮里用户那句上`() {
        val failed = listOf(user("u1", AnalysisStatus.Failed), echo(id = "e1"))
        assertEquals("u1", retryTargetOf(failed, "e1"))

        val unanalyzed = listOf(user("u1", AnalysisStatus.None), echo(id = "e1"))
        assertEquals("u1", retryTargetOf(unanalyzed, "e1"))

        val scored = listOf(user("u1", AnalysisStatus.Ready), echo(id = "e1"))
        assertNull("已经评过的一轮没有可重试的东西", retryTargetOf(scored, "e1"))

        val greeting = listOf(echo(id = "e0"))
        assertNull("开场问候前面没有用户那句", retryTargetOf(greeting, "e0"))
    }

    @Test
    fun `这一轮的用户句被删掉时不拿更早的用户句顶上`() {
        // e2 前面那个用户轮被整条删了，列表里只剩 u1、e1 与 e2；
        // 按「往前最近的用户轮」会落到 u1，评的是上一轮的话。
        val holed = listOf(user("u1", AnalysisStatus.Failed), echo(id = "e1"), echo(id = "e2"))
        assertNull("隔了别的轮就不是同一轮，评错人不如不给重试", retryTargetOf(holed, "e2"))

        // 紧邻的那一位是用户句时认它，排在第几位都不影响。
        val adjacent = listOf(echo(id = "e1"), user("u2", AnalysisStatus.Failed), echo(id = "e2"))
        assertEquals("u2", retryTargetOf(adjacent, "e2"))
    }
}
