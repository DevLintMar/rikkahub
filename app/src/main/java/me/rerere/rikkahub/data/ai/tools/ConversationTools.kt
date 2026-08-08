package me.rerere.rikkahub.data.ai.tools

import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
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
import me.rerere.rikkahub.data.repository.FolderRepository
import me.rerere.rikkahub.utils.JsonInstantPretty
import me.rerere.rikkahub.utils.toLocalDate
import kotlin.uuid.Uuid

/**
 * Tools that let the assistant query the user's past conversations on demand, instead of
 * statically injecting recent chats into the system prompt (which would break prompt caching).
 */
fun createConversationTools(
    conversationRepo: ConversationRepository,
    folderRepo: FolderRepository,
    assistantId: Uuid,
    conversationId: Uuid,
): List<Tool> = listOf(
    Tool(
        name = "list_conversation_folders",
        description = """
            List the user's conversation folders (including the default unfiled "chat" folder) for the current assistant.
            Use this to obtain folder IDs, then pass `folder_id` to `recent_chats` or `conversation_search` to scope results to a single folder.
            `current_folder_id` / `folders[].is_current` marks the folder the current conversation belongs to.
            `current_folder_id` is null (and the default entry's `is_current` is true) when the current conversation is unfiled in the default folder.
            Pass the obtained `current_folder_id` to `recent_chats` / `conversation_search` as `folder_id` to search the current folder.
        """.trimIndent(),
        parameters = {
            InputSchema.Obj(
                properties = buildJsonObject {}
            )
        },
        execute = {
            val currentFolderId = conversationRepo.getConversationById(conversationId)?.folderId?.toString()
            val folders = folderRepo.getFoldersOfAssistant(assistantId).first()
            val folderCounts = folders.map { folder ->
                folder.id to conversationRepo.getConversationIdsInFolder(folder.id.toString()).size
            }
            val unfiledCount = (conversationRepo.countConversationsOfAssistant(assistantId) - folderCounts.sumOf { it.second })
                .coerceAtLeast(0)
            val payload = buildJsonObject {
                put("type", "list_conversation_folders")
                put("current_folder_id", currentFolderId ?: JsonNull)
                putJsonArray("folders") {
                    add(buildJsonObject {
                        put("id", JsonNull)
                        put("name", "Default (unfiled)")
                        put("is_current", currentFolderId == null)
                        put("conversation_count", unfiledCount)
                    })
                    folders.forEach { folder ->
                        add(buildJsonObject {
                            put("id", folder.id.toString())
                            put("name", folder.name)
                            put("is_current", folder.id.toString() == currentFolderId)
                            put("conversation_count", folderCounts.firstOrNull { it.first == folder.id }?.second ?: 0)
                        })
                    }
                }
            }
            listOf(UIMessagePart.Text(JsonInstantPretty.encodeToString(payload)))
        }
    ),
    Tool(
        name = "recent_chats",
        description = """
            List the user's recent conversations with you to understand their preferences and ongoing topics.
            Returns conversation titles and the date of last activity, ordered by pinned first then most recently updated.
            Use this when you need quick context about what the user has been discussing lately.
            Pass `folder_id` (a folder UUID) to list only conversations in that folder; omit it to list all.
            Only titles and dates are returned; use `conversation_search` to look up the actual content.
            Use `offset` to page through older conversations beyond the first page.
            `has_more` indicates whether more conversations exist after this page.
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
                    put("offset", buildJsonObject {
                        put("type", "integer")
                        put(
                            "description",
                            "Number of conversations to skip, to read older ones (default: 0)"
                        )
                    })
                    put("folder_id", buildJsonObject {
                        put("type", "string")
                        put(
                            "description",
                            "The folder ID (UUID) to restrict results to. Omit to list conversations in all folders."
                        )
                    })
                }
            )
        },
        execute = {
            val limit = (it.jsonObject["limit"]?.jsonPrimitive?.intOrNull ?: 10).coerceIn(1, 50)
            val offset = (it.jsonObject["offset"]?.jsonPrimitive?.intOrNull ?: 0).coerceAtLeast(0)
            val folderId = parseFolderId(it.jsonObject)
            val total = conversationRepo.countConversationsOfAssistant(assistantId, folderId)
            val recent = conversationRepo.getRecentConversations(
                assistantId = assistantId,
                folderId = folderId,
                limit = limit,
                offset = offset,
            )
            val payload = buildJsonObject {
                put("type", "recent_chats")
                put("has_more", offset + limit < total)
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
            Pass `folder_id` (a folder UUID) to search only conversations in that folder; omit it to search all.
            Returns each specific matched message, with the matched keywords marked in [brackets] in `snippet`, the conversation
            (`conversation_id`, `title`), the message's `index` within it, and its `date` (yyyy-MM-dd).
            Results are ordered by date, newest first. `offset` pages to older matches; `has_more` indicates whether more exist.
            Use `read_conversation` with the same `conversation_id` and an `offset` near `index` to read the surrounding context.
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
                            "Maximum number of matched messages to return (default: 15, max: 50)"
                        )
                    })
                    put("offset", buildJsonObject {
                        put("type", "integer")
                        put(
                            "description",
                            "Number of matched messages to skip, to page to older ones (default: 0)"
                        )
                    })
                    put("folder_id", buildJsonObject {
                        put("type", "string")
                        put(
                            "description",
                            "The folder ID (UUID) to restrict results to. Omit to search all conversations."
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
            val offset = (it.jsonObject["offset"]?.jsonPrimitive?.intOrNull ?: 0).coerceAtLeast(0)
            val folderId = parseFolderId(it.jsonObject)
            val page = conversationRepo.searchConversationMessages(query, folderId, limit, offset)
            val payload = buildJsonObject {
                put("type", "conversation_search")
                put("query", query)
                put("offset", offset)
                put("has_more", page.hasMore)
                putJsonArray("results") {
                    page.hits.forEach { h ->
                        add(buildJsonObject {
                            put("conversation_id", h.conversationId)
                            put("title", h.title.ifBlank { "Untitled" })
                            put("index", h.index)
                            put("role", h.role)
                            put("snippet", h.snippet)
                            put("date", h.date)
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
            The message `index` from conversation_search results maps to the `offset` here,
            so pass an `offset` near the index to read the context around a match.
            For long conversations, do not read the whole conversation at once — read only the
            needed window by passing the appropriate `offset` and a small `limit`.
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

/** 解析可选的 folder_id 参数；缺失/非法返回 null（= 全部文件夹）。 */
private fun parseFolderId(args: JsonObject): Uuid? =
    args["folder_id"]?.jsonPrimitive?.contentOrNull
        ?.let { v -> runCatching { Uuid.parse(v) }.getOrNull() }
