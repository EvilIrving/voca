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
    fun `助手那一轮先写库也排在提问后面`() = runBlocking {
        // 流式期间助手那一轮先落库，用户那一轮要到收尾才写：顺序认轮次自己的先后，不认写库时刻。
        val first = SessionStore(app)
        first.saveTurn(Turn(id = "echo-3", speaker = Speaker.Echo, text = "Yes, I can hear you.", seq = 3))
        first.saveTurn(Turn(id = "user-2", speaker = Speaker.User, text = "Can you hear me?", seq = 2))

        val afterRestart = SessionStore(app).loadTurns()

        assertEquals(listOf("user-2", "echo-3"), afterRestart.map { it.id })
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
    fun `全局记忆写一次重启后还在`() = runBlocking {
        SessionStore(app).saveMemory("他叫李明，准备雅思，周四面试。")

        assertEquals("他叫李明，准备雅思，周四面试。", SessionStore(app).globalMemory())
    }

    @Test
    fun `升到七版时补出记忆表，旧会话原样带着`() {
        withDatabaseFile("parley.db", version = 6) { legacy ->
            legacy.execSQL(TURN_TABLE_V5)
            legacy.execSQL(VOCAB_TABLE)
            legacy.execSQL(SESSION_TABLE)
            legacy.execSQL(MODEL_CATALOG_TABLE)
            legacy.execSQL(
                "INSERT INTO `turn` (`id`, `speaker`, `text`, `createdAt`) VALUES ('u1', 'User', '上一版的对话', 100)",
            )
        }

        val stored = runBlocking { SessionStore(app).loadTurns() }

        assertEquals(listOf("上一版的对话"), stored.map { it.text })
        assertEquals("新库没有记忆这一行，等第一轮改写写进来", null, runBlocking { SessionStore(app).globalMemory() })
    }

    @Test
    fun `同一个词的两种大小写在库里是一条`() = runBlocking {
        val store = SessionStore(app)
        store.recordVocab(listOf(card("Coffee", repeats = 1)))
        store.recordVocab(listOf(card("coffee", repeats = 1)))

        val stored = store.loadVocab().filter { it.word.equals("coffee", ignoreCase = true) }
        assertEquals("Coffee 与 coffee 应当合成一条，实际 ${stored.map { it.word }}", 1, stored.size)
        assertEquals("再出现一次要算复现", 2, stored.single().repeats)
    }

    @Test
    fun `升到六版时把只差大小写的词条合成一条`() {
        withDatabaseFile("parley.db", version = 5) { legacy ->
            legacy.execSQL(TURN_TABLE_V5)
            legacy.execSQL(VOCAB_TABLE)
            legacy.execSQL(SESSION_TABLE)
            legacy.execSQL(MODEL_CATALOG_TABLE)
            legacy.execSQL(VOCAB_V5)
            legacy.execSQL(row("Coffee", repeats = 1, firstSeenAt = 100, lastSeenAt = 200))
            legacy.execSQL(row("coffee", repeats = 2, firstSeenAt = 50, lastSeenAt = 300))
        }

        runBlocking { SessionStore(app).loadVocab() }

        // 卡片不带 firstSeenAt，直接读库确认合成时取了最早的首次与最晚的出现。
        withDatabaseFile("parley.db") { migrated ->
            migrated.rawQuery("SELECT `word`, `repeats`, `firstSeenAt`, `lastSeenAt` FROM `vocab`", null).use { c ->
                assertTrue(c.moveToFirst())
                assertEquals(1, c.count)
                assertEquals(3, c.getInt(1))
                assertEquals(50L, c.getLong(2))
                assertEquals(300L, c.getLong(3))
            }
        }
    }

    @Test
    fun `同一个词再出现一次算复现，次数在库里加一`() = runBlocking {
        val store = SessionStore(app)
        val card = com.parley.speaker.domain.VocabCard(
            id = "grab a bite",
            word = "grab a bite",
            phonetic = "/ɡræb ə baɪt/",
            meaning = "随便吃点",
            example = "Let's grab a bite before the movie.",
            highlight = "grab a bite",
            repeats = 1,
        )

        store.recordVocab(listOf(card))
        store.recordVocab(listOf(card))

        val stored = store.loadVocab().single { it.word == card.word }
        assertEquals("再次出现要累加，不是回写成一", 2, stored.repeats)
    }

    @Test
    fun `同一分钟开的两段会话各自只看到自己的词条`() = runBlocking {
        val store = SessionStore(app)
        val firstBegin = System.currentTimeMillis()
        store.saveTurn(Turn(id = "s1-u1", speaker = Speaker.User, text = "第一段", sessionId = "s1"))
        store.closeSession("s1", firstBegin, System.currentTimeMillis())

        val secondBegin = System.currentTimeMillis()
        store.saveTurn(Turn(id = "s2-u1", speaker = Speaker.User, text = "第二段", sessionId = "s2"))
        store.closeSession("s2", secondBegin, System.currentTimeMillis())

        assertEquals(listOf("第一段"), store.transcript("s1").map { it.text })
        assertEquals(listOf("第二段"), store.transcript("s2").map { it.text })
    }

    @Test
    fun `接着聊回来的那一条不另起一行，读数按整段重算`() = runBlocking {
        val store = SessionStore(app)
        val started = System.currentTimeMillis() - 60_000L
        store.saveTurn(Turn(id = "s1-u1", speaker = Speaker.User, text = "早上没睡好", sessionId = "s1"))
        store.saveTurn(Turn(id = "s1-e1", speaker = Speaker.Echo, text = "Rough start.", sessionId = "s1"))
        store.closeSession("s1", started)
        store.rename("s1", "没睡好的早晨", manual = false)

        // 中间夹了另一段会话，它的轮次不能被接着聊的那一条圈进去。
        store.saveTurn(Turn(id = "s2-u1", speaker = Speaker.User, text = "另一段", sessionId = "s2"))
        store.closeSession("s2", System.currentTimeMillis())

        store.saveTurn(Turn(id = "s1-u2", speaker = Speaker.User, text = "还想去喝咖啡", sessionId = "s1"))
        val again = store.closeSession("s1", started)

        assertEquals("名字写定就留着", "没睡好的早晨", again.name)
        assertEquals("起点还是那一次", started, again.startedAt)
        assertEquals("轮次按整段重算", 2, again.rounds)
        assertEquals(1, store.loadSessions().flatMap { it.sessions }.count { it.id == "s1" })
        assertEquals(
            listOf("早上没睡好", "Rough start.", "还想去喝咖啡"),
            store.transcript("s1").map { it.text },
        )
    }

    @Test
    fun `升级到四版时把旧词条按时间窗补进会话`() {
        withDatabaseFile("parley.db", version = 3) { legacy ->
            legacy.execSQL(TURN_TABLE_V3)
            legacy.execSQL(VOCAB_TABLE)
            legacy.execSQL(SESSION_TABLE)
            legacy.execSQL(
                "INSERT INTO `session` VALUES ('morning', '早间', 100, 200, 1, NULL, NULL, 0, 0)",
            )
            legacy.execSQL(
                "INSERT INTO `turn` (`id`, `speaker`, `text`, `createdAt`) VALUES ('in', 'User', '窗内', 150)",
            )
            legacy.execSQL(
                "INSERT INTO `turn` (`id`, `speaker`, `text`, `createdAt`) VALUES ('out', 'User', '窗外', 250)",
            )
        }

        // 开一次库就会跑 3 到 4 的迁移。
        runBlocking { SessionStore(app).transcript("morning") }

        val bound = mutableMapOf<String, String?>()
        withDatabaseFile("parley.db") { migrated ->
            migrated.rawQuery("SELECT `id`, `sessionId` FROM `turn`", null).use { cursor ->
                while (cursor.moveToNext()) bound[cursor.getString(0)] = cursor.getString(1)
            }
        }
        assertEquals("morning", bound["in"])
        assertEquals(null, bound["out"])
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

    private fun card(word: String, repeats: Int) = com.parley.speaker.domain.VocabCard(
        id = word,
        word = word,
        phonetic = "/test/",
        meaning = "测试",
        example = "A $word example.",
        highlight = word,
        repeats = repeats,
    )

    private fun row(word: String, repeats: Int, firstSeenAt: Long, lastSeenAt: Long) =
        "INSERT INTO `vocab` VALUES ('$word', '/test/', '测试', 'A $word example.', '$word', " +
            "$repeats, $firstSeenAt, $lastSeenAt)"

    private companion object {
        const val TURN_TABLE_V5 = "CREATE TABLE IF NOT EXISTS `turn` (`id` TEXT NOT NULL, " +
            "`speaker` TEXT NOT NULL, `text` TEXT NOT NULL, `reviewJson` TEXT, `createdAt` INTEGER NOT NULL, " +
            "`audioPath` TEXT, `saved` INTEGER NOT NULL DEFAULT 0, `translation` TEXT, `sessionId` TEXT, " +
            "PRIMARY KEY(`id`))"

        const val VOCAB_V5 = "CREATE TABLE IF NOT EXISTS `vocab` (`word` TEXT NOT NULL, " +
            "`phonetic` TEXT NOT NULL, `meaning` TEXT NOT NULL, `example` TEXT NOT NULL, " +
            "`highlight` TEXT NOT NULL, `repeats` INTEGER NOT NULL, `firstSeenAt` INTEGER NOT NULL, " +
            "`lastSeenAt` INTEGER NOT NULL, PRIMARY KEY(`word`))"

        const val MODEL_CATALOG_TABLE = "CREATE TABLE IF NOT EXISTS `model_catalog` (" +
            "`providerId` TEXT NOT NULL, `modelsJson` TEXT NOT NULL, `fetchedAt` INTEGER NOT NULL, " +
            "`keyTag` TEXT NOT NULL, PRIMARY KEY(`providerId`))"

        const val TURN_TABLE = "CREATE TABLE IF NOT EXISTS `turn` (`id` TEXT NOT NULL, " +
            "`speaker` TEXT NOT NULL, `text` TEXT NOT NULL, `reviewJson` TEXT, " +
            "`createdAt` INTEGER NOT NULL, PRIMARY KEY(`id`))"

        const val TURN_TABLE_V3 = "CREATE TABLE IF NOT EXISTS `turn` (`id` TEXT NOT NULL, " +
            "`speaker` TEXT NOT NULL, `text` TEXT NOT NULL, `reviewJson` TEXT, `createdAt` INTEGER NOT NULL, " +
            "`audioPath` TEXT, `saved` INTEGER NOT NULL DEFAULT 0, `translation` TEXT, PRIMARY KEY(`id`))"

        const val SESSION_TABLE = "CREATE TABLE IF NOT EXISTS `session` (`id` TEXT NOT NULL, " +
            "`name` TEXT NOT NULL, `startedAt` INTEGER NOT NULL, `endedAt` INTEGER NOT NULL, " +
            "`rounds` INTEGER NOT NULL, `pronunciationAvg` INTEGER, `grammarAvg` INTEGER, " +
            "`newWordCount` INTEGER NOT NULL, `manualName` INTEGER NOT NULL, PRIMARY KEY(`id`))"

        const val VOCAB_TABLE = "CREATE TABLE IF NOT EXISTS `vocab` (`word` TEXT NOT NULL, " +
            "`phonetic` TEXT NOT NULL, `meaning` TEXT NOT NULL, `example` TEXT NOT NULL, " +
            "`highlight` TEXT NOT NULL, `repeats` INTEGER NOT NULL, `firstSeenAt` INTEGER NOT NULL, " +
            "`lastSeenAt` INTEGER NOT NULL, PRIMARY KEY(`word`))"
    }
}
