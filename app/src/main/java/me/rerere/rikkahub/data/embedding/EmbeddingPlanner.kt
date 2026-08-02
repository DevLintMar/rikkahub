package me.rerere.rikkahub.data.embedding

import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.rikkahub.data.db.entity.EmbeddingStatus
import me.rerere.rikkahub.data.db.entity.MessageEmbeddingEntity

fun planMarkPending(
    messages: List<Pair<String, UIMessage>>,
    existing: List<MessageEmbeddingEntity>,
    conversationId: String,
    modelName: String,
    now: Long,
): List<MessageEmbeddingEntity> {
    val existingByMessageId = existing.associateBy { it.messageId }
    val planned = mutableListOf<MessageEmbeddingEntity>()
    for ((nodeId, message) in messages) {
        if (message.role != MessageRole.USER && message.role != MessageRole.ASSISTANT) continue
        val text = MessageTextExtractor.messageToSearchText(message)
        if (text.isBlank()) continue

        val messageId = message.id.toString()
        val old = existingByMessageId[messageId]
        when {
            old == null -> planned.add(
                MessageEmbeddingEntity(
                    messageId = messageId,
                    nodeId = nodeId,
                    conversationId = conversationId,
                    modelName = modelName,
                    status = EmbeddingStatus.PENDING,
                    chunkText = text,
                    updatedAt = now,
                ),
            )
            old.chunkText != text -> planned.add(
                old.copy(status = EmbeddingStatus.DIRTY, chunkText = text, updatedAt = now),
            )
            old.status == EmbeddingStatus.FAILED -> planned.add(
                old.copy(status = EmbeddingStatus.PENDING, updatedAt = now),
            )
        }
    }
    return planned
}

fun scoreSemanticHits(
    queryEmbedding: FloatArray,
    rows: List<MessageEmbeddingEntity>,
    threshold: Float,
    limit: Int,
): List<SemanticHit> {
    return rows.asSequence()
        .filter { it.status == EmbeddingStatus.INDEXED }
        .filter { it.embedding != null && it.dimension == queryEmbedding.size }
        .mapNotNull { row ->
            val score = EmbeddingIndexer.cosineSimilarity(
                queryEmbedding,
                EmbeddingIndexer.bytesToFloats(row.embedding!!),
            )
            if (score >= threshold) {
                SemanticHit(
                    messageId = row.messageId,
                    nodeId = row.nodeId,
                    conversationId = row.conversationId,
                    score = score,
                    chunkText = row.chunkText,
                ) to score
            } else null
        }
        .sortedByDescending { it.second }
        .take(limit)
        .map { it.first }
        .toList()
}
