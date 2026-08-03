# 工具选项卡回归原样式 + 聚合折叠卡修正 + 小转换器修复 实现计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 工具调用选项卡回归最早 RikkaHub 概览样式（去掉"已完成/调用中"摘要行）；聚合折叠卡"正在调用工具…"字体与工具名颜色对齐"思考 X 秒"；聚合文案分隔符改为中文逗号；自动折叠增加三种抑制场景（ask_user 等待/工具详情打开/用户展开思考）；独立单工具调用扁平化；ask_user 等待时标题变"询问 N 个问题"且聚合头不显示"正在调用工具ask_user"；小转换器修复为 json/text 文本框直出；MCP 工具详情规范（无大开关、结果非 JSON 不提供小开关）；以及收尾文案/本地化/代码整洁项。

**Architecture:** 展示层只消费信封、不改 producer。聚合折叠卡显示逻辑集中在 `ChatMessage.kt` 的 `MessagePartsBlock`（聚合头 lambda + `autoExpand`），折叠机制在 `ChainOfThought.kt`。工具选项卡概览在 `ChatMessageTools.kt` 的 `ChatMessageToolStep`。工具详情分区开关在 `ToolDetailCommon.kt` 的 `ToolJsonSection`，默认工具详情在 `ToolUI.kt` 的 `DefaultToolPreview`。所有改动为纯 UI 展示层，不触碰 `ai`/`data` 生产逻辑（除已确认的字符串）。

**Tech Stack:** Kotlin / Jetpack Compose / Material3 / HugeIcons / kotlinx.serialization / 无本地编译器（编译验证靠 CI）。

## Global Constraints

- **本机无编译器**：不运行 gradle。所有代码静态编写 + review；编译验证全靠 CI。
- **CI 流程**：先 `git push origin master` 再 `gh workflow run nightly-build-debug.yml --repo DevLintMar/rikkahub --ref master`（push 在前）。`gh` 必须带 `--repo`。
- **字符串双写**：英文 `values/` + 中文 `values-zh/`。新增字符串只写这两处（ja/ko/ru/zh-rTW 无 key 回退英文）；修改既有格式参数只需这两处。删除字符串时需 grep 全 java 确认零引用，再从**存在该 key 的所有 locale 文件**删除。
- **展示层只消费信封，不改 producer**（信封形状冻结）。
- **文件删除走 `~/.claude/scripts/trash.sh`**（回收站），不用 rm。
- 库/API 问题先走 Context7，禁止凭训练数据给代码示例。
- JitPack `sqlite-android:-SNAPSHOT` / JBR21 下载偶发 flake——**重跑即绿，勿判为代码问题**。
- 图标名（HugeIcons）以 CI 编译为准；本项目只用已验证图标，不新增图标引用。
- 最终审查惯例：大/高风险 diff 用 opus；中小 diff 用 sonnet；纯转写用 haiku。

---

## 任务总览

| Task | 内容 | 主要文件 | 实现者模型 | 审查模型 |
|---|---|---|---|---|
| 1 | 聚合折叠卡颜色/文案 + 独立单工具扁平化 + ask_user 不算"正在调用"（item 2,3,4,8,9-聚合） | ChatMessage.kt + values-zh/strings.xml | haiku | sonnet |
| 2 | 自动折叠抑制 + ask_user 标题（item 5,9-标题） | ChainOfThought.kt + ChatMessageCot.kt + ChatMessage.kt + ChatMessageReasoning.kt + ChatMessageTools.kt | sonnet | opus |
| 3 | 工具选项卡回归原样式 + ToolPresentation 死代码清理（item 1） | ChatMessageTools.kt + 删除 ToolPresentation.kt + 6 locale | haiku | sonnet |
| 4 | 小转换器修复 + MCP 工具详情规范 + Sheet 滚动重置（item 6,7 + 收尾⑧） | ToolDetailCommon.kt + ToolUI.kt + ChatMessageTools.kt + ToolDetailSheet.kt | sonnet | sonnet |
| 5 | 收尾文案/本地化/整洁（收尾①③⑤⑦⑨ + ⑥） | ChatMessage.kt + ChatMessageReasoning.kt + BuiltinToolUIs.kt + Favicon.kt + JsonTreeView.kt + JsonTree.kt + strings | haiku | sonnet |

**已被取代的收尾项（无需动作）**：收尾②（`tool_summary_empty_file` 本地化——Task 3 删除 ToolPresentation.kt 后全部 `tool_summary_*` 变死串）；收尾④（JsonArray import 统一——同在 ToolPresentation.kt）；收尾⑩（DefaultToolPreview 参数区开关 no-op——Task 4 修复小转换器后不再是 no-op）。

**前置约定（本阶段沿用的接口）**：`ChainOfThought(autoExpand, suppressAutoCollapse)`；`ChatMessageReasoningStep(..., interaction: ChainBlockInteractionState?)`；`ChatMessageToolStep(..., interaction: ChainBlockInteractionState?)`；`ToolDetailSheet(title, onDismiss, jsonBody?, content)`；`ToolJsonSection(label, json, semanticContent, showToggle = json != null)`；`ToolUIRenderer.isBuiltIn`。后写的 Task 不得假设前 Task 未完成的接口。

---

### Task 1: 聚合折叠卡颜色/文案 + 独立单工具扁平化 + ask_user 等待不算"正在调用"

**Files:**
- Modify: `app/src/main/java/me/rerere/rikkahub/ui/components/message/ChatMessage.kt`（`MessagePartsBlock` 的 `ThinkingBlock` 分支，约 L343-434）
- Modify: `app/src/main/res/values-zh/strings.xml`（`chain_of_thought_aggregate`）

**Interfaces:**
- Consumes: 无（Task 1 不动 ChainOfThought 签名）。
- Produces: `isSingleToolBlock` 判定；运行态聚合头两个 Text 颜色改为 `onSurfaceVariant.copy(alpha = 0.6f)`；`runningTool` 排除 `isPending` 工具。Task 2 在本任务后的同一 lambda 上继续加 `suppressAutoCollapse`，不冲突。

**背景（为何是"回归"）**：`fe99057` 之前的工具步骤只在选项卡显示 `renderer.title(context)`，摘要（Summary）在展开内容里；`fe99057` 起改为"标题 + 摘要行"的聚合列表行，摘要行即"已完成/调用中"文案。本任务只改聚合折叠卡显示（工具选项卡摘要行的移除在 Task 3）。

- [ ] **Step 1: ChatMessage.kt — 聚合头运行态颜色（item 2/3）+ 独立单工具扁平化（item 8）+ runningTool 排除等待（item 9）**

把 `MessagePartsBlock` 的 `ThinkingBlock` 分支（L343-434）中：

```kotlin
val isReasoningOnlyBlock = block.steps.fastAll { it is ThinkingStep.ReasoningStep }
val (thoughtMs, toolCount) = remember(block.steps) { block.steps.thinkingAggregate() }
val aggregateHeader: (@Composable () -> Unit)? =
    if (isReasoningOnlyBlock) null else {
        @Composable {
            val runningTool = block.steps.asReversed().firstOrNull { step ->
                step is ThinkingStep.ToolStep &&
                    (step.tool.toolState == ToolState.RUNNING ||
                        step.tool.toolState == ToolState.CALLING)
            } as? ThinkingStep.ToolStep
            if (runningTool != null) {
                ChainOfThoughtHeaderRow {
                    Text(
                        text = stringResource(R.string.chain_of_thought_calling),
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = runningTool.tool.toolName,
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            } else {
```

改为：

```kotlin
val isReasoningOnlyBlock = block.steps.fastAll { it is ThinkingStep.ReasoningStep }
// 独立工具调用（无思考且仅一个工具）不折叠成聚合头，直接平铺该工具，高度与独立思考相当
val isSingleToolBlock = block.steps.size == 1 && block.steps[0] is ThinkingStep.ToolStep
val (thoughtMs, toolCount) = remember(block.steps) { block.steps.thinkingAggregate() }
val aggregateHeader: (@Composable () -> Unit)? =
    if (isReasoningOnlyBlock || isSingleToolBlock) null else {
        @Composable {
            val callingColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
            val runningTool = block.steps.asReversed().firstOrNull { step ->
                step is ThinkingStep.ToolStep &&
                    !step.tool.isPending &&  // 等待用户输入(ask_user/审批)不算"正在调用"
                    (step.tool.toolState == ToolState.RUNNING ||
                        step.tool.toolState == ToolState.CALLING)
            } as? ThinkingStep.ToolStep
            if (runningTool != null) {
                ChainOfThoughtHeaderRow {
                    Text(
                        text = stringResource(R.string.chain_of_thought_calling),
                        style = MaterialTheme.typography.titleSmall,
                        color = callingColor,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = runningTool.tool.toolName,
                        style = MaterialTheme.typography.titleSmall,
                        color = callingColor,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            } else {
```

其余（`else` 完成态、`ChainOfThought` 调用、steps 渲染）保持原样。注意 `isSingleToolBlock` 使 `header = null`，ChainOfThought 走非聚合模式；单 step 时 `canCollapse = steps.size(1) > collapsedVisibleCount(2)` 为 false，工具直接平铺显示。

- [ ] **Step 2: values-zh/strings.xml — 聚合文案分隔符 `·` → `，`（item 4）**

把：

```xml
<string name="chain_of_thought_aggregate">思考了 %1$.1f 秒 · 调用了 %2$s</string>
```

改为：

```xml
<string name="chain_of_thought_aggregate">思考了 %1$.1f 秒 ，调用了 %2$s</string>
```

英文 `values/` 保持 `·` 不动（用户描述的是中文文案；英文 "Thought for 1.5 s · called 3 tools" 为规范用法）。ja/ko/ru/zh-rTW 无此 key，回退英文。

- [ ] **Step 3: 自审 + 提交**

`git add app/src/main/java/me/rerere/rikkahub/ui/components/message/ChatMessage.kt app/src/main/res/values-zh/strings.xml`
`git commit -m "fix(ui): 聚合折叠卡运行态颜色对齐思考标签+独立单工具扁平化+ask_user等待不算调用中"`

---

### Task 2: 自动折叠抑制 + ask_user 标题

**Files:**
- Modify: `app/src/main/java/me/rerere/rikkahub/ui/components/ui/ChainOfThought.kt`（签名 + 折叠逻辑 + KDoc）
- Modify: `app/src/main/java/me/rerere/rikkahub/ui/components/message/ChatMessageCot.kt`（新增 `ChainBlockInteractionState`）
- Modify: `app/src/main/java/me/rerere/rikkahub/ui/components/message/ChatMessage.kt`（计算 `suppressCollapse` + 传递 interaction）
- Modify: `app/src/main/java/me/rerere/rikkahub/ui/components/message/ChatMessageReasoning.kt`（interaction 上报）
- Modify: `app/src/main/java/me/rerere/rikkahub/ui/components/message/ChatMessageTools.kt`（interaction + detailOpen + ask_user 标题）

**Interfaces:**
- Consumes: Task 1 的 `isSingleToolBlock`、`callingColor` 已就位。
- Produces: `ChainBlockInteractionState`（`detailOpen: Boolean`、`expandedThoughtCount: Int`）；`ChainOfThought` 新参 `suppressAutoCollapse: Boolean = false`；`ChatMessageReasoningStep`/`ChatMessageToolStep` 新参 `interaction: ChainBlockInteractionState? = null`。Task 3 复用 `interaction`（onDismiss 清 detailOpen 在 Task 2 已含）。

**item 5 语义**：执行完成自动折叠（`autoExpand` true→false）时，若满足任一条件则不折叠：① 块内存在等待用户输入的 ask_user（`isPending`）；② 用户已点开某工具详情 Sheet；③ 用户展开了本折叠栏下的思考内容（`Expanded` 态）。②③ 需跨组件上报 → 每块共享一个 `ChainBlockInteractionState`。

- [ ] **Step 1: ChatMessageCot.kt — 新增交互状态类**

在文件顶部 import 区后追加（保留现有 `groupMessageParts`/`thinkingAggregate` 不动）：

```kotlin
/**
 * 聚合思考块内的用户交互状态（用于抑制执行结束后的自动收起）：
 * - [detailOpen]：是否有工具详情 BottomSheet 打开
 * - [expandedThoughtCount]：处于用户展开态(Expanded)的思考步骤数，>0 表示有用户展开的思考内容
 */
@Stable
class ChainBlockInteractionState {
    var detailOpen by mutableStateOf(false)
    var expandedThoughtCount by mutableStateOf(0)
}
```

新增 imports：

```kotlin
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
```

- [ ] **Step 2: ChainOfThought.kt — 新增 `suppressAutoCollapse`**

把函数签名：

```kotlin
fun <T> ChainOfThought(
    modifier: Modifier = Modifier,
    cardColors: CardColors = CardDefaults.cardColors(
        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
    ),
    steps: List<T>,
    collapsedVisibleCount: Int = 2,
    collapsedAdaptiveWidth: Boolean = false,
    header: (@Composable () -> Unit)? = null,
    autoExpand: Boolean = false,
    content: @Composable ChainOfThoughtScope.(T) -> Unit
) {
    var expanded by remember { mutableStateOf(autoExpand) }
    var lastAutoExpand by remember { mutableStateOf(autoExpand) }
    if (autoExpand != lastAutoExpand) {
        lastAutoExpand = autoExpand
        expanded = autoExpand   // 执行开始(true)强制展开；执行完(false)自动收起
    }
```

改为：

```kotlin
fun <T> ChainOfThought(
    modifier: Modifier = Modifier,
    cardColors: CardColors = CardDefaults.cardColors(
        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
    ),
    steps: List<T>,
    collapsedVisibleCount: Int = 2,
    collapsedAdaptiveWidth: Boolean = false,
    header: (@Composable () -> Unit)? = null,
    autoExpand: Boolean = false,
    suppressAutoCollapse: Boolean = false,
    content: @Composable ChainOfThoughtScope.(T) -> Unit
) {
    var expanded by remember { mutableStateOf(autoExpand) }
    var lastAutoExpand by remember { mutableStateOf(autoExpand) }
    if (autoExpand != lastAutoExpand) {
        lastAutoExpand = autoExpand
        when {
            autoExpand -> expanded = true                    // 执行开始：强制展开
            !suppressAutoCollapse -> expanded = false        // 执行完：默认自动收起，被抑制时保持展开
        }
    }
```

在文件顶部 KDoc（L53-70 的 `ChainOfThought` 注释块）中补两个 `@param`：

```kotlin
 * @param autoExpand 执行期间是否强制展开；true→false 切换时若 [suppressAutoCollapse] 为 true 则保持展开
 * @param suppressAutoCollapse 执行结束(autoExpand true→false)时抑制自动收起（如等待用户输入/详情打开/思考被展开）
```

- [ ] **Step 3: ChatMessage.kt — 计算 `suppressCollapse` 并传递 interaction**

在 `MessagePartsBlock` 的 `ThinkingBlock` 分支（Task 1 之后的状态），把：

```kotlin
val isReasoningOnlyBlock = block.steps.fastAll { it is ThinkingStep.ReasoningStep }
// 独立工具调用（无思考且仅一个工具）不折叠成聚合头，直接平铺该工具，高度与独立思考相当
val isSingleToolBlock = block.steps.size == 1 && block.steps[0] is ThinkingStep.ToolStep
val (thoughtMs, toolCount) = remember(block.steps) { block.steps.thinkingAggregate() }
```

改为：

```kotlin
val isReasoningOnlyBlock = block.steps.fastAll { it is ThinkingStep.ReasoningStep }
// 独立工具调用（无思考且仅一个工具）不折叠成聚合头，直接平铺该工具，高度与独立思考相当
val isSingleToolBlock = block.steps.size == 1 && block.steps[0] is ThinkingStep.ToolStep
// 块级用户交互状态：工具详情打开 / 思考被用户展开 → 抑制执行结束自动收起
val interaction = remember { ChainBlockInteractionState() }
val hasPendingTool = block.steps.any { it is ThinkingStep.ToolStep && it.tool.isPending }
val suppressCollapse = hasPendingTool || interaction.detailOpen || interaction.expandedThoughtCount > 0
val (thoughtMs, toolCount) = remember(block.steps) { block.steps.thinkingAggregate() }
```

再把 `ChainOfThought(...)` 调用补一个参数：

```kotlin
                    ChainOfThought(
                        modifier = Modifier.animateContentSize(),
                        steps = block.steps,
                        collapsedAdaptiveWidth = isReasoningOnlyBlock,
                        autoExpand = loading && !isReasoningOnlyBlock,
                        suppressAutoCollapse = suppressCollapse,
                        cardColors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = settings.displaySetting.bubbleOpacity),
                        ),
                        header = aggregateHeader,
                    ) { step ->
```

并把 steps 渲染的两个子调用补 `interaction`：

```kotlin
                            is ThinkingStep.ReasoningStep -> {
                                key(step.reasoning.createdAt) {
                                    ChatMessageReasoningStep(
                                        reasoning = step.reasoning,
                                        model = model,
                                        assistant = assistant,
                                        collapsedAdaptiveWidth = isReasoningOnlyBlock,
                                        interaction = interaction,
                                    )
                                }
                            }

                            is ThinkingStep.ToolStep -> {
                                key(step.tool.toolCallId.ifBlank { step.hashCode().toString() }) {
                                    ChatMessageToolStep(
                                        tool = step.tool,
                                        loading = loading && !step.tool.isExecuted,
                                        onToolApproval = onToolApproval,
                                        onToolAnswer = onToolAnswer,
                                        interaction = interaction,
                                    )
                                }
                            }
```

- [ ] **Step 4: ChatMessageReasoning.kt — interaction 上报用户展开**

在 `ChatMessageReasoningStep` 签名加参：

```kotlin
@Composable
fun ChainOfThoughtScope.ChatMessageReasoningStep(
    reasoning: UIMessagePart.Reasoning,
    model: Model?,
    assistant: Assistant?,
    fadeHeight: Float = 64f,
    collapsedAdaptiveWidth: Boolean = false,
    interaction: ChainBlockInteractionState? = null,
) {
```

在函数体 `val (state, loading) = rememberReasoningState(reasoning)` 之后插入：

```kotlin
    // 上报用户展开的思考步骤：Expanded 计数随状态增减，块据此抑制执行结束自动收起
    // （用 DisposableEffect 保证块销毁/状态切换时计数正确回落）
    DisposableEffect(state.expandState) {
        val expanded = state.expandState == ReasoningCardState.Expanded
        if (interaction != null && expanded) interaction.expandedThoughtCount++
        onDispose {
            if (interaction != null && expanded) interaction.expandedThoughtCount--
        }
    }
```

新增 import：`androidx.compose.runtime.DisposableEffect`。

- [ ] **Step 5: ChatMessageTools.kt — interaction + detailOpen + ask_user 标题（item 9）**

(5a) `ChatMessageToolStep` 签名加参：

```kotlin
@Composable
fun ChainOfThoughtScope.ChatMessageToolStep(
    tool: UIMessagePart.Tool,
    loading: Boolean = false,
    onToolApproval: ((toolCallId: String, approved: Boolean, reason: String) -> Unit)? = null,
    onToolAnswer: ((toolCallId: String, answer: String) -> Unit)? = null,
    interaction: ChainBlockInteractionState? = null,
) {
```

(5b) `onClick` 打开详情时置 detailOpen，Sheet 关闭时清。
**注意**：安全调用 `?` 不能用于赋值左侧，统一用 `interaction?.let { it.detailOpen = ... }`。

把：

```kotlin
        onClick = if (hasClickable) { { showResult = true } } else null,
```

改为：

```kotlin
        onClick = if (hasClickable) {
            {
                showResult = true
                interaction?.let { it.detailOpen = true }
            }
        } else null,
```

把详情块：

```kotlin
    if (showResult) {
        ToolDetailSheet(
            title = renderer.title(context),
            onDismiss = { showResult = false },
            jsonBody = { ToolJsonBody(context) },
        ) {
            renderer.Preview(context = context, onDismissRequest = { showResult = false })
        }
    }
```

改为（Preview 的 onDismissRequest 与 Sheet onDismiss 两条退出路径都清 detailOpen）：

```kotlin
    if (showResult) {
        ToolDetailSheet(
            title = renderer.title(context),
            onDismiss = {
                showResult = false
                interaction?.let { it.detailOpen = false }
            },
            jsonBody = { ToolJsonBody(context) },
        ) {
            renderer.Preview(context = context, onDismissRequest = {
                showResult = false
                interaction?.let { it.detailOpen = false }
            })
        }
    }
```

(5c) `AskUserToolStep` 标题：AI 问完停下来（`isPending`）后变为"询问 N 个问题"。

把：

```kotlin
        label = {
            Text(
                text = if (questions.size <= 1) firstQuestion else stringResource(
                    R.string.chat_message_tool_ask_questions,
                    questions.size
                ),
```

改为：

```kotlin
        label = {
            Text(
                text = if (!isPending && questions.size <= 1) {
                    firstQuestion
                } else {
                    stringResource(R.string.chat_message_tool_ask_questions, questions.size)
                },
```

（`isPending` 已在 AskUserToolStep 顶部定义。）

- [ ] **Step 6: 自审 + 提交**

`git add app/src/main/java/me/rerere/rikkahub/ui/components/ui/ChainOfThought.kt app/src/main/java/me/rerere/rikkahub/ui/components/message/ChatMessageCot.kt app/src/main/java/me/rerere/rikkahub/ui/components/message/ChatMessage.kt app/src/main/java/me/rerere/rikkahub/ui/components/message/ChatMessageReasoning.kt app/src/main/java/me/rerere/rikkahub/ui/components/message/ChatMessageTools.kt`
`git commit -m "fix(ui): 自动折叠抑制(ask_user等待/详情打开/思考展开)+ask_user等待时标题变询问N个问题"`

---

### Task 3: 工具选项卡回归原样式 + ToolPresentation 死代码清理

**Files:**
- Modify: `app/src/main/java/me/rerere/rikkahub/ui/components/message/ChatMessageTools.kt`（移除摘要行）
- Delete: `app/src/main/java/me/rerere/rikkahub/ui/components/message/tools/ToolPresentation.kt`（trash.sh）
- Modify: `app/src/main/res/values*/strings.xml`（移除 `tool_summary_*` 死串）

**Interfaces:**
- Consumes: Task 2 已给 `ChatMessageToolStep` 加 `interaction` 参数（本任务只改 label，不动签名）。
- Produces: `ToolPresentation.kt`（含 `ToolKind`/`ToolPresentation`/`ToolPresentationResolver`/`toolSummary`）全量删除；`tool_summary_*` 19 个字符串全量删除。Task 4/5 不得再引用它们。

**背景**：`fe99057` 前的工具选项卡只显示 `renderer.title(context)`；`fe99057` 起加了第二行 `toolSummary` 摘要（"已完成/调用中"）。本任务回归原样式：选项卡只显示标题。摘要行删除后 `ToolPresentation.kt` 无任何引用 → 整文件删除；其内引用的 `tool_summary_*` 字符串全部变死串 → 删除。

- [ ] **Step 1: ChatMessageTools.kt — 移除摘要行**

把 `ChatMessageToolStep` 内的：

```kotlin
    val presentation = remember(tool) { ToolPresentationResolver.resolve(tool) }
    val images = tool.output.filterIsInstance<UIMessagePart.Image>()
```

改为（删 presentation 行，保留 images）：

```kotlin
    val images = tool.output.filterIsInstance<UIMessagePart.Image>()
```

把 label：

```kotlin
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
                    val live = tool.liveOutput
                    val summaryText = if (loading && live != null) {
                        live.lineSequence().lastOrNull()?.take(80)
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
```

改为（回归原样式：仅标题）：

```kotlin
        label = {
            Text(
                text = renderer.title(context),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.secondary,
                modifier = Modifier.shimmer(isLoading = loading),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        },
```

删除不再使用的 import（逐一 grep 确认零引用后删）：

```kotlin
import me.rerere.ai.ui.ToolState
import me.rerere.rikkahub.ui.components.message.tools.ToolPresentationResolver
import me.rerere.rikkahub.ui.components.message.tools.toolSummary
```

- [ ] **Step 2: 删除 ToolPresentation.kt**

先确认零引用：`grep -rn "ToolPresentation\|ToolKind\|toolSummary" app/src/main/java --include=*.kt`（应只剩本文件自身）。然后：

`bash ~/.claude/scripts/trash.sh "X:\projects\rikkahub\app\src\main\java\me\rerere\rikkahub\ui\components\message\tools\ToolPresentation.kt"`

- [ ] **Step 3: 移除 `tool_summary_*` 死串**

对以下 19 个 key，先 `grep -rn "R.string.<key>" app/src/main/java` 确认零引用（Step 2 删除后应全部为零），再从**存在该 key 的所有** `app/src/main/res/values*/strings.xml` 中删除该 `<string>` 行：

`tool_summary_calling`, `tool_summary_running`, `tool_summary_exit_failed`, `tool_summary_error`, `tool_summary_failed`, `tool_summary_stopped`, `tool_summary_empty`, `tool_summary_no_search_results`, `tool_summary_no_conv_results`, `tool_summary_no_conversations`, `tool_summary_empty_file`, `tool_summary_no_screen_time`, `tool_summary_no_events`, `tool_summary_done`, `tool_summary_search_done`, `tool_summary_conv_done`, `tool_summary_conv_count`, `tool_summary_exit_ok`, `tool_summary_file_done`

**注意**：不要删除 `chat_message_tool_call_generic`（`DefaultToolUIRenderer.title` 仍用）、`chat_message_tool_call_label`/`chat_message_tool_call_result`（`DefaultToolPreview`/`ToolJsonBody` 仍用）。

- [ ] **Step 4: 自审 + 提交**

`git add app/src/main/java/me/rerere/rikkahub/ui/components/message/ChatMessageTools.kt app/src/main/res/`
`git commit -m "feat(ui): 工具选项卡回归原样式(仅标题)并删除 ToolPresentation/tool_summary 死代码"`

---

### Task 4: 小转换器修复 + MCP 工具详情规范 + Sheet 滚动重置

**Files:**
- Modify: `app/src/main/java/me/rerere/rikkahub/ui/components/message/tools/ToolDetailCommon.kt`（`ToolJsonSection` + `ToolJsonRawText`）
- Modify: `app/src/main/java/me/rerere/rikkahub/ui/components/message/tools/ToolUI.kt`（`DefaultToolPreview` + `isBuiltIn`）
- Modify: `app/src/main/java/me/rerere/rikkahub/ui/components/message/ChatMessageTools.kt`（`jsonBody` 按 `isBuiltIn` 门控）
- Modify: `app/src/main/java/me/rerere/rikkahub/ui/components/message/tools/ToolDetailSheet.kt`（`showJson` 切换重置 scrollState，收尾⑧）

**Interfaces:**
- Consumes: Task 2 的 `ToolDetailSheet` 退出路径（onDismiss/onDismissRequest 清 detailOpen）。本任务只改 `jsonBody` 参数。
- Produces: `ToolJsonSection(label, json, semanticContent, showToggle = json != null)`；`ToolJsonRawText(json)`；`ToolUIRenderer.isBuiltIn`（默认 true，`DefaultToolUIRenderer` false）。Task 5 不得假设旧 `ToolJsonSection` 语义（旧：toggle 显示 JsonTreeView；新：toggle 显示原始 JSON 文本框）。

**item 6/7 语义**：
- 小转换器（分区级 CodeSquare）当前在默认工具（语义视图=JsonTreeView）上切换 JsonTreeView↔JsonTreeView = 视觉 no-op（收尾⑩ 记录的 bug）。修复为：切换 渲染视图(semanticContent) ↔ 原始 json/text 文本框（`JsonInstantPretty.encodeToString` 等宽可选框，对齐最早 rikkahub 的 json/text 直出）。
- MCP/未知工具（`DefaultToolUIRenderer`）：详情页不提供页面级大开关（`jsonBody=null`）；默认参数/结果为 JsonTreeView（渲染性 json 展示）；仅提供分区级小开关（json 树 ↔ json 文本框）。调用结果非 JSON → 直接文本框，不提供小开关（`showToggle=false`）。

- [ ] **Step 1: ToolDetailCommon.kt — ToolJsonSection 改为原始文本切换 + 新增 ToolJsonRawText**

新增 import：`me.rerere.rikkahub.utils.JsonInstantPretty`。

在文件末尾新增：

```kotlin
/** 原始 JSON 文本框（对齐最早 rikkahub 的 json/text 直出形式：等宽、可选中） */
@Composable
internal fun ToolJsonRawText(json: JsonElement) {
    ToolTerminalOutput(JsonInstantPretty.encodeToString(json))
}
```

把 `ToolJsonSection`（L122-161）整体替换为：

```kotlin
/**
 * 工具详情分区：label 右侧一个小开关（CodeSquare），切换 渲染视图(semanticContent) / 原始 json/text 文本框。
 * [json] 非 null 时开关才把内容切到原始 JSON 文本框；为 null（如结果非 JSON）时默认不提供开关，直接渲染语义内容。
 */
@Composable
internal fun ToolJsonSection(
    label: String,
    json: JsonElement?,
    semanticContent: @Composable () -> Unit,
    showToggle: Boolean = json != null,
) {
    var showRaw by remember { mutableStateOf(false) }
    Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            if (showToggle) {
                IconButton(onClick = { showRaw = !showRaw }, modifier = Modifier.size(28.dp)) {
                    Icon(
                        imageVector = HugeIcons.CodeSquare,
                        contentDescription = stringResource(
                            if (showRaw) R.string.tool_ui_view_style else R.string.tool_ui_view_json,
                        ),
                        modifier = Modifier.size(16.dp),
                        tint = if (showRaw) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                    )
                }
            }
        }
        if (showRaw && json != null) {
            ToolJsonRawText(json)
        } else {
            semanticContent()
        }
    }
}
```

- [ ] **Step 2: ToolUI.kt — 接口加 `isBuiltIn` + DefaultToolPreview 重构**

(2a) 接口加属性（`Preview` 默认实现之后）：

```kotlin
    /** 是否为内置工具渲染器（内置渲染器提供页面级 JSON 大开关；MCP/未知工具不提供） */
    val isBuiltIn: Boolean get() = true
```

`DefaultToolUIRenderer`：

```kotlin
private object DefaultToolUIRenderer : ToolUIRenderer {
    override val toolName: String get() = ""
    override val isBuiltIn: Boolean get() = false
}
```

(2b) 把 `DefaultToolPreview`（L106-156）整体替换为：

```kotlin
/**
 * 默认工具详情（content-only）：参数/结果的 JSON 树渲染展示。
 * 参数与 JSON 结果提供分区级小开关（渲染视图 ↔ 原始 JSON 文本框）；非 JSON 结果直接文本框、不提供开关。
 */
@Composable
fun DefaultToolPreview(
    context: ToolUIContext,
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        ToolJsonSection(
            label = stringResource(R.string.chat_message_tool_call_label, context.tool.toolName),
            json = context.arguments,
        ) {
            JsonTreeView(context.arguments)
        }
        if (context.tool.output.isNotEmpty()) {
            val textParts = context.tool.output.filterIsInstance<UIMessagePart.Text>()
            val imageParts = context.tool.output.filterIsInstance<UIMessagePart.Image>()
            val joinedText = textParts.joinToString("\n") { it.text }
            val resultJson = runCatching { JsonInstant.parseToJsonElement(joinedText) }.getOrNull()
            ToolJsonSection(
                label = stringResource(R.string.chat_message_tool_call_result),
                json = resultJson, // 非 JSON → json=null → 不提供小开关，直接渲染文本框
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (resultJson != null) {
                        JsonTreeView(resultJson)
                    } else if (joinedText.isNotBlank()) {
                        HighlightCodeBlock(
                            code = joinedText,
                            language = "plaintext",
                            style = TextStyle(fontSize = 10.sp, lineHeight = 12.sp),
                        )
                    }
                    imageParts.forEach { part ->
                        ZoomableAsyncImage(
                            model = part.url,
                            contentDescription = null,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
            }
        }
    }
}
```

移除不再使用的 import（确认零引用后）：`androidx.compose.ui.util.fastForEach`。

- [ ] **Step 3: ChatMessageTools.kt — jsonBody 按 isBuiltIn 门控**

把 Task 2 后的详情块（`jsonBody` 行）：

```kotlin
            jsonBody = { ToolJsonBody(context) },
```

改为（if 为表达式，两分支分别为 lambda 与 null；MCP/未知工具 → `null` 不显示页面级大开关）：

```kotlin
            jsonBody = if (renderer.isBuiltIn) {
                { ToolJsonBody(context) }
            } else {
                null
            },
```

- [ ] **Step 4: ToolDetailSheet.kt — showJson 切换重置 scrollState（收尾⑧）**

在 `LaunchedEffect(Unit) { animateTo(PARTIAL); snapJob?.join(); rawFraction = PARTIAL }` 之后插入：

```kotlin
    // 页面 JSON 开关切换时回到顶部，避免深滚夹在中间
    LaunchedEffect(showJson) {
        scrollState.scrollTo(0)
    }
```

- [ ] **Step 5: 自审 + 提交**

`git add app/src/main/java/me/rerere/rikkahub/ui/components/message/tools/ToolDetailCommon.kt app/src/main/java/me/rerere/rikkahub/ui/components/message/tools/ToolUI.kt app/src/main/java/me/rerere/rikkahub/ui/components/message/ChatMessageTools.kt app/src/main/java/me/rerere/rikkahub/ui/components/message/tools/ToolDetailSheet.kt`
`git commit -m "fix(ui): 小转换器切原始json/text文本框+MCP工具无大开关且非JSON结果无小开关+JSON切换重置滚动"`

---

### Task 5: 收尾文案/本地化/代码整洁

**Files:**
- Modify: `app/src/main/java/me/rerere/rikkahub/ui/components/message/ChatMessage.kt`（收尾① Locale.ROOT）
- Modify: `app/src/main/java/me/rerere/rikkahub/ui/components/message/ChatMessageReasoning.kt`（收尾① Locale.ROOT）
- Modify: `app/src/main/java/me/rerere/rikkahub/ui/components/message/tools/BuiltinToolUIs.kt`（收尾③⑨）
- Modify: `app/src/main/java/me/rerere/rikkahub/ui/components/ui/Favicon.kt`（收尾⑦ FaviconRow）
- Modify: `app/src/main/java/me/rerere/rikkahub/ui/components/message/tools/JsonTreeView.kt` + `app/src/main/java/me/rerere/rikkahub/ui/components/ui/JsonTree.kt`（收尾⑤ KDoc 移后）
- Modify: `app/src/main/res/values/strings.xml` + `app/src/main/res/values-zh/strings.xml`（收尾③⑨ 新串 + 收尾⑦ 死串）

**Interfaces:**
- Consumes: Task 3 已删 ToolPresentation/tool_summary。Task 2 已补 ChainOfThought `@param autoExpand/suppressAutoCollapse`（收尾⑥ 已含）。
- Produces: 新串 `tool_ui_read_conv_empty`、`tool_ui_read_conv_more`（en+zh）；`read_conversation` 空消息用新串；`has_more` 提示；`FaviconRow` 移除；4 死串移除。

**收尾②④⑩ 已由 Task 3/4 取代，本任务不做。**

- [ ] **Step 1: 收尾① — Locale.ROOT 格式化（chat_message_tool_aggregate 与 deep_thinking_seconds）**

ChatMessage.kt 完成态聚合摘要（L379-384），把：

```kotlin
                                    val thinkingSummary = if (hasReasoning) {
                                        stringResource(
                                            R.string.chain_of_thought_aggregate,
                                            thoughtMs / 1000.0,
                                            toolText,
                                        )
                                    } else {
                                        stringResource(R.string.called_n_tools, toolText)
                                    }
```

改为：

```kotlin
                                    val thinkingSummary = if (hasReasoning) {
                                        String.format(
                                            Locale.ROOT,
                                            stringResource(R.string.chain_of_thought_aggregate),
                                            thoughtMs / 1000.0,
                                            toolText,
                                        )
                                    } else {
                                        stringResource(R.string.called_n_tools, toolText)
                                    }
```

`java.util.Locale` 已在 ChatMessage.kt 导入（L101）。

ChatMessageReasoning.kt 思考时长（L222-231），把：

```kotlin
                Text(
                    text = stringResource(
                        R.string.deep_thinking_seconds,
                        state.duration.toDouble(DurationUnit.SECONDS).toFloat()
                    ),
```

改为：

```kotlin
                Text(
                    text = String.format(
                        Locale.ROOT,
                        stringResource(R.string.deep_thinking_seconds),
                        state.duration.toDouble(DurationUnit.SECONDS).toFloat()
                    ),
```

新增 import：`java.util.Locale`。

- [ ] **Step 2: 收尾③⑨ — read_conversation 空消息串 + has_more 提示**

strings.xml（values + values-zh）各新增两行（对齐既有 `tool_ui_read_conv_*` 相邻位置）：

```xml
<string name="tool_ui_read_conv_empty">No messages</string>
<string name="tool_ui_read_conv_more">More messages not shown</string>
```

values-zh：

```xml
<string name="tool_ui_read_conv_empty">没有消息</string>
<string name="tool_ui_read_conv_more">更多消息未显示</string>
```

BuiltinToolUIs.kt `ReadConversationToolUI.Preview`（L898-904），把空消息分支：

```kotlin
            if (messages.isEmpty()) {
                Text(
                    text = stringResource(R.string.tool_ui_conv_search_empty),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                messages.forEach { m ->
```

改为：

```kotlin
            val hasMore = content.getStringContent("has_more") == "true"
            if (messages.isEmpty()) {
                Text(
                    text = stringResource(R.string.tool_ui_read_conv_empty),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                messages.forEach { m ->
```

并在 `messages.forEach { ... }` 块结束（`}` 对齐 forEach 处）之后、`ToolDetailContainer {` 闭合前，插入 has_more 提示：

```kotlin
                if (hasMore) {
                    Text(
                        text = stringResource(R.string.tool_ui_read_conv_more),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
```

（插入位置：`messages.forEach` 的大括号之后、`ToolDetailContainer` 的闭合大括号之前。）

- [ ] **Step 3: 收尾⑦ — 移除 FaviconRow + 4 死串**

Favicon.kt：删除 `FaviconRow` 函数（L49-95），并移除仅它使用的 imports（确认 `Favicon` 本身仍用后）：
`androidx.compose.ui.draw.shadow`、`androidx.compose.ui.layout.Layout`、`androidx.compose.ui.unit.Dp`、`androidx.compose.ui.zIndex`、`androidx.compose.foundation.shape.CircleShape`。
（保留 `androidx.compose.foundation.layout.size`——Favicon 用 `Modifier.size(20.dp)`；保留 `RoundedCornerShape`——Favicon 默认 shape。）

4 死串（先 grep 确认零引用，再删存在 key 的 locale 文件）：
- `chat_message_tool_call_title`（6 locale 均有）
- `tool_ui_glob_count`、`tool_ui_grep_count`（仅 values + values-zh 有）
- `search_results_count`（当前文件已不存在，grep 确认即可跳过）

- [ ] **Step 4: 收尾⑤ — 两 JSON 树文件头 KDoc 移到 import 后**

JsonTreeView.kt：把第 1 行 `/** 详情页的平铺 JSON 树。日志页可展开 JSON 树见 ui/components/ui/JsonTree.kt（交互模式不同，两组件并存不合并）。 */` 移到 import 块之后、`JSON_TREE_MAX_DEPTH` 常量之前。

JsonTree.kt：把第 1 行 `/** 日志页可展开 JSON 树。详情页的平铺 JSON 树见 ui/components/message/tools/JsonTreeView.kt（交互模式不同，两组件并存不合并）。 */` 移到 import 块之后。

- [ ] **Step 5: 自审 + 提交**

`git add app/src/main/java/me/rerere/rikkahub/ui/components/message/ChatMessage.kt app/src/main/java/me/rerere/rikkahub/ui/components/message/ChatMessageReasoning.kt app/src/main/java/me/rerere/rikkahub/ui/components/message/tools/BuiltinToolUIs.kt app/src/main/java/me/rerere/rikkahub/ui/components/ui/Favicon.kt app/src/main/java/me/rerere/rikkahub/ui/components/message/tools/JsonTreeView.kt app/src/main/java/me/rerere/rikkahub/ui/components/ui/JsonTree.kt app/src/main/res/`
`git commit -m "polish(ui): 收尾①Locale.ROOT③⑨read_conversation文案⑦死代码移除⑤KDoc归位"`

---

## 收尾验证（全部任务完成后）

1. **全分支 grep 断言**：`ToolPresentation`/`toolSummary`/`R.string.tool_summary_` /`FaviconRow`/`search_results_count`/`tool_ui_glob_count`/`tool_ui_grep_count`/`chat_message_tool_call_title` 在 java 中零引用；`chat_message_tool_ask_questions`、`chat_message_tool_call_label`、`chat_message_tool_call_result`、`chat_message_tool_call_generic`、`chain_of_thought_calling`、`called_n_tools` 仍被引用。
2. **签名一致性**：`ChainOfThought` 调用点（ChatMessage.kt）传 `suppressAutoCollapse`；`ChatMessageReasoningStep`/`ChatMessageToolStep` 调用点传 `interaction`；`ToolJsonSection` 所有调用点符合新签名（含 `showToggle` 默认）。
3. **最终 CI**：`git push origin master` → `gh workflow run nightly-build-debug.yml --repo DevLintMar/rikkahub --ref master`。flake（JitPack/JBR21）重跑即绿。
