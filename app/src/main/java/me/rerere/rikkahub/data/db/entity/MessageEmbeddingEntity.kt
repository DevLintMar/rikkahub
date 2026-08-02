package me.rerere.rikkahub.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "message_embeddings")
data class MessageEmbeddingEntity(
    @PrimaryKey @ColumnInfo("message_id") val messageId: String,
    @ColumnInfo("node_id") val nodeId: String,
    @ColumnInfo("conversation_id") val conversationId: String,
    @ColumnInfo("model_name") val modelName: String,
    val status: Int,
    @ColumnInfo("chunk_text") val chunkText: String,
    val embedding: ByteArray? = null,
    val dimension: Int? = null,
    @ColumnInfo("updated_at") val updatedAt: Long = 0L,
)

object EmbeddingStatus {
    const val PENDING = 0
    const val INDEXED = 1
    const val DIRTY = 2
    const val FAILED = 3
}
