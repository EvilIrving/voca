package com.parley.speaker.data

import android.app.Application
import android.database.sqlite.SQLiteDatabase
import com.parley.speaker.domain.Speaker
import com.parley.speaker.domain.Turn
import com.parley.speaker.domain.TurnEffect
import com.parley.speaker.domain.TurnEvent
import com.parley.speaker.domain.TurnFlow
import com.parley.speaker.domain.TurnFlowState
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/**
 * 会话持久化：冷启动能捡回历史、老库的结构对得上、版本错配时不清库、取消的一轮不在库里。
 */
@RunWith(RobolectricTestRunner::class)
class SessionPersistenceTest {

    private val app: Application = RuntimeEnvironment.getApplication()

    private class StoreDriver(private val store: SessionStore) {
        private var state = TurnFlowState()
        private var counter = 0

        suspend fun send(sequence: List<TurnEvent>) {
            sequence.forEach { event ->
                val reduction = TurnFlow.reduce(state, event) { prefix -> "$prefix-${++counter}" }
                state = reduction.state
                reduction.effects.forEach { effect ->
                    when (effect) {
                        is TurnEffect.Persist -> store.saveTurn(effect.turn)
                        is TurnEffect.Delete -> store.deleteTurn(effect.turnId)
                        else -> Unit
                    }
                }
            }
        }
    }

    @Test
    fun `冷启动捡回上一次的对话`() = runBlocking {
        val first = SessionStore(app)
        first.saveTurn(Turn(id = "u1", speaker = Speaker.User, text = "I went to the coffee shop"))
        first.saveTurn(Turn(id = "e1", speaker = Speaker.Echo, text = "You went there on your own?"))

        val afterRestart = SessionStore(app).loadTurns()

        assertEquals(
            setOf("u1" to "I went to the coffee shop", "e1" to "You went there on your own?"),
            afterRestart.map { it.id to it.text }.toSet(),
        )
        assertEquals(Speaker.User, afterRestart.first { it.id == "u1" }.speaker)
        assertEquals(Speaker.Echo, afterRestart.first { it.id == "e1" }.speaker)
    }

    @Test
    fun `取消掉的一轮不在库里`() = runBlocking {
        val driver = StoreDriver(SessionStore(app))
        driver.send(
            listOf(
                TurnEvent.SessionReady,
                TurnEvent.PressStart,
                TurnEvent.UserText("keep this one"),
                TurnEvent.Commit,
                TurnEvent.AssistantText("kept."),
                TurnEvent.TurnComplete,
            ),
        )
        driver.send(
            listOf(
                TurnEvent.PressStart,
                TurnEvent.UserText("never mind this one"),
                TurnEvent.ArmCancel(armed = true),
                TurnEvent.Cancel,
            ),
        )

        val stored = SessionStore(app).loadTurns()

        assertFalse(
            "取消的这一轮不该留下，实际库里有 ${stored.map { it.text }}",
            stored.any { it.text.contains("never mind") },
        )
        assertEquals(setOf("keep this one", "kept."), stored.map { it.text }.toSet())
    }

    @Test
    fun `老版本留下的库还能按时间读出会话`() {
        withDatabaseFile("parley.db", version = 1) { legacy ->
            legacy.execSQL(TURN_TABLE)
            legacy.execSQL(VOCAB_TABLE)
            legacy.execSQL("INSERT INTO `turn` VALUES ('newest', 'Echo', '第三句', NULL, 300)")
            legacy.execSQL("INSERT INTO `turn` VALUES ('oldest', 'User', '第一句', NULL, 100)")
            legacy.execSQL("INSERT INTO `turn` VALUES ('middle', 'Echo', '第二句', NULL, 200)")
        }

        val stored = runBlocking { SessionStore(app).loadTurns() }

        assertEquals(listOf("第一句", "第二句", "第三句"), stored.map { it.text })
        assertEquals(listOf(Speaker.User, Speaker.Echo, Speaker.Echo), stored.map { it.speaker })
    }

    @Test
    fun `版本对不上时宁可打不开也不清库`() {
        withDatabaseFile("parley.db", version = 99) { future ->
            future.execSQL(TURN_TABLE)
            future.execSQL(VOCAB_TABLE)
            future.execSQL("INSERT INTO `turn` VALUES ('future-1', 'User', '更高版本写下的这一句', NULL, 1)")
        }

        val outcome = runCatching { runBlocking { SessionStore(app).loadTurns() } }

        assertTrue("版本不匹配要明确失败，而不是删表重建", outcome.isFailure)
        withDatabaseFile("parley.db") { reopened ->
            reopened.rawQuery("SELECT `text` FROM `turn`", null).use { cursor ->
                assertTrue("库里还留着这一行", cursor.moveToFirst())
                assertEquals("更高版本写下的这一句", cursor.getString(0))
            }
        }
    }

    private fun withDatabaseFile(name: String, version: Int? = null, block: (SQLiteDatabase) -> Unit) {
        val path = app.getDatabasePath(name)
        path.parentFile?.mkdirs()
        val database = SQLiteDatabase.openOrCreateDatabase(path, null)
        try {
            block(database)
            if (version != null) database.version = version
        } finally {
            database.close()
        }
    }

    private companion object {
        const val TURN_TABLE = "CREATE TABLE IF NOT EXISTS `turn` (`id` TEXT NOT NULL, " +
            "`speaker` TEXT NOT NULL, `text` TEXT NOT NULL, `reviewJson` TEXT, " +
            "`createdAt` INTEGER NOT NULL, PRIMARY KEY(`id`))"

        const val VOCAB_TABLE = "CREATE TABLE IF NOT EXISTS `vocab` (`word` TEXT NOT NULL, " +
            "`phonetic` TEXT NOT NULL, `meaning` TEXT NOT NULL, `example` TEXT NOT NULL, " +
            "`highlight` TEXT NOT NULL, `repeats` INTEGER NOT NULL, `firstSeenAt` INTEGER NOT NULL, " +
            "`lastSeenAt` INTEGER NOT NULL, PRIMARY KEY(`word`))"
    }
}
