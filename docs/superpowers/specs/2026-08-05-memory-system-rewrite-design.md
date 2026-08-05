# 记忆系统重写设计方案（Memory System Rewrite）

**日期**：2026-08-05
**状态**：设计稿（待审阅）
**参考**：Agora 记忆系统（`references/Agora`）、rikkahub 现有记忆系统

---

## 1. 概述与目标

把 rikkahub 现有的"单表 id+content 记忆 + 单 `memory_tool`"重写为 Agora 式记忆系统：

- **活跃记忆（Active Memory）**：每个作用域一个文本槽位，随 useGlobalMemory 决定 per-assistant/全局。AI 只有 edit 能力。
- **已保存的记忆（Saved Memories）**：以 id 组织（Room），展示为 标题/描述/内容。AI 有 write（含显式 overwrite）/edit/delete/read 能力。
- **提示词注入**：活跃记忆全量 + 已保存记忆的标题+描述（**不是**完整内容）。
- 2 个新开关：`更改活跃记忆`、`更改已保存的记忆`，门控对应写工具。
- 两个开关 UI 迁移（参考历史聊天记录 → 本地工具页；时间提醒 → 提示词页）。
- 质量修复：工作区工具改名（去 `_file` 后缀）+ 工具报错展示修复。

已确认的决策：
1. 活跃记忆作用域**跟随 useGlobalMemory**（per-assistant 默认，`__global__` 共享）。
2. `read_memory` 工具**只要 enableMemory 即注册**（注入的只有标题+描述，读取不改数据）。
3. 参考历史聊天记录开关：**保留字段 `enableRecentChatsReference`，仅移动 UI 行**。
4. **不含 `list_memory` 工具**。
5. 活跃记忆卡**始终显示**（与已保存记忆管理区一致）。

---

## 2. 数据模型

### 2.1 `AssistantMemory`（`app/.../data/model/Assistant.kt:64-68`）

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

### 2.2 `Assistant`（`Assistant.kt`）

新增 2 个字段（默认值 → kotlinx 向后兼容，DataStore JSON 无需版本迁移）：

```kotlin
val enableEditActiveMemory: Boolean = false,   // "更改活跃记忆"
val enableEditSavedMemories: Boolean = false,  // "更改已保存的记忆"
```

保留不动的字段：`enableMemory`（总开关）、`useGlobalMemory`、`enableRecentChatsReference`、`enableTimeReminder`、`timeReminderAlwaysInsert`。

### 2.3 工具门槛汇总

| 能力 | 门槛 |
|---|---|
| 记忆注入（活跃全量 + 已保存标题/描述） | `enableMemory` |
| `read_memory` | `enableMemory` |
| `update_active_memory` | `enableMemory && enableEditActiveMemory` |
| `write_memory` / `edit_memory` / `delete_memory` | `enableMemory && enableEditSavedMemories` |

---

## 3. 存储 + 迁移

### 3.1 MemoryEntity（`data/db/entity/MemoryEntity.kt`）

加 3 列：

```kotlin
@ColumnInfo("title")       val title: String = "",
@ColumnInfo("description") val description: String = "",
@ColumnInfo("is_active")   val isActive: Boolean = false,
```

### 3.2 Room 迁移（version 25 → 26）

`AppDatabase.kt`：`version = 26`，不加 AutoMigration(25→26)，改用手动迁移（AutoMigration 无法做数据回填）。新增 `data/db/migrations/Migration_25_26.kt`，注册到 `DataSourceModule.kt:58` 的 `.addMigrations(...)`：

```sql
ALTER TABLE memoryentity ADD COLUMN title TEXT NOT NULL DEFAULT ''
ALTER TABLE memoryentity ADD COLUMN description TEXT NOT NULL DEFAULT ''
ALTER TABLE memoryentity ADD COLUMN is_active INTEGER NOT NULL DEFAULT 0
-- 旧记忆回填标题（内容前 40 字符）
UPDATE memoryentity SET title = substr(trim(content), 1, 40) WHERE title = ''
```

### 3.3 MemoryDAO 扩展

新增查询：
- `getActiveMemory(assistantId): MemoryEntity?`（`is_active = 1`）
- `getMemoryByTitle(assistantId, title): MemoryEntity?`（`title = :title` 精确匹配，取首条）
- 现有 `getMemoriesOfAssistant*` / `getGlobalMemories*` **改为过滤 `is_active = 0`**（已保存记忆不含活跃行）

### 3.4 MemoryRepository（`data/repository/MemoryRepository.kt`）

- 保持 `GLOBAL_MEMORY_ID = "__global__"`。
- 现有 `getMemoriesOfAssistant*` / `getGlobalMemories*` → 只返回已保存记忆（`is_active=0`）。
- **新增**：
  - `getActiveMemory(scope): AssistantMemory?`
  - `updateActiveMemory(scope, content, mode, oldString, newString): AssistantMemory` — replace/append/prepend/patch 4 模式；活跃行不存在时 replace/append/prepend 创建之，patch 要求已存在。
  - `getMemoryByTitle(scope, title): AssistantMemory?` — 精确标题优先；若为空则回退匹配"内容首行推导标题"（`content.lineSequence().firstOrNull().take(40)`）。
  - `addMemory(scope, title, description, content, overwrite): AssistantMemory` — 标题已存在且 `overwrite=false` → `error("Memory already exists ...")`；已存在且 overwrite → 更新该行（保留 id）；否则插入。
  - `updateMemory(id, title, description, content)` — 扩展现有方法。
  - `deleteMemory(id)` — 保留（UI 与旧渲染器用）。
  - 新增 `deleteMemoryByTitle(scope, title)`。

**唯一性约束**：仓库层强制标题唯一（不建 DB unique index，避免迁移破坏已有重复数据）；`write_memory`/`edit_memory` 改名时检查冲突。重复标题（迁移历史数据）时 `getMemoryByTitle` 返回首条。

---

## 4. AI 工具（重写 `MemoryTools.kt`，替换单 `memory_tool`）

全部返回 `List<Tool>`，由 `GenerationHandler.generateText`（现 `GenerationHandler.kt:97-115`）按门槛条件注册。工具名与 Agora 对齐（去 `_file`/`_memory_file` 冗词）：

### 4.1 `read_memory`
- 门槛：`enableMemory`
- 参数：`title`（required）
- 执行：`getMemoryByTitle(scope, title)`，找不到 `error("Memory not found: $title")`；成功返回 `{type:"read_memory", id, title, description, content}`。
- 目的：req 4 —— 模型按标题读取完整内容（注入的只有标题+描述）。

### 4.2 `update_active_memory`
- 门槛：`enableMemory && enableEditActiveMemory`
- 参数：`content`（required）、`mode`（enum: replace/append/prepend/patch，默认 replace）、`old_string`（patch 用）、`new_string`（patch 用）
- 执行：委托 `MemoryRepository.updateActiveMemory(scope, content, mode, oldString, newString)`；patch 时 `old_string` 必须恰好出现一次（与 Agora 一致），否则 error。
- 目的：req 3 —— 活跃记忆**只有 edit**。

### 4.3 `write_memory`
- 门槛：`enableMemory && enableEditSavedMemories`
- 参数：`title`（required）、`content`（required）、`description`（optional）、`overwrite`（boolean，默认 false）
- 执行：委托 `addMemory(scope, title, description, content, overwrite)`。**标题已存在且未显式传 `overwrite=true` → error**（req 3：覆盖必须显式）。
- 返回 `{type:"write_memory", id, title, description, content}`。

### 4.4 `edit_memory`
- 门槛：`enableMemory && enableEditSavedMemories`
- 参数：`title`（required，定位目标）、`content`（全量重写，可选）、`old_text`+`new_text`（手术刀式替换，可选）、`replace_all`（bool，默认 false）、`new_title`（改名，可选）、`description`（更新描述，可选）
- 执行：至少提供一个变更字段（content/old_text/new_title/description），否则 error。`old_text→new_text` 复用 `TextReplacers.replaceText`（与工作区 edit 相同策略：exact → line_trimmed → block_anchor，`replace_all` 控制）。改名时检查 `new_title` 不与其他已保存记忆冲突。返回 `{type:"edit_memory", ...}`。
- 目的：req 3 —— **edit 参考工作区 edit 功能**（`WorkspaceTools.createEditFileTool` 的 old_text/new_text + replace_all 语义）。

### 4.5 `delete_memory`
- 门槛：`enableMemory && enableEditSavedMemories`
- 参数：`title`（required）
- 执行：`deleteMemoryByTitle(scope, title)`；返回 `{type:"delete_memory", title, success:true}`。

**执行错误统一**：所有工具在参数缺失/记忆不存在/覆盖冲突时 `error(...)`，由 `GenerationHandler` 现有 `runCatching.onFailure`（`GenerationHandler.kt:321-344`）包装成 `{type, error, message}` 信封 + `ToolState.FAILED`。

---

## 5. 提示词注入（重写 `buildMemoryPrompt`，`GenerationPrompts.kt`）

签名改为：

```kotlin
internal fun buildMemoryPrompt(
    activeMemory: AssistantMemory?,
    savedMemories: List<AssistantMemory>,
): String
```

`GenerationHandler.generateInternal:408` 处当 `enableMemory` 时注入（保留 RikkaHub `**Memories**` markdown 惯例）：

```
**Memories**

Active memory:
<活跃记忆完整内容>

Saved memories (use read_memory to read the full content of any):
- <标题> — <描述>
- <标题> — <描述>

Only the titles and descriptions of saved memories are listed here; call read_memory with a title to fetch its full content.
```

- 活跃记忆：完整内容；无活跃记忆则该小节省略。
- 已保存记忆：**只注入标题+描述**（req 2）。标题为空时用内容首行（≤40 字符）兜底显示。
- 尾部指令行说明 `read_memory` 用法（比 Agora 多注入 saved titles —— 这是用户明确要求的差异）。

`ChatService.kt:657-661` 的 `memories` 参数改为传已保存记忆（已含过滤），并额外传 `activeMemory` 给 `generateText`。

---

## 6. UI

### 6.1 记忆页 `AssistantMemoryPage.kt`（重做）

**CardGroup**（保留 2 项，新增 2 项，移除 3 项）：
1. 记忆 `enableMemory`（保留）
2. 全局记忆 `useGlobalMemory`（保留，`enabled = enableMemory`）
3. **新增** 更改活跃记忆 `enableEditActiveMemory`（`enabled = enableMemory`）
4. **新增** 更改已保存的记忆 `enableEditSavedMemories`（`enabled = enableMemory`）
- 移除：参考历史聊天记录 / 时间提醒 / 总是插入时间提醒 3 行。

**活跃记忆卡**（始终显示，位于已保存记忆上方，Agora 式）：
- 内容前 100 字符 + "..."（空则显示空态文案）；右侧编辑铅笔 → 仅 content 的编辑对话框（`mode=replace`）。
- 数据来自 `AssistantDetailVM.activeMemory`。

**"已保存的记忆"**（由"管理记忆"更名）：
- 标题 + 添加按钮。
- 列表项：标题（粗体）+ 描述（supporting）+ 内容预览（≤5 行）；kebab 菜单 Edit/Delete（Agora 式）。
- 添加/编辑对话框：**标题 + 描述 + 内容** 三字段（Agora File Editor Dialog）。
- 删除走 `RikkaConfirmDialog`。

**`AssistantDetailVM.kt`**：`memories` flow 已含过滤（repository 层）；新增 `activeMemory: StateFlow<AssistantMemory?>`（`flatMapLatest` 于 `useGlobalMemory`）。

### 6.2 本地工具页 `AssistantLocalToolPage.kt`

在"询问用户"（`AskUser`，现 line 187-200）与"屏幕时间"（`ScreenTime`，现 line 201-214）之间插入一行：

- 标题"参考历史聊天记录"、描述沿用 `assistant_page_recent_chats_desc`、Switch 绑 `assistant.enableRecentChatsReference`（普通 boolean 行，不走 `toggleLocalTool`，同 `SubAgentAutoApproval` 模式）。

### 6.3 提示词页 `AssistantPromptPage.kt`

在"独立对话提示词注入"卡片（`allowConversationPromptInjection`，现 line 231-255）下方插入一张 CardGroup 两行：

- 时间提醒 `enableTimeReminder`（`assistant_page_time_reminder*`）
- 总是插入时间提醒 `timeReminderAlwaysInsert`（`assistant_page_time_reminder_always*`）

### 6.4 助手列表页

`AssistantPage.kt` 的记忆数量 tag（`assistant_page_memory_count`）保留，`memories.size` 现为已保存记忆数（含过滤，活跃行不计入）。

---

## 7. 工具调用展示（req 4）

`ToolUI.kt` 的 `ToolUIRegistry` 新增渲染器，集中放在**新建 `ui/components/message/tools/MemoryToolsUIs.kt`**（沿用现有 `MemoryToolUI` 的折叠行 + Summary + Preview 模式）：

| 渲染器 | toolName | 折叠行标题 | 摘要 | 详情 |
|---|---|---|---|---|
| `UpdateActiveMemoryToolUI` | `update_active_memory` | 更新活跃记忆 | content | content + id |
| `ReadMemoryToolUI` | `read_memory` | 读取记忆 | content 首部 | title/description/content |
| `WriteMemoryToolUI` | `write_memory` | 写入记忆 | content | content + title |
| `EditMemoryToolUI` | `edit_memory` | 编辑记忆 | 变更内容 | content/变更 |
| `DeleteMemoryToolUI` | `delete_memory` | 删除记忆 | — | 删除确认 |

- 旧 `MemoryToolUI`（`memory_tool`）渲染器**保留注册**（老历史消息仍能渲染）；旧工具实现 `buildMemoryTools` 删除。
- 错误态：新渲染器沿用第 8 节统一的 FAILED 展示（`message` error 色）。
- 图标：write/edit 用 `QuillWrite01`，delete 用 `Eraser`，read 用 `BookOpen01`/`FolderOpen`（以 CI 编译为准）。

---

## 8. 质量修复 A：工作区工具改名

`workspace_read_file → workspace_read`、`workspace_write_file → workspace_write`、`workspace_edit_file → workspace_edit`（shell/glob/grep 不动）。

### 8.1 改动点（含文件:行）

| 文件 | 位置 | 改动 |
|---|---|---|
| `data/ai/tools/WorkspaceTools.kt` | name 78/144/192；approval map 34-36；needsApproval 101/165/219；信封 type 125/175/240/509 | 全部改新名 |
| `ui/components/ai/ToolSelector.kt` | `ALL_BASE_TOOLS` 27-29 | 改新名 |
| `data/ai/agents/AgentManager.kt` | `disallowedTools` 25-26 | 改新名 |
| `data/ai/transformers/WorkspaceReminderTransformer.kt` | 48-51 提示词散文 | 改新名（模型可见） |
| `ui/components/message/ChatMessageEditedFiles.kt` | `WORKSPACE_FILE_TOOL_NAMES` 54 | 改新名 |
| `ui/pages/extensions/workspace/WorkspaceDetailPage.kt` | `workspaceToolApprovalItems()` 464-466 | 改新名 |
| `ui/components/message/tools/WorkspaceToolUIs.kt` | `toolName` 59/160/204 | 改新名（注册表 key 跟随） |

`ToolUIRegistry`（ToolUI.kt:88-109）按 `it.toolName` 自动跟随，无需单独改。

### 8.2 历史审批覆盖迁移

`WorkspaceEntity.toolApprovals` 存的是 `toolName → needsApproval` JSON，旧 key 会失效。在 `WorkspaceEntity.toolApprovalOverrides()`（`WorkspaceEntity.kt:37-39`）读时做**旧→新 key 重映射**：

```kotlin
private val TOOL_NAME_LEGACY_MAP = mapOf(
    "workspace_read_file" to "workspace_read",
    "workspace_write_file" to "workspace_write",
    "workspace_edit_file" to "workspace_edit",
)
```

已保存的审批设置不失效；`setToolApproval` 写入的自然是新名。

### 8.3 旧聊天记录

旧持久化消息里 `UIMessagePart.Tool.toolName` 为旧名 → 展示回退 `DefaultToolUIRenderer`（通用标题 + JSON 详情），可接受，不做别名映射。

---

## 9. 质量修复 B：工具报错展示

### 9.1 根因（已确认）

`ToolState.FAILED` 在数据层已计算并存储（`GenerationHandler.kt:274/319/342`、`ToolResultEnvelope.inferToolState`），错误信封 `{type, error:"error", message:"[类名] 消息"}` 已写入 `tool.output`，但 **UI 从不读取 `toolState` 与 `message`**。`ChatMessageToolStep` 的 `extra` 槽只有 isPending/isDenied 分支。

### 9.2 修复点

1. **`ChatMessageTools.kt` `ChatMessageToolStep`**（`extra` 槽，现 line 129-168）：加 `isFailed` 分支 —— `tool.toolState == ToolState.FAILED` 时，在折叠行 error 色显示 `context.content.getStringContent("message")`（无则通用失败文案）。所有工具统一受益。
2. **`WorkspaceToolUIs.kt` 渲染器**：`hasSummary`/`Summary` 检测 `content.getStringContent("error") != null` → error 色渲染 `message`（对齐已有 `GetScreenTimeToolUI.Summary` 错误模式，BuiltinToolUIs.kt:742-749）。修：WriteFileToolUI 失败仍显示待写内容（误导）、ReadFile/EditFile 折叠行空白、ShellToolUI 异常时显示"exit code ?"。
3. **`ToolUI.kt` `DefaultToolPreview`**（现 line 122-170）：结果 JSON 含 `error` key 时，优先渲染可读错误消息（error 色），而非只显示 JSON 树。
4. **`ShellToolUI`**（WorkspaceToolUIs.kt:296-404）：信封含 `error` key（无 `exitCode`）时显示 `message` 而非 "exit code ?"。

---

## 10. i18n

新串 **en+zh 双写**；ja/ko-rKR/ru/zh-rTW 中已存在对应记忆相关 key 的（现各 8 个，en/zh 各 12 个）一并补。新 key 清单（在 plan 中细化）：

- 开关：`assistant_page_edit_active_memory(_desc)`、`assistant_page_edit_saved_memories(_desc)`
- 活跃记忆卡：`assistant_page_active_memory`、`assistant_page_active_memory_empty`、`assistant_page_active_memory_edit`
- 已保存记忆更名：`assistant_page_saved_memories`（替换现 `assistant_page_manage_memory_title` 行 277 处用法）；编辑/添加对话框标题新增 `assistant_page_edit_memory`（add/edit 共用，替换行 122/131 用法）；`assistant_page_manage_memory_title` 删串前先 grep 零引用
- 记忆项字段：`assistant_page_memory_title_hint`、`assistant_page_memory_description_hint`、`assistant_page_memory_content_hint`
- 工具折叠行：`chat_message_tool_read_memory`、`chat_message_tool_write_memory`、`chat_message_tool_edit_memory`、`chat_message_tool_delete_memory`、`chat_message_tool_update_active_memory`
- 工具详情：`tool_ui_memory_*`（复用/新增）
- 错误展示：`chat_message_tool_failed`（通用失败文案）

删串先 grep 零引用。

---

## 11. 验证

- CI 铁律：`git push origin master` → `gh workflow run nightly-build-debug.yml --repo DevLintMar/rikkahub --ref master` → `gh run list ... --json databaseId,headSha,status,conclusion` 核对 headSha。
- 设备核验（用户装 debug APK）：
  1. 记忆页：活跃记忆卡 + 已保存记忆三字段展示/编辑/删除；两个新开关；旧记忆迁移出标题。
  2. 本地工具页/提示词页：两个开关行位置正确、行为不变。
  3. 对话中：AI 能 read/write/edit/delete/update_active 记忆；报错（覆盖冲突/不存在）折叠行直接显示错误。
  4. 工作区工具：改名后工具调用正常；报错时折叠行显示错误消息；已保存的审批覆盖仍生效。
  5. 提示词注入：系统消息含活跃记忆 + 已保存标题/描述。
  6. 夜间模式、中英文串正确。

---

## 12. 不在本次范围

- `list_memory` 工具（用户确认不做）。
- 老 `memory_tool` 的历史调用消息重渲染（保留旧渲染器，不回改数据）。
- 乱召回/语义搜索、Task 12 ripgrep、更新检查恢复点（既有挂起项，另行处理）。
