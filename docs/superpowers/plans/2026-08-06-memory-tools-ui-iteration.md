# 记忆工具 UI 迭代 + 错误处理修复（9 点需求）

**日期**：2026-08-06
**需求来源**：用户新一轮反馈（5 点记忆工具 UI + 2 点工具调用展示修复 + 图标库纠正 + 错误消息去堆栈）

## Global Constraints（约束所有任务）

1. **本机无 Android 编译器**：禁止运行 gradle/kotlinc，禁止写/跑单元测试。验证 = 静态代码阅读 + GitHub Actions CI（`gh run list --workflow nightly-build-debug.yml --repo DevLintMar/rikkahub --limit 1 --json databaseId,headSha,status,conclusion`，必须核对 headSha）。图标名以库内真实存在为准（见约束 5）。
2. **图标库**：本需求涉及的图标一律用 HugeIcons（`me.rerere.hugeicons.HugeIcons.<Name>` + `import me.rerere.hugeicons.stroke.<Name>`）。**已验证存在**：`Redo`、`FolderSearch`、`BookmarkCheck02`、`QuillWrite01`、`PencilEdit01`、`Eraser`、`Delete01`。库内**没有** `BookCheck`/`BookOpenCheck`。
3. **字符串双写**：新/改字符串必须 en（`values/strings.xml`）+ zh（`values-zh/strings.xml`）；ja/ko-rKR/ru/zh-rTW 已有对应 key 的一并更新。占位符格式串（`%1$s` 等）六个 locale 全写。
4. **runCatching 不能包 suspend**；suspend 错误处理用 try/catch + 重抛 `CancellationException`（现有代码已有先例，照抄风格）。
5. **精确执行，永不越界**：只改任务列出的内容；不顺手重构无关代码。文件删除走 `~/.claude/scripts/trash.sh`（本计划无需删文件）。
6. **commit 风格**：中文 conventional commit（feat(memory)/fix(...) 前缀），每任务一次 commit，本地提交后由控制器统一推送验证。

## 现状关键事实（调研结论）

- 工具抛异常时 `GenerationHandler.kt` onFailure 生成错误信封 `{type, error:"error", message:"[类名] 消息"}` + `ToolState.FAILED`（`inferToolState` 见 error 字段即 FAILED）。
- `MemoryRepository.editMemoryByTitle` 的 old_text 未匹配经 `replaceText` 抛 `IllegalArgumentException` → 已走失败路径；信封 message 含 `[java.lang.IllegalStateException] ...` 堆栈类名。
- `buildDeleteTool` 删除不存在的标题时静默 `success:false`（用户已确认改为报错）。
- 错误文案当前显示在 `ControlledChainOfThoughtStep` 的 `extra`（标题行右侧）→ 长错误挤压标题。
- 聚合思考头（"思考了x秒，调用了x个工具"）大脑图标在 `ChatMessage.kt` `ChainOfThoughtHeaderRow`：Box 20.dp + Icon 16.dp。
- 概览标题格式参照 `GrepToolUI`：`stringResource(R.string.tool_ui_grep, pattern)`——标题串带参数。

---

## Task 1：数据层 — 错误信封去堆栈 + 记忆工具失败语义

**文件**：
- `app/src/main/java/me/rerere/rikkahub/data/ai/tools/ToolResultEnvelope.kt`
- `app/src/main/java/me/rerere/rikkahub/data/ai/tools/MemoryTools.kt`

**改动**：

1. `ToolResultEnvelope.kt` 新增公开顶层函数：
```kotlin
/** 工具错误消息：只保留原因本身，不暴露异常类名等堆栈细节 */
fun toolErrorMessage(throwable: Throwable): String =
    throwable.message ?: throwable.javaClass.simpleName
```

2. `GenerationHandler.kt` onFailure 分支（约 337-355 行）：把 `JsonPrimitive("[${it.javaClass.name}] ${it.message ?: it.javaClass.simpleName}")` 改为 `JsonPrimitive(toolErrorMessage(it))`，并加 import。

3. `MemoryTools.kt` `buildDeleteTool` 的 execute：删除失败时报错而不是静默成功：
```kotlin
val success = deleteFn(title)
if (!success) error("Memory not found: $title")
```
（`error()` 抛出后被 GenerationHandler 捕获成错误信封 + FAILED，与其他失败路径一致。信封里的 `success` 字段保留不动。）

**验收**：edit_memory old_text 未匹配 → UI 显示失败（红色错误文案，无 `[java.lang...]` 前缀）；delete_memory 标题不存在 → 失败信封而非成功。

---

## Task 2：UI 层 — read_memory 自定义信封 + 七工具概览/详情/图标/按钮

**文件**：`app/src/main/java/me/rerere/rikkahub/ui/components/message/tools/MemoryToolsUIs.kt`、`values/strings.xml`、`values-zh/strings.xml`、`values-ja/strings.xml`、`values-ko-rKR/strings.xml`、`values-ru/strings.xml`、`values-zh-rTW/strings.xml`

**改动**：

1. **字符串**：七个工具标题串全部改为带参格式（en + zh，其余 4 locale 同步）：
   - `chat_message_tool_read_memory`：`Read memory: %1$s` / `读取记忆：%1$s`
   - `chat_message_tool_write_memory`：`Write memory: %1$s` / `写入记忆：%1$s`
   - `chat_message_tool_edit_memory`：`Edit memory: %1$s` / `编辑记忆：%1$s`
   - `chat_message_tool_delete_memory`：`Delete memory: %1$s` / `删除记忆：%1$s`
   - `chat_message_tool_create_active_memory`：`Create active memory: %1$s` / `新建活跃记忆：%1$s`
   - `chat_message_tool_edit_active_memory`：`Edit active memory: %1$s` / `编辑活跃记忆：%1$s`
   - `chat_message_tool_delete_active_memory`：`Delete active memory: %1$s` / `删除活跃记忆：%1$s`
   - （ja/ko-rKR/ru/zh-rTW 按现有译文同样追加 `: %1$s` 等价形式）

2. **七个渲染器的 `title()`**：从信封或入参取标题（envelope 优先，fallback arguments），拼进标题串；两者都取不到时用内容前 40 字兜底（与注入兜底一致），再不行返回原串。参照 `GrepToolUI` 的参数标题写法。

3. **read_memory 加 Preview**：复用 `MemoryDetailPreview`（kind=READ 新增枚举值，无动作按钮）。READ 分支：标题（SemiBold）+ 描述（italic，非空才显示）+ 内容（bodyMedium）+ id pill；错误信封照旧 fallback `DefaultToolPreview`。加 `hasSemanticDetail`（与 write/edit 一致：content 非 null 且无 error 字段）。

4. **删除概览小字换内容**：`MemoryDeletedSummary` 从显示 `title` 改为显示 `content`，样式对齐创建记忆概览：`typography.labelSmall` + `color = onPrimaryContainer` + `maxLines = 3` + `Ellipsis`。content 取不到（旧数据/失败信封）时退回 title 再退回无。

5. **详情信封加标题+描述**（write/edit/delete × saved/active 六种 + read）：`MemoryDetailPreview` 顶部统一加：标题 `bodyMedium` + `FontWeight.SemiBold`；描述 `bodyMedium` + `FontStyle.Italic` + `onSurfaceVariant`（为空不显示）。DELETE 分支现有重复的标题渲染移除（由共用头部承担）。

6. **图标**：read_memory 从 `Lucide.BookCheck` 改为 `HugeIcons.BookmarkCheck02`；删除两个 Lucide import（`BookCheck`/`Lucide`，若无其他使用）。

7. **回退/恢复按钮换 Redo 图标**：`MemoryDetailActions` 中 canRestore 与 canRevert 的 `TextButton`+Text 改为 `IconButton` + `Icon(HugeIcons.Redo)`，contentDescription 沿用现有 `tool_ui_restore_memory` / `tool_ui_revert_edit` 串。两个确认对话框保留（回退仍弹确认；恢复直接执行，与现状一致）。

**验收**：概览标题形如"读取记忆：我的标题"；删除折叠行小字是记忆内容（onPrimaryContainer 色）；详情页标题加粗+描述斜体在最上方；read_memory 有语义化详情；读取图标为 BookmarkCheck02；回退/恢复为 Redo 图标按钮。

---

## Task 3：展示层 — 错误信息移到标题下方 + 大脑图标缩小

**文件**：
- `app/src/main/java/me/rerere/rikkahub/ui/components/message/ChatMessageTools.kt`
- `app/src/main/java/me/rerere/rikkahub/ui/components/ui/ChainOfThought.kt`
- `app/src/main/java/me/rerere/rikkahub/ui/components/message/ChatMessage.kt`

**改动**：

1. `ChainOfThought.kt` `ControlledChainOfThoughtStep`（接口 + 实现 + `ChainOfThoughtStepContent`）新增可选参数 `belowLabel: (@Composable () -> Unit)? = null`：非空时在 label 行下方渲染，`padding(start = 32.dp)` 对齐展开内容的缩进。不改动现有调用方（默认 null）。

2. `ChatMessageTools.kt` `ChatMessageToolStep`：`isFailed && !renderer.hasSummary(context)` 的错误文案从 `extra` 槽移到 `belowLabel` 槽（样式不变：labelSmall + error 色 + maxLines=2 + Ellipsis）；extra 分支只剩 pending 审批按钮与 denied。

3. `ChatMessage.kt` `ChainOfThoughtHeaderRow`：外层 `Box(Modifier.size(20.dp))` → 16.dp，`Icon(Modifier.size(16.dp))` → 13.dp（略微缩小，保持盒/图标 3dp 差）。

**验收**：工具报错时错误文案显示在标题行下方独立一行，标题不再被挤压；聚合思考头的大脑图标变小。

---

## Verification（控制器执行，非任务内容）

- 每任务 reviewer 通过后本地 commit；全部任务完成后统一 push，`gh workflow run nightly-build-debug.yml --repo DevLintMar/rikkahub --ref master`，轮询 `gh run list` 核对 headSha 与 conclusion。
- 设备核验清单（写入交接文档，用户上手）：
  1. 概览标题带记忆标题（七个工具）；删除小字=内容且颜色与创建一致
  2. read_memory 详情信封；详情标题加粗+描述斜体
  3. edit_memory old_text 乱传 → 失败文案在标题下方、无堆栈类名
  4. delete_memory 乱传标题 → 失败
  5. 图标：读取=BookmarkCheck02、grep=FolderSearch、回退/恢复=Redo、大脑图标变小
  6. 夜间模式 + 6 locale
