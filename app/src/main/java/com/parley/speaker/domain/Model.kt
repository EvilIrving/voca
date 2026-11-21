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
data class Turn(
    val id: String,
    val speaker: Speaker,
    val text: String,
    val review: Review? = null,
    val audioPath: String? = null,
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
)

data class VocabGroup(
    val label: String,
    val cards: List<VocabCard>,
)

data class Scenario(val name: String)

enum class LinkStatus { Connected, Configured, Unconfigured, Failed }

data class ProviderLine(
    val name: String,
    val detail: String,
    val status: LinkStatus,
)
