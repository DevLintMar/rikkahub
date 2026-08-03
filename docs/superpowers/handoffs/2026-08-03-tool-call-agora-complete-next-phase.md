# 交接文档：RikkaHub 工具调用 Agora 化（Plan 1/2/3 完成）— 下一阶段入口

**日期**：2026-08-03
**目的**：上下文清理前的完整交接。新会话先读本文档 + 三个 SDD 台账（恢复地图），即可无缝续接。当前 master 全部绿、已推送。

---

## 0. 一句话概况

RikkaHub（Android Kotlin/Compose LLM 客户端）的"工具调用"已按 Agora 参考**三层全部对齐**：模型侧（Plan 1：信封/流式/截断/read 分段/glob+grep 工具）→ 展示层（Plan 2：分组时间线/聚合折叠头/列表行/5 渲染器回归修复）→ 详情视图（Plan 3：11 工具语义化 Preview + JsonTreeView 树形兜底 + 错误守卫）。三个 Plan 全部完成、CI 绿、master 已推送。下一步从"乱召回"（语义搜索 bug）或"收尾候选"进入。

---

## 1. 已完成工作（全部在 master，CI 绿）

### Plan 1 — 模型侧（`docs/superpowers/plans/2026-08-02-tool-call-agora-style-plan1-model-side.md`）
- 工具结果套 **`{type, ...}` camelCase 信封**（use_skill/ask_user/MCP 除外）+ 错误契约（紧凑信封、无 Java 堆栈给模型）
- **全量流式化**：`Tool.executeFlow`（Tool.kt），`GenerationHandler.kt` collect + 错误信封 + 100KB 安全网
- `UIMessagePart.Tool` 加 `toolState`（CALLING/RUNNING/SUCCEEDED/EMPTY/FAILED/STOPPED）+ `@Transient liveOutput`
- `workspace_read_file` 加 offset/limit + totalChars/hasMore 分段
- **新增 `workspace_glob`/`workspace_grep` 工具**（照 Agora file_glob/file_grep）
- 灯泡图标修复（ReasoningPicker 强制 onSurface）

### Plan 2 — 展示层（`docs/superpowers/plans/2026-08-02-tool-call-agora-style-plan2-display.md`）
- `ToolPresentation.kt`：字面工具名→ToolKind + 信封→结构化对象 + `toolSummary` 一行概览
- `thinkingAggregate()` 聚合头计算（思考时长+工具数）
- `ChainOfThought` 加 `header` 聚合模式（AiBrain02 + "思考X秒·调用X个工具" + 右箭头）
- `ChatMessageToolStep` 改聚合列表行（图标+一行概览+点击进详情 BottomSheet）
- 5 渲染器回归修复：ScrapeWebPreview / SearchWeb answer 卡 / RecentChats / ConversationSearch / **TTS Preview 重播**
- 聚合头执行中状态（"正在调用工具…" + 运行中工具名）
- 顺手 minor：read_file 描述 offset/limit、glob `**` 语义、type 规则定案
- **字符串双写约定**（用户拍板）：英文进 `values/`、中文进 `values-zh/`（ja/ko/ru 不新增，回退英文）

### Plan 3 — 详情视图（`docs/superpowers/plans/2026-08-02-tool-detail-views-agora-style-plan3.md`）
- 共享组件 `ToolDetailCommon.kt`（ToolDetailContainer/ToolPill/ToolTerminalOutput/formatFileSize）
- **`GlobToolUI`/`GrepToolUI`**（索引文件列表 / 按路径分组行号匹配）——补 Plan 1 Task 11 遗漏的注册
- 9 个既有渲染器补 Preview：recent_chats / conversation_search / get_screen_time / calendar_query / calendar_create / get_time_info / clipboard_tool / use_skill / memory_tool
- **`JsonTreeView`**（Agora `JsonNodeView` 对齐：键 chips + 内联值 + 长字符串单行 + 嵌套缩进 + 可选中）替换 `DefaultToolPreview` 的整块 JSON——MCP/未注册工具兜底
- 所有新 Preview 统一守卫**通用异常信封** `{type,error,message}`（`GenerationHandler` 抛出时）→ 失败不误报"成功/空结果"

### 额外（用户即时反馈修复）
- 聚合折叠头**紧凑化**（16dp 图标 Agora 比例）+ **黑色 onSurface**（非主题色）+ **折叠态时间线竖线守卫**（drawBehind 只在有可见步骤时画）

---

## 2. 当前 git 状态

- 分支：`master`，HEAD = `5554f4c`，已推送，工作树干净
- 最近提交链：`10c93ae`(Plan2 T1) … `e74f4ec`(header 修复) … `0e094be`(Plan3 T1) … `5554f4c`(Plan3 最终修复)
- CI：`nightly-build-debug` 全绿（最后一次 run 30786105853）
- ⚠️ 一次瞬态失败：JBR21 工具链下载 502（GitHub Actions 从 JetBrains 拉 JDK 失败），重跑即绿，**非代码问题**

---

## 3. 恢复地图（三个 SDD 台账，git-ignored，磁盘持久）

| 台账 | 路径 | 内容 |
|---|---|---|
| Plan 1 | `.superpowers/sdd/2026-08-02-tool-call-agora-style-plan1-model-side/progress.md` | 10+1 任务全过、OPEN MINORS 追踪清单、CARRY-TO-PLAN2、Task 12 预留 |
| Plan 2 | `.superpowers/sdd/2026-08-02-tool-call-agora-style-plan2-display/progress.md` | 7 任务全过、deferred minors、最终 review 结论 |
| Plan 3 | `.superpowers/sdd/2026-08-02-tool-detail-views-agora-style-plan3/progress.md` | 6 任务全过、deferred minors、final review triage |

三个台账都含各自的 deferred minor 清单——**收尾候选的直接来源**。

---

## 4. 挂起大项（按优先级）

### ① 乱召回（语义搜索 bug）——历史最高优先级，一直挂着
- **状态**：2026-08-02 起"先放着"，未动。用户要求调查"乱召回"（garbled recall），需用户提供 rawTop logs。
- **线索**：SemanticIndexManager.kt 曾有 CI 编译修复（缺右括号，a8d80ec）；问题疑似在召回打分/索引质量。
- **入口**：从 ConversationRepository.search / SemanticIndexManager.search 开始排查；需要用户端日志。

### ② Task 12（workspace_grep → 原生 ripgrep）——阻塞于 artifact
- 预留文档：`docs/superpowers/plans/2026-08-02-tool-call-ripgrep-task12-reserved.md`
- 依赖：原生 ripgrep AAR 交付后开工（JNI `NativeRipgrep.searchJson` + Claude Code 参数集 + glob mtime 排序 + JVM 回退）

### ③ 原生 ripgrep artifact 流水线——需新代理
- 交接文档：`docs/superpowers/handoffs/2026-08-02-native-ripgrep-artifact-pipeline.md`（给新 AI 代理，独立 Android JNI 库工程，交叉编译 4 ABI，发布 Maven，RikkaHub 侧零 JNI 接触）
- 新会话可直接把该文档丢给新代理执行

---

## 5. 收尾候选（2026-08-03 与用户讨论，未定优先级）

### A. 死代码清理（低风险，grep 已验证）
- `hasSummary`/`Summary` 接口 + **26 个 override**：零调用者（Plan 2 Task 4 弃用内联摘要后彻底死）。删接口（ToolUI.kt）+ 2 文件 override。
- `DefaultToolPreview.headerActions` 参数：唯一调用者 Memory 已迁移，参数死。删 1 处。

### B. 文案/本地化 polish（Plan 2 review 遗留）
- `(thoughtMs/1000)` 截断 → 亚秒块显示"Thought for 0 s"
- 英文单复数："called 1 tools"
- **只有工具无思考的块**：现显示"Thought for 0 s · called N tools"，Agora 单独显示"Called N tools"（Agora `called_n_tools` 字符串已确认存在）
- `tool_summary_empty_file` 硬编码 `"file"` 未本地化

### C. 渲染/行为微调（cosmetic）
- glob 行 pill 换行路径 `CenterVertically` → `Top`
- memory 详情 `0.8f×0.8f` 高度嵌套简化
- calendar 空串 title → `ifBlank`
- clipboard `else` 分支 → 诚实 action 判断
- CONVERSATION_LIST subject 恒 null（recent_chats 无 conversation_id）
- TTS 重播按钮默认尺寸

### D. 性能/健壮性
- `JsonTreeView` 大响应硬化（MCP 几千元素全量组合，非 Lazy；可加 size/depth cap）
- 聚合头 `filterIsInstance` 分配 → `asReversed().firstOrNull`
- `sizeBytes.toInt` 溢出（>2GB；实际 read_file 限 8MB，风险低）
- 两个 JSON 树组件交叉注释：`ui/components/ui/JsonTree.kt`（日志页，可展开）与 `ui/components/message/tools/JsonTreeView.kt`（详情，平铺）**并存不合并**（交互模式不同），建议加注释互指

---

## 6. 技术约束 / 惯例（必须遵守）

- **本机无编译器**：不运行 gradle。所有代码静态编写 + review；编译验证全靠 CI。
- **CI 流程**：先 `git push origin master` 再 `gh workflow run nightly-build-debug.yml --repo DevLintMar/rikkahub --ref master`（push 在前，否则跑旧代码）。`gh` 必须带 `--repo`。
- CI `assembleDebug` **不编译测试源码**——测试文件（Plan 2 写的 `ToolPresentationTest`/`ThinkingBlockAggregateTest`）为将来本地运行而写，从未真正编译过。
- **字符串双写**：英文 `values/` + 中文 `values-zh/`（用户拍板）。新增字符串一律照此。
- **展示层只消费信封，不改 producer**（信封形状冻结）。
- SDD 执行：`superpowers:subagent-driven-development`，每任务 fresh subagent + task review + fix loop；直接 master 上执行（用户同意，Plan 1 起沿用）。
- 图标名（HugeIcons）以 CI 编译为准（曾有 SearchList→Search01、AiBrain02 验证经验）。
- 文件删除走 `~/.claude/scripts/trash.sh`（回收站），不用 rm。
- 库/API 问题先走 Context7（mcp__mcphub__context7-*），禁止凭训练数据给代码示例。

---

## 7. 参考文件索引

| 文件 | 用途 |
|---|---|
| `docs/superpowers/specs/2026-08-02-tool-call-agora-style-design.md` | 设计 spec（信封/流式/展示/type 规则定案） |
| `docs/superpowers/plans/2026-08-02-tool-call-agora-style-plan1-model-side.md` | Plan 1 |
| `docs/superpowers/plans/2026-08-02-tool-call-agora-style-plan2-display.md` | Plan 2 |
| `docs/superpowers/plans/2026-08-02-tool-detail-views-agora-style-plan3.md` | Plan 3 |
| `docs/superpowers/plans/2026-08-02-tool-call-ripgrep-task12-reserved.md` | Task 12 预留 |
| `docs/superpowers/handoffs/2026-08-02-native-ripgrep-artifact-pipeline.md` | 原生 ripgrep artifact 流水线交接（给新代理） |
| `references/Agora/` | Agora 参考实现（ToolDetailContent/JsonNodeView/MessageItemTimeline 等） |
| 三个 SDD 台账 | 恢复地图 + deferred 清单（见 §3） |

---

## 8. 下一阶段建议

1. **先定收尾范围**：与用户确认 §5 清单做哪些（建议 A 死代码 > B 聚合头文案 > D 性能）。
2. **乱召回**：需要用户提供 rawTop logs 才能定位；若用户愿意，优先排。
3. **Task 12 / ripgrep artifact**：可并行开新代理执行 artifact 流水线（读 §4-③ 交接文档）。
4. 每个收尾项提交后照例 CI 验证。

---

## 9. 停靠点

- **已完成**：Plan 1/2/3 全部、header 修复、全部 CI 绿、master 推送至 `5554f4c`。
- **恢复动作**：读本文档 §3 台账 + §4 挂起项，与用户确认下一阶段入口（收尾 or 乱召回）。
