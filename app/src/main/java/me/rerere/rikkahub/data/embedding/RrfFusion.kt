package me.rerere.rikkahub.data.embedding

import me.rerere.rikkahub.data.db.fts.MessageSearchResult

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
): List<MessageSearchResult> =
    rrfFuseScored(fts, semantic, k).map { it.source }

/** RRF 融合结果，保留每个命中的融合分（供上下文窗口排序用）。 */
data class FusedHit(
    val source: MessageSearchResult,
    val score: Double,
) {
    val messageId get() = source.messageId
    val nodeId get() = source.nodeId
    val conversationId get() = source.conversationId
    val snippet get() = source.snippet
}

fun rrfFuseScored(
    fts: List<MessageSearchResult>,
    semantic: List<MessageSearchResult>,
    k: Int = 60,
): List<FusedHit> {
    if (fts.isEmpty() && semantic.isEmpty()) return emptyList()

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
        .mapNotNull { (messageId, score) ->
            byId[messageId]?.let { FusedHit(it, score) }
        }
}
