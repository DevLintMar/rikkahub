# 工具调用：对齐 Agora 的截断 / 喂 AI 信封 / grouped timeline 展示

**日期**：2026-08-02
**状态**：设计规格（brainstorming 产物）
**范围**：RikkaHub 工具调用三层对齐 Agora —— ① 截断机制 ② 喂 AI 的信封与错误契约 ③ grouped timeline 聚合展示。持久化守卫（Agora `MessagePersistenceGuard`）**本设计明确不做**，另列"延迟项"。

---

## 1. 背景与目标

### 现状（RikkaHub）

- **截断**：`GenerationHandler.maybeTruncateToolOutput`（32KB 阈值）在工具文本输出超限且助手有 shell 权限时，把全文写入 `filesDir/tool_outputs/{toolCallId}.txt`，只给模型 4KB 预览 + "用 shell cat/grep 文件"指针。模型拿不到全文，UI 也因截断标记非合法 JSON 而解析失败显示不出结果。
- **喂 AI 形式**：工具结果是无 `type` 信封的散装 JSON；工具抛异常时把**完整 Java 堆栈**倒进 `{"error": "..."}` 喂给模型。
- **展示**：`ChainOfThought` 折叠态显示"最后 N 步 + 显示更多"；工具步骤摘要需展开才可见；无执行状态字段、无实时输出。

### 目标（对齐 Agora）

1. 截断 = 工具级自限 + 硬性安全网，模型拿**完整可处理的量**（≤100KB），不再藏进文件。
2. 喂 AI = 统一 `type` 信封 + 紧凑错误码，堆栈不进模型。
3. 展示 = **grouped timeline**：聚合折叠头「思考X秒·调用X个工具」→ 展开列表（每段显示名 + 一行概览）→ 点击进详情。保留每工具小图标。

### 非目标（本设计不做）

- **持久化守卫**：Agora `MessagePersistenceGuard`（防 CursorWindow 2MB 行超限）暂不引入。RikkaHub 一条消息一个 node 行、单行风险低；等 100KB 内容落地后看实际行尺寸再评估。
- **引入 Agora `MessageSegment` 模型**：保持 RikkaHub `UIMessage`/`UIMessagePart` 模型，只加必要字段。
- **Agora 的 timeline / compact 两种模式**：只做 grouped timeline（用户选定）。`ToolCallDisplayModes` 多模式切换不做。

---

## 2. 消息模型与流式执行

### 2.1 `UIMessagePart.Tool` 加字段（ai 模块 `ui/Message.kt`）

```kotlin
data class Tool(
    val toolCallId: String,
    val toolName: String,
    val input: String,
    val output: List<UIMessagePart>,
    val approvalState: ToolApprovalState = ToolApprovalState.Auto,
    val toolState: ToolState = ToolState.CALLING,   // 新增
    val liveOutput: String? = null,                  // 新增：实时输出缓冲（仅执行中）
    override var metadata: JsonObject? = null
)
```

`ToolState` 枚举（新增，对齐 Agora `ToolExecutionStates`）：

```kotlin
enum class ToolState { CALLING, RUNNING, SUCCEEDED, EMPTY, FAILED, STOPPED }
```

- `BACKGROUND_RUNNING` 不做（workspace_shell 的后台任务暂不单独呈现，归入 `RUNNING`/`SUCCEEDED`）。
- 状态来源：执行期回传（CALLING→RUNNING）+ 结果信封推断（见 §3.2）。
- `liveOutput` 用 `@Transient` 标注**不持久化**（仅执行中内存存在，结束后清空，避免 DB 膨胀）。`toolState` 持久化。

### 2.2 `Tool.execute` 改流式（ai 模块 `core/Tool.kt`）

现状：`fun execute(args: JsonElement): List<UIMessagePart>`（同步）。

改为：

```kotlin
sealed interface ToolOutput {
    data class OutputDelta(val text: String) : ToolOutput  // 只给用户看，绝不发模型
    data class Progress(val message: String) : ToolOutput
    data class Completed(val parts: List<UIMessagePart>) : ToolOutput  // 唯一权威结果
}

fun execute(args: JsonElement): Flow<ToolOutput>
```

契约：
- 每个 `Tool` 必须**恰好一次** emit `Completed`；`OutputDelta`/`Progress` 可任意多次、可省略。
- `OutputDelta` 绝不进入发给模型的 `tool_result`（模型只在 `Completed` 后拿到结果）。

适配：
- **一次性工具**（search_web / scrape_web / recent_chats / conversation_search / read_conversation / use_skill / memory / clipboard / tts / screen_time / calendar / ask_user 等）走默认 adapter：
  ```kotlin
  fun execute(args): Flow<ToolOutput> = flow { emit(Completed(executeOnce(args))) }
  ```
  逻辑不动，只改包装。
- **workspace_shell 深度流式**：执行中 emit `OutputDelta`（stdout/stderr 逐行），`Completed` 时给最终信封 JSON（见 §3.2）。唯一真正流式的工具。
- **MCP 工具**（`ChatService.kt:716` 包装的 `mcpManager.callTool`）：同样走一次性 adapter，不流式。

### 2.3 GenerationHandler 改造（app 模块 `data/ai/GenerationHandler.kt`）

- `execute(args)` 调用点（~L287）改为 `collect` Flow：
  - `OutputDelta`：更新对应 `tool.liveOutput`、`toolState=RUNNING`，通过现有 `onUpdateMessages` 流式推给 UI；不中断主生成流。
  - `Progress`：`toolState=RUNNING`，文案可选进 UI。
  - `Completed`：写入 `tool.output`，由信封推断最终 `toolState`（§3.2），随后进入发模型的 tool_result。
- 异常路径（~L296）：`runCatching { execute() }` 的失败分支从"堆栈进模型"改为紧凑错误信封（§3.3），完整堆栈只记 `Log.e`。

### 2.4 持久化

- `toolState` 是 `UIMessagePart.Tool` 的序列化字段，存于 `message_node.messages` TEXT 列（序列化 JSON）——**列不变，不需要 Room migration**。
- `JsonInstant` 配置 `ignoreUnknownKeys=true` + `encodeDefaults=true`：旧 node JSON 缺新字段自动补默认值（`toolState=CALLING`），向后兼容。
- `liveOutput` 用 `@Transient` 标注，**不持久化**（仅流式期间内存中存在，结束后清空）。

---

## 3. 截断机制与喂 AI 信封/错误契约

### 3.1 截断机制（对齐 Agora）

**移除**：`GenerationHandler.maybeTruncateToolOutput`（32KB + 落盘 + shell 指针）整体删除。

**① 工具级自限（主要机制）**：产出大结果的自研工具（`workspace_shell`、`scrape_web`、`conversation_search`、`read_conversation`、`file_read`）在**信封内**自行截断 payload 并自报：

```json
{"type": "workspace_shell", "exitCode": 0, "stdout": "<截断到上限>", "stderr": "", "truncated": true, "totalChars": 452313}
```

- 模型看到合法 JSON + 明确提示（结果被截断、总共 N 字），可按需用 shell grep / read 等工具取更多——即 Agora `web_fetch maxChars + truncated:true` 的协商协议。

各自限工具的具体上限（自报 `truncated`/`totalChars`）：
| 工具 | payload 上限 | 说明 |
|---|---|---|
| `workspace_shell` | 依赖 workspace 层 128KB 上限 | stdout/stderr 任一被 workspace 层截断即自报 |
| `scrape_web` | 截到 32KB | 超限自报 |
| `workspace_read_file` | 由 `limit` 参数控制（§3.4） | 分段读 + `hasMore` 提示；无 `limit` 时整读受安全网约束 |
| `conversation_search` / `read_conversation` | 天然有界（窗口 200 条 / 分页 ≤100 条） | 一般不自报；仅当整体超 app 层安全网时由安全网兜底 |

**② 硬性安全网**：GenerationHandler 对工具 `Completed` 的文本输出做最后防线 `take(MAX_TOOL_RESULT_LENGTH = 100_000)`，超限追加 `…[truncated]` 标记。兜底对象：MCP 原始文本 / use_skill markdown 这类非信封大输出。信封工具已自限，极少触发。

- workspace 层 `WorkspaceShellRunner.MAX_OUTPUT_CHARS = 128KB` 保留不动（app 层 100KB 已是瓶颈）。
- 常量命名/取值对齐 Agora：`MAX_TOOL_RESULT_LENGTH = 100_000`。

### 3.2 信封格式（所有自研工具统一）

每个自研工具的结果是带 `type` 字段的 JSON（`type` 值 = 工具名，供展示 resolver 对应）：

```json
{"type": "web_search", "query": "番茄炒蛋", "items": [...], "images": [...]}
{"type": "web_fetch", "url": "...", "text": "...", "truncated": false, "totalChars": 1240}
{"type": "conversation_search", "query": "...", "results": [...]}
{"type": "workspace_shell", "exitCode": 0, "stdout": "...", "stderr": "", "truncated": false, "totalChars": 1234}
```

语义字段（约定）：
| 字段 | 含义 | 用于 |
|---|---|---|
| `type` | 工具名 | 展示 resolver 映射 ToolKind |
| `truncated` / `totalChars` | 是否截断 / 原始总字符数 | 模型自省 + 展示概览 |
| `exitCode` | shell 退出码 | 展示状态（非 0 → FAILED） |
| `error` | 错误码（见 §3.3） | 展示状态（→ FAILED） |

**字段命名决策**：信封字段保持 RikkaHub 现有 **camelCase** 约定（`exitCode`/`stdout`/`stderr`/`text`），只新增 `type` 字段；**不**对齐 Agora 的 snake_case（`exit_code`/`old_string` 等）（用户决策）。展示 resolver 解析 camelCase。

**不套信封**：MCP 工具结果（服务器定义内容，原样）、`use_skill` 结果（原始 markdown）。展示走 `UNKNOWN` 兜底。

### 3.4 `workspace_read_file` 新增 offset/limit 分段读（用户要求）

现状：整文件读入内存，超 8MB（`MAX_READ_FILE_BYTES`）直接报错"用 shell head/tail/grep"。新增可选参数，使模型能分段读大文件（对齐 Agora `file_read` 的 offset/limit）：

- `offset`（Int，默认 0）：字节偏移起点。
- `limit`（Int，默认 0 = 读到文件尾）：本次最大读取字节数。

成功结果信封新增分页字段（保持 camelCase）：
```json
{"type": "workspace_read_file", "path": "/workspace/big.log", "text": "<分段内容>", "offset": 0, "limit": 65536, "totalChars": 137241, "hasMore": true}
```

- `totalChars`：文件总字符数；`hasMore` = `offset + limit < totalChars`，提示模型可继续分段读。
- `offset` 越界 / `path` 校验失败仍走错误信封。
- 图片路径保持现状（整图返回，不分段）。
- 参数描述里注明分段读用途，引导模型读大文件时用小 limit。

### 3.3 错误契约

异常路径输出改为紧凑错误码信封（完整堆栈只记 logcat）：

```json
{"type": "<tool_name>", "error": "<code>", "message": "<一句话原因>"}
```

错误码初稿：
| 码 | 场景 |
|---|---|
| `no_query` / `no_results` | 搜索/查询缺少参数 / 无结果 |
| `not_found` | 会话/文件/记忆/技能不存在 |
| `invalid_args` | 参数校验失败 |
| `timeout` | shell 超时 |
| `fetch_error` / `network_error` | web 抓取/网络失败 |
| `bad_request` / `unauthorized` | HTTP 4xx |
| `error` | 其余异常兜底，`message` 带简短原因 |

- 完整 Java 堆栈不再进模型。
- MCP 调用失败（当前 `McpManager.callTool` 返回 `"Failed to execute MCP tool: ..."` 纯文本）改为简短错误文本，仍不套信封。

---

## 4. 展示层（grouped timeline）

### 4.1 中心解析层（新增 `ui/components/message/tools/ToolPresentation.kt`）

把工具信封解析成结构化对象，统一驱动状态、概览、标题：

```kotlin
data class ToolPresentation(
    val kind: ToolKind,          // 由 resolver 基于 tool.toolName 映射（见下方定案），未知 → UNKNOWN
    val state: ToolState,        // 从 tool.toolState + 信封 error/exitCode 推断
    val subject: String?,        // query / url / path / 命令名（一行概览素材）
    val count: Int?,             // items / files / matches 数量
    val errorMessage: String?,
    val exitCode: Int?,
)
```

**Resolver 依据定案**：展示层 resolver 一律基于 `tool.toolName`（字面工具名）映射 `ToolKind`，**不依赖信封 `type` 字段**。`SearchTools` 的 `type` 字段保持 `web_search`/`web_fetch` 等模型侧语义名（不改），二者不一致由 resolver 的 toolName 映射规避——不再 retrofit SearchTools。

`ToolKind` 枚举（对齐 Agora）：`WEB_SEARCH / WEB_FETCH / CONVERSATION_SEARCH / CONVERSATION_LIST / CONVERSATION_READ / SHELL_EXECUTE / SHELL_JOB_* / FILE_READ / FILE_WRITE / FILE_EDIT / FILE_GLOB / FILE_GREP / MEMORY_* / IMAGE_GENERATE / TASK_* / LOOP_* / UNKNOWN`。RikkaHub 现有工具映射到对应 kind；**MCP 工具名 / use_skill → `UNKNOWN`**。

状态推断规则：
| 输入 | 结果 |
|---|---|
| `toolState = RUNNING` 且无结果 | `RUNNING` |
| 信封含 `error` 码 | `FAILED` |
| 信封 `exitCode != 0` | `FAILED` |
| 信封语义为空（count==0 / 空内容） | `EMPTY` |
| `toolState = STOPPED` | `STOPPED` |
| 其余 | `SUCCEEDED` |

### 4.2 一行概览（`@Composable fun toolSummary(presentation): String`）

按 `kind × state` 产出单行文案（对齐 Agora `toolSummary`）：
- 执行中：`正在搜索 "番茄炒蛋"…` / `正在执行 gradle build…`（shell 显示 `liveOutput` 最后一行）
- 成功：`✓ 找到 5 个结果` / `exit 0` / `已读取 memory: xxx`
- 空：`未找到结果` / `未找到匹配`
- 失败：`⚠ exit 1` / `错误：no_results`

字符串走 `strings.xml`，遵循现有 `tool_ui_*` / `chat_message_tool_*` 字符串资源模式。

### 4.3 聚合卡片（核心交互，对齐 Agora `CompactSegmentBlock`）

每个 `MessagePartBlock.ThinkingBlock`（一组连续 思考+工具）渲染为一张聚合卡片：

```
┌─ 🧠 思考了 5 秒 · 调用了 3 个工具          ▾ ┐   ← 折叠头（可点击）
└──────────────────────────────────────────────┘
        ↓ 点击头部展开
┌─ 🧠 思考了 5 秒 · 调用了 3 个工具          ▴ ┐
│  💭 [思考片段预览]                           │   ← reasoning 段
│  ─────────────────────────────────────      │
│  🔍 搜索 "番茄炒蛋的做法"                    │   ← tool 段：[小图标] 显示名
│     ✓ 找到 5 个结果                         │   ← 一行概览
│  ─────────────────────────────────────      │
│  💻 gradle build   exit 1                   │
│     ⚠ 退出码 1                             │
└──────────────────────────────────────────────┘
```

**折叠头**（关键细节）：
- 布局：`[🧠 AiBrain02] 「思考X秒·调用X个工具」 …… [ArrowUp01/ArrowDown01]`。
  - **箭头在最右**（对齐 Agora `CompactSegmentBlock` 头部）。复用 RikkaHub 现有 `HugeIcons.ArrowUp01/ArrowDown01`，从当前 `ChainOfThought` 折叠控制的**左侧移到最右**。
  - 左侧图标：`HugeIcons.AiBrain02`（import `me.rerere.hugeicons.stroke.AiBrain02`），用户已选定；实现时 CI 编译验证图标名存在。
- 文案：
  - 完成态：`思考了 X 秒 · 调用了 N 个工具`（X = reasoning 各段 `finishedAt - createdAt` 求和，秒向下取整；N = 有结果的 tool 段数）。
  - 无思考、有工具：`调用了 N 个工具`。
  - 执行中（还有工具在跑）：显示最后工具名 + 执行态文案（对齐 Agora `isToolCalling` 分支）。
- 无"最后两步预览"：折叠态只有聚合头（明确**不做** RikkaHub 当前"显示最后 N 步 + 更多"的尾巴）。

**展开列表**：每段一行，段间分隔线：
- reasoning 段：`[Sparkles] 思考预览`（单行省略），点击展开看全文（保留 `ChatMessageReasoningStep`）。
- tool 段：`[renderer.icon(context)] 显示名` + 换行缩进的一行概览（§4.2）。**保留每工具小图标**（`ToolUIRenderer.icon`）。
- **点击某行** → 现有 `ModalBottomSheet` + `renderer.Preview` 详情（保留）。

### 4.4 组件落点

- **改造** `ui/components/ui/ChainOfThought.kt`：折叠控制从"左箭头+显示更多"改为"聚合头（左图标+文案+右箭头）"；展开态列出全部步骤。
- **改造** `ChatMessage.kt`（ThinkingBlock 渲染路径 ~L322）：接入聚合头计算（思考时长/工具数）与新折叠态。
- **改造** `ChatMessageToolStep`：步骤内容在列表里显示一行概览（§4.2），点击进详情；不再依赖"展开步骤才见摘要"。
- **新增** `ToolPresentation.kt` / `toolSummary`；`ToolUIRegistry` 渲染器保留 icon/title/Preview。

### 4.5 MCP / use_skill 处理

- 无注册渲染器 → `ToolKind.UNKNOWN`：显示名从工具名推导（`mcp_xxx` → "Mcp Xxx"），一行概览用通用文案，详情走 `DefaultToolPreview`（JSON/纯文本）。保留现有 `UseSkillToolUI` 对 `use_skill` 的定制。

---

## 5. 测试与验证

- **本机无编译器**：不跑 gradle；所有代码静态编写 + review，真实编译靠 CI（nightly-build-debug）。
- 纯函数单测（`app/src/test/.../tools/`）：
  - `ToolPresentationResolver`：信封 → kind/state/subject/count（含 UNKNOWN 兜底、错误码、exitCode、空语义）。
  - `toolSummary` 各 kind×state 文案。
  - 聚合头计算：思考时长求和、工具计数、无思考分支。
  - `ToolOutput` adapter：一次性工具包装后恰一次 Completed。
- 手动验证（装 debug APK）：长 shell 命令执行中看到实时输出；聚合折叠/展开/点详情；MCP/skill 走 UNKNOWN；错误码展示。

---

## 6. 涉及文件清单（初稿）

| 模块 | 文件 | 改动 |
|---|---|---|
| ai | `ui/Message.kt` | `UIMessagePart.Tool` 加 `toolState`/`liveOutput`；新增 `ToolState` 枚举 |
| ai | `core/Tool.kt` | `execute` 改 `Flow<ToolOutput>`；新增 `ToolOutput` |
| ai | `ui/Message.kt` 序列化 | 新字段纳入序列化 |
| app | `data/ai/GenerationHandler.kt` | collect Flow；删 `maybeTruncateToolOutput`；异常改错误信封；100KB 安全网 |
| app | `data/ai/tools/SearchTools.kt` | 信封 + 错误码 + `scrape_web` 自限 |
| app | `data/ai/tools/ConversationTools.kt` | 信封 + 错误码 + 大结果自限 |
| app | `data/ai/tools/WorkspaceTools.kt` | 信封 + 错误码 + `workspace_shell` 流式自限 + `workspace_read_file` 加 `offset`/`limit` 分段读 |
| app | `data/ai/tools/local/*.kt`（memory/clipboard/tts/calendar/ask_user/skill） | 信封 + 错误码（一次性工具） |
| app | `service/ChatService.kt` | MCP 工具包装 adapter；失败文本简短化 |
| app | `ui/components/message/tools/ToolPresentation.kt`（新增） | 解析器 + `toolSummary` + `ToolKind` |
| app | `ui/components/ui/ChainOfThought.kt` | 聚合折叠头（左 AiBrain02 + 文案 + 右箭头） |
| app | `ui/components/message/ChatMessage.kt` | ThinkingBlock 接入聚合头 |
| app | `ui/components/message/ChatMessageTools.kt` | 列表一行概览 + 点行进详情 |
| app | `values*/strings.xml` | 概览/聚合头文案 |

---

## 7. 延迟项

- 持久化守卫（`MessagePersistenceGuard`）：等 100KB 内容落地后评估行尺寸再定。
- `ToolState.BACKGROUND_RUNNING`（workspace_shell 后台任务独立状态）。
- Agora `ToolCallDisplayModes` 的 timeline / compact 两种附加模式。
- `liveOutput` 持久化（当前设计为不持久化）。
