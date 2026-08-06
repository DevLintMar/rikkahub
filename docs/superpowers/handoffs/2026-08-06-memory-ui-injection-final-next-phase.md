# 交接文档：记忆工具 UI 迭代 + 注入格式定型 — 下一阶段入口

**日期**：2026-08-06
**目的**：上下文清理前的完整交接。新会话读本文档即可续接。master 已推送至 `2cf0396`（功能终点 `d67e888`），工作树干净；全部 CI 绿（headSha 均核对）。

---

## 0. 一句话概况

接上一交接 `dcc9e4e`（活跃记忆多条化）。本阶段完成用户两轮反馈共 13 项改动：**记忆工具 UI 迭代 9 点**（read 自定义信封、概览标题带记忆标题、删除小字换内容、详情标题加粗/描述斜体、图标全换 HugeIcons、edit/delete 失败报错、错误消息去堆栈、报错显示下移、大脑图标缩小）+ **追加 4 项**（工具描述纠正、流式传参占位符修复、注入格式两次迭代最终定型为 `<memories>` 标签 + JSON）。SDD 3 任务 + 终审（opus）修复波，追加项小改直修。全程 CI 绿。master `dcc9e4e` → `2cf0396`。

---

## 1. 已完成工作（commit 链）

### SDD 三任务 + 终审修复波（9 点需求）

| commit | 内容 |
|---|---|
| e64141b | docs: 计划（`docs/superpowers/plans/2026-08-06-memory-tools-ui-iteration.md`） |
| 9a1295c | Task 1: `toolErrorMessage()` 错误信封去堆栈类名；`buildDeleteTool` 标题不存在时报错 |
| ec000db | Task 2: 七工具概览标题带记忆标题（6 locale 参数化串）+ read 语义化 Preview（BookmarkCheck02）+ 详情共用标题加粗/描述斜体 + 删除小字换内容 + 回退/恢复换 Redo 图标 |
| b861d75 | Task 3: `belowLabel` 槽；报错从 extra（右侧）移到标题下方；大脑图标盒 20→16/图标 16→13 |
| f0a0586 | 终审修复波：F1 belowLabel 加 `!isDenied`；F2 legacy memory_tool + Export.kt 传 fallback 标题；F3 delete 渲染器 hasSummary 改 content 检查 |

### 追加修复（用户反馈，小改直修）

| commit | 内容 | CI |
|---|---|---|
| 6462125 | create_active_memory 工具描述"id + content"→"标题+描述+全内容" | 31081800256 ✅ |
| 2e437bd | 注入序号分隔（后被 d67e888 替代） | — |
| 8d0cb53 | memoryToolTitle 兜底空参（流式期不再显示字面 %1$s）+ 六 locale 去冒号前空格 | 31083315002 ✅ |
| d67e888 | **注入最终形态**：`<memories>` 标签 + JSON 数组 | 31084784378 ✅ |

> 主功能终点 392b901（含首批交接文档）CI run 31078297074 ✅。

---

## 2. 关键设计决策（认知遗留）

- **注入最终形态**（`GenerationPrompts.buildMemoryPrompt`）：
  ```
  <memories>
  Active memories (injected with full content):
  [{"title":"...","description":"...","content":"..."}]
  Saved memories (title and description only; use read_memory with a title ...):
  [{"title":"...","description":"..."}]
  </memories>
  ```
  JsonInstantPretty 输出；空描述省略；标题空→内容前 40 字兜底；两区全空不注入。历史演进：`- 标题` 列表 → 序号 `1. 标题`（被替代）→ JSON。
- **错误信封**：`{type, error:"error", message:"<原因>"}` + `ToolState.FAILED`；message 只含 `throwable.message`（`toolErrorMessage()`，无堆栈类名）。删除不存在标题现在抛错（原静默 success:false）。
- **belowLabel 槽**：`ControlledChainOfThoughtStep` 参数（extra 后、onClick 前，默认 null），渲染在标题行下方 `padding(start=32.dp)`；失败且无摘要且非拒绝态时显示错误文案。非受控版本未加。
- **概览标题**：`memoryToolTitle` 解析链 信封 title → 入参 title → 内容前 40 字 → 空参兜底（`stringResource(id, "")`，勿改回零参——零参会渲染字面 %1$s）。
- **READ 详情**：`MemoryDetailKind.READ` 无动作按钮（canDelete 显式排除 READ）。
- **图标全集**：read=BookmarkCheck02、write/create=QuillWrite01、edit=PencilEdit01、delete=Eraser、详情删除=Delete01、回退/恢复=Redo、grep=FolderSearch；全 HugeIcons，记忆渲染器无 Lucide。大脑图标=ChainOfThoughtHeaderRow 盒 16/图标 13。
- **流式传参期标题**（核查结论）：唯一泄露点是 memoryToolTitle（已修）；workspace 六件有 default 串；search_web/conversation_search/calendar_create 传 `?: ""` 兜底（尾随分隔符，纯外观）。

## 3. git 状态

- 分支 `master`，HEAD = `2cf0396`，工作树干净
- CI 判定：`gh run list --workflow nightly-build-debug.yml --repo DevLintMar/rikkahub --limit 1 --json databaseId,headSha,status,conclusion`，最近 run 31084784378 ✅（headSha d67e888 核对一致）

## 4. 恢复地图

| 台账 | 路径 |
|---|---|
| SDD 台账（本阶段） | `.superpowers/sdd/2026-08-06-memory-tools-ui-iteration/progress.md`（git-ignored，含全部 review 记录） |
| 计划 | `docs/superpowers/plans/2026-08-06-memory-tools-ui-iteration.md`、`2026-08-06-memory-injection-numbering.md`（后者为直修记录） |
| 本阶段详细交接 | `docs/superpowers/handoffs/2026-08-06-memory-tools-ui-iteration-complete.md` |
| 更早交接 | 同目录：`2026-08-06-memory-iteration-active-multi-complete.md`（活跃记忆多条化）、`2026-08-06-memory-system-rewrite-complete.md`（初版重写 10 任务）、`2026-08-05-chat-cover-prewarm-complete-next-phase.md` |

## 5. 待确认 / 挂起项（按优先级）

### ① 设备核验（最高优先，唯一需用户上手）

装最新 debug APK 验证：
1. **注入**：`<memories>` JSON 块，多条记忆（含 `-` 开头内容、多行内容）边界清晰；活跃带 content、已保存仅 title+description
2. **AI 工具**：按标题 read/edit/delete/overwrite 正常（注入 title 可定位）；edit old_text 乱传 → 报错在标题下方、无堆栈；delete 乱传标题 → 报错
3. **拒绝态**：拒绝工具只显示拒绝文案，不叠加"工具执行失败"
4. **流式**：调用记忆工具传参过程中标题显示"xx记忆："，无 %1$s
5. **UI**：概览"xx记忆：标题"；删除小字=内容（onPrimaryContainer）；read 详情信封；详情标题加粗+描述斜体；回退/恢复=Redo 图标；大脑图标已缩小；READ 详情无按钮
6. **兼容**：旧对话 legacy memory_tool（edit/delete）标题正常（无 %1$s）；导出同理
7. 夜间模式 + 6 locale

### ② 遗留 Minor（不阻塞）

- search_web/conversation_search/calendar_create 流式期标题尾随空格/冒号（纯外观）
- `take(40)` UTF-16 边界极端 emoji 可断字形（外观）
- 回退编辑只恢复 content 不回退改名；ShellToolUI.Preview 错误信封 "exit code ?"（Summary 已修）

### ③ 历史挂起（更早遗留）

- 乱召回（语义搜索 bug，需 rawTop logs）
- Task 12 ripgrep artifact 流水线
- 更新检查恢复点（UpdateChecker.kt 删 `return@flow`）
- 遮罩+预热计划曾存在于 plan mode（atomic-frolicking-hare.md）——若用户提及"切换对话转圈遮罩"，那是已调研未实现的旧需求

## 6. 技术约束 / 惯例（必须遵守）

- **本机无编译器**：不运行 gradle。静态编写 + review，编译验证全靠 CI。
- **CI 判定铁律**：`gh run list ...` 权威判定 + **核对 headSha**。流程：先 push 再 `gh workflow run nightly-build-debug.yml --repo DevLintMar/rikkahub --ref master`。
- **runCatching 不能包 suspend**；suspend 用 try/catch + 重抛 `CancellationException`。
- **SDD**（用户指定开发模式）：fresh implementer + task review + 终审（最强大模型）+ 修复波 + scoped re-review；小改可直修但需 CI 验证。
- 字符串双写 en+zh；六 locale（values/-zh/-ja/-ko-rKR/-ru/-zh-rTW）占位符串全写。
- HugeIcons 图标名以库内真实存在为准（find-hugeicons 技能；库无 BookCheck/BookOpenCheck）。
- 中文 conventional commit；文件删除走 `~/.claude/scripts/trash.sh`；force-push 需用户明确要求。

## 7. 停靠点

- **已完成**：13 项改动全部落地并 CI 绿，master HEAD `2cf0396`。
- **待确认**：设备核验（§5-①）。
- **恢复动作**：读本文档 §4/§5；下一阶段开始前先让用户装包核验本轮改动。
