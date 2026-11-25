package com.voca.speaker.domain

import org.junit.Assert.assertEquals
import org.junit.Test

/** 释义后面那条括号注释该切干净，切完不留孤零零的标点与空格。 */
class MeaningTest {

    @Test
    fun `全角括号注释连同内容一起去掉`() {
        assertEquals("最近怎么样？", "最近怎么样？ （日常随口问候）".withoutAnnotation())
    }

    @Test
    fun `半角括号同样处理`() {
        assertEquals("有空、能抽出一点时间", "有空、能抽出一点时间 (informal)".withoutAnnotation())
    }

    @Test
    fun `注释前面带逗号时一并收干净`() {
        assertEquals("再说一遍？", "再说一遍？， (非正式口语)".withoutAnnotation())
    }

    @Test
    fun `没有注释的释义原样保留`() {
        assertEquals("反复回到、总是去", "反复回到、总是去".withoutAnnotation())
    }
}
