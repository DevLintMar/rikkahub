package me.rerere.rikkahub.data.ai.tools

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.model.AssistantMemory

private fun JsonObject.string(name: String): String? =
    this[name]?.jsonPrimitive?.contentOrNull

/**
 * 记忆工具集。由调用方（GenerationHandler）按门槛决定包含哪些：
 * - read_memory: enableMemory
 * - update_active_memory: enableMemory && enableEditActiveMemory
 * - write_memory / edit_memory / delete_memory: enableMemory && enableEditSavedMemories
 */
fun buildMemoryTools(
    json: Json,
    readMemoryByTitle: suspend (title: String) -> AssistantMemory?,
    updateActiveMemory: suspend (content: String, mode: String, oldString: String, newString: String) -> AssistantMemory,
    writeMemory: suspend (title: String, description: String, content: String, overwrite: Boolean) -> AssistantMemory,
    editMemory: suspend (
        title: String, newTitle: String?, description: String?, content: String?,
        oldText: String?, newText: String?, replaceAll: Boolean,
    ) -> AssistantMemory,
    deleteMemoryByTitle: suspend (title: String) -> Boolean,
    includeActiveEdit: Boolean,
    includeSavedEdit: Boolean,
): List<Tool> {
    val tools = mutableListOf<Tool>()
    tools += Tool(
        name = "read_memory",
        description = "Read the full content of a saved memory by its title. Use this when you need the complete text of a memory whose title and description are listed in the system prompt; only titles and descriptions are injected, not full contents.",
        parameters = {
            InputSchema.Obj(
                properties = buildJsonObject {
                    put("title", buildJsonObject {
                        put("type", "string")
                        put("description", "The exact title of the memory to read")
                    })
                },
                required = listOf("title"),
            )
        },
        execute = {
            val title = it.jsonObject.string("title") ?: error("title is required")
            val memory = readMemoryByTitle(title) ?: error("Memory not found: $title")
            listOf(UIMessagePart.Text(json.encodeToString(buildJsonObject {
                put("type", JsonPrimitive("read_memory"))
                put("id", memory.id)
                put("title", memory.title)
                put("description", memory.description)
                put("content", memory.content)
            })))
        },
    )
    if (includeActiveEdit) {
        tools += Tool(
            name = "update_active_memory",
            description = "Update the active memory context. Modes: 'replace' (overwrite with 'content'), 'append' (add 'content' to end), 'prepend' (add 'content' to beginning), 'patch' (find 'old_string' exactly once and replace with 'new_string'). Default is replace.",
            parameters = {
                InputSchema.Obj(
                    properties = buildJsonObject {
                        put("content", buildJsonObject {
                            put("type", "string")
                            put("description", "The content to write (for replace/append/prepend modes)")
                        })
                        put("mode", buildJsonObject {
                            put("type", "string")
                            put("enum", buildJsonArray {
                                add("replace")
                                add("append")
                                add("prepend")
                                add("patch")
                            })
                            put("description", "One of: replace, append, prepend, patch. Default is replace.")
                        })
                        put("old_string", buildJsonObject {
                            put("type", "string")
                            put("description", "Exact string to find in the active memory. Required for patch mode.")
                        })
                        put("new_string", buildJsonObject {
                            put("type", "string")
                            put("description", "Replacement string for old_string in patch mode. Pass empty string to delete the matched text.")
                        })
                    },
                    required = listOf("content"),
                )
            },
            execute = {
                val content = it.jsonObject.string("content") ?: error("content is required")
                val mode = it.jsonObject.string("mode") ?: "replace"
                val oldString = it.jsonObject.string("old_string").orEmpty()
                val newString = it.jsonObject.string("new_string").orEmpty()
                val memory = updateActiveMemory(content, mode, oldString, newString)
                listOf(UIMessagePart.Text(json.encodeToString(buildJsonObject {
                    put("type", JsonPrimitive("update_active_memory"))
                    put("id", memory.id)
                    put("content", memory.content)
                })))
            },
        )
    }
    if (includeSavedEdit) {
        tools += Tool(
            name = "write_memory",
            description = "Create a new saved memory with the given title, optional description, and content. If a memory with the same title already exists, you MUST explicitly pass overwrite=true to replace it; otherwise the write fails.",
            parameters = {
                InputSchema.Obj(
                    properties = buildJsonObject {
                        put("title", buildJsonObject {
                            put("type", "string")
                            put("description", "The title of the memory. Must be unique among saved memories.")
                        })
                        put("content", buildJsonObject {
                            put("type", "string")
                            put("description", "The content of the memory")
                        })
                        put("description", buildJsonObject {
                            put("type", "string")
                            put("description", "A short description of what this memory contains (optional)")
                        })
                        put("overwrite", buildJsonObject {
                            put("type", "boolean")
                            put("description", "Whether to overwrite an existing memory with the same title. Defaults to false. Must be explicitly true to replace.")
                        })
                    },
                    required = listOf("title", "content"),
                )
            },
            execute = {
                val title = it.jsonObject.string("title") ?: error("title is required")
                val content = it.jsonObject.string("content") ?: error("content is required")
                val description = it.jsonObject.string("description").orEmpty()
                val overwrite = it.jsonObject["overwrite"]
                    ?.jsonPrimitive?.contentOrNull?.toBooleanStrictOrNull() ?: false
                val memory = writeMemory(title, description, content, overwrite)
                listOf(UIMessagePart.Text(json.encodeToString(buildJsonObject {
                    put("type", JsonPrimitive("write_memory"))
                    put("id", memory.id)
                    put("title", memory.title)
                    put("description", memory.description)
                    put("content", memory.content)
                })))
            },
        )
        tools += Tool(
            name = "edit_memory",
            description = "Edit an existing saved memory identified by its title. Provide 'content' for a full rewrite, or 'old_text' + 'new_text' for a precise replacement (old_text must match exactly once unless replace_all=true). Optionally rename with 'new_title' or update the 'description'. At least one of content, old_text, new_title, or description must be provided.",
            parameters = {
                InputSchema.Obj(
                    properties = buildJsonObject {
                        put("title", buildJsonObject {
                            put("type", "string")
                            put("description", "The current title of the memory to edit")
                        })
                        put("content", buildJsonObject {
                            put("type", "string")
                            put("description", "Full replacement content (optional, mutually preferred with old_text)")
                        })
                        put("old_text", buildJsonObject {
                            put("type", "string")
                            put("description", "Exact text to replace inside the memory content (optional)")
                        })
                        put("new_text", buildJsonObject {
                            put("type", "string")
                            put("description", "Replacement text for old_text (required when old_text is provided)")
                        })
                        put("replace_all", buildJsonObject {
                            put("type", "boolean")
                            put("description", "Whether to replace every occurrence of old_text. Defaults to false.")
                        })
                        put("new_title", buildJsonObject {
                            put("type", "string")
                            put("description", "New title to rename the memory to (optional)")
                        })
                        put("description", buildJsonObject {
                            put("type", "string")
                            put("description", "New description for the memory (optional)")
                        })
                    },
                    required = listOf("title"),
                )
            },
            execute = {
                val title = it.jsonObject.string("title") ?: error("title is required")
                val newTitle = it.jsonObject.string("new_title")
                val description = it.jsonObject.string("description")
                val content = it.jsonObject.string("content")
                val oldText = it.jsonObject.string("old_text")
                val newText = it.jsonObject.string("new_text")
                val replaceAll = it.jsonObject["replace_all"]
                    ?.jsonPrimitive?.contentOrNull?.toBooleanStrictOrNull() ?: false
                require(
                    content != null || newTitle != null || description != null || oldText != null
                ) { "At least one of content, new_title, description, or old_text must be provided" }
                val memory = editMemory(title, newTitle, description, content, oldText, newText, replaceAll)
                listOf(UIMessagePart.Text(json.encodeToString(buildJsonObject {
                    put("type", JsonPrimitive("edit_memory"))
                    put("id", memory.id)
                    put("title", memory.title)
                    put("description", memory.description)
                    put("content", memory.content)
                })))
            },
        )
        tools += Tool(
            name = "delete_memory",
            description = "Delete a saved memory by its title.",
            parameters = {
                InputSchema.Obj(
                    properties = buildJsonObject {
                        put("title", buildJsonObject {
                            put("type", "string")
                            put("description", "The title of the memory to delete")
                        })
                    },
                    required = listOf("title"),
                )
            },
            execute = {
                val title = it.jsonObject.string("title") ?: error("title is required")
                deleteMemoryByTitle(title)
                listOf(UIMessagePart.Text(json.encodeToString(buildJsonObject {
                    put("type", JsonPrimitive("delete_memory"))
                    put("title", title)
                    put("success", true)
                })))
            },
        )
    }
    return tools
}
