package me.rerere.rikkahub.data.ai.tools

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
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
 * - create_active_memory / edit_active_memory / delete_active_memory: enableMemory && enableEditActiveMemory
 * - write_memory / edit_memory / delete_memory: enableMemory && enableEditSavedMemories
 *
 * saved 与 active 的 write/edit/delete 工具结构完全同构，复用私有辅助函数
 * （active 走 createActiveMemory / editActiveMemory / deleteActiveMemoryByTitle）。
 * edit/delete 的 handler 会先经 read 函数查出旧内容（previous_content / 完整记录），
 * 供 UI 侧恢复，故信封统一带 scope_id（memoryAssistantId）。
 */
fun buildMemoryTools(
    json: Json,
    memoryAssistantId: String,
    readMemoryByTitle: suspend (title: String) -> AssistantMemory?,
    readActiveMemoryByTitle: suspend (title: String) -> AssistantMemory?,
    writeMemory: suspend (title: String, description: String, content: String, overwrite: Boolean) -> AssistantMemory,
    editMemory: suspend (
        title: String, newTitle: String?, description: String?, content: String?,
        oldText: String?, newText: String?, replaceAll: Boolean,
    ) -> AssistantMemory,
    deleteMemoryByTitle: suspend (title: String) -> Boolean,
    createActiveMemory: suspend (title: String, description: String, content: String, overwrite: Boolean) -> AssistantMemory,
    editActiveMemory: suspend (
        title: String, newTitle: String?, description: String?, content: String?,
        oldText: String?, newText: String?, replaceAll: Boolean,
    ) -> AssistantMemory,
    deleteActiveMemoryByTitle: suspend (title: String) -> Boolean,
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
        tools += buildWriteTool(
            json = json,
            toolName = "create_active_memory",
            toolDescription = "Create a new active memory with the given title, optional description, and content. Active memories are injected into the system prompt as id + content. If an active memory with the same title already exists, you MUST explicitly pass overwrite=true to replace it; otherwise the write fails.",
            titleDescription = "The title of the active memory. Must be unique among active memories.",
            scopeId = memoryAssistantId,
            writeFn = createActiveMemory,
        )
        tools += buildEditTool(
            json = json,
            toolName = "edit_active_memory",
            toolDescription = "Edit an existing active memory identified by its title. Provide 'content' for a full rewrite, or 'old_text' + 'new_text' for a precise replacement (old_text must match exactly once unless replace_all=true). Optionally rename with 'new_title' or update the 'description'. At least one of content, old_text, new_title, or description must be provided.",
            scopeId = memoryAssistantId,
            readFn = readActiveMemoryByTitle,
            editFn = editActiveMemory,
        )
        tools += buildDeleteTool(
            json = json,
            toolName = "delete_active_memory",
            description = "Delete an active memory by its title.",
            scopeId = memoryAssistantId,
            readFn = readActiveMemoryByTitle,
            deleteFn = deleteActiveMemoryByTitle,
        )
    }
    if (includeSavedEdit) {
        tools += buildWriteTool(
            json = json,
            toolName = "write_memory",
            toolDescription = "Create a new saved memory with the given title, optional description, and content. If a memory with the same title already exists, you MUST explicitly pass overwrite=true to replace it; otherwise the write fails.",
            titleDescription = "The title of the memory. Must be unique among saved memories.",
            scopeId = memoryAssistantId,
            writeFn = writeMemory,
        )
        tools += buildEditTool(
            json = json,
            toolName = "edit_memory",
            toolDescription = "Edit an existing saved memory identified by its title. Provide 'content' for a full rewrite, or 'old_text' + 'new_text' for a precise replacement (old_text must match exactly once unless replace_all=true). Optionally rename with 'new_title' or update the 'description'. At least one of content, old_text, new_title, or description must be provided.",
            scopeId = memoryAssistantId,
            readFn = readMemoryByTitle,
            editFn = editMemory,
        )
        tools += buildDeleteTool(
            json = json,
            toolName = "delete_memory",
            description = "Delete a saved memory by its title.",
            scopeId = memoryAssistantId,
            readFn = readMemoryByTitle,
            deleteFn = deleteMemoryByTitle,
        )
    }
    return tools
}

/** 新增记忆工具（write_memory / create_active_memory 同构），信封带 scope_id。 */
private fun buildWriteTool(
    json: Json,
    toolName: String,
    toolDescription: String,
    titleDescription: String,
    scopeId: String,
    writeFn: suspend (title: String, description: String, content: String, overwrite: Boolean) -> AssistantMemory,
): Tool = Tool(
    name = toolName,
    description = toolDescription,
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("title", buildJsonObject {
                    put("type", "string")
                    put("description", titleDescription)
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
        val memory = writeFn(title, description, content, overwrite)
        listOf(UIMessagePart.Text(json.encodeToString(buildJsonObject {
            put("type", JsonPrimitive(toolName))
            put("id", memory.id)
            put("title", memory.title)
            put("description", memory.description)
            put("content", memory.content)
            put("scope_id", scopeId)
        })))
    },
)

/** 编辑记忆工具（edit_memory / edit_active_memory 同构），先查旧内容，信封带 previous_content + scope_id。 */
private fun buildEditTool(
    json: Json,
    toolName: String,
    toolDescription: String,
    scopeId: String,
    readFn: suspend (title: String) -> AssistantMemory?,
    editFn: suspend (
        title: String, newTitle: String?, description: String?, content: String?,
        oldText: String?, newText: String?, replaceAll: Boolean,
    ) -> AssistantMemory,
): Tool = Tool(
    name = toolName,
    description = toolDescription,
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
        val previous = readFn(title)
        val memory = editFn(title, newTitle, description, content, oldText, newText, replaceAll)
        listOf(UIMessagePart.Text(json.encodeToString(buildJsonObject {
            put("type", JsonPrimitive(toolName))
            put("id", memory.id)
            put("title", memory.title)
            put("description", memory.description)
            put("content", memory.content)
            if (previous != null) {
                put("previous_content", previous.content)
            }
            put("scope_id", scopeId)
        })))
    },
)

/** 删除记忆工具（delete_memory / delete_active_memory 同构），先查后删，信封带完整记忆信息 + scope_id。 */
private fun buildDeleteTool(
    json: Json,
    toolName: String,
    description: String,
    scopeId: String,
    readFn: suspend (title: String) -> AssistantMemory?,
    deleteFn: suspend (title: String) -> Boolean,
): Tool = Tool(
    name = toolName,
    description = description,
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
        val memory = readFn(title)
        val success = deleteFn(title)
        if (!success) error("Memory not found: $title")
        listOf(UIMessagePart.Text(json.encodeToString(buildJsonObject {
            put("type", JsonPrimitive(toolName))
            put("title", title)
            if (memory != null) {
                put("content", memory.content)
                put("description", memory.description)
            }
            put("scope_id", scopeId)
            put("success", success)
        })))
    },
)
