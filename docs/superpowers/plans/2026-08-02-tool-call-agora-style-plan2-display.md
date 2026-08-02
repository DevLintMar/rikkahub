# RikkaHub 工具调用对齐 Agora — Plan 2（展示层）Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 把工具调用展示改为 Agora 式 **grouped timeline**：聚合折叠头「🧠 AiBrain02 思考X秒·调用X个工具 ▸」→ 展开列表（每段显示名 + 一行概览）→ 点击进详情；保留每工具小图标；修复 Plan 1 留下的 4 处展示回归。

**Architecture:** 新增中心解析层 `ToolPresentationResolver`（信封 → kind/state/subject/count）+ `toolSummary` 一行概览；`ChainOfThought` 加可选 `header` 聚合模式（Export 不受影响）；`ChatMessageToolStep` 改聚合列表行（一行概览 + 点击进详情）；修复 BuiltinToolUIs 里 4 个读旧形状的渲染器。**展示层只消费 Plan 1 产出的 `type` 信封 + `toolState`，不改任何数据流。**

**Tech Stack:** Jetpack Compose, Material3, HugeIcons（`AiBrain02`/`ArrowUp01`/`ArrowDown01`）, kotlinx.serialization。

## Global Constraints

- **本机无编译器**：不运行 gradle。所有代码静态编写 + review；真实编译靠 CI（nightly-build-debug，先 push 再触发，gh 需 `--repo DevLintMar/rikkahub`）。
- **单测不可本地运行**：CI `assembleDebug` 不编译测试源码；纯函数测试为将来本地运行而写。
- **CARRY-TO-PLAN2（本计划必须修复的展示回归）**：① `ScrapeWebPreview` 读旧 `urls` key（信封后空）；② `SearchWebToolUI` 的 answer 卡（信封丢弃 answer 字段）；③ `RecentChatsToolUI` 读旧裸数组（信封后空）；④ `ConversationSearchToolUI` 读旧裸数组（信封后空）。
- **展示 key 基于 `tool.toolName`（字面工具名）**，不用信封 `type`——规避 Plan 1 的 `search_web`/`web_fetch` 语义名 vs 字面名不一致（用户可接受：`toolName` 始终可用）。
- 保留每工具小图标（`ToolUIRenderer.icon`）；聚合头图标 `HugeIcons.AiBrain02`；箭头 `HugeIcons.ArrowUp01`/`ArrowDown01` 在**最右**。
- 字符串走 `strings.xml`，遵循 `tool_ui_*` / `chat_message_tool_*` / `chain_of_thought_*` 现有模式。
- **字符串语言归属（用户 2026-08-02 拍板）**：英文默认串进 `values/strings.xml`，中文翻译进 `values-zh/strings.xml`（照现有 `chain_of_thought_*` 模式；ja/ko/ru 不新增，回退英文默认）。本计划所有新增字符串（Task 1 的 `tool_summary_*`、Task 3 的 `chain_of_thought_aggregate`/`chain_of_thought_calling`）都按此双写。
- 不改任何数据流/模型侧（Plan 1 已冻结）。

---

### Task 1: `ToolPresentation` 解析器 + `toolSummary` 一行概览（新增文件）

**Files:**
- Create: `app/src/main/java/me/rerere/rikkahub/ui/components/message/tools/ToolPresentation.kt`
- Test: `app/src/test/java/me/rerere/rikkahub/data/ai/tools/ToolPresentationTest.kt`（新建）

**Interfaces:**
- Produces: `ToolKind` 枚举、`ToolPresentation` 数据类、`ToolPresentationResolver.kindFor(toolName)`、`ToolPresentationResolver.resolve(tool)`、`@Composable fun toolSummary(presentation): String`。Task 3/4 依赖。

- [ ] **Step 1: 写失败测试**

```kotlin
package me.rerere.rikkahub.data.ai.tools

import me.rerere.ai.ui.ToolState
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.ui.components.message.tools.ToolKind
import me.rerere.rikkahub.ui.components.message.tools.ToolPresentationResolver
import org.junit.Assert.assertEquals
import org.junit.Test

class ToolPresentationTest {

    private fun tool(name: String, output: String = """{"type":"$name"}"""): UIMessagePart.Tool =
        UIMessagePart.Tool(
            toolCallId = "t1",
            toolName = name,
            input = "{}",
            toolState = ToolState.SUCCEEDED,  // 已完成工具（真实中由 GenerationHandler 写入）
            output = if (output.isBlank()) emptyList() else listOf(UIMessagePart.Text(output)),
        )

    @Test
    fun `kindFor maps literal tool names`() {
        assertEquals(ToolKind.WEB_SEARCH, ToolPresentationResolver.kindFor("search_web"))
        assertEquals(ToolKind.WEB_FETCH, ToolPresentationResolver.kindFor("scrape_web"))
        assertEquals(ToolKind.CONVERSATION_SEARCH, ToolPresentationResolver.kindFor("conversation_search"))
        assertEquals(ToolKind.SHELL_EXECUTE, ToolPresentationResolver.kindFor("workspace_shell"))
        assertEquals(ToolKind.UNKNOWN, ToolPresentationResolver.kindFor("mcp_tool"))
    }

    @Test
    fun `resolve reads subject and count from envelope`() {
        val t = tool(
            "conversation_search",
            """{"type":"conversation_search","query":"番茄","results":[{"title":"a"},{"title":"b"}]}""",
        )
        val p = ToolPresentationResolver.resolve(t)
        assertEquals(ToolKind.CONVERSATION_SEARCH, p.kind)
        assertEquals("番茄", p.subject)
        assertEquals(2, p.count)
    }

    @Test
    fun `resolve state comes from toolState with empty refinement`() {
        val empty = tool("recent_chats", """{"type":"recent_chats","conversations":[]}""")
        assertEquals(ToolState.EMPTY, ToolPresentationResolver.resolve(empty).state)
        val failed = tool("workspace_shell", """{"type":"workspace_shell","exitCode":1}""")
        assertEquals(ToolState.FAILED, ToolPresentationResolver.resolve(failed).state)
    }
}
```

- [ ] **Step 2: Verify it fails（静态）** — 引用的 `ToolKind`/`ToolPresentationResolver` 不存在。

- [ ] **Step 3: 实现**

`app/src/main/java/me/rerere/rikkahub/ui/components/message/tools/ToolPresentation.kt`：

```kotlin
package me.rerere.rikkahub.ui.components.message.tools

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import me.rerere.ai.ui.ToolState
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.ai.tools.parseEnvelope

/** 工具种类（展示分流用；MCP/未知 → UNKNOWN） */
enum class ToolKind {
    WEB_SEARCH, WEB_FETCH,
    CONVERSATION_LIST, CONVERSATION_SEARCH, CONVERSATION_READ,
    SHELL_EXECUTE, FILE_READ, FILE_WRITE, FILE_EDIT, FILE_GLOB, FILE_GREP,
    CLIPBOARD, TEXT_TO_SPEECH, SCREEN_TIME, CALENDAR_QUERY, CALENDAR_CREATE, TIME_INFO,
    EVAL_JAVASCRIPT, RUN_WORKFLOW, SUB_AGENT,
    UNKNOWN,
}

/** 工具展示结构化对象 */
data class ToolPresentation(
    val toolName: String,
    val kind: ToolKind,
    val state: ToolState,
    val subject: String?,
    val count: Int?,
    val errorMessage: String?,
    val exitCode: Int?,
)

object ToolPresentationResolver {

    /** 基于字面工具名映射种类（规避信封 type 的语义名/字面名不一致）。 */
    fun kindFor(toolName: String): ToolKind = when (toolName) {
        "search_web" -> ToolKind.WEB_SEARCH
        "scrape_web" -> ToolKind.WEB_FETCH
        "recent_chats" -> ToolKind.CONVERSATION_LIST
        "conversation_search" -> ToolKind.CONVERSATION_SEARCH
        "read_conversation" -> ToolKind.CONVERSATION_READ
        "workspace_shell" -> ToolKind.SHELL_EXECUTE
        "workspace_read_file" -> ToolKind.FILE_READ
        "workspace_write_file" -> ToolKind.FILE_WRITE
        "workspace_edit_file" -> ToolKind.FILE_EDIT
        "workspace_glob" -> ToolKind.FILE_GLOB
        "workspace_grep" -> ToolKind.FILE_GREP
        "clipboard_tool" -> ToolKind.CLIPBOARD
        "text_to_speech" -> ToolKind.TEXT_TO_SPEECH
        "get_screen_time" -> ToolKind.SCREEN_TIME
        "calendar_query" -> ToolKind.CALENDAR_QUERY
        "calendar_create" -> ToolKind.CALENDAR_CREATE
        "get_time_info" -> ToolKind.TIME_INFO
        "eval_javascript" -> ToolKind.EVAL_JAVASCRIPT
        "run_workflow" -> ToolKind.RUN_WORKFLOW
        "sub_agent" -> ToolKind.SUB_AGENT
        else -> ToolKind.UNKNOWN
    }

    fun resolve(tool: UIMessagePart.Tool): ToolPresentation {
        val kind = kindFor(tool.toolName)
        val envelope = parseEnvelope(tool.output)
        val errorMessage = envelope?.get("error")
            ?.let { (it as? JsonPrimitive)?.contentOrNull }
            ?.takeIf { it.isNotBlank() }
        val exitCode = (envelope?.get("exitCode") as? JsonPrimitive)?.intOrNull
        return ToolPresentation(
            toolName = tool.toolName,
            kind = kind,
            state = refineState(tool.toolState, kind, envelope, exitCode),
            subject = subjectFor(kind, tool, envelope),
            count = countFor(kind, envelope),
            errorMessage = errorMessage,
            exitCode = exitCode,
        )
    }

    /** toolState 为主，信封语义细化 EMPTY。 */
    private fun refineState(
        base: ToolState,
        kind: ToolKind,
        envelope: JsonObject?,
        exitCode: Int?,
    ): ToolState {
        if (base == ToolState.RUNNING || base == ToolState.CALLING) return base
        if (exitCode != null && exitCode != 0) return ToolState.FAILED
        val count = countFor(kind, envelope)
        if (kind != ToolKind.UNKNOWN && count != null && count == 0) return ToolState.EMPTY
        return base
    }

    private fun subjectFor(kind: ToolKind, tool: UIMessagePart.Tool, envelope: JsonObject?): String? {
        val args = tool.inputAsJson().jsonObjectOrNull()
        return when (kind) {
            ToolKind.WEB_SEARCH, ToolKind.CONVERSATION_SEARCH -> args?.string("query")
                ?: envelope?.string("query")
            ToolKind.WEB_FETCH -> args?.string("url") ?: envelope?.string("url")
            ToolKind.CONVERSATION_LIST -> envelope?.string("conversation_id")
            ToolKind.CONVERSATION_READ -> envelope?.string("title")
                ?: envelope?.string("conversation_id")
            ToolKind.SHELL_EXECUTE -> args?.string("command")
                ?.replace('\n', ' ')?.trim()
            ToolKind.FILE_READ, ToolKind.FILE_WRITE, ToolKind.FILE_EDIT -> envelope?.string("path")
            ToolKind.FILE_GLOB, ToolKind.FILE_GREP -> args?.string("pattern")
                ?: envelope?.string("pattern")
            ToolKind.CLIPBOARD, ToolKind.TEXT_TO_SPEECH, ToolKind.SCREEN_TIME,
            ToolKind.CALENDAR_QUERY, ToolKind.CALENDAR_CREATE, ToolKind.TIME_INFO,
            ToolKind.EVAL_JAVASCRIPT, ToolKind.RUN_WORKFLOW, ToolKind.SUB_AGENT -> envelope?.string("path")
            ToolKind.UNKNOWN -> null
        }?.take(120)
    }

    private fun countFor(kind: ToolKind, envelope: JsonObject?): Int? = when (kind) {
        ToolKind.WEB_SEARCH -> envelope?.arraySize("items")
        ToolKind.WEB_FETCH -> null
        ToolKind.CONVERSATION_LIST -> envelope?.arraySize("conversations")
        ToolKind.CONVERSATION_SEARCH -> envelope?.arraySize("results")
        ToolKind.CONVERSATION_READ -> envelope?.get("total_messages")?.let {
            (it as? JsonPrimitive)?.contentOrNull?.toIntOrNull()
        }
        ToolKind.SHELL_EXECUTE -> null
        ToolKind.FILE_READ, ToolKind.FILE_WRITE, ToolKind.FILE_EDIT -> envelope?.get("sizeBytes")
            ?.let { (it as? JsonPrimitive)?.contentOrNull?.toLongOrNull()?.toInt() }
        ToolKind.SCREEN_TIME -> envelope?.arraySize("apps")
        ToolKind.CALENDAR_QUERY -> envelope?.arraySize("events")
        ToolKind.FILE_GLOB -> envelope?.arraySize("files")   // workspace_glob 信封发 "files"（非 "matches"）
        ToolKind.FILE_GREP -> envelope?.arraySize("matches")
        ToolKind.CLIPBOARD, ToolKind.TEXT_TO_SPEECH, ToolKind.CALENDAR_CREATE, ToolKind.TIME_INFO,
        ToolKind.EVAL_JAVASCRIPT, ToolKind.RUN_WORKFLOW, ToolKind.SUB_AGENT, ToolKind.UNKNOWN -> null
    }

    private fun JsonObject?.string(key: String): String? =
        (this?.get(key) as? JsonPrimitive)?.contentOrNull

    private fun JsonObject?.arraySize(key: String): Int? =
        (this?.get(key) as? kotlinx.serialization.json.JsonArray)?.size

    private fun kotlinx.serialization.json.JsonElement.jsonObjectOrNull(): JsonObject? =
        this as? JsonObject
}
```

注意 import：`jsonObject`（`JsonElement.jsonObject` 扩展用于 `inputAsJson()` 的 JsonElement → 需 `kotlinx.serialization.json.jsonObject`），`contentOrNull`（`JsonPrimitive.contentOrNull` 来自 `kotlinx.serialization.json.contentOrNull`）。

**`toolSummary`（同文件追加，@Composable 用 stringResource）：**

```kotlin
/** 一行概览：状态 + subject + count（对齐 Agora toolSummary）。 */
@Composable
fun toolSummary(p: ToolPresentation): String {
    val subject = p.subject?.let { s ->
        if (s.length > 60) s.take(60) + "…" else s
    }
    return when (p.state) {
        ToolState.CALLING -> stringResource(R.string.tool_summary_calling, p.toolName)
        ToolState.RUNNING -> stringResource(R.string.tool_summary_running, subject ?: p.toolName)
        ToolState.FAILED -> when {
            p.exitCode != null && p.exitCode != 0 ->
                stringResource(R.string.tool_summary_exit_failed, p.exitCode)
            p.errorMessage != null -> stringResource(R.string.tool_summary_error, p.errorMessage)
            else -> stringResource(R.string.tool_summary_failed)
        }
        ToolState.STOPPED -> stringResource(R.string.tool_summary_stopped)
        ToolState.EMPTY -> emptySummary(p, subject)
        ToolState.SUCCEEDED -> succeededSummary(p, subject)
    }
}

@Composable
private fun emptySummary(p: ToolPresentation, subject: String?): String = when (p.kind) {
    ToolKind.WEB_SEARCH -> stringResource(R.string.tool_summary_no_search_results, subject.orEmpty())
    ToolKind.CONVERSATION_SEARCH -> stringResource(R.string.tool_summary_no_conv_results, subject.orEmpty())
    ToolKind.CONVERSATION_LIST -> stringResource(R.string.tool_summary_no_conversations)
    ToolKind.FILE_READ -> stringResource(R.string.tool_summary_empty_file, subject ?: "file")
    ToolKind.SCREEN_TIME -> stringResource(R.string.tool_summary_no_screen_time)
    ToolKind.CALENDAR_QUERY -> stringResource(R.string.tool_summary_no_events)
    else -> stringResource(R.string.tool_summary_empty, subject ?: p.toolName)
}

@Composable
private fun succeededSummary(p: ToolPresentation, subject: String?): String = when (p.kind) {
    ToolKind.WEB_SEARCH -> p.count?.let {
        stringResource(R.string.tool_summary_search_done, it, subject.orEmpty())
    } ?: stringResource(R.string.tool_summary_done, p.toolName)
    ToolKind.CONVERSATION_SEARCH -> p.count?.let {
        stringResource(R.string.tool_summary_conv_done, it, subject.orEmpty())
    } ?: stringResource(R.string.tool_summary_done, p.toolName)
    ToolKind.CONVERSATION_LIST -> p.count?.let {
        stringResource(R.string.tool_summary_conv_count, it)
    } ?: stringResource(R.string.tool_summary_done, p.toolName)
    ToolKind.SHELL_EXECUTE -> p.exitCode?.let {
        stringResource(R.string.tool_summary_exit_ok, it)
    } ?: stringResource(R.string.tool_summary_done, p.toolName)
    ToolKind.FILE_WRITE, ToolKind.FILE_EDIT -> stringResource(
        R.string.tool_summary_file_done, subject ?: p.toolName
    )
    else -> stringResource(R.string.tool_summary_done, subject ?: p.toolName)
}
```

- [ ] **Step 4: 加字符串资源**（**双写**：英文进 `app/src/main/res/values/strings.xml`、中文进 `values-zh/strings.xml`，见 Global Constraints「字符串语言归属」；聚合头字符串在 Task 3 加）。中文版原样如下，英文版由实现者按 Global Constraints 约定补写（Task 1 已落地，后续任务照做）：

```xml
    <string name="tool_summary_calling">正在调用 %1$s…</string>
    <string name="tool_summary_running">正在执行 %1$s…</string>
    <string name="tool_summary_exit_failed">⚠ 退出码 %1$d</string>
    <string name="tool_summary_error">错误：%1$s</string>
    <string name="tool_summary_failed">调用失败</string>
    <string name="tool_summary_stopped">已停止</string>
    <string name="tool_summary_empty">%1$s 无结果</string>
    <string name="tool_summary_no_search_results">未找到“%1$s”的搜索结果</string>
    <string name="tool_summary_no_conv_results">未找到“%1$s”的会话</string>
    <string name="tool_summary_no_conversations">没有会话</string>
    <string name="tool_summary_empty_file">文件 %1$s 为空</string>
    <string name="tool_summary_no_screen_time">今日无屏幕时间</string>
    <string name="tool_summary_no_events">无日历事件</string>
    <string name="tool_summary_done">已完成 %1$s</string>
    <string name="tool_summary_search_done">找到 %1$d 条“%2$s”结果</string>
    <string name="tool_summary_conv_done">找到 %1$d 条“%2$s”会话</string>
    <string name="tool_summary_conv_count">%1$d 个会话</string>
    <string name="tool_summary_exit_ok">exit %1$d</string>
    <string name="tool_summary_file_done">已写入 %1$s</string>
```

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/me/rerere/rikkahub/ui/components/message/tools/ToolPresentation.kt app/src/test/java/me/rerere/rikkahub/data/ai/tools/ToolPresentationTest.kt app/src/main/res/values/strings.xml
git commit -m "feat(ui): ToolPresentation 解析器 + toolSummary 一行概览"
```

---

### Task 2: 聚合头计算纯函数（思考时长 + 工具数）

**Files:**
- Modify: `app/src/main/java/me/rerere/rikkahub/ui/components/message/ChatMessageCot.kt`
- Test: `app/src/test/java/me/rerere/rikkahub/data/ai/tools/ThinkingBlockAggregateTest.kt`（新建）

**Interfaces:**
- Produces: `fun List<ThinkingStep>.thinkingAggregate(): Pair<Long, Int>`（thoughtMs 总和，toolCount 有结果的工具数）。Task 3 依赖。

- [ ] **Step 1: 写失败测试**

```kotlin
package me.rerere.rikkahub.data.ai.tools

import kotlin.time.Clock  // ⚠️ 不是 kotlinx.datetime.Clock（kotlinx-datetime 0.8.0 已移除 Clock/Instant；项目用 kotlin.time.*）
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.ui.components.message.ThinkingStep
import me.rerere.rikkahub.ui.components.message.thinkingAggregate
import org.junit.Assert.assertEquals
import org.junit.Test

class ThinkingBlockAggregateTest {

    private fun reasoning(durationSeconds: Int) = UIMessagePart.Reasoning(
        reasoning = "think",
        createdAt = Clock.System.now() - kotlin.time.Duration.parse("${durationSeconds}s"),
        finishedAt = Clock.System.now(),
    )

    private fun tool(output: String? = null) = UIMessagePart.Tool(
        toolCallId = "t", toolName = "x", input = "{}",
        output = output?.let { listOf(UIMessagePart.Text(it)) } ?: emptyList(),
    )

    @Test
    fun `sums reasoning durations and counts executed tools`() {
        val steps = listOf(
            ThinkingStep.ReasoningStep(reasoning(3)),
            ThinkingStep.ToolStep(tool("""{"type":"x"}""")),
            ThinkingStep.ReasoningStep(reasoning(2)),
            ThinkingStep.ToolStep(tool()),  // 未执行（output 空）不计
        )
        val (thoughtMs, toolCount) = steps.thinkingAggregate()
        assertEquals(5000, thoughtMs)
        assertEquals(1, toolCount)
    }

    @Test
    fun `empty block yields zeros`() {
        assertEquals(0L to 0, emptyList<ThinkingStep>().thinkingAggregate())
    }
}
```

- [ ] **Step 2: Verify it fails（静态）** — `thinkingAggregate` 不存在。

- [ ] **Step 3: 实现**（追加到 `ChatMessageCot.kt`，import `kotlin.time.Clock`）

```kotlin
/** 聚合思考块：返回 (思考总毫秒数, 已执行的工具数)。 */
fun List<ThinkingStep>.thinkingAggregate(): Pair<Long, Int> {
    var thoughtMs = 0L
    var toolCount = 0
    forEach { step ->
        when (step) {
            is ThinkingStep.ReasoningStep -> {
                val r = step.reasoning
                val end = r.finishedAt ?: Clock.System.now()
                thoughtMs += (end - r.createdAt).inWholeMilliseconds
            }
            is ThinkingStep.ToolStep -> if (step.tool.isExecuted) toolCount++
        }
    }
    return thoughtMs to toolCount
}
```

- [ ] **Step 4: Verify（静态）** — `thinkingAggregate` 只在展示层用，无模型侧影响。

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/me/rerere/rikkahub/ui/components/message/ChatMessageCot.kt app/src/test/java/me/rerere/rikkahub/data/ai/tools/ThinkingBlockAggregateTest.kt
git commit -m "feat(ui): thinkingAggregate 聚合头计算（思考时长+工具数）"
```

---

### Task 3: `ChainOfThought` 聚合折叠头（脑图标 + 思考X秒·调用X个工具 + 右箭头）

**Files:**
- Modify: `app/src/main/java/me/rerere/rikkahub/ui/components/ui/ChainOfThought.kt`
- Modify: `app/src/main/java/me/rerere/rikkahub/ui/components/message/ChatMessage.kt`（ThinkingBlock 集成）

**Interfaces:**
- Consumes: `thinkingAggregate`（Task 2）、`ToolPresentationResolver`（Task 1，用于执行中显示最后一个工具名）。
- Produces: `ChainOfThought` 新可选参数 `header: (@Composable () -> Unit)? = null`（聚合模式）。Export 不传 header → 行为不变。

- [ ] **Step 1: 加字符串**（双写：英文进 `values/strings.xml`，中文进 `values-zh/strings.xml`，按字母序插入）

```xml
    <!-- values/strings.xml（英文默认） -->
    <string name="chain_of_thought_aggregate">Thought for %1$d s · called %2$d tools</string>
    <string name="chain_of_thought_calling">Calling tools…</string>
    <!-- values-zh/strings.xml（中文翻译） -->
    <string name="chain_of_thought_aggregate">思考了 %1$d 秒 · 调用了 %2$d 个工具</string>
    <string name="chain_of_thought_calling">正在调用工具…</string>
```

- [ ] **Step 2: 改 `ChainOfThought`**

在 `ChainOfThought` 签名加 `header` 参数；聚合模式下折叠只显示 header，展开显示全部步骤：

```kotlin
@Composable
fun <T> ChainOfThought(
    modifier: Modifier = Modifier,
    cardColors: CardColors = CardDefaults.cardColors(
        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
    ),
    steps: List<T>,
    collapsedVisibleCount: Int = 2,
    collapsedAdaptiveWidth: Boolean = false,
    header: (@Composable () -> Unit)? = null,   // 新增：聚合折叠头（ChatMessage 传）；null 保持旧行为
    content: @Composable ChainOfThoughtScope.(T) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    val aggregateMode = header != null
    val canCollapse = aggregateMode || steps.size > collapsedVisibleCount
    val shouldFillCollapseControlWidth = expanded || !collapsedAdaptiveWidth

    CompositionLocalProvider(LocalCardColor provides cardColors.containerColor) {
        Card(modifier = modifier, colors = cardColors, shape = RoundedCornerShape(16.dp)) {
            Column(
                modifier = Modifier
                    .padding(horizontal = 12.dp, vertical = 4.dp)
                    .animateContentSize(animationSpec = MaterialTheme.motionScheme.defaultSpatialSpec()),
            ) {
                val visibleSteps = when {
                    aggregateMode -> if (expanded) steps else emptyList()
                    expanded || !canCollapse -> steps
                    else -> steps.takeLast(collapsedVisibleCount)
                }

                if (aggregateMode) {
                    // 聚合折叠头：[icon+text …… 箭头]，箭头在最右
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(MaterialTheme.shapes.small)
                            .clickable { expanded = !expanded }
                            .padding(vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        header()
                        Spacer(Modifier.weight(1f))
                        Icon(
                            imageVector = if (expanded) HugeIcons.ArrowUp01 else HugeIcons.ArrowDown01,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                        )
                    }
                } else if (canCollapse) {
                    // ...现有"显示 N 条更多"控制保持不变...
                }

                // ...步骤渲染不变...
            }
        }
    }
}
```

注意：`header` 参数放在 `content` 之前、带默认值 `null`，保持现有调用（Export/ChatMessage 无 header 传参）兼容。

- [ ] **Step 3: ChatMessage 集成**（`ChatMessage.kt` ThinkingBlock 分支，约 L319-355）

把 `ChainOfThought(...)` 调用改为传聚合 header：

```kotlin
                    is MessagePartBlock.ThinkingBlock -> {
                        if (block.steps.isNotEmpty()) {
                            val isReasoningOnlyBlock = block.steps.fastAll { it is ThinkingStep.ReasoningStep }
                            val (thoughtMs, toolCount) = remember(block.steps) {
                                block.steps.thinkingAggregate()
                            }
                            ChainOfThought(
                                modifier = Modifier.animateContentSize(),
                                steps = block.steps,
                                collapsedAdaptiveWidth = isReasoningOnlyBlock,
                                cardColors = CardDefaults.cardColors(
                                    containerColor = MaterialTheme.colorScheme.surfaceContainerHigh.copy(
                                        alpha = settings.displaySetting.bubbleOpacity
                                    ),
                                ),
                                header = {
                                    val thinkingSummary = stringResource(
                                        R.string.chain_of_thought_aggregate,
                                        (thoughtMs / 1000).toInt(),
                                        toolCount,
                                    )
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    ) {
                                        Box(modifier = Modifier.size(24.dp), contentAlignment = Alignment.Center) {
                                            Icon(
                                                imageVector = HugeIcons.AiBrain02,
                                                contentDescription = null,
                                                modifier = Modifier.size(18.dp),
                                                tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.7f),
                                            )
                                        }
                                        Text(
                                            text = thinkingSummary,
                                            style = MaterialTheme.typography.titleSmall,
                                            color = MaterialTheme.colorScheme.primary.copy(alpha = 0.7f),
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                    }
                                },
                            ) { step -> ... /* 现有步骤渲染不变 */ }
                        }
                    }
```

import：`HugeIcons.AiBrain02`（`me.rerere.hugeicons.stroke.AiBrain02`）。若 `AiBrain02` 编译失败（图标名不对），CI 会报——按 Plan 1 经验，图标名以 CI 编译为准。

- [ ] **Step 4: Verify（CI 编译）** — push + 触发 nightly-build-debug（先 push 再触发），watch 通过。

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/me/rerere/rikkahub/ui/components/ui/ChainOfThought.kt app/src/main/java/me/rerere/rikkahub/ui/components/message/ChatMessage.kt app/src/main/res/values/strings.xml
git commit -m "feat(ui): ChainOfThought 聚合折叠头（AiBrain02 + 思考X秒·调用X个工具 + 右箭头）"
```

---

### Task 4: `ChatMessageToolStep` 改聚合列表行（一行概览 + 点击进详情）

**Files:**
- Modify: `app/src/main/java/me/rerere/rikkahub/ui/components/message/ChatMessageTools.kt`

**Interfaces:**
- Consumes: `ToolPresentationResolver`/`toolSummary`（Task 1）。

- [ ] **Step 1: 改造 `ChatMessageToolStep`**

保留 `AskUserToolStep` 交互流程不动。对常规工具，把 `ControlledChainOfThoughtStep`（可展开）改为**非受控行**：icon + 显示名 + 一行概览，点击进详情。用 `ChainOfThoughtStep`（非受控，`onClick` 优先）替代：

```kotlin
@Composable
fun ChainOfThoughtScope.ChatMessageToolStep(
    tool: UIMessagePart.Tool,
    loading: Boolean = false,
    onToolApproval: ((toolCallId: String, approved: Boolean, reason: String) -> Unit)? = null,
    onToolAnswer: ((toolCallId: String, answer: String) -> Unit)? = null,
) {
    if (tool.toolName == ASK_USER_TOOL_NAME) {
        AskUserToolStep(tool = tool, loading = loading, onToolAnswer = onToolAnswer)
        return
    }

    val renderer = remember(tool.toolName) { ToolUIRegistry.resolve(tool.toolName) }
    val context = remember(tool, loading) {
        ToolUIContext(
            tool = tool,
            arguments = tool.inputAsJson(),
            content = if (tool.isExecuted) {
                runCatching {
                    JsonInstant.parseToJsonElement(
                        tool.output.filterIsInstance<UIMessagePart.Text>().joinToString("\n") { it.text }
                    )
                }.getOrElse { JsonObject(emptyMap()) }
            } else {
                null
            },
            loading = loading,
        )
    }
    val presentation = remember(tool) { ToolPresentationResolver.resolve(tool) }
    val images = tool.output.filterIsInstance<UIMessagePart.Image>()

    var showResult by remember { mutableStateOf(false) }
    var showDenyDialog by remember { mutableStateOf(false) }
    val isPending = tool.approvalState is ToolApprovalState.Pending
    val isDenied = tool.approvalState is ToolApprovalState.Denied
    val hasClickable = context.content != null || isPending || images.isNotEmpty()

    ChainOfThoughtStep(
        icon = {
            if (loading) DotLoading(size = 10.dp)
            else Icon(renderer.icon(context), null, Modifier.size(16.dp),
                tint = LocalContentColor.current.copy(alpha = 0.7f))
        },
        label = {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    text = renderer.title(context),
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.secondary,
                    modifier = Modifier.shimmer(isLoading = loading),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (loading || tool.toolState != ToolState.SUCCEEDED || tool.output.isNotEmpty()) {
                    val summaryText = if (loading && tool.liveOutput != null) {
                        tool.liveOutput.lineSequence().lastOrNull()?.take(80)
                            ?: toolSummary(presentation)
                    } else {
                        toolSummary(presentation)
                    }
                    Text(
                        text = summaryText,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        },
        extra = {
            if (isPending && onToolApproval != null) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    // ...现有 approve/deny 按钮（Cancel01/Tick01）...
                }
            } else if (isDenied) {
                Text(
                    text = stringResource(R.string.chat_message_tool_denied),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        },
        onClick = if (hasClickable) { { showResult = true } } else null,
        content = null,  // 列表行不内联展开，详情全进 BottomSheet
    )

    // ...showDenyDialog / showResult BottomSheet 保持现有逻辑不变...
    // images 的处理：行内不再横向预览；移入 BottomSheet 的 Preview（renderer.Preview 已渲染 images），
    // 若 renderer 未渲染 images，在 Sheet 顶部补一个图片行。
}
```

要点：
- 每工具小图标保留（`renderer.icon`）。
- 一行概览 = `toolSummary(presentation)`；shell 执行中显示 `liveOutput` 最后一行（loading 且 liveOutput 非空）。
- 点击行 → 详情 BottomSheet（`renderer.Preview`），行为不变。
- `content = null` 使步骤不可展开（聚合列表不需要逐工具展开）。
- 现有 `images` 预览行（原 LazyRow）移除——图片进 Sheet。若 `renderer.Preview` 已处理 images（`DefaultToolPreview` 会），无需额外；`WorkspaceToolUIs`/`BuiltinToolUIs` 的 Preview 若不显示图片，在 Sheet 顶部加一行图片（参照原 LazyRow 逻辑，移入 Sheet 内容）。

- [ ] **Step 2: Verify（静态）** — `ChainOfThoughtStep` 非受控版本签名正确（icon/label/extra/onClick/content）；`toolSummary` import；`ToolState` import。

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/me/rerere/rikkahub/ui/components/message/ChatMessageTools.kt
git commit -m "feat(ui): ChatMessageToolStep 改聚合列表行（一行概览 + 点击进详情）"
```

---

### Task 5: CARRY-TO-PLAN2 展示回归修复（4 个渲染器）

**Files:**
- Modify: `app/src/main/java/me/rerere/rikkahub/ui/components/message/tools/BuiltinToolUIs.kt`

**Interfaces:**
- Consumes: Plan 1 的信封形状。

- [ ] **Step 1: `ScrapeWebPreview` 读新信封**（约 L786-827）

现状读 `content.jsonObject["urls"]`。信封后形状为 `{"type":"web_fetch","url":...,"text":...,"truncated":...,"totalChars":...}`。改为读 `text`/`url`，truncated 时显示提示：

```kotlin
// 在 ScrapeWebToolUI.Preview / ScrapeWebPreview 内
val content = context.content
if (content != null) {
    val url = content.getStringContent("url")
    val text = content.getStringContent("text")
    val truncated = content.getStringContent("truncated") == "true"
    // 渲染：url（若可点击）+ text（HighlightCodeBlock 或 Text），truncated 时加 "…已截断，共 N 字符" 提示
}
```

若 `ScrapeWebPreview` 是独立 composable，直接在函数内改读取逻辑；`content.getStringContent` 已在 ToolUI.kt 提供（`internal fun JsonElement?.getStringContent`）。

- [ ] **Step 2: `SearchWebToolUI` 的 answer 卡**（约 L675/692-708）

信封丢弃了 `answer` 字段（web_search 只有 items/images）。移除 answer 卡（它读不存在的字段，本来也渲染空）——直接删掉读 `answer` 的分支，保留 items 卡片列表与图片。若需要"AI 总结"展示，从 items 里取（不引入新数据流）。

- [ ] **Step 3: `RecentChatsToolUI.chats`**（约 L365-366）

现状 `(context.content as? JsonArray) ?: emptyList()`。信封后是 `{"type":"recent_chats","conversations":[...]}`。改为读 `conversations`：

```kotlin
private fun chats(context: ToolUIContext): JsonArray =
    (context.content as? JsonObject)?.get("conversations") as? JsonArray ?: emptyList()
```

（参照 `GetScreenTimeToolUI` 的 `jsonObjectOrNull?.get("apps")` 模式，`BuiltinToolUIs.kt:431-432`。）`hasSummary`/`Summary` 依赖 `chats` 的返回——改后自动恢复。

- [ ] **Step 4: `ConversationSearchToolUI.results`**（约 L399-400）

信封后是 `{"type":"conversation_search","query":...,"results":[...]}`。改为：

```kotlin
private fun results(context: ToolUIContext): JsonArray =
    (context.content as? JsonObject)?.get("results") as? JsonArray ?: emptyList()
```

同步核对该 renderer 的 `Summary`/`Preview` 对每条 result 读取的字段（`title`/`conversation_id`/`messages` 等信封里都有，保持 camelCase）。

- [ ] **Step 5: `TextToSpeechToolUI` 补 `Preview`（TTS 重播回归，用户拍板 2026-08-02）**

Task 4 弃用 `renderer.Summary` 后，TTS 的重播按钮丢失（`TextToSpeechToolUI` 无 `Preview` override，详情 Sheet 走 `DefaultToolPreview` 显示原始 JSON）。给 `TextToSpeechToolUI` 加 `Preview` override（`BuiltinToolUIs.kt` ~289-335）：
- 复用原 `Summary` 里的重播按钮（`FilledTonalIconButton`，emit `AppEvent.Speak(text)`，`BuiltinToolUIs.kt:323-332`），搬到 Preview 里；同时展示 TTS 文本（信封 `text` 字段，若有）。
- 若 `hasSummary`/`Summary` override 因此无调用者，可顺手删掉该 renderer 的 Summary（其余 renderer 的 Summary 保留，final review 再统一裁）。

- [ ] **Step 6: Verify（CI 编译）** — 参照 Task 3 Step 4。

- [ ] **Step 7: Commit**

```bash
git add app/src/main/java/me/rerere/rikkahub/ui/components/message/tools/BuiltinToolUIs.kt
git commit -m "fix(ui): 修复展示回归 + TTS Preview 重播（ScrapeWebPreview/SearchWeb answer/RecentChats/ConversationSearch/TextToSpeech）"
```

---

### Task 6: 聚合头执行中状态 + 最终 CI 验证

**Files:**
- Modify: `app/src/main/java/me/rerere/rikkahub/ui/components/message/ChatMessage.kt`
- Modify: `app/src/main/res/values/strings.xml`

- [ ] **Step 1: 执行中聚合头**（ChatMessage ThinkingBlock）

当 block 里有工具在 `RUNNING`/`CALLING`（`toolState`），聚合头显示 `正在调用工具…` + 最后一个运行中的工具名（对齐 Agora `isToolCalling`）。在 `header` lambda 里判断：

```kotlin
header = {
    val runningTool = block.steps.filterIsInstance<ThinkingStep.ToolStep>()
        .lastOrNull { it.tool.toolState == ToolState.RUNNING || it.tool.toolState == ToolState.CALLING }
    if (runningTool != null) {
        // [AiBrain02] 正在调用工具… <运行中工具名>
        Row { Icon(AiBrain02); Text(stringResource(R.string.chain_of_thought_calling)); Text(runningTool.tool.toolName, maxLines=1, ellipsis) }
    } else {
        // 完成态：[AiBrain02] 思考了X秒·调用X个工具（Task 3 的实现）
    }
}
```

import：`ToolState`。

- [ ] **Step 2: 全量静态 review**

- 每个工具步骤在聚合列表显示一行概览（`toolSummary`），无内联展开残留。
- 聚合头在完成/执行中两种态正确。
- Export.kt 的 `ChainOfThought` 调用不受 `header` 参数影响（未传 → 旧行为）。
- 4 处展示回归修复后，`recent_chats`/`conversation_search`/`scrape_web`/`search_web` 卡片恢复内容。

- [ ] **Step 3: 最终 CI 验证**

```bash
git push origin master
gh workflow run nightly-build-debug.yml --repo DevLintMar/rikkahub --ref master
gh run watch <RUN_ID> --repo DevLintMar/rikkahub --exit-status
```

期望 `Gradle Build` 通过。若报错修完重新 push + 触发。

- [ ] **Step 4: 手动验证清单**（装 debug APK）

1. 让 AI 调 `workspace_shell` 长命令 → 聚合头显示"正在调用工具…"，展开列表 shell 行实时滚动最后一行输出；完成后显示"思考X秒·调用X个工具"。
2. `conversation_search` → 展开列表显示"找到 N 条结果"，点击行进详情。
3. `search_web`/`scrape_web`/`recent_chats` 卡片恢复内容（不再空）。
4. MCP/`use_skill` 走 UNKNOWN 兜底（显示名 + 通用概览）。

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/me/rerere/rikkahub/ui/components/message/ChatMessage.kt app/src/main/res/values/strings.xml
git commit -m "feat(ui): 聚合头执行中状态（正在调用工具…）+ 最终 CI 验证"
```

---

### Task 7: 顺手 minor 修复（台账 [PLAN2 顺手修] 清单）

**Files:**
- Modify: `app/src/main/java/me/rerere/rikkahub/data/ai/tools/WorkspaceTools.kt`（read_file 描述/schema、glob 描述）
- 文档：`docs/superpowers/specs/2026-08-02-tool-call-agora-style-design.md`（type 统一规则定案）

**Interfaces:** 无——纯描述/schema 文案修正，不改行为。

- [ ] **Step 1: `workspace_read_file` 描述与 schema 补 offset/limit**

现状（Task 8 后）：工具已有 `offset`/`limit` 参数，但**描述没提、schema 没广告 `default:0`**，模型不知道能分段读。修改 `createReadFileTool`：

- 描述追加一句：`For large files, read a byte range with offset (default 0) and limit (default 0 = read to end); the result includes totalChars and hasMore so you can page through.`
- schema 的 `offset`/`limit` 参数描述补 `(default 0)`。

- [ ] **Step 2: `workspace_glob` 描述微调**

描述里把 "matched against file names" 改为更准确：`pattern matches paths relative to the base directory; '*.go' matches top-level only, use '**/*.go' to recurse.`（明示 RikkaHub glob 的 `**` 语义，避免模型按 Agora 习惯传裸模式漏匹配）。

- [ ] **Step 3: SearchTools `type` 统一规则定案（文档）**

在 spec 里加一句定案：**展示层 resolver 一律基于 `tool.toolName`（字面工具名）映射 kind，不信赖信封 `type`**。SearchTools 的 `type` 字段保持 `web_search`/`web_fetch`（模型侧语义名，不改），二者不一致由 resolver 用 toolName 规避——不再 retrofit SearchTools。Plan 2 Task 1 的 `kindFor` 已按此实现。

- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/me/rerere/rikkahub/data/ai/tools/WorkspaceTools.kt docs/superpowers/specs/2026-08-02-tool-call-agora-style-design.md
git commit -m "docs+fix: 顺手 minor（read_file 描述补 offset/limit、glob 描述明示 ** 语义、type 规则定案）"
```

---

## Self-Review 结论

- **Spec 覆盖**：§4.1 解析器（Task 1）、§4.2 一行概览（Task 1）、§4.3 聚合卡片（Task 3/4/6）、§4.4 组件落点（Task 3/4）、§4.5 MCP/use_skill UNKNOWN 兜底（Task 1 kindFor else→UNKNOWN）、CARRY-TO-PLAN2 回归（Task 5）。
- **类型一致性**：`thinkingAggregate`（Task 2）→ `ChainOfThought.header`（Task 3）；`ToolPresentation`/`toolSummary`（Task 1）→ `ChatMessageToolStep`（Task 4）；`toolState`/`liveOutput`/`isExecuted`（Plan 1）贯穿 Task 4/6。
- **无占位符**；后续任务引用的类型均在前面任务定义。
- **已知风险**：`HugeIcons.AiBrain02` 图标名以 CI 编译为准（Plan 1 的 Connect 图标经验）；`ChainOfThought` 的 `header` 参数兼容性由 Export.kt 未传参验证。
