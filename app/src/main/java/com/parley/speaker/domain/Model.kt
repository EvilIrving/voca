package com.parley.speaker.domain

import kotlinx.serialization.Serializable

@Serializable
enum class Speaker { Echo, User }

@Serializable
data class PhonemeIssue(
    val word: String,
    val phonetic: String,
    val note: String,
)

@Serializable
data class PronunciationReview(
    val score: Int,
    val note: String,
    val phonemes: List<PhonemeIssue> = emptyList(),
)

@Serializable
data class GrammarReview(
    val score: Int,
    val note: String,
)

@Serializable
enum class Register(val label: String) {
    Idiomatic("地道表达"),
    Advanced("高级表达"),
}

@Serializable
data class Rewrite(
    val register: Register,
    val text: String,
    val gloss: String,
)

@Serializable
data class Review(
    val original: String,
    val grammar: GrammarReview,
    val pronunciation: PronunciationReview?,
    val rewrites: List<Rewrite>,
)

@Serializable
enum class AnalysisStatus { None, Pending, Ready, Failed }

@Serializable
data class Turn(
    val id: String,
    val speaker: Speaker,
    val text: String,
    val review: Review? = null,
    val audioPath: String? = null,
    val analysis: AnalysisStatus = AnalysisStatus.None,
    val saved: Boolean = false,
    /** 气泡译文的落库位。非空表示这一轮已经译过，重开对话不再请求文本模型。 */
    val translation: String? = null,
    val sessionId: String? = null,
)

@Serializable
data class VocabCard(
    val id: String,
    val word: String,
    val phonetic: String,
    val meaning: String,
    val example: String,
    val highlight: String,
    val repeats: Int,
    /** 0 表示这份词条还没从库里读出来，写入时由存储层盖上当下时间。 */
    val lastSeenAt: Long = 0,
)

/** 生词按复习状态分档：今天新收录的、该回看的、以及更早收下但还不用复习的。 */
enum class VocabBucket(val label: String) {
    Today("今天"),
    Due("待复习"),
    Earlier("更早"),
}

/** 筛选条的三档。词条类型不占档位，它是卡片属性。 */
enum class VocabFilter(val label: String) {
    Today("今天"),
    Due("待复习"),
    All("全部"),
}

/** 带空格的词形算地道搭配，单个词算生词。 */
enum class VocabEntryType(val label: String) {
    Collocation("地道搭配"),
    Word("生词"),
}

val VocabCard.entryType: VocabEntryType
    get() = if (word.trim().contains(' ')) VocabEntryType.Collocation else VocabEntryType.Word

enum class VocabTagKind { Type, Repeats }

data class VocabTag(val text: String, val kind: VocabTagKind)

/** 卡片上的两枚标签：词条类型在前，复现次数跟在后面。 */
fun VocabCard.tags(): List<VocabTag> = listOf(
    VocabTag(entryType.label, VocabTagKind.Type),
    VocabTag("复现 $repeats 次", VocabTagKind.Repeats),
)

data class VocabSection(val bucket: VocabBucket, val cards: List<VocabCard>)

data class VocabCounts(val today: Int, val due: Int, val all: Int) {
    operator fun get(filter: VocabFilter): Int = when (filter) {
        VocabFilter.Today -> today
        VocabFilter.Due -> due
        VocabFilter.All -> all
    }
}

data class VocabPage(val counts: VocabCounts, val sections: List<VocabSection>)

/**
 * 生词库一页的全部口径：按复习状态分档，搜索收窄，筛选决定摊开哪几档。
 * 档位计数与列表从同一份分档结果来；计数按搜索后的集合算，所以档位上的数字
 * 与点进去看到的条数始终一致。
 */
fun vocabPage(
    cards: List<VocabCard>,
    todayStart: Long,
    filter: VocabFilter,
    query: String = "",
): VocabPage {
    val matched = cards.filter { it.matches(query) }.groupBy { it.bucket(todayStart) }
    val sections = VocabBucket.entries.mapNotNull { bucket ->
        val cards = matched[bucket].orEmpty().sortedWith(bucket.order())
        if (cards.isEmpty()) null else VocabSection(bucket, cards)
    }.filter { filter == VocabFilter.All || it.bucket == filter.bucket }
    return VocabPage(
        counts = VocabCounts(
            today = matched[VocabBucket.Today].orEmpty().size,
            due = matched[VocabBucket.Due].orEmpty().size,
            all = matched.values.sumOf { it.size },
        ),
        sections = sections,
    )
}

private val VocabFilter.bucket: VocabBucket?
    get() = when (this) {
        VocabFilter.Today -> VocabBucket.Today
        VocabFilter.Due -> VocabBucket.Due
        VocabFilter.All -> null
    }

// 口径与 VocabDao.due 的 WHERE 一致：今天露过面的进今天，其余按复现次数分待复习与更早。
private fun VocabCard.bucket(todayStart: Long): VocabBucket = when {
    lastSeenAt >= todayStart -> VocabBucket.Today
    repeats > 0 -> VocabBucket.Due
    else -> VocabBucket.Earlier
}

/** 待复习里复现少的先看，同次数按出现日期旧的在前；今天与更早都按最近露面时间倒序。 */
private fun VocabBucket.order(): Comparator<VocabCard> = when (this) {
    VocabBucket.Due -> compareBy({ it.repeats }, { it.lastSeenAt }, { it.word.lowercase() })
    else -> compareByDescending<VocabCard> { it.lastSeenAt }.thenBy { it.word.lowercase() }
}

private fun VocabCard.matches(query: String): Boolean {
    val needle = query.trim()
    if (needle.isEmpty()) return true
    return word.contains(needle, ignoreCase = true) ||
        phonetic.contains(needle, ignoreCase = true) ||
        meaning.contains(needle, ignoreCase = true)
}

enum class LinkStatus { Connected, Configured, Unconfigured, Failed, NotSupported }

data class ProviderLine(
    val key: String,
    val group: String,
    val name: String,
    val detail: String,
    val status: LinkStatus,
    val editable: Boolean = false,
)
