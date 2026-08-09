# 交接文档：会话文件夹体系（新建落当前文件夹 + 工具 folder 过滤 + 历史删除确认 + list_conversation_folders 工具） — 下一阶段入口

**日期**：2026-08-09
**目的**：上下文清理前的完整交接。新会话读本文档即可续接。master HEAD = `20d7475`，工作树干净，debug CI 全绿（`31265608863` success，headSha `20d7475` 核对一致）。

---

## 0. 一句话概况

接上一交接 `10da3a0`（markdown file:// + CI 单测）。本阶段围绕**会话文件夹体系**五件事：**① 新建聊天落在抽屉当前 focus 的文件夹**（folderId 贯穿导航参数到会话创建）；**② `recent_chats` / `conversation_search` 加 `folder_id` 过滤参数**；**③ 历史页右滑删除加二次确认**（含一次根因修复：reset 取消以 currentValue 为 key 的协程）；**④ 新增 `list_conversation_folders` 工具 + 专属信封 UI + 创建文件夹重名校验**；**⑤ 演进：「聊天」文件夹 id 统一为 `default`、`folder_id` 支持空串/`default`、创建拦截默认名**。master `10da3a0` → `20d7475`（8 commits）。全程 CI 绿（中间一轮红已修复）。

---

## 1. 已完成工作（commit 链）

### 一、新建聊天落在当前 focus 的文件夹（需求）

**需求**：抽屉有"聊天"（未归类）+ 自定义文件夹分组。之前 focus 在其它文件夹时，新建聊天仍创建为未归类。期望新建到当前 focus 文件夹。

| commit | 内容 |
|---|---|
| b23d8e9 | folderId 贯穿导航参数：`Screen.Chat` / `navigateToChatPage` / `ChatPage` / `ChatVM` 加 `folderId`；`ChatService.initializeConversation` 新建分支写 folderId（仅新建生效，已存在会话不受影响）；`ChatPageContent` TopBar 新建读 activity 级 `ChatDrawerVM.selectedFolderId`；`ChatDrawer` 删除当前会话后自动新建同样带上；切换助手不传（助手切换时 selectedFolderId 已重置 null） |

### 二、工具 folder_id 过滤参数（需求）

**需求**：给 recent_chats 和 search conversation 加参数，仅搜索当前文件夹内容（缺省全部）。

| commit | 内容 |
|---|---|
| 944ac98 | DAO 两查询加可空 folderId（`(:folderId IS NULL OR folder_id = :folderId)`）+ 新增 `getConversationIdsInFolder`；Repository 三方法加 folderId；`searchConversationMessages` 有 folder 时融合后按 conversationId 过滤 + 候选不足循环放大 fetchLimit（16→256，4 轮）保 has_more 正确；工具加 folder_id（可选，UUID），非法/缺失忽略为全部 |

### 三、历史页右滑删除二次确认（需求）

**需求**：聊天历史右滑删除加二次确认。

| commit | 内容 |
|---|---|
| e1afb00 | 右滑后条目回位 + 父级 AlertDialog 确认才删（保留 Undo）；六 locale 字符串 ×2 |
| cddbbb7 | **根因修复**：上版在 `LaunchedEffect(dismissState.currentValue)` 内先 `reset()` 再 `onRequestDelete()` —— `reset()` 是 suspend 动画，动画让 currentValue 变回 Settled → LaunchedEffect 以它为 key 的协程被取消 → 回调永不执行 → 右滑后不弹窗也不删。改用 `SwipeToDismissBox` 的 `onDismiss` 回调（内部 `LaunchedEffect(settledValue, onDismiss)` 在条目真正滑出时回调一次）+ `rememberCoroutineScope` 独立协程 reset 回位；onDismiss lambda 用 `remember` 固定避免重复回调 |

### 四、list_conversation_folders 工具 + 专属信封 UI + 创建文件夹重名校验（需求）

**需求**：让 AI 列当前文件夹时它不知道当前文件夹 id → 在开启"参考历史记录"工具时提供 list_conversation_folders，列出所有聊天文件夹并标注当前（= 当前会话所在文件夹，用户确认此定义）；工具要有独特信封 + 概览小字，图标 FolderClock。另：创建文件夹不允许与当前助手已有文件夹重名（跨助手允许）。

| commit | 内容 |
|---|---|
| 53b07c5 | `createConversationTools` 签名加 `folderRepo` + `conversationId`（ChatService 两处调用点更新）；新工具 `list_conversation_folders`：列全部文件夹（含默认未归类条目）+ `current_folder_id`/`is_current` + `conversation_count`；`ListConversationFoldersToolUI`（`HugeIcons.FolderClock` 图标已从 jitpack 1.3 jar 验证、折叠下方小字列文件夹当前高亮、详情信封名称/当前/对话数/ID）+ ToolUIRegistry 注册；`chat_message_tool_list_folders` / `tool_ui_folder_*` 字符串 ×6 |
| cde0055 | `ChatDrawerVM.isFolderNameTaken`（忽略大小写查当前助手已有文件夹名）+ 创建对话框 confirm 先预检——重名弹 toast 不关闭、通过则创建关闭；`chat_page_folder_duplicate` 字符串 ×6 |
| 082808a | **编译修复**（CI 一轮红 31262665767 抓出）：`getConversationIdsInFolder` 只在 DAO 上，Repository 未暴露 → ConversationTools 调用 Unresolved + 级联类型推断失败；补 Repository 公开方法（委托 DAO，解析 Uuid）。`put("current_folder_id", currentFolderId ?: JsonNull)` 推断为 Any → 改 `JsonPrimitive(it) ?: JsonNull` |

### 五、演进：「聊天」id=default + folder_id 支持空串/default + 创建拦截默认名（需求）

**需求**：未归类文件夹名改"聊天"、id 写 "default"；不允许创建名为"聊天"的文件夹；给 AI 提供 id 访问未归类文件夹（否则不知输入什么 id）。

| commit | 内容 |
|---|---|
| 20d7475 | `list_conversation_folders`：默认条目 `id="default"`、`name="Chat"`；`current_folder_id` 未归类时返回 `"default"`（AI 可直接回填）。**`ConversationFolderScope` 枚举**（All/Unfiled/Folder）统一语义：`folder_id` 缺失=全部、`""`或`"default"`=「聊天」、UUID=指定、非法=全部（容错）；Unfiled→`folder_id=""`。UI 信封 isDefaultFolder 改判 `id=="default"`，显示名用 `chat_page_folder_default`；删 `tool_ui_folder_unfiled`。`isFolderNameTaken` 拦截默认名（当前 locale 标签 + 英文 Chat，忽略大小写） |

**CI 判定链**（每 run 核对 headSha）：
`31258041588` success（e1afb00）→ `31259271468` success（cddbbb7）→ `31262665767` **failure**（cde0055：ConversationTools 编译错）→ `31262964669` success（082808a）→ `31265608863` success（20d7475）。

---

## 2. 关键设计决策（认知遗留）

### 文件夹模型
- **「聊天」= 未归类**：`folder_id` 为空串即默认「聊天」文件夹，是应用内真实存储形态；`default` 只是工具层暴露给 AI 的**固定逻辑 id**（非 UUID），Repository 翻译 `Unfiled → folder_id=""`。`ConversationFolderScope`（All/Unfiled/Folder）是这三方法共用的过滤语义，DAO 层仍用可空 folderId。
- **跨助手允许重名**：文件夹表无唯一约束，重名只在创建时应用层预检（`isFolderNameTaken`）；查重与创建同协程顺序执行，无并发窗口。
- **默认名保留**：`chat_page_folder_default` 六 locale 已存在（聊天/Chat/Чат/채팅/チャット）；拦截创建 "聊天" 用当前 locale 标签 + 英文 "Chat" 双查（忽略大小写），避免中文用户改英文标签就绕过去。

### 导航参数贯穿
- **folderId 走导航参数而非 CompositionLocal**：新建会话的归属是路由级语义（ChatService 初始化时用），与渲染层无关；默认 null 兼容全部既有调用点（web 路由 `initializeConversation(uuid)` 等）。
- **ChatPageContent 读 activity 级 ChatDrawerVM**：`val drawerVm: ChatDrawerVM = koinViewModel(viewModelStoreOwner = LocalContext.current as ComponentActivity)`——selectedFolderId 是抽屉 UI 状态，activity 级 VM 让 Chat 页与抽屉共享同一实例。
- **Koin 4.2.2 `params.get(1)`**：ParametersHolder **没有 `getOrNull(index)`**（源码核对 4.2.2 tag：只有 `get(i)` + 无参 `getOrNull()`）。ChatPage 是 ChatVM 唯一创建点且恒传 2 参，index 1 安全。

### SwipeToDismissBox 陷阱（重要）
- **`onDismiss` 是正确回调点**（源码：内部 `LaunchedEffect(state.settledValue, onDismiss)`，settled 到非 Settled 时回调一次），**不是** `LaunchedEffect(currentValue)`——后者会被 reset 动画取消。
- **onDismiss lambda 必须 `remember` 固定**：否则重组时 lambda 变新实例 → 内部 LaunchedEffect 的 onDismiss 键变化 → 对同一已滑出状态重复回调。
- **`reset()` 是 suspend**，在 rememberCoroutineScope 独立协程调，与弹确认框并行不互斥。

### 工具与 UI 信封
- **工具注册即插即用**：新工具在 `ConversationTools.createConversationTools` 定义，UI 在 `ToolUIRegistry` 注册对应 `ToolUIRenderer`；未注册自动 fallback 默认 JSON 详情。
- **payload name/id 用英文**（`name="Chat"`、`id="default"`），UI 显示层再按 locale 翻译（`chat_page_folder_default`）；description 里写清 `folder_id` 语义（"default"/""=聊天、UUID=指定、省略=全部）。
- **AI 引导靠 description 而非提示词注入**：list_conversation_folders 描述明确"Pass the obtained `current_folder_id` to recent_chats/conversation_search as `folder_id`"，AI 拿到 id 就知道怎么用。

### CI 教训
- **跨层调用链先在 Repository 层核对暴露**：工具直接调 `conversationRepo.xxx` 时，确认该方法是 Repository 公开 API 而非仅 DAO 上有——否则 Unresolved + 一串类型推断级联错误（本阶段 31262665767 教训）。
- **`put(key, x ?: JsonNull)` 类型是 Any**：String? 与 JsonNull 共同超类型非 JsonElement，kotlinx-serialization `put` 要 JsonElement——用 `JsonPrimitive(it) ?: JsonNull`。

---

## 3. git 状态 / CI

- 分支 `master`，HEAD = `20d7475`（`feat(ai): 「聊天」文件夹 id 统一为 default…`），工作树干净
- **debug CI 全绿**：`20d7475` → `31265608863` success（headSha 核对一致）
- release / pre 工作流**仍未跑**（见 §5-②）

## 4. 恢复地图

| 台账/文档 | 路径 |
|---|---|
| 上一交接（markdown file:// + CI 单测） | `docs/superpowers/handoffs/2026-08-08-markdown-file-url-ci-tests-next-phase.md` |
| 本阶段核心文件 | `data/ai/tools/ConversationTools.kt`（list_conversation_folders + folder_id 语义 + parseFolderScope）、`data/repository/ConversationRepository.kt`（ConversationFolderScope / 三方法 / getConversationIdsInFolder / search 放大循环）、`ui/components/message/tools/BuiltinToolUIs.kt`（ListConversationFoldersToolUI）、`ui/components/message/tools/ToolUI.kt`（ToolUIRegistry）、`ui/pages/history/HistoryPage.kt`（onDismiss + AlertDialog）、`service/ChatService.kt`（initializeConversation folderId + createConversationTools 调用点）、`ui/pages/chat/ChatDrawerVM.kt`（isFolderNameTaken）、`utils/ChatUtil.kt` + `RouteActivity.kt`（Screen.Chat folderId） |
| 相关 memory | `memory-system-handoff-chain`（本文档为最新入口） |

## 5. 待办 / 挂起项（按优先级）

### ① 设备核验（唯一需用户上手）

装 `20d7475` debug 包：
1. **新建落当前文件夹**：抽屉 focus 文件夹 A → 顶部 `+` 新建 → 新会话在 A 内；focus"聊天"新建仍在"聊天"；在 A 内删当前会话 → 自动新建的也在 A
2. **工具 folder_id**：`recent_chats`/`conversation_search` 不传 folder_id → 全部；传 `"default"` 或 `""` → 仅「聊天」（未归类）；传文件夹 UUID → 仅该文件夹
3. **list_conversation_folders**：绑定助手会话让 AI"列出我的聊天文件夹" → 折叠 FolderClock 图标 + 下方小字列文件夹（当前高亮+标记）；点击信封：名称/当前/对话数/id（「聊天」条目显示"聊天" + id `default`）；让 AI 用返回的 `current_folder_id` 回填 folder_id 调 recent_chats → 只返回该文件夹
4. **历史页右滑**：右滑 → 确认框 + 条目回位；确认删除（Undo 可恢复）/取消不删/可再次右滑
5. **创建文件夹拦截**：输入与已有同名（含大小写）→ toast 不关闭；输入"聊天"/"Chat" → toast；不同助手同名 → 允许
6. **上阶段遗留**（2026-08-08 交接 §5-①）：工作区图片内联 file://、/upload 引用、工作区导出/导入、滚动性能、多图合并、MCP 开关 + 新模型

### ② 遗留 Minor / defer（不阻塞）

- **release / pre CI 未跑**：convention plugin + `rikkahub.keep` + `optimization{enable}` 在混淆路径未验证（2026-08-08 交接 §5-②延续）。
- **历史挂起**（2026-08-07 交接 §5-②）：Firecrawl crawl/batch、Jina 多 URL、Exa ids 复用、Metaso scope；乱召回（语义搜索 bug）、Task 12 ripgrep artifact 流水线、UpdateChecker.kt 删 `return@flow`。
- **诊断日志仍在**（ScrollFrameSampler + ChatImg，debug-only）：真机确认后清理。
- **图片区滚动 slow16-33 仍偏高**（30fps 级）：已可接受；继续压方向 = 组合侧。

### ③ 下一阶段候选（未开工）

无明确用户需求。可选项：设备核验、release/pre CI 验证、诊断日志清理、历史挂起清理、或用户新需求。

## 6. 技术约束 / 惯例（必须遵守）

- **本机无编译器**：不运行 gradle。静态编写 + review，编译验证全靠 CI。
- **CI 判定铁律**：`gh run list` 权威判定 + **核对 headSha**。流程：先 push 再 `gh workflow run nightly-build-debug.yml --repo DevLintMar/rikkahub --ref master`。**CI 结果无论红绿都要主动汇报**（Monitor 工具脚本覆盖"完成/失败/查询出错"三态，终态单条通知）。
- **JVM 单测风格**：testImplementation 只有 junit 4.13.2，**无 kotlin-test**；`org.junit.*`；类型断言 `assertTrue(is)` + cast；方法名反引号内禁 `/`。
- **Koin 4.2.2**：ParametersHolder **无 `getOrNull(index)`**——用 `params.get(i)`（索引参数）。
- **material3 SwipeToDismissBox**：删除确认用 `onDismiss` 回调（remember 固定 lambda）+ 独立协程 `reset()`；**勿**用 `LaunchedEffect(currentValue)`。
- **ConversationFolderScope 语义**：`All`=全部、`Unfiled`=「聊天」（folder_id=""）、`Folder(uuid)`=指定；工具层 `folder_id`：缺失=全部、`""`/`"default"`=「聊天」、UUID=指定、非法=全部。
- **跨层调用前核对 Repository 暴露**：工具层只允许调 ConversationRepository/FolderRepository 公开方法，DAO 方法须先补 Repository 包装。
- **kotlinx-serialization `put` 要 JsonElement**：null 值用 `JsonPrimitive(x) ?: JsonNull`。
- **跨层 UI→服务状态桥避免**：本次用「当前会话所在文件夹」而非抽屉 focus 状态判定当前文件夹（用户确认），避免 UI 状态跨层暴露。
- 字符串双写 en+zh；六 locale 全写（values/zh/zh-rTW/ja/ko-rKR/ru）；工具 description/JSON 字段用英文。
- runCatching 不能包 suspend；suspend 用 try/catch + 重抛 `CancellationException`。
- 中文 conventional commit；文件删除走 `~/.claude/scripts/trash.sh`；force-push 需用户明确要求。
- 本机 git 换行警告（LF→CRLF）为仓库既有状态，非错误。

## 7. 停靠点

- **已完成**：文件夹体系五件事全部落地（新建落当前文件夹 / 工具 folder_id / 历史删除二次确认+修复 / list_conversation_folders 工具+信封+重名校验 / 「聊天」id=default+空串语义+默认名拦截）。master `20d7475`，debug CI 全绿。
- **待确认**：设备核验（§5-①，重点是工具 folder_id 空串/default、list_conversation_folders 信封、右滑确认）。
- **下一阶段**：无明确需求；候选 = 设备核验、release/pre CI 验证、诊断日志清理。
- **恢复动作**：读本文档 §4/§5；开始前先让用户设备核验文件夹体系闭环。
