# 交接文档：markdown file:// 链接指向工作区/uploads + CI 单测从纸面到真实 — 下一阶段入口

**日期**：2026-08-08
**目的**：上下文清理前的完整交接。新会话读本文档即可续接。master HEAD = `bd5c98e`，工作树干净，debug CI 全绿（`31255172614` success，headSha `bd5c98e` 核对一致）。

---

## 0. 一句话概况

接上一交接 `50f8ef9`（聊天性能 + 工作区备份）。本阶段两件事：**① markdown `file://` 链接指向工作区/uploads 文件**（AI 消息内联工作区图片，核心 = 逻辑路径 `file:///workspace/...`、`file:///upload/...` → 宿主 File 的解析层 + CompositionLocal 渲染接入 + AI 提示词引导）；**② debug CI 增加单元测试步骤**——首次让 JVM 单测真实执行，一路扫出并修掉 4 类历史欠账（含 1 个真实生产 bug：`parseEnvelope` 工具状态信封从未生效）。全程 CI 绿。master `50f8ef9` → `bd5c98e`（5 commits）。

---

## 1. 已完成工作（commit 链）

### 一、markdown file:// 功能（用户需求）

**需求**：AI 在 markdown 消息中无法插入工作区图片。目标 `![alt](file:///workspace/...)` / `![alt](file:///upload/...)` 渲染为可显示图片、可点击链接。

**探明的事实**（两个 Explore agent）：
- AI 侧路径是 Rootfs 逻辑路径：工作区文件 = `/workspace/<rel>`（`files/` 字面量从不暴露给 AI）；upload = `/upload/<name>`（`<filesDir>/upload` **单数**，全局 bind mount，提示词标只读）。
- 物理映射：`/workspace/<rel>` → `<filesDir>/workspaces/<workspaceId>/files/<rel>`；`/upload/<name>` → `<filesDir>/upload/<name>`。
- workspaceId（= workspace root UUID）来自 `assistant.workspaceId`；`ChatMessage` 已持有 `assistant` 参数透传到 `MessagePartsBlock`。
- 渲染链路：`ChatMessage` → `MessagePartsBlock(assistant)` → `MarkdownBlock(content, onClickCitation)` → `MarkdownNode`（private 递归几十处，**必须 CompositionLocal 传递**）或 `MarkdownNew`（HTML 路径）。
- Coil3 原生加载 `file://` URI（消息图已是 `file:///data/user/0/.../files/upload/<uuid>` 主路径）；`File.toUri().toString()` 即得可用 model。
- FileProvider 已配置（`file_paths.xml` `<files-path path="." />` 覆盖整个 `filesDir`，含 `workspaces/` 与 `upload/`）。

| commit | 内容 |
|---|---|
| 38575fb | **功能主体**（6 文件，291+/55-）：见下四块 |

- **`WorkspaceFileUrlResolver.kt`**（新增，`ui/components/richtext/`）：纯函数 `resolveFile(filesDir, workspaceId, href): File?`。`file:///workspace/<rel>` → `filesDir/workspaces/<id>/files/<rel>`（需 workspaceId）；`file:///upload/<name>` → `filesDir/upload/<name>`（无需 workspaceId）；**容忍不带 `file://` 的裸 `/workspace/...`、`/upload/...`**（AI 漏写前缀不空白）；其余（http/https/data/content/宿主绝对路径/rootfs 内部）→ null 保持原样；百分号解码（%XX，**不解码 `+`**——路径中 `+` 是合法字符）；防路径穿越（canonicalFile 必须在根内）。
- **`Markdown.kt`**：`LocalWorkspaceFileProvider = staticCompositionLocalOf<((String) -> File?)?>`；`MarkdownBlock` 加 `workspaceId: String? = null` 参数，函数体最外层 `CompositionLocalProvider` 包裹（resolver = remember(workspaceId) 闭包捕获 context.filesDir + workspaceId）；IMAGE 分支 `workspaceImage?.toUri()?.toString() ?: imageUrl`（`takeIf { it.isFile }` 回退）；INLINE_LINK 分支 `workspaceFile != null` 时 FileProvider `ACTION_VIEW` + `FLAG_GRANT_READ_URI_PERMISSION` + `runCatching`（无应用处理静默），否则原系统 Intent。
- **`MarkdownNew.kt`**：两处 `<img>`（块级 ~246 / 内联 ~752）同样解析 src。
- **`ChatMessage.kt`**：`MessagePartsBlock` 内三处 `MarkdownBlock(...)` 调用加 `workspaceId = assistant?.workspaceId?.toString()`。
- **`WorkspaceReminderTransformer.kt`**：`buildWorkspacePrompt` 追加一句引导——"To embed a workspace file in your markdown reply, reference it with a `file://` URL: `![alt](file:///workspace/notes.md)` ... or `![alt](file:///upload/photo.png)`"。**功能可用性的关键一环**（没有它 AI 不知道能这么写）。

### 二、CI 单测步骤（把纸面测试变成真实测试）

**背景**：`nightly-build-debug.yml` 原只跑 `app:assembleDebug`，JVM 单测（27+ 个既有测试文件）**从未被编译/执行**。用户问"这单测是怎么测的"→ 自查发现 CI 不跑测试 → 用户确认"加进 debug CI"。

| commit | 内容 |
|---|---|
| 79a86a2 | `nightly-build-debug.yml` 加 `Unit Tests` 步骤：`./gradlew :app:testDebugUnitTest`（在 assembleDebug 之后）。此后单测红则构建失败 |
| 0d42d2f | **kotlin.test → JUnit4**：本会话写的两个测试用了 `kotlin.test`（Test/assertEquals/assertIs/BeforeTest/AfterTest），但 app test classpath **只有 junit 4.13.2**（`testImplementation(libs.junit)`，无 `kotlin("test")`）。`compileDebugUnitTestKotlin` 全量 Unresolved。改 `org.junit.*` 风格（与既有 27 个文件一致） |
| a601f9e | **assertInstanceOf 是 JUnit5 API**（`org.junit.jupiter.api.Assertions`），JUnit4 的 `org.junit.Assert` 没有 → 改 `assertTrue(is)` + 强转（断言失败带实际类型提示）。同 commit 修**既有** `ToolResultEnvelopeTest` 方法名反引号内含 `/`（`SUCCEEDED / EMPTY`，`/` 是 Kotlin 标识符非法字符）→ 改 `or`。该文件从未被编译过，本次首次暴露 |
| bd5c98e | **真实生产 bug**：`ToolResultEnvelope.parseEnvelope`（`data/ai/tools/ToolResultEnvelope.kt:13`）`joinToString("\n")` **漏 transform**，把 data class `Text` 的 `toString()`（`Text(text=..., metadata=...)`）当 JSON 解析 → **恒返回 null**。generation 层 `GenerationHandler.kt:325` 的 `inferToolState` 因此对 `error`/`exitCode` 信封全部误判 SUCCEEDED——**工具失败状态在生产上从未正确显示过**。修复：`{ it.text }`。同 commit 修 `ThinkingBlockAggregateTest` 脆弱断言：`reasoning()` 里 createdAt/finishedAt 分别调 `Clock.System.now()`，微秒抖动 + `inWholeMilliseconds` 截断 → 5000ms 断言不稳定 → 固定基准时间戳 |

**CI 判定链**（每 run 核对 headSha）：
- `31253094100` failure（首次触发，还有单测前状态）→ `31253441343` failure（kotlin.test 编译错）→ `31253939258` failure（assertInstanceOf + 方法名 `/`）→ `31254283527` failure（3 个测试断言失败：parseEnvelope×2 + ThinkingBlock 时间戳）→ **`31255172614` success（179 tests 全过，含 Unit Tests 步骤）**。

---

## 2. 关键设计决策（认知遗留）

### markdown file://
- **用 AI 视角逻辑路径而非宿主绝对路径**：`file:///workspace/<rel>`、`file:///upload/<name>` 天然跨包名/跨 workspace，不依赖 `filesDir` 绝对路径，**无需 RestorePathRebaser 干预**（那套只处理持久化的宿主 `file:///data/user/0/<pkg>/files/` 前缀）。消息里存的是逻辑路径，恢复备份也不受影响。
- **CompositionLocal 而非逐层透传**：`MarkdownNode` 是 private 递归 composable、几十处递归调用，透传参数侵入巨大；CompositionLocal 一次定义，`MarkdownBlock` 顶层层级提供，默认 null 时其他调用点（Export 预览、翻译、时间线）自动降级不解析。
- **`takeIf { it.isFile }` 回退**：文件不存在/路径解析不到时回退原字符串，Coil 静默失败不崩。图片点击预览（`ImagePreviewDialog` 用同一字符串）+ 保存（`saveMessageImage` 的 `file:` 分支）都自动支持，无需改。
- **AI 提示词引导是功能成立的前提**：`WorkspaceReminderTransformer` 已告诉 AI `/workspace`、`/upload` 挂载；加一句 file:// 引用语法，AI 才会产出这种链接。
- **裸路径容忍**：AI 可能漏写 `file://` 前缀直接写 `/workspace/x.png`，解析层顺手兜住，避免"图白了但看不出为什么"。

### CI 单测
- **纸面测试的代价**：CI 不跑单测时，测试文件的编译错误、生产代码中只有测试能暴露的逻辑错误全部潜伏。`parseEnvelope` 的 bug 从 2026-08-02 引入至今才被 CI 抓到——**单测进 CI 是必要的**。
- **测试风格必须匹配依赖**：仓库只有 `testImplementation(libs.junit)`（4.13.2），没有 kotlin-test。写测试前先查 build.gradle.kts 的 test 依赖，别默认 kotlin.test 可用。
- **JUnit4 无 assertInstanceOf**：那是 JUnit5 API。类型断言用 `assertTrue(is)` + cast。
- **测试名反引号**：Kotlin 标识符内 `/` 非法，方法名里别放 `/`。

---

## 3. git 状态 / CI

- 分支 `master`，HEAD = `bd5c98e`（`fix(tools): 修 parseEnvelope 生产 bug + ThinkingBlock 测试时间戳抖动`），工作树干净
- **debug CI 全绿**：`bd5c98e` → `31255172614` success（含 Unit Tests 步骤，179 tests 通过，headSha 核对一致）
- release / pre 工作流**仍未跑**（convention plugin + keep 规则在混淆路径未验证，见 §5-②）

## 4. 恢复地图

| 台账/文档 | 路径 |
|---|---|
| 上一交接（聊天性能+工作区备份） | `docs/superpowers/handoffs/2026-08-08-chat-performance-workspace-backup-next-phase.md` |
| 本阶段核心文件 | `ui/components/richtext/WorkspaceFileUrlResolver.kt`（新增）、`ui/components/richtext/Markdown.kt`（LocalWorkspaceFileProvider/MarkdownBlock workspaceId/IMAGE/INLINE_LINK）、`ui/components/richtext/MarkdownNew.kt`（两处 img）、`ui/components/message/ChatMessage.kt`（三处传参）、`data/ai/transformers/WorkspaceReminderTransformer.kt`（file:// 引导）、`data/ai/tools/ToolResultEnvelope.kt`（parseEnvelope 修复）、`.github/workflows/nightly-build-debug.yml`（Unit Tests 步骤） |
| 单元测试 | `app/src/test/java/me/rerere/rikkahub/ui/components/richtext/WorkspaceFileUrlResolverTest.kt`（12 例）、`ui/components/message/GroupMessagePartsTest.kt`、`data/ai/tools/ToolResultEnvelopeTest.kt`、`data/ai/tools/ThinkingBlockAggregateTest.kt` |
| 相关 memory | `cross-package-backup-restore`（宿主 file:// rebase，本功能逻辑路径不受影响）、`backup-coverage-gaps`（workspaces/ 已独立备份） |

## 5. 待办 / 挂起项（按优先级）

### ① 设备核验（唯一需用户上手）

装 `bd5c98e` debug 包：
1. **工作区图片内联**：绑 workspace 的助手对话，AI 按新提示词用 `![alt](file:///workspace/xxx.png)` 发消息 → 图片直接显示、点击放大、保存到相册
2. **链接**：`[text](file:///workspace/...)` 可点开文件
3. **/upload 引用**：AI 引用用户上传的图 `![alt](file:///upload/photo.png)` 同样显示
4. **上阶段遗留**：工作区导出/导入（含 rootfs，不再报 bin/awk）、滚动性能、多图合并、MCP 开关 + 新模型

**若图片不显示**：让用户贴出 AI 那条消息的原文 markdown——大概率 AI 用了裸 `/workspace/...`（应已被解析层兜住）、带真实文件名（需检查文件是否真在 files 区）、或用了宿主绝对路径（属解析层 null 分支，应回退原样——需确认该路径存在）。

### ② 遗留 Minor / defer（不阻塞）

- **release / pre CI 未跑**：convention plugin + `rikkahub.keep` + `optimization{enable}` 在混淆路径未验证。建议触发 `nightly-build.yml` + `nightly-build-pre.yml` 确认 release 构建链（keep 规则真正生效的构建）。
- **历史挂起**（2026-08-07 交接 §5-②）：Firecrawl crawl/batch、Jina 多 URL、Exa ids 复用、Metaso scope；乱召回（语义搜索 bug）、Task 12 ripgrep artifact 流水线、UpdateChecker.kt 删 `return@flow`。
- **诊断日志仍在**（ScrollFrameSampler + ChatImg，debug-only）：真机确认后清理。
- **图片区滚动 slow16-33 仍偏高**（30fps 级）：已可接受；继续压方向 = 组合侧（recompose 范围 / beyondBoundsItemCount 预热）。

### ③ 下一阶段候选（未开工）

无明确用户需求。可选项：release/pre CI 验证、诊断日志清理、历史挂起清理、或用户新需求。

## 6. 技术约束 / 惯例（必须遵守）

- **本机无编译器**：不运行 gradle。静态编写 + review，编译验证全靠 CI。
- **CI 判定铁律**：`gh run list` 权威判定 + **核对 headSha**。流程：先 push 再 `gh workflow run nightly-build-debug.yml --repo DevLintMar/rikkahub --ref master`。**CI 结果无论绿红都要主动汇报**（本会话教训：两次红靠用户发现——Monitor 工具的轮询要覆盖"完成/失败/查询出错"三态，别用 run_in_background + until 等通知）。
- **JVM 单测风格**：testImplementation 只有 junit 4.13.2，**无 kotlin-test**。写测试用 `org.junit.Test` / `org.junit.Assert.*`；类型断言用 `assertTrue(is)` + cast（无 assertInstanceOf）；测试方法名反引号内禁 `/`。
- **库模块 build.gradle.kts 用 convention plugin**（`rikkahub.android.library(.compose)`），pre buildType 保留；keep 规则进 `app/src/main/keepRules/rikkahub.keep`；release 用 `optimization { enable = true }`（AGP 9.3.1）。
- **Coil3**：AsyncImage 传预构建 ImageRequest 需显式 `.size()`；缓存来源用 `SuccessResult.dataSource`。
- **Compose 绘制期状态铁律**：drawBehind 绘制期读取的状态必须组合期固化（CompositionLocal/remember）。
- **CompositionLocal 惯例**：跨递归 composable 上下文用 staticCompositionLocalOf，顶层层级提供、默认降级。
- runCatching 不能包 suspend；suspend 用 try/catch + 重抛 `CancellationException`。
- 字符串双写 en+zh；六 locale 占位符串全写（`%1$s`）。
- 中文 conventional commit；文件删除走 `~/.claude/scripts/trash.sh`；force-push 需用户明确要求。
- 本机 git 换行警告（LF→CRLF）为仓库既有状态，非错误。

## 7. 停靠点

- **已完成**：markdown file:// 功能（解析层 + CompositionLocal 渲染 + AI 引导 + 12 例单测）、CI 单测步骤 + 4 类历史欠账修复（含 parseEnvelope 生产 bug）。master `bd5c98e`，debug CI 全绿（含 179 tests）。
- **待确认**：设备核验（§5-①，重点工作区图片内联 + /upload 引用）。
- **下一阶段**：无明确需求；候选 = release/pre CI 验证、诊断日志清理、历史挂起清理。
- **恢复动作**：读本文档 §4/§5；开始前先让用户核验 markdown file:// 闭环（贴 AI 消息原文排查问题）。
