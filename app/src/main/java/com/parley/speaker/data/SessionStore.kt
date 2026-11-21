package com.parley.speaker.data

import androidx.room.Room
import com.parley.speaker.data.db.ParleyDatabase
import com.parley.speaker.data.db.TurnRow
import com.parley.speaker.data.db.VocabRow
import com.parley.speaker.domain.Speaker
import com.parley.speaker.domain.Turn
import com.parley.speaker.domain.VocabCard
import com.parley.speaker.domain.VocabGroup
import android.content.Context
import kotlinx.serialization.json.Json

/**
 * 会话与生词的本地存储。全部数据留在设备上，不上传任何服务端。
 */
class SessionStore(context: Context) {

    private val db = Room.databaseBuilder(context, ParleyDatabase::class.java, "parley.db").build()
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun loadTurns(): List<Turn> = db.turns().all().mapNotNull { row ->
        val speaker = runCatching { Speaker.valueOf(row.speaker) }.getOrNull() ?: return@mapNotNull null
        val review = row.reviewJson?.let { runCatching { json.decodeFromString<com.parley.speaker.domain.Review>(it) }.getOrNull() }
        Turn(id = row.id, speaker = speaker, text = row.text, review = review)
    }

    suspend fun saveTurns(turns: List<Turn>) {
        val now = System.currentTimeMillis()
        db.turns().upsert(
            turns.mapIndexed { index, turn ->
                TurnRow(
                    id = turn.id,
                    speaker = turn.speaker.name,
                    text = turn.text,
                    reviewJson = turn.review?.let { json.encodeToString(it) },
                    createdAt = now + index,
                )
            },
        )
    }

    suspend fun clearTurns() = db.turns().clear()

    suspend fun loadVocab(): List<VocabGroup> {
        val rows = db.vocab().all()
        if (rows.isEmpty()) return emptyList()
        val today = ArrayList<VocabCard>()
        val earlier = ArrayList<VocabCard>()
        val startOfToday = System.currentTimeMillis() - 20 * 60 * 60 * 1000L
        rows.forEach { row ->
            val card = VocabCard(
                id = row.word,
                word = row.word,
                phonetic = row.phonetic,
                meaning = row.meaning,
                example = row.example,
                highlight = row.highlight,
                repeats = row.repeats,
            )
            if (row.lastSeenAt >= startOfToday) today += card else earlier += card
        }
        return buildList {
            if (today.isNotEmpty()) add(VocabGroup("今天", today))
            if (earlier.isNotEmpty()) add(VocabGroup("更早", earlier))
        }
    }

    suspend fun recordVocab(cards: List<VocabCard>) {
        val now = System.currentTimeMillis()
        cards.forEach { card ->
            db.vocab().upsert(
                VocabRow(
                    word = card.word,
                    phonetic = card.phonetic,
                    meaning = card.meaning,
                    example = card.example,
                    highlight = card.highlight,
                    repeats = card.repeats,
                    firstSeenAt = now,
                    lastSeenAt = now,
                ),
            )
        }
    }

    suspend fun bumpVocab(word: String) = db.vocab().bump(word, System.currentTimeMillis())
}
