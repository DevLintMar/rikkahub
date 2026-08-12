# 交接文档：图片懒加载 + read_image 工具 + 引用计数回收 — 下一阶段入口

**日期**：2026-08-12
**目的**：上下文清理前的完整交接。新会话读本文档即可续接。master HEAD = `e90f260`，工作树干净，debug CI 全绿（`31573308753` success，headSha `e90f260` 核对一致）。

---

## 0. 一句话概况

本阶段围绕**图片懒加载体系**：参考 Claude Code 的 Read 图片语义，用户附加图片**默认不进上下文**，只注入 URL 标记（`file:///upload/xxx`），AI 通过 `read_image` 工具按需读取（视觉模型返回图片本体、非视觉模型并行 OCR）。过程中排查并修复了**"重新生成含图消息 → 图片变占位/被删"**的根因链，最后给 upload 附件加了**引用计数回收**（删除对话/消息自动清理无引用图片）。master `3964b01`（上一交接）→ `e90f260`（24 commits）。全程 CI 绿（中间多轮红均为编译/测试错已修）。

---

## 1. 已完成工作（commit 链）

### 一、压缩上限 + 编码缓存（60d88f8）

- `FileEncoder.compressAndEncode` 默认 `maxDimension` 10000 → **4000px**（16MP / quality 85 / 强制 JPEG / GIF 原样不变）
- file:// 分支加**纯内存 LRU 编码缓存**（`ImageEncodeCache`，32 条/16MB 双限，key=path+length+mtime）——多轮请求不再反复解码压缩同一张图；`data:`/`http` 分支不缓存

### 二、图片懒加载主体（9433e54 → 310c129）

- **`ImageLazyLoadTransformer`**（新，input transformer）：发送侧把 file:// 图片替换为文本标记
  ```
  [The user attached N image(s) to this message:
  - file:///upload/xxx.jpg
  The image contents are NOT included automatically. Call the `read_image` tool with these URLs if you need to see them.]
  ```
  - **只处理 USER role** 消息（assistant 出图/工具结果图片保持原样，避免重新生成含 assistant 图消息时被替换成标记）
  - **只作用于发送副本**（GenerationHandler.generateInternal 的 internalMessages），不写回会话状态，消息本体 Image part 保留（UI/导出/多图合并零影响）
  - `resolveDisplayPath`：upload 目录 → `file:///upload/<name>`、workspace → `file:///workspace/<rel>`、其余回退自身 file:// URL；**upload 根 canonical 化**（Android /data/data ↔ /data/user/0 符号链接差异导致 `==` 失效的坑）；文件不存在 → `[Image]` 占位
  - inputTransformers 移除 `OcrTransformer`（全历史 OCR 改由 read_image 按需承担）；`performOcr` 保留给工具复用（CancellationException 重抛、未配置模型明确错误文本）

### 三、read_image 工具（9433e54 起多轮演进 → e24d8d0 定型）

- **恒注册无开关**（afae4e7 去工作区门控）：`file:///upload/...` 全局可解析（无工作区也行），`file:///workspace/...` 与 rootfs 路径才需要工作区
- 参数 `urls`（array of string，必填），单次上限 8 张（`READ_IMAGE_MAX_IMAGES_PER_CALL`）
- **支持三种 URL**：`file:///upload/...`（直接解析）、`file:///workspace/...` 或绝对路径（rootfs 读字节落 upload/）、`http(s)://`（下载到 upload/，8MB 上限）
- **结果统一 JSON 信封**（工具返回单 Text part）：
  ```json
  { "type": "read_image", "note"?: "...",
    "results": [{ "url": "...", "mode": "base64"|"ocr"|"error", "text"?: "..." }] }
  ```
  - 视觉分支：`mode=base64`，图片以 Image part 返回（模型真能看到）+ 信封
  - 非视觉分支：`coroutineScope + async` **并行 OCR**（`OcrTransformer.performOcr`，3 天 LRU 缓存），`mode=ocr` + 文本
- 路径解析 `resolveImageFileUri`：`/upload/` → filesDir/upload + canonicalFile 防穿越；http 下载（Koin OkHttpClient）；rootfs 用 `rootfsFileSize`/`exportRootfsFile`（8MB 上限）
- 文件落盘用 `FilesManager.createChatFilesByByteArrays(bytes, extension)`（**带扩展名参数**，保留原图扩展名）
- 子代理自动继承（进 baseTools，RESTRICTED_TOOLS 不含 read_image）

### 四、read_image 专属信封 UI（e24d8d0）

- **`ReadImageToolUI`**：`HugeIcons.Image03`（与图像生成页画廊 tab 一致）、标题"识别图片"（流式调用中与完成一致，loading 动画）、折叠小字显示图片数/失败数 + 首个 OCR 文本预览、详情信封渲染图片列表（高度 120dp、宽度按 painter.intrinsicSize 原比例、间距 8dp）+ 每图卡片（base64/OCR/失败 pill + URL + 识别文本/错误）
- 概览缩略图由 ChatMessageTools 统一渲染（折叠 64dp），Summary 只留文本概览（去重）
- 字符串 ×6：`chat_message_tool_read_image` / `_count` / `_failed` / `_failed_label` + `error_tool_unable_read_images`

### 五、重新生成占位符/删除 bug 排查链（310c129 → e90f260）

用户报告"重新生成含图消息后图片变占位/被删"。排查发现**两层根因**：

1. **消息节点定位失败清空会话**（fad8db8）：`regenerateAtMessage` 用 `equals`（`node.messages.contains`）定位消息节点，UI 传入实例与 session 最新实例字段（usage/annotations）不等 → 定位 null → `indexOf(null)=-1` → `subList(0,0)` 清空会话 → `checkFilesDelete` 连带删全部图片文件。修复：**改用 id 匹配**（`getMessageNodeByMessageId`）+ node==null 时 addError 中止 + `checkFilesDelete` 空会话防御
2. **belt 误删保护 → 引用计数回收**（3ccbbd1 → e90f260）：
   - `checkFilesDelete` **跳过 upload 附件**（belt）：自动清理永不触碰用户附件（代价：单条消息删除的图片不再回收）
   - 引用计数回收（72e59cf）：删除对话/消息后对失去引用的 upload 附件做全库引用检查（`message_node.messages LIKE %uuid%`），无引用才物理删除；`deleteConversation` 改用引用计数（多会话共享图不误删）
   - **排除当前会话**（e90f260，关键）：日志显示 `refs=1` 但用户确认未共享——引用来自**当前会话自身残留节点**（删除与取消中的生成 job 并发交错，被取消 job 的 `onCompletion` 兜底保存把删除前含图快照写回 DB）。`countMessageNodesContaining` 加 `excludeConversationId`，只统计其他会话
- 诊断日志写入**请求日志设置页**（Logging.log → recentLogs 100 条）：`belt skipped`（上传附件缺失引用）、`FILES_DELETE`（文件真删+全栈）、`deleteMessage: lost`、`cleanup: N candidate` + `refs=N -> DELETE/KEEP`——设备上无需 logcat 即可定位

### 六、信封 UI 修复（e6b6530 → e3eca99）

- 详情图片不截断：120×160 容器 + Fit（e6b6530）→ 改进为高度 120dp、宽度按原比例（painter.intrinsicSize，48~240dp 范围，无左右留白，e3eca99）
- 残留懒加载标记清理：`Conversation.stripLazyLoadImageMarkers()` + `checkInvalidMessages` 末尾统一剥离（标记前缀 `IMAGE_LAZY_LOAD_MARKER_PREFIX` 顶层 const，transformer 与清理共用）

## 2. 关键设计决策（认知遗留）

### 图片懒加载模型
- **懒加载是"发送态替换"**：transformer 只作用于发给模型的副本（internalMessages），消息本体 Image part 恒保留 → UI 显示/导出/多图合并/重新生成都不受干扰。这是本阶段最核心的设计决策。
- **标记与工具统一 URL 语义**（1ee4b8d）：给 AI 的标记展示 `file:///upload/xxx`（不是手机绝对路径、也不是裸 `/upload`），工具参数就叫 `urls`，AI 原样回填即可。
- **恒注册**（1ee4b8d/afae4e7）：read_image 不随工作区开关——`/upload` 挂载在无工作区时依然存在（proot 只在工作区开启时绑定，但文件解析是纯 filesDir 路径，不依赖工作区运行）。
- **模型行为差异放工具 description**：视觉/OCR 分支说明写在 read_image 的 description，标记对所有模型统一（用户确认）。

### 上传附件生命周期（重要）
- **`checkFilesDelete` belt**：upload 附件不随会话更新自动删除（防重新生成/懒加载误判引用误删），靠设置页手动清理或删除动作的引用计数回收。
- **引用计数回收**：删除对话/消息的明确动作后，`cleanupUploadFilesIfUnreferenced(files, excludeConversationId)` 查全库（排除当前会话）无引用才删。
- **并发残留陷阱**：取消中的生成 job 的 `onCompletion` 兜底保存（ChatService.kt:812-820）会用删除前快照写回 DB → 当前会话自身出现残留引用 → 引用检查必须排除当前会话。

### 工具 UI 机制
- 工具注册即插即用：新工具在 createXxxTools 定义，UI 在 ToolUIRegistry 注册 ToolUIRenderer；output 里的 Image part 无需专属 renderer 自动渲染（ChatMessageTools 抽图 + 64dp 缩略图）。
- 详情信封图片按 `painter.intrinsicSize` 原比例（高度固定、宽度随比例）。

## 3. git 状态 / CI

- 分支 `master`，HEAD = `e90f260`，工作树干净
- **debug CI 全绿**：`31573308753` success（headSha `e90f260` 核对一致）
- 本阶段 CI 判定链多轮红均为编译/测试错已修：60d88f8→9433e54→…→e90f260（详见各 commit 备注）

## 4. 恢复地图

| 台账/文档 | 路径 |
|---|---|
| 上一交接（文件夹体系） | `docs/superpowers/handoffs/2026-08-09-chat-folders-tools-next-phase.md` |
| 本阶段核心文件 | `data/ai/transformers/ImageLazyLoadTransformer.kt`、`data/ai/tools/ReadImageTools.kt`、`data/ai/transformers/OcrTransformer.kt`（performOcr 复用）、`data/repository/ConversationRepository.kt`（cleanupUploadFilesIfUnreferenced / filterUnreferencedUploadUrls / uploadFileNameOrNull）、`data/db/dao/MessageNodeDAO.kt`（countMessageNodesContaining）、`service/ChatService.kt`（regenerateAtMessage id 匹配 / checkFilesDelete belt / deleteMessage 回收 / stripLazyLoadImageMarkers）、`ui/components/message/tools/BuiltinToolUIs.kt`（ReadImageToolUI）、`ai/src/main/java/me/rerere/ai/util/FileEncoder.kt`（4000px + 缓存） |
| 相关 memory | `memory-system-handoff-chain`（本文档为最新入口） |

## 5. 待办 / 挂起项（按优先级）

### ① 设备核验（唯一需用户上手）

装 `e90f260` debug 包：
1. **懒加载主流程**：发带图消息 → AI 收到 URL 标记（`file:///upload/xxx`，非手机绝对路径）；让 AI 调 read_image → 信封出图（图标 Image03、标题"识别图片"、图片按比例宽度）+ AI 能描述内容；多轮对话图片不重发
2. **重新生成**：发图 → 等回复 → 对该消息重新生成 → 图片保留不占位、AI 正常回复（belt + id 匹配双保险）
3. **引用计数回收**：删含图消息（无共享）→ 请求日志 `refs=0 -> DELETE` + 文件管理里图片消失；构造两会话共享同图 → 删一个会话 → 另一会话图片仍在
4. **http 图片**：让 AI 传 https:// 图片链接给 read_image → 下载读取
5. **非视觉模型**：read_image 返回并行 OCR 文本（`<image_file_ocr>` 包裹）
6. **无工作区助手**：read_image 仍可用（`file:///upload` 全局）；传 workspace 路径 → 明确报错
7. **Gemini 视觉模型**：read_image 出图是否真被模型看到（`$ref` 工具结果通道）——重点验证，不行则 Gemini 降级 OCR 分支
8. **无工具能力模型 + 有图会话** → 弹"无法查看图片"提示

### ② 遗留 Minor / defer（不阻塞）

- **release / pre CI 未跑**：convention plugin + rikkahub.keep + optimization 混淆路径未验证（历史挂起延续）
- **诊断日志仍在**（belt skipped / FILES_DELETE / cleanup refs / ChatImg / ScrollFrameSampler）：设备确认后清理
- **引用计数 LIKE 查询性能**：`countMessageNodesContaining` 全表 LIKE 扫描，会话多时删除操作可能略慢——当前规模可接受；量大可考虑缓存文件名索引
- **历史挂起**（更早交接延续）：Firecrawl crawl/batch、Jina 多 URL、Exa ids 复用、Metaso scope、乱召回、Task 12 ripgrep、UpdateChecker `return@flow`

### ③ 下一阶段候选（未开工）

无明确用户需求。可选项：设备核验、诊断日志清理、release/pre CI 验证、或用户新需求。

## 6. 技术约束 / 惯例（必须遵守）

- **本机无编译器**：不运行 gradle。静态编写 + review，编译验证全靠 CI。
- **CI 判定铁律**：先 push 再 `gh workflow run nightly-build-debug.yml --repo DevLintMar/rikkahub --ref master`；**`gh run watch`** 实时监控（不手写轮询）；`gh run view --json conclusion,headSha` 核对 headSha；**结果无论红绿主动汇报**。
- **JVM 单测风格**：testImplementation 只有 junit 4.13.2，无 kotlin-test；`org.junit.*`；类型断言 `assertTrue(is)` + cast；方法名反引号内禁 `/`。**androidx.toUri/toFile 是 stub**——单测里涉及文件路径映射用纯 JVM 字符串拼接，别用 toUri。
- **`checkFilesDelete` belt**：upload 附件不随会话更新自动删除；删除动作走 `cleanupUploadFilesIfUnreferenced(files, excludeConversationId)`。
- **引用计数排除当前会话**：`countMessageNodesContaining(fileName, excludeConversationId)`——并发取消 job 的兜底保存会在当前会话残留引用，必须排除。
- **ImageLazyLoadTransformer 只处理 USER role**；只作用于发送副本（不写回 session.state）。
- **标记前缀**：`IMAGE_LAZY_LOAD_MARKER_PREFIX = "[The user attached "`（Conversation.kt 顶层 const），transformer 注入与 strip 清理共用，改动需同步。
- suspend 内并发用 try/catch，重抛 `CancellationException`；runCatching 不包 suspend；工具层只调 Repository/FilesManager 公开 API。
- 字符串六 locale（values/zh/zh-rTW/ja/ko-rKR/ru）；工具 description/注入文本/JSON 用英文。
- 中文 conventional commit；文件删除走 `~/.claude/scripts/trash.sh`；force-push 需用户明确要求。
- 本机 git 换行警告（LF→CRLF）为仓库既有状态，非错误。

## 7. 停靠点

- **已完成**：图片懒加载体系全部落地（4000px 压缩 + 编码缓存 / 发送侧替换标记 / read_image 工具 + 信封 UI / belt 保护 + 引用计数回收 / 重新生成占位符根因修复）。master `e90f260`，debug CI 全绿。
- **待确认**：设备核验（§5-①，重点是重新生成不占位、引用计数回收、Gemini 出图）。
- **下一阶段**：无明确需求；候选 = 设备核验、诊断日志清理、release/pre CI 验证。
- **恢复动作**：读本文档 §4/§5；开始前先让用户设备核验懒加载闭环。
