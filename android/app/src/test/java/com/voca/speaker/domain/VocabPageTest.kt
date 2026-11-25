package com.voca.speaker.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 生词库的筛选口径：三档按复习状态分，计数与列表同源；词条类型只落在卡片标签上。
 */
class VocabPageTest {

    private val day = 86_400_000L
    private val todayStart = 1_700_006_400_000L

    private fun card(
        word: String,
        repeats: Int,
        lastSeenAt: Long,
        meaning: String = "释义",
        phonetic: String = "/x/",
    ) = VocabCard(
        id = word,
        word = word,
        phonetic = phonetic,
        meaning = meaning,
        example = "I said $word.",
        highlight = word,
        repeats = repeats,
        lastSeenAt = lastSeenAt,
    )

    private val todayWord = card("coffee", repeats = 2, lastSeenAt = todayStart + 3_600_000)
    private val todayCollocation = card("grab a bite", repeats = 1, lastSeenAt = todayStart + 60_000)
    private val dueOlder = card("sleep in", repeats = 1, lastSeenAt = todayStart - 2 * day)
    private val dueNewer = card("toss and turn", repeats = 1, lastSeenAt = todayStart - day)
    private val dueFrequent = card("burn out", repeats = 3, lastSeenAt = todayStart - 3 * day)
    private val earlier = card("commute", repeats = 0, lastSeenAt = todayStart - 5 * day)

    private val cards = listOf(
        todayCollocation,
        dueFrequent,
        earlier,
        todayWord,
        dueOlder,
        dueNewer,
    )

    @Test
    fun `三档计数按复习状态分`() {
        val page = vocabPage(cards, todayStart, VocabFilter.All)

        assertEquals(VocabCounts(today = 2, due = 3, all = 6), page.counts)
        assertEquals(2, page.counts[VocabFilter.Today])
        assertEquals(3, page.counts[VocabFilter.Due])
        assertEquals(6, page.counts[VocabFilter.All])
    }

    @Test
    fun `今天这一档按最近出现时间倒序`() {
        val page = vocabPage(cards, todayStart, VocabFilter.Today)

        assertEquals(listOf(VocabBucket.Today), page.sections.map { it.bucket })
        assertEquals(listOf("coffee", "grab a bite"), page.sections.single().cards.map { it.word })
    }

    @Test
    fun `待复习按复现次数升序 同次数日期旧的在前`() {
        val page = vocabPage(cards, todayStart, VocabFilter.Due)

        assertEquals(listOf(VocabBucket.Due), page.sections.map { it.bucket })
        assertEquals(listOf("sleep in", "toss and turn", "burn out"), page.sections.single().cards.map { it.word })
    }

    @Test
    fun `全部按今天 待复习 更早铺开`() {
        val page = vocabPage(cards, todayStart, VocabFilter.All)

        assertEquals(listOf(VocabBucket.Today, VocabBucket.Due, VocabBucket.Earlier), page.sections.map { it.bucket })
        assertEquals(page.counts.all, page.sections.sumOf { it.cards.size })
        assertEquals(listOf("commute"), page.sections.last().cards.map { it.word })
    }

    @Test
    fun `没有内容的分组不显示`() {
        val page = vocabPage(listOf(todayWord, earlier), todayStart, VocabFilter.All)

        assertEquals(listOf(VocabBucket.Today, VocabBucket.Earlier), page.sections.map { it.bucket })
        assertTrue(vocabPage(listOf(todayWord, earlier), todayStart, VocabFilter.Due).sections.isEmpty())
        assertEquals(0, vocabPage(listOf(todayWord, earlier), todayStart, VocabFilter.Due).counts.due)
    }

    @Test
    fun `今天零点露面的词算今天`() {
        val page = vocabPage(listOf(card("dawn", repeats = 0, lastSeenAt = todayStart)), todayStart, VocabFilter.Today)

        assertEquals(listOf("dawn"), page.sections.single().cards.map { it.word })
        assertEquals(0, page.counts.due)
    }

    @Test
    fun `标签先打词条类型 再接复现次数`() {
        val page = vocabPage(cards, todayStart, VocabFilter.All)
        val due = page.sections.first { it.bucket == VocabBucket.Due }.cards.first()
        val earlierCard = page.sections.first { it.bucket == VocabBucket.Earlier }.cards.single()

        assertEquals(listOf("地道搭配", "复现 1 次"), due.tags().map { it.text })
        assertEquals(listOf(VocabTagKind.Type, VocabTagKind.Repeats), due.tags().map { it.kind })
        assertEquals("生词", earlierCard.tags().first().text)
        assertNotEquals(VocabBucket.Due.label, due.tags().first().text)
        assertFalse(due.tags().any { tag -> VocabBucket.entries.any { it.label == tag.text } })
    }

    @Test
    fun `搜索与筛选同时生效`() {
        val page = vocabPage(cards, todayStart, VocabFilter.Due, "toss")

        assertEquals(listOf(VocabBucket.Due), page.sections.map { it.bucket })
        assertEquals(listOf("toss and turn"), page.sections.single().cards.map { it.word })
        assertEquals(VocabCounts(today = 0, due = 1, all = 1), page.counts)
    }

    @Test
    fun `筛选今天时搜索不到的词只改计数不改列表`() {
        val page = vocabPage(cards, todayStart, VocabFilter.Today, "toss")

        assertTrue(page.sections.isEmpty())
        assertEquals(1, page.counts[VocabFilter.Due])
        assertEquals(1, page.counts[VocabFilter.All])
    }

    @Test
    fun `音标也参与搜索`() {
        val page = vocabPage(
            listOf(card("coffee", repeats = 1, lastSeenAt = todayStart, phonetic = "/ˈkɒfi/")),
            todayStart,
            VocabFilter.All,
            "kɒf",
        )

        assertEquals(listOf("coffee"), page.sections.single().cards.map { it.word })
    }

    @Test
    fun `释义那一栅的搜索也不分大小写`() {
        // 词形与音标都不含这个串，只靠释义命中，而且大小写对不上。
        val americano = card(
            "americano",
            repeats = 1,
            lastSeenAt = todayStart,
            meaning = "美式 Espresso Diluted",
            phonetic = "/əˌmerɪˈkɑːnəʊ/",
        )

        val page = vocabPage(listOf(americano), todayStart, VocabFilter.All, "espresso DILUTED")

        assertEquals(listOf("americano"), page.sections.single().cards.map { it.word })
        assertEquals(1, page.counts.all)
    }
}
