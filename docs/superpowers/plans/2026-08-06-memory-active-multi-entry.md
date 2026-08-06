# 记忆系统迭代（活跃记忆多条化 + 工具展示增强）Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 活跃记忆从单槽改为多条（标题/描述/内容，AI 只收 id+内容），活跃记忆工具改为 create/edit/delete 三件套，工具图标更新，删除概览显示标题，五个记忆工具加详情页按钮（删除/回退/恢复），修复注入空行。

**Architecture:** 数据层（DAO/Repository 活跃记忆多条化 + isActive 参数化）→ AI 层（工具替换 + 信封扩展 + 注入格式）→ UI 层（记忆页活跃记忆区 + 渲染器/详情按钮/图标/串）。

**Tech Stack:** Kotlin、Jetpack Compose、Room、kotlinx.serialization、Koin。

**Spec:** 用户 5 点需求（本 plan 即设计）

## Global Constraints

- **本机无编译器**：不运行 gradle。静态编写 + review，编译验证全靠 CI。
- **CI 判定铁律**：`gh run list --workflow nightly-build-debug.yml --repo DevLintMar/rikkahub --limit 1 --json databaseId,headSha,status,conclusion` + **核对 headSha**。CI 流程：先 push 再 `gh workflow run nightly-build-debug.yml --repo DevLintMar/rikkahub --ref master`。
- **CI 不跑单测**（只 assembleDebug）。
- **runCatching 不能包 suspend**；包 suspend 用 try/catch + 重抛 `CancellationException`。
- **字符串双写 en+zh**；ja/ko-rKR/ru/zh-rTW 有对应 key 时一并补。
- **图标名以 CI 编译为准**：`Lucide.FolderSearch`/`Lucide.BookCheck` 来自 `com.composables.icons.lucide`（项目已依赖 icons-lucide 1.1.0）；若 CI 报不存在则回退 HugeIcons 等价物（FolderSearch→HugeIcons.Search01 保持/BookCheck→HugeIcons.Tools）。
- 文件删除走 `~/.claude/scripts/trash.sh`。

---

### Task 1: 数据层 — 活跃记忆多条化

**Files:**
- Modify: `app/src/main/java/me/rerere/rikkahub/data/db/dao/MemoryDAO.kt`
- Modify: `app/src/main/java/me/rerere/rikkahub/data/repository/MemoryRepository.kt`

**Interfaces:**
- Consumes: 现有 MemoryEntity/AssistantMemory
- Produces:
  - `MemoryDAO.getActiveMemories(assistantId): List<MemoryEntity>`、`getActiveMemoriesFlow(assistantId): Flow<List<MemoryEntity>>`（is_active=1）
  - `MemoryDAO.getActiveMemoryByTitle(assistantId, title): MemoryEntity?`（is_active=1）
  - `MemoryRepository.getActiveMemories(assistantId): List<AssistantMemory>` / `getActiveMemoriesFlow(...): Flow<List<AssistantMemory>>`
  - `MemoryRepository.getActiveMemoryByTitle(assistantId, title): AssistantMemory?`（供 Task 2 工具接线）
  - `MemoryRepository.addMemory(assistantId, title, description, content, overwrite, isActive=false)` — 唯一性检查按 isActive 路由到对应表
  - `MemoryRepository.editMemoryByTitle(assistantId, title, newTitle, description, content, oldText, newText, replaceAll, isActive=false)`
  - `MemoryRepository.deleteMemoryByTitle(assistantId, title, isActive=false): Boolean`
  - **删除**：`getActiveMemory`（单条）、`getActiveMemoryFlow`（单条 Flow）、`updateActiveMemory`、`ActiveMemoryMode` 枚举（update_active_memory 工具已不存在）

- [ ] **Step 1: MemoryDAO 改动**

把单条活跃查询改为列表，并加按标题查询：

```kotlin
@Query("SELECT * FROM memoryentity WHERE assistant_id = :assistantId AND is_active = 1")
suspend fun getActiveMemories(assistantId: String): List<MemoryEntity>

@Query("SELECT * FROM memoryentity WHERE assistant_id = :assistantId AND is_active = 1")
fun getActiveMemoriesFlow(assistantId: String): Flow<List<MemoryEntity>>

@Query("SELECT * FROM memoryentity WHERE assistant_id = :assistantId AND is_active = 1 AND title = :title LIMIT 1")
suspend fun getActiveMemoryByTitle(assistantId: String, title: String): MemoryEntity?
```

删除旧的 `getActiveMemory` / `getActiveMemoryFlow`（单条版本）。

- [ ] **Step 2: MemoryRepository 改动**

1. 删除 `ActiveMemoryMode` 枚举、`getActiveMemory`、`getActiveMemoryFlow`、`updateActiveMemory`（整个方法）。
2. 新增：

```kotlin
fun getActiveMemoriesFlow(assistantId: String): Flow<List<AssistantMemory>> =
    memoryDAO.getActiveMemoriesFlow(assistantId)
        .map { entities -> entities.map { it.toModel() } }

suspend fun getActiveMemories(assistantId: String): List<AssistantMemory> =
    memoryDAO.getActiveMemories(assistantId).map { it.toModel() }

suspend fun getActiveMemoryByTitle(assistantId: String, title: String): AssistantMemory? =
    memoryDAO.getActiveMemoryByTitle(assistantId, title)?.toModel()
```

3. `addMemory` 加 `isActive: Boolean = false` 参数，唯一性检查路由：

```kotlin
suspend fun addMemory(
    assistantId: String,
    title: String,
    description: String,
    content: String,
    overwrite: Boolean,
    isActive: Boolean = false,
): AssistantMemory {
    require(title.isNotBlank()) { "title is required" }
    val existing = if (isActive) {
        memoryDAO.getActiveMemoryByTitle(assistantId, title)
    } else {
        memoryDAO.getMemoryByTitle(assistantId, title)
    }
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
        isActive = isActive,
    )
    return entity.copy(id = memoryDAO.insertMemory(entity).toInt()).toModel()
}
```

4. `editMemoryByTitle` 加 `isActive: Boolean = false`：内部 `memoryDAO.getMemoryByTitle` → 按 isActive 路由到 `getActiveMemoryByTitle`；改名冲突检查同样路由。
5. `deleteMemoryByTitle` 加 `isActive: Boolean = false`：按 isActive 路由查询后删除。
6. `getMemoryByTitle`（saved）保持不变。

- [ ] **Step 3: 本地提交（不推送、不触发 CI）**

```bash
git add app/src/main/java/me/rerere/rikkahub/data/db/dao/MemoryDAO.kt
git add app/src/main/java/me/rerere/rikkahub/data/repository/MemoryRepository.kt
git commit -m "feat(memory): 活跃记忆多条化（DAO 列表查询 + Repository isActive 参数化）"
```
**不推送、不触发 CI**——此时 GenerationHandler/GenerationPrompts/ChatService/VM 仍引用旧 API，推送必然编译失败。Task 1/2/3 作为整体在 Task 3 结束时统一推送验证。

---

### Task 2: AI 层 — 工具替换 + 信封扩展 + 注入格式

**Files:**
- Modify: `app/src/main/java/me/rerere/rikkahub/data/ai/tools/MemoryTools.kt`（整文件重写）
- Modify: `app/src/main/java/me/rerere/rikkahub/data/ai/GenerationHandler.kt`
- Modify: `app/src/main/java/me/rerere/rikkahub/data/ai/GenerationPrompts.kt`
- Modify: `app/src/main/java/me/rerere/rikkahub/service/ChatService.kt`

**Interfaces:**
- Consumes: Task 1 Repository API
- Produces:
  - 工具：`read_memory`、`write_memory`、`edit_memory`、`delete_memory`（saved，不变）+ `create_active_memory`、`edit_active_memory`、`delete_active_memory`（新增；`update_active_memory` 删除）
  - 信封扩展：`edit_memory`/`edit_active_memory` 加 `previous_content`；`delete_memory`/`delete_active_memory` 加 `content`/`description`/`scope_id`；`create_active_memory`/`write_memory`/`edit_*` 加 `scope_id`（UI 恢复/删除本条用）
  - `GenerationPrompts.buildMemoryPrompt(activeMemories: List<AssistantMemory>, savedMemories: List<AssistantMemory>)`
  - `GenerationHandler.generateText/generateInternal` 参数 `activeMemories: List<AssistantMemory> = emptyList()`
  - ChatService 传 `activeMemories = memoryRepository.getActiveMemories(scope)`

- [ ] **Step 1: 重写 MemoryTools.kt**

签名改为：

```kotlin
fun buildMemoryTools(
    json: Json,
    memoryAssistantId: String,
    readMemoryByTitle: suspend (title: String) -> AssistantMemory?,
    readActiveMemoryByTitle: suspend (title: String) -> AssistantMemory?,
    writeMemory: suspend (title: String, description: String, content: String, overwrite: Boolean) -> AssistantMemory,
    editMemory: suspend (title: String, newTitle: String?, description: String?, content: String?, oldText: String?, newText: String?, replaceAll: Boolean) -> AssistantMemory,
    deleteMemoryByTitle: suspend (title: String) -> Boolean,
    createActiveMemory: suspend (title: String, description: String, content: String, overwrite: Boolean) -> AssistantMemory,
    editActiveMemory: suspend (title: String, newTitle: String?, description: String?, content: String?, oldText: String?, newText: String?, replaceAll: Boolean) -> AssistantMemory,
    deleteActiveMemoryByTitle: suspend (title: String) -> Boolean,
    includeActiveEdit: Boolean,
    includeSavedEdit: Boolean,
): List<Tool>
```

> **delete 工具"先查后删"**：delete_memory / delete_active_memory 的 handler 先经 readMemoryByTitle / readActiveMemoryByTitle 查出整条记忆（含 content/description，供恢复），再调 deleteXxxByTitle（返回 Boolean）。信封带完整记忆信息。
> GenerationHandler 需传 `readActiveMemoryByTitle`：Repository 补 `suspend fun getActiveMemoryByTitle(assistantId: String, title: String): AssistantMemory? = memoryDAO.getActiveMemoryByTitle(assistantId, title)?.toModel()`（若 Task 1 未加则此处补）。

工具定义：
- `read_memory`：不变。
- `write_memory`：不变，信封加 `scope_id`（memoryAssistantId）。
- `edit_memory`：执行时先 `readMemoryByTitle(title)` 拿旧内容，信封加 `previous_content` + `scope_id`。
- `delete_memory`：先查（title→memory），再删；信封 `{type, title, content, description, scope_id, success}`。
- `create_active_memory`：与 write_memory 同构，走 createActiveMemory（isActive=true），信封 `{type, id, title, description, content, scope_id}`。
- `edit_active_memory`：与 edit_memory 同构（先查旧内容），走 editActiveMemory。
- `delete_active_memory`：与 delete_memory 同构，走 deleteActiveMemoryByTitle。
- 删除 `update_active_memory` 工具定义。

saved 与 active 的 edit/delete 工具逻辑完全同构，可写私有辅助函数复用（参数：toolName、readFn、editFn、deleteFn、includeFlag）。

- [ ] **Step 2: GenerationHandler 接线**

`generateText` 的 toolsInternal 块改为（替换现有 buildMemoryTools 调用）：

```kotlin
buildMemoryTools(
    json = json,
    memoryAssistantId = memoryAssistantId,
    readMemoryByTitle = { title -> memoryRepo.getMemoryByTitle(memoryAssistantId, title) },
    readActiveMemoryByTitle = { title -> memoryRepo.getActiveMemoryByTitle(memoryAssistantId, title) },
    writeMemory = { title, description, content, overwrite ->
        memoryRepo.addMemory(memoryAssistantId, title, description, content, overwrite, isActive = false)
    },
    editMemory = { title, newTitle, description, content, oldText, newText, replaceAll ->
        memoryRepo.editMemoryByTitle(memoryAssistantId, title, newTitle, description, content, oldText, newText, replaceAll, isActive = false)
    },
    deleteMemoryByTitle = { title -> memoryRepo.deleteMemoryByTitle(memoryAssistantId, title, isActive = false) },
    createActiveMemory = { title, description, content, overwrite ->
        memoryRepo.addMemory(memoryAssistantId, title, description, content, overwrite, isActive = true)
    },
    editActiveMemory = { title, newTitle, description, content, oldText, newText, replaceAll ->
        memoryRepo.editMemoryByTitle(memoryAssistantId, title, newTitle, description, content, oldText, newText, replaceAll, isActive = true)
    },
    deleteActiveMemoryByTitle = { title -> memoryRepo.deleteMemoryByTitle(memoryAssistantId, title, isActive = true) },
    includeActiveEdit = assistant.enableEditActiveMemory,
    includeSavedEdit = assistant.enableEditSavedMemories,
).let(this::addAll)
```

参数变更：`generateText`/`generateInternal` 的 `activeMemory: AssistantMemory? = null` → `activeMemories: List<AssistantMemory> = emptyList()`；注入行改为：

```kotlin
if (assistant.enableMemory) {
    append(buildMemoryPrompt(activeMemories = activeMemories, savedMemories = memories))
}
```

- [ ] **Step 3: GenerationPrompts 重写（修复空行 + 活跃列表）**

```kotlin
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
            appendLine("- [id ${memory.id}] ${memory.content}")
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
    append("Only the titles and descriptions of saved memories are listed here; call read_memory with a title to fetch its full content.")
}
```

注意：活跃记忆**只注入 id+content**（标题/描述不注入）。"Active memory:" 后**无空行**（直接 appendLine 列表）。

- [ ] **Step 4: ChatService 传 activeMemories**

```kotlin
activeMemories = if (assistant.useGlobalMemory) {
    memoryRepository.getActiveMemories(MemoryRepository.GLOBAL_MEMORY_ID)
} else {
    memoryRepository.getActiveMemories(assistant.id.toString())
},
```

- [ ] **Step 5: 本地提交（不推送、不触发 CI）**

```bash
git add app/src/main/java/me/rerere/rikkahub/data/db/dao/MemoryDAO.kt
git add app/src/main/java/me/rerere/rikkahub/data/repository/MemoryRepository.kt
git add app/src/main/java/me/rerere/rikkahub/data/ai/tools/MemoryTools.kt
git add app/src/main/java/me/rerere/rikkahub/data/ai/GenerationHandler.kt
git add app/src/main/java/me/rerere/rikkahub/data/ai/GenerationPrompts.kt
git add app/src/main/java/me/rerere/rikkahub/service/ChatService.kt
git commit -m "feat(memory): 活跃记忆多条目工具（create/edit/delete_active_memory）+ 注入仅 id+内容无空行"
```
**不推送、不触发 CI**——AssistantDetailVM 仍引用旧 API。与 Task 1 一样，在 Task 3 结束时统一推送验证。

---

### Task 3: VM + 记忆页 UI（活跃记忆区）

**Files:**
- Modify: `app/src/main/java/me/rerere/rikkahub/ui/pages/assistant/detail/AssistantDetailVM.kt`
- Modify: `app/src/main/java/me/rerere/rikkahub/ui/pages/assistant/detail/AssistantMemoryPage.kt`
- Modify: `app/src/main/res/values*/strings.xml`

**Interfaces:**
- Consumes: Task 1/2 Repository API
- Produces: `vm.activeMemories: StateFlow<List<AssistantMemory>>`、`vm.addActiveMemory`/`vm.updateActiveMemory(memory)`/`vm.deleteActiveMemory`

- [ ] **Step 1: AssistantDetailVM**

1. `activeMemory`（单条 StateFlow<AssistantMemory?>）改为：

```kotlin
val activeMemories = assistant
    .flatMapLatest { currentAssistant ->
        if (currentAssistant.useGlobalMemory) {
            memoryRepository.getActiveMemoriesFlow(MemoryRepository.GLOBAL_MEMORY_ID)
        } else {
            memoryRepository.getActiveMemoriesFlow(assistantId.toString())
        }
    }
    .stateIn(
        scope = viewModelScope, started = SharingStarted.Eagerly, initialValue = emptyList()
    )
```

2. 删除 `updateActiveMemory(content: String)`（单槽编辑），新增：

```kotlin
fun addActiveMemory(memory: AssistantMemory) {
    viewModelScope.launch {
        val scope = if (assistant.value.useGlobalMemory) {
            MemoryRepository.GLOBAL_MEMORY_ID
        } else {
            assistantId.toString()
        }
        try {
            memoryRepository.addMemory(
                assistantId = scope,
                title = memory.title,
                description = memory.description,
                content = memory.content,
                overwrite = false,
                isActive = true,
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "addActiveMemory failed", e)
        }
    }
}

fun updateActiveMemory(memory: AssistantMemory) {
    viewModelScope.launch {
        try {
            memoryRepository.updateMemory(
                id = memory.id,
                title = memory.title,
                description = memory.description,
                content = memory.content,
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "updateActiveMemory failed", e)
        }
    }
}

fun deleteActiveMemory(memory: AssistantMemory) {
    viewModelScope.launch {
        memoryRepository.deleteMemory(memory.id)
    }
}
```

（`updateActiveMemory(memory)` 复用 `updateMemory`（按 id 全字段更新），与 saved 一致。）

- [ ] **Step 2: AssistantMemoryPage — 活跃记忆区**

把现有"活跃记忆卡"（单条 Card + 内容编辑对话框）替换为与"已保存的记忆"同构的分区：

```kotlin
Box(
    modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp)
) {
    Text(
        text = stringResource(R.string.assistant_page_active_memories),
        style = MaterialTheme.typography.titleMedium,
        modifier = Modifier.padding(bottom = 8.dp).align(Alignment.CenterStart)
    )
    IconButton(
        onClick = { activeMemoryDialogState.open(AssistantMemory(0, "", "", "", isActive = true)) },
        modifier = Modifier.align(Alignment.CenterEnd)
    ) {
        Icon(HugeIcons.Add01, null)
    }
}

activeMemories.fastForEach { memory ->
    key(memory.id) {
        MemoryItem(
            memory = memory,
            onEditMemory = { activeMemoryDialogState.open(it) },
            onDeleteMemory = { pendingDeleteActiveMemory = it }
        )
    }
}
```

- `activeMemoryDialogState = useEditState<AssistantMemory> { if (it.id == 0) onAddActiveMemory(it) else onUpdateActiveMemory(it) }`（与 saved 对话框同构，三字段 + 标题唯一性门控 `memories=activeMemories`）
- `pendingDeleteActiveMemory` + RikkaConfirmDialog（同 saved）
- 删除旧的活跃记忆单条卡与内容编辑对话框（activeMemoryEditing/activeMemoryDraft 状态）
- `MemoryItem` 复用（标题为主，id 小字——见 Step 3）
- 页面参数加 `activeMemories: List<AssistantMemory>`、`onAddActiveMemory`/`onUpdateActiveMemory`/`onDeleteActiveMemory`

- [ ] **Step 3: MemoryItem 加 id 小字**

`MemoryItem` 的标题下方（描述之上或内容之下）加一行小字 id：

```kotlin
Text(
    text = "#${memory.id}",
    style = MaterialTheme.typography.labelSmall,
    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
)
```

（放在描述 Text 之后、content 之前；或标题行右侧。实现时选描述之后。）

- [ ] **Step 4: 新串**

`values/strings.xml`：
```xml
  <string name="assistant_page_active_memories">Active Memories</string>
```
`values-zh/strings.xml`：
```xml
  <string name="assistant_page_active_memories">活跃记忆</string>
```
ja/ko-rKR/ru/zh-rTW 补同 key。删除不再使用的 `assistant_page_active_memory`、`assistant_page_active_memory_empty`、`assistant_page_active_memory_edit`（先 grep 零引用）。

- [ ] **Step 5: 编译验证 + 提交（Task 1+2+3 合并验证）**

```bash
git add app/src/main/java/me/rerere/rikkahub/ui/pages/assistant/detail/AssistantDetailVM.kt
git add app/src/main/java/me/rerere/rikkahub/ui/pages/assistant/detail/AssistantMemoryPage.kt
git add app/src/main/res/values*
git commit -m "feat(memory): 记忆页活跃记忆分区（多条增删改）+ VM activeMemories flow"
git push origin master
gh workflow run nightly-build-debug.yml --repo DevLintMar/rikkahub --ref master
```
Expected: CI 绿（此时 Task 1/2/3 全部就绪）。

---

### Task 4: 渲染器 + 图标 + 详情页按钮 + 串

**Files:**
- Modify: `app/src/main/java/me/rerere/rikkahub/ui/components/message/tools/MemoryToolsUIs.kt`（整文件重写）
- Modify: `app/src/main/java/me/rerere/rikkahub/ui/components/message/tools/ToolUI.kt`（注册表）
- Modify: `app/src/main/java/me/rerere/rikkahub/ui/components/message/tools/WorkspaceToolUIs.kt`（GrepToolUI 图标）
- Modify: `app/src/main/res/values*/strings.xml`

**Interfaces:**
- Consumes: ToolUIRenderer/ToolUIContext/ToolDetailContainer/ToolPill/RikkaConfirmDialog（参考 BuiltinToolUIs.kt MemoryToolUI.Preview 的按钮+确认对话框模式）、MemoryRepository（koinInject）
- Produces: 8 个渲染器（read/write/edit/delete × saved/active，其中 saved 4 个已有需改）

- [ ] **Step 1: 新串**

`values/strings.xml`：
```xml
  <string name="chat_message_tool_create_active_memory">Create active memory</string>
  <string name="chat_message_tool_edit_active_memory">Edit active memory</string>
  <string name="chat_message_tool_delete_active_memory">Delete active memory</string>
  <string name="tool_ui_restore_memory">Restore</string>
  <string name="tool_ui_revert_edit">Revert edit</string>
  <string name="tool_ui_delete_this_memory">Delete this memory</string>
```
`values-zh/strings.xml`：
```xml
  <string name="chat_message_tool_create_active_memory">新建活跃记忆</string>
  <string name="chat_message_tool_edit_active_memory">编辑活跃记忆</string>
  <string name="chat_message_tool_delete_active_memory">删除活跃记忆</string>
  <string name="tool_ui_restore_memory">恢复</string>
  <string name="tool_ui_revert_edit">回退编辑</string>
  <string name="tool_ui_delete_this_memory">删除本条记忆</string>
```
ja/ko-rKR/ru/zh-rTW 补。删除 `chat_message_tool_update_active_memory`（先 grep 零引用）。

- [ ] **Step 2: MemoryToolsUIs.kt 整文件重写**

8 个渲染器。共享模式（参考旧 MemoryToolUI.Preview 与现有渲染器）：

```kotlin
package me.rerere.rikkahub.ui.components.message.tools

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.composables.icons.lucide.BookCheck
import com.composables.icons.lucide.Lucide
import kotlinx.coroutines.launch
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Eraser
import me.rerere.hugeicons.stroke.PencilEdit01
import me.rerere.hugeicons.stroke.QuillWrite01
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.repository.MemoryRepository
import org.koin.compose.koinInject
```

图标分配：
- ReadMemoryToolUI: `Lucide.BookCheck`
- WriteMemoryToolUI / CreateActiveMemoryToolUI: `HugeIcons.QuillWrite01`
- EditMemoryToolUI / EditActiveMemoryToolUI: `HugeIcons.PencilEdit01`
- DeleteMemoryToolUI / DeleteActiveMemoryToolUI: `HugeIcons.Eraser`

折叠行标题串：read/write/edit/delete saved 用现有串；create/edit/delete active 用新串。

**DeleteMemoryToolUI / DeleteActiveMemoryToolUI 概览**：

```kotlin
override fun hasSummary(context: ToolUIContext): Boolean = true

@Composable
override fun Summary(context: ToolUIContext) {
    val title = context.content.getStringContent("title")
        ?: context.arguments.getStringContent("title")
    title?.let {
        Text(
            text = it,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
```

**详情页按钮**（Preview，参考 MemoryToolUI.Preview 的 ToolDetailContainer + Row + IconButton + AlertDialog 确认模式）：

- Write/Create Preview：content 展示 + 删除本条按钮（envelope.id；确认对话框 → `memoryRepo.deleteMemory(id)` → onDismissRequest）
- Edit Preview：content + previous_content 展示 + 删除本条 + 回退编辑（`memoryRepo.updateMemory(id, title, description, previous_content)`——从 envelope 取 id/title/description/previous_content；确认对话框）
- Delete Preview：title + content 展示 + 恢复按钮（`memoryRepo.addMemory(scopeId, title, description, content, overwrite=true, isActive=<tool 类型>)`——scope_id 从 envelope 取；无确认，直接恢复后 dismiss）

> saved 与 active 的 Preview 逻辑同构，差异只在 isActive 标志（恢复时）与串。可写私有 @Composable 辅助函数 `MemoryDetailActions(...)` 复用。

- [ ] **Step 3: ToolUIRegistry 更新**

`ToolUI.kt` renderers 列表：删除 `UpdateActiveMemoryToolUI`，加 `CreateActiveMemoryToolUI`/`EditActiveMemoryToolUI`/`DeleteActiveMemoryToolUI`（放在 MemoryToolUI 之前，与现有顺序一致）。

- [ ] **Step 4: GrepToolUI 图标**

`WorkspaceToolUIs.kt` GrepToolUI：

```kotlin
override fun icon(context: ToolUIContext): ImageVector = Lucide.FolderSearch
```

加 import `com.composables.icons.lucide.FolderSearch`、`com.composables.icons.lucide.Lucide`。

- [ ] **Step 5: 编译验证 + 提交**

```bash
git add app/src/main/java/me/rerere/rikkahub/ui/components/message/tools/MemoryToolsUIs.kt
git add app/src/main/java/me/rerere/rikkahub/ui/components/message/tools/ToolUI.kt
git add app/src/main/java/me/rerere/rikkahub/ui/components/message/tools/WorkspaceToolUIs.kt
git add app/src/main/res/values*
git commit -m "feat(memory): 记忆工具渲染器重做（活跃三件套+详情按钮+图标+grep 换 FolderSearch）"
git push origin master
gh workflow run nightly-build-debug.yml --repo DevLintMar/rikkahub --ref master
```
Expected: CI 绿。若 `Lucide.FolderSearch`/`Lucide.BookCheck` 编译失败，回退 HugeIcons 等价物并记录。
