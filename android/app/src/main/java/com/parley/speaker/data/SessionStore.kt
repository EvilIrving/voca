package com.parley.speaker.data

import androidx.room.Room
import com.parley.speaker.data.db.MIGRATION_1_2
import com.parley.speaker.data.db.MIGRATION_2_3
import com.parley.speaker.data.db.MIGRATION_3_4
import com.parley.speaker.data.db.MIGRATION_4_5
import com.parley.speaker.data.db.MIGRATION_5_6
import com.parley.speaker.data.db.MIGRATION_6_7
import com.parley.speaker.data.db.MemoryRow
import com.parley.speaker.data.db.ParleyDatabase
import com.parley.speaker.data.db.SessionRow
import com.parley.speaker.data.db.TurnRow
import com.parley.speaker.data.db.VocabRow
import com.parley.speaker.domain.AnalysisStatus
import com.parley.speaker.domain.Review
import com.parley.speaker.domain.Speaker
import com.parley.speaker.domain.Turn
import com.parley.speaker.domain.VocabCard
import com.parley.speaker.domain.withoutAnnotation
import android.content.Context
import kotlin.math.roundToInt
import kotlinx.serialization.json.Json

/**
 * 会话与生词的本地存储。全部数据留在设备上，不上传任何服务端。
 */
class SessionStore(context: Context) {

    private val db = Room.databaseBuilder(context, ParleyDatabase::class.java, "parley.db")
        .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7)
        .build()
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun loadTurns(): List<Turn> = db.turns().all().mapNotNull { it.toTurn() }

    /** 全局记忆：跨会话保留的那一段。库里没有时返回空，由调用方拿默认起点顶上。 */
    suspend fun globalMemory(): String = db.memory().byId(GlobalMemoryId)?.text.orEmpty()

    /** 改写完的记忆整段盖掉，时间戳只作记录，读的时候不参与判定。 */
    suspend fun saveMemory(text: String) {
        db.memory().put(MemoryRow(id = GlobalMemoryId, text = text, updatedAt = System.currentTimeMillis()))
    }

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
            sessionId = turn.sessionId,
        )
        db.turns().insertIfAbsent(row)
        db.turns().update(
            row.id,
            row.speaker,
            row.text,
            row.reviewJson,
            row.audioPath,
            row.translation,
            row.sessionId,
        )
    }

    suspend fun setSaved(turnId: String, saved: Boolean) = db.turns().setSaved(turnId, saved)

    /**
     * 只写这一轮的评测结果：收段之后才回来的分析走这条路，不改表也不重新插行。
     * 这一轮已经被删掉就不写，返回假表示没有落到任何一行上。
     */
    suspend fun saveReview(turnId: String, review: Review): Boolean {
        val row = db.turns().all().firstOrNull { it.id == turnId } ?: return false
        db.turns().update(
            id = row.id,
            speaker = row.speaker,
            text = row.text,
            reviewJson = json.encodeToString(review),
            audioPath = row.audioPath,
            translation = row.translation,
            sessionId = row.sessionId,
        )
        return true
    }

    suspend fun deleteTurn(turnId: String) = db.turns().delete(turnId)

    suspend fun clearTurns() = db.turns().clear()

    /** 全部词条按最近出现时间倒序；分组与筛选由 domain 的 vocabPage 统一算，存储层不再自己分天。 */
    suspend fun loadVocab(): List<VocabCard> = db.vocab().all().map { it.toCard() }

    /** 待复习：今天没有露面、复现次数少的先排，同次数里最近一次出现早的在前。 */
    suspend fun due(now: Long = System.currentTimeMillis()): List<VocabCard> =
        db.vocab().due(startOfLocalDay(now)).map { it.toCard() }

    suspend fun recordVocab(cards: List<VocabCard>) {
        val now = System.currentTimeMillis()
        cards.forEach { card ->
            val inserted = db.vocab().insertIfAbsent(
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
            // 已在库里的词是又出现了一次：内容刷新、复现次数加一，firstSeenAt 保持首次收录的时间。
            if (inserted == -1L) {
                db.vocab().refresh(
                    word = card.word,
                    phonetic = card.phonetic,
                    meaning = card.meaning,
                    example = card.example,
                    highlight = card.highlight,
                    now = now,
                )
            }
        }
    }

    suspend fun bumpVocab(word: String) = db.vocab().bump(word, System.currentTimeMillis())

    /**
     * 会话结束时签到一条记录，轮次、双项均值与新词数从 [startedAt] 到 [endedAt] 的库里算出来。
     * 已经有一行的（接着聊回来的那一段）不另起一行，只把收口时间与重算的读数写回去，
     * 名字与起点保持第一次的值；这一条也不按时间窗重绑轮次，中间夹着的别的会话不会被圈进来。
     */
    suspend fun closeSession(
        id: String,
        startedAt: Long,
        endedAt: Long = System.currentTimeMillis(),
    ): SessionRecord {
        val existing = db.sessions().byId(id)
        val turnsInSession = if (existing != null) {
            db.turns().bySession(id)
        } else {
            db.turns().bindSession(id, startedAt, endedAt)
            db.turns().bySession(id).ifEmpty { db.turns().between(startedAt, endedAt) }
        }
        val reviews = turnsInSession.mapNotNull { it.toTurn()?.review }
        val rounds = turnsInSession.count { it.speaker == Speaker.User.name }
        val pronunciationAvg = reviews.mapNotNull { it.pronunciation?.score }.mean()
        val grammarAvg = reviews.map { it.grammar.score }.mean()
        val newWordCount = db.vocab().countFirstSeenSince(startedAt)
        if (existing != null) {
            db.sessions().updateStats(id, endedAt, rounds, pronunciationAvg, grammarAvg, newWordCount)
            return db.sessions().byId(id)?.toRecord() ?: existing.toRecord()
        }
        val row = SessionRow(
            id = id,
            name = "",
            startedAt = startedAt,
            endedAt = endedAt,
            rounds = rounds,
            pronunciationAvg = pronunciationAvg,
            grammarAvg = grammarAvg,
            newWordCount = newWordCount,
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

    /** 自动命名与回看要看整段对话，优先按 sessionId 取，找不到时退回时间窗圈词条。 */
    suspend fun transcript(sessionId: String): List<Turn> {
        val bySession = db.turns().bySession(sessionId).mapNotNull { it.toTurn() }
        if (bySession.isNotEmpty()) return bySession
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
            sessionId = sessionId,
        )
    }

    private fun VocabRow.toCard(): VocabCard = VocabCard(
        id = word,
        word = word,
        phonetic = phonetic,
        meaning = meaning.withoutAnnotation(),
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

        /** 全局记忆只有这一条，不按会话分表，也不随会话收口增删。 */
        const val GlobalMemoryId = "global"
    }
}
