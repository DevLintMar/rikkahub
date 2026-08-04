# 交接文档：工具展示修复+ask_user/更新禁用/官方信息移除/删除键+conversation_search重设计+对话工具分页 — 下一阶段入口

**日期**：2026-08-04
**目的**：上下文清理前的完整交接。新会话先读本文档 + 台账（恢复地图），即可无缝续接。当前 master 已推送至 `81355bc`，工作树干净；最后 CI（`gh run list` 权威确认）绿。

---

## 0. 一句话概况

本阶段（自上一交接 `c466756` 起）完成：**6 项工具展示质量修复**（参数为空隐藏/子代理标题/内置工具 error 详情无大开关/JSON 着色上限/ask_user 文本/web_search 概述恢复）、**ask_user 显示修正**（loading 三个点→"正在询问用户..."，等待回答→"询问X个问题"）、**暂时禁用更新检查**、**移除官方渠道/赞助/标注信息**（赞助弹窗/捐赠页/SponsorAPI/文档链接/分享文案官网/provider 措辞/导出与 Mermaid 水印）、**时间提醒"总是插入"选项**、**设置页恢复"使用文档"/"赞助"按钮（点击无反应）**、**MCP/模式注入/世界书删除键（编辑界面红色删除+滑动删除均二次确认）**、**conversation_search 重设计**（per-message+[brackets]着重+索引+date+offset/has_more）、**对话工具分页优化**（recent_chats offset/has_more、read_conversation 精读建议）。

---

## 1. 已完成工作（全部在 master，推至 `81355bc`，工作树干净）

### A. 工具展示质量修复（6 项，计划 `d9744c5`）

| 内容 | 提交 |
|---|---|
| 参数为空隐藏"参数"分区（ToolJsonBody/DefaultToolPreview） | `c220baf` |
| 子代理工具标题→"运行子代理"/"运行子代理（后台）" | `03d031e` |
| 内置工具 error/未执行详情不显示整页 JSON 大开关（hasSemanticDetail 逐渲染器，13+6 个 override） | `8c8e7f7` |
| 原始 JSON 文本视图语法高亮上限提升（HighlightText/CodeBlock 参数化 `maxCodeLength`，工具视图 512KB） | `aba83dc` |
| ask_user 执行中文本（后修正，见 B） | `4abe2f5` |
| web_search 概述(answer)恢复 + 去首行 "Search: query" + 渲染格式还原（primaryContainer 卡+bodySmall+16dp，对照 `596f08d~1`） | `23f4127` + `d633508` |

### B. ask_user 显示 + 更新禁用 + 官方信息移除 + 时间提醒（计划 `dd72f1f`）

| 内容 | 提交 |
|---|---|
| ask_user 修正：loading（三个点）→"正在询问用户..."；等待回答→恢复"询问X个问题" | `7c8c572` |
| 暂时禁用更新检查/自动更新（`UpdateChecker.checkUpdate()` 加 `return@flow` 短路，**删该行即恢复**） | `bf34162` |
| 移除官方/赞助/标注信息：赞助弹窗、捐赠页+SponsorAPI+Sponsor 模型+ko-fi/afdian 图标、设置页文档链接(docs.rikka-ai.com)、分享文案官网行、provider 描述"官方/赞助"措辞、13 个死串×6 locale（trash.sh 删文件） | `384bc17` |
| 导出文件头部 + Mermaid 导出 PNG 的 rikka-ai.com 标注 | `dc59601` |
| 时间提醒"总是插入"选项（`Assistant.timeReminderAlwaysInsert` + Transformer 忽略间隔阈值 + 设置开关） | `79e5b40` |

### C. 设置页无反应按钮 + 文案

| 内容 | 提交 |
|---|---|
| 设置页恢复"使用文档"/"赞助"按钮（点击无反应）+ 分享按钮改无反应（onClick={}，死局部/import 清理，删 share_text/no_share_app 死串） | `9de83a5` |
| 时间提醒"总是插入"→"总是插入时间提醒" | `0c4e479` |

### D. 删除键 + conversation_search 重设计 + Minor（计划 `b2ac3b8`）

| 内容 | 提交 |
|---|---|
| MCP/模式注入/世界书编辑界面左下角红色删除键（二次确认，仅编辑已存在项显示） | `d9415dd` + `9ea13ec`(作用域修复) |
| conversation_search 重设计：per-message+index+[brackets]着重标记，删旧 window 方法，UI 渲染器适配 | `e79c710` |
| setting_page_donate_desc 撇号转义 `author\'s`（修 aapt，9de83a5 起一直红）+ 删 tool_ui_match_count 死串 | `e2520e4` |
| DefaultToolPreview result json 解析加 remember | `96e08b8` |
| MCP 删除确认 AlertDialog 移入 EditStateContent 作用域（修 d9415dd 的 unresolved 编译错误） | `9ea13ec` |
| MCP/模式注入/世界书**滑动删除按钮**加二次确认弹窗 | `b0d73cc` |

### E. 对话工具分页增强（3 项）

| 内容 | 提交 |
|---|---|
| recent_chats 加 offset 分页；conversation_search 去 text/score；read_conversation 描述建议精读不整读 | `7635c10` |
| recent_chats 加 has_more（offset+limit<total，用新 count 查询）；conversation_search 加 date(按日期新→旧)+offset+has_more | `6b9b39c` |
| 移除无效 `import kotlinx.datetime.date`（LocalDateTime.date 是成员属性）——修复 `6b9b39c` 的编译红 | `81355bc` |

---

## 2. 当前 git 状态

- 分支：`master`，HEAD = `81355bc`，已推送，工作树干净
- 提交链：`c466756`（上阶段交接）→ 30 commits（见 §1）→ `81355bc`
- CI：最后 run 30887281309（`81355bc`）= **success**（`gh run list` 权威确认）

---

## 3. 恢复地图（计划 + 台账）

| 台账 | 路径 | 内容 |
|---|---|---|
| 计划（6 项质量修复） | `docs/superpowers/plans/2026-08-04-tool-display-quality-fixes.md`（源 `d9744c5`） | A 组 6 项全代码 |
| 计划（ask_user/更新禁用/官方移除/时间提醒） | `docs/superpowers/plans/2026-08-04-update-disable-official-removal-time-reminder.md` | B 组全代码 |
| 计划（文案/删除键/conversation_search/Minor） | `docs/superpowers/plans/2026-08-04-copy-delete-buttons-conversation-search-minors.md` | C/D 组全代码 |
| 上一阶段交接 | `docs/superpowers/handoffs/2026-08-03-tool-tab-regression-and-collapse-refinement-complete-next-phase.md` | 更早历史 |
| SDD 台账 | `.superpowers/sdd/2026-08-03-tool-tab-regression-and-collapse-refinement/progress.md`（git-ignored） | 约 30 项 deferred minors 清单 |

> 注：本阶段多数改动直接 master 执行，无 SDD 台账；计划文档即恢复地图。

---

## 4. 待确认 / 挂起项（按优先级）

### ① 设备级视觉/行为核验（唯一需用户上手，涉及大量 UI 改动）
- ask_user 三态：执行中（三个点）"正在询问用户..."、等待回答"询问X个问题"、已回答单问题显示问题
- 聊天抽屉无更新卡片；设置页无赞助弹窗/捐赠项/文档项；关于页链接保留
- 分享文案与 RikkaHub provider 描述无官网/赞助字样；导出文件与 Mermaid PNG 无 rikka-ai.com
- 助手详情：时间提醒开关下"总是插入时间提醒"选项
- MCP/模式注入/世界书：编辑界面左下角红色删除 + 滑动删除均二次确认；创建态无删除键
- conversation_search：结果单条匹配消息 + [brackets] + `#索引` + date 标签；**日期新→旧排序是否符合预期**（替代了原相关性排序，如不合适可回退）
- 工具详情：内置工具 error 详情无整页 JSON 大开关；参数为空无"参数"分区；原始 JSON 着色

### ② 历史最高优先：乱召回（语义搜索 bug）
- 需用户提供 rawTop logs。入口：`ConversationRepository.search` / `SemanticIndexManager.search`。

### ③ Task 12（workspace_grep → 原生 ripgrep）
- 阻塞于 artifact。预留：`docs/superpowers/plans/2026-08-02-tool-call-ripgrep-task12-reserved.md`。

### ④ 原生 ripgrep artifact 流水线
- 交接：`docs/superpowers/handoffs/2026-08-02-native-ripgrep-artifact-pipeline.md`。

### ⑤ 更新检查恢复点
- `UpdateChecker.kt` 的 `checkUpdate()` 有 `return@flow` 短路 + 注释，**删除该行即恢复**。

### ⑥ 台账 deferred minors（约 30 项，全部 Acceptable）
- 见 `.superpowers/sdd/2026-08-03-tool-tab-regression-and-collapse-refinement/progress.md`。本阶段已消化其中：result json remember、JsonTreeView 硬化（用户要求保持 2000 上限不动）、sizeBytes toInt（已确认全链路 Long 无溢出，无需改）。

---

## 5. 技术约束 / 惯例（必须遵守）

- **本机无编译器**：不运行 gradle。静态编写 + review；编译验证全靠 CI。
- **CI 判定铁律（本阶段踩坑教训）**：`gh run list --workflow nightly-build-debug.yml --repo DevLintMar/rikkahub --limit 1 --json databaseId,headSha,status,conclusion` 权威判定，且**核对 headSha == 目标 commit**。`gh run watch --exit-status` 可能因 `gh run list` 抓到旧 run 而误报绿——本阶段 9de83a5/0c4e479/d9415dd/6b9b39c 的 CI 曾被误判为绿，实际红。
- CI 流程：先 `git push origin master` 再 `gh workflow run nightly-build-debug.yml --repo DevLintMar/rikkahub --ref master`（push 在前）。
- **字符串撇号必须转义**（`author\'s`）——aapt 对未转义 `'` 报 "Invalid unicode escape sequence"（本阶段红 3 个 commit）。
- 字符串双写 en+zh（ja/ko/ru/zh-rTW 有 key 时一并改/删）；删串先 grep 零引用。
- 文件删除走 `~/.claude/scripts/trash.sh`（回收站），禁止 rm。
- `EditStateContent { config, updateValue -> }` 是 lambda 作用域——内部状态/弹窗须放 lambda 内，否则 unresolved（本阶段 `9ea13ec` 修复）。
- `kotlinx.datetime.LocalDateTime.date` 是**成员属性**，无需 `import kotlinx.datetime.date`（会报 Unresolved）。
- 图标名（HugeIcons）以 CI 编译为准；本项目只用已验证图标。
- JitPack `sqlite-android:-SNAPSHOT`/JBR21 偶发 flake——重跑即绿，勿判代码问题；真实编译错误看 `--log-failed`。

---

## 6. 关键架构决策（本阶段遗留给后续的认知）

- **工具详情 JSON 大开关判定**：`isBuiltIn && hasSemanticDetail(context)`（19 个渲染器逐一定义 fallback 一致）；error/未执行详情只显示默认 JSON 展开，无冗余大开关。
- **conversation_search 新形态**：per-message 结果 `{conversation_id, title, index, role, snippet([brackets]着重), date}`，按日期新→旧排序，`offset`/`has_more` 分页；`index` 与 read_conversation `offset` 对齐（都基于 `currentMessages.filter{USER||ASSISTANT}`）；AI 用 read_conversation 精读所需窗口。
- **recent_chats**：`has_more = offset + limit < total`（total 用独立 `countConversationsOfAssistant` 查询，不加载消息节点）。
- **删除键 UX**：编辑界面左下角红色删除（仅编辑已存在项，创建态隐藏）+ 滑动删除均弹 `delete_confirm_message` 二次确认；通用字符串 `delete`/`delete_confirm_message` 可复用。
- **更新检查**：`checkUpdate()` 短路（不发起网络请求），UpdateCard 保持 Loading 不渲染。
- **官方/赞助移除**：设置页关于区保留"关于/请求日志/使用文档(无反应)/赞助(无反应)/分享(无反应)"；关于页官网/GitHub/许可证链接按用户要求保留。

---

## 7. 参考文件索引

| 文件 | 用途 |
|---|---|
| `docs/superpowers/plans/2026-08-04-tool-display-quality-fixes.md` | 6 项质量修复计划 |
| `docs/superpowers/plans/2026-08-04-update-disable-official-removal-time-reminder.md` | ask_user/更新禁用/官方移除/时间提醒计划 |
| `docs/superpowers/plans/2026-08-04-copy-delete-buttons-conversation-search-minors.md` | 文案/删除键/conversation_search/Minor 计划 |
| 关键新/改文件 | `ConversationTools.kt`（对话工具）/ `ConversationRepository.kt`（searchConversationMessages/ConversationSearchPage/count）/ `ConversationDAO.kt`（OFFSET+COUNT）/ `ChatMessageTools.kt`（ask_user loading）/ `ToolUI.kt`（hasSemanticDetail+remember）/ `SettingMcpPage.kt`+`PromptPage.kt`（删除键+二次确认）/ `UpdateChecker.kt`（短路）/ `ToolDetailCommon.kt`（TOOL_RAW_JSON_MAX_CODE_LENGTH）/ `BuiltinToolUIs.kt`（hasSemanticDetail 13 处+ConversationSearchToolUI date） |
| 历史参考 | `596f08d~1`（web_search answer 原始渲染）/ `244ce35`（最早 conversation_search 含 date） |

---

## 8. 下一阶段建议

1. **设备级核验**（§4-①），特别是 conversation_search 日期排序观感、删除键二次确认、ask_user 三态。
2. **乱召回**：需用户 rawTop logs；若用户愿意，优先排。
3. **Task 12 / ripgrep artifact**：可并行开新代理执行 artifact 流水线。
4. 台账 deferred minors 可按需合并消化（§4-⑥）。
5. 若用户对 conversation_search 相关性→日期排序有异议，回退点：`6b9b39c` 的 `sortedByDescending { it.date }` 一行。

---

## 9. 停靠点

- **已完成**：30 commits（`c466756`→`81355bc`），覆盖工具展示修复、ask_user 修正、更新禁用、官方信息移除、时间提醒、删除键、conversation_search 重设计、对话工具分页。master 推送至 `81355bc`，工作树干净。
- **待确认**：设备级核验（§4-① 清单）；conversation_search 日期排序是否符合预期。
- **恢复动作**：读本文档 §3 计划 + §4 待确认/挂起项，与用户确认下一阶段入口。
