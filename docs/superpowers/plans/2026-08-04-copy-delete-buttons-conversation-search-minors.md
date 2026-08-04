# 文案修正 + 三处删除键 + conversation_search 重设计 + 3 个 Minor 修复

## Context

用户提出 4 项改动：

1. **文案**：时间提醒"总是插入" → "总是插入时间提醒"
2. **删除键**：MCP 服务器、模式注入、世界书在编辑界面无法删除 → 每个编辑界面左下角加红色"删除"按钮（需二次确认）
3. **conversation_search 重设计**：只返回匹配的具体那条消息（`snippet` 含 `[brackets]` 着重标记，对应最早 rikkahub 实现的 FTS `simple_snippet`），加所在对话（`conversation_id`/`title`）与消息索引（`index`）；与 `read_conversation` 搭配取上下文；工具描述同步优化
4. **3 个 Minor**：`DefaultToolPreview` result json 解析无 remember；`JsonTreeView` 大响应硬化；sizeBytes toInt 溢出（**已确认当前代码为 Long，已修复，无需改动**，核验后报告）

## Global Constraints

- 本机无编译器：静态编写 + review，编译验证全靠 CI
- CI：先 `git push origin master` 再 `gh workflow run nightly-build-debug.yml --repo DevLintMar/rikkahub --ref master`
- 字符串双写 en `values/` + zh `values-zh/`
- `MessageSearchResult.snippet` = FTS `simple_snippet(…, '[', ']', '...', 30)` → `[brackets]` 着重标记
- conversation_search 的 `index` 与 read_conversation 的 `offset` 对齐（都基于 `currentMessages.filter { USER || ASSISTANT }`）

---

## Task 1: "总是插入" → "总是插入时间提醒"

**Files:** `values/strings.xml` + `values-zh/strings.xml`

- zh `assistant_page_time_reminder_always`：`总是插入` → `总是插入时间提醒`
- en `assistant_page_time_reminder_always`：`Always insert` → `Always insert time reminder`

（desc 不变）

---

## Task 2: 三处删除键（红色"删除" + 二次确认，编辑界面左下角）

三个编辑界面结构一致（底部 `Row` 右对齐确认/取消）。统一方案：`Row` 改为左侧红色"删除"TextButton（`ButtonDefaults.textButtonColors(contentColor = error)`）+ `Spacer(weight(1f))` 推右；点击弹确认 AlertDialog，确认后删除并关闭 Sheet/Modal。删除按钮**仅在编辑已存在项时显示**（创建态隐藏）。

**Files:**
- Modify: `app/src/main/java/me/rerere/rikkahub/ui/pages/setting/SettingMcpPage.kt`
- Modify: `app/src/main/java/me/rerere/rikkahub/ui/pages/extensions/PromptPage.kt`
- Modify: `values/strings.xml` + `values-zh/strings.xml`

### 2a. MCP（SettingMcpPage.kt `McpServerConfigModal`）

- 签名加 `onDelete: ((McpServerConfig) -> Unit)? = null`（编辑态传、创建态 null）
- 底部 `Row`：`if (onDelete != null) TextButton(红"删除") { showDeleteConfirm = true }` + `Spacer(weight(1f))` + 原 Save
- 确认 AlertDialog：确认 → `onDelete(config); state.dismiss()`
- 调用点：
  ```kotlin
  McpServerConfigModal(creationState)
  McpServerConfigModal(editState, onDelete = { cfg ->
      vm.updateSettings(settings.copy(mcpServers = mcpConfigs.filter { it.id != cfg.id }))
  })
  ```

### 2b. 模式注入（PromptPage.kt `ModeInjectionEditSheet`）

- 签名加 `onDelete: (() -> Unit)? = null`
- 底部 `Row`：`if (onDelete != null) TextButton(红"删除")` + `Spacer(weight(1f))` + 原 Cancel/Confirm
- 确认 AlertDialog → `onDelete()`
- 调用点（`ModeInjectionTab`，仅已存在项可删）：
  ```kotlin
  onDelete = if (modeInjections.any { it.id == state.id }) {
      { onUpdate(modeInjections - state); editState.dismiss() }
  } else null
  ```

### 2c. 世界书（PromptPage.kt `LorebookEditSheet`）

- 同 2b 模式，`onDelete = if (lorebooks.any { it.id == state.id }) { { onUpdate(lorebooks - state); editState.dismiss() } } else null`

### 2d. 字符串（en + zh）

新增（若已存在通用 `delete`/`confirm` 则复用）：
```xml
<string name="delete">Delete</string>                      <!-- zh: 删除 -->
<string name="delete_confirm_message">Are you sure you want to delete? This action cannot be undone.</string>
<!-- zh: 确定要删除吗？此操作无法撤销。 -->
```

---

## Task 3: conversation_search 重设计

**Files:**
- Modify: `app/src/main/java/me/rerere/rikkahub/data/repository/ConversationRepository.kt`
- Modify: `app/src/main/java/me/rerere/rikkahub/data/ai/tools/ConversationTools.kt`
- Modify: `app/src/main/java/me/rerere/rikkahub/ui/components/message/tools/BuiltinToolUIs.kt`

### 3a. Repository：新增 per-message 搜索，删除旧 window 方法

新增（`ConversationSearchWindow`/`WindowMessage` 仅被旧方法使用，删除旧方法 + 两个 data class）：

```kotlin
data class ConversationSearchHit(
    val conversationId: String,
    val title: String,
    val index: Int,        // 在 currentMessages(USER/ASSISTANT) 中的位置，与 read_conversation offset 对齐
    val role: String,
    val text: String,      // 匹配消息全文
    val snippet: String,   // FTS snippet（[brackets] 着重标记）
    val score: Float,
)

suspend fun searchConversationMessages(query: String, limit: Int = 15): List<ConversationSearchHit> {
    val fts = searchMessages(query, MessageSearchSort.RELEVANCE).take(limit)
    val semantic = if (semanticIndexManager.isConfigured()) {
        semanticIndexManager.search(query, limit).map { hit ->
            MessageSearchResult(hit.nodeId, hit.messageId, hit.conversationId, "", Instant.EPOCH, hit.chunkText.take(120))
        }
    } else emptyList()
    val fused = rrfFuseScored(fts, semantic, k = 60)
    return fused.mapNotNull { hit ->
        val conversation = getConversationById(Uuid.parse(hit.conversationId)) ?: return@mapNotNull null
        val branch = runCatching { conversation.currentMessages }
            .getOrDefault(emptyList())
            .filter { it.role == MessageRole.USER || it.role == MessageRole.ASSISTANT }
        val index = branch.indexOfFirst { it.id.toString() == hit.messageId }
        if (index < 0) return@mapNotNull null
        val message = branch[index]
        ConversationSearchHit(
            conversationId = hit.conversationId,
            title = conversation.title,
            index = index,
            role = message.role.name.lowercase(),
            text = MessageTextExtractor.messageToSearchText(message),
            snippet = hit.snippet,
            score = hit.score.toFloat(),
        )
    }
}
```

### 3b. 工具（ConversationTools.kt）

- 移除 `context_window` 参数；描述改为：
  ```
  Returns each specific matched message, with the matched keywords marked in [brackets] in `snippet`.
  Each result includes the conversation (`conversation_id`, `title`) and the message's `index` within it.
  Use `read_conversation` with the same `conversation_id` and `offset` near `index` to read the surrounding context.
  ```
- 输出：
  ```json
  { "type": "conversation_search", "query": "...", "results": [
      { "conversation_id": "...", "title": "...", "index": 7, "role": "user",
        "text": "...", "snippet": "before [matched] after ...", "score": 0.8 }
  ] }
  ```
- `read_conversation` 描述补一句：`The message index from conversation_search maps to the offset here, so pass offset near the index to read context around a match.`

### 3c. UI 渲染器（BuiltinToolUIs.kt `ConversationSearchToolUI.Preview`）

结果条目渲染改为：标题 + `index` 序号 + `role` + `snippet`（`[brackets]` 原样展示，与 AI 所见一致）。去掉 `match_count`/`messages[]` 渲染。

---

## Task 4: 3 个 Minor 修复

### 4a. DefaultToolPreview result json 解析 remember

`ToolUI.kt`：
```kotlin
val joinedText = textParts.joinToString("\n") { it.text }
val resultJson = remember(joinedText) {
    runCatching { JsonInstant.parseToJsonElement(joinedText) }.getOrNull()
}
```

### 4b. JsonTreeView 大响应硬化

`JsonTreeView.kt`：
- `JSON_TREE_MAX_ITEMS`：2000 → 500（减少一次性组合的行数）
- 长字符串截断：`JsonTreePrimitiveView` 中字符串超过 `JSON_TREE_STRING_MAX = 800` 时显示前 800 + `…`（防单个大 Text 节点）

### 4c. sizeBytes toInt 溢出

**已确认修复**：`WorkspaceFileEntry.sizeBytes: Long` → 生产者 `put("sizeBytes", JsonPrimitive(f.sizeBytes))`（Long）→ UI `f.long("sizeBytes")`（Long）+ `formatFileSize(Long)`。全仓无 `sizeBytes`+`toInt`。**无需改动**，验证后报告。

---

## Verification

- 每任务独立 commit → push → CI；CI 红时 `--log-failed` 区分 flake 与编译错误
- 设备核验：设置→MCP 编辑弹层左下角红色删除（二次确认）；提示页模式注入/世界书编辑 Sheet 左下角红色删除；conversation_search 结果只含单条匹配消息+索引+着重标记、描述引导用 read_conversation 取上下文
