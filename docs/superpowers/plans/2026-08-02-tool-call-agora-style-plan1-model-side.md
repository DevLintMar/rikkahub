# RikkaHub 工具调用对齐 Agora — Plan 1（模型侧）Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 让 RikkaHub 工具结果以 type 信封 + 紧凑错误码 + 工具级自限截断喂给模型；消息模型携带 `toolState`；`workspace_shell` 流式执行；`workspace_read_file` 支持 `offset`/`limit` 分段读。这是双计划的第一半（展示层见 Plan 2）。

**Architecture:** 在 ai 模块给 `Tool` 增加 `executeFlow`（默认 adapter 包装现有 `execute`，不破坏 15+ 现有工具），`UIMessagePart.Tool` 加 `toolState`/`liveOutput` 字段；GenerationHandler 改为 collect Flow 并注入实时状态更新；各工具 execute 结果套 `type` 信封 + 错误码；删除 `maybeTruncateToolOutput`（落盘+指针），改为工具自限 + 100KB 安全网。字段保持 RikkaHub camelCase。

**Tech Stack:** Kotlin, kotlinx.coroutines (Flow), kotlinx.serialization, Room, WorkManager（不用）, Koin, OkHttp。

## Global Constraints

- **本机无编译器**：不运行 gradle。所有代码静态编写 + review；真实编译靠 CI。CI 命令见每任务的 Verify 步骤。
- **CI 触发顺序**：必须先 `git push origin master`，再 `gh workflow run nightly-build-debug.yml --repo DevLintMar/rikkahub --ref master`（否则跑旧代码）。gh 必须显式 `--repo DevLintMar/rikkahub`。
- **单测不可本地运行**：`app:assembleDebug` 不编译/运行测试源码。单测仅为将来本地运行而写（纯函数）；主源码编译错误靠 CI `assembleDebug` 暴露。
- **信封字段 camelCase**（用户决策）：`exitCode`/`stdout`/`stderr`/`text`/`totalChars`/`hasMore`，只新增 `type`。**不对齐** Agora 的 snake_case。
- **完整 Java 堆栈不进模型**：异常只记 `Log.e`（TAG），模型侧只给 `{type, error, message}`。
- **`use_skill` / `ask_user` / MCP 工具不套信封**（spec §3.2）：`use_skill` 返回原始 markdown；`ask_user` 是交互流程；MCP 结果内容由服务器定义。
- `liveOutput` 用 `@Transient` 不持久化；`toolState` 持久化。**不需要 Room migration**（`message_node.messages` 是 TEXT 列存 JSON，`JsonInstant` 配置 `ignoreUnknownKeys=true`+`encodeDefaults=true` 自动兼容旧数据）。

---

### Task 1: `ToolOutput` 接口 + `Tool.executeFlow`（ai 模块）

**Files:**
- Modify: `ai/src/main/java/me/rerere/ai/core/Tool.kt`
- Test: `app/src/test/java/me/rerere/rikkahub/data/ai/tools/ToolExecuteFlowTest.kt`（新建）

**Interfaces:**
- Produces: `me.rerere.ai.core.ToolOutput`（sealed），`Tool.executeFlow: suspend (JsonElement) -> Flow<ToolOutput>`。Task 4 依赖这两个。

- [ ] **Step 1: 写失败测试**

```kotlin
package me.rerere.rikkahub.data.ai.tools

import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import me.rerere.ai.core.Tool
import me.rerere.ai.core.ToolOutput
import me.rerere.ai.ui.UIMessagePart
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ToolExecuteFlowTest {

    @Test
    fun `default executeFlow emits exactly one Completed with execute result`() = runTest {
        val executed = mutableListOf<String>()
        val tool = Tool(
            name = "fake_tool",
            description = "",
            execute = { _ ->
                executed += "called"
                listOf(UIMessagePart.Text("""{"type":"fake_tool","ok":true}"""))
            },
        )
        val outputs = tool.executeFlow(JsonObject(emptyMap())).toList()
        assertEquals(1, outputs.size)
        val completed = outputs.single() as? ToolOutput.Completed
        assertTrue(completed != null)
        assertEquals(listOf("called"), executed)
        assertEquals("""{"type":"fake_tool","ok":true}""", completed!!.parts.single().let { (it as UIMessagePart.Text).text })
    }

    @Test
    fun `executeFlow propagates execute exception`() = runTest {
        val tool = Tool(
            name = "boom",
            description = "",
            execute = { _ -> throw IllegalStateException("boom") },
        )
        val error = runCatching { tool.executeFlow(JsonObject(emptyMap())).toList() }.exceptionOrNull()
        assertTrue(error is IllegalStateException)
    }
}
```

- [ ] **Step 2: Verify it fails（静态）**

本机无法运行测试。确认测试引用的 `ToolOutput`/`executeFlow` 尚不存在即达"失败"态。进入 Step 3。

- [ ] **Step 3: 实现**

修改 `ai/src/main/java/me/rerere/ai/core/Tool.kt`：

```kotlin
package me.rerere.ai.core

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import me.rerere.ai.provider.Model
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart

/** 工具执行输出。一次性工具由默认 adapter 只发 [Completed]；流式工具可先发 [OutputDelta]。 */
sealed interface ToolOutput {
    /** 增量输出：只给用户看（实时显示），绝不发给模型。 */
    data class OutputDelta(val text: String) : ToolOutput
    /** 生命周期提示（可选）。 */
    data class Progress(val message: String) : ToolOutput
    /** 唯一权威结果，发给模型。 */
    data class Completed(val parts: List<UIMessagePart>) : ToolOutput
}

@Serializable
data class Tool(
    val name: String,
    val description: String,
    val parameters: () -> InputSchema? = { null },
    val systemPrompt: (model: Model, messages: List<UIMessage>) -> String = { _, _ -> "" },
    val needsApproval: (JsonElement) -> Boolean = { false },
    val execute: suspend (JsonElement) -> List<UIMessagePart>,
    val executeFlow: suspend (JsonElement) -> Flow<ToolOutput> = { args ->
        flow { emit(ToolOutput.Completed(execute(args))) }
    },
)
```

说明：`executeFlow` 默认值引用构造参数 `execute`（Kotlin 允许）。`Tool` 已有函数类型字段（`execute`/`parameters`/`systemPrompt`）在 CI 编译通过，新字段同构。

- [ ] **Step 4: Verify**

静态自检：`Tool` 仍 `@Serializable`，函数类型字段与现有模式一致。测试源码不参与 CI 编译，但主源码必须能编译——确认无未解析引用（`ToolOutput` 已定义、`Flow`/`flow` 已 import）。

- [ ] **Step 5: Commit**

```bash
git add ai/src/main/java/me/rerere/ai/core/Tool.kt app/src/test/java/me/rerere/rikkahub/data/ai/tools/ToolExecuteFlowTest.kt
git commit -m "feat(ai): Tool 增加 executeFlow 流式接口与 ToolOutput（默认 adapter 包 execute）"
```

---

### Task 2: `ToolState` 枚举 + `UIMessagePart.Tool` 加字段（ai 模块）

**Files:**
- Modify: `ai/src/main/java/me/rerere/ai/ui/Message.kt`
- Test: `app/src/test/java/me/rerere/rikkahub/data/ai/tools/ToolStateSerializationTest.kt`（新建）

**Interfaces:**
- Produces: `me.rerere.ai.ui.ToolState` 枚举；`UIMessagePart.Tool` 新字段 `toolState`（默认 `CALLING`）、`liveOutput`（`@Transient`）。Task 4 写 `toolState`；Task 2 展示读 `toolState`/`liveOutput`。

- [ ] **Step 1: 写失败测试**

```kotlin
package me.rerere.rikkahub.data.ai.tools

import me.rerere.ai.ui.ToolState
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.utils.JsonInstant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ToolStateSerializationTest {

    private fun toolJson(toolState: String = "", liveOutput: String = ""): String {
        val stateField = if (toolState.isNotEmpty()) """, "toolState":"$toolState"""" else ""
        val liveField = if (liveOutput.isNotEmpty()) """, "liveOutput":"$liveOutput"""" else ""
        return """{"type":"tool","toolCallId":"t1","toolName":"x","input":"{}","output":[]$stateField$liveField}"""
    }

    @Test
    fun `old node json without new fields decodes with defaults`() {
        val tool = JsonInstant.decodeFromString<UIMessagePart>(toolJson()) as UIMessagePart.Tool
        assertEquals(ToolState.CALLING, tool.toolState)
        assertNull(tool.liveOutput)
    }

    @Test
    fun `toolState persists through round trip`() {
        val tool = UIMessagePart.Tool(
            toolCallId = "t1", toolName = "x", input = "{}",
            toolState = ToolState.FAILED,
        )
        val encoded = JsonInstant.encodeToString<UIMessagePart>(tool)
        val decoded = JsonInstant.decodeFromString<UIMessagePart>(encoded) as UIMessagePart.Tool
        assertEquals(ToolState.FAILED, decoded.toolState)
    }

    @Test
    fun `liveOutput is transient and not persisted`() {
        val tool = UIMessagePart.Tool(
            toolCallId = "t1", toolName = "x", input = "{}",
            liveOutput = "some live text",
        )
        val encoded = JsonInstant.encodeToString<UIMessagePart>(tool)
        val decoded = JsonInstant.decodeFromString<UIMessagePart>(encoded) as UIMessagePart.Tool
        assertNull(decoded.liveOutput)
    }
}
```

- [ ] **Step 2: Verify it fails（静态）** — `ToolState` 未定义、`toolState`/`liveOutput` 参数不存在。

- [ ] **Step 3: 实现**

在 `ai/src/main/java/me/rerere/ai/ui/Message.kt` 的 `UIMessagePart` 伴生区附近新增：

```kotlin
/** 工具执行状态（对齐 Agora ToolExecutionStates，省略 BACKGROUND_RUNNING）。 */
enum class ToolState { CALLING, RUNNING, SUCCEEDED, EMPTY, FAILED, STOPPED }
```

修改 `UIMessagePart.Tool` 数据类（新增两字段 + 更新 `merge`）：

```kotlin
    @Serializable
    @SerialName("tool")
    data class Tool(
        val toolCallId: String,
        val toolName: String,
        val input: String,
        val output: List<UIMessagePart> = emptyList(),
        val approvalState: ToolApprovalState = ToolApprovalState.Auto,
        val toolState: ToolState = ToolState.CALLING,
        @Transient
        val liveOutput: String? = null,
        override var metadata: JsonObject? = null
    ) : UIMessagePart() {
        /** Whether the tool has been executed (has output) */
        val isExecuted: Boolean get() = output.isNotEmpty()
        // ... 现有 isPending / canResumeExecution / inputAsJson 保持不变 ...

        fun merge(other: Tool): Tool {
            return Tool(
                toolCallId = toolCallId,
                toolName = toolName + other.toolName,
                input = input + other.input,
                output = output + other.output,
                approvalState = approvalState,
                toolState = other.toolState.takeIf { it != ToolState.CALLING } ?: toolState,
                liveOutput = other.liveOutput ?: liveOutput,
                metadata = if (other.metadata != null) other.metadata else metadata,
            )
        }
    }
```

需要 import：`kotlinx.serialization.Transient`（若文件未 import）。注意 `@Transient` 在 `kotlinx.serialization` 下必须有默认值（`liveOutput = null` ✓）。

- [ ] **Step 4: Verify（静态）** — 确认 `merge` 保留新字段；确认序列化注解正确。

- [ ] **Step 5: Commit**

```bash
git add ai/src/main/java/me/rerere/ai/ui/Message.kt app/src/test/java/me/rerere/rikkahub/data/ai/tools/ToolStateSerializationTest.kt
git commit -m "feat(ai): UIMessagePart.Tool 加 toolState/liveOutput（liveOutput 不持久化）"
```

---

### Task 3: 信封解析 + 状态粗推断纯函数（app 模块，新建）

**Files:**
- Create: `app/src/main/java/me/rerere/rikkahub/data/ai/tools/ToolResultEnvelope.kt`
- Test: `app/src/test/java/me/rerere/rikkahub/data/ai/tools/ToolResultEnvelopeTest.kt`（新建）

**Interfaces:**
- Produces: `parseEnvelope(parts): JsonObject?`、`inferToolState(parts): ToolState`。Task 4（GenerationHandler）依赖。

- [ ] **Step 1: 写失败测试**

```kotlin
package me.rerere.rikkahub.data.ai.tools

import me.rerere.ai.ui.ToolState
import me.rerere.ai.ui.UIMessagePart
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ToolResultEnvelopeTest {

    private fun text(s: String) = UIMessagePart.Text(s)

    @Test
    fun `parseEnvelope returns null for non json`() {
        assertNull(parseEnvelope(listOf(text("plain markdown"))))
        assertNull(parseEnvelope(emptyList()))
    }

    @Test
    fun `parseEnvelope returns object for envelope json`() {
        val env = parseEnvelope(listOf(text("""{"type":"workspace_shell","exitCode":0}""")))
        assertEquals("workspace_shell", env?.get("type")?.let { (it as kotlinx.serialization.json.JsonPrimitive).content })
    }

    @Test
    fun `inferToolState maps error and non-zero exitCode to FAILED`() {
        assertEquals(ToolState.FAILED, inferToolState(listOf(text("""{"type":"x","error":"no_results"}"""))))
        assertEquals(ToolState.FAILED, inferToolState(listOf(text("""{"type":"x","exitCode":1}"""))))
    }

    @Test
    fun `inferToolState maps success and empty to SUCCEEDED / EMPTY`() {
        assertEquals(ToolState.SUCCEEDED, inferToolState(listOf(text("""{"type":"x","exitCode":0}"""))))
        assertEquals(ToolState.SUCCEEDED, inferToolState(listOf(text("plain text"))))
        assertEquals(ToolState.EMPTY, inferToolState(emptyList()))
    }
}
```

- [ ] **Step 2: Verify it fails（静态）** — `parseEnvelope`/`inferToolState` 未定义。

- [ ] **Step 3: 实现**

`app/src/main/java/me/rerere/rikkahub/data/ai/tools/ToolResultEnvelope.kt`：

```kotlin
package me.rerere.rikkahub.data.ai.tools

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import me.rerere.ai.ui.ToolState
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.utils.JsonInstant

/** 从工具输出文本解析信封 JSON。非 JSON 或空返回 null。 */
fun parseEnvelope(parts: List<UIMessagePart>): JsonObject? {
    val text = parts.filterIsInstance<UIMessagePart.Text>().joinToString("\n").trim()
    if (text.isBlank()) return null
    return runCatching { JsonInstant.parseToJsonElement(text).jsonObject }.getOrNull()
}

/** 从结果信封粗推断工具状态（展示层再细化 EMPTY/STOPPED）。 */
fun inferToolState(parts: List<UIMessagePart>): ToolState {
    if (parts.isEmpty()) return ToolState.EMPTY
    val envelope = parseEnvelope(parts) ?: return ToolState.SUCCEEDED
    if (envelope["error"] != null) return ToolState.FAILED
    val exitCode = (envelope["exitCode"] as? JsonPrimitive)?.intOrNull
    if (exitCode != null && exitCode != 0) return ToolState.FAILED
    return ToolState.SUCCEEDED
}
```

- [ ] **Step 4: Verify（静态）** — import 齐全；`JsonInstant` 来自 `me.rerere.rikkahub.utils`。

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/me/rerere/rikkahub/data/ai/tools/ToolResultEnvelope.kt app/src/test/java/me/rerere/rikkahub/data/ai/tools/ToolResultEnvelopeTest.kt
git commit -m "feat(tools): parseEnvelope/inferToolState 纯函数（信封解析 + 状态粗推断）"
```

---

### Task 4: GenerationHandler 流式 collect + 错误信封 + 截断安全网（app 模块）

**Files:**
- Modify: `app/src/main/java/me/rerere/rikkahub/data/ai/GenerationHandler.kt`

**Interfaces:**
- Consumes: `Tool.executeFlow`（Task 1）、`ToolState`/`UIMessagePart.Tool` 新字段（Task 2）、`inferToolState`（Task 3）。
- Produces: `clipToolOutput(parts): List<UIMessagePart>`（100KB 安全网）。

- [ ] **Step 1: 移除旧截断**

删除 `maybeTruncateToolOutput` 方法（约 L458-490）及其内部引用的 `FileFolders.TOOL_OUTPUTS`。新增常量：

```kotlin
private const val MAX_TOOL_RESULT_LENGTH = 100_000
```

新增安全网方法（替换旧方法）：

```kotlin
    /** 硬性安全网：文本输出超 100KB 截断并加标记（兜底 MCP/use_skill 非信封大输出）。 */
    private fun clipToolOutput(parts: List<UIMessagePart>): List<UIMessagePart> {
        val textParts = parts.filterIsInstance<UIMessagePart.Text>()
        val nonTextParts = parts.filter { it !is UIMessagePart.Text }
        val totalChars = textParts.sumOf { it.text.length }
        if (totalChars <= MAX_TOOL_RESULT_LENGTH) return parts
        val clipped = textParts.joinToString("\n") { it.text }
            .take(MAX_TOOL_RESULT_LENGTH) + "…[truncated]"
        return listOf(UIMessagePart.Text(clipped)) + nonTextParts
    }
```

检查 import：`File` 若不再使用则删 import（`java.io.File`）。

- [ ] **Step 2: 改造工具执行循环**

将工具循环中 `else` 分支（Auto/Approved，当前约 L276-314）替换为 collect Flow 结构：

```kotlin
                    else -> {
                        // Auto or Approved - execute the tool
                        runCatching {
                            val toolDef = toolsInternal.find { toolDef -> toolDef.name == tool.toolName }
                                ?: error("Tool ${tool.toolName} not found")
                            val args = runCatching {
                                json.parseToJsonElement(tool.input.ifBlank { "{}" })
                            }.getOrElse {
                                error("Invalid tool arguments JSON for ${tool.toolName}: ${it.message}")
                            }
                            Log.i(TAG, "generateText: executing tool ${toolDef.name} with args: $args")
                            var completed: UIMessagePart.Tool? = null
                            toolDef.executeFlow(args).collect { output ->
                                when (output) {
                                    is ToolOutput.OutputDelta -> updateToolLive(tool.toolCallId, output.text)
                                    is ToolOutput.Progress -> updateToolLive(tool.toolCallId, null)
                                    is ToolOutput.Completed -> {
                                        completed = tool.copy(
                                            output = clipToolOutput(output.parts),
                                            toolState = inferToolState(output.parts),
                                        )
                                    }
                                }
                            }
                            executedTools += completed ?: tool.copy(
                                output = emptyList(),
                                toolState = ToolState.FAILED,
                            )
                        }.onFailure {
                            // 取消必须向上传播，否则停止生成会被误报为工具执行错误
                            if (it is CancellationException) throw it
                            Log.e(TAG, "Tool ${tool.toolName} execution failed", it)
                            executedTools += tool.copy(
                                output = listOf(
                                    UIMessagePart.Text(
                                        json.encodeToString(
                                            buildJsonObject {
                                                put("type", JsonPrimitive(tool.toolName))
                                                put("error", JsonPrimitive("error"))
                                                put(
                                                    "message",
                                                    JsonPrimitive(
                                                        "[${it.javaClass.name}] ${it.message ?: it.javaClass.simpleName}"
                                                    )
                                                )
                                            }
                                        )
                                    )
                                ),
                                toolState = ToolState.FAILED,
                            )
                        }
                    }
```

在同循环块内（flow builder 中）定义局部 suspend 函数 `updateToolLive`（放在 `toolsToProcess.forEach` 之前）：

```kotlin
            // 工具实时输出：把 OutputDelta 注入最后一条消息的对应 Tool 部件并发出中间更新
            suspend fun updateToolLive(toolCallId: String, text: String?) {
                val lastMessage = messages.last()
                val updatedParts = lastMessage.parts.map { part ->
                    if (part is UIMessagePart.Tool && part.toolCallId == toolCallId) {
                        part.copy(liveOutput = text, toolState = ToolState.RUNNING)
                    } else {
                        part
                    }
                }
                messages = messages.dropLast(1) + lastMessage.copy(parts = updatedParts)
                emit(GenerationChunk.Messages(messages))
            }
```

import 补：`me.rerere.ai.core.ToolOutput`、`me.rerere.ai.ui.ToolState`、`me.rerere.rikkahub.data.ai.tools.inferToolState`。

- [ ] **Step 3: 检查被删符号引用**

删除 `maybeTruncateToolOutput` 后全局搜索确认无残留引用；删除 `FileFolders.TOOL_OUTPUTS` 相关（含 `FileFolders` 若仅此一处用）。确认 `hasShellAccess` 变量及其用途（原本传给 maybeTruncateToolOutput）不再需要，删除。

- [ ] **Step 4: Verify（CI 编译）**

```bash
git add -A && git commit -m "refactor(ai): GenerationHandler 流式 collect 工具执行 + 错误信封 + 100KB 安全网"
git push origin master
gh workflow run nightly-build-debug.yml --repo DevLintMar/rikkahub --ref master
gh run watch $(gh run list --repo DevLintMar/rikkahub --workflow nightly-build-debug.yml --limit 1 --json databaseId --jq '.[0].databaseId') --repo DevLintMar/rikkahub --exit-status
```

期望：`Gradle Build` 通过。若报错，修完重新 push + 触发（先 push 再触发）。

- [ ] **Step 5: Commit（已含在 Step 4）**

---

### Task 5: SearchTools 信封 retrofit

**Files:**
- Modify: `app/src/main/java/me/rerere/rikkahub/data/ai/tools/SearchTools.kt`
- Test: `app/src/test/java/me/rerere/rikkahub/data/ai/tools/SearchToolsEnvelopeTest.kt`（新建，仅测纯解析辅助，若工具 execute 依赖 service 无法单测则跳过测试、只静态改）

**Interfaces:**
- Produces: `search_web` 结果 `{"type":"web_search","query":...,"items":[...],"images":[...]}`；`scrape_web` 结果 `{"type":"web_fetch","url":...,"text":...,"truncated":bool,"totalChars":int}`。

- [ ] **Step 1: `search_web` 信封**

在 `search_web` 的 execute 中，把结果包装为信封：

```kotlin
                    val raw = JsonInstantPretty.encodeToJsonElement(result.getOrThrow()).jsonObject
                    val results = JsonObject(raw.toMutableMap().apply {
                        // items 数组加 id/index（保持现有行为）
                        this["items"] = JsonArray(this["items"]!!.jsonArray.mapIndexed { index, item ->
                            JsonObject(item.jsonObject.toMutableMap().apply {
                                put("id", JsonPrimitive(Uuid.random().toString().take(6)))
                                put("index", JsonPrimitive(index + 1))
                            })
                        })
                    })
                    val query = it.jsonObject["query"]?.jsonPrimitive?.contentOrNull
                    listOf(
                        UIMessagePart.Text(
                            buildJsonObject {
                                put("type", JsonPrimitive("web_search"))
                                query?.let { q -> put("query", JsonPrimitive(q)) }
                                put("items", results["items"] ?: JsonArray(emptyList()))
                                put("images", results["images"] ?: JsonArray(emptyList()))
                            }.toString()
                        )
                    )
```

注意：`results` 的其余顶层字段（服务相关）不必保留，模型只消费 `items`/`images`。若 `query` 参数存在（search_web 的 query 由 service.parameters 定义，key 可能是 `query`），保持现状取值。

- [ ] **Step 2: `scrape_web` 信封 + 自限**

```kotlin
                        val scrape = JsonInstantPretty.encodeToJsonElement(result.getOrThrow()).jsonObject
                        val url = scrape["url"]?.jsonPrimitive?.contentOrNull
                            ?: it.jsonObject["url"]?.jsonPrimitive?.contentOrNull
                            ?: ""
                        val text = scrape["text"]?.jsonPrimitive?.contentOrNull
                            ?: scrape["content"]?.jsonPrimitive?.contentOrNull
                            ?: ""
                        val totalChars = text.length
                        val truncated = totalChars > MAX_SCRAPE_TEXT_CHARS
                        val clippedText = if (truncated) text.take(MAX_SCRAPE_TEXT_CHARS) else text
                        listOf(
                            UIMessagePart.Text(
                                buildJsonObject {
                                    put("type", JsonPrimitive("web_fetch"))
                                    put("url", JsonPrimitive(url))
                                    put("text", JsonPrimitive(clippedText))
                                    put("truncated", JsonPrimitive(truncated))
                                    put("totalChars", JsonPrimitive(totalChars))
                                }.toString()
                            )
                        )
```

文件顶部加：`private const val MAX_SCRAPE_TEXT_CHARS = 32 * 1024`。

- [ ] **Step 3: Verify（CI 编译）** — 参照 Task 4 Step 4 的 push + 触发 + watch 命令。搜索工具 execute 依赖 `SearchService`，无法纯单测；以 CI 编译 + 静态 review 为准。

- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/me/rerere/rikkahub/data/ai/tools/SearchTools.kt
git commit -m "feat(search): search_web/scrape_web 结果套 type 信封 + scrape 32KB 自限"
```

---

### Task 6: ConversationTools 信封 retrofit

**Files:**
- Modify: `app/src/main/java/me/rerere/rikkahub/data/ai/tools/ConversationTools.kt`

**Interfaces:**
- Produces: `recent_chats` → `{"type":"recent_chats","conversations":[...]}`；`conversation_search` → `{"type":"conversation_search","query":...,"results":[...]}`；`read_conversation` → `{"type":"read_conversation","conversation_id":...,"title":...,"total_messages":...,"offset":...,"limit":...,"has_more":...,"messages":[...]}`。

- [ ] **Step 1: `recent_chats` 信封**

把 payload 数组包进 `{"type":"recent_chats","conversations":[...]}`：

```kotlin
            val payload = buildJsonObject {
                put("type", JsonPrimitive("recent_chats"))
                putJsonArray("conversations") {
                    recent.forEach { conversation ->
                        add(buildJsonObject {
                            put("id", JsonPrimitive(conversation.id.toString()))
                            put("title", JsonPrimitive(conversation.title.ifBlank { "Untitled" }))
                            put("last_chat", JsonPrimitive(conversation.updateAt.toLocalDate()))
                        })
                    }
                }
            }
            listOf(UIMessagePart.Text(JsonInstantPretty.encodeToString(payload)))
```

- [ ] **Step 2: `conversation_search` 信封**

把 windows 数组包进 `{"type":"conversation_search","query":...,"results":[...]}`：

```kotlin
            val payload = buildJsonObject {
                put("type", JsonPrimitive("conversation_search"))
                put("query", JsonPrimitive(query))
                putJsonArray("results") {
                    windows.forEach { w ->
                        add(buildJsonObject {
                            put("title", JsonPrimitive(w.title.ifBlank { "Untitled" }))
                            put("conversation_id", JsonPrimitive(w.conversationId))
                            put("top_score", JsonPrimitive(w.topScore))
                            put("match_count", JsonPrimitive(w.matchCount))
                            putJsonArray("messages") {
                                w.messages.forEach { m ->
                                    add(buildJsonObject {
                                        put("participant", JsonPrimitive(m.participant))
                                        put("text", JsonPrimitive(m.text))
                                        put("timestamp", JsonPrimitive(m.timestamp))
                                    })
                                }
                            }
                        })
                    }
                }
            }
            listOf(UIMessagePart.Text(JsonInstantPretty.encodeToString(payload)))
```

- [ ] **Step 3: `read_conversation` 信封**

现有 payload 已含 `conversation_id`/`title`/`total_messages` 等，加 `type` 字段即可：

```kotlin
            val payload = buildJsonObject {
                put("type", JsonPrimitive("read_conversation"))
                put("conversation_id", JsonPrimitive(conversationId))
                // ...其余字段保持不变...
            }
```

- [ ] **Step 4: Verify（CI 编译）** — 参照 Task 4 Step 4。

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/me/rerere/rikkahub/data/ai/tools/ConversationTools.kt
git commit -m "feat(conversation): recent_chats/conversation_search/read_conversation 套 type 信封"
```

---

### Task 7: WorkspaceTools 信封 + `workspace_shell` 流式

**Files:**
- Modify: `app/src/main/java/me/rerere/rikkahub/data/ai/tools/WorkspaceTools.kt`
- Modify: `workspace/src/main/java/me/rerere/workspace/WorkspaceShellRunner.kt`（若需要暴露逐行流，见 Step 3）

**Interfaces:**
- Consumes: `Tool.executeFlow`（Task 1）、`ToolOutput`（Task 1）。
- Produces: 4 个 workspace 工具套 `type` 信封；`workspace_shell` 覆盖 `executeFlow` 深度流式。

- [ ] **Step 1: 文件工具信封（read/write/edit）**

- `workspace_read_file` 结果 → `{"type":"workspace_read_file","path":...,"text":...}`（图片路径保持 Image 部件 + `{"type":"workspace_read_file","path":...,"description":"Image file read successfully"}`）。
- `workspace_write_file` 结果 → `{"type":"workspace_write_file","path":...,"name":...,"isDirectory":...,"sizeBytes":...,"updatedAt":...}`。
- `workspace_edit_file` 结果 → `{"type":"workspace_edit_file","path":...,"replacements":...,"matchStrategy":?,"sizeBytes":...,"updatedAt":...}`；diff 继续进 metadata（不随结果发送）。

为统一，给 `WorkspaceFileEntry.toJson()` 加 `type`（或在各 execute 处用 `buildJsonObject { put("type", ...); entry.toJson().forEach { ... } }`）。建议在各 execute 处构造信封：

```kotlin
// workspace_write_file execute
val entry = workspaceRepository.writeTextInRootfs(workspaceId, path, text, overwrite)
listOf(UIMessagePart.Text(buildJsonObject {
    put("type", JsonPrimitive("workspace_write_file"))
    put("path", JsonPrimitive(entry.path))
    put("name", JsonPrimitive(entry.name))
    put("isDirectory", JsonPrimitive(entry.isDirectory))
    put("sizeBytes", JsonPrimitive(entry.sizeBytes))
    put("updatedAt", JsonPrimitive(entry.updatedAt))
}.toString()))
```

- [ ] **Step 2: `workspace_shell` 结果信封**

```kotlin
        val result = workspaceRepository.executeCommand(workspaceId, command, cwd, timeoutMillis)
        listOf(
            UIMessagePart.Text(
                buildJsonObject {
                    put("type", JsonPrimitive("workspace_shell"))
                    put("exitCode", JsonPrimitive(result.exitCode))
                    put("stdout", JsonPrimitive(result.stdout))
                    put("stderr", JsonPrimitive(result.stderr))
                    put("timedOut", JsonPrimitive(result.timedOut))
                    val totalChars = result.stdout.length + result.stderr.length
                    put("totalChars", JsonPrimitive(totalChars))
                    if (result.truncated) put("truncated", JsonPrimitive(true))
                }.toString()
            )
        )
```

- [ ] **Step 3: `workspace_shell` 流式覆盖 executeFlow**

分层：`WorkspaceRepository.executeCommandStreaming` → `WorkspaceManager.executeCommandStreaming` → `WorkspaceShellContext` 带 `onLine` → `HostShellRunner.execute` 逐行回调。改动：

**(a) `workspace/.../WorkspaceShellContext.kt`** 加字段（默认 null，向后兼容）：
```kotlin
data class WorkspaceShellContext(
    // ...现有字段...
    val onLine: ((String) -> Unit)? = null,
)
```

**(b) `workspace/.../WorkspaceShellRunner.kt`** 的 `StreamCollector` 加 `onLine` 回调：每读到一个完整行（按 `\n` 切分）且 `onLine != null` 时调用 `onLine(line)`（在 `synchronized(builder)` 之外调用，避免持有锁回调）。`HostShellRunner.execute` 构造 collector 时传入 `context.onLine`。保留现有截断（128KB）逻辑不变。

**(c) `workspace/.../WorkspaceManager.kt`** 新增（复用现有 `executeCommand` 的逻辑，仅在 context 里带 onLine）：
```kotlin
    fun executeCommandStreaming(
        root: String,
        command: String,
        cwd: String = "",
        timeoutMillis: Long = DEFAULT_COMMAND_TIMEOUT_MS,
        stdin: ByteArray? = null,
        onLine: ((String) -> Unit)? = null,
    ): WorkspaceCommandResult {
        require(command.isNotBlank()) { "Command is required" }
        val workingDir = fileSystem.resolve(filesDir(root), cwd)
        require(workingDir.exists()) { "Working directory does not exist: $cwd" }
        require(workingDir.isDirectory) { "Working path is not a directory: $cwd" }
        return shellRunner.execute(
            WorkspaceShellContext(
                root = root, command = command, cwd = cwd,
                filesDir = filesDir(root), linuxDir = linuxDir(root), tempDir = tempDir(root),
                workingDir = workingDir, timeoutMillis = timeoutMillis, stdin = stdin,
                bindMounts = bindMounts, onLine = onLine,
            )
        )
    }
```

**(d) `app/.../WorkspaceRepository.kt`** 委托：
```kotlin
    suspend fun executeCommandStreaming(
        id: String,
        command: String,
        cwd: String = "",
        timeoutMillis: Long = WorkspaceManager.DEFAULT_COMMAND_TIMEOUT_MS,
        stdin: ByteArray? = null,
        onLine: suspend (String) -> Unit = {},
    ): WorkspaceCommandResult {
        val workspace = dao.getById(id) ?: error("Workspace not found: $id")
        return runInterruptible(Dispatchers.IO) {
            manager.ensureWorkspace(workspace.root)
            val sink = { line: String -> /* 桥接：通过 CoroutineScope 把行发给 onLine（见下） */ }
            manager.executeCommandStreaming(workspace.root, command, cwd, timeoutMillis, stdin, onLine = sink)
        }
    }
```

注意 `onLine` 是 `suspend`，而 shell 收集线程是普通线程——委托里需要一个桥接：在 `runInterruptible` 内起一个 `CoroutineScope(Dispatchers.Default)`，`sink` 里 `scope.launch { onLine(line) }`（行顺序由 launch 保序性近似保证；若需严格保序可用 `Mutex` 或 `Channel`）。实现者可选用 `Channel<String>` + 收集协程转发，保证顺序。

**(e) `workspace_shell` 工具覆盖 `executeFlow`**（抽 helper 复用命令解析与信封构造）：
```kotlin
fun Tool = Tool(
    name = "workspace_shell",
    // ...description/parameters/needsApproval 不变...
    execute = { params -> listOf(UIMessagePart.Text(shellEnvelope(runShell(params)))) },
    executeFlow = { params ->
        flow {
            val lineChannel = Channel<String>(Channel.UNLIMITED)
            val result = workspaceRepository.executeCommandStreaming(
                id = workspaceId,
                command = shellCommand(params),
                cwd = shellCwd(params),
                timeoutMillis = shellTimeoutMillis(params),
            ) { line -> lineChannel.trySend(line) }
            // 转发已缓存行（executeCommandStreaming 返回后 channel 已收齐）
            for (line in lineChannel) emit(ToolOutput.OutputDelta(line))
            emit(ToolOutput.Completed(listOf(UIMessagePart.Text(shellEnvelope(result)))))
        }
    },
)
```
为简化，也可让 `executeCommandStreaming` 的 `onLine` 直接用非 suspend 回调（`((String) -> Unit)?`）在收集线程里调 `emit`——但 `emit` 只能在 flow 协程里调用。**因此必须走 Channel 桥接**（上面方案）或让 collect 侧用 `channelFlow`。实现者任选其一，要求：行保序、`Completed` 在全部 `OutputDelta` 之后发出。

helper（`createShellTool` 文件内私有）：
```kotlin
private fun shellCommand(params: kotlinx.serialization.json.JsonObject): String =
    params.string("command") ?: error("command is required")
private fun shellCwd(params: kotlinx.serialization.json.JsonObject): String =
    (params.string("cwd") ?: defaultCwd.orEmpty()).removePrefix("/workspace/").removePrefix("/workspace")
private fun shellTimeoutMillis(params: kotlinx.serialization.json.JsonObject): Long =
    params.string("timeout")?.toLongOrNull()?.coerceIn(1L, SHELL_TIMEOUT_MAX_SECONDS)?.times(1_000L)
        ?: WorkspaceManager.DEFAULT_COMMAND_TIMEOUT_MS
private fun shellEnvelope(result: WorkspaceCommandResult): String = buildJsonObject {
    put("type", JsonPrimitive("workspace_shell"))
    put("exitCode", JsonPrimitive(result.exitCode))
    put("stdout", JsonPrimitive(result.stdout))
    put("stderr", JsonPrimitive(result.stderr))
    put("timedOut", JsonPrimitive(result.timedOut))
    put("totalChars", JsonPrimitive(result.stdout.length + result.stderr.length))
    if (result.truncated) put("truncated", JsonPrimitive(true))
}.toString()
```

`workspace_shell` 工具覆盖 `executeFlow`：

```kotlin
fun Tool = Tool(
    name = "workspace_shell",
    // ...description/parameters/needsApproval 不变...
    execute = { /* 同步兜底：调内部同步实现 */ },
    executeFlow = { params ->
        flow {
            val command = params.jsonObject.string("command") ?: error("command is required")
            val cwd = (params.jsonObject.string("cwd") ?: defaultCwd.orEmpty())
                .removePrefix("/workspace/").removePrefix("/workspace")
            val timeoutMillis = params.jsonObject.string("timeout")?.toLongOrNull()
                ?.coerceIn(1L, SHELL_TIMEOUT_MAX_SECONDS)?.times(1_000L)
                ?: WorkspaceManager.DEFAULT_COMMAND_TIMEOUT_MS
            val buffer = StringBuilder()
            val result = workspaceRepository.executeCommandStreaming(
                workspaceId = workspaceId,
                command = command,
                cwd = cwd,
                timeoutMillis = timeoutMillis,
                onLine = { line ->
                    buffer.append(line).append('\n')
                    emit(ToolOutput.OutputDelta(line))
                },
            )
            emit(ToolOutput.Completed(listOf(UIMessagePart.Text(buildJsonObject {
                put("type", JsonPrimitive("workspace_shell"))
                put("exitCode", JsonPrimitive(result.exitCode))
                put("stdout", JsonPrimitive(result.stdout))
                put("stderr", JsonPrimitive(result.stderr))
                put("timedOut", JsonPrimitive(result.timedOut))
                put("totalChars", JsonPrimitive(result.stdout.length + result.stderr.length))
                if (result.truncated) put("truncated", JsonPrimitive(true))
            }.toString()))))
        }
    },
)
```

为避免命令解析重复，建议把"命令/工作目录/超时解析 + 信封构造"抽为私有 helper（`parseShellParams(params)`、`shellEnvelope(result)`），execute 与 executeFlow 共用。

- [ ] **Step 4: Verify（CI 编译）** — workspace 模块与 app 模块都会编译。参照 Task 4 Step 4。

- [ ] **Step 5: Commit**

```bash
git add workspace/src/main/java/me/rerere/workspace/WorkspaceShellRunner.kt app/src/main/java/me/rerere/rikkahub/data/ai/tools/WorkspaceTools.kt app/src/main/java/me/rerere/rikkahub/data/repository/WorkspaceRepository.kt
git commit -m "feat(workspace): 工具套 type 信封 + workspace_shell 流式 executeFlow"
```

---

### Task 8: `workspace_read_file` 加 offset/limit 分段读

**Files:**
- Modify: `workspace/src/main/java/me/rerere/workspace/WorkspaceManager.kt`
- Modify: `app/src/main/java/me/rerere/rikkahub/data/repository/WorkspaceRepository.kt`
- Modify: `app/src/main/java/me/rerere/rikkahub/data/ai/tools/WorkspaceTools.kt`

**Interfaces:**
- Produces: `WorkspaceManager.exportRootfsFileRange(root, path, offset, limit, out)`；`WorkspaceRepository.exportRootfsFileRange(workspaceId, path, offset, limit, out)`；`workspace_read_file` 新参数 `offset`/`limit` 与结果字段 `offset`/`limit`/`totalChars`/`hasMore`。

- [ ] **Step 1: workspace 模块范围读**

`WorkspaceManager.kt` 在 `exportRootfsFile` 旁新增：

```kotlin
    /** 读取文件字节范围 [offset, offset+limit)。limit <= 0 表示读到文件尾。 */
    fun exportRootfsFileRange(
        root: String,
        path: String,
        offset: Long,
        limit: Long,
        outputStream: OutputStream,
    ) {
        val file = resolveRootfsFile(root, path)
        file.requireReadableFile(path)
        file.inputStream().use { input ->
            input.skip(offset.coerceAtLeast(0))
            if (limit <= 0) {
                input.copyTo(outputStream)
            } else {
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                var remaining = limit
                while (remaining > 0) {
                    val read = input.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
                    if (read < 0) break
                    outputStream.write(buffer, 0, read)
                    remaining -= read
                }
            }
        }
    }
```

`DEFAULT_BUFFER_SIZE` 若不存在则用 `8192`。import：`java.io.OutputStream`（若未 import）。

- [ ] **Step 2: app WorkspaceRepository 委托**

`WorkspaceRepository.kt` 在 `exportRootfsFile` 旁新增（对齐现有 `id` 参数名）：

```kotlin
    suspend fun exportRootfsFileRange(
        id: String,
        path: String,
        offset: Long,
        limit: Long,
        outputStream: OutputStream,
    ) {
        val workspace = dao.getById(id) ?: error("Workspace not found: $id")
        manager.ensureWorkspace(workspace.root)
        manager.exportRootfsFileRange(workspace.root, path, offset, limit, outputStream)
    }
```

（参照现有 `exportRootfsFile` 的 workspace 解析方式——若它用 `withWorkspace` 或直接取 root，照抄。）

- [ ] **Step 3: `workspace_read_file` 工具加参数与分段**

改 `createReadFileTool`：

- 参数加 `offset`（integer，默认 0，描述"字节偏移，用于分段读大文件"）、`limit`（integer，默认 0=到文件尾，描述"最大读取字节数"）。
- execute 中，图片路径保持现状；文本路径改为范围读：

```kotlin
    execute = {
        val path = it.jsonObject.absolutePath("path")
        if (path.isImagePath()) {
            workspaceRepository.readImageInRootfs(workspaceId, path)
        } else {
            val offset = it.jsonObject["offset"]?.jsonPrimitive?.contentOrNull?.toLongOrNull()?.coerceAtLeast(0) ?: 0L
            val limit = it.jsonObject["limit"]?.jsonPrimitive?.contentOrNull?.toLongOrNull()?.coerceAtLeast(0) ?: 0L
            val totalChars = workspaceRepository.rootfsFileSize(workspaceId, path)
            if (offset >= totalChars && totalChars > 0) {
                error("offset $offset is beyond end of file ($totalChars bytes)")
            }
            val buffer = ByteArrayOutputStream()
            workspaceRepository.exportRootfsFileRange(workspaceId, path, offset, limit, buffer)
            val text = buffer.toString(Charsets.UTF_8.name())
            val hasMore = limit > 0 && offset + limit < totalChars
            listOf(
                UIMessagePart.Text(
                    buildJsonObject {
                        put("type", JsonPrimitive("workspace_read_file"))
                        put("path", JsonPrimitive(path))
                        put("text", JsonPrimitive(text))
                        put("offset", JsonPrimitive(offset))
                        put("limit", JsonPrimitive(limit))
                        put("totalChars", JsonPrimitive(totalChars))
                        put("hasMore", JsonPrimitive(hasMore))
                    }.toString()
                )
            )
        }
    },
```

- [ ] **Step 4: Verify（CI 编译）** — 参照 Task 4 Step 4。

- [ ] **Step 5: Commit**

```bash
git add workspace/src/main/java/me/rerere/workspace/WorkspaceManager.kt app/src/main/java/me/rerere/rikkahub/data/repository/WorkspaceRepository.kt app/src/main/java/me/rerere/rikkahub/data/ai/tools/WorkspaceTools.kt
git commit -m "feat(workspace): workspace_read_file 支持 offset/limit 分段读（hasMore 提示）"
```

---

### Task 9: 本地工具信封 retrofit

**Files:**
- Modify: `app/src/main/java/me/rerere/rikkahub/data/ai/tools/local/*.kt`（calendar_create、calendar_query、clipboard_tool、eval_javascript、get_screen_time、get_time_info、run_workflow、sub_agent、text_to_speech）

**Interfaces:**
- Produces: 上述工具结果套 `type` 信封。`use_skill`（返回原始 markdown）与 `ask_user`（交互流程）**不套信封**。

- [ ] **Step 1: 逐工具加 type 信封**

对每个工具 execute 的结果，用 `buildJsonObject` 包一层 `"type": "<工具名>"`，其余字段保持现有。对简单工具（calendar/clipboard/get_time_info/text_to_speech 等）直接在结果 JSON 上加 `type`；对返回非 JSON 文本的工具（如 sub_agent 返回 sub-agent 回复、eval_javascript 返回执行结果），用 `{"type":"<name>","result": <原内容>}` 包一层。

具体各工具处理（实现者逐一核对现有 execute 返回值）：
- `calendar_create` / `calendar_query`：结果对象加 `"type":"calendar_create"` / `"type":"calendar_query"`。
- `clipboard_tool`：加 `"type":"clipboard_tool"`。
- `get_time_info`：加 `"type":"get_time_info"`。
- `get_screen_time`：加 `"type":"get_screen_time"`。
- `text_to_speech`：若返回路径/状态对象，加 `"type":"text_to_speech"`。
- `eval_javascript` / `run_workflow` / `sub_agent`：结果包成 `{"type":"<name>", ...}`；若原是纯文本则 `{"type":"<name>","result": "<text>"}`。
- `ask_user`、`use_skill`：**不改**。

若某工具结果无法安全改形状（依赖消费方），以"加 type 字段到已有 JSON 对象"为最小改动；纯文本才包 `result` 字段。

- [ ] **Step 2: Verify（CI 编译）**

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/me/rerere/rikkahub/data/ai/tools/local/
git commit -m "feat(local): 本地工具结果套 type 信封（use_skill/ask_user 除外）"
```

---

### Task 10: MCP 失败文本简短化 + 最终 CI 验证

**Files:**
- Modify: `app/src/main/java/me/rerere/rikkahub/data/ai/mcp/McpManager.kt`

- [ ] **Step 1: MCP 失败文本简短化**

`McpManager.callTool` 的 `McpClientUnavailableException` 分支当前返回长文本。保持简短（仍不套信封，spec §3.2）：

```kotlin
        } catch (e: McpClientUnavailableException) {
            return listOf(UIMessagePart.Text("MCP tool unavailable: ${e.message ?: e.javaClass.simpleName}"))
        }
```

- [ ] **Step 2: 全量静态 review + CI**

- 复查每个改过的 execute 是否信封格式一致（`type` 首字段，camelCase）。
- 复查 GenerationHandler 不再有任何 `stackTraceToString`/`printStackTrace` 进模型。
- push + 触发 nightly-build-debug，`gh run watch ... --exit-status` 期望通过。
- 手动验证清单（装 debug APK）：
  1. 让 AI 调 `workspace_shell` 长命令 → 执行中有实时输出（Plan 2 前显示 loading，但状态/输出已写入消息）。
  2. 调 `conversation_search`/`search_web` → 模型收到的结果带 `type` 信封。
  3. 触发工具异常 → 模型看到紧凑 `{type,error,message}` 而非堆栈。
  4. `workspace_read_file` 用 `offset`/`limit` 分段读大文件 → 返回 `hasMore`。

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/me/rerere/rikkahub/data/ai/mcp/McpManager.kt
git commit -m "refactor(mcp): MCP 失败文本简短化（不套信封）"
```

---

### Task 11: `workspace_glob` / `workspace_grep` 工具（照 Agora file_glob/file_grep，RikkaHub 命名）

**Files:**
- Modify: `workspace/src/main/java/me/rerere/workspace/WorkspaceManager.kt`（无改动——`glob`/`grep` 已存在，仅确认签名）
- Modify: `app/src/main/java/me/rerere/rikkahub/data/repository/WorkspaceRepository.kt`
- Modify: `app/src/main/java/me/rerere/rikkahub/data/ai/tools/WorkspaceTools.kt`
- Modify: `app/src/main/java/me/rerere/rikkahub/ui/components/ai/ToolSelector.kt`（ALL_BASE_TOOLS）

**Interfaces:**
- Consumes（已存在）: `WorkspaceManager.glob(root, pattern, path="") : List<WorkspaceFileEntry>`；`WorkspaceManager.grep(root, query, path="", regex=false, ignoreCase=true, includeGlob=null) : List<WorkspaceSearchMatch>`；`WorkspaceSearchMatch(path, line, text)`；`WorkspaceFileEntry(path, name, isDirectory, sizeBytes, updatedAt)`。
- Produces: `WorkspaceRepository.glob(id, pattern, path)` / `WorkspaceRepository.grep(id, query, path, regex, ignoreCase, includeGlob)` 委托；两个工具 `workspace_glob` / `workspace_grep`（camelCase 信封，`workspace_` 前缀，**无 `server`**——RikkaHub 纯本地）。
- Plan 2 联动：`ToolKind` 加 `FILE_GLOB`/`FILE_GREP`，`kindFor` 映射 `workspace_glob→FILE_GLOB`、`workspace_grep→FILE_GREP`。

- [ ] **Step 1: `WorkspaceRepository` 委托**（对齐现有 `id`/dao/ensureWorkspace 模式，参照 `executeCommand`/`exportRootfsFileRange`）

```kotlin
    suspend fun glob(
        id: String,
        pattern: String,
        path: String = "",
    ): List<WorkspaceFileEntry> = withContext(Dispatchers.IO) {
        val workspace = dao.getById(id) ?: error("Workspace not found: $id")
        manager.ensureWorkspace(workspace.root)
        manager.glob(workspace.root, pattern, path)
    }

    suspend fun grep(
        id: String,
        query: String,
        path: String = "",
        regex: Boolean = false,
        ignoreCase: Boolean = true,
        includeGlob: String? = null,
    ): List<WorkspaceSearchMatch> = withContext(Dispatchers.IO) {
        val workspace = dao.getById(id) ?: error("Workspace not found: $id")
        manager.ensureWorkspace(workspace.root)
        manager.grep(workspace.root, query, path, regex, ignoreCase, includeGlob)
    }
```

import 补：`me.rerere.workspace.WorkspaceSearchMatch`（`WorkspaceFileEntry` 已 import）。

- [ ] **Step 2: 两个工具定义**（`WorkspaceTools.kt`，`createWorkspaceTools` 列表加两项）

`workspace_glob`（只读，审批默认 false）：

```kotlin
private fun createGlobTool(
    workspaceId: String,
    needsApproval: (String) -> Boolean,
    workspaceRepository: WorkspaceRepository,
) = Tool(
    name = "workspace_glob",
    description = """
        List files matching a glob pattern in the assistant's bound workspace Rootfs /workspace area.
        The pattern is matched against file names (e.g. '*.go', '**/*.md'). 'path' is an optional base
        directory relative to /workspace. Returns path, name, isDirectory, sizeBytes and updatedAt for each file.
    """.trimIndent().replace("\n", " "),
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("pattern", buildJsonObject {
                    put("type", "string")
                    put("description", "Glob pattern matched against file names (e.g. '*.go', '**/*.md')")
                })
                put("path", buildJsonObject {
                    put("type", "string")
                    put("description", "Optional base directory relative to /workspace. Omit for the workspace root.")
                })
            },
            required = listOf("pattern"),
        )
    },
    needsApproval = { needsApproval("workspace_glob") },
    execute = {
        val pattern = it.jsonObject.string("pattern") ?: error("pattern is required")
        val path = it.jsonObject.string("path").orEmpty()
        val files = workspaceRepository.glob(workspaceId, pattern, path)
        listOf(
            UIMessagePart.Text(
                buildJsonObject {
                    put("type", JsonPrimitive("workspace_glob"))
                    put("pattern", JsonPrimitive(pattern))
                    putJsonArray("files") {
                        files.forEach { f ->
                            add(buildJsonObject {
                                put("path", JsonPrimitive(f.path))
                                put("name", JsonPrimitive(f.name))
                                put("isDirectory", JsonPrimitive(f.isDirectory))
                                put("sizeBytes", JsonPrimitive(f.sizeBytes))
                                put("updatedAt", JsonPrimitive(f.updatedAt))
                            })
                        }
                    }
                }.toString()
            )
        )
    },
)
```

`workspace_grep`（只读，审批默认 false；`regex` 默认 false=字面匹配，`glob` 为文件过滤）：

```kotlin
private fun createGrepTool(
    workspaceId: String,
    needsApproval: (String) -> Boolean,
    workspaceRepository: WorkspaceRepository,
) = Tool(
    name = "workspace_grep",
    description = """
        Search for text or regex in files under the assistant's bound workspace Rootfs /workspace area.
        Set 'regex'=true to treat the pattern as a regular expression (default is literal match).
        'path' is an optional base directory relative to /workspace; 'glob' is an optional file-name filter.
        Returns matching lines with file path, line number and content.
    """.trimIndent().replace("\n", " "),
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("pattern", buildJsonObject {
                    put("type", "string")
                    put("description", "Text to search for, or a regular expression when regex=true")
                })
                put("path", buildJsonObject {
                    put("type", "string")
                    put("description", "Optional base directory relative to /workspace. Omit to search the workspace root.")
                })
                put("regex", buildJsonObject {
                    put("type", "boolean")
                    put("description", "Whether to treat the pattern as a regular expression (default false = literal match)")
                })
                put("glob", buildJsonObject {
                    put("type", "string")
                    put("description", "Optional file-name glob filter (e.g. '*.kt')")
                })
            },
            required = listOf("pattern"),
        )
    },
    needsApproval = { needsApproval("workspace_grep") },
    execute = {
        val pattern = it.jsonObject.string("pattern") ?: error("pattern is required")
        val path = it.jsonObject.string("path").orEmpty()
        val regex = it.jsonObject["regex"]?.jsonPrimitive?.contentOrNull?.toBooleanStrictOrNull() ?: false
        val glob = it.jsonObject.string("glob")
        val matches = workspaceRepository.grep(
            id = workspaceId, query = pattern, path = path, regex = regex,
            ignoreCase = true, includeGlob = glob,
        )
        listOf(
            UIMessagePart.Text(
                buildJsonObject {
                    put("type", JsonPrimitive("workspace_grep"))
                    put("pattern", JsonPrimitive(pattern))
                    putJsonArray("matches") {
                        matches.forEach { m ->
                            add(buildJsonObject {
                                put("path", JsonPrimitive(m.path))
                                put("line", JsonPrimitive(m.line))
                                put("text", JsonPrimitive(m.text))
                            })
                        }
                    }
                }.toString()
            )
        )
    },
)
```

在 `createWorkspaceTools` 的返回列表加两项（在 `createShellTool` 之后）：

```kotlin
    return listOf(
        createReadFileTool(workspaceId, ::needsApproval, workspaceRepository),
        createWriteFileTool(workspaceId, ::needsApproval, workspaceRepository),
        createEditFileTool(workspaceId, ::needsApproval, workspaceRepository),
        createShellTool(workspaceId, ::needsApproval, workspaceRepository, shellCwd),
        createGlobTool(workspaceId, ::needsApproval, workspaceRepository),
        createGrepTool(workspaceId, ::needsApproval, workspaceRepository),
    )
```

- [ ] **Step 3: 审批默认 + 工具选择器注册**

`WorkspaceToolDefaultApprovals` 加：
```kotlin
    "workspace_glob" to false,
    "workspace_grep" to false,
```

`ToolSelector.kt` 的 `ALL_BASE_TOOLS` 加（`workspace_shell` 之后）：
```kotlin
    "workspace_glob",
    "workspace_grep",
```

- [ ] **Step 4: Verify（CI 编译）** — 参照 Task 4 Step 4（push 再触发）。

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/me/rerere/rikkahub/data/repository/WorkspaceRepository.kt app/src/main/java/me/rerere/rikkahub/data/ai/tools/WorkspaceTools.kt app/src/main/java/me/rerere/rikkahub/ui/components/ai/ToolSelector.kt
git commit -m "feat(workspace): workspace_glob/workspace_grep 工具（照 Agora file_glob/file_grep）"
```

---

## Self-Review 结论

- **Spec 覆盖**：§2.1/2.2/2.3/2.4（Task 1/2/3/4）、§3.1（Task 4 安全网 + Task 5/7 自限）、§3.2（Task 5/6/7/9）、§3.3（Task 4）、§3.4（Task 8）。展示层 §4 全部在 Plan 2。
- **已知在计划阶段修正的 spec 偏差**：`Tool.execute` 保持返回 `List<UIMessagePart>`，新增 `executeFlow` 字段（spec §2.2 写的是改 execute 签名——实现取"新增 executeFlow + 默认 adapter"，行为等价且不破坏 15+ 工具）；**移除 Room 25→26 migration**（TEXT 列存 JSON，字段带默认值，Json 配置兼容）。
- **无占位符**；后续任务引用的类型（`ToolOutput`/`ToolState`/`inferToolState`/`parseEnvelope`/`executeFlow`/`exportRootfsFileRange`/`executeCommandStreaming`）均在前面任务定义。
