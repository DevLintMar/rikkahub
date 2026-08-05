# 记忆系统重写（Agora 式活跃记忆 + 已保存记忆 + 工作区工具修复）Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 把 rikkahub 记忆系统重写为 Agora 式（活跃记忆单槽 + 已保存记忆按标题/描述/内容组织 + 5 个 AI 工具 + 新注入格式），同时迁移两个开关 UI 位置、工作区工具改名、修复工具报错展示。

**Architecture:** 数据层（MemoryEntity + Room v26 迁移 + Repository 扩展）→ AI 层（MemoryTools 重写 + GenerationHandler 门槛注册 + buildMemoryPrompt 新格式）→ UI 层（记忆页重做 + 两个开关迁移 + 新工具渲染器）。工作区改名与报错展示为独立质量修复流。

**Tech Stack:** Kotlin、Jetpack Compose、Room、kotlinx.serialization、Koin、TextReplacers。

**Spec:** `docs/superpowers/specs/2026-08-05-memory-system-rewrite-design.md`

## Global Constraints

- **本机无编译器**：不运行 gradle。静态编写 + review，编译验证全靠 CI。
- **CI 判定铁律**：`gh run list --workflow nightly-build-debug.yml --repo DevLintMar/rikkahub --limit 1 --json databaseId,headSha,status,conclusion` 权威判定 + **核对 headSha**。CI 流程：先 `git push origin master` 再 `gh workflow run nightly-build-debug.yml --repo DevLintMar/rikkahub --ref master`。
- **CI 不跑单测**（只 `assembleDebug`）；单测验证不可靠。行为验证走设备核验清单。
- **reified 类型推断陷阱**：局部 val 里 `decodeFromString` 带 `?: listOf(具体类型)` 会被推断成具体类型，破坏 sealed 多态解码 → 显式多态类型。
- **runCatching 不能包 suspend 调用**；包 suspend 需 `try/catch` 且**重抛 `CancellationException`**。
- **字符串双写 en+zh**；ja/ko-rKR/ru/zh-rTW 中已存在对应记忆 key 的（各 8 个）一并补（用 `locale-tui-localization` skill）。删串先 grep 零引用。
- **图标名（HugeIcons）以 CI 编译为准**。
- **文件删除走 `~/.claude/scripts/trash.sh`**，禁止 rm。
- **force-push 需用户明确要求**。

---

### Task 1: 数据模型（AssistantMemory + Assistant 字段）

**Files:**
- Modify: `app/src/main/java/me/rerere/rikkahub/data/model/Assistant.kt`

**Interfaces:**
- Consumes: 无
- Produces:
  - `AssistantMemory(id: Int, title: String = "", description: String = "", content: String = "", isActive: Boolean = false)`
  - `Assistant.enableEditActiveMemory: Boolean = false`
  - `Assistant.enableEditSavedMemories: Boolean = false`

- [ ] **Step 1: 扩展 `AssistantMemory` 模型**

在 `Assistant.kt` 将 `data class AssistantMemory`（现 line 64-68）改为：

```kotlin
@Serializable
data class AssistantMemory(
    val id: Int,
    val title: String = "",
    val description: String = "",
    val content: String = "",
    val isActive: Boolean = false,   // 仅活跃记忆行为 true
)
```

- [ ] **Step 2: 给 `Assistant` 加 2 个字段**

在 `Assistant` 数据类中 `allowConversationPromptInjection`（现 line 54）之后追加：

```kotlin
    val enableEditActiveMemory: Boolean = false,   // "更改活跃记忆"：允许 AI 修改活跃记忆
    val enableEditSavedMemories: Boolean = false,  // "更改已保存的记忆"：允许 AI 新增/编辑/删除已保存记忆
```

- [ ] **Step 3: 编译验证 + 提交**

```bash
git add app/src/main/java/me/rerere/rikkahub/data/model/Assistant.kt
git commit -m "feat(memory): AssistantMemory 加 title/description/isActive，Assistant 加 2 个编辑开关"
git push origin master
gh workflow run nightly-build-debug.yml --repo DevLintMar/rikkahub --ref master
```
Expected: CI 绿（`gh run list ... --json databaseId,headSha,status,conclusion`，headSha 对应当前 HEAD）。

---

### Task 2: 存储层（Entity + Migration_25_26 + DAO + Repository）

**Files:**
- Modify: `app/src/main/java/me/rerere/rikkahub/data/db/entity/MemoryEntity.kt`
- Create: `app/src/main/java/me/rerere/rikkahub/data/db/migrations/Migration_25_26.kt`
- Modify: `app/src/main/java/me/rerere/rikkahub/data/db/AppDatabase.kt`
- Modify: `app/src/main/java/me/rerere/rikkahub/di/DataSourceModule.kt:58`
- Modify: `app/src/main/java/me/rerere/rikkahub/data/db/dao/MemoryDAO.kt`
- Modify: `app/src/main/java/me/rerere/rikkahub/data/repository/MemoryRepository.kt`

**Interfaces:**
- Consumes: `AssistantMemory`（Task 1 模型）
- Produces:
  - `MemoryEntity` 新列 `title: String`、`description: String`、`isActive: Boolean`
  - `MemoryDAO.getActiveMemory(assistantId): MemoryEntity?`、`MemoryDAO.getActiveMemoryFlow(assistantId): Flow<MemoryEntity?>`、`MemoryDAO.getMemoryByTitle(assistantId, title): MemoryEntity?`
  - `MemoryRepository.getActiveMemory(assistantId): AssistantMemory?`、`getActiveMemoryFlow(assistantId): Flow<AssistantMemory?>`
  - `MemoryRepository.updateActiveMemory(assistantId, content, mode=ActiveMemoryMode.REPLACE, oldString="", newString=""): AssistantMemory`
  - `MemoryRepository.getMemoryByTitle(assistantId, title): AssistantMemory?`
  - `MemoryRepository.addMemory(assistantId, title, description, content, overwrite): AssistantMemory`
  - `MemoryRepository.updateMemory(id, title, description, content): AssistantMemory`
  - `MemoryRepository.editMemoryByTitle(assistantId, title, newTitle, description, content, oldText, newText, replaceAll): AssistantMemory`
  - `MemoryRepository.deleteMemoryByTitle(assistantId, title): Boolean`
  - `enum class ActiveMemoryMode { REPLACE, APPEND, PREPEND, PATCH }`

- [ ] **Step 1: 扩展 `MemoryEntity`**

```kotlin
@Entity
data class MemoryEntity(
    @PrimaryKey(true)
    val id: Int = 0,
    @ColumnInfo("assistant_id")
    val assistantId: String,
    @ColumnInfo("content")
    val content: String = "",
    @ColumnInfo("title")
    val title: String = "",
    @ColumnInfo("description")
    val description: String = "",
    @ColumnInfo("is_active")
    val isActive: Boolean = false,
)
```

- [ ] **Step 2: 新建 `Migration_25_26.kt`**（仿 `Migration_11_12` 的 `object : Migration` 模式，无 tracker 调用——纯 ALTER 无需；用 beginTransaction）

```kotlin
package me.rerere.rikkahub.data.db.migrations

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

val Migration_25_26 = object : Migration(25, 26) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.beginTransaction()
        try {
            db.execSQL("ALTER TABLE memoryentity ADD COLUMN title TEXT NOT NULL DEFAULT ''")
            db.execSQL("ALTER TABLE memoryentity ADD COLUMN description TEXT NOT NULL DEFAULT ''")
            db.execSQL("ALTER TABLE memoryentity ADD COLUMN is_active INTEGER NOT NULL DEFAULT 0")
            // 旧记忆回填标题（内容前 40 字符，与运行时兜底推导规则一致）
            db.execSQL("UPDATE memoryentity SET title = substr(trim(content), 1, 40) WHERE title = ''")
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }
}
```

- [ ] **Step 3: 更新 `AppDatabase.kt`**

- `version = 25` → `version = 26`（line 44）
- **不加** `AutoMigration(from = 25, to = 26)`（手动迁移与 AutoMigration 互斥）

- [ ] **Step 4: 注册迁移到 `DataSourceModule.kt:58`**

```kotlin
.addMigrations(Migration_6_7, Migration_11_12, Migration_13_14, Migration_14_15, Migration_15_16, Migration_25_26)
```
并在文件 import 区加 `import me.rerere.rikkahub.data.db.migrations.Migration_25_26`。

- [ ] **Step 5: 扩展 `MemoryDAO.kt`**

修改两个 `getMemoriesOfAssistant*` 查询加 `AND is_active = 0`（已保存记忆不含活跃行），并新增：

```kotlin
@Query("SELECT * FROM memoryentity WHERE assistant_id = :assistantId AND is_active = 0")
fun getMemoriesOfAssistantFlow(assistantId: String): Flow<List<MemoryEntity>>

@Query("SELECT * FROM memoryentity WHERE assistant_id = :assistantId AND is_active = 0")
suspend fun getMemoriesOfAssistant(assistantId: String): List<MemoryEntity>

@Query("SELECT * FROM memoryentity WHERE assistant_id = :assistantId AND is_active = 1")
suspend fun getActiveMemory(assistantId: String): MemoryEntity?

@Query("SELECT * FROM memoryentity WHERE assistant_id = :assistantId AND is_active = 1")
fun getActiveMemoryFlow(assistantId: String): Flow<MemoryEntity?>

@Query("SELECT * FROM memoryentity WHERE assistant_id = :assistantId AND title = :title LIMIT 1")
suspend fun getMemoryByTitle(assistantId: String, title: String): MemoryEntity?
```

- [ ] **Step 6: 重写 `MemoryRepository.kt`**

```kotlin
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

    /** 兼容旧调用（迁移期临时，Task 5 后删除）：按 assistantId + content 写入空标题记录。不校验标题，直接插入（旧 memory_tool 无标题概念） */
    suspend fun addMemory(assistantId: String, content: String): AssistantMemory {
        val entity = MemoryEntity(assistantId = assistantId, content = content)
        return entity.copy(id = memoryDAO.insertMemory(entity).toInt()).toModel()
    }

    suspend fun updateMemory(id: Int, title: String, description: String, content: String): AssistantMemory {
        val old = memoryDAO.getMemoryById(id) ?: error("Memory record #$id not found")
        val updated = old.copy(title = title, description = description, content = content)
        memoryDAO.updateMemory(updated)
        return updated.toModel()
    }

    /** 兼容旧调用（迁移期临时，Task 5 后删除）：仅更新内容，保留标题/描述 */
    suspend fun updateContent(id: Int, content: String): AssistantMemory {
        val old = memoryDAO.getMemoryById(id) ?: error("Memory record #$id not found")
        val updated = old.copy(content = content)
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
```

> 注：保留两个**迁移期兼容方法**——旧签名 `addMemory(assistantId, content)` 与 `updateContent(id, content)`——使旧 `memory_tool` 接线（GenerationHandler）与 `AssistantDetailVM` 在本任务后仍能编译；Task 4 迁移 GenerationHandler、Task 5 迁移 VM 后删除。

- [ ] **Step 7: 编译验证 + 提交**

```bash
git add app/src/main/java/me/rerere/rikkahub/data/db
git add app/src/main/java/me/rerere/rikkahub/di/DataSourceModule.kt
git add app/src/main/java/me/rerere/rikkahub/data/repository/MemoryRepository.kt
git commit -m "feat(memory): MemoryEntity 加 title/description/is_active，Room v26 迁移，Repository 活跃记忆与按标题 CRUD"
git push origin master
gh workflow run nightly-build-debug.yml --repo DevLintMar/rikkahub --ref master
```
Expected: CI 绿（核对 headSha）。`replaceText` import：`me.rerere.rikkahub.data.ai.tools.replaceText` 是顶层函数，`data.ai.tools` 包可被 `data.repository` 引用（同 module，无依赖环问题）。

---

### Task 3: AI 工具（MemoryTools.kt 重写）

**Files:**
- Modify: `app/src/main/java/me/rerere/rikkahub/data/ai/tools/MemoryTools.kt`（整文件重写）

**Interfaces:**
- Consumes: `MemoryRepository` 方法（Task 2）、`AssistantMemory`、`ActiveMemoryMode`、`replaceText`
- Produces:
  - `fun buildMemoryTools(json: Json, readMemoryByTitle, updateActiveMemory, writeMemory, editMemory, deleteMemoryByTitle, includeActiveEdit: Boolean, includeSavedEdit: Boolean): List<Tool>`
  - 工具名：`read_memory`、`update_active_memory`、`write_memory`、`edit_memory`、`delete_memory`

- [ ] **Step 1: 整文件重写 `MemoryTools.kt`**

```kotlin
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
                            put("enum", buildJsonArrayOf("replace", "append", "prepend", "patch"))
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
```

> 注：`buildJsonArrayOf` 来自 `kotlinx.serialization.json`（顶层函数，无需 import 冲突）；`put("id", memory.id)` 中 Int 自动装箱为 JsonPrimitive。

- [ ] **Step 2: 编译验证 + 提交**

```bash
git add app/src/main/java/me/rerere/rikkahub/data/ai/tools/MemoryTools.kt
git commit -m "feat(memory): 重写记忆工具为 read/update_active/write/edit/delete 五工具"
git push origin master
gh workflow run nightly-build-debug.yml --repo DevLintMar/rikkahub --ref master
```
Expected: CI 绿。旧 `memory_tool` 定义删除；`MemoryToolUI`（UI 渲染器）暂保留供老消息展示。

---

### Task 4: 工具注册门槛 + 提示词注入 + ChatService 接线

**Files:**
- Modify: `app/src/main/java/me/rerere/rikkahub/data/ai/GenerationHandler.kt`
- Modify: `app/src/main/java/me/rerere/rikkahub/data/ai/GenerationPrompts.kt`
- Modify: `app/src/main/java/me/rerere/rikkahub/service/ChatService.kt:657-661`

**Interfaces:**
- Consumes: `buildMemoryTools`（Task 3）、`ActiveMemoryMode`（Task 2）、`AssistantMemory`
- Produces:
  - `GenerationHandler.generateText(..., activeMemory: AssistantMemory? = null)`
  - `GenerationHandler.generateInternal(..., activeMemory: AssistantMemory?)`
  - `GenerationPrompts.buildMemoryPrompt(activeMemory: AssistantMemory?, savedMemories: List<AssistantMemory>): String`

- [ ] **Step 1: 重写 `GenerationPrompts.buildMemoryPrompt`**

```kotlin
package me.rerere.rikkahub.data.ai

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import me.rerere.rikkahub.data.model.AssistantMemory
import me.rerere.rikkahub.utils.JsonInstantPretty

internal fun buildMemoryPrompt(
    activeMemory: AssistantMemory?,
    savedMemories: List<AssistantMemory>,
) = buildString {
    appendLine()
    append("**Memories**")
    appendLine()
    if (activeMemory != null && activeMemory.content.isNotBlank()) {
        appendLine("Active memory:")
        appendLine()
        append(activeMemory.content)
        appendLine()
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
    append("Only the titles and descriptions of saved memories are listed here; call read_memory with a title to fetch its full content.")
}
```

- [ ] **Step 2: `GenerationHandler.generateText` 加 `activeMemory` 参数**

在 `generateText` 签名（`memories: List<AssistantMemory>? = null` 之后，line 78 附近）加：

```kotlin
        activeMemory: AssistantMemory? = null,
```

并在 `generateInternal(...)` 调用（line 157 处 `memories = memories ?: emptyList()` 之后）加：

```kotlin
                    activeMemory = activeMemory,
```

- [ ] **Step 3: 替换 `generateText` 中记忆工具注册逻辑**

把现 line 97-115 的 `if (assistant?.enableMemory == true) { ... buildMemoryTools(...) }` 整体替换为：

```kotlin
                if (assistant?.enableMemory == true) {
                    val memoryAssistantId = if (assistant.useGlobalMemory) {
                        MemoryRepository.GLOBAL_MEMORY_ID
                    } else {
                        assistant.id.toString()
                    }
                    buildMemoryTools(
                        json = json,
                        readMemoryByTitle = { title ->
                            memoryRepo.getMemoryByTitle(memoryAssistantId, title)
                        },
                        updateActiveMemory = { content, mode, oldString, newString ->
                            memoryRepo.updateActiveMemory(
                                assistantId = memoryAssistantId,
                                content = content,
                                mode = ActiveMemoryMode.valueOf(mode.uppercase()),
                                oldString = oldString,
                                newString = newString,
                            )
                        },
                        writeMemory = { title, description, content, overwrite ->
                            memoryRepo.addMemory(memoryAssistantId, title, description, content, overwrite)
                        },
                        editMemory = { title, newTitle, description, content, oldText, newText, replaceAll ->
                            memoryRepo.editMemoryByTitle(
                                assistantId = memoryAssistantId,
                                title = title,
                                newTitle = newTitle,
                                description = description,
                                content = content,
                                oldText = oldText,
                                newText = newText,
                                replaceAll = replaceAll,
                            )
                        },
                        deleteMemoryByTitle = { title ->
                            memoryRepo.deleteMemoryByTitle(memoryAssistantId, title)
                        },
                        includeActiveEdit = assistant.enableEditActiveMemory,
                        includeSavedEdit = assistant.enableEditSavedMemories,
                    ).let(this::addAll)
                }
```

新增 import：`import me.rerere.rikkahub.data.ai.tools.buildMemoryTools`（若未显式，用全限定名或加 import）、`import me.rerere.rikkahub.data.repository.ActiveMemoryMode`、`import me.rerere.rikkahub.data.repository.MemoryRepository`。

- [ ] **Step 4: `generateInternal` 加 `activeMemory` 参数并替换注入调用**

在 `generateInternal` 签名（`memories: List<AssistantMemory>` 之后，line 387）加：

```kotlin
        activeMemory: AssistantMemory? = null,
```

把 line 408-411 的注入替换为：

```kotlin
                // 记忆
                if (assistant.enableMemory) {
                    appendLine()
                    append(buildMemoryPrompt(activeMemory = activeMemory, savedMemories = memories))
                }
```

- [ ] **Step 5: `ChatService.kt` 传 `activeMemory`**

在 `generateText(...)` 调用的 `memories = ...`（line 657-661）之前加：

```kotlin
                activeMemory = if (assistant.useGlobalMemory) {
                    memoryRepository.getActiveMemory(MemoryRepository.GLOBAL_MEMORY_ID)
                } else {
                    memoryRepository.getActiveMemory(assistant.id.toString())
                },
```

确认 `ChatService` 已 import `MemoryRepository`（若未，加 `import me.rerere.rikkahub.data.repository.MemoryRepository`）。

- [ ] **Step 6: 编译验证 + 提交**

```bash
git add app/src/main/java/me/rerere/rikkahub/data/ai/GenerationHandler.kt
git add app/src/main/java/me/rerere/rikkahub/data/ai/GenerationPrompts.kt
git add app/src/main/java/me/rerere/rikkahub/service/ChatService.kt
git commit -m "feat(memory): 记忆工具按开关门槛注册，注入活跃记忆全量+已保存记忆标题描述"
git push origin master
gh workflow run nightly-build-debug.yml --repo DevLintMar/rikkahub --ref master
```
Expected: CI 绿。

---

### Task 5: AssistantDetailVM（activeMemory flow + CRUD 适配）

**Files:**
- Modify: `app/src/main/java/me/rerere/rikkahub/ui/pages/assistant/detail/AssistantDetailVM.kt`

**Interfaces:**
- Consumes: `MemoryRepository`（Task 2）
- Produces: `vm.activeMemory: StateFlow<AssistantMemory?>`、`vm.updateActiveMemory(content: String)`

- [ ] **Step 1: 加 `activeMemory` flow**

在 `memories` flow（line 69-79）之后加：

```kotlin
    val activeMemory = assistant
        .flatMapLatest { currentAssistant ->
            if (currentAssistant.useGlobalMemory) {
                memoryRepository.getActiveMemoryFlow(MemoryRepository.GLOBAL_MEMORY_ID)
            } else {
                memoryRepository.getActiveMemoryFlow(assistantId.toString())
            }
        }
        .stateIn(
            scope = viewModelScope, started = SharingStarted.Eagerly, initialValue = null
        )
```

- [ ] **Step 2: 适配 addMemory / updateMemory，新增 updateActiveMemory**

替换 `addMemory`/`updateMemory`（line 181-199）并加 `updateActiveMemory`：

```kotlin
    fun addMemory(memory: AssistantMemory) {
        viewModelScope.launch {
            val memoryAssistantId = if (assistant.value.useGlobalMemory) {
                MemoryRepository.GLOBAL_MEMORY_ID
            } else {
                assistantId.toString()
            }
            memoryRepository.addMemory(
                assistantId = memoryAssistantId,
                title = memory.title,
                description = memory.description,
                content = memory.content,
                overwrite = false,
            )
        }
    }

    fun updateMemory(memory: AssistantMemory) {
        viewModelScope.launch {
            memoryRepository.updateMemory(
                id = memory.id,
                title = memory.title,
                description = memory.description,
                content = memory.content,
            )
        }
    }

    fun updateActiveMemory(content: String) {
        viewModelScope.launch {
            val scope = if (assistant.value.useGlobalMemory) {
                MemoryRepository.GLOBAL_MEMORY_ID
            } else {
                assistantId.toString()
            }
            memoryRepository.updateActiveMemory(assistantId = scope, content = content)
        }
    }
```

- [ ] **Step 3: 删除迁移期兼容方法**

此时 GenerationHandler 已改用新记忆方法（Task 4）、旧 `memory_tool` 已删除（Task 3）、`AssistantDetailVM` 已改用全字段方法（Step 2）。删除 `MemoryRepository` 中的两个兼容方法，先 grep 确认零引用：

```bash
grep -rn "updateContent\|addMemory(" app/src/main/java --include=*.kt | grep -v "MemoryRepository.kt"
```
预期：无 `memoryRepository.updateContent` / `memoryRepository.addMemory(`（旧签名）调用。然后从 `MemoryRepository.kt` 删除 `addMemory(assistantId: String, content: String)` 兼容重载与 `updateContent(id, content)`。

- [ ] **Step 4: 编译验证 + 提交**

```bash
git add app/src/main/java/me/rerere/rikkahub/ui/pages/assistant/detail/AssistantDetailVM.kt
git add app/src/main/java/me/rerere/rikkahub/data/repository/MemoryRepository.kt
git commit -m "feat(memory): AssistantDetailVM 加 activeMemory flow 与全字段 CRUD，删除迁移期兼容方法"
git push origin master
gh workflow run nightly-build-debug.yml --repo DevLintMar/rikkahub --ref master
```
Expected: CI 绿。

---

### Task 6: 记忆页 UI 重做（AssistantMemoryPage + 字符串）

**Files:**
- Modify: `app/src/main/java/me/rerere/rikkahub/ui/pages/assistant/detail/AssistantMemoryPage.kt`（整文件重写）
- Modify: `app/src/main/res/values/strings.xml`
- Modify: `app/src/main/res/values-zh/strings.xml`
- Modify: `app/src/main/res/values-ja/strings.xml`、`values-ko-rKR/strings.xml`、`values-ru/strings.xml`、`values-zh-rTW/strings.xml`（新增 key，用 locale-tui-localization skill）

**Interfaces:**
- Consumes: `vm.activeMemory`（Task 5）、`AssistantMemory` 新模型（Task 1）、现有 `assistant_page_*` 串
- Produces: 新的 `assistant_page_*` 串

- [ ] **Step 1: 新增字符串（en + zh 必写，其余 locale 补）**

`values/strings.xml` 新增：

```xml
  <string name="assistant_page_edit_active_memory">Edit Active Memory</string>
  <string name="assistant_page_edit_active_memory_desc">Allow the model to update the active memory</string>
  <string name="assistant_page_edit_saved_memories">Edit Saved Memories</string>
  <string name="assistant_page_edit_saved_memories_desc">Allow the model to create, edit, and delete saved memories</string>
  <string name="assistant_page_active_memory">Active Memory</string>
  <string name="assistant_page_active_memory_empty">No active memory set.</string>
  <string name="assistant_page_active_memory_edit">Edit Active Memory</string>
  <string name="assistant_page_saved_memories">Saved Memories</string>
  <string name="assistant_page_edit_memory">Edit Memory</string>
  <string name="assistant_page_memory_title_hint">Title</string>
  <string name="assistant_page_memory_description_hint">Description</string>
  <string name="assistant_page_memory_content_hint">Content</string>
```

`values-zh/strings.xml`：

```xml
  <string name="assistant_page_edit_active_memory">更改活跃记忆</string>
  <string name="assistant_page_edit_active_memory_desc">允许模型修改活跃记忆</string>
  <string name="assistant_page_edit_saved_memories">更改已保存的记忆</string>
  <string name="assistant_page_edit_saved_memories_desc">允许模型新增、编辑、删除已保存的记忆</string>
  <string name="assistant_page_active_memory">活跃记忆</string>
  <string name="assistant_page_active_memory_empty">尚未设置活跃记忆</string>
  <string name="assistant_page_active_memory_edit">编辑活跃记忆</string>
  <string name="assistant_page_saved_memories">已保存的记忆</string>
  <string name="assistant_page_edit_memory">编辑记忆</string>
  <string name="assistant_page_memory_title_hint">标题</string>
  <string name="assistant_page_memory_description_hint">描述</string>
  <string name="assistant_page_memory_content_hint">内容</string>
```

ja/ko-rKR/ru/zh-rTW：用 `locale-tui-localization` skill 补上述 12 个 key 的对应翻译。旧 key `assistant_page_manage_memory_title` 先保留（待 Step 4 确认零引用后删除）。

- [ ] **Step 2: 整文件重写 `AssistantMemoryPage.kt`**

```kotlin
package me.rerere.rikkahub.ui.pages.assistant.detail

import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.PencilEdit01
import me.rerere.hugeicons.stroke.Add01
import me.rerere.hugeicons.stroke.Delete01
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.fastForEach
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.model.AssistantMemory
import me.rerere.rikkahub.ui.components.nav.BackButton
import me.rerere.rikkahub.ui.components.ui.CardGroup
import me.rerere.rikkahub.ui.components.ui.RikkaConfirmDialog
import me.rerere.rikkahub.ui.hooks.EditStateContent
import me.rerere.rikkahub.ui.hooks.useEditState
import me.rerere.rikkahub.ui.theme.CustomColors
import org.koin.androidx.compose.koinViewModel
import org.koin.core.parameter.parametersOf

@Composable
fun AssistantMemoryPage(id: String) {
    val vm: AssistantDetailVM = koinViewModel(
        parameters = {
            parametersOf(id)
        }
    )
    val assistant by vm.assistant.collectAsStateWithLifecycle()
    val memories by vm.memories.collectAsStateWithLifecycle()
    val activeMemory by vm.activeMemory.collectAsStateWithLifecycle()
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()

    Scaffold(
        topBar = {
            LargeFlexibleTopAppBar(
                title = {
                    Text(stringResource(R.string.assistant_page_tab_memory))
                },
                navigationIcon = {
                    BackButton()
                },
                scrollBehavior = scrollBehavior,
                colors = CustomColors.topBarColors,
            )
        },
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        containerColor = CustomColors.topBarColors.containerColor,
    ) { innerPadding ->
        AssistantMemoryContent(
            innerPadding = innerPadding,
            assistant = assistant,
            memories = memories,
            activeMemory = activeMemory,
            onUpdateAssistant = { vm.update(it) },
            onDeleteMemory = { vm.deleteMemory(it) },
            onAddMemory = { vm.addMemory(it) },
            onUpdateMemory = { vm.updateMemory(it) },
            onUpdateActiveMemory = { vm.updateActiveMemory(it) }
        )
    }
}

@Composable
private fun AssistantMemoryContent(
    innerPadding: PaddingValues,
    assistant: Assistant,
    memories: List<AssistantMemory>,
    activeMemory: AssistantMemory?,
    onUpdateAssistant: (Assistant) -> Unit,
    onAddMemory: (AssistantMemory) -> Unit,
    onUpdateMemory: (AssistantMemory) -> Unit,
    onDeleteMemory: (AssistantMemory) -> Unit,
    onUpdateActiveMemory: (String) -> Unit,
) {
    val memoryDialogState = useEditState<AssistantMemory> {
        if (it.id == 0) {
            onAddMemory(it)
        } else {
            onUpdateMemory(it)
        }
    }
    var activeMemoryEditing by remember { mutableStateOf(false) }
    var activeMemoryDraft by remember { mutableStateOf("") }
    var pendingDeleteMemory by remember { mutableStateOf<AssistantMemory?>(null) }

    // 已保存记忆 添加/编辑对话框（标题 + 描述 + 内容）
    memoryDialogState.EditStateContent { memory, update ->
        AlertDialog(
            onDismissRequest = { memoryDialogState.dismiss() },
            title = {
                Text(stringResource(R.string.assistant_page_edit_memory))
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = memory.title,
                        onValueChange = { update(memory.copy(title = it)) },
                        label = { Text(stringResource(R.string.assistant_page_memory_title_hint)) },
                        singleLine = true,
                    )
                    OutlinedTextField(
                        value = memory.description,
                        onValueChange = { update(memory.copy(description = it)) },
                        label = { Text(stringResource(R.string.assistant_page_memory_description_hint)) },
                        singleLine = true,
                    )
                    OutlinedTextField(
                        value = memory.content,
                        onValueChange = { update(memory.copy(content = it)) },
                        label = { Text(stringResource(R.string.assistant_page_memory_content_hint)) },
                        minLines = 2,
                        maxLines = 8,
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = { memoryDialogState.confirm() }
                ) {
                    Text(stringResource(R.string.assistant_page_save))
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { memoryDialogState.dismiss() }
                ) {
                    Text(stringResource(R.string.assistant_page_cancel))
                }
            }
        )
    }

    // 活跃记忆编辑对话框（仅内容）
    if (activeMemoryEditing) {
        AlertDialog(
            onDismissRequest = { activeMemoryEditing = false },
            title = {
                Text(stringResource(R.string.assistant_page_active_memory_edit))
            },
            text = {
                OutlinedTextField(
                    value = activeMemoryDraft,
                    onValueChange = { activeMemoryDraft = it },
                    label = { Text(stringResource(R.string.assistant_page_active_memory)) },
                    minLines = 3,
                    maxLines = 10,
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        onUpdateActiveMemory(activeMemoryDraft)
                        activeMemoryEditing = false
                    }
                ) {
                    Text(stringResource(R.string.assistant_page_save))
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { activeMemoryEditing = false }
                ) {
                    Text(stringResource(R.string.assistant_page_cancel))
                }
            }
        )
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp)
            .padding(innerPadding)
            .imePadding(),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        CardGroup {
            item(
                headlineContent = { Text(stringResource(R.string.assistant_page_memory)) },
                supportingContent = {
                    Text(
                        text = stringResource(R.string.assistant_page_memory_desc),
                    )
                },
                trailingContent = {
                    Switch(
                        checked = assistant.enableMemory,
                        onCheckedChange = {
                            onUpdateAssistant(
                                assistant.copy(
                                    enableMemory = it
                                )
                            )
                        }
                    )
                }
            )
            item(
                headlineContent = { Text(stringResource(R.string.assistant_page_global_memory)) },
                supportingContent = {
                    Text(
                        text = stringResource(R.string.assistant_page_global_memory_desc),
                    )
                },
                trailingContent = {
                    Switch(
                        checked = assistant.useGlobalMemory,
                        onCheckedChange = {
                            onUpdateAssistant(
                                assistant.copy(
                                    useGlobalMemory = it
                                )
                            )
                        },
                        enabled = assistant.enableMemory
                    )
                }
            )
            item(
                headlineContent = { Text(stringResource(R.string.assistant_page_edit_active_memory)) },
                supportingContent = {
                    Text(
                        text = stringResource(R.string.assistant_page_edit_active_memory_desc),
                    )
                },
                trailingContent = {
                    Switch(
                        checked = assistant.enableEditActiveMemory,
                        onCheckedChange = {
                            onUpdateAssistant(
                                assistant.copy(
                                    enableEditActiveMemory = it
                                )
                            )
                        },
                        enabled = assistant.enableMemory
                    )
                }
            )
            item(
                headlineContent = { Text(stringResource(R.string.assistant_page_edit_saved_memories)) },
                supportingContent = {
                    Text(
                        text = stringResource(R.string.assistant_page_edit_saved_memories_desc),
                    )
                },
                trailingContent = {
                    Switch(
                        checked = assistant.enableEditSavedMemories,
                        onCheckedChange = {
                            onUpdateAssistant(
                                assistant.copy(
                                    enableEditSavedMemories = it
                                )
                            )
                        },
                        enabled = assistant.enableMemory
                    )
                }
            )
        }

        // 活跃记忆卡（始终显示）
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .clickable {
                    activeMemoryDraft = activeMemory?.content.orEmpty()
                    activeMemoryEditing = true
                },
            colors = CustomColors.cardColorsOnSurfaceContainer
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(R.string.assistant_page_active_memory),
                        style = MaterialTheme.typography.titleMediumEmphasized,
                    )
                    Text(
                        text = when {
                            activeMemory == null ->
                                stringResource(R.string.assistant_page_active_memory_empty)
                            activeMemory.content.length > 100 ->
                                activeMemory.content.take(100) + "..."
                            else -> activeMemory.content
                        },
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                IconButton(
                    onClick = {
                        activeMemoryDraft = activeMemory?.content.orEmpty()
                        activeMemoryEditing = true
                    }
                ) {
                    Icon(
                        HugeIcons.PencilEdit01,
                        stringResource(R.string.assistant_page_active_memory_edit)
                    )
                }
            }
        }

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp)
        ) {
            Text(
                text = stringResource(R.string.assistant_page_saved_memories),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier
                    .padding(bottom = 8.dp)
                    .align(Alignment.CenterStart)
            )

            IconButton(
                onClick = {
                    memoryDialogState.open(AssistantMemory(0, "", "", ""))
                },
                modifier = Modifier.align(Alignment.CenterEnd)
            ) {
                Icon(
                    imageVector = HugeIcons.Add01,
                    contentDescription = null
                )
            }
        }

        memories.fastForEach { memory ->
            key(memory.id) {
                MemoryItem(
                    memory = memory,
                    onEditMemory = {
                        memoryDialogState.open(it)
                    },
                    onDeleteMemory = {
                        pendingDeleteMemory = it
                    }
                )
            }
        }
    }

    RikkaConfirmDialog(
        show = pendingDeleteMemory != null,
        title = stringResource(R.string.confirm_delete),
        confirmText = stringResource(R.string.confirm),
        dismissText = stringResource(R.string.cancel),
        onConfirm = {
            pendingDeleteMemory?.let(onDeleteMemory)
            pendingDeleteMemory = null
        },
        onDismiss = { pendingDeleteMemory = null },
        text = {
            Text(
                text = pendingDeleteMemory?.content.orEmpty(),
                maxLines = 8,
                overflow = TextOverflow.Ellipsis
            )
        }
    )
}

@Composable
private fun MemoryItem(
    memory: AssistantMemory,
    onEditMemory: (AssistantMemory) -> Unit,
    onDeleteMemory: (AssistantMemory) -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CustomColors.cardColorsOnSurfaceContainer
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Text(
                    text = memory.title.ifBlank {
                        memory.content.trim().take(40)
                    },
                    style = MaterialTheme.typography.titleMediumEmphasized,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (memory.description.isNotBlank()) {
                    Text(
                        text = memory.description,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Text(
                    text = memory.content,
                    maxLines = 5,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            IconButton(
                onClick = { onEditMemory(memory) }
            ) {
                Icon(HugeIcons.PencilEdit01, null)
            }
            IconButton(
                onClick = { onDeleteMemory(memory) }
            ) {
                Icon(
                    HugeIcons.Delete01,
                    stringResource(R.string.assistant_page_delete)
                )
            }
        }
    }
}
```

> 注意：`clickable` import 已在代码块中补；本设计用直接铅笔/删除按钮（与现有 MemoryItem 一致），无需 DropdownMenu。

- [ ] **Step 3: 删除零引用旧串**

`assistant_page_manage_memory_title` 现 3 处引用（行 122/131/277）全部被替换后，grep 零引用则删除该 key（en/zh/ja/ko-rKR/ru/zh-rTW 各档）。确认 `chat_message_tool_create_memory`/`chat_message_tool_edit_memory`/`chat_message_tool_delete_memory` 仍被旧 MemoryToolUI 使用（保留）。

- [ ] **Step 4: 编译验证 + 提交**

```bash
git add app/src/main/java/me/rerere/rikkahub/ui/pages/assistant/detail/AssistantMemoryPage.kt
git add app/src/main/res/values*        # 各 locale strings.xml
git commit -m "feat(memory): 记忆页重做（活跃记忆卡+已保存记忆三字段+2开关+新串）"
git push origin master
gh workflow run nightly-build-debug.yml --repo DevLintMar/rikkahub --ref master
```
Expected: CI 绿。若 `clickable`/空安全编译报错，按报错微调后重跑。

---

### Task 7: 开关 UI 迁移（本地工具页 + 提示词页）

**Files:**
- Modify: `app/src/main/java/me/rerere/rikkahub/ui/pages/assistant/detail/AssistantLocalToolPage.kt`
- Modify: `app/src/main/java/me/rerere/rikkahub/ui/pages/assistant/detail/AssistantPromptPage.kt`

**Interfaces:**
- Consumes: `Assistant.enableRecentChatsReference` / `enableTimeReminder` / `timeReminderAlwaysInsert`（既有字段）
- Produces: 无（纯 UI 行迁移；复用既有 `assistant_page_recent_chats*`、`assistant_page_time_reminder*` 串，**无需新增串**）

- [ ] **Step 1: 本地工具页插入"参考历史聊天记录"行**

在 `AssistantLocalToolPage.kt` 的 CardGroup 中，`AskUser` item（现 line 187-200）之后、`ScreenTime` item（现 line 201-214）之前，插入：

```kotlin
            item(
                headlineContent = { Text(stringResource(R.string.assistant_page_recent_chats)) },
                supportingContent = {
                    Text(
                        text = stringResource(R.string.assistant_page_recent_chats_desc),
                    )
                },
                trailingContent = {
                    Switch(
                        checked = assistant.enableRecentChatsReference,
                        onCheckedChange = {
                            onUpdate(
                                assistant.copy(
                                    enableRecentChatsReference = it
                                )
                            )
                        }
                    )
                }
            )
```

- [ ] **Step 2: 提示词页插入时间提醒 CardGroup**

在 `AssistantPromptPage.kt` 的 `allowConversationPromptInjection` Card（现 line 231-255）之后、消息模板 Card（现 line 257）之前，插入：

```kotlin
        Card(
            colors = CustomColors.cardColorsOnSurfaceContainer
        ) {
            Column {
                FormItem(
                    modifier = Modifier.padding(8.dp),
                    label = {
                        Text(stringResource(R.string.assistant_page_time_reminder))
                    },
                    description = {
                        Text(stringResource(R.string.assistant_page_time_reminder_desc))
                    },
                    tail = {
                        Switch(
                            checked = assistant.enableTimeReminder,
                            onCheckedChange = {
                                onUpdate(
                                    assistant.copy(
                                        enableTimeReminder = it
                                    )
                                )
                            }
                        )
                    }
                )
                FormItem(
                    modifier = Modifier.padding(8.dp),
                    label = {
                        Text(stringResource(R.string.assistant_page_time_reminder_always))
                    },
                    description = {
                        Text(stringResource(R.string.assistant_page_time_reminder_always_desc))
                    },
                    tail = {
                        Switch(
                            checked = assistant.timeReminderAlwaysInsert,
                            onCheckedChange = {
                                onUpdate(
                                    assistant.copy(
                                        timeReminderAlwaysInsert = it
                                    )
                                )
                            }
                        )
                    }
                )
            }
        }
```

确认 `AssistantPromptPage` 已 import `Card`/`Column`/`FormItem`/`Switch`（现文件均已 import）。

- [ ] **Step 3: 编译验证 + 提交**

```bash
git add app/src/main/java/me/rerere/rikkahub/ui/pages/assistant/detail/AssistantLocalToolPage.kt
git add app/src/main/java/me/rerere/rikkahub/ui/pages/assistant/detail/AssistantPromptPage.kt
git commit -m "feat(settings): 参考历史聊天记录移入本地工具页，时间提醒移入提示词页"
git push origin master
gh workflow run nightly-build-debug.yml --repo DevLintMar/rikkahub --ref master
```
Expected: CI 绿。

---

### Task 8: 记忆工具调用展示（MemoryToolsUIs.kt + 注册 + 串）

**Files:**
- Create: `app/src/main/java/me/rerere/rikkahub/ui/components/message/tools/MemoryToolsUIs.kt`
- Modify: `app/src/main/java/me/rerere/rikkahub/ui/components/message/tools/ToolUI.kt:88-109`（注册表）
- Modify: `app/src/main/res/values*/strings.xml`（新工具串）

**Interfaces:**
- Consumes: `ToolUIRenderer`/`ToolUIContext`（ToolUI.kt）、`ToolUIRegistry`、`UIMessagePart.Tool`
- Produces: `UpdateActiveMemoryToolUI`/`ReadMemoryToolUI`/`WriteMemoryToolUI`/`EditMemoryToolUI`/`DeleteMemoryToolUI` 五渲染器

- [ ] **Step 1: 新增工具串**

`values/strings.xml`：

```xml
  <string name="chat_message_tool_read_memory">Read memory</string>
  <string name="chat_message_tool_write_memory">Write memory</string>
  <string name="chat_message_tool_edit_memory">Edit memory</string>
  <string name="chat_message_tool_delete_memory">Delete memory</string>
  <string name="chat_message_tool_update_active_memory">Update active memory</string>
```

`values-zh/strings.xml`：

```xml
  <string name="chat_message_tool_read_memory">读取记忆</string>
  <string name="chat_message_tool_write_memory">写入记忆</string>
  <string name="chat_message_tool_edit_memory">编辑记忆</string>
  <string name="chat_message_tool_delete_memory">删除记忆</string>
  <string name="chat_message_tool_update_active_memory">更新活跃记忆</string>
```

ja/ko-rKR/ru/zh-rTW 用 locale-tui-localization skill 补。

- [ ] **Step 2: 新建 `MemoryToolsUIs.kt`**

```kotlin
package me.rerere.rikkahub.ui.components.message.tools

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Eraser
import me.rerere.hugeicons.stroke.FolderOpen
import me.rerere.hugeicons.stroke.PencilEdit01
import me.rerere.hugeicons.stroke.QuillWrite01
import me.rerere.hugeicons.stroke.Tools
import me.rerere.rikkahub.R
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject

/** 读记忆 */
object ReadMemoryToolUI : ToolUIRenderer {
    override val toolName: String = "read_memory"

    override fun icon(context: ToolUIContext): ImageVector = HugeIcons.FolderOpen

    @Composable
    override fun title(context: ToolUIContext): String =
        stringResource(R.string.chat_message_tool_read_memory)

    override fun hasSummary(context: ToolUIContext): Boolean =
        context.content.getStringContent("content") != null

    @Composable
    override fun Summary(context: ToolUIContext) {
        context.content.getStringContent("content")?.let { content ->
            Text(
                text = content,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** 更新活跃记忆 */
object UpdateActiveMemoryToolUI : ToolUIRenderer {
    override val toolName: String = "update_active_memory"

    override fun icon(context: ToolUIContext): ImageVector = HugeIcons.PencilEdit01

    @Composable
    override fun title(context: ToolUIContext): String =
        stringResource(R.string.chat_message_tool_update_active_memory)

    override fun hasSummary(context: ToolUIContext): Boolean =
        context.content.getStringContent("content") != null

    @Composable
    override fun Summary(context: ToolUIContext) {
        context.content.getStringContent("content")?.let { content ->
            Text(
                text = content,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** 写入记忆 */
object WriteMemoryToolUI : ToolUIRenderer {
    override val toolName: String = "write_memory"

    override fun icon(context: ToolUIContext): ImageVector = HugeIcons.QuillWrite01

    @Composable
    override fun title(context: ToolUIContext): String =
        stringResource(R.string.chat_message_tool_write_memory)

    override fun hasSummary(context: ToolUIContext): Boolean =
        context.content.getStringContent("content") != null

    @Composable
    override fun Summary(context: ToolUIContext) {
        context.content.getStringContent("content")?.let { content ->
            Text(
                text = content,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** 编辑记忆 */
object EditMemoryToolUI : ToolUIRenderer {
    override val toolName: String = "edit_memory"

    override fun icon(context: ToolUIContext): ImageVector = HugeIcons.QuillWrite01

    @Composable
    override fun title(context: ToolUIContext): String =
        stringResource(R.string.chat_message_tool_edit_memory)

    override fun hasSummary(context: ToolUIContext): Boolean =
        context.content.getStringContent("content") != null

    @Composable
    override fun Summary(context: ToolUIContext) {
        context.content.getStringContent("content")?.let { content ->
            Text(
                text = content,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** 删除记忆 */
object DeleteMemoryToolUI : ToolUIRenderer {
    override val toolName: String = "delete_memory"

    override fun icon(context: ToolUIContext): ImageVector = HugeIcons.Eraser

    @Composable
    override fun title(context: ToolUIContext): String =
        stringResource(R.string.chat_message_tool_delete_memory)

    override fun hasSummary(context: ToolUIContext): Boolean = false

    @Composable
    override fun Summary(context: ToolUIContext) {
    }
}
```

- [ ] **Step 3: 注册到 `ToolUIRegistry`**

`ToolUI.kt` line 88-109 的 `renderers` 列表头部加：

```kotlin
    private val renderers: Map<String, ToolUIRenderer> = listOf(
        ReadMemoryToolUI,
        UpdateActiveMemoryToolUI,
        WriteMemoryToolUI,
        EditMemoryToolUI,
        DeleteMemoryToolUI,
        MemoryToolUI,
        ...
```
（在 `MemoryToolUI` 之前插入五个新渲染器；`MemoryToolUI` 保留用于旧 `memory_tool` 历史消息。）

- [ ] **Step 4: 编译验证 + 提交**

```bash
git add app/src/main/java/me/rerere/rikkahub/ui/components/message/tools/MemoryToolsUIs.kt
git add app/src/main/java/me/rerere/rikkahub/ui/components/message/tools/ToolUI.kt
git add app/src/main/res/values*
git commit -m "feat(memory): 记忆五工具调用展示渲染器（read/write/edit/delete/update_active）"
git push origin master
gh workflow run nightly-build-debug.yml --repo DevLintMar/rikkahub --ref master
```
Expected: CI 绿。图标名以 CI 编译为准（若 `FolderOpen`/`QuillWrite01` 不存在，改用 `HugeIcons.Tools` 兜底）。

---

### Task 9: 工作区工具改名（去 `_file` 后缀）

**Files:**
- Modify: `app/src/main/java/me/rerere/rikkahub/data/ai/tools/WorkspaceTools.kt`
- Modify: `app/src/main/java/me/rerere/rikkahub/ui/components/ai/ToolSelector.kt:27-29`
- Modify: `app/src/main/java/me/rerere/rikkahub/data/ai/agents/AgentManager.kt:25-26`
- Modify: `app/src/main/java/me/rerere/rikkahub/data/ai/transformers/WorkspaceReminderTransformer.kt:48-51`
- Modify: `app/src/main/java/me/rerere/rikkahub/ui/components/message/ChatMessageEditedFiles.kt:54`
- Modify: `app/src/main/java/me/rerere/rikkahub/ui/pages/extensions/workspace/WorkspaceDetailPage.kt:464-466`
- Modify: `app/src/main/java/me/rerere/rikkahub/ui/components/message/tools/WorkspaceToolUIs.kt:59,160,204`
- Modify: `app/src/main/java/me/rerere/rikkahub/data/db/entity/WorkspaceEntity.kt`

**Interfaces:**
- Consumes: 无
- Produces: 工具名 `workspace_read` / `workspace_write` / `workspace_edit`（`workspace_shell`/`workspace_glob`/`workspace_grep` 不变）；`WorkspaceEntity.toolApprovalOverrides()` 返回新 key

- [ ] **Step 1: 全仓字符串替换（8 处文件）**

对 `workspace_read_file`→`workspace_read`、`workspace_write_file`→`workspace_write`、`workspace_edit_file`→`workspace_edit` 做精确替换（不要动 `workspace_shell`/`workspace_glob`/`workspace_grep`，不要动 `docs/` 下的历史文档）：

| 文件 | 位置 | 内容 |
|---|---|---|
| `WorkspaceTools.kt` | name 78/144/192 | `name = "workspace_read"` 等 |
| `WorkspaceTools.kt` | approval map 34-36 | map key 改新名 |
| `WorkspaceTools.kt` | needsApproval 101/165/219 | 字符串改新名 |
| `WorkspaceTools.kt` | 信封 type 125/175/240/509 | `put("type", "workspace_read")` 等 |
| `ToolSelector.kt` | 27-29 | `ALL_BASE_TOOLS` 改新名 |
| `AgentManager.kt` | 25-26 | `disallowedTools` 改新名 |
| `WorkspaceReminderTransformer.kt` | 48-51 | 提示词散文改新名（模型可见） |
| `ChatMessageEditedFiles.kt` | 54 | `WORKSPACE_FILE_TOOL_NAMES` 改新名 |
| `WorkspaceDetailPage.kt` | 464-466 | `workspaceToolApprovalItems()` map key 改新名 |
| `WorkspaceToolUIs.kt` | 59/160/204 | `toolName` 改新名（注册表 key 自动跟随） |

> `TextReplacers.kt:4` 与 `ai/.../MessageMetadata.kt:51` 仅注释提及，不必改（但可顺手改保持一致）。

- [ ] **Step 2: `WorkspaceEntity.toolApprovalOverrides()` 旧→新 key 重映射**

`WorkspaceEntity.kt` 加：

```kotlin
companion object {
    private val TOOL_NAME_LEGACY_MAP = mapOf(
        "workspace_read_file" to "workspace_read",
        "workspace_write_file" to "workspace_write",
        "workspace_edit_file" to "workspace_edit",
    )
}

fun toolApprovalOverrides(): Map<String, Boolean> = runCatching {
    JsonInstant.decodeFromString<Map<String, Boolean>>(toolApprovals)
        .mapKeys { (key, _) -> TOOL_NAME_LEGACY_MAP[key] ?: key }
}.getOrDefault(emptyMap())
```

- [ ] **Step 3: 编译验证 + 提交**

```bash
git add app/src/main/java/me/rerere/rikkahub/data/ai/tools/WorkspaceTools.kt
git add app/src/main/java/me/rerere/rikkahub/ui/components/ai/ToolSelector.kt
git add app/src/main/java/me/rerere/rikkahub/data/ai/agents/AgentManager.kt
git add app/src/main/java/me/rerere/rikkahub/data/ai/transformers/WorkspaceReminderTransformer.kt
git add app/src/main/java/me/rerere/rikkahub/ui/components/message/ChatMessageEditedFiles.kt
git add app/src/main/java/me/rerere/rikkahub/ui/pages/extensions/workspace/WorkspaceDetailPage.kt
git add app/src/main/java/me/rerere/rikkahub/ui/components/message/tools/WorkspaceToolUIs.kt
git add app/src/main/java/me/rerere/rikkahub/data/db/entity/WorkspaceEntity.kt
git commit -m "refactor(workspace): 工作区 read/write/edit 工具去 _file 后缀，审批覆盖旧 key 重映射"
git push origin master
gh workflow run nightly-build-debug.yml --repo DevLintMar/rikkahub --ref master
```
Expected: CI 绿。

---

### Task 10: 工具报错展示修复

**Files:**
- Modify: `app/src/main/java/me/rerere/rikkahub/ui/components/message/ChatMessageTools.kt`
- Modify: `app/src/main/java/me/rerere/rikkahub/ui/components/message/tools/WorkspaceToolUIs.kt`
- Modify: `app/src/main/java/me/rerere/rikkahub/ui/components/message/tools/ToolUI.kt`
- Modify: `app/src/main/res/values*/strings.xml`

**Interfaces:**
- Consumes: `ToolState.FAILED`、错误信封 `{type, error, message}`（GenerationHandler.kt:321-344）
- Produces: `chat_message_tool_failed` 串

- [ ] **Step 1: 新增通用失败串**

`values/strings.xml`：

```xml
  <string name="chat_message_tool_failed">Tool failed: %1$s</string>
```

`values-zh/strings.xml`：

```xml
  <string name="chat_message_tool_failed">工具执行失败：%1$s</string>
```

ja/ko-rKR/ru/zh-rTW 用 locale-tui-localization skill 补。

- [ ] **Step 2: `ChatMessageToolStep` 加 FAILED 分支**

在 `ChatMessageTools.kt`（line 98-99 附近）加：

```kotlin
    val isFailed = tool.toolState == ToolState.FAILED
```

把 `extra` 槽（现 line 129-168）的 `else if (isDenied) {...}` 之后、`else { null }` 之前插入：

```kotlin
        } else if (isFailed) {
            {
                val message = context.content.getStringContent("message")
                    ?: stringResource(R.string.chat_message_tool_failed, tool.toolName)
                Text(
                    text = message,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        } else {
            null
        },
```

（`getStringContent` 已在 ToolUI.kt 定义，同包可见；`TextOverflow` 需确认已 import，否则加。）

- [ ] **Step 3: `WorkspaceToolUIs.kt` 各渲染器错误摘要**

对 `ReadFileToolUI`/`WriteFileToolUI`/`EditFileToolUI` 的 `hasSummary` 加错误识别，`Summary` 加 error 色消息（对齐 BuiltinToolUIs.kt 中 `GetScreenTimeToolUI.Summary` 的错误模式，约 line 742-749）：

对每个渲染器在 `hasSummary` 中加：

```kotlin
    override fun hasSummary(context: ToolUIContext): Boolean =
        context.content.getStringContent("error") != null || <原条件>
```

并在 `Summary` 开头加：

```kotlin
    @Composable
    override fun Summary(context: ToolUIContext) {
        context.content.getStringContent("message")?.let { message ->
            Text(
                text = message,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.error,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
            return
        }
        <原 Summary 逻辑>
    }
```

具体到三个渲染器：
- **ReadFileToolUI**（line 159-198）：`hasSummary` 原为 `context.content.getStringContent("text") != null` → 改为 `getStringContent("error") != null || getStringContent("text") != null`；Summary 先渲染 error message 再渲染 text。
- **WriteFileToolUI**（line 203-241）：`hasSummary` 原读 arguments 的 `text` → 先检查 error；error 时不再显示待写内容。
- **EditFileToolUI**（line 58-154）：`hasSummary` 原 `diffOf() != null` → 改为 error 优先；error 时显示 message。

- [ ] **Step 4: `ShellToolUI` 错误信封处理**

`ShellToolUI`（WorkspaceToolUIs.kt:296-404）：`Summary` 里当 `context.content.getStringContent("error") != null`（无 exitCode 的异常信封）时，先渲染 `message`（error 色），不再走 `ShellExitStatus` 的 "exit code ?" 分支。

- [ ] **Step 5: `DefaultToolPreview` 错误优先**

`ToolUI.kt` `DefaultToolPreview`（line 138-168）的结果 JSON 渲染前加：

```kotlin
        if (context.tool.output.isNotEmpty()) {
            val textParts = context.tool.output.filterIsInstance<UIMessagePart.Text>()
            val joinedText = remember(textParts) { textParts.joinToString("\n") { it.text } }
            val resultJson = remember(joinedText) {
                runCatching { JsonInstant.parseToJsonElement(joinedText) }.getOrNull()
            }
            val errorMessage = resultJson?.jsonObjectOrNull?.get("message")
                ?.jsonPrimitiveOrNull?.contentOrNull
            if (errorMessage != null) {
                Text(
                    text = errorMessage,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            ToolJsonSection(
                label = stringResource(R.string.chat_message_tool_call_result),
                json = resultJson,
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (resultJson != null) {
                        JsonTreeView(resultJson)
                    } else if (joinedText.isNotBlank()) {
                        HighlightCodeBlock(...)
                    }
                    ...
                }
            }
        }
```

（`jsonObjectOrNull`/`jsonPrimitiveOrNull` 已在 ToolUI.kt 顶层有内部扩展，直接复用；错误消息显示在 JSON 树上方，仍保留 JSON 树供排查。）

- [ ] **Step 6: 编译验证 + 提交**

```bash
git add app/src/main/java/me/rerere/rikkahub/ui/components/message/ChatMessageTools.kt
git add app/src/main/java/me/rerere/rikkahub/ui/components/message/tools/WorkspaceToolUIs.kt
git add app/src/main/java/me/rerere/rikkahub/ui/components/message/tools/ToolUI.kt
git add app/src/main/res/values*
git commit -m "fix(chat): 工具失败折叠行显示错误消息（FAILED 状态接入 UI）+ DefaultToolPreview 错误优先"
git push origin master
gh workflow run nightly-build-debug.yml --repo DevLintMar/rikkahub --ref master
```
Expected: CI 绿。

---

## 设备核验清单（全部任务完成后）

用户装最新 debug APK 验证：

1. **记忆页**：活跃记忆卡始终显示、可编辑；已保存记忆以标题/描述/内容展示、增删改正常；两个新开关（更改活跃记忆/更改已保存的记忆）随 enableMemory 使能；旧记忆迁移出标题。
2. **开关迁移**：本地工具页"询问用户"与"屏幕时间"之间有"参考历史聊天记录"；提示词页"独立对话提示词注入"下方有"时间提醒"+"总是插入时间提醒"，行为不变。
3. **AI 记忆工具**：对话中让 AI "记住我的偏好"→ write_memory；"我上次说的 X 是什么"→ read_memory；覆盖已有标题时若未显式 overwrite 应报错并在折叠行显示错误；活跃记忆 update_active_memory 生效。系统消息含活跃记忆全量 + 已保存标题/描述。
4. **工作区工具**：改名后 `workspace_read`/`workspace_write`/`workspace_edit` 正常；报错（文件不存在等）折叠行直接显示错误消息；已保存的审批覆盖仍生效（读旧 key 重映射）。
5. **夜间模式**、中英文串正确（ja/ko-rKR/ru/zh-rTW 有对应翻译）。
