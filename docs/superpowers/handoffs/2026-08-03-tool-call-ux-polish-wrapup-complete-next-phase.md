# 交接文档：工具调用 UX 质量修复（13 项）+ 收尾 A-D 完成 — 下一阶段入口

**日期**：2026-08-03
**目的**：上下文清理前的完整交接。新会话先读本文档 + 计划台账（恢复地图），即可无缝续接。当前 master 全部绿、已推送。

---

## 0. 一句话概况

RikkaHub 工具调用展示的**13 项质量修复 + 收尾 A-D** 已全部完成：聚合头高度/秒数/颜色统一、执行中自动展开、详情半屏→近全屏（Agora 移植）+ 页面/分区 JSON 开关、长文本不截断、记忆删除二次确认、ask_user/denied 卡调用中修复、read_conversation/sub_agent 渲染器，以及死代码清理/文案/渲染微调/性能硬化。13 个任务逐 review、最终全分支审查 APPROVED、最终 HEAD CI 绿。

---

## 1. 已完成工作（全部在 master，CI 绿）

### Phase 1 — 13 项质量修复（items 1-13）

| 项 | 内容 | 提交 |
|---|---|---|
| 1 | 聚合头/推理行高度统一（36/26→平均 31dp）：两行 padding 5.5dp + 聚合头图标包 20dp 盒 | 8939726 |
| 2 | 秒数一位小数（`%1$.1f`）+ 纯工具块显示 "Called N tools" + 工具数单复数（`plurals.tool_called_count`） | 8939726 |
| 3-4 | 思考文本/图标/大脑图标/箭头颜色与工具列表行一致（推理图标 `LocalContentColor.7`、文本 `onSurfaceVariant.6`、聚合头箭头去 0.5 alpha） | 69b79fa |
| 5 | AI 执行中聚合块自动展开、完成自动收起（`ChainOfThought` 新参 `autoExpand`） | e1a1f75 |
| 6 | **详情半屏→近全屏**：新 `ToolDetailSheet.kt`（Agora SegmentDetailSheet 移植，Half 0.5/Full 0.94 状态机 + 嵌套滚动 + 原生 dimAmount），全量 Preview 改 content-only | d1286d7 |
| 7-8 | 工具标题 + 页面级 JSON 开关（Sheet 标题栏 CodeSquare）+ 分区级开关（`ToolJsonBody`/`ToolJsonSection`） | d52c97c |
| 9 | 长文本详情不截断（JsonTreeView 去 maxLines、会话搜索片段去 take(120)） | 32264e2 |
| 10 | 记忆删除二次确认 AlertDialog | 885a12a |
| 11 | ask_user 完成后 toolState 置 SUCCEEDED（**顺带** Denied 分支置 FAILED——同根因） | aeb0ec6 + 7b2ad49 |
| 12-13 | `read_conversation`（阅读对话 / MessageDelay01）+ `sub_agent`（子代理 / ChatBot）语义化渲染器 + SUB_AGENT subject 改 description | f89ff2b |

### Phase 2 — 收尾 A-D

| 项 | 内容 | 提交 |
|---|---|---|
| A | 死代码：`hasSummary`/`Summary` 接口 + 26 override + `headerActions` 参数全清；另清 9 helper/13 imports | ab31e92 |
| B | 文案：`tool_summary_empty_file` 的 "file" 兜底本地化（聚合头文案随 Task 1） | 7e86de8 |
| C | 渲染微调：glob pill Top 对齐 / calendar ifBlank / clipboard 诚实 else / CONVERSATION_LIST subject 取首条 title（Step2 memory 嵌套、Step6 TTS 按钮经证据确认 no-op） | 66324e1 |
| D | 性能：JsonTreeView 深度 12 + 条目 2000 硬化（共享 IntArray 预算，**fix：每组合重置避免跨重组耗尽**）/ 聚合头 `asReversed().firstOrNull` / sizeBytes 溢出 / 双 JSON 树交叉注释 | 6f45b14 + 8388fad |

---

## 2. 当前 git 状态

- 分支：`master`，HEAD = `8388fad`，已推送，工作树干净
- 提交链：`ba65cfe`（上一阶段交接）→ 15 个任务提交（见上表）→ `8388fad`（Task 13 fix）
- CI：**最终 HEAD `8388fad` run 30800787661 = SUCCESS**（决定性绿，全 13 任务代码一次编译通过）；中间 9/10/11/12 各自 run 均绿
- ⚠️ 三次瞬态失败：Task 4/5 首跑 + Task 8 单独 run = **JitPack `sqlite-android:-SNAPSHOT` 解析 flake**（Could not HEAD maven-metadata.xml），非代码（被后续含其代码的绿 run 证明），与 JBR21 502 同类

---

## 3. 恢复地图（计划 + SDD 台账）

| 台账 | 路径 | 内容 |
|---|---|---|
| 实现计划 | `docs/superpowers/plans/2026-08-03-tool-call-ux-polish-and-wrapup.md` | 13 任务全代码 + 自审 + 执行交接 |
| SDD 台账 | `.superpowers/sdd/2026-08-03-tool-call-ux-polish-and-wrapup/progress.md`（git-ignored，磁盘持久） | 13 任务进度 + **全 deferred minors 清单** + CI 记录 + 最终审查结论 |
| 上一阶段 | `docs/superpowers/handoffs/2026-08-03-tool-call-agora-complete-next-phase.md` | Plan 1/2/3 交接（历史） |
| 三个 Plan 台账 | `.superpowers/sdd/2026-08-02-*.md` | 历史恢复地图（deferred 大多已在本阶段消化） |

---

## 4. 挂起大项（按优先级）

### ① 乱召回（语义搜索 bug）——历史最高优先级，一直挂着
- 状态：需用户提供 rawTop logs 才能定位。入口：`ConversationRepository.search` / `SemanticIndexManager.search`。
- 线索：SemanticIndexManager.kt 曾有 CI 编译修复（缺右括号 a8d80ec）；疑似召回打分/索引质量。

### ② Task 12（workspace_grep → 原生 ripgrep）——阻塞于 artifact
- 预留文档：`docs/superpowers/plans/2026-08-02-tool-call-ripgrep-task12-reserved.md`
- 依赖：原生 ripgrep AAR 交付后开工（JNI `NativeRipgrep.searchJson` + Claude Code 参数集 + glob mtime 排序 + JVM 回退）

### ③ 原生 ripgrep artifact 流水线——需新代理
- 交接文档：`docs/superpowers/handoffs/2026-08-02-native-ripgrep-artifact-pipeline.md`（独立 Android JNI 库工程，交叉编译 4 ABI，发布 Maven，RikkaHub 侧零 JNI 接触）

---

## 5. 剩余收尾候选（本次最终审查 triage 的 deferred 清单，全部 Acceptable-deferred）

### 真机验证（唯一需用户上手）
- ToolDetailSheet 手势：入场动画抓握回弹半屏 / Full 态下拉 phase 保持 / Half 慢拖靠 fling+80ms 安全网 / dimAmount 每帧调暗

### 低风险 polish（可合并成一个小任务一次做完）
- 文案：① `chain_of_thought_aggregate` `%1$.1f` 用默认 locale（部分 locale 小数点变逗号）→ 传 `String.format(Locale.ROOT,...)`；② `tool_summary_empty_file` 缺 ja/zh-rTW/ko/ru 4 locale；③ read_conversation 空消息页复用 "No conversations" → 加 `tool_ui_read_conv_empty`
- 代码整洁：④ `JsonArray` import 与 `arraySize` 全限定并存统一；⑤ 两 JSON 树文件头 KDoc 在 package 前（移到 import 后）；⑥ ChainOfThought 缺 `@param autoExpand` KDoc；⑦ 4 个死字符串 + 死 `FaviconRow`（移除需动 6 locale，可选）
- 行为微调：⑧ ToolDetailSheet 页面 JSON 开关不重置 `scrollState`；⑨ ReadConversationToolUI messages 无上限遍历（可加 has_more 提示）；⑩ DefaultToolPreview 参数区开关视觉 no-op（新样式=JSON 树；可去掉或说明）

### 已知边界（pre-existing，非回归）
- mixed 块零已执行工具显示 "called 0 tools"；流式标题行 extra 时长保持 secondary（裁决保留）

---

## 6. 技术约束 / 惯例（必须遵守）

- **本机无编译器**：不运行 gradle。所有代码静态编写 + review；编译验证全靠 CI。
- **CI 流程**：先 `git push origin master` 再 `gh workflow run nightly-build-debug.yml --repo DevLintMar/rikkahub --ref master`（push 在前）。`gh` 必须带 `--repo`。
- CI `assembleDebug` **不编译测试源码**（ToolPresentationTest/ThinkingBlockAggregateTest 为将来本地运行而写，从未编译）。
- **字符串双写**：英文 `values/` + 中文 `values-zh/`（用户拍板）。新增/修改字符串一律照此；修改既有格式参数只需这两处（ja/ko/ru/zh-rTW 无此 key 则回退英文）。新字符串用 `stringResource`/`pluralStringResource`。
- **展示层只消费信封，不改 producer**（信封形状冻结）。已注册渲染器列表见 `ToolUIRegistry`（ToolUI.kt）。
- SDD 执行：`superpowers:subagent-driven-development`，每任务 fresh subagent + task review + fix loop；直接 master 执行（用户同意，Plan 1 起沿用）。
- 图标名（HugeIcons）以 CI 编译为准；`MessageDelay01`/`ChatBot`/`CodeSquare` 已确认真实存在（GitHub 树 + v1.3）。
- 文件删除走 `~/.claude/scripts/trash.sh`（回收站），不用 rm。
- 库/API 问题先走 Context7（mcp__mcphub__context7-*），禁止凭训练数据给代码示例。
- 最终审查惯例：大/高风险 diff 用 opus（Task 4/10/最终）；中小 diff 用 sonnet；纯转写用 haiku。
- JitPack `sqlite-android:-SNAPSHOT` / JBR21 下载偶发 flake——**重跑即绿，勿判为代码问题**（本阶段已遇 3 次）。

---

## 7. 参考文件索引

| 文件 | 用途 |
|---|---|
| `docs/superpowers/plans/2026-08-03-tool-call-ux-polish-and-wrapup.md` | 本阶段实现计划（13 任务全代码） |
| `.superpowers/sdd/2026-08-03-tool-call-ux-polish-and-wrapup/progress.md` | 本阶段台账：进度 + deferred 清单 + CI 记录 |
| `docs/superpowers/handoffs/2026-08-03-tool-call-agora-complete-next-phase.md` | 上一阶段交接（Plan 1/2/3） |
| `references/Agora/app/src/main/java/com/newoether/agora/ui/chat/message/SegmentDetailSheet.kt` | ToolDetailSheet 移植源 |
| 关键新文件 | `ToolDetailSheet.kt` / `ToolDetailCommon.kt`（ToolJsonBody/ToolJsonSection）/ `JsonTreeView.kt` |
| 上一阶段挂起 | 乱召回入口、Task 12 预留、ripgrep artifact 流水线（见 §4） |

---

## 8. 下一阶段建议

1. **真机验证 ToolDetailSheet 手势**（§5-真机），确认无误后如需要可微调 drag 参数。
2. **低风险 polish 合并成一个小任务**（§5-低风险 ①-⑩），每项提交后照例 CI。
3. **乱召回**：需用户提供 rawTop logs 才能定位；若用户愿意，优先排。
4. **Task 12 / ripgrep artifact**：可并行开新代理执行 artifact 流水线（读 §4-③ 交接文档）。
5. 每个收尾项提交后照例 CI 验证。

---

## 9. 停靠点

- **已完成**：13 项质量修复 + A-D 收尾全部、逐任务 review、最终全分支审查 APPROVED、最终 HEAD CI 绿、master 推送至 `8388fad`。
- **恢复动作**：读本文档 §3 台账 + §4 挂起项 + §5 收尾候选，与用户确认下一阶段入口（真机验证 / polish / 乱召回）。
