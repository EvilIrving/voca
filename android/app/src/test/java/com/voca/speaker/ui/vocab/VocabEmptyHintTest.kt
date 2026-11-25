package com.voca.speaker.ui.vocab

import com.voca.speaker.domain.VocabFilter
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 生词页空结果的说明：筛选只是把列表收窄到一档，搜索没命中的时候
 * 得说清是这一档里没有，不然读起来像整库都没有。
 */
class VocabEmptyHintTest {

    @Test
    fun `搜索没命中时带上当前档位名`() {
        assertEquals("今天里没有匹配的词", emptyHint("leverage", VocabFilter.Today))
        assertEquals("待复习里没有匹配的词", emptyHint("leverage", VocabFilter.Due))
        assertEquals("全部里没有匹配的词", emptyHint("leverage", VocabFilter.All))
    }

    @Test
    fun `没在搜索时说的是这一档本身空`() {
        assertEquals("今天还没有新词", emptyHint("", VocabFilter.Today))
        assertEquals("没有待复习的词", emptyHint("   ", VocabFilter.Due))
        assertEquals("还没有生词，对话里出现的地道搭配会自己进来", emptyHint("", VocabFilter.All))
    }
}
