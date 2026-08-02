package me.rerere.rikkahub.data.embedding

import android.content.Context
import android.util.Log
import androidx.work.BackoffPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import me.rerere.rikkahub.data.db.dao.MessageEmbeddingDAO
import me.rerere.rikkahub.data.db.entity.EmbeddingStatus
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.service.EmbeddingIndexWorker
import java.util.concurrent.TimeUnit
import kotlin.uuid.Uuid

private const val TAG = "SemanticIndexManager"

class SemanticIndexManager(
    private val dao: MessageEmbeddingDAO,
    private val embeddingClient: EmbeddingClient,
    private val settingsStore: SettingsStore,
    private val context: Context,
) {
    data class IndexCounts(val indexed: Int, val failed: Int)

    suspend fun isConfigured(): Boolean {
        val c = settingsStore.settingsFlowRaw.first().embedder
        return c.enabled && c.apiKey.isNotBlank() && c.baseUrl.isNotBlank()
    }

    private suspend fun currentConfig() =
        settingsStore.settingsFlowRaw.first().embedder

    /** 保存路径调用：纯本地写，标记 PENDING/DIRTY；有活时排期 worker。 */
    suspend fun markPending(conversation: Conversation): Boolean {
        val config = currentConfig()
        if (!config.enabled) return false

        val conversationId = conversation.id.toString()
        val existing = dao.getByConversation(conversationId)
        val now = System.currentTimeMillis()

        // 收集 (nodeId, message)，遍历全部节点下全部消息（与 FTS 索引范围一致）
        val pairs = conversation.messageNodes.flatMap { node ->
            node.messages.map { node.id.toString() to it }
        }
        val planned = planMarkPending(
            messages = pairs,
            existing = existing,
            conversationId = conversationId,
            modelName = config.model,
            now = now,
        )
        if (planned.isEmpty()) return false
        dao.upsertAll(planned)
        EmbeddingIndexWorker.enqueue(context)
        return true
    }

    /** worker 调用：批量嵌入 PENDING/DIRTY/FAILED。 */
    suspend fun indexPending(limit: Int = 64): IndexCounts = withContext(Dispatchers.IO) {
        val config = currentConfig()
        if (!config.enabled) return@withContext IndexCounts(0, 0)

        val rows = dao.getPending(statuses = listOf(
            EmbeddingStatus.PENDING, EmbeddingStatus.DIRTY, EmbeddingStatus.FAILED,
        ), limit = limit)
        if (rows.isEmpty()) return@withContext IndexCounts(0, 0)

        val texts = rows.map { it.chunkText }
        val embeddings = embeddingClient.computeEmbeddings(
            texts = texts,
            model = config.model,
            baseUrl = config.baseUrl,
            apiKey = config.apiKey,
        )
        val updated = rows.mapIndexed { i, row ->
            val emb = embeddings.getOrNull(i)
            if (emb != null) {
                row.copy(
                    status = EmbeddingStatus.INDEXED,
                    embedding = EmbeddingIndexer.floatsToBytes(emb),
                    dimension = emb.size,
                    modelName = config.model,
                    updatedAt = System.currentTimeMillis(),
                )
            } else {
                row.copy(status = EmbeddingStatus.FAILED, updatedAt = System.currentTimeMillis())
            }
        }
        dao.upsertAll(updated)
        val indexed = updated.count { it.status == EmbeddingStatus.INDEXED }
        IndexCounts(indexed = indexed, failed = updated.size - indexed)
    }

    /** 搜索：query 嵌入 + 余弦 + 阈值 + 维度保护。 */
    suspend fun search(query: String, limit: Int): List<SemanticHit> = withContext(Dispatchers.IO) {
        val config = currentConfig()
        if (!config.enabled) return@withContext emptyList()

        val queryEmbedding = embeddingClient.computeEmbeddings(
            texts = listOf(query),
            model = config.model,
            baseUrl = config.baseUrl,
            apiKey = config.apiKey,
        ).firstOrNull() ?: return@withContext emptyList()

        val rows = dao.getByModel(config.model)
        if (rows.isEmpty()) return@withContext emptyList()

        scoreSemanticHits(queryEmbedding, rows, RAG_THRESHOLD, limit)
    }

    suspend fun deleteConversation(conversationId: Uuid) {
        dao.deleteByConversation(conversationId.toString())
    }

    suspend fun deleteAll() {
        dao.deleteAll()
    }
}
