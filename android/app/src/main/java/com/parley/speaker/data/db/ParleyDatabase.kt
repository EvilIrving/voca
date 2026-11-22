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
    val sessionId: String? = null,
    // 这一轮话筒录了多久：打卡按真实录音长度算，老库没有这一列时补零。
    @ColumnInfo(defaultValue = "0") val durationMs: Long = 0,
)

@Entity(tableName = "vocab")
data class VocabRow(
    // 同一个词的 Coffee 与 coffee 是同一条：键按 NOCASE 比较，写入方不用自己折大小写。
    @PrimaryKey @ColumnInfo(collate = ColumnInfo.NOCASE) val word: String,
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

/**
 * 一家供应商的模型清单缓存。modelsJson 是整份清单的序列化结果，fetchedAt 是拉取成功的时间，
 * keyTag 是密钥指纹，换过密钥就不再拿旧清单顶上。
 */@Entity(tableName = "model_catalog")
data class ModelCatalogRow(
    @PrimaryKey val providerId: String,
    val modelsJson: String,
    val fetchedAt: Long,
    val keyTag: String,
)

/** 全局记忆只一行：跨会话保留的那段叙述，界面看不见也改不了，只能由自动改写链路写。 */
@Entity(tableName = "memory")
data class MemoryRow(
    @PrimaryKey val id: String,
    val text: String,
    val updatedAt: Long,
)

/** 打卡热力图要的三个字段：这一轮属于哪段会话、落在什么时刻、那段录音有多长。 */
data class TurnPractice(val sessionId: String, val createdAt: Long, val durationMs: Long)

@Dao
interface TurnDao {
    @Query("SELECT * FROM turn ORDER BY createdAt ASC")
    suspend fun all(): List<TurnRow>

    @Query("SELECT * FROM turn WHERE createdAt BETWEEN :since AND :until ORDER BY createdAt ASC")
    suspend fun between(since: Long, until: Long): List<TurnRow>

    @Query("SELECT * FROM turn WHERE sessionId = :sessionId ORDER BY createdAt ASC")
    suspend fun bySession(sessionId: String): List<TurnRow>

    // 打卡只认自己说过的那些轮次：助手的下行音频不算，打字的那一轮没有录音，长度记零。
    @Query(
        "SELECT sessionId, createdAt, durationMs FROM turn " +
            "WHERE speaker = 'User' AND sessionId IS NOT NULL AND sessionId != '' AND createdAt >= :since " +
            "ORDER BY createdAt ASC",
    )
    suspend fun practiceTurnsSince(since: Long): List<TurnPractice>

    @Query(
        "UPDATE turn SET sessionId = :sessionId " +
            "WHERE (sessionId IS NULL OR sessionId = '') AND createdAt BETWEEN :startedAt AND :endedAt",
    )
    suspend fun bindSession(sessionId: String, startedAt: Long, endedAt: Long)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIfAbsent(row: TurnRow)

    // audioPath 与 translation 都晚于文本到达，重复保存时不能把已经存下的内容抹成空。
    @Query(
        "UPDATE turn SET speaker = :speaker, text = :text, reviewJson = :reviewJson, " +
            "audioPath = COALESCE(:audioPath, audioPath), translation = COALESCE(:translation, translation), " +
            "sessionId = COALESCE(:sessionId, sessionId), " +
            "durationMs = CASE WHEN :durationMs > 0 THEN :durationMs ELSE durationMs END " +
            "WHERE id = :id",
    )
    suspend fun update(
        id: String,
        speaker: String,
        text: String,
        reviewJson: String?,
        audioPath: String?,
        translation: String?,
        sessionId: String?,
        durationMs: Long,
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
    suspend fun insertIfAbsent(row: VocabRow): Long

    // 再次出现的词是复现，不是重录：内容刷新，复现次数在库里自己加一，调用方不传这个数。
    @Query(
        "UPDATE vocab SET phonetic = :phonetic, meaning = :meaning, example = :example, " +
            "highlight = :highlight, repeats = repeats + 1, lastSeenAt = :now WHERE word = :word",
    )
    suspend fun refresh(
        word: String,
        phonetic: String,
        meaning: String,
        example: String,
        highlight: String,
        now: Long,
    )

    @Query(
        "SELECT * FROM vocab WHERE repeats > 0 AND lastSeenAt < :startOfToday " +
            "ORDER BY repeats ASC, lastSeenAt ASC, word ASC",
    )
    suspend fun due(startOfToday: Long): List<VocabRow>

    @Query("SELECT COUNT(*) FROM vocab WHERE firstSeenAt >= :since")
    suspend fun countFirstSeenSince(since: Long): Int

    @Query("UPDATE vocab SET repeats = repeats + 1, lastSeenAt = :now WHERE word = :word")
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

    // 接着聊的那一段收回去：只刷新收口时间与重算出来的数与均值，名字与起点保持第一次的值。
    @Query(
        "UPDATE `session` SET endedAt = :endedAt, rounds = :rounds, " +
            "pronunciationAvg = :pronunciationAvg, grammarAvg = :grammarAvg, newWordCount = :newWordCount " +
            "WHERE id = :id",
    )
    suspend fun updateStats(
        id: String,
        endedAt: Long,
        rounds: Int,
        pronunciationAvg: Int?,
        grammarAvg: Int?,
        newWordCount: Int,
    )

    @Query("SELECT * FROM `session` WHERE name = '' AND endedAt >= :since ORDER BY endedAt DESC LIMIT :limit")
    suspend fun unnamedSince(since: Long, limit: Int): List<SessionRow>

    // 手动改过名的会话只有手动改名才能覆盖，自动补名在这里就被拦住，调用方没有绕过的入口。
    @Query(
        "UPDATE `session` SET name = :name, manualName = :manual " +
            "WHERE id = :id AND (manualName = 0 OR :manual = 1)",
    )
    suspend fun rename(id: String, name: String, manual: Boolean)
}

/** 模型清单缓存按供应商一条，重复拉取整条盖掉。 */
@Dao
interface ModelCatalogDao {
    @Query("SELECT * FROM model_catalog WHERE providerId = :providerId")
    suspend fun byProvider(providerId: String): ModelCatalogRow?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun put(row: ModelCatalogRow)

    @Query("DELETE FROM model_catalog WHERE providerId = :providerId")
    suspend fun clear(providerId: String)
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

val MIGRATION_3_4 = object : Migration(3, 4) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE `turn` ADD COLUMN `sessionId` TEXT")
        db.execSQL(
            "UPDATE `turn` SET `sessionId` = (" +
                "SELECT `id` FROM `session` " +
                "WHERE `turn`.`createdAt` >= `session`.`startedAt` " +
                "AND `turn`.`createdAt` <= `session`.`endedAt` " +
                "ORDER BY `session`.`startedAt` DESC LIMIT 1" +
            ") WHERE `sessionId` IS NULL",
        )
    }
}

/** 模型清单缓存是新表，旧库只补一张空表，已有会话与词条原样带过来。 */
val MIGRATION_4_5 = object : Migration(4, 5) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `model_catalog` (" +
                "`providerId` TEXT NOT NULL, " +
                "`modelsJson` TEXT NOT NULL, " +
                "`fetchedAt` INTEGER NOT NULL, " +
                "`keyTag` TEXT NOT NULL, " +
                "PRIMARY KEY(`providerId`))",
        )
    }
}

/**
 * 五到六：`vocab` 的主键改成大小写不敏感。SQLite 不能原地改列的比较规则，只能重建表再搬数据，
 * 搬的时候顺带把只差大小写的行合成一条——复现次数求和，首次取最早、最近出现取最晚。
 */
val MIGRATION_5_6 = object : Migration(5, 6) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `vocab_new` (" +
                "`word` TEXT NOT NULL COLLATE NOCASE, `phonetic` TEXT NOT NULL, " +
                "`meaning` TEXT NOT NULL, `example` TEXT NOT NULL, `highlight` TEXT NOT NULL, " +
                "`repeats` INTEGER NOT NULL, `firstSeenAt` INTEGER NOT NULL, " +
                "`lastSeenAt` INTEGER NOT NULL, PRIMARY KEY(`word`))",
        )
        db.execSQL(
            "INSERT INTO `vocab_new` SELECT `word`, `phonetic`, `meaning`, `example`, `highlight`, " +
                "SUM(`repeats`), MIN(`firstSeenAt`), MAX(`lastSeenAt`) FROM `vocab` " +
                "GROUP BY `word` COLLATE NOCASE",
        )
        db.execSQL("DROP TABLE `vocab`")
        db.execSQL("ALTER TABLE `vocab_new` RENAME TO `vocab`")
    }
}

/** 全局记忆读写：一行一条，改写一次就整段盖掉。 */
@Dao
interface MemoryDao {
    @Query("SELECT * FROM memory WHERE id = :id")
    suspend fun byId(id: String): MemoryRow?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun put(row: MemoryRow)
}

/** 七到八：每一轮记下录音长度。老库补零，等于那几轮没有可测的音频，不计进打卡。 */
val MIGRATION_7_8 = object : Migration(7, 8) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE turn ADD COLUMN durationMs INTEGER NOT NULL DEFAULT 0")
    }
}

/** 六到七：全局记忆单独一张表，旧库只补一张空表，会话与生词原样带过来。 */
val MIGRATION_6_7 = object : Migration(6, 7) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `memory` (" +
                "`id` TEXT NOT NULL, " +
                "`text` TEXT NOT NULL, " +
                "`updatedAt` INTEGER NOT NULL, " +
                "PRIMARY KEY(`id`))",
        )
    }
}

/**
 * 表结构变更规约：改实体就把 version 加一，同一次改动里写好 [androidx.room.migration.Migration]，
 * 并在建库的地方用 addMigrations 注册，旧版本数据原样带过来；禁止 fallbackToDestructiveMigration，
 * 它会在版本不匹配时删表重建，用户的会话与生词库跟着一起没。新增列必须可空或带默认值，
 * 否则老库补齐该列时无值可填。每次改完把 exportSchema 导出的 app/schemas 一并提交，
 * 它就是历史版本的唯一凭据。
 */
@Database(
    entities = [TurnRow::class, VocabRow::class, SessionRow::class, ModelCatalogRow::class, MemoryRow::class],
    version = 8,
    exportSchema = true,
)
abstract class ParleyDatabase : RoomDatabase() {
    abstract fun turns(): TurnDao
    abstract fun vocab(): VocabDao
    abstract fun sessions(): SessionDao
    abstract fun modelCatalogs(): ModelCatalogDao
    abstract fun memory(): MemoryDao
}
