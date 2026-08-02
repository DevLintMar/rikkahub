package me.rerere.rikkahub.data.db.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "message_embeddings")
data class MessageEmbeddingEntity(
    @PrimaryKey val messageId: String,
    val nodeId: String,
    val conversationId: String,
    val modelName: String,
    val status: Int,
    val chunkText: String,
    val embedding: ByteArray? = null,
    val dimension: Int? = null,
    val updatedAt: Long = 0L,
)

object EmbeddingStatus {
    const val PENDING = 0
    const val INDEXED = 1
    const val DIRTY = 2
    const val FAILED = 3
}
