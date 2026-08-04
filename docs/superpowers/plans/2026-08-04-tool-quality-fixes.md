# 工具调用展示质量修复（6 项）

## Context

用户报告 6 个工具调用 UI 的质量问题（均在 `app` 模块 Compose UI 层 + 一个 producer 输出）：

1. 参数为空时仍显示"参数"分区 → 应隐藏
2. 子代理工具标题"子代理" → "运行子代理"，background 模式 → "运行子代理（后台）"
3. 内置工具（如 calendar_create）error/未执行详情：内容本身已是"参数+调用结果"的 JSON 展开（`DefaultToolPreview`），但标题栏右侧仍有整页 JSON 大开关（冗余）→ 应隐藏
4. web_search / scrape_web 的原始 JSON 文本视图"完全没有语法着色" → 根因 `HighlightText.MAX_CODE_LENGTH = 4096`，大 JSON（scrape 32KB）超过后回退 Plain token
5. ask_user 选项卡执行中显示"询问 N 个问题" → 改为"正在询问用户"
6. web_search 概述文本（search service 的 `answer` 字段）在结果展示/预览中消失 → 根因 `SearchTools.kt` 输出丢弃了 `answer`；同时去掉预览首行"Search: query"（与主标题重复）

## Global Constraints

- 本机无编译器：不运行 gradle，静态编写，编译验证全靠 CI
- CI 流程：先 `git push origin master` 再 `gh workflow run nightly-build-debug.yml --repo DevLintMar/rikkahub --ref master`（push 在前）；`gh` 必须带 `--repo`
- 字符串双写：英文 `values/` + 中文 `values-zh/`（ja/ko/ru/zh-rTW 无 key 回退英文）；删除字符串先 grep 零引用，再从**存在该 key 的所有 locale** 删
- `ToolJsonSection(label, json, showToggle=json!=null, semanticContent)`：semanticContent 必须末位（尾随 lambda）
- 库/API 问题先走 Context7，禁止凭训练数据给代码示例

---

## Task 1: 参数为空时隐藏"参数"分区

**Files:**
- Modify: `app/src/main/java/me/rerere/rikkahub/ui/components/message/tools/ToolDetailCommon.kt`
- Modify: `app/src/main/java/me/rerere/rikkahub/ui/components/message/tools/ToolUI.kt`

`context.arguments` = `tool.inputAsJson()`（空输入 → `{}` 空 JsonObject）。空参数时 `JsonTreeView({})` 本身无内容，整个"参数"分区应隐藏。

- [ ] **Step 1: ToolDetailCommon.kt 加 `isJsonEmpty` 扩展**

新增（放 `formatFileSize` 附近）+ 补 imports（`kotlinx.serialization.json.JsonArray`、`JsonNull`、`JsonObject`）：

```kotlin
/** 工具入参是否为空（{} / [] / null）：空参数时隐藏"参数"分区 */
internal fun JsonElement.isJsonEmpty(): Boolean = when (this) {
    is JsonObject -> isEmpty()
    is JsonArray -> isEmpty()
    is JsonNull -> true
    is JsonPrimitive -> false
}
```

- [ ] **Step 2: ToolJsonBody 加空参数判断**

`ToolDetailCommon.kt` 的 `ToolJsonBody`，把参数分区包进 `if (!context.arguments.isJsonEmpty()) { ... }`：

```kotlin
@Composable
internal fun ToolJsonBody(context: ToolUIContext) {
    Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (!context.arguments.isJsonEmpty()) {
            ToolJsonSection(
                label = stringResource(R.string.tool_ui_arguments),
                json = context.arguments,
            ) {
                JsonTreeView(context.arguments)
            }
        }
        if (context.content != null) {
            ToolJsonSection(
                label = stringResource(R.string.chat_message_tool_call_result),
                json = context.content,
            ) {
                JsonTreeView(context.content)
            }
        }
    }
}
```

- [ ] **Step 3: DefaultToolPreview 加空参数判断**

`ToolUI.kt` 的 `DefaultToolPreview`，同样包参数分区：

```kotlin
Column(
    modifier = Modifier.fillMaxWidth(),
    verticalArrangement = Arrangement.spacedBy(8.dp),
) {
    if (!context.arguments.isJsonEmpty()) {
        ToolJsonSection(
            label = stringResource(R.string.tool_ui_arguments),
            json = context.arguments,
        ) {
            JsonTreeView(context.arguments)
        }
    }
    if (context.tool.output.isNotEmpty()) {
        // ... 其余不变
    }
}
```

- [ ] **Step 4: push + CI**（`git push origin master` → `gh workflow run ...`）

---

## Task 2: 子代理标题 → 运行子代理 / 运行子代理（后台）

**Files:**
- Modify: `app/src/main/java/me/rerere/rikkahub/ui/components/message/tools/BuiltinToolUIs.kt`（`SubAgentToolUI.title`）
- Modify: `app/src/main/res/values/strings.xml` + `app/src/main/res/values-zh/strings.xml`

`SubAgentToolUI.title` 同时驱动步骤标题与详情 Sheet 标题。background 判断：content 的 `mode`（"background"/"synchronous"）优先，回退参数 `run_in_background`（默认 true）。

- [ ] **Step 1: 改 SubAgentToolUI.title**

```kotlin
@Composable
override fun title(context: ToolUIContext): String {
    val isBackground = when (context.content.getStringContent("mode")) {
        "background" -> true
        "synchronous" -> false
        else -> context.arguments.getStringContent("run_in_background") != "false"
    }
    return stringResource(
        if (isBackground) R.string.chat_message_tool_sub_agent_background
        else R.string.chat_message_tool_sub_agent
    )
}
```

- [ ] **Step 2: strings.xml（values/ + values-zh/）**

改 `chat_message_tool_sub_agent` 值：
- en: `Sub-agent` → `Running sub-agent`
- zh: `子代理` → `运行子代理`

新增（en + zh）：
```xml
<string name="chat_message_tool_sub_agent_background">Running sub-agent (background)</string>
<!-- zh: 运行子代理（后台） -->
```

- [ ] **Step 3: push + CI**

---

## Task 3: 内置工具 error/未执行详情不显示整页 JSON 大开关

**Files:**
- Modify: `app/src/main/java/me/rerere/rikkahub/ui/components/message/tools/ToolUI.kt`（接口加方法）
- Modify: `app/src/main/java/me/rerere/rikkahub/ui/components/message/tools/BuiltinToolUIs.kt`（13 个 override）
- Modify: `app/src/main/java/me/rerere/rikkahub/ui/components/message/tools/WorkspaceToolUIs.kt`（6 个 override）
- Modify: `app/src/main/java/me/rerere/rikkahub/ui/components/message/ChatMessageTools.kt`（调用点）

`ToolDetailSheet` 标题栏右侧 CodeSquare 大开关仅在 `jsonBody != null` 时显示。当前 `ChatMessageTools.kt:228` 对**所有** `isBuiltIn` 渲染器传 `ToolJsonBody`。当渲染器 Preview 回退 `DefaultToolPreview`（error / 未执行 / 无语义视图）时，内容本身就是"参数+结果" JSON 展开，大开关冗余。新增接口方法，让各渲染器声明其 Preview 是否为语义化视图。

- [ ] **Step 1: ToolUI.kt 接口加方法**

```kotlin
/** 详情是否为语义化视图（区别于默认"参数+结果" JSON 展开）。false 时详情弹层标题栏不显示整页 JSON 开关（内容本身已是 JSON 展开） */
fun hasSemanticDetail(context: ToolUIContext): Boolean = true
```

- [ ] **Step 2: ChatMessageTools.kt 调用点**

```kotlin
jsonBody = if (renderer.isBuiltIn && renderer.hasSemanticDetail(context)) {
    { ToolJsonBody(context) }
} else {
    null
},
```

- [ ] **Step 3: BuiltinToolUIs.kt 各 override（与各自 Preview 的 fallback 条件一致）**

| 渲染器 | `hasSemanticDetail` |
|---|---|
| MemoryToolUI | `context.content != null && context.content.getStringContent("error") == null` |
| SearchWebToolUI | `context.content != null` |
| ScrapeWebToolUI | `context.content != null` |
| GetTimeInfoToolUI | `context.content != null` |
| ClipboardToolUI | `context.content != null` |
| RecentChatsToolUI | `context.content != null` |
| ConversationSearchToolUI | `context.content != null` |
| SubAgentToolUI | `context.content != null` |
| GetScreenTimeToolUI | `context.content != null && context.content.jsonObjectOrNull?.get("error") == null` |
| CalendarQueryToolUI | `context.content != null && context.content.getStringContent("error") == null` |
| CalendarCreateToolUI | `context.content != null && context.content.getStringContent("error") == null` |
| ReadConversationToolUI | `context.content != null && context.content.getStringContent("error") == null` |
| UseSkillToolUI | `context.tool.output.filterIsInstance<UIMessagePart.Text>().joinToString("\n") { it.text }.trim().isNotBlank()` |

（TextToSpeechToolUI 无 fallback，保持默认 true 不 override）

每个 override 紧贴其 Preview，加一行注释 `// 与 Preview 的 DefaultToolPreview fallback 保持一致`。

- [ ] **Step 4: WorkspaceToolUIs.kt 各 override**

| 渲染器 | `hasSemanticDetail` |
|---|---|
| EditFileToolUI | `diffOf(context) != null` |
| ReadFileToolUI | `textOf(context) != null` |
| WriteFileToolUI | `textOf(context) != null` |
| ShellToolUI | `context.content != null` |
| GlobToolUI | `context.content != null && context.content.getStringContent("error") == null` |
| GrepToolUI | `context.content != null && context.content.getStringContent("error") == null` |

- [ ] **Step 5: push + CI**

---

## Task 4: 原始 JSON 文本视图语法着色上限提升

**Files:**
- Modify: `app/src/main/java/me/rerere/rikkahub/ui/components/richtext/HighlightCodeBlock.kt`
- Modify: `app/src/main/java/me/rerere/rikkahub/ui/components/message/tools/ToolDetailCommon.kt`（`ToolJsonRawText`）
- Modify: `app/src/main/java/me/rerere/rikkahub/ui/components/message/tools/BuiltinToolUIs.kt`（`SearchWebPreview` fallback）

根因：`HighlightText` 中 `MAX_CODE_LENGTH = 4096`，超出后 `highlighter.highlight` 被跳过，整体按 Plain 渲染 → 无着色。web_search（多结果 + 文本）与 scrape_web（32KB）的原始 JSON 均超限。把上限参数化，工具原始 JSON 文本视图传大上限。

- [ ] **Step 1: HighlightCodeBlock.kt 参数化 `maxCodeLength`**

`HighlightText` 签名加默认参数，且判断用参数：

```kotlin
fun HighlightText(
    code: String,
    language: String,
    modifier: Modifier = Modifier,
    colors: HighlightTextColorPalette = HighlightTextColorPalette.Default,
    fontSize: TextUnit = 12.sp,
    fontFamily: FontFamily = FontFamily.Monospace,
    fontStyle: FontStyle = FontStyle.Normal,
    fontWeight: FontWeight = FontWeight.Normal,
    lineHeight: TextUnit = TextUnit.Unspecified,
    overflow: TextOverflow = TextOverflow.Clip,
    softWrap: Boolean = true,
    maxLines: Int = Int.MAX_VALUE,
    minLines: Int = 1,
    maxCodeLength: Int = MAX_CODE_LENGTH,
) {
    ...
    tokens = if (updatedCode.length <= maxCodeLength) {
        highlighter.highlight(updatedCode, updatedLanguage)
    } else {
        listOf(HighlightToken.Plain(content = updatedCode))
    }
```

`HighlightCodeBlock` 签名加 `maxCodeLength: Int = MAX_CODE_LENGTH`，透传给 `CodeBlockDefault(..., maxCodeLength = maxCodeLength)`；`CodeBlockDefault` 加参数并透传给其 `HighlightText(code = displayCode, ..., maxCodeLength = maxCodeLength)`。

- [ ] **Step 2: ToolDetailCommon.kt 加常量 + ToolJsonRawText 传上限**

```kotlin
/** 工具原始 JSON 文本视图的语法高亮长度上限（覆盖 web_search/scrape_web 大响应；超出回退纯文本） */
internal const val TOOL_RAW_JSON_MAX_CODE_LENGTH = 512 * 1024
```

```kotlin
@Composable
internal fun ToolJsonRawText(json: JsonElement) {
    HighlightCodeBlock(
        code = JsonInstantPretty.encodeToString(json),
        language = "json",
        style = TextStyle(fontSize = 10.sp, lineHeight = 12.sp),
        maxCodeLength = TOOL_RAW_JSON_MAX_CODE_LENGTH,
    )
}
```

- [ ] **Step 3: BuiltinToolUIs.kt SearchWebPreview fallback 传上限**

```kotlin
HighlightText(
    code = JsonInstantPretty.encodeToString(content),
    language = "json",
    fontSize = 12.sp,
    maxCodeLength = TOOL_RAW_JSON_MAX_CODE_LENGTH,
)
```

- [ ] **Step 4: push + CI**

---

## Task 5: ask_user 执行中文本 → 正在询问用户

**Files:**
- Modify: `app/src/main/java/me/rerere/rikkahub/ui/components/message/ChatMessageTools.kt`（`AskUserToolStep` label）
- Modify: `app/src/main/res/values/strings.xml` + `app/src/main/res/values-zh/strings.xml`

- [ ] **Step 1: 改 label 逻辑（isPending = 执行中/等待用户回答）**

```kotlin
text = if (isPending) {
    stringResource(R.string.chat_message_tool_ask_running)
} else if (questions.size <= 1) {
    firstQuestion
} else {
    stringResource(R.string.chat_message_tool_ask_questions, questions.size)
},
```

- [ ] **Step 2: strings.xml（values/ + values-zh/）新增**

```xml
<string name="chat_message_tool_ask_running">Asking the user</string>
<!-- zh: 正在询问用户 -->
```

- [ ] **Step 3: push + CI**

---

## Task 6: web_search answer 概述恢复 + 去掉首行"Search: query"

**Files:**
- Modify: `app/src/main/java/me/rerere/rikkahub/data/ai/tools/SearchTools.kt`（producer 输出补 `answer`）
- Modify: `app/src/main/java/me/rerere/rikkahub/ui/components/message/tools/BuiltinToolUIs.kt`（Summary 恢复 answer + SearchWebPreview 加 answer 框、去首行）
- Modify: `app/src/main/res/`（6 个 locale 删死串 `chat_message_tool_search_prefix`）

根因：search service 结果含 `answer` 字段，但 `SearchTools.kt` 输出只保留 query/items/images，`answer` 被丢弃（`getStringContent("answer")` 恒 null）；且 596f08d 删掉了 Summary 里的 answer 渲染。预览首行 `Search: query`（`chat_message_tool_search_prefix`，全仓仅 1 处引用）与主标题"Search web: query"重复。

- [ ] **Step 1: SearchTools.kt 输出补 answer**

`search_web` 的 `buildJsonObject` 加一行（`results` 是 `result.getOrThrow()` 编码出的 JsonObject，含 answer）：

```kotlin
put("answer", results["answer"] ?: JsonNull)
```

补 import：`import kotlinx.serialization.json.JsonNull`

- [ ] **Step 2: SearchWebToolUI.hasSummary + Summary 恢复 answer**

```kotlin
override fun hasSummary(context: ToolUIContext): Boolean =
    context.content.getStringContent("answer") != null || items(context).isNotEmpty()

@Composable
override fun Summary(context: ToolUIContext) {
    context.content.getStringContent("answer")?.let { answer ->
        Text(
            text = answer,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onPrimaryContainer,
            modifier = Modifier.shimmer(isLoading = context.loading),
            maxLines = 3,
            overflow = TextOverflow.Ellipsis,
        )
    }
    val items = items(context)
    if (items.isNotEmpty()) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            FaviconRow(
                urls = items.mapNotNull { it.getStringContent("url") },
                size = 18.dp,
            )
            Text(
                text = stringResource(R.string.chat_message_tool_search_results_count, items.size),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f),
            )
        }
    }
}
```

- [ ] **Step 3: SearchWebPreview 去掉"Search: query"首行 + 加 answer 框 + 收窄参数**

`SearchWebPreview` 移除 `arguments` 参数与 `query` 变量（不再使用），顶部改为 answer 卡片（与 ScrapeWebPreview 的 Markdown 卡片一致）：

```kotlin
@Composable
private fun SearchWebPreview(content: JsonElement) {
    val context = LocalContext.current
    val items = content.jsonObject["items"]?.jsonArray ?: emptyList()
    val images = content.jsonObject["images"]?.jsonArray
        ?.mapNotNull { it.jsonPrimitive.contentOrNull }
        ?.filter { it.isNotBlank() }
        ?: emptyList()

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        content.getStringContent("answer")?.takeIf { it.isNotBlank() }?.let { answer ->
            Card {
                MarkdownBlock(
                    content = answer,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(8.dp)
                )
            }
        }

        if (images.isNotEmpty()) { /* LazyRow 不变 */ }

        if (items.isNotEmpty()) { /* Cards 不变 */ } else {
            HighlightText(
                code = JsonInstantPretty.encodeToString(content),
                language = "json",
                fontSize = 12.sp,
                maxCodeLength = TOOL_RAW_JSON_MAX_CODE_LENGTH,
            )
        }
    }
}
```

调用点改为 `SearchWebPreview(content = content)`（`SearchWebToolUI.Preview`）。

- [ ] **Step 4: 删死串 `chat_message_tool_search_prefix`（6 个 locale）**

从 `values/`、`values-zh/`、`values-ja/`、`values-ko-rKR/`、`values-ru/`、`values-zh-rTW/` 的 `strings.xml` 删除 `<string name="chat_message_tool_search_prefix">...</string>`。

- [ ] **Step 5: push + CI**

---

## Verification

- 每任务独立 commit → `git push origin master` → `gh workflow run nightly-build-debug.yml --repo DevLintMar/rikkahub --ref master`
- CI 红时先 `gh run view <run> --repo DevLintMar/rikkahub --log-failed` 区分 JitPack flake（重跑即绿）与真实编译错误
- 设备级核验（用户）：空参数工具详情无"参数"分区；子代理步骤/详情标题为"运行子代理（后台）"；calendar_create error 详情标题栏无大开关；web_search/scrape_web 原始 JSON 有语法着色；ask_user 执行中显示"正在询问用户"；web_search 摘要/预览恢复概述文本、预览无首行"Search: query"
