package com.voca.speaker.ui.chat

import android.app.Application
import com.voca.speaker.data.SessionStore
import com.voca.speaker.domain.Speaker
import com.voca.speaker.domain.Turn
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/**
 * 长按菜单里删除那一行：只把指定那一轮从库里的列表上撤掉，
 * 同一段里别的轮次连同正文与译文都原样留着。
 */
@RunWith(RobolectricTestRunner::class)
class TurnDeleteTest {

    private val app: Application = RuntimeEnvironment.getApplication()

    @Test
    fun `删除只从库里拿走指定那一轮`() = runBlocking {
        val store = SessionStore(app)
        store.saveTurn(Turn(id = "d1", speaker = Speaker.User, text = "删掉前面的"))
        store.saveTurn(Turn(id = "d2", speaker = Speaker.Echo, text = "要删的这一句", translation = "要被一起删的译文"))
        store.saveTurn(Turn(id = "d3", speaker = Speaker.Echo, text = "删掉后面的"))

        store.deleteTurn("d2")

        val left = SessionStore(app).loadTurns()
        assertEquals(listOf("d1", "d3"), left.map { it.id })
        assertEquals(listOf("删掉前面的", "删掉后面的"), left.map { it.text })
    }

    @Test
    fun `删完之后再落库不会把那一轮带回来`() = runBlocking {
        val store = SessionStore(app)
        store.saveTurn(Turn(id = "r1", speaker = Speaker.Echo, text = "先写进来"))
        store.deleteTurn("r1")
        store.saveTurn(Turn(id = "r2", speaker = Speaker.Echo, text = "后来的一句"))

        assertEquals(listOf("r2"), SessionStore(app).loadTurns().map { it.id })
    }

    @Test
    fun `列表里减掉一轮只影响这一轮`() {
        val turns = listOf(
            Turn(id = "a", speaker = Speaker.User, text = "第一句"),
            Turn(id = "b", speaker = Speaker.Echo, text = "第二句"),
            Turn(id = "c", speaker = Speaker.Echo, text = "第三句"),
        )

        assertEquals(listOf("a", "c"), turns.minusTurn("b").map { it.id })
        assertEquals(listOf("a", "b", "c"), turns.map { it.id })
    }
}
