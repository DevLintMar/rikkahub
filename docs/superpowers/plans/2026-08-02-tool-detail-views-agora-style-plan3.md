# 工具详情视图（Preview）全量 Agora 化 — Plan 3 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 消除工具详情视图的"贴 JSON"现象——给所有当前落 `DefaultToolPreview`（原始 JSON）的工具补语义化 `Preview`，参照 Agora `ToolDetailContent` 的视觉语言（小节标签 / 胶囊 / 终端块 / 索引行），同时注册 Plan 1 Task 11 遗漏的 `workspace_glob`/`workspace_grep` 渲染器。

**Architecture:** 新增共享详情组件 `ToolDetailCommon.kt`（容器 + 小节标签 + 胶囊 + 终端块 + 索引行），供所有 Preview 复用；`WorkspaceToolUIs.kt` 加 `GlobToolUI`/`GrepToolUI` 并注册进 `ToolUIRegistry`；`BuiltinToolUIs.kt` 给 9 个既有渲染器补 `Preview` override。**只改展示层，信封形状冻结。** MCP/未注册工具保持 `DefaultToolPreview`（JSON 兜底，与 Agora 一致——未知形状无法语义化）。

**Tech Stack:** Jetpack Compose, Material3, kotlinx.serialization, HugeIcons。

## Global Constraints

- **本机无编译器**：不运行 gradle。所有代码静态编写 + review；真实编译靠 CI（nightly-build-debug，先 push 再触发，gh 需 `--repo DevLintMar/rikkahub`）。
- **展示层只消费信封**：不改任何工具 producer（信封形状冻结，见各任务已核实的 key）。
- **字符串双写**（用户 2026-08-02 拍板）：英文进 `app/src/main/res/values/strings.xml`、中文进 `values-zh/strings.xml`，按字母序插入。新字符串一律走此规。
- **保留既有 renderer 的 `icon`/`title`/`Summary` 不动**，本计划只加 `Preview` override + 注册新渲染器。
- 视觉语言：复用本计划新增的 `ToolDetailCommon.kt` 组件 + RikkaHub 既有 `FormItem`/`HighlightCodeBlock`/`Text`；布局对齐 Agora `ToolDetailContent`（参数区 → 结果区）。
- `use_skill` 无信封（输出原始文本）；`memory_tool` 有信封 `{success, id, content}`。
- 详情容器统一用 `ToolDetailContainer`（与 `DefaultToolPreview` 同款滚动区）。

---

### Task 1: 共享详情组件 + `workspace_glob`/`workspace_grep` 渲染器

**Files:**
- Create: `app/src/main/java/me/rerere/rikkahub/ui/components/message/tools/ToolDetailCommon.kt`
- Modify: `app/src/main/java/me/rerere/rikkahub/ui/components/message/tools/WorkspaceToolUIs.kt`（加 GlobToolUI/GrepToolUI）
- Modify: `app/src/main/java/me/rerere/rikkahub/ui/components/message/tools/ToolUI.kt`（注册 glob/grep）
- Modify: `app/src/main/res/values/strings.xml` + `app/src/main/res/values-zh/strings.xml`

**Interfaces:**
- Produces: `ToolDetailContainer`、`ToolPill`、`ToolTerminalOutput`、`formatFileSize`（`ToolDetailCommon.kt`，Task 2-4 消费）；`GlobToolUI`/`GrepToolUI` 注册进 registry（Task 2-4 的 Preview 同文件消费）。

- [ ] **Step 1: 创建 `ToolDetailCommon.kt`**（精确代码；**只含被使用的组件**——YAGNI，勿加未用组件）：

```kotlin
package me.rerere.rikkahub.ui.components.message.tools

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** 详情 BottomSheet 外层容器（与 DefaultToolPreview 一致的滚动区） */
@Composable
internal fun ToolDetailContainer(content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxHeight(0.8f)
            .padding(16.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) { content() }
}

/** 元信息胶囊（Agora MetaPill） */
@Composable
internal fun ToolPill(text: String) {
    Surface(
        shape = RoundedCornerShape(6.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHighest,
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = 7.dp, vertical = 3.dp),
        )
    }
}

/** 终端风格输出块（Agora TerminalOutput）：等宽 + 可选中 */
@Composable
internal fun ToolTerminalOutput(text: String) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.5f),
        shape = RoundedCornerShape(10.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        SelectionContainer {
            Text(
                text = text,
                style = MaterialTheme.typography.bodySmall.copy(
                    fontFamily = FontFamily.Monospace,
                    fontSize = 12.sp,
                    lineHeight = 16.sp,
                ),
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.padding(10.dp),
            )
        }
    }
}

/** 字节数 → 人类可读（512 B / 1.2 KB / 3.4 MB / 1.0 GB） */
internal fun formatFileSize(bytes: Long): String = when {
    bytes < 1024 -> "$bytes B"
    else -> {
        val kb = bytes / 1024.0
        if (kb < 1024) String.format("%.1f KB", kb)
        else {
            val mb = kb / 1024
            if (mb < 1024) String.format("%.1f MB", mb)
            else String.format("%.1f GB", mb / 1024)
        }
    }
}
```

- [ ] **Step 2: 加 `GlobToolUI` + `GrepToolUI`**（追加到 `WorkspaceToolUIs.kt`，复用文件已有的 `jsonObjectOrNull`/`jsonPrimitiveOrNull`/`getStringContent` import，补 `booleanOrNull`/`longOrNull`/`JsonElement`/`SelectionContainer`/`FontFamily` 等需要的 import）：

```kotlin
/**
 * 工作空间 glob: 详情为索引文件列表（路径 + 目录/大小标记）
 */
object GlobToolUI : ToolUIRenderer {
    override val toolName: String = "workspace_glob"

    override fun icon(context: ToolUIContext): ImageVector = HugeIcons.Folder02

    @Composable
    override fun title(context: ToolUIContext): String {
        val pattern = context.arguments.getStringContent("pattern")
        return if (pattern != null) {
            stringResource(R.string.tool_ui_glob, pattern)
        } else {
            stringResource(R.string.tool_ui_glob_default)
        }
    }

    override fun hasSummary(context: ToolUIContext): Boolean = context.content != null

    @Composable
    override fun Summary(context: ToolUIContext) {
        val files = files(context)
        if (files.isNotEmpty()) {
            Text(
                text = stringResource(R.string.tool_ui_glob_count, files.size),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
            )
        }
    }

    @Composable
    override fun Preview(context: ToolUIContext, onDismissRequest: () -> Unit) {
        val content = context.content
        if (content == null) {
            DefaultToolPreview(context = context)
            return
        }
        val files = files(context)
        ToolDetailContainer {
            Text(
                text = context.arguments.getStringContent("pattern") ?: toolName,
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.fillMaxWidth(),
            )
            if (files.isEmpty()) {
                Text(
                    text = stringResource(R.string.tool_ui_glob_empty),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                files.forEachIndexed { index, f ->
                    val isDir = f.boolean("isDirectory") ?: false
                    val size = f.long("sizeBytes")
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text(
                            text = (index + 1).toString(),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.55f),
                            modifier = Modifier.width(28.dp),
                        )
                        SelectionContainer(modifier = Modifier.weight(1f)) {
                            Text(
                                text = f.string("path") ?: f.string("name").orEmpty(),
                                style = MaterialTheme.typography.bodySmall.copy(
                                    fontFamily = FontFamily.Monospace,
                                    fontSize = 12.sp,
                                ),
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                        }
                        if (isDir) {
                            ToolPill(stringResource(R.string.tool_ui_dir))
                        } else if (size != null) {
                            ToolPill(formatFileSize(size))
                        }
                    }
                }
            }
        }
    }

    private fun files(context: ToolUIContext): List<JsonElement> =
        context.content?.jsonObjectOrNull?.get("files")?.jsonArray ?: emptyList()
}
```

（glob 行用内联布局：行号 Text(width 28) + 路径 SelectionContainer(weight 1f) + 目录/大小 pill——不要嵌套 `ToolIndexedLine`，因其内部已含 weight 会与 pill 冲突。）

```kotlin
/**
 * 工作空间 grep: 详情为按路径分组的行号匹配列表
 */
object GrepToolUI : ToolUIRenderer {
    private data class GrepMatch(val path: String, val line: Int?, val text: String)

    override val toolName: String = "workspace_grep"

    override fun icon(context: ToolUIContext): ImageVector = HugeIcons.SearchList

    @Composable
    override fun title(context: ToolUIContext): String {
        val pattern = context.arguments.getStringContent("pattern")
        return if (pattern != null) {
            stringResource(R.string.tool_ui_grep, pattern)
        } else {
            stringResource(R.string.tool_ui_grep_default)
        }
    }

    override fun hasSummary(context: ToolUIContext): Boolean = context.content != null

    @Composable
    override fun Summary(context: ToolUIContext) {
        val matches = matches(context)
        if (matches.isNotEmpty()) {
            Text(
                text = stringResource(R.string.tool_ui_grep_count, matches.size),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
            )
        }
    }

    @Composable
    override fun Preview(context: ToolUIContext, onDismissRequest: () -> Unit) {
        val content = context.content
        if (content == null) {
            DefaultToolPreview(context = context)
            return
        }
        val matches = matches(context)
        ToolDetailContainer {
            Text(
                text = context.arguments.getStringContent("pattern") ?: toolName,
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.fillMaxWidth(),
            )
            if (matches.isEmpty()) {
                Text(
                    text = stringResource(R.string.tool_ui_grep_empty),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                matches.groupBy { it.path }.forEach { (path, pathMatches) ->
                    Text(
                        text = path.ifBlank { stringResource(R.string.tool_ui_file_unknown) },
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.primary,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        pathMatches.forEach { m ->
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.Top,
                            ) {
                                ToolPill(m.line?.toString() ?: "–")
                                Spacer(Modifier.width(8.dp))
                                SelectionContainer(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = m.text,
                                        style = MaterialTheme.typography.bodySmall.copy(
                                            fontFamily = FontFamily.Monospace,
                                            fontSize = 12.sp,
                                        ),
                                        color = MaterialTheme.colorScheme.onSurface,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    private fun matches(context: ToolUIContext): List<GrepMatch> =
        (context.content?.jsonObjectOrNull?.get("matches") as? kotlinx.serialization.json.JsonArray)
            ?.mapNotNull { value ->
                val item = value as? JsonObject ?: return@mapNotNull null
                GrepMatch(
                    path = item.string("path").orEmpty(),
                    line = item.int("line"),
                    text = item.string("text").orEmpty(),
                )
            }
            .orEmpty()
}
```

- [ ] **Step 3: 注册 glob/grep**（`ToolUI.kt` 的 `renderers` 列表追加 `GlobToolUI, GrepToolUI`，注意列表按字母序或就近放 ReadFileToolUI 附近——保持可读即可）：

```kotlin
        RecentChatsToolUI,
        ConversationSearchToolUI,
        GlobToolUI,
        GrepToolUI,
        EditFileToolUI,
```

- [ ] **Step 4: 加字符串**（双写：英文 `values/strings.xml`、中文 `values-zh/strings.xml`，按字母序插入）：

```xml
    <!-- values/strings.xml -->
    <string name="tool_ui_glob">Glob: %s</string>
    <string name="tool_ui_glob_count">%d files</string>
    <string name="tool_ui_glob_default">Glob files</string>
    <string name="tool_ui_glob_empty">No files matched</string>
    <string name="tool_ui_dir">dir</string>
    <string name="tool_ui_grep">Grep: %s</string>
    <string name="tool_ui_grep_count">%d matches</string>
    <string name="tool_ui_grep_default">Grep files</string>
    <string name="tool_ui_grep_empty">No matches</string>
    <string name="tool_ui_file_unknown">(unknown file)</string>
    <!-- values-zh/strings.xml -->
    <string name="tool_ui_glob">Glob：%s</string>
    <string name="tool_ui_glob_count">%d 个文件</string>
    <string name="tool_ui_glob_default">Glob 文件</string>
    <string name="tool_ui_glob_empty">没有匹配的文件</string>
    <string name="tool_ui_dir">目录</string>
    <string name="tool_ui_grep">Grep：%s</string>
    <string name="tool_ui_grep_count">%d 条匹配</string>
    <string name="tool_ui_grep_default">Grep 文件</string>
    <string name="tool_ui_grep_empty">没有匹配</string>
    <string name="tool_ui_file_unknown">（未知文件）</string>
```

- [ ] **Step 5: 静态自检（无编译器）**
- `ToolDetailCommon.kt` 无外部依赖（只 imports compose/material3）；`formatFileSize` 用 `String.format` 无 lint 阻断。
- `GlobToolUI`/`GrepToolUI` 的 `f.boolean`/`f.long`/`f.string`/`item.string`/`item.int` 扩展——`WorkspaceToolUIs.kt` 已有 `JsonElement?.boolean(key)`/`int(key)`/`long(key)`（L417-427），**缺 `string(key)` 需补**：

```kotlin
/** 从工具输出 JSON 读取字符串字段 */
private fun JsonElement?.string(key: String): String? =
    this?.jsonObjectOrNull?.get(key)?.jsonPrimitiveOrNull?.contentOrNull
```
- 图标 `HugeIcons.Folder02`/`HugeIcons.SearchList`——以 CI 编译为准（Plan 1 的 Connect 图标经验）；若名字不对改近义图标。
- registry 引用编译：`GlobToolUI`/`GrepToolUI` 与 `ToolUIRegistry` 同包（`me.rerere.rikkahub.ui.components.message.tools`）。

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/me/rerere/rikkahub/ui/components/message/tools/ToolDetailCommon.kt app/src/main/java/me/rerere/rikkahub/ui/components/message/tools/WorkspaceToolUIs.kt app/src/main/java/me/rerere/rikkahub/ui/components/message/tools/ToolUI.kt app/src/main/res/values/strings.xml app/src/main/res/values-zh/strings.xml
git commit -m "feat(ui): 共享详情组件 + workspace_glob/workspace_grep 语义化渲染器"
```

---

### Task 2: `recent_chats` + `conversation_search` Preview

**Files:**
- Modify: `app/src/main/java/me/rerere/rikkahub/ui/components/message/tools/BuiltinToolUIs.kt`
- Modify: `app/src/main/res/values/strings.xml` + `values-zh/strings.xml`

**Interfaces:**
- Consumes: `ToolDetailContainer`/`ToolPill`（Task 1）。
- Envelope: `recent_chats` → `{type, conversations:[{id,title,last_chat}]}`；`conversation_search` → `{type, query, results:[{title, conversation_id, top_score, match_count, messages:[{participant,text,timestamp}]}]}`（已核实 `ConversationTools.kt`）。

- [ ] **Step 1: `RecentChatsToolUI.Preview`**（对象内加 override，保留既有 `chats(context)`/`Summary`）

```kotlin
    @Composable
    override fun Preview(context: ToolUIContext, onDismissRequest: () -> Unit) {
        val content = context.content
        if (content == null) {
            DefaultToolPreview(context = context)
            return
        }
        val chats = chats(context)
        ToolDetailContainer {
            if (chats.isEmpty()) {
                Text(
                    text = stringResource(R.string.tool_ui_recent_chats_empty),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                chats.forEach { c ->
                    Column(modifier = Modifier.fillMaxWidth()) {
                        Text(
                            text = c.getStringContent("title") ?: stringResource(R.string.tool_ui_untitled),
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                        c.getStringContent("last_chat")?.let { lastChat ->
                            Text(
                                text = lastChat,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        }
    }
```

- [ ] **Step 2: `ConversationSearchToolUI.Preview`**（保留既有 `results(context)`/`Summary`；每条 result 显示 title + match_count pill + 前 3 条消息片段）

```kotlin
    @Composable
    override fun Preview(context: ToolUIContext, onDismissRequest: () -> Unit) {
        val content = context.content
        if (content == null) {
            DefaultToolPreview(context = context)
            return
        }
        val results = results(context)
        ToolDetailContainer {
            if (results.isEmpty()) {
                Text(
                    text = stringResource(R.string.tool_ui_conv_search_empty),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                results.forEach { r ->
                    val title = r.getStringContent("title") ?: stringResource(R.string.tool_ui_untitled)
                    val matchCount = (r.jsonObjectOrNull?.get("match_count") as? JsonPrimitive)?.contentOrNull?.toIntOrNull()
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text(
                            text = title,
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f),
                        )
                        if (matchCount != null && matchCount > 0) {
                            ToolPill(stringResource(R.string.tool_ui_match_count, matchCount))
                        }
                    }
                    // 前 3 条消息片段
                    (r.jsonObjectOrNull?.get("messages") as? JsonArray)
                        ?.take(3)
                        ?.forEach { m ->
                            val text = m.getStringContent("text").orEmpty()
                            if (text.isNotBlank()) {
                                Text(
                                    text = text.take(120),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                }
            }
        }
    }
```

- [ ] **Step 3: 加字符串**（双写，字母序）：

```xml
    <!-- values/ -->
    <string name="tool_ui_recent_chats_empty">No conversations</string>
    <string name="tool_ui_untitled">Untitled</string>
    <string name="tool_ui_conv_search_empty">No conversations found</string>
    <string name="tool_ui_match_count">%d matches</string>
    <!-- values-zh/ -->
    <string name="tool_ui_recent_chats_empty">没有会话</string>
    <string name="tool_ui_untitled">无标题</string>
    <string name="tool_ui_conv_search_empty">没有找到会话</string>
    <string name="tool_ui_match_count">%d 条匹配</string>
```

- [ ] **Step 4: 静态自检** — `chats(context)`/`results(context)` 已是 private 函数返回 `JsonArray`；`r.jsonObjectOrNull`（`me.rerere.common.http.jsonObjectOrNull` 已在文件 import）；`m.getStringContent`（`ToolUI.kt` 的 internal 扩展，同包）。确认 `JsonArray`/`JsonPrimitive` import 存在。
- [ ] **Step 5: Commit**（只 `BuiltinToolUIs.kt` + 两个 strings.xml）

```bash
git add app/src/main/java/me/rerere/rikkahub/ui/components/message/tools/BuiltinToolUIs.kt app/src/main/res/values/strings.xml app/src/main/res/values-zh/strings.xml
git commit -m "feat(ui): recent_chats/conversation_search 详情视图（会话列表 + 结果卡片）"
```

---

### Task 3: `get_screen_time` + `calendar_query` + `calendar_create` Preview

**Files:**
- Modify: `app/src/main/java/me/rerere/rikkahub/ui/components/message/tools/BuiltinToolUIs.kt`
- Modify: `app/src/main/res/values/strings.xml` + `values-zh/strings.xml`

**Interfaces:**
- Consumes: `ToolDetailContainer`/`ToolPill`（Task 1）。
- Envelope（已核实）：`get_screen_time` → `{type, range, start, end, total_ms, total_minutes, apps:[{package, app_name, total_ms, total_minutes}]}`；`calendar_query` → `{type, range_start, range_end, count, events:[{id,title,start,all_day,calendar}]}`；`calendar_create` → `{type, event_id, title, start, end}`（实现时以 producer `CalendarTool.kt` 实际字段为准，event 至少含 title/start）。

- [ ] **Step 1: `GetScreenTimeToolUI.Preview`**（总时长 + 各 app 行：app_name + 分钟数，可选相对总时长的进度条）

```kotlin
    @Composable
    override fun Preview(context: ToolUIContext, onDismissRequest: () -> Unit) {
        val content = context.content
        if (content == null) {
            DefaultToolPreview(context = context)
            return
        }
        val apps = (content.jsonObjectOrNull?.get("apps") as? JsonArray).orEmpty()
        val totalMinutes = content.getStringContent("total_minutes")?.toIntOrNull()
        ToolDetailContainer {
            if (totalMinutes != null) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ToolPill(stringResource(R.string.tool_ui_screen_total, totalMinutes))
                }
            }
            if (apps.isEmpty()) {
                Text(
                    text = stringResource(R.string.tool_ui_screen_empty),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                apps.forEach { app ->
                    val name = app.getStringContent("app_name") ?: app.getStringContent("package") ?: "?"
                    val minutes = app.getStringContent("total_minutes")?.toIntOrNull()
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text(
                            text = name,
                            style = MaterialTheme.typography.bodyMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f),
                        )
                        if (minutes != null) {
                            Text(
                                text = stringResource(R.string.tool_ui_minutes, minutes),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        }
    }
```

- [ ] **Step 2: `CalendarQueryToolUI.Preview`**（count pill + 事件列表：title + start + all_day/calendar 标记）

```kotlin
    @Composable
    override fun Preview(context: ToolUIContext, onDismissRequest: () -> Unit) {
        val content = context.content
        if (content == null) {
            DefaultToolPreview(context = context)
            return
        }
        val events = (content.jsonObjectOrNull?.get("events") as? JsonArray).orEmpty()
        ToolDetailContainer {
            if (events.isEmpty()) {
                Text(
                    text = stringResource(R.string.tool_ui_calendar_empty),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                events.forEach { ev ->
                    val title = ev.getStringContent("title") ?: stringResource(R.string.tool_ui_untitled)
                    val start = ev.getStringContent("start")
                    val allDay = (ev.jsonObjectOrNull?.get("all_day") as? JsonPrimitive)?.contentOrNull == "true"
                    val calendar = ev.getStringContent("calendar")
                    Column(modifier = Modifier.fillMaxWidth()) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(
                                text = title,
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.SemiBold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f),
                            )
                            if (allDay) ToolPill(stringResource(R.string.tool_ui_all_day))
                        }
                        start?.let {
                            Text(
                                text = it,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        calendar?.takeIf { it.isNotBlank() }?.let {
                            Text(
                                text = it,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                            )
                        }
                    }
                }
            }
        }
    }
```

- [ ] **Step 3: `CalendarCreateToolUI.Preview`**（创建确认：title + start + end + event_id）

```kotlin
    @Composable
    override fun Preview(context: ToolUIContext, onDismissRequest: () -> Unit) {
        val content = context.content
        if (content == null) {
            DefaultToolPreview(context = context)
            return
        }
        val title = content.getStringContent("title") ?: stringResource(R.string.tool_ui_untitled)
        val start = content.getStringContent("start")
        val end = content.getStringContent("end")
        val eventId = content.getStringContent("event_id")
        ToolDetailContainer {
            Text(
                text = stringResource(R.string.tool_ui_event_created, title),
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
            )
            start?.let { Text(stringResource(R.string.tool_ui_event_start, it), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            end?.let { Text(stringResource(R.string.tool_ui_event_end, it), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            eventId?.let { ToolPill(stringResource(R.string.tool_ui_event_id, it)) }
        }
    }
```

- [ ] **Step 4: 加字符串**（双写，字母序）：

```xml
    <!-- values/ -->
    <string name="tool_ui_screen_total">Total %1$d min</string>
    <string name="tool_ui_screen_empty">No screen time</string>
    <string name="tool_ui_minutes">%1$d min</string>
    <string name="tool_ui_calendar_empty">No events</string>
    <string name="tool_ui_all_day">all day</string>
    <string name="tool_ui_event_created">Created: %1$s</string>
    <string name="tool_ui_event_start">Start: %1$s</string>
    <string name="tool_ui_event_end">End: %1$s</string>
    <string name="tool_ui_event_id">id %1$s</string>
    <!-- values-zh/ -->
    <string name="tool_ui_screen_total">共 %1$d 分钟</string>
    <string name="tool_ui_screen_empty">无屏幕时间</string>
    <string name="tool_ui_minutes">%1$d 分钟</string>
    <string name="tool_ui_calendar_empty">无事件</string>
    <string name="tool_ui_all_day">全天</string>
    <string name="tool_ui_event_created">已创建：%1$s</string>
    <string name="tool_ui_event_start">开始：%1$s</string>
    <string name="tool_ui_event_end">结束：%1$s</string>
    <string name="tool_ui_event_id">id %1$s</string>
```

- [ ] **Step 5: 静态自检** — 确认 `GetScreenTimeToolUI`/`CalendarQueryToolUI`/`CalendarCreateToolUI` 现有的 `Summary`/`hasSummary` 读取的字段（apps/events）与 Preview 一致；`app.getStringContent`/`ev.getStringContent` 用 `getStringContent`（同包 internal 扩展）。calendar_create producer 实际字段以 `CalendarTool.kt` 为准（event_id/title/start/end），若有出入按实际字段调整。
- [ ] **Step 6: Commit**（同 Task 2 Step 5 文件集）

```bash
git add app/src/main/java/me/rerere/rikkahub/ui/components/message/tools/BuiltinToolUIs.kt app/src/main/res/values/strings.xml app/src/main/res/values-zh/strings.xml
git commit -m "feat(ui): get_screen_time/calendar_query/calendar_create 详情视图（app 列表/事件列表/创建确认）"
```

---

### Task 4: `get_time_info` + `clipboard_tool` + `use_skill` + `memory_tool` Preview

**Files:**
- Modify: `app/src/main/java/me/rerere/rikkahub/ui/components/message/tools/BuiltinToolUIs.kt`
- Modify: `app/src/main/res/values/strings.xml` + `values-zh/strings.xml`

**Interfaces:**
- Consumes: `ToolDetailContainer`/`ToolPill`/`ToolTerminalOutput`（Task 1）。
- Envelope（已核实）：`get_time_info` → `{type, year, month, day, weekday, weekday_en, weekday_index, date, time, datetime, timezone, utc_offset, timestamp_ms}`；`clipboard_tool` → `{type, text}`（read）/`{type, success, text}`（write）；`memory_tool` → `{success, id, content}`（create/edit 有 content，delete 无）；`use_skill` → **无信封**，输出是 `UIMessagePart.Text` 原始文本。

- [ ] **Step 1: `GetTimeInfoToolUI.Preview`**（日期 + 星期 pill + 时间/时区行）

```kotlin
    @Composable
    override fun Preview(context: ToolUIContext, onDismissRequest: () -> Unit) {
        val content = context.content
        if (content == null) {
            DefaultToolPreview(context = context)
            return
        }
        val date = content.getStringContent("date")
        val time = content.getStringContent("time")
        val weekday = content.getStringContent("weekday")
        val timezone = content.getStringContent("timezone")
        val utcOffset = content.getStringContent("utc_offset")
        ToolDetailContainer {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = date ?: stringResource(R.string.tool_ui_time_default),
                    style = MaterialTheme.typography.titleMedium,
                )
                weekday?.takeIf { it.isNotBlank() }?.let { ToolPill(it) }
            }
            if (time != null) {
                Text(
                    text = time,
                    style = MaterialTheme.typography.bodyMedium,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
            if (timezone != null || utcOffset != null) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    timezone?.takeIf { it.isNotBlank() }?.let { ToolPill(it) }
                    utcOffset?.takeIf { it.isNotBlank() }?.let { ToolPill(it) }
                }
            }
        }
    }
```

- [ ] **Step 2: `ClipboardToolUI.Preview`**（读：剪贴板文本；写：成功确认 + 文本）

```kotlin
    @Composable
    override fun Preview(context: ToolUIContext, onDismissRequest: () -> Unit) {
        val content = context.content
        if (content == null) {
            DefaultToolPreview(context = context)
            return
        }
        val text = content.getStringContent("text")
        val action = context.arguments.getStringContent("action")
        ToolDetailContainer {
            if (action == "read") {
                if (text.isNullOrBlank()) {
                    Text(
                        text = stringResource(R.string.tool_ui_clipboard_empty),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    ToolTerminalOutput(text)
                }
            } else {
                Text(
                    text = stringResource(R.string.tool_ui_clipboard_written),
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                text?.takeIf { it.isNotBlank() }?.let { ToolTerminalOutput(it) }
            }
        }
    }
```

- [ ] **Step 3: `UseSkillToolUI.Preview`**（skill 结果原始文本；无信封）

```kotlin
    @Composable
    override fun Preview(context: ToolUIContext, onDismissRequest: () -> Unit) {
        val output = context.tool.output.filterIsInstance<UIMessagePart.Text>()
            .joinToString("\n") { it.text }
            .trim()
        if (output.isBlank()) {
            DefaultToolPreview(context = context)
            return
        }
        ToolDetailContainer {
            ToolTerminalOutput(output)
        }
    }
```

- [ ] **Step 4: `MemoryToolUI.Preview`**（把 `DefaultToolPreview` 换成内容视图；**保留删除按钮**；create/edit 显示 content，delete 显示"已删除"。具体布局：外层 `Column(fillMaxHeight(0.8f))` = `ToolDetailContainer`（内容区）+ 底部 `Row(End)`（删除按钮））：

```kotlin
    @Composable
    override fun Preview(context: ToolUIContext, onDismissRequest: () -> Unit) {
        val memoryRepo: MemoryRepository = koinInject()
        val scope = rememberCoroutineScope()
        val memoryId = (context.content as? JsonObject)?.get("id")?.jsonPrimitiveOrNull?.intOrNull
        val content = context.content?.getStringContent("content")
        val canDelete = action(context) in listOf(ACTION_CREATE, ACTION_EDIT) && memoryId != null
        Column(modifier = Modifier.fillMaxHeight(0.8f)) {
            ToolDetailContainer {
                when (action(context)) {
                    ACTION_CREATE, ACTION_EDIT -> {
                        if (!content.isNullOrBlank()) {
                            Text(
                                text = content,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                        }
                        if (memoryId != null) {
                            ToolPill(stringResource(R.string.tool_ui_memory_id, memoryId))
                        }
                    }
                    ACTION_DELETE -> Text(
                        text = stringResource(R.string.tool_ui_memory_deleted),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    else -> DefaultToolPreview(context = context)
                }
            }
            if (canDelete) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.End,
                ) {
                    IconButton(
                        onClick = {
                            scope.launch {
                                memoryRepo.deleteMemory(memoryId!!)
                                onDismissRequest()
                            }
                        }
                    ) {
                        Icon(
                            imageVector = HugeIcons.Delete01,
                            contentDescription = stringResource(R.string.tool_ui_delete_memory),
                        )
                    }
                }
            }
        }
    }
```

（删除按钮在 `ToolDetailContainer` 之外、`Column` 底部——保证可达且不随滚动内容消失。`memoryId!!` 因 `canDelete` 已判非空而安全。）

- [ ] **Step 5: 加字符串**（双写，字母序）：

```xml
    <!-- values/ -->
    <string name="tool_ui_time_default">Time</string>
    <string name="tool_ui_clipboard_empty">Clipboard is empty</string>
    <string name="tool_ui_clipboard_written">Copied to clipboard</string>
    <string name="tool_ui_memory_id">id %1$d</string>
    <string name="tool_ui_memory_deleted">Memory deleted</string>
    <!-- values-zh/ -->
    <string name="tool_ui_time_default">时间</string>
    <string name="tool_ui_clipboard_empty">剪贴板为空</string>
    <string name="tool_ui_clipboard_written">已复制到剪贴板</string>
    <string name="tool_ui_memory_id">id %1$d</string>
    <string name="tool_ui_memory_deleted">记忆已删除</string>
```

- [ ] **Step 6: 静态自检** — `UIMessagePart`/`UIMessagePart.Text` import；`koinInject`/`rememberCoroutineScope`/`IconButton` 已在文件；`context.arguments.getStringContent`（ToolUI.kt internal 扩展）；memory 删除按钮逻辑与现状一致（原 `DefaultToolPreview(headerActions=...)` 迁移到新布局）。
- [ ] **Step 7: Commit**（同 Task 2/3 文件集）

```bash
git add app/src/main/java/me/rerere/rikkahub/ui/components/message/tools/BuiltinToolUIs.kt app/src/main/res/values/strings.xml app/src/main/res/values-zh/strings.xml
git commit -m "feat(ui): get_time_info/clipboard/use_skill/memory 详情视图"
```

---

### Task 5: `DefaultToolPreview` JSON → `JsonTreeView` 树形兜底（对齐 Agora `JsonNodeView`）

> 用户 2026-08-02 拍板：无专门视图的工具（MCP/未注册）兜底也用 Agora 式 JSON 树，不要一坨 JSON。

**Files:**
- Create: `app/src/main/java/me/rerere/rikkahub/ui/components/message/tools/JsonTreeView.kt`
- Modify: `app/src/main/java/me/rerere/rikkahub/ui/components/message/tools/ToolUI.kt`（`DefaultToolPreview` 的 JSON 渲染换树形）

**Interfaces:**
- Produces: `@Composable internal fun JsonTreeView(json: JsonElement)`（Task 6 兜底引用）。
- Design 参照: `references/Agora/app/src/main/java/com/newoether/agora/ui/chat/message/MessageItemJson.kt` 的 `JsonNodeView`。

- [ ] **Step 1: 创建 `JsonTreeView.kt`**（镜像 Agora `JsonNodeView`：键 chips + 内联值 + 长/多行字符串单独一行 + 嵌套缩进 + 可选中）：

```kotlin
package me.rerere.rikkahub.ui.components.message.tools

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** Agora JsonNodeView 对齐：JSON 树形视图（键 chips + 内联值 + 嵌套缩进 + 可选中）。 */
@Composable
internal fun JsonTreeView(json: JsonElement) {
    SelectionContainer {
        Column(modifier = Modifier.fillMaxWidth()) {
            when (json) {
                is JsonObject -> JsonTreeObjectView(json, 0)
                is JsonArray -> JsonTreeArrayView(json, 0)
                is JsonPrimitive -> JsonTreePrimitiveView(json, Modifier.fillMaxWidth())
                is JsonNull -> Text(
                    text = "—",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/** 长(>40)或含换行的字符串值单独一行展示，避免被压成窄列。 */
private fun isBlockString(value: JsonElement): Boolean =
    value is JsonPrimitive && value.isString &&
        (value.content.length > 40 || value.content.contains('\n'))

@Composable
private fun JsonTreeKeyChip(label: String, color: Color) {
    Surface(
        shape = RoundedCornerShape(4.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHighest,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = color,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
        )
    }
}

@Composable
private fun JsonTreeObjectView(obj: JsonObject, depth: Int) {
    Column(modifier = Modifier.fillMaxWidth()) {
        obj.entries.forEach { (key, value) ->
            val blockString = isBlockString(value)
            Column(modifier = Modifier.fillMaxWidth().padding(vertical = 1.dp)) {
                Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
                    JsonTreeKeyChip(key, MaterialTheme.colorScheme.primary)
                    if (!blockString) {
                        Spacer(Modifier.width(8.dp))
                        when (value) {
                            is JsonPrimitive -> JsonTreePrimitiveView(value, Modifier.weight(1f))
                            is JsonNull -> Text(
                                text = "—",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            is JsonObject -> Text(
                                text = "{…}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            is JsonArray -> Text(
                                text = "[…]",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
                if (blockString && value is JsonPrimitive) {
                    JsonTreePrimitiveView(value, Modifier.fillMaxWidth().padding(top = 2.dp))
                }
                when (value) {
                    is JsonObject -> Box(
                        modifier = Modifier.padding(start = ((depth + 1) * 16).dp).padding(top = 2.dp),
                    ) { JsonTreeObjectView(value, depth + 1) }
                    is JsonArray -> Box(
                        modifier = Modifier.padding(start = ((depth + 1) * 16).dp).padding(top = 2.dp),
                    ) { JsonTreeArrayView(value, depth + 1) }
                    else -> {}
                }
            }
        }
    }
}

@Composable
private fun JsonTreeArrayView(arr: JsonArray, depth: Int) {
    Column(modifier = Modifier.fillMaxWidth()) {
        arr.forEachIndexed { i, item ->
            Row(
                modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
                verticalAlignment = Alignment.Top,
            ) {
                JsonTreeKeyChip((i + 1).toString(), MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.width(8.dp))
                when (item) {
                    is JsonPrimitive -> JsonTreePrimitiveView(item, Modifier.weight(1f))
                    is JsonNull -> Text(
                        text = "—",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    is JsonObject -> Box(Modifier.weight(1f)) { JsonTreeObjectView(item, depth) }
                    is JsonArray -> Box(Modifier.weight(1f)) { JsonTreeArrayView(item, depth) }
                }
            }
        }
    }
}

@Composable
private fun JsonTreePrimitiveView(primitive: JsonPrimitive, modifier: Modifier = Modifier) {
    val color = when {
        primitive.isString -> MaterialTheme.colorScheme.onSurface
        else -> MaterialTheme.colorScheme.tertiary
    }
    Text(
        text = primitive.content,
        style = MaterialTheme.typography.bodySmall.copy(
            fontFamily = FontFamily.Monospace,
            fontSize = 12.sp,
        ),
        color = color,
        maxLines = if (primitive.isString) 4 else 1,
        overflow = TextOverflow.Ellipsis,
        modifier = modifier,
    )
}
```

- [ ] **Step 2: 改 `DefaultToolPreview`**（`ToolUI.kt`）——Arguments 与输出 Text 部件的 JSON 渲染从 `HighlightCodeBlock` 整块 pretty-JSON 换成 `JsonTreeView`：
  - Arguments（L152-156）：`HighlightCodeBlock(code = JsonInstantPretty.encodeToString(context.arguments), language = "json", ...)` → `JsonTreeView(context.arguments)`
  - 输出 Text 部件（L167-175）：`runCatching { JsonInstantPretty.encodeToString(JsonInstant.parseToJsonElement(part.text)) }.getOrElse { part.text }` → 改为：
    ```kotlin
    is UIMessagePart.Text -> {
        val parsed = runCatching { JsonInstant.parseToJsonElement(part.text) }.getOrNull()
        if (parsed != null) {
            JsonTreeView(parsed)
        } else {
            HighlightCodeBlock(
                code = part.text,
                language = "plaintext",
                style = TextStyle(fontSize = 10.sp, lineHeight = 12.sp),
            )
        }
    }
    ```
  - `Image` 部件（L177-181）不变。

- [ ] **Step 3: 静态自检** — `JsonTreeView.kt` 只依赖 compose/material3/kotlinx.serialization（无项目内部依赖）；`Modifier.weight(1f)` 在 `Row` 的 RowScope 内（`JsonTreeObjectView`/`JsonTreeArrayView` 的 Row lambda）；`SelectionContainer` 包整棵 Column；`primitive.isString`/`primitive.content` 是 `JsonPrimitive` 成员；`is JsonNull` 分支存在。
- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/me/rerere/rikkahub/ui/components/message/tools/JsonTreeView.kt app/src/main/java/me/rerere/rikkahub/ui/components/message/tools/ToolUI.kt
git commit -m "feat(ui): DefaultToolPreview JSON 树形兜底（JsonTreeView 对齐 Agora JsonNodeView）"
```

---

### Task 6: 日历错误信封兜底修复 + 字符串 sweep + 全量静态 review + 最终 CI

**Files:**
- Review only（各任务产物）。
- 若 review 发现遗漏：修 `BuiltinToolUIs.kt`/`WorkspaceToolUIs.kt`/strings。

**Interfaces:** 无新接口——纯验证 + 一个小的错误兜底修复。

- [ ] **Step 1: 日历错误信封兜底修复**（Task 3 review Important，plan-mandated 修正）

`CalendarQueryToolUI.Preview`/`CalendarCreateToolUI.Preview` 对错误信封（`NO_PERMISSION`/`INVALID_*`/`NO_CALENDAR`/`INSERT_FAILED`，带 `error` key 无 `events`/`event_id`）会显示"No events"/"Created: Untitled"——**把失败的插入误报成成功**。给两个 Preview 加 `error != null → DefaultToolPreview` 兜底（镜像 `GetScreenTimeToolUI.Preview` 的 L593 模式）：

```kotlin
// CalendarQueryToolUI.Preview 开头
val content = context.content
if (content == null || content.getStringContent("error") != null) {
    DefaultToolPreview(context = context)
    return
}
// CalendarCreateToolUI.Preview 开头同理
val content = context.content
if (content == null || content.getStringContent("error") != null) {
    DefaultToolPreview(context = context)
    return
}
```

- [ ] **Step 2: 全量静态 review**
- 每个工具点进详情：`workspace_glob`（索引文件列表）、`workspace_grep`（按路径分组行号匹配）、`recent_chats`（会话列表）、`conversation_search`（结果卡片 + match_count + 消息片段）、`get_screen_time`（app 列表 + 总时长）、`calendar_query`（事件列表）、`calendar_create`（创建确认）、`get_time_info`（日期 + 时间 + 时区）、`clipboard_tool`（剪贴板文本）、`use_skill`（skill 输出）、`memory_tool`（记忆内容 + 删除按钮）。
- 信封 key 逐一对照 producer（`WorkspaceTools.kt`/`ConversationTools.kt`/`ScreenTimeTool.kt`/`CalendarTool.kt`/`TimeInfoTool.kt`/`ClipboardTool.kt`/`MemoryTools.kt`/`SkillsTools.kt`）。
- 字符串双写完整、无重复 key、字母序。
- MCP/未注册工具走 `DefaultToolPreview` → `JsonTreeView`（Task 5）树形 JSON 兜底，符合 Agora 行为。

- [ ] **Step 3: 最终 CI**

```bash
git push origin master
gh workflow run nightly-build-debug.yml --repo DevLintMar/rikkahub --ref master
gh run watch <RUN_ID> --repo DevLintMar/rikkahub --exit-status
```

期望 `Gradle Build` 通过。若报错修完重新 push + 触发。

- [ ] **Step 4: 手动验证清单**（装 debug APK）
1. 让 AI 调 `workspace_glob`/`workspace_grep` → 点进去分别是文件列表 / 行号匹配，不再是 JSON。
2. `conversation_search`/`recent_chats`/`get_screen_time`/`calendar_query` → 语义列表。
3. `get_time_info`/`clipboard_tool`/`use_skill`/`memory_tool` → 时间/文本/删除确认。
4. MCP 工具 → JSON 树形兜底（`JsonTreeView`，Task 5）。

- [ ] **Step 5: Commit（若 review 有修复）**——按需。

---

## Self-Review 结论

- **Spec 覆盖**：用户全量范围——glob/grep 新渲染器（Task 1）、9 个既有渲染器 Preview（Task 2-4）、MCP/未知工具 JsonTreeView 树形兜底（Task 5，用户 2026-08-02 拍板）、日历错误信封兜底（Task 6）、字符串双写、最终 CI（Task 6）。
- **类型一致性**：`ToolDetailContainer`/`ToolPill`/`ToolTerminalOutput`/`formatFileSize`（Task 1）被 Task 2-4 消费；`JsonTreeView`（Task 5）被 `DefaultToolPreview` 消费；envelope key 已逐个核实。
- **无占位符**；每任务含具体代码或精确布局 + key。
- **已知风险**：`HugeIcons.Folder02`/`SearchList` 图标名以 CI 为准（Task 1 已验证：Folder02 通过、SearchList 改 Search01）；memory 删除按钮布局（Task 4 已注）；calendar_create event 字段以 producer 实际为准（Task 3 已核）。
- **MCP/未知工具兜底 = `JsonTreeView` 树形 JSON**（对齐 Agora `JsonNodeView`，用户拍板），不再是整块 JSON。
