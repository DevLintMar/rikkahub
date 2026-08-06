# 交接文档：记忆系统重写（Agora 式）完成 — 下一阶段入口

**日期**：2026-08-06
**目的**：上下文清理前的完整交接。新会话读本文档即可续接。master 已推送至 `29539e6`（+ 本文档），工作树干净；最后 CI 绿（run 31065287906，headSha 核对）。

---

## 0. 一句话概况

本阶段完成**记忆系统重写**（用户 5 项需求全部落地）：设置项迁移（参考历史→本地工具页、时间提醒→提示词页）、Agora 式记忆页（活跃记忆卡 + 已保存记忆标题/描述/内容 + 2 个 AI 修改开关）、5 个记忆工具（read/update_active/write 含显式 overwrite/edit 工作区式/delete）、按标题读取工具与展示、质量修复（工作区工具去 `_file` 后缀 + 工具报错折叠行直接显示错误）。采用 SDD（10 任务 + 终审 + 修复波），全程 CI 绿。master 从 `81cd510`（更早交接）经遮罩预热阶段推进到本阶段终点 `29539e6`。

---

## 1. 已完成工作（10 任务 + 终审修复波）

| # | 任务 | commit | CI run |
|---|------|--------|--------|
| 1 | 数据模型（AssistantMemory title/description/isActive + Assistant 2 开关） | 888647b | 31009755353 |
| 2 | 存储层（MemoryEntity 3 列 + Room v26 手动迁移 + DAO + Repository） | 989ed7f→ec68277 | 31011616720 |
| 3 | AI 工具重写（read/update_active/write/edit/delete）+ GenerationHandler 门槛注册 | c9be66c | 31013105863 |
| 4 | 提示词注入（活跃全量 + 已保存标题/描述）+ activeMemory 贯穿生成链路 | 7c702a8 | 31013992400 |
| 5 | AssistantDetailVM（activeMemory flow + 全字段 CRUD + 兼容方法删除） | 3a3013e | 31014805471 |
| 6 | 记忆页 UI 重做（活跃记忆卡 + 已保存记忆三字段 + 2 开关 + 12 串×6 locale） | 3f26214→4d4c8d5 | 31016879328 |
| 7 | 开关迁移（参考历史→本地工具页 AskUser 与 ScreenTime 之间；时间提醒→提示词页） | 6558122 | 31017667362 |
| 8 | 记忆五工具调用展示渲染器（MemoryToolsUIs.kt + 注册） | 9bca9fe | 31019437396 |
| 9 | 工作区工具改名（workspace_read/write/edit）+ 审批覆盖旧 key 重映射 | 3386a82 | 31020533352 |
| 10 | 工具报错展示修复（FAILED 接入 UI + 各渲染器错误摘要 + DefaultToolPreview 错误优先） | 8d358c2 | 31062918504 |
| 终审修复波 | F1 重复标题崩溃防护 + F2 编辑重名检查 + F3 错误双显修复 + F4 stale memory_tool + F5 未用 import | 29539e6 | 31065287906 |

**过程修复（fix loop）**：Task2 兼容 addMemory 空标题被 require 拦截（Critical→修）；Task6 Row 误用 verticalArrangement（CI 编译错→修）。计划文档同步修复：9a8b3aa / 22ac922 / 6f5887a / 2c34ece。

---

## 2. 关键设计决策（认知遗留）

- **数据模型**：`AssistantMemory(id, title, description, content, isActive)`；活跃记忆 = `is_active=1` 行（每作用域一条），作用域跟随 `useGlobalMemory`（per-assistant 或 `__global__`）。
- **Room v25→26 手动迁移**（Migration_25_26）：3 ALTER + `UPDATE ... SET title = substr(trim(content),1,40)` 回填，与运行时 `derivedTitle()` 规则一致。
- **工具门槛**：`read_memory` = enableMemory；`update_active_memory` = + enableEditActiveMemory；`write/edit/delete_memory` = + enableEditSavedMemories。无 list_memory（用户确认不做）。
- **注入格式**：`**Memories**` → Active memory 全量 → Saved memories 仅标题/描述列表 → read_memory 指令行（`GenerationPrompts.buildMemoryPrompt`）。
- **write_memory overwrite 必须显式**：未传 overwrite=true 且标题已存在 → error。
- **edit_memory 复用工作区 TextReplacers.replaceText**（exact → line_trimmed → block_anchor）。
- **工作区改名**：仅 read/write/edit 三个去 `_file`；`WorkspaceEntity.toolApprovalOverrides()` 读时旧→新 key 重映射（TOOL_NAME_LEGACY_MAP），历史审批设置不失效。
- **报错展示**：FAILED 折叠行 extra 分支条件 `isFailed && !renderer.hasSummary(context)`（避免与错误感知渲染器双显）；错误信封 `{type, error, message}` 由 GenerationHandler onFailure 统一产生。
- **崩溃防护**：记忆对话框确认按钮门控 = 标题非空 + 标题唯一；VM addMemory/updateMemory try/catch 兜底（重抛 CancellationException）。

---

## 3. 当前 git 状态

- 分支：`master`，HEAD = `29539e6`（+本文档），工作树干净
- 全部 commit 已推送，CI 全绿

---

## 4. 恢复地图

| 台账 | 路径 | 内容 |
|---|---|---|
| SDD 台账（本阶段） | `.superpowers/sdd/2026-08-05-memory-system-rewrite/progress.md`（git-ignored） | 10 任务 + 2 fix round + 终审 + 修复波的完整记录 |
| 设计稿 | `docs/superpowers/specs/2026-08-05-memory-system-rewrite-design.md` | 完整设计 |
| 计划 | `docs/superpowers/plans/2026-08-05-memory-system-rewrite.md` | 10 任务含代码（多次修复） |
| 上一交接（遮罩/预热） | `docs/superpowers/handoffs/2026-08-05-chat-cover-prewarm-complete-next-phase.md` | 更早历史 |

---

## 5. 待确认 / 挂起项（按优先级）

### ① 设备核验（最高优先，唯一需用户上手）
装最新 debug APK 验证（计划文末清单）：
1. **记忆页**：活跃记忆卡始终显示/可编辑；已保存记忆标题/描述/内容增删改；两个新开关随 enableMemory 使能；重复标题时确认按钮禁用；旧记忆迁移出标题。
2. **开关迁移**：本地工具页 AskUser 与 ScreenTime 之间有"参考历史聊天记录"；提示词页"独立对话提示词注入"下方有"时间提醒"+"总是插入"。
3. **AI 记忆工具**：write/read/edit/delete/update_active 全链路；未显式 overwrite 报覆盖错并折叠行显示错误；系统消息含活跃全量+已保存标题/描述。
4. **工作区工具**：改名后正常；报错折叠行显示错误消息；历史审批覆盖生效。
5. 夜间模式、6 locale 串正确。

### ② 遗留 Low（终审 re-review 记录，不阻塞）
- error 信封缺 message 时失败渲染空白（需畸形信封，极罕见）
- 失败 write/edit 的摘要仍显示待写内容（常见路径有 message，红字正常）
- 派生标题碰撞可绕过 UI 门控（需遗留空白标题记忆；VM 兜底已防崩溃）

### ③ 历史挂起（更早交接遗留）
- 乱召回（语义搜索 bug，需 rawTop logs）
- Task 12 ripgrep artifact 流水线
- 更新检查恢复点（UpdateChecker.kt 删 `return@flow`）
- ShellToolUI.Preview 错误信封仍显示 "exit code ?"（Summary 已修，详情页未修——可选小修）
- Export.kt 未特判 5 个新记忆工具名（导出显示泛化文案，可选）

---

## 6. 技术约束 / 惯例（必须遵守）

- **本机无编译器**：不运行 gradle。静态编写 + review，编译验证全靠 CI。
- **CI 判定铁律**：`gh run list --workflow nightly-build-debug.yml --repo DevLintMar/rikkahub --limit 1 --json databaseId,headSha,status,conclusion` 权威判定 + **核对 headSha**。CI 流程：先 push 再 `gh workflow run nightly-build-debug.yml --repo DevLintMar/rikkahub --ref master`。
- **CI 不跑单测**（只 assembleDebug）。
- **runCatching 不能包 suspend**；包 suspend 用 try/catch + 重抛 `CancellationException`。
- 字符串双写 en+zh；ja/ko-rKR/ru/zh-rTW 有对应记忆 key 时一并补。
- 文件删除走 `~/.claude/scripts/trash.sh`。
- force-push 需用户明确要求。

---

## 7. 停靠点

- **已完成**：记忆系统重写 10 任务 + 终审修复波，master HEAD `29539e6`，CI 全绿。
- **待确认**：设备核验（§5-①）。
- **恢复动作**：读本文档 §4/§5，先让用户装包核验。
