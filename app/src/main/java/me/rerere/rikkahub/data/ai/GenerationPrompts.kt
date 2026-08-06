package me.rerere.rikkahub.data.ai

import me.rerere.rikkahub.data.model.AssistantMemory

internal fun buildMemoryPrompt(
    activeMemories: List<AssistantMemory>,
    savedMemories: List<AssistantMemory>,
) = buildString {
    appendLine()
    append("**Memories**")
    appendLine()
    if (activeMemories.isNotEmpty()) {
        appendLine("Active memories:")
        activeMemories.forEach { memory ->
            val title = memory.title.ifBlank { memory.content.trim().take(40) }
            if (memory.description.isBlank()) {
                appendLine("- $title")
            } else {
                appendLine("- $title — ${memory.description}")
            }
            appendLine("  ${memory.content}")
        }
    }
    if (savedMemories.isNotEmpty()) {
        appendLine("Saved memories (use read_memory to read the full content of any):")
        savedMemories.forEach { memory ->
            val title = memory.title.ifBlank { memory.content.trim().take(40) }
            if (memory.description.isBlank()) {
                appendLine("- $title")
            } else {
                appendLine("- $title — ${memory.description}")
            }
        }
    }
    append(
        "Active memories above are listed with their full content; saved memories are listed by title and description only — call read_memory with a title to fetch the full content."
    )
}
