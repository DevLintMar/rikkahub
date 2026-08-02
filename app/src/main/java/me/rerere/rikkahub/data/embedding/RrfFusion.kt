package me.rerere.rikkahub.data.embedding

import me.rerere.rikkahub.data.db.fts.MessageSearchResult

const val RAG_THRESHOLD = 0.3f

data class SemanticHit(
    val messageId: String,
    val nodeId: String,
    val conversationId: String,
    val score: Float,
    val chunkText: String,
)

fun rrfFuse(
    fts: List<MessageSearchResult>,
    semantic: List<MessageSearchResult>,
    k: Int = 60,
): List<MessageSearchResult> {
    if (fts.isEmpty()) return semantic
    if (semantic.isEmpty()) return fts

    val scores = linkedMapOf<String, Double>()
    val byId = linkedMapOf<String, MessageSearchResult>()
    fun addRanked(list: List<MessageSearchResult>) {
        list.forEachIndexed { index, item ->
            byId.putIfAbsent(item.messageId, item)
            val s = scores[item.messageId] ?: 0.0
            scores[item.messageId] = s + 1.0 / (k + index + 1)
        }
    }
    addRanked(fts)
    addRanked(semantic)

    return scores.entries
        .sortedByDescending { it.value }
        .mapNotNull { byId[it.key] }
}
