# 交接文档：file:// 沙箱路径统一 + 工具结果图片通道 + 附件回收与下载超时 — 下一阶段入口

**日期**：2026-09-11（当日第二份；上一份为 `2026-09-11-read-image-retry-folder-drag-tilde-render-fix-next-phase.md`）
**目的**：上下文清理前的完整交接。新会话读本文档即可续接。master HEAD = `8c1259a`，工作树干净，debug CI 全绿（`34600695014` success，headSha `8c1259a` 核对一致）。

---

## 0. 一句话概况

本阶段四件事 + 一个跟进修复：① `file://` 路径语义定型为**「根 = 工作区沙箱根」**，markdown 两条渲染路径与 `read_image` 共用同一个解析器，沙箱内任意路径（`/workspace`、`/tmp`、`/skills`…）均可引用，**真机路径一律不解析**；② `read_image` 放宽 URL 解析、图片类型改按**文件头**识别、并把工作区内的图复制进 upload 再引用；③ 修掉「多模态模型识图报 `invalid input`」——根因是 **Chat Completions 的 tool 消息 content 只能是文本**，图片必须旁挂到 tool 消息之后的 user 消息；④ 修掉「删 AI 回复把图片清理掉」——按附件**文件名**而非 URL 字符串判定失去引用；⑤ 跟进：图片下载加 **20 秒总超时且超时不再重试**（原先最坏要挂约 40 分钟）。master `3e1b3ca`（上一交接）→ `8c1259a`（6 commits），22 files changed, 1037 insertions(+), 346 deletions(-)；沿途 1 轮 CI 红当场定位修复，最终绿。

---

## 1. 已完成工作（commit 链）

### 一、file:// 路径语义：根 = 工作区沙箱根（2fb9153）

**用户定的规则（本阶段最重要的一条约束）**：**`file://` 下的根目录就是工作区沙箱的根 `/`，不允许访问真机路径。**

- [WorkspaceFileUrlResolver.kt](app/src/main/java/me/rerere/rikkahub/data/files/WorkspaceFileUrlResolver.kt) **从 `ui/components/richtext` 移到 `data/files`**：它不再是 UI 专属逻辑，markdown 渲染与 `read_image` 工具共用（旧文件与其单测已 trash）
- 解析规则与 [WorkspaceManager.resolveRootfsPath](workspace/src/main/java/me/rerere/workspace/WorkspaceManager.kt) 对齐（两处改动需同步）：
  | 逻辑路径 | 宿主落点 | 需要 workspaceId |
  |---|---|---|
  | `/upload/<name>` | `<filesDir>/upload/<name>` | **否**（bind mount，没开工作区也能读） |
  | `/skills/...`、`/tool_outputs/...` | 对应 bind mount 宿主目录 | **否** |
  | `/workspace/<rel>` | `<filesDir>/workspaces/<id>/files/<rel>` | 是 |
  | 其余绝对路径（`/tmp/chart.png`、`/etc/...`、`/root/...`） | `<filesDir>/workspaces/<id>/linux/<path>` | 是 |
- **拒绝**（返回 null）：非绝对路径/空串、http/https/data/content 等其它 scheme、**内核伪文件系统**（`/proc`、`/dev`、`/sys`，只能走 shell）、**真机（宿主）绝对路径**（含应用自己的私有目录 `file:///data/user/0/<pkg>/files/...`）、需要工作区但没给 workspaceId
- 防路径穿越：`canonicalFile` 后必须仍在逻辑根内（同时挡住指向根外的符号链接）；百分号解码 `%XX`，**不解码 `+`**（路径中 `+` 合法）
- **单一来源**：新增 [FileFolders.ROOTFS_BIND_MOUNTS](app/src/main/java/me/rerere/rikkahub/data/files/FilesManager.kt)（`/skills`→`skills`、`/tool_outputs`→`tool_outputs`、`/upload`→`upload`），[RepositoryModule](app/src/main/java/me/rerere/rikkahub/di/RepositoryModule.kt) 由它生成 PRoot `-b` 参数，解析器也读它 → 挂载表不再两处各写一份；`WorkspaceManager` 的 `WORKSPACES_BASE_DIR` / `FILES_DIR` / `LINUX_DIR` 开放为公开常量
- [Markdown.kt](app/src/main/java/me/rerere/rikkahub/ui/components/richtext/Markdown.kt) 只是换 import + 更新注释（`LocalWorkspaceFileProvider` 与两条渲染路径的解析点本就在，见 §2「本阶段纠正的一个认知」）
- 单测 11 → 17 例（新增：沙箱其它绝对路径、bind mount、内核 fs 拒绝、真机路径拒绝、**「解析结果永远落在 filesDir 内」安全不变量**）

### 二、Chat Completions 工具结果图片改由 user 消息随附（5a7a2d8）

**根因（任务 3 `invalid input`）**：OpenAI Chat Completions 规范限制 `role: "tool"` 消息的 `content` **只能是文本**（API reference: "The content for these messages is restricted to text format"）。`89f8c4b`（2026-07-06）把 `image_url` 内容块塞进了 tool 消息 → 服务端直接 400 拒收，报错形如 `Invalid input`（`param` 指向 `messages.N.content`）、`Invalid content type`，本地推理服务还有 `Invalid 'messages' in payload`。

**反直觉之处（现象解释）**：非视觉模型走的是文本占位分支，恰好合法；**只有支持图片输入的模型才会踩中** —— 所以现象看起来像「支持视觉反而更坏」。

修法（[ProviderMessageUtils.kt](ai/src/main/java/me/rerere/ai/provider/providers/ProviderMessageUtils.kt) 新增共享决策 + [ChatCompletionsAPI.kt](ai/src/main/java/me/rerere/ai/provider/providers/openai/ChatCompletionsAPI.kt) 调用）：
- tool 消息只输出文本（图片位置留占位说明 `TOOL_RESULT_IMAGE_ATTACHED_NOTE`；模型不支持图片输入时给 `TOOL_RESULT_IMAGE_OMITTED_NOTE`）
- 图片紧接着**全部** tool 消息之后作为**一条** user 消息随附 —— OpenAI 要求 `tool_calls` 的所有响应消息紧跟其 assistant 消息，中间插入其它 role 会让整个请求非法，因此同一组（并行）工具调用的图片必须**合并**到一条 user 消息、且晚于所有 tool 消息
- 决策逻辑放 `ProviderMessageUtils` 供单测直接锁死（`ToolResultImageChannelTest`，5 例），防止回退
- 顺带：图片编码失败原本写入**空 text 块**（部分服务端拒收空文本），改为可读的错误说明并写请求日志；[ClaudeProvider](ai/src/main/java/me/rerere/ai/provider/providers/ClaudeProvider.kt) 同样处理
- **未改**：Anthropic 的 `tool_result` 内嵌 `image` 是官方支持；Google 走 `functionResponse` + `$ref` 绑定媒体；OpenAI ResponseAPI 走 `function_call_output.output` 数组

### 三、read_image 路径放宽 + 文件头识别 + 附件回收修复（2b5f7a2）

**read_image（任务 1/2）**（[ReadImageTools.kt](app/src/main/java/me/rerere/rikkahub/data/ai/tools/ReadImageTools.kt)）：
- URL 统一走 `WorkspaceFileUrlResolver`：`/upload` 全局可用（**无需工作区**），沙箱内任意绝对路径在**传入 workspaceId** 后可用
- **两个注册点原先恒传 `workspaceId = null`**（[ChatService.kt](app/src/main/java/me/rerere/rikkahub/service/ChatService.kt) 两处），导致工作区路径**永远解析不了** —— 这是任务 2 的核心缺口，已修
- **图片类型改按文件头识别**（`sniffImageExtension`：png/jpg/gif/webp/bmp/heic/avif）：原先按 URL 后缀判断（`READ_IMAGE_EXTENSIONS` 集合），AI 与网页给的 URL 常常没有扩展名、带 query（`?w=100`）或后缀与内容不符 → 合法图片被直接拒掉。下载的扩展名同样改按内容判断（原实现 `error` 掉非图片内容，现在明确报「Downloaded content is not an image」）
- **工作区内的图复制一份进 upload 再引用**（不直接引用工作区原文件）：工具结果的 Image part 会进 `Conversation.files`，删除消息时清理逻辑**只对 upload 目录留手**（`checkFilesDelete` 的 belt）→ 直接引用会删掉工作区里的原文件
- 提示词（[WorkspaceReminderTransformer](app/src/main/java/me/rerere/rikkahub/data/ai/transformers/WorkspaceReminderTransformer.kt)）：说明可用 `file://` 内联显示工作区任意文件/图片（图片显示为图，其它文件变成可点链接）
- 每张图的解析结果（含实际路径与原因）写入请求日志页

**附件回收（任务 4）**（[ConversationRepository.lostUploadUrlsAfterDelete](app/src/main/java/me/rerere/rikkahub/data/repository/ConversationRepository.kt)）：
- 用户报告：删 AI 回复（未删自己的）后图片被清理，重新生成时图没了
- 根因链：删 AI 回复会连带丢掉 `read_image` 工具结果里的那张图，而工具结果保存的 URL 与用户消息里的**可能只是同一文件的两种拼写**（`context.filesDir` 经 `/data/user/0` ↔ `/data/data` 符号链接，`canonicalFile` 化所致；该坑在 [ImageLazyLoadTransformer.kt:75-77](app/src/main/java/me/rerere/rikkahub/data/ai/transformers/ImageLazyLoadTransformer.kt) 有注释记录）→ 原实现按 URL 字符串做差集 → 判成「失去引用」→ 引用计数又**排除当前会话**（`countMessageNodesContaining(fileName, excludeConversationId)`）→ `refs=0` → 物理删除
- 修法：按 upload **文件名**判定失去引用（其余文件仍按 URL 精确比较），纯函数 + 5 例单测（含两个拼写方向）

### 四、请求日志页记录失败响应体并折叠 base64（6f91268）

[RequestLoggingInterceptor.kt](app/src/main/java/me/rerere/rikkahub/data/ai/RequestLoggingInterceptor.kt)：
- 非 2xx 响应把**响应体**记进日志条目（8KB 上限，`peekBody` 只读副本、不消耗真正的响应流）。原先只记状态码，而 provider 报错的原因（哪个字段、哪个参数路径不对）**只在 body 里** —— 这次排查 `invalid input` 就吃过这个亏
- 请求体里的**长 base64 折叠**（≥2048 连续 base64 字母表字符）：带图请求原本会在日志页糊成一堵墙并撑爆保存的日志；占位符不含引号与反斜杠 → 仍是合法 JSON，日志页的 `JsonTree` 照常解析；超长请求体截断到 64KB
- `compactLoggedBody` 纯函数，8 例单测（含「折叠后仍是合法 JSON」）

### 五、图片下载 20 秒总超时，超时不再重试（8c1259a）

**根因（用户报「传 http 图片 URL 一直卡住」）**：不是「没有超时」，而是**超时配置错配**：

| 项 | 值 |
|---|---|
| 全局 OkHttp 客户端 `connectTimeout` | 20s |
| 全局 `readTimeout` | **10 分钟**（那是给流式 LLM 响应用的） |
| 全局 `callTimeout` | 无 |
| 自动重试 | 4 次 |

对「TCP 连得上、但不返回内容」的图片（挂死的图床、tarpit、极慢的源），`connectTimeout` **完全失效**（连接已建立）→ 一次下载挂 10 分钟 × 4 次重试 ≈ **最坏 40 分钟**，表现即「一直卡住」。

修法：
- 下载走**独立的图片客户端**（`getKoin().get<OkHttpClient>().newBuilder().callTimeout(20, SECONDS)`，共享全局客户端的连接池与调度器）：`callTimeout` 覆盖 DNS、建连、TLS、重定向与读正文，正是这里需要的时间预算
- [Retry.kt](app/src/main/java/me/rerere/rikkahub/utils/Retry.kt) 新增 `retryIf: (Exception) -> Boolean = { true }` 谓词（默认全重试，OCR 侧行为不变）；下载传 `retryIf = { !isTimeoutFailure(it) }` → **超时直接失败不重试**，其余瞬时错误（连接被重置、5xx）仍重试 3 次
- `isTimeoutFailure(error) = error is InterruptedIOException`：按**类型**判定而非 message 文本（OkHttp 的 `callTimeout` 与读超时都抛它，`SocketTimeoutException` 是子类）
- 超时文案明确（含 20s 与 URL）并写入请求日志页
- **顺带收益**：协程取消原本也打断不了阻塞的 `execute()`（`withContext` 要等 block 返回），`callTimeout` 现在同时给「停止生成」提供了时间上界
- **内部实现坑（已在代码注释中记明）**：`retryOnFailure` 的「不重试」分支**必须 throw** —— `repeat` 会继续下一轮，只跳过延迟等于换个方式静默重试（无延迟、无日志）

---

## 2. 关键设计决策（认知遗留）

### file:// 路径语义（本阶段定型，最重要）
- **`file://` 的根 = 工作区沙箱根 `/`，真机路径一律不解析**（用户明确要求）。含义有两层：① 模型给不出、也读不到沙箱外的任何文件；② **设备形状的路径不是「特例」**，只是沙箱里不存在的路径 —— 唯一需要专门挡住的是「伪装成 Rootfs 路径、实则落在应用私有目录」的输入（符号链接两种拼写都认）
- **本阶段纠正的一个认知**：任务 1 描述为「支持 file:// 图片」，但**该功能 2026-08-08 已做**（见 `2026-08-08-markdown-file-url-ci-tests-next-phase.md`：`LocalWorkspaceFileProvider` + `MarkdownBlock(workspaceId)` + 两条渲染路径各自的解析点）。本阶段的实际缺口是**前缀只有 `/workspace` 与 `/upload` 两个**，以及 `read_image` 恒传 `null` workspaceId。**下次遇到「看起来是新需求」的同类任务，先 grep 既有实现再动手**
- **解析器放 `data/files` 而非 `ui/`**：markdown 与工具必须共用同一套规则，否则会出现「markdown 能显示、read_image 说找不到」这类分裂
- **工作区内的图必须复制进 upload**：`Conversation.files` 会包含工具结果里的 Image part，而删除消息的清理只对 upload 目录留手 → 直接引用工作区原文件会被删掉。**新增「工具返回本地文件引用」的功能时都要考虑这一点**

### 工具结果图片通道（跨 provider 的硬约束）
- Chat Completions：tool 消息 content **只能文本**，图片旁挂到**全部 tool 消息之后**的 user 消息；同一组并行工具调用的图片必须**合并**进一条（否则 tool 响应的连续性被破坏，整个请求 400）
- Claude：`tool_result` 内嵌 `image` 块是官方支持，不必旁挂
- 详细约束与出处见 memory `chat-completions-tool-image-channel`

### 附件生命周期（延续上一阶段，本阶段补一层）
- 判定「失去引用」要按 **upload 文件名**比较，不能按 URL 字符串 —— 同一物理文件可能有多种拼写。**只要还有「同一文件两种拼写」的可能（符号链接、canonicalFile、路径 rebase），字符串比较就是错的**
- 引用计数仍**排除当前会话**（并发取消 job 的兜底保存会在当前会话残留引用）—— 这条不变

### 超时与重试
- **超时是「不该重试」的失败类型**：连不上/不响应的目标重试只是再等一轮。瞬时错误（连接被重置、5xx）才值得重试
- **`callTimeout` 才是有意义的整体预算**：`connectTimeout` 挡不住「连上但不回数据」，`readTimeout` 在共享客户端里被流式场景撑到 10 分钟
- 给单个功能收紧超时用 `newBuilder()` **派生客户端**（共享连接池/调度器），不要改动全局客户端 —— 全局那些大超时是给流式 LLM 用的

---

## 3. git 状态 / CI

- 分支 `master`，HEAD = `8c1259a`，工作树干净
- 本阶段范围：`3e1b3ca`（上一交接）→ `8c1259a`，6 commits，**22 files changed, 1037 insertions(+), 346 deletions(-)**
- **debug CI 全绿**：`34600695014` success（headSha `8c1259a` 核对一致）
- 本阶段 CI 红→绿记录：

| run | sha | 结果 | 说明 |
|---|---|---|---|
| `34598086658` | 6f91268 | ❌ failure | 单测 1 例失败（237 tests, 1 failed）：`WorkspaceFileUrlResolverTest > 设备上的绝对路径不解析` |
| `34598624916` | 8e5ff35 | ✅ success | 换成「解析结果永远落在 filesDir 内」不变量后绿 |
| `34600695014` | 8c1259a | ✅ success | — **绿** |

**那次红是我自己的断言写错，不是实现错**（值得记住的教训）：
断言写成「`file:///data/user/0/<pkg>/files/...` 恒为 null」，但实现只在它**符号链接解析后落在应用私有目录内**时才拒绝 —— CI 上 `filesDir` 是临时目录，该路径不在其中 → 按 Rootfs 逻辑路径落到 linux 区 → 断言失败。实现是对的（与「file:// 的根是沙箱根」一致），错的是把「设备形状」当成了特例。改成真正的安全性质：**任何输入（设备绝对路径、伪装私有目录路径、穿越尝试、bind mount、Rootfs 各前缀）解析出的 File 都必须落在 filesDir 内**。教训：**跨平台单测里别断言「某条真机路径的样子」，断言实现真正保证的性质。**

---

## 4. 恢复地图

| 台账/文档 | 路径 |
|---|---|
| 上一交接（read_image 重试 + 文件夹拖拽 + 单波浪删除线） | `docs/superpowers/handoffs/2026-09-11-read-image-retry-folder-drag-tilde-render-fix-next-phase.md` |
| 再上一份（markdown file:// 初版实现） | `docs/superpowers/handoffs/2026-08-08-markdown-file-url-ci-tests-next-phase.md` |
| 再上一份（图片懒加载 + read_image 工具） | `docs/superpowers/handoffs/2026-08-12-image-lazy-load-refcount-next-phase.md` |

| 本阶段核心文件 | 说明 |
|---|---|
| `data/files/WorkspaceFileUrlResolver.kt` | **新增**（从 ui/ 移入并扩写）：`file://` → 宿主 File 的唯一解析入口（markdown + read_image 共用） |
| `data/ai/tools/ReadImageTools.kt` | 重写：URL 解析放宽、文件头识别、20s 超时客户端、诊断日志 |
| `utils/Retry.kt` | `retryIf` 谓词 + `isTimeoutFailure`；「不重试」分支必须 throw |
| `ai/.../providers/ProviderMessageUtils.kt` | tool 结果文本/图片的共享决策（`toolResultText` / `toolResultImagesForUserMessage`） |
| `ai/.../providers/openai/ChatCompletionsAPI.kt` | tool 消息只出文本 + 图片旁挂 user 消息（合并、晚于所有 tool 消息） |
| `data/repository/ConversationRepository.kt` | `lostUploadUrlsAfterDelete`（按文件名判定失去引用） |
| `service/ChatService.kt` | 两处 read_image 注册点补传 workspaceId；deleteMessage 改用新函数 |
| `data/ai/RequestLoggingInterceptor.kt` | 失败响应体 + base64 折叠 |
| `data/files/FilesManager.kt` | `FileFolders.ROOTFS_BIND_MOUNTS` 单一来源 |
| `di/RepositoryModule.kt`、`workspace/.../WorkspaceManager.kt` | 常量开放 + 挂载表由单一来源生成 |
| `ui/components/richtext/Markdown.kt` | 换 import/注释（两条渲染路径的解析点本就在） |
| `data/ai/transformers/WorkspaceReminderTransformer.kt` | 提示词：file:// 可内联任意沙箱文件/图片 |

| 本阶段单测 | 例数 |
|---|---|
| `data/files/WorkspaceFileUrlResolverTest.kt`（新位置） | 17（含安全不变量） |
| `data/ai/tools/ToolResultImageChannelTest.kt` | 5 |
| `data/ai/RequestLoggingInterceptorTest.kt` | 8 |
| `data/ai/tools/ReadImageToolsTest.kt`（改为只测文件头识别） | 6 |
| `data/repository/UploadCleanupTest.kt`（+失去引用判定） | 11 |
| `utils/RetryTest.kt`（+retryIf/超时） | 7 |

| 相关 memory | 说明 |
|---|---|
| `memory-system-handoff-chain` | 本文档为最新入口 |
| `chat-completions-tool-image-channel` | **本阶段新增**：各 provider 工具结果媒体的约束与出处 |
| `markdown-parser-whitebox-debug` | 本机 JDK+kotlinc 白盒验证链路（本阶段扩展用法见 §6） |

---

## 5. 待办 / 挂起项（按优先级）

### ① 设备核验（唯一需用户上手）

装 `8c1259a` debug 包：

**file:// 沙箱路径**
1. 绑工作区的助手，让 AI 发 `![x](file:///workspace/xxx.png)` → 图片内联显示；`file:///tmp/xxx.png`（工作区 shell 里生成到 /tmp 的图）同样显示
2. 含 HTML 的消息（表格/`<div>` 等走 `MarkdownNew` 路径）里的 file:// 图片同样显示
3. AI 引用 `file:///upload/...`（用户附件）→ 显示

**read_image**
4. 视觉模型调 `read_image` 看用户附加图 → **出图且不再报 `invalid input`**（本次修复的直接目标，重点）
5. 让 AI 读工作区内的图（`/workspace`、`/tmp`）→ 成功；无工作区助手读 `/workspace/...` → 明确报错而非静默失败
6. 传一个**访问不到/极慢的 http 图片 URL** → **约 20 秒内失败**（不再是卡住），请求日志页出现 `timed out after 20s, not retrying`；传一个正常网页图片 → 正常出图
7. 请求日志页：失败请求能看到**响应体**里的错误原因；带图请求的 base64 已折叠成 `<<N chars of base64 omitted>>` 且 JSON 树仍可展开
8. 非视觉模型 / OCR 路径仍工作（本次未改，但 retryOnFailure 改了内部结构）

**附件回收**
9. 发图 → AI 用 read_image 看过该图 → 删 **AI 回复**（保留自己的消息）→ 图片**仍在**，重新生成正常
10. 真失去引用的图（删掉唯一引用它的消息）→ 请求日志 `refs=0 -> DELETE`，文件管理里消失

**上一阶段遗留（仍未核验）**
11. 单波浪删除线：`**重点内容**是~哈哈~` 显示纯文本 `~哈哈~`（无反斜杠、无删除线）；`~~删除线~~` 正常
12. 文件夹 chip 长按拖拽排序，顺序重启后保持；单击已选中文件夹弹重命名/删除菜单
13. 图片懒加载闭环：URL 标记 `file:///upload/xxx`、read_image 信封 UI、重新生成不占位、引用计数回收、Gemini 视觉 read_image 出图（`$ref` 通道，重点）

### ② 本阶段发现但**未改**（用户明确「不动了」）

- **OCR 调用没有时间上界**：`OcrTransformer.performOcr` → `provider.generateText` 走全局客户端（10 分钟 readTimeout × 4 次重试）。与本次修好的 http 下载不同，这条路径仍可能长时间卡住。两条修法（未选）：
  - 给 [`Call.await()`](common/src/main/java/me/rerere/common/http/Request.kt) 补 `continuation.invokeOnCancellation { cancel() }` —— **现在协程被取消时底层 HTTP 请求不会取消，会泄漏在后台**，补上后即可给 OCR 套 `withTimeout` 并真正中断（改动最小，且顺带修掉「停止生成不生效」）
  - 或给 OCR 单独一个收紧超时的客户端（要往 provider 层透传参数，改动较大）
- **HTML 渲染路径点 `file://` 链接会崩**：`MarkdownNew.kt` 的 `"a"` 分支用裸 `Intent`（AnnotatedString 路径已用 FileProvider + `FLAG_GRANT_READ_URI_PERMISSION`）。Android 7+ 会 `FileUriExposedException`。修它需要把 context 透传进**非 composable** 的 `appendHtmlInlineElement`（builder lambda 里读不了 `LocalContext.current`），因此没顺手做
- **`ImageLazyLoadTransformer` 的降级路径**：对 upload/workspaces 之外的图（如 `images/`）会回退成**设备绝对路径**标记，按新规则 `read_image` 读不到（会明确报错、不会越界读设备文件）。用户附加的图都在 upload，不受影响
- **BMP 能识别但发不出去**：`FileEncoder.guessMimeType()` 没有 BMP 分支（`42 4D`），会抛 `Failed to guess MIME type`；Android 全版本支持 BMP 解码，补一行即可打通
- **AVIF 在 Android 8～13 上解码失败**（API 34+ 才支持），目前会在编码阶段抛模糊错误；可在识别阶段给明确提示
- **`FileEncoder.supportedTypes` 是死代码**（全仓库无引用），别被它误导

### ③ 遗留 Minor / defer（延续）

- **release / pre CI 未跑**：convention plugin + rikkahub.keep + optimization 混淆路径未验证（历史挂起延续）
- **诊断日志仍在**（belt skipped / FILES_DELETE / cleanup refs / ChatImg / ScrollFrameSampler / 本阶段新增的 read_image 与 hoisted-image 日志）：设备确认后清理
- **引用计数 LIKE 查询性能**：`countMessageNodesContaining` 全表 LIKE 扫描
- **历史挂起**（更早交接延续）：Firecrawl crawl/batch、Jina 多 URL、Exa ids 复用、Metaso scope、乱召回、Task 12 ripgrep、UpdateChecker `return@flow`

### ④ 下一阶段候选（未开工）

无明确用户需求。可选项：上表 ② 的 OCR 超时/取消（推荐，与本次同源）、设备核验、诊断日志清理、release/pre CI 验证。

---

## 6. 技术约束 / 惯例（必须遵守）

### 工程流程
- **本机无 Android 编译器**：不运行 gradle。静态编写 + review，编译验证全靠 CI。
- **CI 判定铁律**：先 push 再 `gh workflow run nightly-build-debug.yml --repo DevLintMar/rikkahub --ref master`；**`gh run watch <id> --exit-status`** 实时监控（不手写轮询）；`gh run view <id> --json conclusion,headSha` 核对 headSha；**结果无论红绿主动汇报**。
- **JVM 单测风格**：`testImplementation` 只有 junit 4.13.2，无 kotlin-test；`org.junit.*`；类型断言 `assertTrue(is)` + cast（无 assertInstanceOf）；方法名反引号内禁 `/`。`androidx.toUri/toFile` 是 stub。
- **跨平台单测铁律**（本阶段红的教训）：断言实现**真正保证的性质**，别断言真机路径的样子；需要 POSIX 语义时用 `assumeTrue(File("/data").path.startsWith("/"))` 守卫或改用不变量。
- 中文 conventional commit；字符串六 locale；工具 description/注入文本/JSON 用英文。
- 文件删除走 `~/.claude/scripts/trash.sh`（绝不 `rm`）；force-push 需用户明确要求。
- 本机 git 换行警告（LF→CRLF）为仓库既有状态，非错误。

### 本机白盒验证链路（本阶段扩展了用法，很值）
本机**没有 Android 编译器，但有 JDK 和 kotlinc**，可以给**纯逻辑**做真实编译 + 运行验证（比纸上推演可靠得多，本阶段靠它抓出两处自己的错）：

```bash
JDK="D:/IDEA/gradleRepository/jdks/jetbrains_s_r_o_-21-amd64-windows.2"
KOTLINC="D:/Temp/kotlinc-dist/kotlinc/bin/kotlinc.bat"
# 1) 把要验证的生产源码抽成 ASCII-only 副本（丢掉含中文的注释行）——
#    kotlinc 在本机对 UTF-8 源文件里的中文会解析错乱，这一步是必需的
grep -v '[^ -~]' path/to/File.kt > D:/Temp/verify/File.kt
# 2) 给外部依赖写 stub（例如 WorkspaceManager 的常量、FileFolders 的表）
# 3) 编译 + 运行
"$KOTLINC" -cp . Stub.kt File.kt Checks.kt -include-runtime -d check.jar
"$JDK/bin/java" -cp "check.jar" pkg.ChecksKt
```
- 本阶段用它在 `D:\Temp\verify2\` 跑了三组：解析器 + 文件头嗅探 + 失去引用判定（50 项）、日志折叠（17 项）、重试控制流（13 项）—— **全部 ALL OK**
- **更有价值的是它抓出的错**：① 我断言 100k 个 `x` 会触发截断，实际它被 base64 正则折叠了（测试前提错）；② 一条 Windows 语义不成立的断言。**harness 红的时候先怀疑前提，别急着改实现**
- 编码坑：本机 JVM stdout 是 GBK（读输出要 `| iconv -f GBK -t UTF-8`）；**用 Write 工具写的 UTF-8 源文件里的中文字符串会被 kotlinc 解析错乱** → 输入与断言全用 ASCII，中文场景交给 CI

### 图片/路径相关（本阶段沉淀）
- **`file://` 的根 = 工作区沙箱根 `/`，真机路径不解析**；沙箱外的图模型读不到（明确的错误，不是静默失败）
- **工具返回本地文件引用必须落进 upload**，否则删除消息的清理会删掉原文件（`checkFilesDelete` 只对 upload 留手）
- **判定「同一文件」按 upload 文件名，不按 URL 字符串**（符号链接/路径 rebase 会产生多种拼写）
- **图片类型按文件头判断**，不看扩展名/URL 后缀
- **给单个功能收紧超时用派生客户端**（`newBuilder()`），别动全局（10 分钟 readTimeout 是给流式 LLM 的）
- 工具结果媒体的 per-provider 约束见 memory `chat-completions-tool-image-channel`

### 代码风格
- suspend 内并发用 try/catch，重抛 `CancellationException`；`runCatching` 不包 suspend。
- 工具层只调 Repository/FilesManager 公开 API。
- 使用请求日志页（`Logging.log`）而非 logcat 做设备诊断。
- `retryOnFailure` 的「不重试」分支**必须 throw**（`repeat` 会继续下一轮）。

---

## 7. 停靠点

- **已完成**：`file://` 沙箱路径语义定型与统一解析器、read_image 路径放宽 + 文件头识别、Chat Completions 工具结果图片通道修复（`invalid input` 根因）、删除消息附件回收按文件名判定、请求日志记失败响应体 + base64 折叠、图片下载 20s 总超时且超时不再重试。master `8c1259a`，debug CI 全绿。
- **待确认**：设备核验（§5-①，重点：视觉模型 read_image 不再 `invalid input`、慢/死链 http 图片 20 秒失败、删 AI 回复图片仍在、沙箱内 `/tmp` 图片能内联显示）。
- **下一阶段**：无明确需求；推荐候选 = §5-② 的 **OCR 调用超时/取消**（与本次同源，且顺带修掉「停止生成不生效」）、设备核验、诊断日志清理、release/pre CI 验证。
- **恢复动作**：读本文档 §4/§5；开始前先让用户设备核验本阶段改动。
