# 计划：工具大输出截断机制优化与 UI 空字典 `{}` 根治

## 1. 背景与缺陷根因 (Context & Root Cause)

在日常使用（尤其是通过 `search_web` 调用 Exa 进行网络搜索）时，用户经常遇到工具调用的返回结果在 UI 上展示为一个空空的 `{}`，没有任何内容。

### 深入调研结论
1. **触发截断机制（后端）**：
   - Exa 搜索不仅返回 URL 和标题，还默认返回多段富文本高光（`highlights`）和正文摘录。一个包含 10 条结果的 Exa 响应序列化后字符数极易达到 35KB ~ 100KB+。
   - 在 `app/src/main/java/me/rerere/rikkahub/data/ai/GenerationLoop.kt` 中：
     `MAX_TOOL_OUTPUT_CHARS = 32 * 1024`（32KB = 32,768 字符）。
     当助手开启了工作区 Shell 能力（`workspace_shell`）且工具输出超过 32KB 时，`maybeTruncateToolOutput` 会将全量内容写入 `/tool_outputs/<toolCallId>.txt`，并将返回给消息的文本替换为：
     ```text
     [Tool output truncated: X characters total]
     Full output saved to: /tool_outputs/<toolCallId>.txt
     Use shell to read: `cat /tool_outputs/<toolCallId>.txt`
     Use shell to search: `grep "pattern" /tool_outputs/<toolCallId>.txt`

     <前 4KB 的 preview 内容>
     ```
2. **结构被破坏导致 UI 暴力兜底（前端）**：
   - 截断后的文本以人类自然语言 `[Tool output truncated:` 开头，**不再是合法的 JSON 格式**。
   - `app/src/main/java/me/rerere/rikkahub/ui/components/message/ChatMessageTools.kt` 第 111~121 行：
     ```kotlin
     content = if (tool.isExecuted) {
         runCatching {
             JsonInstant.parseToJsonElement(
                 tool.output.filterIsInstance<UIMessagePart.Text>().joinToString("\n") { it.text }
             )
         }.getOrElse { JsonObject(emptyMap()) } // <-- 致命伪装！
     } else {
         null
     }
     ```
     `parseToJsonElement` 解析报错后，直接将 `context.content` 兜底为 `JsonObject(emptyMap())`（空字典对象）。
   - 结果：
     - `SearchWebToolUI` 中读取 `items` 为空、`answer` 为空，详情卡片一片空白；
     - 切换到 JSON 视图时，系统渲染 `JsonObject(emptyMap())`，大喇喇地展示出一个毫无意义的 `{}`；
     - 截断提示、保存路径 `/tool_outputs/xxx.txt` 以及 4KB 预览内容在前端被**彻底吞噬**，给用户造成“工具执行了但返回是空”的严重错觉。

---

## 2. 核心优化与修复方案 (Architecture & Solutions)

### 优化点 1：截断阈值大幅扩容至 128KB
- 将 `MAX_TOOL_OUTPUT_CHARS` 从 `32 * 1024`（32KB）上调为 `128 * 1024`（128KB = 131,072 字符）。
  - 大多数包含完整正文的 Exa 搜索结果、网页抓取结果或中长脚本输出（30KB~100KB）将直接完整保留原 JSON 结构透传，不再轻易被截断；
- 将 `TOOL_OUTPUT_PREVIEW_CHARS` 从 `4 * 1024`（4KB）上调为 `16 * 1024`（16KB）；
- 协同调整 `ToolOutputLimits.kt` 中的 `MAX_TOOL_RESULT_LENGTH`：从原来的 `100_000` 字符上调为 `160_000`（160KB），确保 128KB 的输出在落盘之后，不会在后续的硬截断环节被截破尾巴。

### 优化点 2：结构化截断信封（保全机器可读与模型可用性）
- 截断发生时，如果原本的工具输出是标准 JSON 结构，优先保留外层结构或采用结构化信封：
  ```json
  {
    "type": "truncated_output",
    "truncated": true,
    "total_chars": 158200,
    "saved_path": "/tool_outputs/<toolCallId>.txt",
    "shell_hint": "cat /tool_outputs/<toolCallId>.txt",
    "preview": "...",
    "original_type": "web_search"
  }
  ```
- 即使是纯文本工具（如长 Shell 命令），也使用带有明确字段的 JSON 信封或打标，兼顾人类提示词与机器可读性，使模型与前端 UI 均能无损解析关键元信息。

### 优化点 3：前端 UI 容错与截断指示视图（彻底消灭空白 `{}`）
- **根除粗暴兜底**：
  在 `ChatMessageTools.kt` 中，若文本非合规 JSON，`context.content` 设为 `null`（或者赋予明确的非 JSON 标记），绝对不能回退成 `JsonObject(emptyMap())`；
- **智能截断检测与横幅渲染**：
  - 在 `ToolDetailSheet` / `DefaultToolPreview` / `SearchWebPreview` 中，检查是否为截断状态（结构化信封或 `[Tool output truncated]` 文本）；
  - 若为截断状态：
    1. 在详情顶部渲染醒目的 **橙色/提示横幅**：明确告知“输出内容过长（X 字符），完整结果已保存至 `/tool_outputs/<toolCallId>.txt`”；
    2. 提供一键复制命令按钮（如 `cat /tool_outputs/<toolCallId>.txt`）；
    3. 下方展示保留的预览文本（支持语法高亮与折叠）；
  - 即使是非截断的纯文本，也直接展示原始文本，绝不再渲染出一个空 `{}`。

---

## 3. 涉及模块与文件清单 (Affected Files)

1. **`app/src/main/java/me/rerere/rikkahub/data/ai/GenerationLoop.kt`**
   - 调整 `MAX_TOOL_OUTPUT_CHARS = 128 * 1024`；
   - 调整 `TOOL_OUTPUT_PREVIEW_CHARS = 16 * 1024`；
   - 改造 `maybeTruncateToolOutput` 生成结构化/兼容截断信封。
2. **`app/src/main/java/me/rerere/rikkahub/data/ai/tools/ToolOutputLimits.kt`**
   - 调整 `MAX_TOOL_RESULT_LENGTH = 160_000`（与 128KB 协调匹配）。
3. **`app/src/main/java/me/rerere/rikkahub/ui/components/message/ChatMessageTools.kt`**
   - 修复 `ToolUIContext.content` 的解析逻辑，移除 `.getOrElse { JsonObject(emptyMap()) }`；
   - 引入非 JSON / 截断文本的安全传递。
4. **`app/src/main/java/me/rerere/rikkahub/ui/components/message/tools/BuiltinToolUIs.kt`**
   - 在 `SearchWebToolUI` 中增加对截断状态的优雅降级展示。
5. **`app/src/main/java/me/rerere/rikkahub/ui/components/message/tools/ToolUI.kt` & `ToolDetailCommon.kt`**
   - 增加通用截断卡片组件（文件路径展示、复制按钮、预览内容渲染）。
6. **单元测试与验证**
   - `app/src/test/java/me/rerere/rikkahub/data/ai/tools/ToolOutputLimitsTest.kt`（新增或更新边界测试）。

---

## 4. 实施节奏安排 (Execution Phases)

- **阶段一（准备与待命，当前阶段）**：
  已完成全链路调查，形成本规范计划文档并归档；业务代码保持原样，暂不修改。
- **阶段二（后续开启实施时）**：
  1. 修改后端阈值常量与截断信封生成逻辑；
  2. 重构前端 UI 上下文解析与截断卡片组件；
  3. 执行单测与模拟器实测（通过 Exa 触发超长搜索验证截断横幅与完整文件查看体验）。
