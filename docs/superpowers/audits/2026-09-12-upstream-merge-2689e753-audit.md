# 上游同步审计：`2689e753`（157 提交）给 fork 带来了什么、埋了哪些坑

**日期**：2026-09-12
**审计对象**：`cab3a656`（merge: 同步上游 2689e753）之后的 fork 状态
**基线 SHA**：

| 名称 | SHA | 说明 |
|---|---|---|
| 分叉点（merge-base） | `4b6449e3` | 上次同步后的共同祖先 |
| 合并前 fork master | `7042fa80` | fork 自己的最新状态 |
| 上游 pin（本次合入） | `2689e753` | 上游最后一个 CI 绿提交 |
| 合并提交 | `cab3a656` | |
| 审计时 HEAD | `61c0b26b` | |

**规模**：上游 157 提交 / 422 文件；fork 自有 475 提交 / 398 文件；**双方都改过 68 文件**（冲突面）。

**方法**：11 个只读子代理按模块并行审计 + 主会话逐条复验关键指控。所有结论要求 `文件:行号` + 引入它的 commit sha + 可复现命令。
**证据等级**：✅ 主会话已复验 ｜ ⚠️ 代理结论未复验 ｜ ❌ 已推翻（见 §5）

> **后续状态（2026-09-12 同日）**：本文档是审计时点（`61c0b26b`）的快照。§2 里的 A 类缺陷**基本已修**
> （§2-1 / §2-2 / §2-7 / §2-8 / §2-13 的 `.editorconfig`、`AGENTS.md`、`liveOutput` / §2-4）。
> ⚠️ **两处更正**：§2-3a（输入框 IME 形态）与 §2-3b（`<think>` 行内标签）被审计判为「无人做过这个决定」，
> 实际**上游各有专门的 `fix:` 提交**（`f86d6e82` / `85402745` + 7 个单测）→ 已改为**保留上游行为**，不算缺陷。
> §6 的第 1 条已消化、A6 已决策为「保留上游行为」（上游 #1790）；**其余 §6 决策项仍未动**。
> 逐条落点见交接文档 `docs/superpowers/handoffs/2026-09-12-a-class-regressions-and-sync-rules-next-phase.md`。
> 本文档保留原样作为事故复盘（每条带 `文件:行号` + 引入 sha + 复现命令）。

---

## 0. 摘要：五件最该知道的事

1. **🔴 合并丢了一次持久化写入**（✅已复验，两代理独立确认）：`persistSettings` 没有写 `SUB_AGENT_MODEL` / `EMBEDDER` / `KEEP_ALIVE_ENABLED` 三个 fork 专有键 → 子代理模型、语义搜索配置、保活开关**改完重启即回退**，**从备份恢复也不会落地**。见 §2-1。
2. **🟠 合并产出了自相矛盾的代码**（✅已复验，三代理独立报告）：`ChatMessageTools.kt:130` 用旧口径、`:288` 用新口径 → 被"停止生成"掐断的待审批工具仍显示 ✓/✕ 按钮，点了无效。见 §2-2。
3. **🟠 若干"上游改主意、fork 从未表态"的改动被静默接受**（✅已复验 3 项）：搜索信封（已回退）、输入框 IME 形态（键盘弹出不再变直角贴边）、`<think>` 提取正则收紧（行内思考不再被抽取）、合成消息不再过消息模版。见 §2-3。
4. **🟠 上游官方备份升到我们的 DB 会开库失败**（⚠️未复验，逻辑硬）：`message_embeddings` 只由 `AutoMigration(24,25)` 的编译期产物创建，两条手写迁移都不建表；恢复外来备份的暂存校验同样会踩。
5. **下次同步必踩清单已成型**（见 §3）：上游删 `libs.versions.toml`、`AppDatabaseFactory` 迁移注册、`app/schemas/25.json` 同名不同构、huge-icons/haze 钉版本、`.claude/skills` 符号链接、`BuiltinToolUIs.SearchWebPreview`、fork 的 keep 规则追加块贴在文件尾部。

---

## 1. 上游这次带来了什么（按领域）

> 只列**新东西**，不列 bugfix。每条：是什么 → 影响 → 关键位置 → 出处 sha。

### 1.1 ai 模块：流式架构重构（本批最大增量，`4b9ff1a9`）

| 新特性 | 说明 | 关键位置 |
|---|---|---|
| `StreamChunk` 事件流 | 取代 choices 数组式增量：Text/Reasoning/ToolCall/ServerTool/Image/Usage/Finish 共 22 个 sealed 事件 | `ai/.../ui/StreamChunk.kt:16` |
| `StreamChunkHandler` | 有状态合并器，按事件 id 定位 part；`handleTextGenerationResult` | `ai/.../ui/StreamChunkHandler.kt:57/323` |
| 传输无关解码器 | `StreamChunkDecoder` + `SseEvent`；4 个 provider 各自解码器 | `ai/.../provider/stream/{StreamChunkDecoder,SseEvent}.kt` |
| `TextGenerationResult` | `generateText` 返回类型从 `MessageChunk` 换成含 `finishReason`/`usage` 的结构；`MessageChunk` 与 `handleMessageChunk` **整体删除** | `ai/.../provider/Provider.kt:58` |
| 服务端工具 `ServerTool` | 新 sealed 子类 + `ServerToolStatus` + `ServerToolProtocol`，Claude `web_search`/OpenAI Responses/Gemini `googleSearch` 可在 UI 里当"思考步骤"展示 | `ai/.../ui/UIMessagePart.kt:174`、`MessageMetadata.kt:47` |
| `ReasoningType` | 区分 `REASONING_TEXT`（可回放）与 `SUMMARY_TEXT`（不回放） | `UIMessagePart.kt:68`、`ResponseAPI.kt:358` |
| 流式图片 | `ImageSnapshot` 语义=整帧替换（Gemini 图像生成） | `StreamChunk.kt:113-139` |
| 轨迹回放测试设施 | 8 组真实 SSE 轨迹 + `StreamTraceReplayTest` + `trace-cli/` 录制工具 | `ai/src/test/resources/stream-traces/` |
| Provider 包结构重组 | `providers/{claude,google,openai,vertex}/`（**破坏性 import 变更**） | `ai/.../provider/providers/` |
| Claude `pause_turn` 续轮 / Gemini 服务端+客户端工具共存 / Responses 大改 | 见 `ClaudeProvider.kt:101/118`、`GoogleProvider.kt`、`ResponseAPI.kt:429-440` | — |
| 模型注册表 | 新 DSL `contextLength()` + 一批新模型（GPT-6、Claude 5、DeepSeek v4.1、Qwen3、GLM5.3、HY4、Muse） | `ai/.../registry/ModelDsl.kt` |
| 会话头统一 | `X-Session-ID` / `x-opencode-session` / OpenRouter `session_id` | `ai/.../util/Request.kt:41` |
| 工具审批语义收紧 | `Tool.isPending = !isExecuted && approvalState is Pending` | `UIMessagePart.kt:203`（`55506496`） |
| `isSynthetic` | 内部合成消息不参与模版渲染、不落库 | `ai/.../ui/Message.kt:29`（`942d0d28`） |

### 1.2 oauth 模块 + MCP OAuth 重写（`b6df5f04`）

新增 Gradle 模块 `:oauth`：RFC 7591 动态注册 + PKCE S256 + 刷新（`OAuthHttpClient`）、`127.0.0.1` 环回回调服务器（Ktor CIO + 多语言 HTML 页 `OAuthLoopbackCallbackServer`）、前台服务保活（`OAuthCallbackForegroundService`）、Custom Tabs 启动器。app 侧删掉 `McpOAuthClient`/`McpOAuthCallback`/`McpOAuthCallbackActivity` 与对应 manifest 条目、`AppEvent.McpOAuthCallback`，改用 `McpOAuthDiscoveryClient`。**已核实无残留引用**，本机 `core.symlinks=false` 不影响该模块。

### 1.3 生成循环与工具装配（`097cdb90` 为主）

- `GenerationHandler.kt` → **`GenerationLoop.kt`**（fork 的老文件已删）
- 新增 **`ChatToolFactory`**：工具装配唯一入口（记忆/搜索/本地/对话/工作区/技能/MCP）
- 新增 **`TranslationHandler`**（翻译从生成循环里抽出，与上游逐字节一致）
- 网络自动重试：`MAX_PROVIDER_NETWORK_RETRIES=3`，退避 1/2/4s，只对 `IOException`，入口 `ensureActive()` 兜住"停止生成被误判为网络故障"；设置页可关（`NetworkSetting.enableAutoRetry`，**默认 true**）
- 流式重试"快照重放"（每次重试从 `responseBaseMessages` 重放、新建 handler）
- `try/finally { processingStatus.value = null }`
- **消息发送队列** `MessageQueue`（生成中可继续输入并排队、可编辑/撤回/恢复、可 `pause`）
- **语音模式**（ASR 常开 + 服务端 VAD 断句 + 自动朗读；`VoiceMode.kt`/`VoiceSessionController.kt`）
- 前台服务保活 `ChatGenerationForegroundService`
- 内置/外挂搜索统一门控 `shouldUseExternalWebSearch`
- 连续工具审批修复（`afterPreviousGeneration` + `setJob(cancelPrevious=false)`）

> ⚠️ **更正一个常见误解**：`maybeTruncateToolOutput` / `/tool_outputs/` 32KB 指针**不是本次上游新增** —— `4b6449e3:GenerationHandler.kt` 就有，是 fork 在做 Agora 流式改造时删掉换成了 `clipToolOutput`，本次是把上游实现拿回来。

### 1.4 数据层（`540b9dfa` / `66de8b30` 等）

- `AppDatabaseFactory` + `SQLiteConfiguration` 抽取（活库与备份校验共用；**迁移注册点从 DI 搬到这里 —— 就是漏注册 `Migration_25_26` 的位置**）
- `Conversation.localFileUrls()`（递归进工具输出，含 read_image 结果图）
- `hasFileReference`（DAO 级整库 `instr` 查询，供消息队列回收附件）
- `VALID_MESSAGES_JSON`（`json_valid()` 守卫，脏 JSON 不再让统计整条失败）
- FTS 搜索支持按助手过滤（`MessageFtsManager.search(assistantId)`）
- `Assistant.timeReminderIntervalMinutes`（可调间隔）
- `Settings.networkSetting` + 代理/UA；`fastModelReasoningLevel`；**删除** `titleModelId`/`suggestionModelId`

### 1.5 备份/恢复（`540b9dfa` / `f557cef5` / `7a93c92a` / `7714f2bc`）

- `BackupManager` + `PendingRestore` + `DatabaseBackup`：备份/恢复从"三份手写"收成一套
- **一致性快照** `VACUUM INTO`（归档里不再有 `-wal`/`-shm`）
- **事务式暂存恢复**：解压到 `preparing-*` → 校验迁移 → 原子改名 `pending` → **下次进程启动、Koin/Room 之前安装**；安装带 `journal.json`，失败整体回滚（`RestoreFailedException`），中断可续作
- 安装前四道闸：`integrity_check` → 用完整迁移链打开暂存库 → checkpoint 去 sidecar → `settings.json` 迁移后校验 `init`
- 本地导入覆盖确认对话框；导出/导入可选内容（DB / 文件）
- **Chatbox Backup v2 导入**（新第三方迁移入口）

### 1.6 workspace / 终端 / 预览（`03246406` / `7038e981` / `3a533a6a` / `aac5e43e` / `b62d29d1` / `f4508dfa`）

- **Shell 兼容模式** `workspaces.shell_compatibility_mode`（`PROOT_NO_SECCOMP=1`）+ 基础信息页开关（`03246406`）—— **但流式路径漏接，见 §2-7**
- **终端后台驻留 + 多 Tab**：`WorkspaceTerminalSessionManager`（会话所有权从 Composable 生命周期剥离、进程级会话、删工作区前 `cancelAndJoin`）、关 Tab 二次确认、`SecondaryScrollableTabRow` + 无障碍语义、IME 手势/resize 修复（避免逐帧 SIGWINCH）
- **非交互命令 stdin 立即 EOF**：修 `cat`/`gh`/`kubectl` 因 isatty 判断永久阻塞（`WorkspaceShellRunner.kt:55-63` + 单测）
- **proot 二进制升级**（arm64 + x86_64 两个 ABI 全换，`f4508dfa`）；proot env 加固（`CI=true`/`NO_COLOR=1`/`PAGER=cat`）
- **工作区文件列表图片缩略图**（`resolveFile` + `produceState` + `AsyncImage`，`aac5e43e`）
- **工作区 html/svg 预览**（WebView，`allowFileAccess=false`，`baseUrl=workspace-preview.invalid`）；聊天代码块内嵌 HTML/SVG 预览**默认关闭**
- **读取 AGENTS.md**：`/root/.agents/AGENTS.md` → `/workspace/AGENTS.md` → `<cwd>/AGENTS.md`，包 `<workspace_instructions>`，单文件 64KB 上限
- `/skills` 进免审批可写区；`workspace_edit` 入参非 JSON 对象不再崩（`dee88dca`）；SAF 文件存在性判断修复；代码块导出覆盖修复
- fork 侧：fork 会话继承 `workspaceCwd`/`folderId`/`customSystemPrompt`（`6c6a8458`）

### 1.7 UI / 交互（面最广）

消息队列面板、语音模式入口、模型选择器抽成按钮+Sheet、发送按钮抽成 `SendButton`（纯附件可发）、英文句首自动大写、`ask_user` 所有选项类型都支持自定义文本、服务端工具步骤渲染（`ChatMessageServerToolStep`）、推理等级 `MAX` 且选择器去掉刻度、正则列表可拖拽排序（Reorderable）、键盘闪烁修复、主题系统栏图标恢复、更新提醒可暂停、分范围清理文件、供应商"测试连接"、APIMart/MaruCode 默认 provider、抖音群入口（fork 已注释掉）、debug 图标 DEV 徽标、Launcher 快捷方式进翻译页。

### 1.8 构建 / 依赖 / 模块

新模块 `:videogen`（视频生成，Aliyun/MiniMax/Volcengine）、`:oauth`；`snakeyaml` 2.6（解析 skill frontmatter）；mcp SDK 制品换名 `kotlin-sdk-client`；AGP 9.3 的 `optimization {}` + `src/<variant>/keepRules/*.keep` 源集约定；Compose BOM `2026.06.01→2026.08.00`、material3 `alpha25→alpha27`、coil `3.5→3.6.2`、nav3Material `1.0.0-SNAPSHOT→1.3.0`、okhttp `5.4→5.5`、ktor `3.5.1→3.5.2`、sqlite-vector `0.9.92→1.0.0` 等一批升级。

---

## 2. 已确认的缺陷（合并引入，按严重度）

### 2-1 🔴 `persistSettings` 漏写三个 fork 键（配置静默丢失）✅已复验

- **缺口**：`PreferencesStore.kt:171-229` 的 `persistSettings` 没有任何 `SUB_AGENT_MODEL` / `EMBEDDER` / `KEEP_ALIVE_ENABLED` 写入；这三个键只有声明（`:104/:130/:157`）与读取（`:282/:336/:342`）。
- **引入点**：合并提交 `cab3a656`（采纳上游抽出的 `persistSettings` 时只搬了 `SEARCH_SELECTED_IDS`，漏了这三个）。`fa07ca61`/`61c0b26b` 均未修。
- **复现命令**：
  ```bash
  python - <<'EOF'   # 严格比对 声明/读取/写入 三集合
  # 见附录 A：D:\Temp\prefs_key_audit.py
  EOF
  git show 7042fa80:app/src/main/java/me/rerere/rikkahub/data/datastore/PreferencesStore.kt | grep -n 'SUB_AGENT_MODEL\|EMBEDDER\|KEEP_ALIVE_ENABLED'
  ```
  结果：合并前 fork 在 416/418（sub_agent_model，含 null→remove）、451（keep_alive）、455（embedder）有写入；HEAD 零写入，且全仓库别处也没写（排它检查过）。
- **后果**：① 语义搜索配置（开关/baseUrl/model/apiKey/batchSize/threshold）不落盘，重启回默认；② 保活开关不落盘（`RouteActivity.kt:178` 启动时读到 false，保活不再拉起）；③ 子代理模型选择不落盘；④ **从备份恢复时这三项也不落地**（`restoreBeforeInitialization` 与 `update` 都走 `persistSettings`）。
- **修法**：补三行写回，`SUB_AGENT_MODEL` 用 `?.let{…} ?: remove(…)` 语义（对齐 `SELECTED_TTS_PROVIDER` 的写法）；补一个"键集合往返"单测锁住。

### 2-2 🟠 `ChatMessageTools.kt` 的 `isPending` 自相矛盾 ✅已复验（三代理独立报告）

- `:130` = `tool.approvalState is ToolApprovalState.Pending`（旧口径，取 ours）
- `:288` = `tool.isPending`（新口径，取 theirs）
- 两代理都给出上游 `2689e753` 两处**都是** `tool.isPending`、fork `7042fa80` 两处**都是**旧口径 → **只有合并结果是混合的**。
- **后果**：`cancelToolByUser`（`ChatService.kt:1043-1051`）现在只写 output、不再改 `approvalState` → 被停止生成掐断的工具 = "有 output + Pending" → `:130` 判为真 → **UI 仍渲染 ✓/✕**，点击被 `ChatService.kt:651-653` 的 `it.isPending` 守卫静默忽略（"死按钮 + 假对话框"）。
- **修法**：`:130` 改成 `val isPending = tool.isPending`（一行）。

### 2-3 🟠 被静默覆盖的 fork/旧上游行为（观感与语义类）

| # | 项 | 合并前 | 现在 | 复验 |
|---|---|---|---|---|
| a | **输入框 IME 形态** | `WindowInsets.isImeVisible` → 底角 `CornerSize(0.dp)` + `padding(bottom=0)`（键盘弹出时变直角并贴住） | 恒 `shapes.largeIncreased` + 恒 8dp（始终圆角浮起） | ✅（分叉点即有此写法，fork 从未改过 → 上游删除自动生效，**无人做过这个决定**） |
| b | **`<think>` 提取正则** | `Regex("<think>([\s\S]*?)(?:</think>\|$)", DOT_MATCHES_ALL)`：任意位置都提取 | `Regex("\\A\\s*<think>…")`：只认文本开头；且消息已有 Reasoning part 时整条跳过 | ✅（上游 1 次改动 / fork 0 次）**行内思考不再被抽成 reasoning 块，直接显示在正文** |
| c | **合成消息过模版** | 系统提示/注入/工具 systemPrompt 都过 Pebble 模版 | 标 `isSynthetic` → `TemplateTransformer` 跳过 | ✅（`942d0d28`）。**负面**：自定义 messageTemplate 不再作用于 system prompt；**正面**：系统提示里含 `{`/`%` 不再让 Pebble 报错 |
| d | **搜索信封样式** | 无 query 前缀行/参数药丸/日期行/外层 padding | 已回退（`528afe63`） | ✅ 已处理 |
| e | **`SearchMode.BUILT_IN` 成为死值** | — | 枚举保留但无生产者，`ChatPage.kt:385` 分支不可达；上游 6 个 `search_picker_*` 串成孤儿 | ⚠️ |
| f | **标题/建议模型配置** | 独立配置 | 改走快速模型（`ChatService.kt:1090`），旧键成孤儿 | ⚠️（非 bug，需写进更新日志） |

### 2-4 🟠 数据层：外来 v25 备份升到 v27 会开库失败 ⚠️未复验（逻辑硬）

`message_embeddings` 全仓库没有任何手写 `CREATE TABLE`（只 `Migration_11_12`/`Migration_14_15` 两处建表），它只由 `AutoMigration(24,25)` 的**编译期产物**创建。上游官方 v25 库有 `shell_compatibility_mode` 但**没有**这张表；走 `25→26`（只 ALTER memory 三列）→ `26→27`（只 ALTER workspaces）后 Room 校验会发现表缺失 → `IllegalStateException: Migration didn't properly handle: message_embeddings(…)`。**同一路径也出现在备份恢复**：`BackupManager.kt:142` 用完整迁移链打开暂存库。
**建议**：给 `Migration_25_26` 加 `CREATE TABLE IF NOT EXISTS message_embeddings(… 用 27.json 的 createSql 原文)`，并给三条 ALTER 加 `PRAGMA table_info` 探测（对称于 `Migration_26_27` 已做的）。

### 2-5 🟠 备份/恢复与开关语义的若干偏差 ⚠️

- **「覆盖确认」名不副实**：恢复只装入归档里存在的条目，设备上多出的文件**永不删除**；且 S3/WebDAV 的恢复**没有确认对话框**（本地导入有）。
- **诊断字段缩水**：`restore_diag.txt` 少 `userAvatar=` 与 `imageAssistantAvatars=N/总数` 的分母、少 `items=`；`uploadFiles` 口径从只数 `upload/` 变成含 `images/`（字段名未跟着改）。
- **marker 写入时机**：`BackupManager.kt:176-184` 在 `publish()` 后立即写（若下次启动 apply 失败回滚，marker 仍在 → 会对旧库跑一次 rebase；同包名下是空操作）。
- **`workspaces/` 仍不在主备份内**（已知设计，但恢复后 DB 里的工作区记录会全变 `BROKEN`，恢复报告里应显式提示）。

### 2-7 🔴 `shellCompatibilityMode` 没接进 fork 的流式 shell 路径（AI 工具拿不到开关）✅已复验

- **证据（逐跳）**：`WorkspaceShellRunner.kt:23` 字段默认 `false` → `WorkspaceTools.kt:308-330` 的 `workspace_shell` 覆写了 `executeFlow` → `GenerationLoop.kt:294` 只走 `executeFlow` → `WorkspaceRepository.kt:357-370` 的 `executeCommandStreaming` **签名里没有该参数** → `WorkspaceManager.kt:268` 组装 `WorkspaceShellContext` 时不传 → 落到默认 `false`。
- **对照**：同步路径 `executeCommand` 是对的（`WorkspaceRepository.kt:346` 传 `workspace.shellCompatibilityMode`，`WorkspaceManager.kt:244` 透传）。
- **复现命令**：
  ```bash
  grep -rn 'WorkspaceShellContext(' --include='*.kt' .        # 只有 2 处构造点，都在 WorkspaceManager.kt(:233 / :268)
  grep -rn 'shellCompatibilityMode' --include='*.kt' workspace/ app/   # 流式路径与 WorkspaceTools.kt 零出现
  ```
- **后果**：需要 `PROOT_NO_SECCOMP` 的设备上，**终端页与详情页命令框能跑，AI 的 `workspace_shell` 跑不起来**，症状极难归因。
- **修法**：给 `WorkspaceManager.executeCommandStreaming` 与 `WorkspaceRepository.executeCommandStreaming` 补参数并透传；**不要靠默认值糊过去**——默认值正是这个 bug 的成因（见 §3「双构造点」）。

### 2-8 🟠 工作区导出/导入丢 Shell 兼容模式 ⚠️

`WorkspaceBackup.Meta`（`WorkspaceBackup.kt:39-46`）没有 `shellCompatibilityMode` 字段，`importWorkspace`（`WorkspaceRepository.kt:410-419`）构造实体时走默认 `false` → 开了兼容模式的工作区导出再导入（含换机迁移）**开关静默变回关闭**。**注意**：`Meta` 的字段没有默认值，加字段必须写成 `= false`，否则旧 zip 反序列化会 `MissingFieldException`。

### 2-9 🟡 终端页的 bind mount 比 shell 工具少两个 ⚠️

权威表在 `FilesManager.kt:542-546`（`/skills`、`/tool_outputs`、`/upload`）；终端会话是**第三处独立硬编码**（`WorkspaceTerminalSession.kt:39-57`，只挂 `/workspace`、`/skills`、`/dev /proc /sys`）→ **AI 能读 `/upload/<file>`（提示词明确承诺），用户在终端 `ls /upload` 却是空的**。

### 2-10 🟡 其它 workspace 小项 ⚠️

- html/svg 预览按钮**硬编码中文**（`WorkspaceFileEditorPage.kt:94-96` 的 `"源码"/"预览"`），未走 `stringResource`；上游自带的 4 个终端无障碍串（`workspace_terminal_tab/_close_tab/_new_tab/_no_tabs`）**上游自己就没翻**（6 locale 里只有 en）。
- `file://` 链接点击崩（`FileUriExposedException`）**上游未修**（`Markdown.kt:706-727` 的 `else` 分支裸 `Intent`），且 `ImageLazyLoadTransformer.kt:93` 的降级路径**会主动造出**这类 URL（`file:///data/user/0/<pkg>/files/images/…`）→ 点 AI 生成图的链接就会崩。三次交接文档都挂着，本次仍未修。
- `Workspace` 域模型（`workspace/Workspace.kt:3-11`）与 `WorkspaceEntity` 已漂移（域模型没有 `shellCompatibilityMode`）；`WorkspaceDetailVM` 里还留着一套页面内命令执行器，与终端多 Tab 会话并存。

### 2-11 🟡 交叉确认：`settings` / `memory` / `ai` 三块的"半截采纳"

- **Exa 请求侧只进来一半**：输出侧 `publishedDate`/`highlights`/`retrievedAt` 已接；请求侧仍 snake_case 二选一（不发 `maxAgeHours`、不同时请求 text+highlights）→ 默认路径下模型拿到的证据比上游少（`items[].text` 是摘录拼接而非正文）。
- **`SearchMode.BUILT_IN` 死值**：枚举保留但无生产者，`ChatPage.kt:385` 分支不可达。
- **MCP 非法服务器名会 `messageQueue.pause()`**（上游版）；fork 原来只报错不暂停 → 用户需手动恢复队列（改 `ChatService.kt:854` 一行即可回退）。
- **记忆系统 100% fork 侧**（7 工具 / `<memories>` 注入 / 12 参 `buildMemoryTools`），**无半截**；上游的单 `memory_tool` 模型完全未采纳（这是有意为之）。
- **`SkillMetadata.allowedTools` 被静默丢弃**（fork 解析 `allowed-tools` 的两行没了），但 fork 对该字段**零消费**，无功能损失。



### 2-12 🟡 其它待定行为差异 ⚠️

- **网络自动重试默认开**：老装机默认获得重试，彻底失败的场景最多多等 7s。
- **审批语义变化**：从"点即打断前一个 job"改为"等前一个 job 结束"（`afterPreviousGeneration`）→ 生成中点审批可能表现为"点了没反应"。
- **`/tool_outputs` 每次冷启清空**，但指针已写进消息历史 → 跨重启的历史会话里 `cat /tool_outputs/xxx.txt` 会 404。（可达性本身自洽：`/tool_outputs` 在 rootfs bind mount 表里。）
- **32KB 落盘的是已被 100KB 裁剪过的文本**（`clipToolOutput` 在 `maybeTruncateToolOutput` 之内）→ 超 100KB 的输出模型 `cat` 也拿不到全量。
- **子代理链路绕过本轮所有新机制**：`SubAgentRuntime.kt:114-161` 无重试、无截断、无 `executeFlow` 流式、无 `sessionId`、无 `isSynthetic`（fork 自身缺口，非合并引入）。
- **`isEmpty()` 语义变化**：有附件无文字现在算非空 → `sendOnEnter` 下**只贴图片时回车会直接发送**。
- **MCP 非法服务器名会 `messageQueue.pause()`**（上游版），fork 原来只报错不暂停 → 用户需手动恢复队列。
- **`shouldUseExternalWebSearch` 让 `enableWebSearch` 对带 `BuiltInTools.Search` 的模型静默失效**；而选择器的"是否支持内置搜索"仍是 fork 的 modelId 启发式（`SearchPicker.kt:163-164`）→ 两者判据不同源（第三方网关跑 Responses 时看不到内置搜索开关）。
- **Exa 证据参数只进来一半**：输出侧 `publishedDate`/`highlights`/`retrievedAt` 已接；请求侧仍 snake_case 二选一（不发 `maxAgeHours`、不同时请求 text+highlights）。
- **Qwen Audio 3.0 TTS 配置结构变更且无迁移** → 老配置在 `QwenTTSProvider.kt:33-38` 撞 `require(...)`（不可重试），需手动改。
- **赞助商 provider 自动注入**：`DefaultProviders.kt:70`（APIMart）+ `RecommendedProviders.kt:48`（MaruCode）会被"补缺失默认项"循环补进用户列表；fork 的去品牌化意图在设置页守住了（弹窗/文档/赞助/分享仍为禁用或注释），但**列表里会多出条目**，且只在本地删会被补回。

---

### 2-13 🟡 横切面体检捞到的其它静默项

- **`.editorconfig` 被上游静默删除** ✅已复验：`4b6449e3` / `7042fa80` 都是 blob `0464e05e`（fork 从未改过），`2689e753` 与 HEAD **不存在**。这才是 memory `upstream-sync-procedure` 第 8 类的真身（原先举例的 `CLAUDE.md`/`.claude/skills` 其实被解冲突挡回去了）。影响：4 空格/120 列/XML-JSON 2 空格的约定丢失，`AGENTS.md` 里引用它的章节指向空文件。
- **`AGENTS.md` 被上游版整体顶替** ✅已复验：HEAD blob == 上游 `afb17630`，fork 版 `a9e08d86` 丢失（`git diff 7042fa80 HEAD -- AGENTS.md` = −24/+5）。丢的是：中文贡献者导言、`connectedDebugAndroidTest`、构建前提（`google-services.json` + `pnpm`）、Coding Style、Testing Guidelines。
- **`liveOutput` 有生产者、无 UI 消费者** ✅已复验：写入 `GenerationLoop.kt:235`，声明/合并 `UIMessagePart.kt:196/221`，**`ui/` 目录下读取点 0 个** → 交接文档 §5-①-5「工具实时输出：工作区 shell 应流式显示」**不可能通过**。注意：UI 读取端是**更早的 fork 提交 `c21225e1` 自己删掉的**，非本次合并引入 —— 要么确认"有意不做"并改文档，要么恢复。
- **`compose_compiler_config.conf` 有两条悬空** ✅已复验：`:15 UIMessageChoice`、`:16 MessageChunk`（两个类全仓已不存在），而替代者 `me.rerere.ai.ui.StreamChunk` **未声明** → 流式链路的稳定性声明缺失。
- **`baselineProfiles/{baseline,startup}-prof.txt` 两份完全相同（md5 一致）且过期** ⚠️：各 48545 行，`GenerationHandler` 6 处、`jlatexmath` 6 处、`StreamChunk` **0 处** → ART 静默忽略，**启动优化正好失效在本次重构最热的生成循环上**。
- **合并新增 5 个未翻译串** ⚠️：`workspace_terminal_{tab,new_tab,close_tab,no_tabs}`（上游 `3a533a6a`，上游自己就没翻）+ `assistant_page_context_message_limit_too_small`（`adf333ec`，且零引用）→ zh/ja/ko/ru/zh-rTW 下终端多 Tab 文案回落英文。
- **自定义 action/scheme 未按变体加后缀** ⚠️：`me.rerere.rikkahub.action.TRANSLATE`、`rikkahub://shortcut`、`…RESTART_KEEP_ALIVE` → release/debug/pre 同机共存时抢同一 intent。
- **`ExampleInstrumentedTest.kt:20` 断言旧包名**（`me.rerere.rikkahub` vs 实际 `xyz.lynsei.rikkahub.debug`）→ 一旦启用 connected 测试即红（CI 只跑 JVM 单测，所以一直不暴露）。
- `nightly-build-pre.yml` 的发布说明写死 `xyz.lynsei.rikkahub`（实际是 `.pre`）；7 个未使用 import（既有，仅 warning）。

**已推翻的 4 条代理误报**（记录以免后人重复怀疑）：❌「WorkManager 未初始化」—— `startKoin{ workManagerFactory() }` + manifest `tools:node="remove"` 正是 Koin 官方成对写法（Context7 已核）；❌「Google 内置 `ImageGeneration` 被 `else` 吞掉是合并回归」—— `7042fa80` 同逻辑；❌「`hasSummary` 死代码复活」—— 既有；❌「S3Sync/WebDavSync 的 fork 逻辑丢失」—— 实为移植进 `BackupManager.kt`。

## 3. 结构性风险 / 下次同步必踩清单

| 项 | 为什么必踩 | 现状防护 |
|---|---|---|
| 上游 HEAD 删 `gradle/libs.versions.toml`（`288a034c`，无替代、无 CI） | `build-logic/settings.gradle.kts:15` 仍 `from(files("../gradle/libs.versions.toml"))`，全仓 `libs.*` 引用 557 处 → **Gradle 配置阶段就红** | 坚持 pin 到上游最后一个 CI 绿提交；若上游永久删目录，fork 需自带一份 |
| `AppDatabaseFactory.kt:21-31` 迁移注册 | 上游版本只到 `Migration_15_16` | 每次同步后补 `Migration_25_26` + `Migration_26_27`（已踩过一次：已有装机开库即崩） |
| `app/schemas/25.json` 同名不同构 | 双方各自从 24 加不同内容（fork `message_embeddings` / 上游 `shell_compatibility_mode`） | 每次用 `identityHash` 三向比对确认取 ours |
| `BuiltinToolUIs.kt` 的 `SearchWebPreview` | 上游重写（query 前缀/参数药丸/日期行/LazyColumn 容器） | 现为 fork 定制版（`528afe63`）；**容器必须是非懒加载 `Column`**，否则点开即崩 |
| `huge-icons` / `haze` 钉版本 | 上游会带回 1.4 / beta02 | 见 memory `huge-icons-pinned-1-3`、`haze-pinned-alpha03`（各含必改的 2–3 处） |
| `.claude/skills` 被换成符号链接 + `CLAUDE.md` 被删 | 本机 `core.symlinks=false` → 退化成 17 字节文本文件 | 每次同步后还原实体目录（本次已还原 71 文件） |
| fork 的 keep 规则追加块贴在 `rikkahub.keep` 文件尾部 | 与上游的尾部编辑形成冲突块 → 人工取 ours 时**会吃掉上游的 keep 修复**（本次已吃掉一条 jlatexmath 规则，幸运的是死规则） | 建议把 fork 追加块挪到独立文件 `app/src/main/keepRules/fork.keep`（AGP 会合并同源集所有 `*.keep`） |
| `.gitignore:14` 的 `references` 过宽 | 裸模式匹配任意层级的 `references/` → 上游往 `.agents/skills/*/references/` 加文件会被静默忽略（已实测 `git check-ignore`） | 改成 `/references/` |
| `daily-build.yml` 已被删 | 上游仍有该文件 → 下次同步是 modify/delete 冲突 | 人工保留删除 |
| 13 处手工 `create("pre")` | 上游每加一个库模块都会漏 | 建议 `app/build.gradle.kts` 的 `pre` 加 `matchingFallbacks = listOf("release")`（零维护） |
| `videogen`/`oauth` 这类新模块的 `pre` 变体 | 同上（已踩：`assemblePre` 自合并起必红，`5b4b9715` 才修好） | 同上 |
| `ChatMessageTools.kt` / `ChatMessageCot.kt` / `ChatMessage.kt` | 上游持续在同一函数加分支（ServerToolStep / ask_user / isPending），fork 在同一区域有聚合思考块交互 | 逐 hunk 人工解，禁止整段 take-theirs |
| `richtext/Markdown.kt` | fork 相对上游改了 597 行（段落合并/缓存/INLINE_MATH），上游本次零改动 → **上游一碰就是巨型冲突** | — |
| `highlight/**` | fork 是 Prism+QuickJS 全量替换；同名 `Highlighter.kt`/`HighlightToken` 在两侧语义不同 → 任何半套同步都会重复声明 | 见 memory `highlight-prism-revert` |
| `Utils/UpdateChecker.kt` / `PlaceholderTransformer.kt` | fork 单方面"恢复被上游删除/禁用"的东西（`return@flow`、`cur_time`）→ 最易被静默吃掉 | — |
| `search/` 的 `withSingleKey` 有 `else -> this` | 上游再加带 apiKey 的渠道时会静默失去多 key 轮询（豆包先例） | 可改成 `error(...)`，由编译器兜底 |
| `WorkspaceShellContext` 的双构造点 + 字段默认值 | 上游给该 data class 加字段时，`WorkspaceManager.kt:268`（fork 的流式路径）**不报错、只静默漏传**（本次 `shellCompatibilityMode` 即如此） | 建议合并两个构造点为一个私有 `buildContext(...)`；或去掉该字段默认值，逼编译器报错 |
| 终端 bind mount 第三处硬编码 | `WorkspaceTerminalSession.kt:39-57` 自己拼挂载表，与 `FileFolders.ROOTFS_BIND_MOUNTS` 不同步 → 终端看不到 `/upload` | 改为消费同一常量 |
| `values*/strings.xml` ×6 | 双向重改，且删掉的串可能被上游代码重新引用（本次 `chat_message_tool_search_prefix` 先例） | 同步后跑 `R.string.*` vs `values/strings.xml` 全量比对 |

---

## 4. 更正既有交接文档中的错误

| 原说法 | 事实 |
|---|---|
| 「release / pre CI 未跑」 | `release` 已多次真跑且绿（`34618143445`/`bb88cd06`/`1a542d72`）；`pre` 在同步前**确实一次都没真跑过**（历史 `success` 是 `check` 判"24h 无提交"把 `build` 整个 skip 掉后的假绿），同步后首次真跑即红 |
| 「libs 目录里 `ratex`/`nav2`/`androidx-navigation2`/`haze-blur-materials` 与上游新增项并存」 | `nav2`/`androidx-navigation2` **已被上游删除且静默生效**（现仓库无，无引用故不影响编译） |
| 「`maybeTruncateToolOutput` / `/tool_outputs/` 是本次上游新增」 | 分叉点就有；是 **fork** 在做 Agora 流式改造时删掉换成 `clipToolOutput`，本次拿回上游实现 |
| 「`persistSettings` 覆盖了我们内联块的其余全部键」 | **错**：漏了 `SUB_AGENT_MODEL`/`EMBEDDER`/`KEEP_ALIVE_ENABLED`（见 §2-1） |
| 「记忆工具 11 参 / 13 参」 | 实为 **12 参**（8 回调 + json + memoryAssistantId + includeActiveEdit + includeSavedEdit），发 7 个工具（`MemoryTools.kt:30-49`） |
| 「§5-①-5 工具实时输出：工作区 shell 应流式显示」 | **做不到**：`liveOutput` 无 UI 消费者（§2-13），且 UI 端更早已被 fork 自己删掉 |
| memory `upstream-sync-procedure` 第 8 类举例「`CLAUDE.md`、`.claude/skills`」 | 举例不准（两者都被挡回）；**真身是 `.editorconfig`（已消失）与 `AGENTS.md`（被顶替）** |
| memory `signing-key-drift` 里「daily-build 的 `KEY_BASE64`/`SIGNING_CONFIG`、tag 互相覆盖」 | 已由 `61c0b26b` 解决（删 daily-build + daily 专用 secret；nightly 转正式 Release） |
| memory `memory-system-handoff-chain` 记 master `895a1f7f` | 现为 `61c0b26b`，其后 4 个提交（`c77fd1e7`/`5b4b9715`/`1a542d72`/`61c0b26b`）无 handoff 覆盖，已由本文档补上 |
| 「fork v26 = `message_embeddings` + 记忆三列」 | `message_embeddings` 是 **v25** 引入（`2e5560c0`，schema 由 `2cc45d99` 提交）；v26 只加记忆三列（`989ed7f8`） |

---

## 5. 设备核验清单（本次审计新增项）

按优先级（前 6 条对应 §2 的缺陷）：

1. **设置持久化**（验 §2-1）：改语义搜索配置 / 保活开关 / 子代理模型 → 杀进程重进，看是否保留；（更严）从备份恢复后看这三项是否落地。
2. **停止生成 + 待审批工具**（验 §2-2）：开启工具审批 → 触发需要审批的工具 → 停止生成 → 该行是否仍显示 ✓/✕，点了是否无效。
3. ~~**输入框键盘形态**（验 §2-3a）~~ —— ❌ **已更正（见文首「后续状态」）**：上游 `f86d6e82` 是有意改为圆角+间距，不作缺陷，无需再验。
4. ~~**行内 `<think>`**（验 §2-3b）~~ —— ❌ **已更正（见文首「后续状态」）**：上游 `85402745` + 7 个单测定义了「行内标签应保留」，不作缺陷，无需再验。
5. **自定义消息模版**（验 §2-3c）：用包 role 的模版，看 system prompt 是否还被包裹；顺带确认系统提示含 `{{` 不再报错。
6. **连续审批 / 生成中点审批**（验 §2-6）：点击后是否有"按钮立刻变灰"的反馈；会不会卡住。
7. **外来备份**（验 §2-4）：若能拿到一份官方 rikkahub v25 备份，走一次恢复，看是否被拒。
8. 上游新功能抽验：消息队列（生成中发消息/编辑/撤回/恢复）、语音模式（入口只在 ASR 支持服务端 VAD 时出现）、工作区 shell 兼容模式 + 终端多 Tab + html/svg 预览、服务端工具步骤渲染（Claude web_search / Gemini googleSearch）。
9. `enableWebSearch` 门控：用带内置搜索的模型（Claude/Gemini/GPT Responses）开"联网搜索"，确认是走内置搜索而不是两者都没有。
10. `sendOnEnter` + 只贴图片：回车是发送还是换行。
11. **Shell 兼容模式**（验 §2-7）：在需要 `PROOT_NO_SECCOMP` 的设备上打开开关，让 AI 跑 `workspace_shell`，看是否与终端页/详情页命令框表现一致。
12. **工作区导出/导入**（验 §2-8）：开兼容模式 → 导出 → 删除 → 导入，看开关是否保留。
13. **终端挂载一致性**（验 §2-9）：终端里 `ls /upload` 与 AI 侧 `workspace_shell` 看到的挂载是否一致。

---

## 6. 待你决策的项

1. §2-1 / §2-2 / §2-3b/§2-3a 这几条**要不要现在修**（都是 1–3 行的小改动，改完走 CI）。
2. `enableWebSearch` 对带内置搜索的模型失效：接受上游语义，还是要求"总是外挂"（改 `ChatToolFactory.kt:28` 一处）。
3. `SearchMode.BUILT_IN` 死值：删掉还是把上游的模式卡补回来。
4. 赞助商 provider（APIMart/MaruCode）是否要从 `DEFAULT_PROVIDERS`/`RecommendedProviders` 移除。
5. `.gitignore` 的 `references` → `/references/`。
6. fork 的 keep 规则追加块是否拆成独立文件（降低下次同步的静默吃掉概率）。
7. `pre` 变体是否改用 `matchingFallbacks` 一劳永逸。
8. 工作区 Shell 兼容模式的流式路径要不要修（§2-7，约 10 行）。
9. 是否消除 `WorkspaceShellContext` 的双构造点（结构性防复发，§3）。

---

## 附录 A：共用证据清单（本次生成，可直接复用）

```
D:\Temp\rikkahub-audit\upstream_commits.txt      157 条上游提交（sha + subject）
D:\Temp\rikkahub-audit\fork_commits.txt          475 条 fork 自有提交
D:\Temp\rikkahub-audit\post_merge_commits.txt    合并后 fork 自身 15 条（--first-parent）
D:\Temp\rikkahub-audit\upstream_files.txt        上游改动 422 文件
D:\Temp\rikkahub-audit\fork_files.txt            fork 改动 398 文件
D:\Temp\rikkahub-audit\both_touched_files.txt    双方都改过的 68 文件（冲突面）
D:\Temp\rikkahub-audit\both_touched_numstat.tsv  上面 68 文件的 增/删 行数（fork vs 上游）
D:\Temp\prefs_key_audit.py                       Settings 键 声明/读取/写入 三集合严格比对脚本
D:\Temp\apk_signer.py                            从任意 APK 抽取 v2 签名者证书 SHA-256 指纹
```

生成命令：

```bash
A=/d/Temp/rikkahub-audit
git log --oneline --no-merges 4b6449e3..2689e753 > $A/upstream_commits.txt
git log --oneline --no-merges 4b6449e3..7042fa80 > $A/fork_commits.txt
git log --oneline --first-parent 7042fa80..HEAD  > $A/post_merge_commits.txt
git diff --name-only 4b6449e3..2689e753 | sort > $A/upstream_files.txt
git diff --name-only 4b6449e3..7042fa80 | sort > $A/fork_files.txt
comm -12 $A/fork_files.txt $A/upstream_files.txt > $A/both_touched_files.txt
```

## 附录 B：审计范围与未覆盖

**已覆盖**：ai/oauth、生成循环与工具装配、工具信封 UI、workspace/终端/沙箱、数据层与 Room 迁移、备份/恢复/云同步、记忆/搜索/MCP/技能、构建/依赖/模块/CI、设置与偏好、聊天界面与视觉、跨文件横切体检。

**未覆盖 / 仅浅查**：`web-ui/`（React 前端）、`web/`（内嵌 Ktor 服务端路由）、`document/`（MuPDF）、`speech/` 细节（仅由 UI 审计者子代理浅查）、`videogen/` 内部实现、`app/src/androidTest/**`（CI 从不编译）。
