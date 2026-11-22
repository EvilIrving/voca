package com.parley.speaker.data.db

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Entity(tableName = "turn")
data class TurnRow(
    @PrimaryKey val id: String,
    val speaker: String,
    val text: String,
    val reviewJson: String?,
    val createdAt: Long,
    val audioPath: String? = null,
    @ColumnInfo(defaultValue = "0") val saved: Boolean = false,
    val translation: String? = null,
)

@Entity(tableName = "vocab")
data class VocabRow(
    @PrimaryKey val word: String,
    val phonetic: String,
    val meaning: String,
    val example: String,
    val highlight: String,
    val repeats: Int,
    val firstSeenAt: Long,
    val lastSeenAt: Long,
)

@Entity(tableName = "session")
data class SessionRow(
    @PrimaryKey val id: String,
    val name: String,
    val startedAt: Long,
    val endedAt: Long,
    val rounds: Int,
    val pronunciationAvg: Int?,
    val grammarAvg: Int?,
    val newWordCount: Int,
    val manualName: Boolean,
)

@Dao
interface TurnDao {
    @Query("SELECT * FROM turn ORDER BY createdAt ASC")
    suspend fun all(): List<TurnRow>

    @Query("SELECT * FROM turn WHERE createdAt BETWEEN :since AND :until ORDER BY createdAt ASC")
    suspend fun between(since: Long, until: Long): List<TurnRow>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIfAbsent(row: TurnRow)

    // audioPath 与 translation 都晚于文本到达，重复保存时不能把已经存下的内容抹成空。
    @Query(
        "UPDATE turn SET speaker = :speaker, text = :text, reviewJson = :reviewJson, " +
            "audioPath = COALESCE(:audioPath, audioPath), translation = COALESCE(:translation, translation) " +
            "WHERE id = :id",
    )
    suspend fun update(
        id: String,
        speaker: String,
        text: String,
        reviewJson: String?,
        audioPath: String?,
        translation: String?,
    )

    @Query("UPDATE turn SET saved = :saved WHERE id = :id")
    suspend fun setSaved(id: String, saved: Boolean)

    @Query("DELETE FROM turn WHERE id = :id")
    suspend fun delete(id: String)

    @Query("DELETE FROM turn")
    suspend fun clear()
}

@Dao
interface VocabDao {
    @Query("SELECT * FROM vocab ORDER BY lastSeenAt DESC")
    suspend fun all(): List<VocabRow>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIfAbsent(row: VocabRow)

    @Query(
        "UPDATE vocab SET phonetic = :phonetic, meaning = :meaning, example = :example, " +
            "highlight = :highlight, repeats = :repeats, lastSeenAt = :now WHERE word = :word",
    )
    suspend fun refresh(
        word: String,
        phonetic: String,
        meaning: String,
        example: String,
        highlight: String,
        repeats: Int,
        now: Long,
    )

    @Query(
        "SELECT * FROM vocab WHERE repeats > 0 AND lastSeenAt < :startOfToday " +
            "ORDER BY repeats ASC, lastSeenAt ASC, word ASC",
    )
    suspend fun due(startOfToday: Long): List<VocabRow>

    @Query("SELECT COUNT(*) FROM vocab WHERE firstSeenAt >= :since")
    suspend fun countFirstSeenSince(since: Long): Int

    @Query("UPDATE vocab SET repeats = repeats + 1, lastSeenAt = :now WHERE word = :word COLLATE NOCASE")
    suspend fun bump(word: String, now: Long)
}

@Dao
interface SessionDao {
    @Query("SELECT * FROM `session` ORDER BY startedAt DESC")
    suspend fun all(): List<SessionRow>

    @Query("SELECT * FROM `session` WHERE id = :id")
    suspend fun byId(id: String): SessionRow?

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIfAbsent(row: SessionRow)

    @Query("SELECT * FROM `session` WHERE name = '' AND endedAt >= :since ORDER BY endedAt DESC LIMIT :limit")
    suspend fun unnamedSince(since: Long, limit: Int): List<SessionRow>

    // 手动改过名的会话只有手动改名才能覆盖，自动补名在这里就被拦住，调用方没有绕过的入口。
    @Query(
        "UPDATE `session` SET name = :name, manualName = :manual " +
            "WHERE id = :id AND (manualName = 0 OR :manual = 1)",
    )
    suspend fun rename(id: String, name: String, manual: Boolean)
}

/**
 * 迁移后的表由 Room 用实体定义逐列校验，所以 `saved` 的默认值与实体上的 defaultValue 写成同一个 0，
 * `session` 各列不带默认值，与实体一致。[MIGRATION_2_3] 新增的 `translation` 可空，老行补成空，与未译过的轮次同一个状态。
 */
val MIGRATION_1_2 = object : Migration(1, 2) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE `turn` ADD COLUMN `audioPath` TEXT")
        db.execSQL("ALTER TABLE `turn` ADD COLUMN `saved` INTEGER NOT NULL DEFAULT 0")
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `session` (" +
                "`id` TEXT NOT NULL, " +
                "`name` TEXT NOT NULL, " +
                "`startedAt` INTEGER NOT NULL, " +
                "`endedAt` INTEGER NOT NULL, " +
                "`rounds` INTEGER NOT NULL, " +
                "`pronunciationAvg` INTEGER, " +
                "`grammarAvg` INTEGER, " +
                "`newWordCount` INTEGER NOT NULL, " +
                "`manualName` INTEGER NOT NULL, " +
                "PRIMARY KEY(`id`))",
        )
    }
}

val MIGRATION_2_3 = object : Migration(2, 3) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE `turn` ADD COLUMN `translation` TEXT")
    }
}

/**
 * 表结构变更规约：改实体就把 version 加一，同一次改动里写好 [androidx.room.migration.Migration]，
 * 并在建库的地方用 addMigrations 注册，旧版本数据原样带过来；禁止 fallbackToDestructiveMigration，
 * 它会在版本不匹配时删表重建，用户的会话与生词库跟着一起没。新增列必须可空或带默认值，
 * 否则老库补齐该列时无值可填。每次改完把 exportSchema 导出的 app/schemas 一并提交，
 * 它就是历史版本的唯一凭据。
 */
@Database(entities = [TurnRow::class, VocabRow::class, SessionRow::class], version = 3, exportSchema = true)
abstract class ParleyDatabase : RoomDatabase() {
    abstract fun turns(): TurnDao
    abstract fun vocab(): VocabDao
    abstract fun sessions(): SessionDao
}
