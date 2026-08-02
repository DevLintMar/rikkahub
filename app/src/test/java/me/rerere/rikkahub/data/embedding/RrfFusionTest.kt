package me.rerere.rikkahub.data.embedding

import me.rerere.rikkahub.data.db.fts.MessageSearchResult
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.Instant

class RrfFusionTest {
    private fun res(id: String) = MessageSearchResult(
        nodeId = "n-$id", messageId = id, conversationId = "c",
        title = "t", updateAt = Instant.EPOCH, snippet = "",
    )

    @Test
    fun `message in both lists ranks above single-list hits`() {
        val fts = listOf(res("a"), res("b"))
        val semantic = listOf(res("a"), res("c"))
        val fused = rrfFuse(fts, semantic, k = 60)
        // a 两路命中分数最高，排第一
        assertEquals("a", fused.first().messageId)
        // 去重后共 3 条
        assertEquals(setOf("a", "b", "c"), fused.map { it.messageId }.toSet())
    }

    @Test
    fun `empty semantic returns fts order`() {
        val fts = listOf(res("a"), res("b"))
        assertEquals(listOf("a", "b"), rrfFuse(fts, emptyList()).map { it.messageId })
    }

    @Test
    fun `empty fts returns semantic order`() {
        val semantic = listOf(res("a"), res("b"))
        assertEquals(listOf("a", "b"), rrfFuse(emptyList(), semantic).map { it.messageId })
    }
}
