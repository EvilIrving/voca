package com.parley.speaker.ui.chat

import com.parley.speaker.data.SeedContent
import com.parley.speaker.domain.MemoryMaxChars
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 全局记忆的机制：起点从哪里来、排队时怎么收敛、回来之后收不收、每次改写看到的是哪一份。
 * 这里不联网，改写用一段可控的函数顶掉。
 */
class MemoryLedgerTest {

    private val saved = mutableListOf<String>()

    private fun ledger(seed: String = "") = MemoryLedger(seed, persist = { saved += it })

    @Test
    fun `库是空的时候种下出厂起点，并且当场写库`() = runBlocking {
        val memory = ledger()

        memory.seedWith(SeedContent.DefaultMemory)

        assertEquals(SeedContent.DefaultMemory, memory.text)
        assertEquals(listOf(SeedContent.DefaultMemory), saved)
    }

    @Test
    fun `库里留着上一场那份就接着用，不重种`() = runBlocking {
        val memory = ledger()

        memory.seedWith("他叫李明，周四面试，准备雅思。")

        assertEquals("他叫李明，周四面试，准备雅思。", memory.text)
        assertEquals("上一场那份要写回去", 1, saved.size)
    }

    @Test
    fun `排队只留最后一轮，前一条没写完不叠几份`() = runBlocking {
        val memory = ledger("起点")
        val handled = mutableListOf<String>()

        memory.offer("u1")
        memory.offer("u2")
        memory.offer("u3")
        memory.drain(
            materialOf = { it },
            rewrite = { _, material ->
                handled += material
                "记住 $material"
            },
        )

        assertEquals("三条挤在队列里只处理最后一条", listOf("u3"), handled)
        assertEquals("记住 u3", memory.text)
    }

    @Test
    fun `后一次改写看到的一定是前一次收下来的那份`() = runBlocking {
        val memory = ledger("起点")
        val seen = mutableListOf<String>()

        memory.offer("u1")
        memory.drain(
            materialOf = { it },
            rewrite = { current, material ->
                seen += current
                "$current / $material"
            },
        )
        memory.offer("u2")
        memory.drain(
            materialOf = { it },
            rewrite = { current, material ->
                seen += current
                "$current / $material"
            },
        )

        assertEquals(listOf("起点", "起点 / u1"), seen)
        assertEquals("起点 / u1 / u2", memory.text)
    }

    @Test
    fun `改写回来是空的、抄原文的超长、一字不差的都不收`() = runBlocking {
        val memory = ledger("他叫李明。")

        listOf(null, "   ", "字".repeat(MemoryMaxChars + 1), "他叫李明。").forEach { candidate ->
            memory.offer("u1")
            memory.drain(materialOf = { it }, rewrite = { _, _ -> candidate })
            assertEquals("这一轮的记忆应当原地不动", "他叫李明。", memory.text)
        }
        assertTrue("没收下来就不该写库", saved.isEmpty())
    }

    @Test
    fun `这一轮已经不在列表里就跳过，记忆不动`() = runBlocking {
        val memory = ledger("他叫李明。")

        memory.offer("u9")
        memory.drain(materialOf = { null }, rewrite = { _, _ -> "不该走到这里" })

        assertEquals("他叫李明。", memory.text)
        assertTrue(saved.isEmpty())
    }

    @Test
    fun `收下来的新版写库一次`() = runBlocking {
        val memory = ledger("他叫李明。")

        memory.offer("u1")
        memory.drain(materialOf = { it }, rewrite = { current, _ -> current + "他在准备雅思。" })

        assertEquals(listOf("他叫李明。他在准备雅思。"), saved)
    }
}
