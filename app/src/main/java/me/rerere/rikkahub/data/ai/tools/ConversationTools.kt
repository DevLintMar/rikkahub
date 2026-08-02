package me.rerere.rikkahub.data.ai.tools

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.MessageRole
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.embedding.MessageTextExtractor
import me.rerere.rikkahub.data.repository.ConversationRepository
import me.rerere.rikkahub.utils.JsonInstantPretty
import me.rerere.rikkahub.utils.toLocalDate
import kotlin.uuid.Uuid

/**
 * Tools that let the assistant query the user's past conversations on demand, instead of
 * statically injecting recent chats into the system prompt (which would break prompt caching).
 */
fun createConversationTools(
    conversationRepo: ConversationRepository,
    assistantId: Uuid,
): List<Tool> = listOf(
    Tool(
        name = "recent_chats",
        description = """
            List the user's recent conversations with you to understand their preferences and ongoing topics.
            Returns conversation titles and the date of last activity, ordered by pinned first then most recently updated.
            Use this when you need quick context about what the user has been discussing lately.
            Only titles and dates are returned; use `conversation_search` to look up the actual content.
        """.trimIndent(),
        parameters = {
            InputSchema.Obj(
                properties = buildJsonObject {
                    put("limit", buildJsonObject {
                        put("type", "integer")
                        put(
                            "description",
                            "Maximum number of recent conversations to return (default: 10, max: 50)"
                        )
                    })
                }
            )
        },
        execute = {
            val limit = (it.jsonObject["limit"]?.jsonPrimitive?.intOrNull ?: 10).coerceIn(1, 50)
            val recent = conversationRepo.getRecentConversations(
                assistantId = assistantId,
                limit = limit,
            )
            val payload = buildJsonObject {
                put("type", "recent_chats")
                putJsonArray("conversations") {
                    recent.forEach { conversation ->
                        add(buildJsonObject {
                            put("id", conversation.id.toString())
                            put("title", conversation.title.ifBlank { "Untitled" })
                            put("last_chat", conversation.updateAt.toLocalDate())
                        })
                    }
                }
            }
            listOf(UIMessagePart.Text(JsonInstantPretty.encodeToString(payload)))
        }
    ),
    Tool(
        name = "conversation_search",
        description = """
            Hybrid semantic + keyword search across the user's past conversations to recall specific information they mentioned before.
            Matches on meaning as well as exact keywords, so paraphrased queries can find relevant past messages.
            Run multiple searches with different phrasings if needed.
            Each result is a window of surrounding messages from one conversation, ordered by relevance, with the conversation title, a relevance score, and the match count.
        """.trimIndent(),
        parameters = {
            InputSchema.Obj(
                properties = buildJsonObject {
                    put("query", buildJsonObject {
                        put("type", "string")
                        put("description", "Keywords to search for in past conversation messages")
                    })
                    put("limit", buildJsonObject {
                        put("type", "integer")
                        put(
                            "description",
                            "Maximum number of matching conversations to return (default: 15, max: 50)"
                        )
                    })
                    put("context_window", buildJsonObject {
                        put("type", "integer")
                        put(
                            "description",
                            "Total context window size around each hit in messages (default: 8, min: 4, max: 32). Larger gives more surrounding context but uses more tokens."
                        )
                    })
                },
                required = listOf("query")
            )
        },
        execute = {
            val query = it.jsonObject["query"]?.jsonPrimitive?.contentOrNull
                ?: error("query is required")
            val limit = (it.jsonObject["limit"]?.jsonPrimitive?.intOrNull ?: 15).coerceIn(1, 50)
            val contextWindow = (it.jsonObject["context_window"]?.jsonPrimitive?.intOrNull ?: 8).coerceIn(4, 32)
            val windows = conversationRepo.searchConversationsHybrid(query, limit, contextWindow)
            val payload = buildJsonObject {
                put("type", "conversation_search")
                put("query", query)
                putJsonArray("results") {
                    windows.forEach { w ->
                        add(buildJsonObject {
                            put("title", w.title.ifBlank { "Untitled" })
                            put("conversation_id", w.conversationId)
                            put("top_score", w.topScore)
                            put("match_count", w.matchCount)
                            putJsonArray("messages") {
                                w.messages.forEach { m ->
                                    add(buildJsonObject {
                                        put("participant", m.participant)
                                        put("text", m.text)
                                        put("timestamp", m.timestamp)
                                    })
                                }
                            }
                        })
                    }
                }
            }
            listOf(UIMessagePart.Text(JsonInstantPretty.encodeToString(payload)))
        }
    ),
    Tool(
        name = "read_conversation",
        description = """
            Read a specific conversation by ID, showing the currently selected message branch
            as a linear list with pagination. Use this after recent_chats or conversation_search
            to read a conversation of interest. Tool and system messages are excluded.
        """.trimIndent(),
        parameters = {
            InputSchema.Obj(
                properties = buildJsonObject {
                    put("conversation_id", buildJsonObject {
                        put("type", "string")
                        put("description", "The conversation ID to read (from recent_chats or conversation_search results).")
                    })
                    put("offset", buildJsonObject {
                        put("type", "integer")
                        put("description", "Number of messages to skip (default: 0).")
                    })
                    put("limit", buildJsonObject {
                        put("type", "integer")
                        put("description", "Maximum messages to return (default: 50, max: 100).")
                    })
                },
                required = listOf("conversation_id"),
            )
        },
        execute = {
            val conversationId = it.jsonObject["conversation_id"]?.jsonPrimitive?.contentOrNull
                ?: error("conversation_id is required")
            val offset = (it.jsonObject["offset"]?.jsonPrimitive?.intOrNull ?: 0).coerceAtLeast(0)
            val limit = (it.jsonObject["limit"]?.jsonPrimitive?.intOrNull ?: 50).coerceIn(1, 100)

            val conversation = conversationRepo.getConversationById(Uuid.parse(conversationId))
                ?: error("conversation not found: $conversationId")
            val messages = runCatching { conversation.currentMessages }
                .getOrDefault(emptyList())
                .filter { m -> m.role == MessageRole.USER || m.role == MessageRole.ASSISTANT }

            val page = messages.drop(offset).take(limit)
            val payload = buildJsonObject {
                put("type", "read_conversation")
                put("conversation_id", conversationId)
                put("title", conversation.title.ifBlank { "Untitled" })
                put("total_messages", messages.size)
                put("offset", offset)
                put("limit", limit)
                put("has_more", offset + limit < messages.size)
                putJsonArray("messages") {
                    page.forEach { m ->
                        add(buildJsonObject {
                            put("role", m.role.name.lowercase())
                            put("text", MessageTextExtractor.messageToSearchText(m))
                            put("timestamp", m.createdAt.toString())
                        })
                    }
                }
            }
            listOf(UIMessagePart.Text(JsonInstantPretty.encodeToString(payload)))
        },
    )
)
