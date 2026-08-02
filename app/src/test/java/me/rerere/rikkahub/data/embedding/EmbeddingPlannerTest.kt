package me.rerere.rikkahub.data.embedding

import kotlin.uuid.Uuid
import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.db.entity.MessageEmbeddingEntity
import me.rerere.rikkahub.data.db.entity.EmbeddingStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class EmbeddingPlannerTest {

    // UIMessage.id is a kotlin.uuid.Uuid, so planMarkPending derives the messageId
    // from message.id.toString(). Use deterministic UUIDs so tests that must match
    // an existing MessageEmbeddingEntity.messageId actually line up.
    private val M1 = "00000000-0000-0000-0000-000000000001"
    private val M2 = "00000000-0000-0000-0000-000000000002"
    private val M3 = "00000000-0000-0000-0000-000000000003"

    private fun msg(id: String, role: MessageRole, text: String): UIMessage =
        UIMessage(id = Uuid.parse(id), role = role, parts = listOf(UIMessagePart.Text(text)))

    @Test
    fun `new user message becomes pending`() {
        val planned = planMarkPending(
            messages = listOf("n1" to msg(M1, MessageRole.USER, "hi")),
            existing = emptyList(),
            conversationId = "c",
            modelName = "m",
            now = 100L,
        )
        assertEquals(1, planned.size)
        assertEquals(EmbeddingStatus.PENDING, planned[0].status)
        assertEquals("hi", planned[0].chunkText)
        assertEquals("n1", planned[0].nodeId)
    }

    @Test
    fun `unchanged message is not planned`() {
        val existing = listOf(
            MessageEmbeddingEntity(
                messageId = M1, nodeId = "n", conversationId = "c",
                modelName = "m", status = EmbeddingStatus.INDEXED, chunkText = "hi", updatedAt = 1L,
            ),
        )
        val planned = planMarkPending(
            messages = listOf("n" to msg(M1, MessageRole.USER, "hi")),
            existing = existing,
            conversationId = "c",
            modelName = "m",
            now = 100L,
        )
        assertTrue(planned.isEmpty())
    }

    @Test
    fun `changed text marks dirty`() {
        val existing = listOf(
            MessageEmbeddingEntity(
                messageId = M1, nodeId = "n", conversationId = "c",
                modelName = "m", status = EmbeddingStatus.INDEXED, chunkText = "old", updatedAt = 1L,
            ),
        )
        val planned = planMarkPending(
            messages = listOf("n" to msg(M1, MessageRole.USER, "new text")),
            existing = existing,
            conversationId = "c",
            modelName = "m",
            now = 100L,
        )
        assertEquals(1, planned.size)
        assertEquals(EmbeddingStatus.DIRTY, planned[0].status)
    }

    @Test
    fun `tool and system messages are skipped`() {
        val planned = planMarkPending(
            messages = listOf(
                "n" to msg(M1, MessageRole.TOOL, "tool call"),
                "n" to msg(M2, MessageRole.SYSTEM, "system"),
                "n" to msg(M3, MessageRole.USER, ""),
            ),
            existing = emptyList(),
            conversationId = "c",
            modelName = "m",
            now = 100L,
        )
        assertTrue(planned.isEmpty())
    }

    @Test
    fun `scoring filters by threshold and dimension`() {
        val q = floatArrayOf(1f, 0f)
        val rows = listOf(
            MessageEmbeddingEntity(
                messageId = "a", nodeId = "n", conversationId = "c",
                modelName = "m", status = EmbeddingStatus.INDEXED,
                chunkText = "a", embedding = EmbeddingIndexer.floatsToBytes(floatArrayOf(1f, 0f)),
                dimension = 2,
            ),
            MessageEmbeddingEntity(
                messageId = "b", nodeId = "n", conversationId = "c",
                modelName = "m", status = EmbeddingStatus.INDEXED,
                chunkText = "b", embedding = EmbeddingIndexer.floatsToBytes(floatArrayOf(0f, 1f)),
                dimension = 2,
            ),
            MessageEmbeddingEntity(
                messageId = "dim", nodeId = "n", conversationId = "c",
                modelName = "m", status = EmbeddingStatus.INDEXED,
                chunkText = "dim", embedding = EmbeddingIndexer.floatsToBytes(floatArrayOf(1f)),
                dimension = 1,
            ),
        )
        val hits = scoreSemanticHits(q, rows, threshold = 0.5f, limit = 10)
        assertEquals(listOf("a"), hits.map { it.messageId }) // b 余弦=0 被阈值过滤，dim 维度不符被过滤
    }
}
