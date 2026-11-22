package com.parley.speaker.data

import androidx.room.Room
import com.parley.speaker.data.db.MIGRATION_1_2
import com.parley.speaker.data.db.MIGRATION_2_3
import com.parley.speaker.data.db.ParleyDatabase
import com.parley.speaker.data.db.SessionRow
import com.parley.speaker.data.db.TurnRow
import com.parley.speaker.data.db.VocabRow
import com.parley.speaker.domain.AnalysisStatus
import com.parley.speaker.domain.Review
import com.parley.speaker.domain.Speaker
import com.parley.speaker.domain.Turn
import com.parley.speaker.domain.VocabCard
import com.parley.speaker.domain.VocabGroup
import android.content.Context
import kotlin.math.roundToInt
import kotlinx.serialization.json.Json

/**
 * 会话与生词的本地存储。全部数据留在设备上，不上传任何服务端。
 */
class SessionStore(context: Context) {

    private val db = Room.databaseBuilder(context, ParleyDatabase::class.java, "parley.db")
        .addMigrations(MIGRATION_1_2, MIGRATION_2_3)
        .build()
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun loadTurns(): List<Turn> = db.turns().all().mapNotNull { it.toTurn() }

    /** 只写这一轮：新轮插入时写时间戳，已有轮保留原 createdAt；收藏位只走 [setSaved]。 */
    suspend fun saveTurn(turn: Turn) {
        val row = TurnRow(
            id = turn.id,
            speaker = turn.speaker.name,
            text = turn.text,
            reviewJson = turn.review?.let { json.encodeToString(it) },
            createdAt = System.currentTimeMillis(),
            audioPath = turn.audioPath,
            translation = turn.translation,
        )
        db.turns().insertIfAbsent(row)
        db.turns().update(row.id, row.speaker, row.text, row.reviewJson, row.audioPath, row.translation)
    }

    suspend fun setSaved(turnId: String, saved: Boolean) = db.turns().setSaved(turnId, saved)

    suspend fun deleteTurn(turnId: String) = db.turns().delete(turnId)

    suspend fun clearTurns() = db.turns().clear()

    suspend fun loadVocab(now: Long = System.currentTimeMillis()): List<VocabGroup> {
        val rows = db.vocab().all()
        if (rows.isEmpty()) return emptyList()
        val today = startOfLocalDay(now)
        val fresh = rows.filter { it.lastSeenAt >= today }.map { it.toCard() }
        val earlier = rows.filter { it.lastSeenAt < today }.map { it.toCard() }
        return buildList {
            if (fresh.isNotEmpty()) add(VocabGroup(TodayLabel, fresh))
            if (earlier.isNotEmpty()) add(VocabGroup(EarlierLabel, earlier))
        }
    }

    /** 待复习：今天没有露面、复现次数少的先排，同次数里最近一次出现早的在前。 */
    suspend fun due(now: Long = System.currentTimeMillis()): List<VocabCard> =
        db.vocab().due(startOfLocalDay(now)).map { it.toCard() }

    suspend fun recordVocab(cards: List<VocabCard>) {
        val now = System.currentTimeMillis()
        cards.forEach { card ->
            db.vocab().insertIfAbsent(
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
            // 已有的词只推进 lastSeenAt 与释义内容，firstSeenAt 保持首次收录的时间。
            db.vocab().refresh(
                word = card.word,
                phonetic = card.phonetic,
                meaning = card.meaning,
                example = card.example,
                highlight = card.highlight,
                repeats = card.repeats,
                now = now,
            )
        }
    }

    suspend fun bumpVocab(word: String) = db.vocab().bump(word, System.currentTimeMillis())

    /**
     * 会话结束时签到一条记录，轮次、双项均值与新词数从 [startedAt] 到 [endedAt] 的库里算出来。
     * 同名 id 重复签到不会改写已存下的那条，时间戳与首算的均值都保持第一次的值。
     */
    suspend fun closeSession(
        id: String,
        startedAt: Long,
        endedAt: Long = System.currentTimeMillis(),
    ): SessionRecord {
        val turnsInSession = db.turns().between(startedAt, endedAt)
        val reviews = turnsInSession.mapNotNull { it.toTurn()?.review }
        val row = SessionRow(
            id = id,
            name = "",
            startedAt = startedAt,
            endedAt = endedAt,
            rounds = turnsInSession.count { it.speaker == Speaker.User.name },
            pronunciationAvg = reviews.mapNotNull { it.pronunciation?.score }.mean(),
            grammarAvg = reviews.map { it.grammar.score }.mean(),
            newWordCount = db.vocab().countFirstSeenSince(startedAt),
            manualName = false,
        )
        db.sessions().insertIfAbsent(row)
        return db.sessions().byId(id)?.toRecord() ?: row.toRecord()
    }

    suspend fun loadSessions(now: Long = System.currentTimeMillis()): List<SessionGroup> =
        db.sessions().all().map { it.toRecord() }.groupedByDay(now)

    /** 启动时补写遗漏的自动名：最近一天里还没名字的会话，最新结束的排在前面。 */
    suspend fun takeUnnamed(limit: Int = 3, now: Long = System.currentTimeMillis()): List<SessionRecord> =
        db.sessions().unnamedSince(now - UnnamedWindow, limit).map { it.toRecord() }

    /** 自动命名要看整段对话，取这一条会话时间窗内的全部轮次。 */
    suspend fun transcript(sessionId: String): List<Turn> {
        val session = db.sessions().byId(sessionId) ?: return emptyList()
        return db.turns().between(session.startedAt, session.endedAt).mapNotNull { it.toTurn() }
    }

    /** [manual] 为真表示用户亲手改的名字，此后自动补名不能覆盖。 */
    suspend fun rename(id: String, name: String, manual: Boolean) {
        // 空名字会让会话永远停在未命名状态，同时把自动补名挡在门外。
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return
        db.sessions().rename(id, trimmed, manual)
    }

    private fun TurnRow.toTurn(): Turn? {
        val speaker = runCatching { Speaker.valueOf(speaker) }.getOrNull() ?: return null
        val review = reviewJson?.let { runCatching { json.decodeFromString<Review>(it) }.getOrNull() }
        return Turn(
            id = id,
            speaker = speaker,
            text = text,
            review = review,
            audioPath = audioPath,
            analysis = if (review != null) AnalysisStatus.Ready else AnalysisStatus.None,
            saved = saved,
            translation = translation,
        )
    }

    private fun VocabRow.toCard(): VocabCard = VocabCard(
        id = word,
        word = word,
        phonetic = phonetic,
        meaning = meaning,
        example = example,
        highlight = highlight,
        repeats = repeats,
        lastSeenAt = lastSeenAt,
    )

    private fun SessionRow.toRecord(): SessionRecord = SessionRecord(
        id = id,
        name = name,
        startedAt = startedAt,
        endedAt = endedAt,
        rounds = rounds,
        pronunciationAvg = pronunciationAvg,
        grammarAvg = grammarAvg,
        newWordCount = newWordCount,
        manualName = manualName,
    )

    private fun List<Int>.mean(): Int? = if (isEmpty()) null else (sum().toDouble() / size).roundToInt()

    private companion object {
        const val UnnamedWindow = 24 * 60 * 60 * 1000L
    }
}
