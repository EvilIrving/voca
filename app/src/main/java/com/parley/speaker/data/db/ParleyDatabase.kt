package com.parley.speaker.data.db

import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.RoomDatabase

@Entity(tableName = "turn")
data class TurnRow(
    @PrimaryKey val id: String,
    val speaker: String,
    val text: String,
    val reviewJson: String?,
    val createdAt: Long,
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

@Dao
interface TurnDao {
    @Query("SELECT * FROM turn ORDER BY createdAt ASC")
    suspend fun all(): List<TurnRow>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(turns: List<TurnRow>)

    @Query("DELETE FROM turn")
    suspend fun clear()
}

@Dao
interface VocabDao {
    @Query("SELECT * FROM vocab ORDER BY lastSeenAt DESC")
    suspend fun all(): List<VocabRow>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(row: VocabRow)

    @Query("UPDATE vocab SET repeats = repeats + 1, lastSeenAt = :now WHERE word = :word COLLATE NOCASE")
    suspend fun bump(word: String, now: Long)
}

@Database(entities = [TurnRow::class, VocabRow::class], version = 1, exportSchema = false)
abstract class ParleyDatabase : RoomDatabase() {
    abstract fun turns(): TurnDao
    abstract fun vocab(): VocabDao
}
