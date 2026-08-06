package me.rerere.rikkahub.data.ai

import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import me.rerere.rikkahub.data.model.AssistantMemory
import me.rerere.rikkahub.utils.JsonInstantPretty

internal fun buildMemoryPrompt(
    activeMemories: List<AssistantMemory>,
    savedMemories: List<AssistantMemory>,
) = buildString {
    if (activeMemories.isEmpty() && savedMemories.isEmpty()) return@buildString
    appendLine()
    appendLine("<memories>")
    if (activeMemories.isNotEmpty()) {
        appendLine("Active memories (injected with full content):")
        appendLine(encodeMemoriesJson(activeMemories, includeContent = true))
    }
    if (savedMemories.isNotEmpty()) {
        appendLine("Saved memories (title and description only; use read_memory with a title to fetch the full content):")
        appendLine(encodeMemoriesJson(savedMemories, includeContent = false))
    }
    append("</memories>")
}

/** 记忆条目 JSON 数组: 标题（空则内容前 40 字兜底）+ 描述（空则省略）+ 内容（活跃记忆才有） */
private fun encodeMemoriesJson(memories: List<AssistantMemory>, includeContent: Boolean): String =
    JsonInstantPretty.encodeToString(buildJsonArray {
        memories.forEach { memory ->
            add(buildJsonObject {
                put("title", memory.title.ifBlank { memory.content.trim().take(40) })
                if (memory.description.isNotBlank()) put("description", memory.description)
                if (includeContent) put("content", memory.content)
            })
        }
    })
