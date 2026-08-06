# 交接文档：记忆系统迭代（活跃记忆多条化 + 工具展示增强）— 下一阶段入口

**日期**：2026-08-06
**目的**：上下文清理前的完整交接。新会话读本文档即可续接。master 已推送至 `c980f0d`，工作树干净；最后 CI 绿（run 31072649635，headSha 核对）。

---

## 0. 一句话概况

本阶段（接上一交接 `38d878d`）完成用户新一轮记忆系统迭代（5 点需求）：**活跃记忆从单槽改为多条**（记忆页分区、标题/描述/内容、AI 工具 create/edit/delete_active_memory）、**注入格式**改为活跃传标题+描述+全内容、已保存仅标题+描述（并修复空行问题）、**图标调整**（grep→FolderSearch、编辑→PencilEdit01、读取→BookCheck）、**删除概览显示标题**、**详情页按钮**（新增→删除本条；编辑→删除本条+回退；删除→恢复）。SDD 4 任务 + 终审修复波，全程 CI 绿。master `38d878d` → `c980f0d`，共 7 个 commit。

---

## 1. 已完成工作（commit 链）

| commit | 内容 | CI run |
|---|---|---|
| 1712332 | docs: 迭代计划（4 任务） | — |
| 220d656 | Task 1: 数据层活跃记忆多条化（DAO 列表查询 + Repository isActive 参数化；删除单槽 API/ActiveMemoryMode） | 与 Task 3 合并验证 |
| c24be2a | Task 2: AI 层（create/edit/delete_active_memory 三工具替代 update_active_memory；信封加 scope_id/previous_content/content+description） | 同上 |
| f79f505 | Task 3: VM activeMemories flow + 记忆页活跃记忆分区（多条增删改 + id 小字） | 31070342517 ✅ |
| 80d586b | Task 4: 渲染器重做（8 渲染器：saved read/write/edit/delete + active create/edit/delete；详情按钮；图标） | 31070870160 ✅ |
| 1d9c080 | 终审修复波：updateMemory 按命名空间校验标题冲突 + 详情页按钮协程 try/catch+toast + 死串引用 | 31071694826 ✅ |
| c980f0d | 注入格式调整：活跃记忆传标题+描述+全内容（AI 按标题定位） | 31072649635 ✅ |

> Task 1-3 是编译不可分割组（中间态不编译），本地提交后 Task 3 统一推送验证——这是本阶段的特殊执行模式，SDD 台账有记录。

---

## 2. 关键设计决策（认知遗留）

- **活跃记忆多条**：与已保存记忆同表（`is_active=1`），同 CRUD 路径，Repository 方法全部 `isActive: Boolean = false` 参数化；唯一性检查按命名空间路由（saved/active 两区可同名）。
- **AI 工具**：`create_active_memory`（write 同构，含显式 overwrite）/`edit_active_memory`（edit 同构，TextReplacers）/`delete_active_memory`；`update_active_memory` 与 `ActiveMemoryMode` 已删除。saved 四工具（read/write/edit/delete）不变。
- **注入格式**（`GenerationPrompts.buildMemoryPrompt`）：
  - 活跃：`- 标题 — 描述` + 下一行缩进全内容（标题空→内容前 40 字兜底）
  - 已保存：仅 `- 标题 — 描述`
  - 尾部指令说明两者差异 + read_memory 用法
- **信封扩展**：edit_* 加 `previous_content`（先查后改）；delete_* 加 `content`/`description`/`scope_id`（先查后删，供恢复）；create/write/edit 加 `scope_id`。pre-read 失败时字段缺省（UI 按钮按存在性显隐）。
- **详情页按钮**（MemoryToolsUIs.kt `MemoryDetailActions`）：新增/编辑→删除本条（确认对话框）；编辑→回退（updateMemory 恢复 previous_content）；删除→恢复（addMemory overwrite=true 按 isActive）。全部 try/catch + LocalToaster 防崩溃。
- **图标**：read=Lucide.BookCheck；grep=Lucide.FolderSearch（WorkspaceToolUIs）；write/create=QuillWrite01；edit=PencilEdit01；delete=Eraser。

---

## 3. 当前 git 状态

- 分支：`master`，HEAD = `c980f0d`，工作树干净
- 全部 commit 已推送，CI 全绿

---

## 4. 恢复地图

| 台账 | 路径 | 内容 |
|---|---|---|
| SDD 台账（本阶段） | `.superpowers/sdd/2026-08-06-memory-active-multi-entry/progress.md`（git-ignored） | 4 任务 + 终审 + 修复波记录 |
| 计划 | `docs/superpowers/plans/2026-08-06-memory-active-multi-entry.md` | 4 任务含代码 |
| 上一交接（记忆系统重写） | `docs/superpowers/handoffs/2026-08-06-memory-system-rewrite-complete.md` | 记忆系统初版重写（10 任务） |
| 更早交接（遮罩/预热） | `docs/superpowers/handoffs/2026-08-05-chat-cover-prewarm-complete-next-phase.md` | 更早历史 |

---

## 5. 待确认 / 挂起项（按优先级）

### ① 设备核验（最高优先，唯一需用户上手）
装最新 debug APK 验证：
1. 记忆页：活跃记忆分区多条增删改、标题为主 + id 小字；saved/active 两区可同名不冲突
2. 对话中让 AI create/edit/delete 活跃记忆（AI 能按注入的标题定位）；系统消息注入格式正确（活跃标题+描述+内容，无空行）
3. 工具调用：删除折叠行下方显示标题；详情页按钮（删除本条/回退/恢复）功能正常、失败 toast 不崩溃
4. 图标：grep=FolderSearch、读取=BookCheck、编辑=笔
5. 夜间模式、6 locale 串正确

### ② 遗留 Minor（终审记录，不阻塞）
- 旧单槽活跃记忆迁移为无标题条目（内容前 40 字兜底）；可选：一次性迁移补标题
- 回退编辑只恢复 content，不回退 title/description 改名
- ShellToolUI.Preview 错误信封仍显示 "exit code ?"（Summary 已修）
- Export.kt 未特判记忆工具名（导出泛化文案）

### ③ 历史挂起（更早交接遗留）
- 乱召回（语义搜索 bug，需 rawTop logs）
- Task 12 ripgrep artifact 流水线
- 更新检查恢复点（UpdateChecker.kt 删 `return@flow`）

---

## 6. 技术约束 / 惯例（必须遵守）

- **本机无编译器**：不运行 gradle。静态编写 + review，编译验证全靠 CI。
- **CI 判定铁律**：`gh run list --workflow nightly-build-debug.yml --repo DevLintMar/rikkahub --limit 1 --json databaseId,headSha,status,conclusion` 权威判定 + **核对 headSha**。CI 流程：先 push 再 `gh workflow run nightly-build-debug.yml --repo DevLintMar/rikkahub --ref master`。
- **CI 不跑单测**（只 assembleDebug）。
- **runCatching 不能包 suspend**；包 suspend 用 try/catch + 重抛 `CancellationException`。
- **编译不可分割组**：跨任务破坏编译时，本地提交、最后一个任务统一推送验证（本阶段 Task 1-3 模式）。
- 字符串双写 en+zh；ja/ko-rKR/ru/zh-rTW 有对应 key 时一并补。
- 图标名（Lucide/HugeIcons）以 CI 编译为准。
- 文件删除走 `~/.claude/scripts/trash.sh`。
- force-push 需用户明确要求。

---

## 7. 停靠点

- **已完成**：记忆系统迭代 4 任务 + 终审修复波 + 注入格式调整，master HEAD `c980f0d`，CI 全绿。
- **待确认**：设备核验（§5-①）。
- **恢复动作**：读本文档 §4/§5，先让用户装包核验。
