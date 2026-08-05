# 交接文档：切换对话转圈遮罩 + 后台预热缓存 + 系列时序修复 — 下一阶段入口

**日期**：2026-08-05
**目的**：上下文清理前的完整交接。新会话先读本文档 + SDD 台账（恢复地图），即可无缝续接。当前 master 已推送至 `a7e1d4d`，工作树干净；最后 CI（`a7e1d4d` 的 nightly-build-debug）绿。

---

## 0. 一句话概况

本阶段（自上一交接 `81cd510` 起）完成：**3 个质量修复**（TTS 内联摘要/屏幕时间 XhXm/搜索按键颜色）、**2 个性能优化**（Markdown 解析+段落构建进程级缓存）、**Agora 式切换对话转圈遮罩 + 后台预热缓存**（转圈期间预渲染全部 markdown 解析与代码高亮到缓存）、以及**5 轮时序/体验修复**（遮罩时序 conversationLoaded 门控、新建对话零闪、侧栏关闭后再转圈、切换助理保持侧边栏、256KB 高亮上限保护）。master 从 `81cd510` 推进到 `a7e1d4d`，共 17 个 commit，全部 CI 绿。

---

## 1. 已完成工作

### A. 质量修复（`7e40b72`、`2c7b1a7`、`c875924`）
- TTS 工具折叠行恢复内联摘要（朗读文本 + 右侧重放按钮），`596f08d` 曾误移入 Preview
- 屏幕时间工具详情回归最早 rikkahub 展示（`Xh Ym` 格式、总时长单独行、start→end 区间行）
- TTS 标题固定"朗读"（调用中/完成后统一，去掉预览文本）
- 搜索按键颜色 `Color.Black` → `MaterialTheme.colorScheme.onSurface`（对齐思考预算灯泡/加号，适配夜间）

### B. 性能优化：Markdown 进程级缓存（`4c097ff`）
- `MarkdownBlock` 的 `parseMarkdown` 结果进程级 LRU（key=内容，maxSize=128）——滚动滚回视野跳过主线程同步解析
- `Paragraph` 的 annotated string 构建结果进程级缓存（含 inlineContents；无行内公式时宽度归一，避免首帧 0→实际宽二次构建；含 citation 段落不缓存防回调失效）
- `LruCache`（accessOrder LinkedHashMap + @Synchronized）

### C. 切换对话转圈遮罩 + 后台预热（SDD 6 任务 + 终审 2 轮 fix：`7c55c4c`..`cc65e89`）
- **B1**：`prewarmMarkdown` + `collectCodeFences`（Markdown.kt）
- **B2**：highlight 模块 token 进程级缓存 + `prewarmHighlight`（HighlightText.kt）
- **B3（用户确认不做）**：段落 annotated string 预热——渲染时每段构建（被遮罩盖住的部分天然预热）
- **遮罩**：ChatList 全屏背景 + spinner，后台预热 + 布局稳定（3×32ms 采样签名一致）后淡出，8s 超时兜底（预热可取消，异常绝不阻塞释放，`covered=false` 必执行）
- `prewarmConversation`（ChatPrewarm.kt）：遍历 currentMessages.asReversed()，Text/Reasoning → markdown 解析+代码块高亮预热，Tool → 非 JSON 输出 plaintext 高亮预热

### D. 遮罩时序修复（`5897759`）——根治"先卡一下再转圈"
- 根因：conversation 初始是占位（空 messageNodes），真实数据经 `initializeConversation` 异步加载；释放 LaunchedEffect 捕获占位快照（预热 no-op）、空列表判稳提前露出
- 修复：ChatVM 加 `conversationLoaded` 信号（initializeConversation 完成后置 true），遮罩等该信号 + 稳定后才露出；稳定采样前 `withFrameNanos` 等一帧（minor 5）

### E. deferred minors 1/3/4（`c7074ca`）
- minor1 删死代码 `traverseChildren`；minor3 `prewarmHighlight` 阈值参数化；minor4 `prewarmToolOutput` 去 trim 与渲染一致

### F. highlight 字符数限制调整（`05a8c51` → `6a7492a`）
- 先去除全部限制（`05a8c51`：删 MAX_CODE_LENGTH/maxCodeLength/TOOL_RAW_JSON_MAX_CODE_LENGTH）
- 用户要求加回统一 **256KB 上限**（`6a7492a`：`MAX_CODE_LENGTH = 256*1024`，超长回退纯文本不缓存，预热同步跳过）——正常代码块与工具原始 JSON 统一受保护

### G. 加载流程调整（`089ef71`、`2b769f8`、`b2355ff`、`a7e1d4d`）
- **点选对话先关侧栏再转圈**（`089ef71`）：ChatDrawerContent 加 `drawerState` 参数，手机端点选对话 `scope.launch { drawerState?.close(); navigate }`——侧栏关闭动画即空窗，之后转圈；大屏永久抽屉直接导航
- **新建对话不转圈**（`2b769f8` 条件改 `newConversation` → `b2355ff` 根治）：`Screen.Chat` 加 `isNewChat` 导航标记，新建对话入口（TopBar+、抽屉删除后、切换助手新建、启动 create_new_conversation_on_start）传 true → ChatList 遮罩初始 `covered = !isNewChat`，新建对话**第一帧就不转圈（零闪）**
- **遮罩绝不持久**（`b2355ff`）：ChatVM `initializeConversation` 包 try/catch，`conversationLoaded` 必置 true；ChatList 加 8s 硬兜底 `delay(8000); covered=false`
- **启动自动进入的新聊天零闪**（`a7e1d4d`）：`AppRoutes.startScreen` 传 `isNewChat = startId == startNewId`
- **切换助理保持侧边栏**（`a7e1d4d`）：`Screen.Chat` 加 `openDrawer` 标记，切换助理 `navigateToChatPage(openDrawer=true)` → 新 ChatPage 抽屉初始为打开

---

## 2. 当前 git 状态

- 分支：`master`，HEAD = `a7e1d4d`，工作树干净
- 提交链：`81cd510`（上交接）→ `7e40b72`(TTS/屏幕时间) → `2c7b1a7`(TTS 标题) → `4c097ff`(markdown 缓存) → `c875924`(搜索颜色) → `7c55c4c`..`cc65e89`(遮罩+预热 SDD) → `5897759`(时序修复) → `c7074ca`(minors) → `05a8c51`(去限制) → `089ef71`(加载流程) → `6a7492a`(256KB) → `2b769f8`(新建对话) → `b2355ff`(零闪+兜底) → `a7e1d4d`(启动/侧栏)
- CI：全部 commit 独立验证绿；最后 run = `a7e1d4d` 的 nightly-build-debug = success（headSha 核对）

---

## 3. 恢复地图

| 台账 | 路径 | 内容 |
|---|---|---|
| SDD 台账（本阶段全部） | `.superpowers/sdd/atomic-frolicking-hare/progress.md`（git-ignored） | 遮罩+预热 3 任务 + 终审 + 后续 6 轮修复的完整记录、deferred minors 全列表、每任务 commit |
| SDD briefs/reports | `.superpowers/sdd/atomic-frolicking-hare/task-{1..9}-{brief,report}.md` | 每任务的详细需求与实现报告 |
| 设计/计划 | `C:\Users\LintMar\.claude\plans\atomic-frolicking-hare.md` | 遮罩+预热方案（B1/B2/B3 取舍） |
| 上一交接 | `docs/superpowers/handoffs/2026-08-05-subagent-display-search-redesign-bing-fix-complete.md` | 更早历史（搜索多选/Bing 根因修复等） |

---

## 4. 待确认 / 挂起项（按优先级）

### ① 设备核验（最高优先，唯一需用户上手）
装最新 debug APK 验证：
1. 打开已有长对话 → 转圈期间加载（不再先卡）、露出后滚动无首轮解析卡顿
2. 点选对话（手机）→ 侧栏先关 → 再转圈；切换助理 → 侧边栏保持打开、新对话零闪
3. 新建对话（含预设消息）/启动自动进入的新聊天 → 完全不闪转圈
4. 超长代码块（≥256KB）/工具原始 JSON → 纯文本不高亮；<256KB 正常高亮
5. 夜间模式搜索图标、屏幕时间 XhXm、TTS 折叠行文本+重放

### ② 历史最高优先：乱召回（语义搜索 bug）
- 需用户提供 rawTop logs。入口：`ConversationRepository.search` / `SemanticIndexManager.search`

### ③ Task 12（workspace_grep → 原生 ripgrep）+ 原生 ripgrep artifact 流水线
- 计划 `docs/superpowers/plans/2026-08-02-tool-call-ripgrep-task12-reserved.md`；交接 `docs/superpowers/handoffs/2026-08-02-native-ripgrep-artifact-pipeline.md`

### ④ 更新检查恢复点
- `UpdateChecker.kt` 的 `checkUpdate()` 有 `return@flow` 短路 + 注释，删除该行即恢复

### ⑤ SDD 台账 deferred minors（约 30 项）
- 见 `.superpowers/sdd/atomic-frolicking-hare/progress.md`（本阶段新增：T4 参数顺序/预热快照竞态、T7 效果 key 精简、T8 UTF-16 计数、T9 注释取舍、T1 walkNodes 合并等）

---

## 5. 技术约束 / 惯例（必须遵守）

- **本机无编译器**：不运行 gradle。静态编写 + review，编译验证全靠 CI。
- **CI 判定铁律**：`gh run list --workflow nightly-build-debug.yml --repo DevLintMar/rikkahub --limit 1 --json databaseId,headSha,status,conclusion` 权威判定 + **核对 headSha**；`gh run watch` 可能误报。CI 流程：先 `git push origin master` 再 `gh workflow run nightly-build-debug.yml --repo DevLintMar/rikkahub --ref master`。
- **CI 不跑单测**：`nightly-build-debug.yml` 只 `assembleDebug`；单测验证不可靠。
- **reified 类型推断陷阱**：局部 val 里 `decodeFromString` 带 `?: listOf(具体类型)` 会被推断成具体类型，破坏 sealed 多态解码 → 必须显式多态类型。详见记忆 `kotlin-reified-type-inference-sealed-decode`。
- **runCatching 不能包 suspend 调用**（`block: () -> R` 非 suspend）；包 suspend 需 `try/catch` 且**重抛 `CancellationException`**（否则吞掉超时取消）。
- 设备运行时诊断用应用内"请求日志"（`Logging.log(tag, message)` 写入，`LogPage` 显示 TextLog）。
- 字符串双写 en+zh（ja/ko/ru/zh-rTW 有 key 时一并改）；删串先 grep 零引用。
- 文件删除走 `~/.claude/scripts/trash.sh`（回收站），禁止 rm。
- 图标名（HugeIcons）以 CI 编译为准。
- **force-push 需用户明确要求**。

---

## 6. 关键架构决策（本阶段遗留给后续的认知）

- **切换对话转圈遮罩**：ChatList 全屏背景+spinner，`covered` 初始 `!isNewChat`；释放 = 预热完成 + `conversationLoaded` + 布局稳定（3×32ms 签名一致），8s 硬兜底。遮罩隐藏 LazyColumn 首帧渲染卡顿（列表始终组合在遮罩下层）。
- **后台预热**：B1 markdown 解析 + B2 代码高亮 token 写入进程级缓存；遮罩期间后台运行，露出后首帧命中、滚动零首轮解析。B3（段落 annotated string）未做——渲染时构建，被遮罩盖住的可见消息天然预热。
- **新建对话零闪**：靠**导航层同步标记** `isNewChat`（而非异步 `newConversation` 标志）——新建对话入口全部传 true，ChatList covered 初始 false。异步标志依赖 initializeConversation 完成，冷启动慢时仍会闪。
- **conversationLoaded 门控**：遮罩释放等 `initializeConversation` 完成后置 true 的信号，占位期（空 messageNodes）绝不提前露出。
- **启动路径**：`AppRoutes.startScreen` 用 `startId == startNewId` 判断是否新建传 isNewChat。
- **openDrawer 标记**：切换助理保持侧边栏——新 ChatPage 抽屉初始打开。
- **256KB 上限**：`HighlightText`/`prewarmHighlight` 统一 `MAX_CODE_LENGTH = 256*1024`，超长回退纯文本不缓存。
- **预热顺序**：`currentMessages.asReversed()`（最新/底部优先，接近打开时可见区）。
- **Markdown 进程级缓存**（`4c097ff` 独立于遮罩）：`parseMarkdownCached`（key=内容 LRU）+ 段落 annotated string 缓存——滚动滚回视野零重算，是性能治本，遮罩是体验兜底。

---

## 7. 参考文件索引

| 文件 | 用途 |
|---|---|
| `app/.../ui/pages/chat/ChatList.kt` | 遮罩 + 稳定采样 + covered 逻辑（含 isNewChat/newConversation/8s 兜底） |
| `app/.../ui/pages/chat/ChatPrewarm.kt` | `prewarmConversation` 遍历器 |
| `app/.../ui/components/richtext/Markdown.kt` | `parseMarkdownCached` + `collectCodeFences` + 段落缓存 + `LruCache` |
| `highlight/.../HighlightText.kt` | token 缓存 + `prewarmHighlight` + 256KB 上限 |
| `app/.../ui/pages/chat/ChatVM.kt` | `conversationLoaded`（try/catch 必置 true） |
| `app/.../ui/pages/chat/ChatPage.kt` / `ChatDrawer.kt` | `isNewChat`/`openDrawer` 透传 + 点选先关侧栏 |
| `app/.../RouteActivity.kt` | `Screen.Chat` 路由（isNewChat/openDrawer）+ 启动 startScreen |
| `app/.../utils/ChatUtil.kt` | `navigateToChatPage`（isNewChat/openDrawer） |

---

## 8. 下一阶段建议

1. **设备核验**（§4-①）：转圈遮罩/预热/新建零闪/切换助理保持侧边栏/256KB 上限等 5 项。
2. 若核验通过，消化 SDD 台账 deferred minors（§4-⑤）。
3. 历史挂起（§4-②③④）：乱召回（需 logs）、Task 12 ripgrep、更新检查恢复点。

---

## 9. 停靠点

- **已完成**：质量修复 + 性能缓存 + 切换对话转圈遮罩（后台预热 B1/B2）+ 5 轮时序/体验修复（`a7e1d4d`），master 工作树干净，CI 全绿。
- **待确认**：设备核验（§4-①）——转圈期间加载、新建对话零闪、切换助理保持侧边栏等。
- **恢复动作**：读本文档 §3 台账 + §4 待确认/挂起项，先让用户装包核验。
