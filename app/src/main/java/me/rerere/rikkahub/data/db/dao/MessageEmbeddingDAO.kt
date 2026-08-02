package me.rerere.rikkahub.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import me.rerere.rikkahub.data.db.entity.MessageEmbeddingEntity

@Dao
interface MessageEmbeddingDAO {
    @Query("SELECT * FROM message_embeddings WHERE conversation_id = :conversationId")
    suspend fun getByConversation(conversationId: String): List<MessageEmbeddingEntity>

    @Query("SELECT * FROM message_embeddings WHERE status IN (:statuses) LIMIT :limit")
    suspend fun getPending(statuses: List<Int>, limit: Int): List<MessageEmbeddingEntity>

    @Query("SELECT * FROM message_embeddings WHERE model_name = :modelName")
    suspend fun getByModel(modelName: String): List<MessageEmbeddingEntity>

    @Query("SELECT COUNT(*) FROM message_embeddings")
    suspend fun countAll(): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(rows: List<MessageEmbeddingEntity>)

    @Query("DELETE FROM message_embeddings WHERE conversation_id = :conversationId")
    suspend fun deleteByConversation(conversationId: String)

    @Query("DELETE FROM message_embeddings")
    suspend fun deleteAll()
}
