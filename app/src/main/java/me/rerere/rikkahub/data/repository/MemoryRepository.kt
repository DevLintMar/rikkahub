package me.rerere.rikkahub.data.repository

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import me.rerere.rikkahub.data.ai.tools.replaceText
import me.rerere.rikkahub.data.db.dao.MemoryDAO
import me.rerere.rikkahub.data.db.entity.MemoryEntity
import me.rerere.rikkahub.data.model.AssistantMemory

enum class ActiveMemoryMode { REPLACE, APPEND, PREPEND, PATCH }

class MemoryRepository(private val memoryDAO: MemoryDAO) {
    companion object {
        const val GLOBAL_MEMORY_ID = "__global__"
    }

    private fun MemoryEntity.toModel() = AssistantMemory(
        id = id,
        title = title,
        description = description,
        content = content,
        isActive = isActive,
    )

    /** 内容首行推导标题兜底（与 Migration_25_26 回填规则一致） */
    private fun AssistantMemory.derivedTitle(): String = content.trim().take(40)

    fun getMemoriesOfAssistantFlow(assistantId: String): Flow<List<AssistantMemory>> =
        memoryDAO.getMemoriesOfAssistantFlow(assistantId)
            .map { entities -> entities.map { it.toModel() } }

    suspend fun getMemoriesOfAssistant(assistantId: String): List<AssistantMemory> =
        memoryDAO.getMemoriesOfAssistant(assistantId).map { it.toModel() }

    fun getGlobalMemoriesFlow(): Flow<List<AssistantMemory>> =
        memoryDAO.getMemoriesOfAssistantFlow(GLOBAL_MEMORY_ID)
            .map { entities -> entities.map { it.toModel() } }

    suspend fun getGlobalMemories(): List<AssistantMemory> =
        memoryDAO.getMemoriesOfAssistant(GLOBAL_MEMORY_ID).map { it.toModel() }

    fun getActiveMemoryFlow(assistantId: String): Flow<AssistantMemory?> =
        memoryDAO.getActiveMemoryFlow(assistantId).map { it?.toModel() }

    suspend fun getActiveMemory(assistantId: String): AssistantMemory? =
        memoryDAO.getActiveMemory(assistantId)?.toModel()

    suspend fun updateActiveMemory(
        assistantId: String,
        content: String,
        mode: ActiveMemoryMode = ActiveMemoryMode.REPLACE,
        oldString: String = "",
        newString: String = "",
    ): AssistantMemory {
        val current = memoryDAO.getActiveMemory(assistantId)
        val newContent = when (mode) {
            ActiveMemoryMode.REPLACE -> content
            ActiveMemoryMode.APPEND -> (current?.content.orEmpty()) + content
            ActiveMemoryMode.PREPEND -> content + current?.content.orEmpty()
            ActiveMemoryMode.PATCH -> {
                val base = current?.content ?: error("Active memory is empty; patch requires existing content")
                require(oldString.isNotEmpty()) { "old_string is required for patch mode" }
                val count = base.windowed(oldString.length).count { it == oldString }
                require(count == 1) { "old_string matches $count locations in active memory; it must match exactly once" }
                base.replace(oldString, newString)
            }
        }
        val updated = current
            ?.let { it.copy(content = newContent).also { memoryDAO.updateMemory(it) } }
            ?: MemoryEntity(assistantId = assistantId, content = newContent, isActive = true)
                .let { it.copy(id = memoryDAO.insertMemory(it).toInt()) }
        return updated.toModel()
    }

    suspend fun getMemoryByTitle(assistantId: String, title: String): AssistantMemory? {
        memoryDAO.getMemoryByTitle(assistantId, title)?.let { return it.toModel() }
        if (title.isBlank()) return null
        // 回退：旧记忆标题为空时用内容首行推导标题匹配
        return getMemoriesOfAssistant(assistantId).firstOrNull { it.derivedTitle() == title }
    }

    suspend fun addMemory(
        assistantId: String,
        title: String,
        description: String,
        content: String,
        overwrite: Boolean,
    ): AssistantMemory {
        require(title.isNotBlank()) { "title is required" }
        val existing = memoryDAO.getMemoryByTitle(assistantId, title)
        if (existing != null) {
            if (!overwrite) {
                error("A memory with title \"$title\" already exists; pass overwrite=true to replace it")
            }
            val updated = existing.copy(title = title, description = description, content = content)
            memoryDAO.updateMemory(updated)
            return updated.toModel()
        }
        val entity = MemoryEntity(
            assistantId = assistantId,
            title = title,
            description = description,
            content = content,
        )
        return entity.copy(id = memoryDAO.insertMemory(entity).toInt()).toModel()
    }

    suspend fun updateMemory(id: Int, title: String, description: String, content: String): AssistantMemory {
        val old = memoryDAO.getMemoryById(id) ?: error("Memory record #$id not found")
        val updated = old.copy(title = title, description = description, content = content)
        memoryDAO.updateMemory(updated)
        return updated.toModel()
    }

    suspend fun editMemoryByTitle(
        assistantId: String,
        title: String,
        newTitle: String?,
        description: String?,
        content: String?,
        oldText: String?,
        newText: String?,
        replaceAll: Boolean,
    ): AssistantMemory {
        val memory = memoryDAO.getMemoryByTitle(assistantId, title) ?: error("Memory not found: $title")
        var updatedTitle = memory.title
        var updatedDesc = memory.description
        var updatedContent = memory.content
        if (newTitle != null) {
            require(newTitle.isNotBlank()) { "new_title must not be blank" }
            val conflict = memoryDAO.getMemoryByTitle(assistantId, newTitle)
            require(conflict == null || conflict.id == memory.id) {
                "Another memory already uses title \"$newTitle\""
            }
            updatedTitle = newTitle
        }
        if (description != null) updatedDesc = description
        if (content != null) updatedContent = content
        if (oldText != null) {
            require(oldText.isNotEmpty()) { "old_text must not be empty" }
            val newTextValue = newText ?: error("new_text is required when old_text is provided")
            updatedContent = try {
                replaceText(updatedContent, oldText, newTextValue, replaceAll).updated
            } catch (e: IllegalArgumentException) {
                error(e.message ?: "replace failed")
            }
        }
        val updated = memory.copy(title = updatedTitle, description = updatedDesc, content = updatedContent)
        memoryDAO.updateMemory(updated)
        return updated.toModel()
    }

    suspend fun deleteMemory(id: Int) {
        memoryDAO.deleteMemory(id)
    }

    suspend fun deleteMemoryByTitle(assistantId: String, title: String): Boolean {
        val memory = memoryDAO.getMemoryByTitle(assistantId, title) ?: return false
        memoryDAO.deleteMemory(memory.id)
        return true
    }

    suspend fun deleteMemoriesOfAssistant(assistantId: String) {
        memoryDAO.deleteMemoriesOfAssistant(assistantId)
    }
}
