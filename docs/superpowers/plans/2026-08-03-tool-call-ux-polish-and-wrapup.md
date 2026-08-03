# 工具调用 UX 质量修复 + 收尾 A-D 实现计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 完成 13 项工具调用质量修复（聚合头高度/秒数/颜色、执行中自动展开、详情半屏→近全屏、JSON 开关、长文本、记忆删除确认、ask_user 修复、read_conversation/sub_agent 渲染器），随后完成收尾 A-D（死代码、文案、渲染微调、性能）。

**Architecture:** 展示层三项改动并行：(1) 聚合折叠头区域（ChainOfThought/ChatMessage/ChatMessageReasoning）统一高度、秒数一位小数、颜色一致化、执行中自动展开；(2) 详情视图整体重构——移植 Agora `SegmentDetailSheet` 的自定义半屏 Dialog（Half=0.5 / Full=0.94）替代 Material3 ModalBottomSheet，全部 Preview 改为 content-only（由 Sheet 统一滚动），并加共享标题栏 + 页面级/分区级 JSON 开关；(3) 工具渲染器补 read_conversation/sub_agent，修复 ask_user toolState 卡死。收尾 A-D 在 Phase 2 顺序执行（先做详情重构避免死代码清理冲突）。

**Tech Stack:** Kotlin/Jetpack Compose、Material3、kotlinx.serialization、HugeIcons（me.rerere.hugeicons）、Dialog+Window、NestedScrollConnection、Android plurals。

## Global Constraints

- **展示层只消费信封，不改 producer**（信封形状冻结，Plan 1 定案）。本计划所有改动均为展示/状态层。
- **字符串双写**：英文进 `app/src/main/res/values/strings.xml`，中文进 `values-zh/strings.xml`（用户拍板）。ja/ko/ru/zh-rTW **不新增**，回退英文。*例外*：修改既有字符串的**格式参数**（`chain_of_thought_aggregate`）只需改 values + values-zh（ja/ko/ru/zh-rTW 无此 key，回退英文）。
- **本机无编译器**：不运行 gradle。编译验证全靠 CI（`git push origin master` 后 `gh workflow run nightly-build-debug.yml --repo DevLintMar/rikkahub --ref master`，push 在前）。CI `assembleDebug` 不编译测试源码。
- 图标名（HugeIcons）以 CI 编译为准。本计划用到的 `HugeIcons.MessageDelay01` / `HugeIcons.ChatBot` / `HugeIcons.CodeSquare` 已通过 GitHub 树确认存在；若 CI 报错，换已知可用图标。
- 文件删除走 `~/.claude/scripts/trash.sh`（回收站），不用 rm。
- 新字符串一律 `stringResource`/`pluralStringResource` 引用，不硬编码。
- 列表行（聚合折叠行）保留截断；**详情视图**内不截断（item 9）。
- 信封键（已验证）：read_conversation `{type, conversation_id, title, total_messages, offset, limit, has_more, messages:[{role,text,timestamp}]}`；sub_agent 后台 `{type, status:"started", task_id, description, mode:"background"}`、同步 `{type, status:"completed"|"failed", description, mode:"synchronous", result?|error?}`。

---

## Phase 1 — 质量修复（items 1–13）

### Task 1: 聚合头与推理行高度统一 + 秒数一位小数 + 纯工具块文案 + 工具数单复数

**Files:**
- Modify: `app/src/main/java/me/rerere/rikkahub/ui/components/ui/ChainOfThought.kt`（聚合头 Row padding、步骤行 padding）
- Modify: `app/src/main/java/me/rerere/rikkahub/ui/components/message/ChatMessage.kt`（ChainOfThoughtHeaderRow 图标加 20dp 盒、聚合文案/秒数）
- Modify: `app/src/main/java/me/rerere/rikkahub/ui/components/message/ChatMessageCot.kt`（新增 `hasReasoning` 判断——不需要，用 `block.steps.any`）
- Modify: `app/src/main/res/values/strings.xml`、`app/src/main/res/values-zh/strings.xml`
- Create: `app/src/main/res/values/plurals.xml`、`app/src/main/res/values-zh/plurals.xml`

**Interfaces:**
- Produces: `ChainOfThought` 新增参数 `autoExpand: Boolean = false`（Task 3 使用）。
- Produces: `List<ThinkingStep>.thinkingAggregate()` 不变（仍返回 `Pair<Long, Int>`）。
- Produces: 字符串 key：`plurals.tool_called_count`、`called_n_tools`、修改后的 `chain_of_thought_aggregate`。

**背景（高度计算，已验证）：**
- 推理步行（`ChainOfThoughtStepContent` 的 label Row）：`padding(vertical=8.dp)` + 20dp 图标盒 + titleSmall(20dp 行高) → 36dp。
- 聚合头行（`ChainOfThought` 聚合分支）：`padding(vertical=3.dp)` + 16dp 图标 + titleSmall(20dp) → 26dp。
- 平均值 = **31dp**。令两行 padding=5.5.dp 且图标盒=20dp，高度 = 2×5.5 + max(20, textH) = 31 + 0，恒等于平均值（textH≥16 时）。推理行图标盒已是 20dp；聚合头图标需包 20dp 盒。

- [ ] **Step 1: ChainOfThought.kt — 统一两行 padding**

`ChainOfThoughtStepContent` 的 label Row（现 `.padding(vertical = 8.dp)`，约 L354）改为：

```kotlin
                    .padding(vertical = 5.5.dp),
```

聚合折叠头 Row（现 `.padding(vertical = 3.dp)`，约 L116）改为：

```kotlin
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(MaterialTheme.shapes.small)
                            .clickable { expanded = !expanded }
                            .padding(vertical = 5.5.dp),
```

- [ ] **Step 2: ChatMessage.kt — ChainOfThoughtHeaderRow 图标包 20dp 盒**

`ChainOfThoughtHeaderRow`（L268-281）的 Icon 包一层与步骤行一致的 20dp 盒（视觉左对齐 + 高度统一）：

```kotlin
@Composable
private fun ChainOfThoughtHeaderRow(content: @Composable () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Box(
            modifier = Modifier.size(20.dp),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = HugeIcons.AiBrain02,
                contentDescription = null,
                modifier = Modifier.size(16.dp),
                tint = LocalContentColor.current.copy(alpha = 0.7f),   // item 4 一并处理（见 Task 2）
            )
        }
        content()
    }
}
```

需新增 import `androidx.compose.material3.LocalContentColor`（若未导入）。

- [ ] **Step 3: ChatMessage.kt — 聚合文案：秒数一位小数 + 纯工具块只显 "Called N tools" + 单复数**

`MessagePartsBlock` 聚合头完成态分支（现 L368-381）改为：

```kotlin
                                } else {
                                    val hasReasoning = block.steps.any { it is ThinkingStep.ReasoningStep }
                                    val toolText = pluralStringResource(
                                        R.plurals.tool_called_count, toolCount, toolCount,
                                    )
                                    val thinkingSummary = if (hasReasoning) {
                                        stringResource(
                                            R.string.chain_of_thought_aggregate,
                                            thoughtMs / 1000.0,
                                            toolText,
                                        )
                                    } else {
                                        stringResource(R.string.called_n_tools, toolText)
                                    }
                                    ChainOfThoughtHeaderRow {
                                        Text(
                                            text = thinkingSummary,
                                            style = MaterialTheme.typography.titleSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f), // item 3（Task 2 一并）
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                    }
                                }
```

需新增 import `androidx.compose.ui.res.pluralStringResource`。

- [ ] **Step 4: 字符串资源**

`values/strings.xml`（新增/修改，按字母序就近插入）：

```xml
  <!-- 修改：%1$d→%1$.1f，%2$d tools→%2$s 传已复数化短语 -->
  <string name="chain_of_thought_aggregate">Thought for %1$.1f s · called %2$s</string>
  <string name="called_n_tools">Called %1$s</string>
```

`values/plurals.xml`（新建）：

```xml
<?xml version="1.0" encoding="utf-8"?>
<resources>
    <plurals name="tool_called_count">
        <item quantity="one">%d tool</item>
        <item quantity="other">%d tools</item>
    </plurals>
</resources>
```

`values-zh/strings.xml`：

```xml
  <string name="chain_of_thought_aggregate">思考了 %1$.1f 秒 · 调用了 %2$s</string>
  <string name="called_n_tools">调用了 %1$s</string>
```

`values-zh/plurals.xml`（新建，中文无复数变化）：

```xml
<?xml version="1.0" encoding="utf-8"?>
<resources>
    <plurals name="tool_called_count">
        <item quantity="one">%d 个工具</item>
        <item quantity="other">%d 个工具</item>
    </plurals>
</resources>
```

- [ ] **Step 5: CI 验证 + 提交**

```bash
git push origin master
gh workflow run nightly-build-debug.yml --repo DevLintMar/rikkahub --ref master
git add -A
git commit -m "fix(ui): 聚合头/推理行高度统一(平均31dp)+秒数一位小数+纯工具块Called N tools+工具数单复数"
```
**Expected**: CI `assembleDebug` 绿。

### Task 2: 思考文本/图标/箭头颜色一致化（items 3, 4）

**Files:**
- Modify: `app/src/main/java/me/rerere/rikkahub/ui/components/message/ChatMessageReasoning.kt`（思考秒数文本色、思考图标色）
- Modify: `app/src/main/java/me/rerere/rikkahub/ui/components/message/ChatMessage.kt`（聚合头图标/文本色 —— Task 1 Step 2/3 已含，此处核对）
- Modify: `app/src/main/java/me/rerere/rikkahub/ui/components/ui/ChainOfThought.kt`（聚合头箭头色）

**现状（已核对）：** 工具列表行图标 = `LocalContentColor.current.copy(alpha=0.7f)`（ChatMessageTools.kt L111）；工具摘要文本 = `onSurfaceVariant.copy(alpha=0.6f)`（L136）；子步骤箭头 = `onSurfaceVariant`（ChainOfThought L411/418）。不一致点：思考图标=`secondary`、大脑图标=`onSurface`、聚合头箭头=`onSurfaceVariant.copy(0.5f)`、思考文本=`secondary`/`onSurface`。

- [ ] **Step 1: 推理步图标 + 思考秒数文本色（ChatMessageReasoning.kt）**

`ChatMessageReasoningStep` icon 的 `tint = MaterialTheme.colorScheme.secondary`（L215）→ `tint = LocalContentColor.current.copy(alpha = 0.7f)`。
`deep_thinking_seconds` 文本（L227）`color = MaterialTheme.colorScheme.secondary` → `color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)`。
（`ReasoningTitle` 流式思考标题保持 `secondary` 不动——非"思考了x秒"文本。）

- [ ] **Step 2: 聚合头大脑图标 + 完成态文本（ChatMessage.kt，Task 1 已改，核对）**

- `ChainOfThoughtHeaderRow` Icon tint = `LocalContentColor.current.copy(alpha = 0.7f)`（Task 1 Step 2 已含）。
- 完成态聚合文本 color = `onSurfaceVariant.copy(alpha = 0.6f)`（Task 1 Step 3 已含）。
- "Calling tools…" 执行中态文本保持 `onSurface`（瞬态状态，不并入）。

- [ ] **Step 3: 聚合头右箭头色（ChainOfThought.kt）**

聚合折叠头箭头（L125）`tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)` → `tint = MaterialTheme.colorScheme.onSurfaceVariant`（与子步骤箭头一致）。

- [ ] **Step 4: CI + 提交**

```bash
git push origin master
gh workflow run nightly-build-debug.yml --repo DevLintMar/rikkahub --ref master
git add -A
git commit -m "fix(ui): 思考图标/文本/大脑图标/箭头颜色与工具列表行一致化"
```
**Expected**: CI 绿。

### Task 3: 执行中聚合块自动展开（item 5）

**Files:**
- Modify: `app/src/main/java/me/rerere/rikkahub/ui/components/ui/ChainOfThought.kt`（新增 `autoExpand` 参数）
- Modify: `app/src/main/java/me/rerere/rikkahub/ui/components/message/ChatMessage.kt`（传 `autoExpand = loading && !isReasoningOnlyBlock`）

- [ ] **Step 1: ChainOfThought 加 `autoExpand` 参数**

签名（L72-81）加 `autoExpand: Boolean = false`。内部状态改为"跟随自动展开 + 手动可覆盖"：

```kotlin
    var expanded by remember { mutableStateOf(autoExpand) }
    var lastAutoExpand by remember { mutableStateOf(autoExpand) }
    if (autoExpand != lastAutoExpand) {
        lastAutoExpand = autoExpand
        expanded = autoExpand   // 执行开始(true)强制展开；执行完(false)自动收起
    }
```

（`expanded` 声明位于 `aggregateMode` 判断之前；`autoExpand` 变化时跟随，稳定时手动点击可覆盖。）

- [ ] **Step 2: ChatMessage.kt 传 autoExpand**

`ChainOfThought(...)` 调用处（L385-392）加：

```kotlin
                    ChainOfThought(
                        modifier = Modifier.animateContentSize(),
                        steps = block.steps,
                        collapsedAdaptiveWidth = isReasoningOnlyBlock,
                        autoExpand = loading && !isReasoningOnlyBlock,
                        cardColors = ...,
                        header = aggregateHeader,
                    ) { step -> ... }
```

效果：AI 执行思考+调用工具期间（最后一条消息 `loading=true`），含工具的聚合块展开显示实时步骤；生成完毕（`loading=false`）自动收起。旧消息的块不受影响。

- [ ] **Step 3: CI + 提交**

```bash
git push origin master
gh workflow run nightly-build-debug.yml --repo DevLintMar/rikkahub --ref master
git add -A
git commit -m "feat(ui): AI执行中聚合思考块自动展开，完成后自动收起"
```
**Expected**: CI 绿。

### Task 4: 详情 BottomSheet 半屏→近全屏 + 全量 Preview content-only 化（item 6）

**Files:**
- Create: `app/src/main/java/me/rerere/rikkahub/ui/components/message/tools/ToolDetailSheet.kt`
- Modify: `app/src/main/java/me/rerere/rikkahub/ui/components/message/ChatMessageTools.kt`（ModalBottomSheet → ToolDetailSheet）
- Modify: `app/src/main/java/me/rerere/rikkahub/ui/components/message/tools/ToolDetailCommon.kt`（`ToolDetailContainer` 去高度/滚动）
- Modify: `app/src/main/java/me/rerere/rikkahub/ui/components/message/tools/ToolUI.kt`（`DefaultToolPreview` 去高度/滚动/标题）
- Modify: `app/src/main/java/me/rerere/rikkahub/ui/components/message/tools/BuiltinToolUIs.kt`（Memory/TTS/SearchWeb/ScrapeWeb 等 Preview 去高度/滚动，LazyColumn→Column）
- Modify: `app/src/main/java/me/rerere/rikkahub/ui/components/message/tools/WorkspaceToolUIs.kt`（EditFile/FileContentPreview/Shell 去高度/滚动）

**设计：** 移植 Agora `references/Agora/.../SegmentDetailSheet.kt` 的自定义 Dialog 底部弹层（Collapsed=0 / Half=0.5 / Full=0.94 状态机，Animatable fraction + snap + 原生 dimAmount + NestedScrollConnection）。默认打开 Half（半屏），上拉进 Full（近全屏），下拉回 Half，再下拉关闭。标题栏 = 拖拽把手 + 标题 + 右侧 JSON 开关槽（Task 5 用）。内容区由 Sheet 统一 `verticalScroll` + `nestedScroll`，**全部 Preview 改为 content-only**（不再自带高度/滚动）。

**关键差异 vs Agora 源码**：去掉 Agora 专属 `DialogWindowEdgeToEdge`/`noOpBringIntoView`/`ChatType`/`MonoFamily`/`mergeAdjacentSegments`；用 MaterialTheme 排版 + `FontFamily.Monospace`；`content` 槽为 `@Composable ColumnScope.() -> Unit`（见下）。抓握/拖动/嵌套滚动/弹回 FSM 逻辑照搬。

- [ ] **Step 1: 先加 Sheet 需要的两个开关字符串（Task 5 用到的 `tool_ui_arguments` 在 Task 5 加）**

`values/strings.xml`：
```xml
  <string name="tool_ui_view_json">View JSON</string>
  <string name="tool_ui_view_style">View style</string>
```
`values-zh/strings.xml`：
```xml
  <string name="tool_ui_view_json">查看 JSON</string>
  <string name="tool_ui_view_style">查看样式</string>
```

- [ ] **Step 2: 创建 ToolDetailSheet.kt**

```kotlin
package me.rerere.rikkahub.ui.components.message.tools

// imports：animation.core.Animatable/spring, foundation.gestures.detectTapGestures/detectVerticalDragGestures,
// layout.*, rememberScrollState/verticalScroll, material3.*(含 IconButton), runtime.*, ui.*, geometry.Offset,
// input.nestedscroll.*, input.pointer.pointerInput, layout.layout, platform.LocalConfiguration/LocalDensity/LocalView,
// res.stringResource, unit.*, window.*, kotlin.math.*, kotlinx.coroutines.*,
// me.rerere.hugeicons.HugeIcons, me.rerere.hugeicons.stroke.CodeSquare, me.rerere.rikkahub.R

/**
 * 工具详情底部弹层（Agora SegmentDetailSheet 移植）：默认半屏(Half=0.5)，上拉近全屏(Full=0.94)。
 * 自包含拖拽状态机：Collapsed/Half/Full 驱动 Animatable fraction；内容区统一滚动 + 嵌套滚动
 * （Half 时拖动全屏拖动 Sheet，Full 时内容滚动，内容到顶下拉回 Half）。
 */
@Composable
internal fun ToolDetailSheet(
    title: String,
    onDismiss: () -> Unit,
    jsonBody: (@Composable () -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    // jsonBody 非 null → 标题栏右侧显示 CodeSquare 开关，翻转到整页 JSON 视图
    var showJson by remember { mutableStateOf(false) }
    val density = LocalDensity.current
    val configuration = LocalConfiguration.current
    val screenHeightPx = with(density) { configuration.screenHeightDp.dp.toPx() }
    val coroutineScope = rememberCoroutineScope()
    val scrollState = rememberScrollState()

    val PARTIAL = 0.5f
    val FULL = 0.94f

    val PHASE_COLLAPSED = 0; val PHASE_HALF = 1; val PHASE_FULL = 2
    var phase by remember { mutableIntStateOf(PHASE_HALF) }
    var rawFraction by remember { mutableFloatStateOf(0f) }
    val visualFraction = remember { Animatable(0f) }
    var snapJob by remember { mutableStateOf<Job?>(null) }
    var dismissing by remember { mutableStateOf(false) }

    val snapSpring = spring<Float>(dampingRatio = 0.9f, stiffness = 350f, visibilityThreshold = 0.001f)

    fun snapTarget(pos: Float, velSign: Float): Float {
        val goingUp = velSign >= 0f
        return when {
            pos > 0.5f && goingUp -> FULL
            pos > 0.5f && !goingUp -> PARTIAL
            pos <= 0.5f && goingUp -> PARTIAL
            else -> 0f
        }
    }

    fun animateTo(target: Float) {
        snapJob?.cancel()
        snapJob = coroutineScope.launch {
            visualFraction.animateTo(target, snapSpring)
            rawFraction = visualFraction.value
            phase = when (target) { FULL -> PHASE_FULL; PARTIAL -> PHASE_HALF; else -> PHASE_COLLAPSED }
            if (target == 0f) onDismiss()
        }
    }

    fun dismiss() { dismissing = true; animateTo(0f) }

    fun grabSheet() {
        if (dismissing) return
        if (snapJob?.isActive == true) { snapJob?.cancel(); rawFraction = visualFraction.value }
    }

    LaunchedEffect(Unit) { animateTo(PARTIAL); snapJob?.join(); rawFraction = PARTIAL }

    LaunchedEffect(rawFraction) {
        if (dismissing || snapJob?.isActive == true) return@LaunchedEffect
        val pos = rawFraction
        delay(80)
        if (dismissing || pos != rawFraction || snapJob?.isActive == true) return@LaunchedEffect
        val target = snapTarget(pos, 0f)
        if (abs(target - pos) > 0.01f) animateTo(target)
    }

    val dialogWindowRef = remember { mutableStateOf<android.view.Window?>(null) }
    LaunchedEffect(dialogWindowRef.value) {
        val window = dialogWindowRef.value ?: return@LaunchedEffect
        while (isActive) {
            window.attributes = window.attributes.also { it.dimAmount = (0.32f * visualFraction.value).coerceIn(0f, 1f) }
            withFrameNanos { }
        }
    }

    val sheetScrollConnection = remember {
        object : NestedScrollConnection {
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                if (!dismissing && phase != PHASE_FULL) {
                    grabSheet()
                    val delta = -available.y / screenHeightPx
                    rawFraction = (rawFraction + delta).coerceIn(0f, FULL)
                    coroutineScope.launch { visualFraction.snapTo(rawFraction) }
                    if (rawFraction >= FULL && available.y < 0f) phase = PHASE_FULL
                    return available.copy(x = 0f)
                }
                return Offset.Zero
            }

            override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
                if (dismissing) return Offset.Zero
                if (phase == PHASE_FULL && available.y > 0f && scrollState.value == 0
                    && source == NestedScrollSource.UserInput
                ) {
                    phase = PHASE_HALF
                    val delta = -available.y / screenHeightPx
                    rawFraction = (FULL + delta).coerceIn(0f, FULL)
                    coroutineScope.launch { visualFraction.snapTo(rawFraction) }
                    return available.copy(x = 0f)
                }
                return Offset.Zero
            }

            override suspend fun onPreFling(available: Velocity): Velocity {
                if (phase != PHASE_FULL && available.y != 0f) {
                    val velSign = if (available.y < 0f) 1f else -1f
                    animateTo(snapTarget(rawFraction, velSign))
                    return available
                }
                return Velocity.Zero
            }
        }
    }

    Dialog(
        onDismissRequest = { dismiss() },
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            dismissOnBackPress = true,
            dismissOnClickOutside = false,
            decorFitsSystemWindows = false,
        ),
    ) {
        val dialogWindow = (LocalView.current.parent as? DialogWindowProvider)?.window
        SideEffect { dialogWindowRef.value = dialogWindow }

        Box(modifier = Modifier.fillMaxSize()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .pointerInput(Unit) {
                        detectTapGestures(onTap = { if (visualFraction.value > 0.02f) dismiss() })
                    }
            )

            Box(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .layout { measurable, constraints ->
                        val h = (screenHeightPx * visualFraction.value).roundToInt().coerceAtLeast(0)
                        val placeable = measurable.measure(constraints.copy(minHeight = h, maxHeight = h))
                        layout(placeable.width, h) { placeable.placeRelative(0, 0) }
                    }
            ) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    shape = RoundedCornerShape(topStart = 26.dp, topEnd = 26.dp),
                    shadowElevation = 8.dp,
                    color = MaterialTheme.colorScheme.surfaceContainer,
                ) {
                    Column(modifier = Modifier.fillMaxSize()) {
                        // 拖拽把手 + 标题 + JSON 开关
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .pointerInput(Unit) {
                                    var velEma = 0f
                                    detectVerticalDragGestures(
                                        onDragStart = {
                                            if (dismissing) return@detectVerticalDragGestures
                                            velEma = 0f; grabSheet()
                                        },
                                        onVerticalDrag = { change, dragAmount ->
                                            if (dismissing) return@detectVerticalDragGestures
                                            change.consume()
                                            velEma = velEma * 0.5f + (-dragAmount).coerceIn(-1f, 1f) * 0.5f
                                            rawFraction = (rawFraction - dragAmount / screenHeightPx).coerceIn(0f, FULL)
                                            coroutineScope.launch { visualFraction.snapTo(rawFraction) }
                                            if (rawFraction >= FULL && dragAmount < 0f) phase = PHASE_FULL
                                        },
                                        onDragEnd = {
                                            if (dismissing) return@detectVerticalDragGestures
                                            animateTo(snapTarget(rawFraction, velEma))
                                        },
                                    )
                                }
                        ) {
                            Box(modifier = Modifier.fillMaxWidth().height(28.dp), contentAlignment = Alignment.Center) {
                                Box(
                                    modifier = Modifier.width(36.dp).height(5.dp)
                                        .clip(RoundedCornerShape(3.dp))
                                        .background(MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.3f))
                                )
                            }
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 24.dp, vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    text = title,
                                    style = MaterialTheme.typography.titleMedium,
                                    color = MaterialTheme.colorScheme.onSurface,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f),
                                )
                                if (jsonBody != null) {
                                    IconButton(onClick = { showJson = !showJson }) {
                                        Icon(
                                            imageVector = HugeIcons.CodeSquare,
                                            contentDescription = stringResource(
                                                if (showJson) R.string.tool_ui_view_style
                                                else R.string.tool_ui_view_json,
                                            ),
                                            modifier = Modifier.size(18.dp),
                                            tint = if (showJson) {
                                                MaterialTheme.colorScheme.primary
                                            } else {
                                                MaterialTheme.colorScheme.onSurfaceVariant
                                            },
                                        )
                                    }
                                }
                            }
                            HorizontalDivider(
                                modifier = Modifier.padding(horizontal = 24.dp),
                                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f),
                            )
                        }

                        // 内容区：统一滚动 + 嵌套滚动；showJson 时切到整页 JSON 视图
                        Column(
                            modifier = Modifier
                                .fillMaxSize()
                                .nestedScroll(sheetScrollConnection)
                                .verticalScroll(scrollState)
                                .padding(horizontal = 24.dp)
                                .padding(top = 6.dp)
                                .navigationBarsPadding()
                                .padding(bottom = 32.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            if (showJson && jsonBody != null) jsonBody()
                            else content()
                        }
                    }
                }
            }
        }
    }
}
```

- [ ] **Step 3: ToolDetailCommon.kt — ToolDetailContainer 去高度/滚动**

```kotlin
/** 详情内容容器（content-only：滚动由 ToolDetailSheet 统一提供） */
@Composable
internal fun ToolDetailContainer(content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) { content() }
}
```
（删除 `fillMaxHeight`/`rememberScrollState`/`verticalScroll` import 中不再使用的。）

- [ ] **Step 4: ToolUI.kt — DefaultToolPreview 去高度/滚动/自带标题**

```kotlin
/** 默认工具详情（content-only）：参数 + 结果 JSON 树。标题由 ToolDetailSheet 提供 */
@Composable
fun DefaultToolPreview(
    context: ToolUIContext,
    headerActions: (@Composable () -> Unit)? = null,
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = stringResource(R.string.chat_message_tool_call_title),
                style = MaterialTheme.typography.headlineSmall,
                textAlign = TextAlign.Center,
            )
            headerActions?.invoke()
        }
        // 以下 FormItem 内容不变（参数/结果 JsonTreeView 或 HighlightCodeBlock）
        ...
    }
}
```
仅改外层 `Column` 修饰符（`fillMaxHeight(0.8f).padding(16.dp).verticalScroll(...)` → `fillMaxWidth()`）；内部保持不变。

- [ ] **Step 5: ChatMessageTools.kt — ModalBottomSheet → ToolDetailSheet**

现 L197-211 的 `if (showResult) { ModalBottomSheet(...) { renderer.Preview(...) } }` 改为：

```kotlin
    if (showResult) {
        ToolDetailSheet(
            title = renderer.title(context),
            onDismiss = { showResult = false },
        ) {
            renderer.Preview(context = context, onDismissRequest = { showResult = false })
        }
    }
```

删除不再使用的 `ModalBottomSheet`/`rememberBottomSheetState`/`SheetValue` import。

- [ ] **Step 6: 其余 Preview content-only 化（逐个精确编辑）**

对每个 `fillMaxHeight(0.8f)` + 自带滚动的 Preview，去掉外层高度/滚动（content-only，由 Sheet 滚动）。逐项：

| 文件 | Preview | 改动 |
|---|---|---|
| BuiltinToolUIs.kt | `MemoryToolUI.Preview` | 外层 `Column(fillMaxHeight(0.8f))`（L139）→ `Column(Modifier.fillMaxWidth())`；内部 `ToolDetailContainer` 已 content-only |
| BuiltinToolUIs.kt | `TextToSpeechToolUI.Preview` | `LazyColumn(fillMaxHeight(0.8f).padding(16.dp), spacedBy(8.dp))` → `Column(Modifier.fillMaxWidth(), spacedBy(8.dp))`；`item { }` 包装解除；删除 LazyColumn/LazyRow `items` 相关 import（如无其他使用） |
| BuiltinToolUIs.kt | `SearchWebPreview` | 同上：LazyColumn → Column；`item{}`/`items(list)` 解除为直接子级/`forEach`；保留 LazyRow 图片行（横向 lazy 在纵向滚动 Column 内 OK） |
| BuiltinToolUIs.kt | `ScrapeWebPreview` | 同上：LazyColumn → Column |
| WorkspaceToolUIs.kt | `EditFileToolUI.Preview` | 外层 `Column(fillMaxHeight(0.8f).padding(16.dp).verticalScroll(...))`（L127-133）→ `Column(Modifier.fillMaxWidth(), spacedBy(8.dp))` |
| WorkspaceToolUIs.kt | `FileContentPreview` | 外层（L275-281）→ `Column(Modifier.fillMaxWidth(), spacedBy(8.dp))` |
| WorkspaceToolUIs.kt | `ShellToolUI.Preview` | 外层（L362-368）→ `Column(Modifier.fillMaxWidth(), spacedBy(8.dp))` |

（其余 Preview 已用 `ToolDetailContainer`，Step 3 后自动 content-only。）

- [ ] **Step 7: CI + 提交**

```bash
git push origin master
gh workflow run nightly-build-debug.yml --repo DevLintMar/rikkahub --ref master
git add -A
git commit -m "feat(ui): 工具详情 BottomSheet 移植 Agora 半屏→近全屏，全量 Preview content-only"
```
**Expected**: CI 绿。

### Task 5: 工具标题 + 页面级/分区级 JSON 开关 + 精良化（items 7, 8）

**Files:**
- Modify: `app/src/main/java/me/rerere/rikkahub/ui/components/message/tools/ToolDetailSheet.kt`（标题栏右侧 JSON 开关槽接线）
- Create/Modify: `app/src/main/java/me/rerere/rikkahub/ui/components/message/tools/ToolDetailCommon.kt`（新增 `ToolJsonBody`、`ToolJsonSection` 共享组件）
- Modify: `app/src/main/java/me/rerere/rikkahub/ui/components/message/tools/ToolUI.kt`（DefaultToolPreview 分区开关）
- Modify: `app/src/main/java/me/rerere/rikkahub/ui/components/message/ChatMessageTools.kt`（传 `jsonBody`）
- Modify: `app/src/main/res/values/strings.xml`、`values-zh/strings.xml`（开关文案）

**设计：** item 8（工具标题 + 切 JSON 开关）→ Sheet 标题栏右侧加一个 `HugeIcons.CodeSquare` IconButton，切换整页为 JSON 视图（`ToolJsonBody`）。item 7（参数/结果右侧各一个开关）→ 共享 `ToolJsonSection(label, json, semanticContent)`：分区标题右置小开关，新样式/JSON 样式切换。

- [ ] **Step 1: 字符串（values + values-zh）**（`tool_ui_view_json`/`tool_ui_view_style` 已在 Task 4 Step 1 添加）

```xml
  <!-- values/strings.xml -->
  <string name="tool_ui_arguments">Arguments</string>
```
```xml
  <!-- values-zh/strings.xml -->
  <string name="tool_ui_arguments">参数</string>
```

- [ ] **Step 2: ToolDetailCommon.kt 新增共享组件**

```kotlin
/** 工具详情整页 JSON 视图（content-only）：参数 + 结果 JsonTreeView */
@Composable
internal fun ToolJsonBody(context: ToolUIContext) {
    Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        ToolJsonSection(
            label = stringResource(R.string.tool_ui_arguments),
            json = context.arguments,
        ) {
            JsonTreeView(context.arguments)
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

/**
 * 工具详情分区：label 右侧一个小开关（CodeSquare），切换 新样式(semantic) / JSON样式(JsonTreeView)。
 */
@Composable
internal fun ToolJsonSection(
    label: String,
    json: JsonElement?,
    semanticContent: @Composable () -> Unit,
) {
    var showJson by remember { mutableStateOf(false) }
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
            IconButton(onClick = { showJson = !showJson }, modifier = Modifier.size(28.dp)) {
                Icon(
                    imageVector = HugeIcons.CodeSquare,
                    contentDescription = stringResource(
                        if (showJson) R.string.tool_ui_view_style else R.string.tool_ui_view_json,
                    ),
                    modifier = Modifier.size(16.dp),
                    tint = if (showJson) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
            }
        }
        if (showJson && json != null) {
            JsonTreeView(json)
        } else {
            semanticContent()
        }
    }
}
```

需 import：`me.rerere.hugeicons.stroke.CodeSquare`、`androidx.compose.material3.IconButton`。

- [ ] **Step 3: ChatMessageTools 传 `jsonBody`（页面级 JSON 开关接线）**

`ToolDetailSheet`（Task 4 Step 1 最终签名）已有 `jsonBody` 槽。`ChatMessageTools.kt` 的 `if (showResult)` 改为：

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

效果：标题栏右侧出现 `CodeSquare` 开关；点击后整页切换到 `ToolJsonBody`（参数 + 结果的 JsonTreeView），再点切回语义视图。

- [ ] **Step 4: DefaultToolPreview 分区开关（item 7 落地于兜底视图）**

`DefaultToolPreview`（ToolUI.kt）内两个 FormItem（参数/结果）改用 `ToolJsonSection`：

```kotlin
        ToolJsonSection(
            label = stringResource(R.string.chat_message_tool_call_label, context.tool.toolName),
            json = context.arguments,
        ) {
            JsonTreeView(context.arguments)
        }
        if (context.tool.output.isNotEmpty()) {
            ToolJsonSection(
                label = stringResource(R.string.chat_message_tool_call_result),
                json = context.tool.output.filterIsInstance<UIMessagePart.Text>()
                    .joinToString("\n") { it.text }
                    .let { runCatching { JsonInstant.parseToJsonElement(it) }.getOrNull() },
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    context.tool.output.fastForEach { part -> /* 原逻辑不变 */ }
                }
            }
        }
```

- [ ] **Step 5: 未注册工具的语义化覆盖核对（"重新制作"落点）**

"精良化"的本期落点 = 统一脚手架（标题 + 页面级 JSON 开关）+ DefaultToolPreview 分区开关 + Task 9 新渲染器。对当前**未注册**、走 DefaultToolPreview 的工具（`eval_javascript`、`run_workflow` 等），其详情页自动获得：Sheet 标题栏（工具名）+ 页面级 JSON 开关 + 参数/结果分区开关。**本期不做**逐渲染器的额外语义精良化（后续迭代）。核对项：
- [ ] DefaultToolPreview（Step 4）分区开关生效
- [ ] 每个 `ToolDetailSheet` 调用都传了 `jsonBody = { ToolJsonBody(context) }`
- [ ] 语义渲染器在 Sheet 内正常滚动、无空白区（回归核对已注册渲染器 Preview 渲染结果）

- [ ] **Step 6: CI + 提交**

```bash
git push origin master
gh workflow run nightly-build-debug.yml --repo DevLintMar/rikkahub --ref master
git add -A
git commit -m "feat(ui): 工具详情标题栏+页面级/分区级 JSON 开关(ToolJsonSection/ToolJsonBody)"
```
**Expected**: CI 绿。

### Task 6: 长文本详情不截断（item 9）

**Files:**
- Modify: `app/src/main/java/me/rerere/rikkahub/ui/components/message/tools/JsonTreeView.kt`
- Modify: `app/src/main/java/me/rerere/rikkahub/ui/components/message/tools/BuiltinToolUIs.kt`（ConversationSearch 详情片段）

- [ ] **Step 1: JsonTreeView 字符串去 maxLines/省略号**

`JsonTreePrimitiveView`（L140-157）：

```kotlin
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
        modifier = modifier,
    )
}
```
（删除 `maxLines = if (primitive.isString) 4 else 1` 与 `overflow = TextOverflow.Ellipsis`；长字符串在 Sheet 滚动容器内完整显示。`TextOverflow` import 若无其他使用则删除。）

- [ ] **Step 2: ConversationSearch 详情消息片段去截断**

`ConversationSearchToolUI.Preview`（L600-610）：
```kotlin
                            val text = m.getStringContent("text").orEmpty()
                            if (text.isNotBlank()) {
                                Text(
                                    text = text,   // 原 text.take(120)
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
```
（删除 `take(120)` 与 `maxLines = 2`/`overflow`。）

- [ ] **Step 3: CI + 提交**

```bash
git push origin master
gh workflow run nightly-build-debug.yml --repo DevLintMar/rikkahub --ref master
git add -A
git commit -m "fix(ui): 详情视图长文本完整显示（JsonTreeView 字符串/会话搜索片段去截断）"
```
**Expected**: CI 绿。

### Task 7: 记忆删除二次确认（item 10）

**Files:**
- Modify: `app/src/main/java/me/rerere/rikkahub/ui/components/message/tools/BuiltinToolUIs.kt`（MemoryToolUI.Preview）
- Modify: `app/src/main/res/values/strings.xml`、`values-zh/strings.xml`

- [ ] **Step 1: 字符串**

```xml
  <!-- values/strings.xml -->
  <string name="tool_ui_delete_memory_title">Delete memory?</string>
  <string name="tool_ui_delete_memory_message">This memory will be permanently deleted. Continue?</string>
  <string name="tool_ui_delete_memory_confirm">Delete</string>
```
```xml
  <!-- values-zh/strings.xml -->
  <string name="tool_ui_delete_memory_title">删除记忆？</string>
  <string name="tool_ui_delete_memory_message">该记忆将被永久删除，是否继续？</string>
  <string name="tool_ui_delete_memory_confirm">删除</string>
```

- [ ] **Step 2: MemoryToolUI.Preview 加确认对话框**

`MemoryToolUI.Preview`（L127-185）：新增 `var showDeleteConfirm by remember { mutableStateOf(false) }`；删除 IconButton onClick 改为 `showDeleteConfirm = true`；底部加：

```kotlin
            if (showDeleteConfirm) {
                AlertDialog(
                    onDismissRequest = { showDeleteConfirm = false },
                    title = { Text(stringResource(R.string.tool_ui_delete_memory_title)) },
                    text = { Text(stringResource(R.string.tool_ui_delete_memory_message)) },
                    confirmButton = {
                        TextButton(
                            onClick = {
                                showDeleteConfirm = false
                                scope.launch {
                                    memoryRepo.deleteMemory(memoryId!!)
                                    onDismissRequest()
                                }
                            },
                        ) { Text(stringResource(R.string.tool_ui_delete_memory_confirm)) }
                    },
                    dismissButton = {
                        TextButton(onClick = { showDeleteConfirm = false }) {
                            Text(stringResource(android.R.string.cancel))
                        }
                    },
                )
            }
```
需 import `androidx.compose.material3.AlertDialog`。

- [ ] **Step 3: CI + 提交**

```bash
git push origin master
gh workflow run nightly-build-debug.yml --repo DevLintMar/rikkahub --ref master
git add -A
git commit -m "fix(ui): 删除记忆加二次确认对话框"
```
**Expected**: CI 绿。

### Task 8: ask_user 完成后仍显示调用中——修复 toolState 卡 CALLING（item 11）

**Files:**
- Modify: `app/src/main/java/me/rerere/rikkahub/data/ai/GenerationHandler.kt`

**根因（已定位）：** `GenerationHandler.kt` L277-285，`ToolApprovalState.Answered` 分支仅 `tool.copy(output = listOf(Text(answer)))`，未更新 `toolState`，工具停留默认 `CALLING`。聚合头 `runningTool` 判断（`toolState == CALLING || RUNNING`）永远命中 → 一直显示 "Calling tools… ask_user"。

- [ ] **Step 1: Answered 分支补 toolState**

```kotlin
                    is ToolApprovalState.Answered -> {
                        // Tool was answered by user (e.g., ask_user tool)
                        val answer = (tool.approvalState as ToolApprovalState.Answered).answer
                        executedTools += tool.copy(
                            output = listOf(
                                UIMessagePart.Text(answer)
                            ),
                            toolState = ToolState.SUCCEEDED,
                        )
                    }
```

- [ ] **Step 2: CI + 提交**

```bash
git push origin master
gh workflow run nightly-build-debug.yml --repo DevLintMar/rikkahub --ref master
git add -A
git commit -m "fix(ai): ask_user 完成后 toolState 置 SUCCEEDED，修复聚合头一直显示调用中"
```
**Expected**: CI 绿。

### Task 9: read_conversation / sub_agent 渲染器（items 12, 13）

**Files:**
- Create/Modify: `app/src/main/java/me/rerere/rikkahub/ui/components/message/tools/BuiltinToolUIs.kt`（新增两渲染器，或新建 `AgentToolUIs.kt`）
- Modify: `app/src/main/java/me/rerere/rikkahub/ui/components/message/tools/ToolUI.kt`（ToolUIRegistry 注册）
- Modify: `app/src/main/java/me/rerere/rikkahub/ui/components/message/tools/ToolPresentation.kt`（SUB_AGENT subject → description）
- Modify: `app/src/main/res/values/strings.xml`、`values-zh/strings.xml`

- [ ] **Step 1: 字符串**

```xml
  <!-- values/strings.xml -->
  <string name="chat_message_tool_read_conversation">Read conversation</string>
  <string name="chat_message_tool_sub_agent">Sub-agent</string>
  <string name="tool_ui_read_conv_messages">%1$d messages</string>
  <string name="tool_ui_read_conv_role_user">User</string>
  <string name="tool_ui_read_conv_role_assistant">Assistant</string>
  <string name="tool_ui_sub_agent_started">Started in background</string>
  <string name="tool_ui_sub_agent_completed">Completed</string>
  <string name="tool_ui_sub_agent_failed">Failed</string>
```
```xml
  <!-- values-zh/strings.xml -->
  <string name="chat_message_tool_read_conversation">阅读对话</string>
  <string name="chat_message_tool_sub_agent">子代理</string>
  <string name="tool_ui_read_conv_messages">%1$d 条消息</string>
  <string name="tool_ui_read_conv_role_user">用户</string>
  <string name="tool_ui_read_conv_role_assistant">助手</string>
  <string name="tool_ui_sub_agent_started">已在后台启动</string>
  <string name="tool_ui_sub_agent_completed">已完成</string>
  <string name="tool_ui_sub_agent_failed">失败</string>
```

- [ ] **Step 2: 新增 ReadConversationToolUI**

```kotlin
/** 阅读对话: 标题=阅读对话, 详情=标题+元信息+消息列表 */
object ReadConversationToolUI : ToolUIRenderer {
    override val toolName: String = "read_conversation"

    override fun icon(context: ToolUIContext): ImageVector = HugeIcons.MessageDelay01

    @Composable
    override fun title(context: ToolUIContext): String =
        stringResource(R.string.chat_message_tool_read_conversation)

    @Composable
    override fun Preview(context: ToolUIContext, onDismissRequest: () -> Unit) {
        val content = context.content
        if (content == null || content.getStringContent("error") != null) {
            DefaultToolPreview(context = context)
            return
        }
        val title = content.getStringContent("title")
            ?: context.arguments.getStringContent("conversation_id")
            ?: stringResource(R.string.tool_ui_untitled)
        val total = content.getStringContent("total_messages")?.toIntOrNull()
        val messages = (content.jsonObjectOrNull?.get("messages") as? JsonArray) ?: emptyList()
        ToolDetailContainer {
            Text(title, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                total?.let { ToolPill(stringResource(R.string.tool_ui_read_conv_messages, it)) }
            }
            if (messages.isEmpty()) {
                Text(
                    text = stringResource(R.string.tool_ui_conv_search_empty),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                messages.forEach { m ->
                    val role = m.getStringContent("role")
                    val text = m.getStringContent("text").orEmpty()
                    Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(
                            text = when (role) {
                                "user" -> stringResource(R.string.tool_ui_read_conv_role_user)
                                "assistant" -> stringResource(R.string.tool_ui_read_conv_role_assistant)
                                else -> role ?: ""
                            },
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary,
                        )
                        ToolTerminalOutput(text)
                    }
                }
            }
        }
    }
}
```
需 import `me.rerere.hugeicons.stroke.MessageDelay01`。

- [ ] **Step 3: 新增 SubAgentToolUI**

```kotlin
/** 子代理: 标题=子代理, 详情=描述+状态+结果/错误 */
object SubAgentToolUI : ToolUIRenderer {
    override val toolName: String = "sub_agent"

    override fun icon(context: ToolUIContext): ImageVector = HugeIcons.ChatBot

    @Composable
    override fun title(context: ToolUIContext): String =
        stringResource(R.string.chat_message_tool_sub_agent)

    @Composable
    override fun Preview(context: ToolUIContext, onDismissRequest: () -> Unit) {
        val content = context.content
        if (content == null) {
            DefaultToolPreview(context = context)
            return
        }
        val description = content.getStringContent("description")
            ?: context.arguments.getStringContent("description")
        val status = content.getStringContent("status")
        val mode = content.getStringContent("mode")
        val result = content.getStringContent("result")
        val error = content.getStringContent("error")
        val taskId = content.getStringContent("task_id")
        ToolDetailContainer {
            description?.takeIf { it.isNotBlank() }?.let {
                Text(it, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                when (status) {
                    "started" -> ToolPill(stringResource(R.string.tool_ui_sub_agent_started))
                    "completed" -> ToolPill(stringResource(R.string.tool_ui_sub_agent_completed))
                    "failed" -> ToolPill(stringResource(R.string.tool_ui_sub_agent_failed))
                }
                taskId?.takeIf { it.isNotBlank() }?.let { ToolPill(it) }
            }
            if (!error.isNullOrBlank()) {
                Text(
                    text = error,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            if (!result.isNullOrBlank()) {
                ToolTerminalOutput(result)
            }
        }
    }
}
```
需 import `me.rerere.hugeicons.stroke.ChatBot`。

- [ ] **Step 4: ToolUI.kt 注册**

`ToolUIRegistry.renderers` 列表追加 `ReadConversationToolUI`、`SubAgentToolUI`。

- [ ] **Step 5: ToolPresentation.kt — SUB_AGENT subject 改 description**

`subjectFor`（L110）中 `ToolKind.SUB_AGENT` 从 `envelope?.string("path")` 分离：

```kotlin
            ToolKind.SUB_AGENT -> envelope?.string("description")
                ?: args?.string("description")
```
`ToolKind.CONVERSATION_READ` 保持 `envelope?.string("title") ?: envelope?.string("conversation_id")`（已正确）。

- [ ] **Step 6: CI + 提交**

```bash
git push origin master
gh workflow run nightly-build-debug.yml --repo DevLintMar/rikkahub --ref master
git add -A
git commit -m "feat(ui): read_conversation(阅读对话/MessageDelay01) 与 sub_agent(子代理/ChatBot) 语义化渲染器"
```
**Expected**: CI 绿。

---

## Phase 2 — 收尾 A-D

### Task 10 (A): 死代码清理

**Files:**
- Modify: `app/src/main/java/me/rerere/rikkahub/ui/components/message/tools/ToolUI.kt`（删 `hasSummary`/`Summary` 接口 + `headerActions` 参数）
- Modify: `app/src/main/java/me/rerere/rikkahub/ui/components/message/tools/BuiltinToolUIs.kt`、`WorkspaceToolUIs.kt`（删 26 个 override）

**先验证**：`grep -rn "\.Summary(" app/src` 与 `grep -rn "hasSummary" app/src` 确认除定义/override 外零调用（Plan 2 Task 4 后 `renderer.Summary` 已弃用）。

- [ ] **Step 1: 删接口方法**

`ToolUIRenderer`（ToolUI.kt L67-73）删除：
```kotlin
    /** 步骤展开时是否显示内联摘要 */
    fun hasSummary(context: ToolUIContext): Boolean = false

    /** 步骤展开时的内联摘要 */
    @Composable
    fun Summary(context: ToolUIContext) {
    }
```

- [ ] **Step 2: 删 `DefaultToolPreview.headerActions` 参数（如 Task 5 后仍存在）**

`DefaultToolPreview(context, headerActions)` → `DefaultToolPreview(context)`，删除内部 `Row { ... headerActions?.invoke() }` 头部（标题由 Sheet 提供）。更新唯一调用方。

- [ ] **Step 3: 删全部 override**

删除以下文件中的 `override fun hasSummary` 与 `override fun Summary` 实现：
- BuiltinToolUIs.kt：MemoryToolUI、SearchWebToolUI、ScrapeWebToolUI、RecentChatsToolUI、ConversationSearchToolUI、GetScreenTimeToolUI、CalendarQueryToolUI
- WorkspaceToolUIs.kt：EditFileToolUI、ReadFileToolUI、WriteFileToolUI、ShellToolUI、GlobToolUI、GrepToolUI

同步删除各 renderer 中**仅为 Summary 服务**的私有函数（如 `items(context)` 若只被 Summary 用则一并删；若 Preview 也用则保留）。逐个核对编译。

- [ ] **Step 4: CI + 提交**

```bash
git push origin master
gh workflow run nightly-build-debug.yml --repo DevLintMar/rikkahub --ref master
git add -A
git commit -m "chore(ui): 清理死代码 hasSummary/Summary 接口+26 override+headerActions 参数"
```
**Expected**: CI 绿。

### Task 11 (B): 文案 polish 剩余

**Files:**
- Modify: `app/src/main/java/me/rerere/rikkahub/ui/components/message/tools/ToolPresentation.kt`（`tool_summary_empty_file` 的 "file" 硬编码）
- Modify: `app/src/main/res/values/strings.xml`、`values-zh/strings.xml`

（聚合头文案/单复数/纯工具块已随 Task 1 完成。）

- [ ] **Step 1: `tool_summary_empty_file` 去硬编码**

`emptySummary`（ToolPresentation.kt L170）：
```kotlin
    ToolKind.FILE_READ -> stringResource(R.string.tool_summary_empty_file, subject ?: "file")
```
→ `subject ?: stringResource(R.string.tool_ui_file)`（`tool_ui_file` 已存在于两 locale）。

- [ ] **Step 2: CI + 提交**

```bash
git push origin master
gh workflow run nightly-build-debug.yml --repo DevLintMar/rikkahub --ref master
git add -A
git commit -m "fix(ui): tool_summary_empty_file 的 file 兜底本地化"
```
**Expected**: CI 绿。

### Task 12 (C): 渲染/行为微调

**Files:**
- Modify: `app/src/main/java/me/rerere/rikkahub/ui/components/message/tools/WorkspaceToolUIs.kt`（glob pill 换行对齐）
- Modify: `app/src/main/java/me/rerere/rikkahub/ui/components/message/tools/BuiltinToolUIs.kt`（memory 高度嵌套、calendar ifBlank、clipboard else、TTS 按钮）
- Modify: `app/src/main/java/me/rerere/rikkahub/ui/components/message/tools/ToolPresentation.kt`（CONVERSATION_LIST subject）

- [ ] **Step 1: glob 行 pill `CenterVertically` → `Top`**

`GlobToolUI.Preview` 文件行 `Row(... verticalAlignment = Alignment.CenterVertically)`（L536）→ `Alignment.Top`（长路径换行时 pill 不居中，与 grep 匹配行一致）。

- [ ] **Step 2: memory 详情高度嵌套简化**

`MemoryToolUI.Preview` 外层 `Column(fillMaxHeight(0.8f))` 包 `ToolDetailContainer` 的嵌套（Task 4 已把外层改 `fillMaxWidth()`）——核对后去掉冗余外层，直接 `ToolDetailContainer { ... }`；删除按钮行保持底部固定（`Column` 外包即可）。

- [ ] **Step 3: calendar 空串 title → ifBlank**

`CalendarQueryToolUI.Preview`（L822）`ev.getStringContent("title") ?: stringResource(...)` → `ev.getStringContent("title")?.takeIf { it.isNotBlank() } ?: stringResource(...)`。

- [ ] **Step 4: clipboard else 分支诚实化**

`ClipboardToolUI.Preview`（L352）`if (action == ACTION_READ) {...} else {...}` → 显式判断：
```kotlin
        when (action) {
            ACTION_READ -> if (text.isNullOrBlank()) { /* empty */ } else { ToolTerminalOutput(text) }
            ACTION_WRITE -> { /* written + text */ }
            else -> DefaultToolPreview(context = context)
        }
```

- [ ] **Step 5: CONVERSATION_LIST subject 用首条对话标题**

`subjectFor`（ToolPresentation.kt L100）：
```kotlin
            ToolKind.CONVERSATION_LIST -> (envelope?.get("conversations") as? JsonArray)
                ?.firstOrNull()?.let { (it as? JsonObject)?.get("title") as? JsonPrimitive }
                ?.contentOrNull
```

- [ ] **Step 6: TTS 重播按钮默认尺寸**

`TextToSpeechToolUI.Preview` 的 `FilledTonalIconButton` 去掉尺寸覆盖，保持 Material3 默认 40dp 触点（若 Task 4 后无尺寸 modifier 则跳过）。

- [ ] **Step 7: CI + 提交**

```bash
git push origin master
gh workflow run nightly-build-debug.yml --repo DevLintMar/rikkahub --ref master
git add -A
git commit -m "fix(ui): 渲染微调(glob pill 对齐/memory 嵌套/calendar ifBlank/clipboard else/CONVERSATION_LIST subject/TTS 按钮)"
```
**Expected**: CI 绿。

### Task 13 (D): 性能/健壮性

**Files:**
- Modify: `app/src/main/java/me/rerere/rikkahub/ui/components/message/tools/JsonTreeView.kt`（大响应硬化 + 交叉注释）
- Modify: `app/src/main/java/me/rerere/rikkahub/ui/components/message/ChatMessage.kt`（`filterIsInstance` → `asReversed().firstOrNull`）
- Modify: `app/src/main/java/me/rerere/rikkahub/ui/components/message/tools/ToolPresentation.kt`（`sizeBytes.toInt` 溢出）
- Modify: `app/src/main/java/me/rerere/rikkahub/ui/components/ui/JsonTree.kt`（交叉注释）

- [ ] **Step 1: JsonTreeView 大响应硬化**

`JsonTreeObjectView`/`JsonTreeArrayView` 加深度/元素上限（MCP 几千元素全量组合）。**用共享 `IntArray(1)` 持有剩余计数**沿递归传递（真上限：嵌套调用实时回流；写 IntArray 不触发重组）：

```kotlin
private const val JSON_TREE_MAX_DEPTH = 12
private const val JSON_TREE_MAX_ITEMS = 2000

@Composable
private fun JsonTreeObjectView(obj: JsonObject, depth: Int, remaining: IntArray) {
    if (depth > JSON_TREE_MAX_DEPTH || remaining[0] <= 0) {
        Text("…", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        return
    }
    Column(modifier = Modifier.fillMaxWidth()) {
        obj.entries.forEach { (key, value) ->
            if (remaining[0] <= 0) return@forEach
            remaining[0]--
            /* 原有渲染；嵌套递归调用传 depth+1 与同一 remaining */
        }
    }
}
```
调用方 `JsonTreeView(json)` 构造 `val remaining = remember { intArrayOf(JSON_TREE_MAX_ITEMS) }` 并传给两个视图（`JsonTreeArrayView` 同样处理）。超限尾部显示省略。`remember { intArrayOf(...) }` 的持有者在 `JsonTreeView` 顶层；递归子视图只读写，不重建。

- [ ] **Step 2: 聚合头 filterIsInstance → asReversed().firstOrNull**

`ChatMessage.kt` 聚合头（L345-349）：
```kotlin
                                val runningTool = block.steps.asReversed().firstOrNull { step ->
                                    step is ThinkingStep.ToolStep &&
                                        (step.tool.toolState == ToolState.RUNNING ||
                                            step.tool.toolState == ToolState.CALLING)
                                } as? ThinkingStep.ToolStep
```
（`asReversed().firstOrNull` 避免 `filterIsInstance` 整列表分配；`is` 智能转换在 `&&` 第二操作数内有效，尾部 `as?` 归一类型。）

- [ ] **Step 3: sizeBytes.toInt 溢出**

`ToolPresentation.kt` countFor（L124-125）：
```kotlin
        ToolKind.FILE_READ, ToolKind.FILE_WRITE, ToolKind.FILE_EDIT -> envelope?.get("sizeBytes")
            ?.let { (it as? JsonPrimitive)?.contentOrNull?.toLongOrNull() }
            ?.coerceAtMost(Int.MAX_VALUE.toLong())?.toInt()
```

- [ ] **Step 4: 两个 JSON 树组件交叉注释**

`ui/components/ui/JsonTree.kt`（日志页可展开树）文件头加注释：`/** 日志页可展开 JSON 树。详情页的平铺 JSON 树见 ui/components/message/tools/JsonTreeView.kt（交互模式不同，两组件并存不合并）。*/`
`JsonTreeView.kt` 文件头（L29 前）加对称注释。

- [ ] **Step 5: CI + 提交**

```bash
git push origin master
gh workflow run nightly-build-debug.yml --repo DevLintMar/rikkahub --ref master
git add -A
git commit -m "perf(ui): JsonTreeView 深度/条目硬化+聚合头 asReversed+sizeBytes 溢出+双 JSON 树交叉注释"
```
**Expected**: CI 绿。

---

## 自审

**spec 覆盖：**
- items 1-3（高度/秒数/文本色）→ Task 1, 2 ✓
- item 4（图标/箭头色）→ Task 2 ✓
- item 5（自动展开）→ Task 3 ✓
- item 6（半屏→近全屏）→ Task 4 ✓
- items 7-8（分区/页面 JSON 开关 + 标题）→ Task 5 ✓
- item 9（长文本）→ Task 6 ✓
- item 10（删除确认）→ Task 7 ✓
- item 11（ask_user）→ Task 8 ✓
- items 12-13（渲染器）→ Task 9 ✓
- A（死代码）→ Task 10 ✓
- B（文案）→ Task 1 + Task 11 ✓
- C（渲染微调）→ Task 12 ✓
- D（性能）→ Task 13 ✓

**类型/签名一致性：** `ChainOfThought` 新增 `autoExpand`（Task 1 Produces → Task 3 使用）；`ToolDetailSheet(title, onDismiss, jsonBody?, content)` 在 Task 4 定义、Task 5 接线；`ToolJsonSection/ToolJsonBody` 在 Task 5 定义并被 DefaultToolPreview/ChatMessageTools 使用；`ToolUIRenderer` 移除 `hasSummary/Summary` 在 Task 10，早于 Task 10 的任务不得依赖这两个方法（全部任务均只用 `icon/title/Preview`）✓。

**占位符扫描：** 无 TBD/TODO；除 Task 5 Step 5（语义 Preview 逐工具精良化）明确标注"低成本则做、否则保留语义内容"为有意范围控制外，均给全代码。

---

## 执行交接

计划已保存。两种执行方式：

**1. Subagent 驱动（推荐）** — 每任务 fresh subagent + task review + fix loop，直接 master 执行（沿用 Plan 1/2/3 惯例）。

**2. 内联执行** — 本会话 executing-plans 批量执行。

选哪种？确认后开始。每任务提交后照例 CI 验证（push 在前）。
