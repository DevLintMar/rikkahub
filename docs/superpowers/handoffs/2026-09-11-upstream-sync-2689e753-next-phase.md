# 交接文档：同步上游 2689e753（158 commits）+ 生成循环重构 — 下一阶段入口

**日期**：2026-09-11（当日第三份；前两份为 `2026-09-11-read-image-retry-folder-drag-tilde-render-fix-next-phase.md` 与 `2026-09-11-file-url-sandbox-tool-image-timeout-next-phase.md`）
**目的**：上下文清理前的完整交接。新会话读本文档即可续接。
**状态**：master 的最后一个**代码**提交 = `0aff63d7`（haze 回退），CI 全绿（`34617957749`）；本文档及其补记为它之后的 docs 提交。工作树干净。分支 `sync/upstream-2026-09-10` 保留在 `4ca2bafe` 供对照。

---

## 0. 一句话概况

把上游 fork 点（`4b6449e3`，2026-08-04）之后积累的 **158 个上游提交**一次性收进 master，同时保住 fork 的全部自有工作。**没有合上游 HEAD，而是 pin 到上游最后一个 CI 绿的提交 `2689e753`** —— 上游 HEAD `288a034c` 删掉了 `gradle/libs.versions.toml` 却无替代文件（`build-logic/settings.gradle.kts` 仍引用它），且该提交没有任何构建 check-run。33 个冲突文件全部解决，过程中发现并修掉 **5 个不带冲突标记、会静默破坏编译/运行的真实破绽**，随后 CI 又暴露出 **9 处 `:app` 编译错误**与 **2 轮测试源码问题**，全部修完后 CI 绿。合并结果：352 files changed, 23309 insertions(+), 6134 deletions(-)。版本号 3.1.1 → **3.2.0**（versionCode 1174 → 1175）。

---

## 1. 已完成工作（commit 链）

```
1695064a merge: 同步上游 2689e753 到 master（fork 3.2.0）
4ca2bafe chore: 版本号 3.2.0（versionCode 1175）+ 提交 CI 生成的 Room v27 schema
b482d591 fix(test): required 是 nullable，用 orEmpty() 取用
eac621b9 test(search): 让上游的 SearchToolsTest 对齐 fork 的 Settings 与渠道参数契约
fa07ca61 fix: 修掉上游合并后 :app 的 9 处编译错误（CI 34607604330 报出）
cab3a656 merge: 同步上游 2689e753（158 commits）   ← 父提交 = 7042fa80 + 2689e753
09ac2c19 docs: 交接文档——同步上游 2689e753（158 commits）+ 生成循环重构
7c4e44f9 chore: huge-icons 换回 1.3
0aff63d7 fix(ui): haze 回滚到 2.0.0-alpha03，修聊天输入框变全透明   ← master HEAD
```

### 一、pin 的选择（本次最重要的判断）

上游 HEAD `288a034c`「chore: 更新依赖」deleted `gradle/libs.versions.toml`（-197 行）**且未提供替代**：`build-logic/settings.gradle.kts` 仍 `from(files("../gradle/libs.versions.toml"))`，`app/build.gradle.kts` 引用 `libs.` 88 次、根 `build.gradle.kts` 8 次。核实发现该提交只有 `close-blank-issues` 一个 check-run，`state: pending`。最后一个被 CI 验证过的是 `2689e753`（Daily Build 连续两次 success）。

**注意**：pin 到 `2689e753` 后，`gradle/libs.versions.toml` **自动合并成功**（我们的 `ratex`/`haze-blur-materials` 与上游新增的 `snakeyaml` 等并存 —— ⚠️ **2026-09-12 更正**：`nav2`/`androidx-navigation2` 其实已被上游删除且静默生效，现仓库已无这两个 alias），不再是冲突 —— 这是 pin 带来的额外收益。

### 二、解冲突过程中发现的 5 个静默破绽（都不带冲突标记）

| # | 破绽 | 后果 | 修法 |
|---|---|---|---|
| 1 | 上游新文件 `AppDatabaseFactory.kt` 只注册到 `Migration_15_16`，**漏了 `Migration_25_26`** | 已有装机开库即崩（`A migration from 25 to 26 was required but not found`） | 补回注册 |
| 2 | `chat_message_tool_search_prefix` 被 fork 从 **6 个 locale 全删**，而上游新代码 `BuiltinToolUIs.kt:1498` 在用 | 未解析引用 → 编译失败 | 按上游文案补回 6 个 locale（插在 `chat_message_tool_scrape_web` 之后，与上游字母序一致） |
| 3 | fork 专有的 `SubAgentRuntime.kt` 调用上游已删除的 `handleMessageChunk` | 编译失败 | 改用 `StreamChunkHandler(model).handle(messages, chunk)`（与 `GenerationLoop` 同形，每次尝试新建） |
| 4 | 上游新类 `DoubaoOptions` 的 `apiKey` 写成普通 `val`，而 sealed class 声明 `open val apiKey`（其余子类都 `override`） | 编译失败 | 加 `override`；并补进 `withSingleKey` 的多 key 轮询（该项**能编译**——有 `else` 分支——但会静默失去轮询，属语义错误） |
| 5 | `S3Sync.kt` / `WebDavSync.kt` 与上游 `BackupManager` 的委派版本**重复定义** `restoreFromBackupFile` / `prepareBackupFile` | 签名冲突 | 删 fork 手写实现，把 fork 的 `RestorePathRebaser` 跨包名恢复、`images/` 备份覆盖、`restore_diag.txt` 诊断移植进 `BackupManager` |

### 三、合并强制要求的结构性改动（推迟即编译不过）

**生成循环采纳上游结构**（上游 `097cdb90` 把 `GenerationHandler.kt` 改名重写为 `GenerationLoop.kt` 并新增 `ChatToolFactory.kt`）：

- 删除 `data/ai/GenerationHandler.kt`；`generateText` → `GenerationLoop`；`translateText` → 上游新抽出的 `TranslationHandler`；工具装配搬进 `ChatToolFactory`
- `ChatToolFactory.kt` 是上游**新增**文件，按上游旧 API 调用，但在 fork 上 **4 个签名都不存在**：`buildMemoryTools(json, onCreation, onUpdate, onDelete)`、`memoryRepository.addMemory(id, content)`、`memoryRepository.updateContent(id, content)`、`createConversationTools(repo, assistantId)` → 已重写为 fork 实际 API
- 移植进 `ChatToolFactory.createTools` 的 fork 工具（顺序即注册顺序）：记忆工具（12 参版，仅 `enableMemory`；⚠️ 2026-09-12 更正原写的「11 参」）→ `createSearchTools` → `localTools.getTools` 再映射（`"sub_agent"`→`buildSubAgentTool(...)`、`"run_workflow"`→`buildWorkflowTool(...)`）→ `createConversationTools`（4 参，含文件夹）→ 工作区工具 → **`createReadImageTool`** → 技能工具 → MCP 分组
- 子代理上下文 `SubAgentToolContext(baseTools, mcpToolGroups, skillTool, subAgentTool=null, workflowTool=null)` 保留（在 factory 内构造）
- `ChatService` **恢复了 `localTools` 依赖**：上游的 auto-merge 把它删了，但 fork 的 `handleSubAgentRecall` 需要 `localTools.subAgentRuntime.getTaskInfos()` —— 不恢复则 hunk 4 的并集无法编译
- 其余 fork 成员全部保留并一一确认：`pendingNotifications`、`checkPendingRecall`、`handleSubAgentRecall`、`fireRecall`、`lostUploadUrlsAfterDelete`、keep-alive 集成

**DB 升到 v27**（两个分支把两个不同 schema 撞在了同一版本号下）：

- 我们 v25 = `message_embeddings` 表、v26 = 记忆的 `title/description/is_active`（⚠️ **2026-09-12 更正**：`message_embeddings` 是 v25 引入的，v26 只加记忆三列）；上游 v25 = `workspaces.shell_compatibility_mode`
- 新增 `Migration_26_27`，**先 `PRAGMA table_info` 探测列是否存在再 ALTER** —— 上游自己的 v25 就带这列，而 fork 支持恢复外来备份，直接 ALTER 会撞 `duplicate column name`
- `AppDatabase.version = 27`；`27.json` 由 CI 生成后拉回提交。核对生成的 schema 确认是双方并集；也核对 CI 重新生成的 `25.json` 与仓库中逐字节相同（identityHash 除外）→ 证明「25.json 取 ours」的判断正确
- `26.json` **不存在且不会存在**：我们从 26 直接跳到 27，没有 v26 的构建产物。这与仓库既有状态一致（此前也没有 26.json）

### 四、CI 报出的 11 处编译/测试问题（本机无编译器，只能靠 CI 发现）

CI run `34607604330`（merge 提交）结果：**`ai`/`search`/`highlight`/`workspace`/`oauth`/`videogen` 六个模块全部编译通过**，错误集中在 `:app` 的 9 条：

| 文件 | 根因 |
|---|---|
| `ChatService.kt:592` | 上游把 `regenerateAtMessage` 的 `appScope.launch` 换成 `launchGenerationJob`（前台服务保活），`return@launch` 的隐式标签失效 → `return@launchGenerationJob` |
| `ChatInput.kt:82/121` | 合并把 `import kotlin.uuid.Uuid` **重复了一份**（master 只有 1 份）|
| `ChatMessageCot.kt:117` | `thinkingAggregate` 的 `when` 未覆盖上游新增的 `ThinkingStep.ServerToolStep`（`ChatMessage.kt` 与 `Export.kt` 两处已有分支）→ 以 `ServerTool.isFinished` 判定已执行 |
| `ChatPage.kt:293` | 合并丢了 `import androidx.compose.ui.platform.LocalContext`（master 有）|
| `AgentDetailPage.kt:356/358/363` | 合并把 material3 `1.5.0-alpha25 → alpha27`（composeBom `2026.06.01 → 2026.08.00`），该版本 `menuAnchor()` 必须显式传 `MenuAnchorType`，`ExposedDropdownMenu` 也需单独 import |
| `SettingModelPage.kt:171` | **上游重写 `ModelSettingItem` 时丢掉了 fork 的 `onClear`** 及取消按钮，而 fork 的调用点仍在传 → 恢复参数与 UI，并补回一并丢失的 `IconButton`/`Cancel01` import |

CI run `34609183017`：**Gradle Build 通过**，单测停在 `compileDebugUnitTestKotlin` —— 上游新测试 `SearchToolsTest.kt` 用 `Settings(searchServiceSelected = 0)`，而 fork 的字段是 `searchServiceSelectedIds: List<Uuid>`（fork 多选、上游单索引）。顺带发现该测试断言的 schema 也与 fork 不符（**不是编译错，是运行期会挂**）：它断言 Exa 用 camelCase 键、`search_web` 只 `required=["query"]`、`scrape_web` 只 `required=["url"]`，而 fork 是 snake_case、多渠道路由（`required` 含 `service`）与多 URL 抓取（`urls`）。按 fork 实际契约改写，保留原测试意图。

CI run `34610192790`：`InputSchema.Obj.required` 是 `List<String>?`（`properties` 非空），我直接 `.contains()` 编译不过 → `orEmpty()`。上游原测试用 `assertEquals(listOf(...), required)`，null 也能比，所以没暴露这点。

### 五、其他采纳/保留

- **MCP OAuth 采纳上游 `oauth/` 模块**：fork 的 `McpOAuthClient.kt`/`McpOAuthCallback.kt`/`transport/`/`McpOAuthCallbackActivity.kt` 已删，上游 `McpOAuthDiscoveryClient.kt` 已加入，`McpManager`/`McpOAuthCoordinator` 改用它，manifest 声明也已清除 —— 这块由合并自动完成，无需手工
- `oauth/` 与 `videogen/` 两个新模块接线完整（`settings.gradle.kts` include、`app/build.gradle.kts` project 依赖、ktor/browser/snakeyaml 目录条目）
- **高亮**：fork 的 Prism+QuickJS 实现整体保留（上游 143 文件纯 Kotlin 引擎未复活）；上游本次对该模块只改了 2 行（`7b92f89e` 禁用连字），已手工移植进 `HighlightText.kt:135`
- **搜索**：保留 fork 的 snake_case 全参渠道（`category`/`include_domains`/`start_published_date`/`user_location`/`content_type`…），叠加上游的证据字段（`publishedDate`/`highlights`/`retrievedAt`）与豆包渠道。`SearchPicker` 采用**混合方案**：保留 fork 的多选 UI（`List<Uuid>`），但恢复上游的 `SearchMode` 枚举 —— 因为已合入的 `ChatInput.kt`/`ChatPage.kt` 同时需要 `onUpdateSearchMode` 与 `onUpdateSearchService`
- **配置还原**（上游的删除无冲突、会静默生效）：`CLAUDE.md`、`.claude/skills` **实体目录**（原会被换成 17 字节文本文件）、`.claude/settings.local.json`、`.claude/commands/publish-release.md`（上游新的 `.agents/skills/publish-release/SKILL.md` 也保留）
- `AssistantMemoryPage`：顺手删掉 `#${memory.id}` 显示（上游 `08c2648b` 的意图对 fork 同样适用）

---

## 2. 关键设计决策（认知遗留）

### 上游同步本身
- **绝不直接合上游 HEAD**，先 pin 到「最后一个 CI 绿过的上游提交」。判断方法见 §6。
- **冲突数 ≠ 难度**。真正的杀手是「无冲突但编译不过」—— 上游按自己的 API 写的新文件、上游漏掉的 fork 注册、上游 rename 使 fork 调用点失效。完整分类见 memory `upstream-sync-procedure`。
- **上游删除 fork 未改过的文件时会静默生效**，包括删掉 fork 依赖的配置。合并后必须显式核对 `.claude/`、`CLAUDE.md`、workflows。
- **两侧各自加了不同的东西时，「同一版本号下两个不同 schema」是陷阱**：DB 版本号必须顺延，且迁移要容忍外来库（fork 支持跨包名恢复）。

### 生成循环
- 上游的 `ChatToolFactory` 是**更好的结构**（集中装配 + 审批恢复走同一条路径），值得采纳；但它的记忆 API 是旧的，必须换成 fork 的 12 参版（8 回调 + json + memoryAssistantId + includeActiveEdit + includeSavedEdit；⚠️ 2026-09-12 更正原写的「11 参」）。
- `ToolOutput`/`executeFlow` 流式工具模型是 fork 约 100 个提交的工作基础（工具实时输出 UI），上游没有 → 在 `GenerationLoop` 的 `execute` 分支里保留 `executeFlow(args).collect { ... }` 分支。
- 上游的 `maybeTruncateToolOutput`（32KB → 落盘 `/tool_outputs/` 并给模型 `cat` 指针）与 fork 的 `clipToolOutput`（100KB 硬截断兜底）**两者都保留**，执行顺序 `maybeTruncateToolOutput(clipToolOutput(parts))`。
- git 在这两个截断函数上做过一次**错位对齐**（把两侧函数体对成「公共尾部」），产出引用未定义变量的代码 —— 遇到重命名+大改时要注意这类artifact。

### 搜索
- 上游把 Exa 参数改成了 camelCase，**fork 不采纳**：schema 键是模型已适应、提示词也按此写的契约。规则（见 `ExaSearchService.kt` 注释）：**schema 键(snake_case) = 本处读取键 = 聚合键；只有出站 HTTP body 用 Exa 原生 camelCase**。

### 其他
- `Conversation.files` **必须取上游的 `.localFileUrls().map { it.toUri() }`**：fork 原来的 `.collectAllParts().mapNotNull { it.fileUri() }` 里那两个 private helper 已被上游删除（fork 没改过它们，git 接受了删除），保留会编译失败。`localFileUrls()` 会递归进 `UIMessagePart.Tool.output`，**正好覆盖 read_image 的工具结果图**，与 fork 的附件回收修复兼容。
- `PreferencesStore`：采纳上游抽出的 `persistSettings(dataStore, settings)`，删掉 fork 的内联 `dataStore.edit{}`（其中引用了上游已删的 `settings.titleModelId`/`suggestionModelId`），但**把 fork 的 `SEARCH_SELECTED_IDS` 写入搬进 `persistSettings`**，否则 `searchServiceSelectedIds` 不再持久化。
- `ChatMessageTools.kt` 两个 hunk 取 ours：上游侧会与已有声明重复，且 fork 的 `onClick` 带 `interaction.detailOpen` + 内容门槛。

---

## 3. git 状态 / CI

- 分支 `master`，HEAD = `1695064a`；`sync/upstream-2026-09-10` 保留在 `4ca2bafe`（可作对照/回滚参考）
- 本阶段范围：`7042fa8` → `1695064a`，**352 files changed, 23309 insertions(+), 6134 deletions(-)**
- 回滚锚点：合并前的 master = **`7042fa80`**

CI 记录（全部为 `nightly-build-debug.yml`）：

| run | sha | 结果 | 说明 |
|---|---|---|---|
| `34607604330` | cab3a656 | ❌ | `:app` 9 处编译错误（**六个库模块已全绿**）|
| `34609183017` | fa07ca61 | ❌ | Gradle Build 通过；单测停在 `SearchToolsTest` 编译 |
| `34610192790` | eac621b9 | ❌ | `required` nullable |
| `34612251023` | 4ca2bafe | ✅ | sync 分支**全绿** |
| `34616257886` | 7c4e44f9 | ✅ | master 全绿（huge-icons 回退后）|
| `34617957749` | **0aff63d7** | ✅ | **master 全绿**（haze 回退后）← 当前 master HEAD |

（master 在 `1695064a` 上的那次 run `34613243157` 被中断未取结果；`7c4e44f9` 是它的后代且通过，故 master 已验证。）

### 六、图标集回退（合并后的用户反馈）

上游 `02a0c81c`（2026-08-23「chore: 更新依赖」）把 `huge-icons` 从 **1.3 升到 1.4**，整套图标字形改版 → 用户要求换回 1.3。这是本仓库唯一的图标集版本改动（`lucide-icons` 在区间内未变；其余改图标行的提交都是新功能在用图标，非换版）。

1.4 做过一次大小写规范化，本仓库受影响**仅一处**：`HugeIcons.Fullscreen`(1.4) → `HugeIcons.FullScreen`(1.3)，位于 `ChatInput.kt` 与 `TextArea.kt`。只改版本号会编译不过。

**核对方法**（本机 Gradle 缓存里没有这个库，`find-hugeicons` 技能查 JAR 的路子走不通）：用两份独立证据 —— `D:/Temp/hugeicons-list.txt`（4688 个名字，含 `FullScreen`、不含 `Fullscreen` ⇒ 1.3 命名）与 `app/src/release/generated/baselineProfiles/baseline-prof.txt`（fork 自身在 1.3 时代的构建产物，含 `FullScreenKt`）。把源码全部 156 个 `HugeIcons.*` 与之交叉比对：**只有 `Fullscreen` 一个缺失**，其余 155 个（含 `Internet`/`Tiktok`/`DragDropVertical` 这些 1.4 之后才引入代码的名字）都在 1.3 中存在。

⚠️ 同一次上游提交也升了 `haze`（`2.0.0-alpha05 → beta01`）。**`haze` 也已单独回退**（见下），两个依赖各自独立。

### 七、haze 回退（合并后第二个用户反馈）

现象：聊天页底部输入框从**半透明毛玻璃变成全透明**。

合并采纳了上游对 haze 的 2.0 大版本升级（`alpha03 → beta02`）与其 API 迁移，而这次迁移**改变了样式语义**：

| | 合并前 alpha03 | 合并采纳的 beta02 |
|---|---|---|
| 样式 | `HazeMaterials.thin(containerColor = hazeTintColor)` —— **自带半透明着色** | `HazeBlurStyle.Material3 { blurRadius(12.dp) }` —— **只有模糊半径，没有着色** |
| 调用 | `hazeEffect(state) { blurEffect { style } }` | `hazeBlur(input = HazeInput.Sources(state), style)` |

放大器在 `ChatInput.kt`：开启模糊时 Surface 的底色是**硬编码全透明**
`color = if (enableBlurEffect) Color.Transparent else hazeTintColor`，即「半透明」完全由 haze 层提供 → haze 层只剩模糊、没了着色，就露出全透明底。

回滚内容（`0aff63d7`）：
- `gradle/libs.versions.toml`：`haze = "2.0.0-alpha03"`；别名 `haze-blur-material3` → **`haze-blur-materials`**（模块 `dev.chrisbanes.haze:haze-blur-materials`）
- `app/build.gradle.kts`：`libs.haze.blur.material3` → `libs.haze.blur.materials`
- `ChatInput.kt`：导入与调用改回 `HazeMaterials.thin` / `hazeEffect { blurEffect { … } }`
- `ChatList.kt` / `ChatPage.kt` **未改** —— `HazeState`/`hazeSource`/`rememberHazeState` 是核心 API，两个版本一致（合并前后无差异）

核对：`ChatInput.kt` 的 haze 行与合并前 `7042fa80` **完全一致**；无 2.0-beta API 残留；libs alias 全部解析。CI `34617957749` ✅。



---

## 4. 恢复地图

| 上一交接 | 路径 |
|---|---|
| 2026-09-11 第二份（file:// 沙箱 + 工具结果图片通道 + 下载超时）| `docs/superpowers/handoffs/2026-09-11-file-url-sandbox-tool-image-timeout-next-phase.md` |
| 2026-09-11 第一份（read_image 重试 + 文件夹拖拽 + 单波浪）| `docs/superpowers/handoffs/2026-09-11-read-image-retry-folder-drag-tilde-render-fix-next-phase.md` |

| 本次核心文件 | 说明 |
|---|---|
| `data/ai/GenerationLoop.kt` | 上游骨架 + fork 的 `executeFlow` 流式分支、`clipToolOutput`、`maybeTruncateToolOutput`、`activeMemories` |
| `data/ai/tools/ChatToolFactory.kt` | **工具装配唯一入口**（fork 工具全在这里注册）|
| `data/ai/TranslationHandler.kt` | 上游新抽出的翻译处理器（替代 `GenerationHandler.translateText`）|
| `data/db/migrations/Migration_26_27.kt` | **新增**，探测式 ALTER |
| `data/db/AppDatabaseFactory.kt` | 迁移注册处（`Migration_25_26` + `Migration_26_27`）|
| `data/db/AppDatabase.kt` | `version = 27` |
| `app/schemas/.../27.json` | CI 生成后提交 |
| `data/sync/BackupManager.kt` | 上游新文件 + 移植进来的 `RestorePathRebaser`/`images/`/诊断 |
| `data/sync/S3Sync.kt`、`webdav/WebDavSync.kt` | 已与上游逐字节一致（委派 BackupManager）|
| `ui/components/ai/SearchPicker.kt` | fork UI + 上游 `SearchMode` 混合 |
| `highlight/.../HighlightText.kt` | 连字修复（`fontFeatureSettings`），line 135 |
| `service/ChatService.kt` | 上游结构 + fork 成员；**注意 `localTools` 依赖是 fork 必需的** |

| 相关 memory | 说明 |
|---|---|
| `upstream-sync-procedure` | **本次新增**：同步流程 + 10 类静默破坏分类 |
| `windows-core-symlinks-false` | **本次新增**：本机符号链接退化 |
| `backup-coverage-gaps` | **已更新**：`images/` 已补上，`workspaces/` 仍缺 |
| `memory-system-handoff-chain` | 入口已指向本文档 |

---

## 5. 待办 / 挂起项

### ① 设备核验（唯一需用户上手）

装 master `1695064a`（3.2.0）的 debug 包，重点：

1. **生成循环重构**：普通对话、工具调用、工具审批（含连续审批）、停止生成、重新生成 —— 这是本次改动最大的地方
2. **工具实时输出**：工作区 shell 命令应流式显示（`ToolOutput.OutputDelta` 通路）
   —— ⚠️ **2026-09-12 更正：这一条做不到**。`liveOutput` 只有生产者没有 UI 消费者（`ui/` 下读取点为 0；UI 端是更早的 fork 提交 `c21225e1` 自己删掉的），`executeFlow`/`OutputDelta` 现在只用于收集完整结果。按「有意不做」处理，不再作为验收项。
3. **read_image**：视觉模型出图、工具结果图片不再 `invalid input`、死链 http 图片 20 秒失败
4. **上游新功能抽验**：工作区 Shell 兼容模式 + 终端多 Tab + html/svg 预览、消息发送队列、语音模式
5. **备份/恢复**：S3 与 WebDAV 备份 → 恢复，确认 `upload/`、`images/`、头像都在；跨包名恢复（debug↔release）仍能重定位 `file://` 路径
6. **DB 迁移**：从 3.1.1（v26）直接升级，确认开库不崩（`Migration_26_27`）
7. **记忆**：7 个记忆工具、活跃记忆注入
8. **搜索**：snake_case 渠道参数仍生效、豆包渠道可用、`retrievedAt`/`publishedDate` 出现在结果里
9. 代码块连字已禁用（`->` 不再显示成箭头）

### ② 本次有意留下的偏差（不是漏做）

- **Exa 的新鲜度/证据请求侧参数未采纳**：上游 `a8f8c3a1` 把证据参数改成 camelCase 并始终请求 text+highlights，与 fork 的 snake_case 契约冲突。已采用「输出侧保留证据字段、请求侧保持 fork 语义」，故上游那部分特性只进来了一半
- **`SearchPicker` 用混合方案**而非纯粹取 ours（原因见 §1.5）
- **MCP 无效服务器名的行为改为上游版**：现在会暂停消息队列（fork 原来只报错不暂停）
- **搜索门控用上游的 `shouldUseExternalWebSearch`**（`enableWebSearch && BuiltInTools.Search !in model.tools`）而非 fork 的裸 `enableWebSearch`；若 fork 要求总是用外挂搜索，需同时改 `ChatToolFactory.kt` 与 `ChatService.kt:812`（⚠️ 2026-09-12 更正：原写 `:811`）
- **`huge-icons` 钉在 1.3**（上游已 1.4）：上游 1.4 改了整套图标字形，用户要求保留 1.3 观感。**下次同步上游会重现这个冲突** —— 记得改回 1.3 并把 `Fullscreen` 反向改回 `FullScreen`。详见 memory `huge-icons-pinned-1-3`
- **`haze` 钉在 2.0.0-alpha03**（上游已 beta02）：beta 的样式语义会让输入框从半透明变全透明（原因见 §3-七）。**下次同步也会重现** —— 改回 alpha03 并同步改别名/依赖/`ChatInput.kt` 的 API 调用。详见 memory `haze-pinned-alpha03`
- **`pre` 变体未验证**：`nightly-build-pre.yml` 历史上从未跑过，本次也没跑
- **`baselineProfiles/*.txt` 已过期**：里面还记录着已不存在的 `GenerationHandler` 构造器

### ③ 历史挂起（延续）

- **release / pre CI 未跑**：convention plugin + `rikkahub.keep` + optimization 混淆路径未验证（✅ **2026-09-12 已解决**：`release` 已多次真跑且绿；`pre` 自 `1a542d72` 起真跑并绿 —— 此前那些 success 是 `check` 判「24h 无提交」把 `build` 整个 skip 后的假绿）
- **诊断日志仍在**（belt skipped / FILES_DELETE / cleanup refs / ChatImg / ScrollFrameSampler / read_image）：设备确认后清理
  （⚠️ **2026-09-12 更正**：原列表里的 `hoisted-image` 已不存在 —— `ChatCompletionsAPI.kt` 里那条是解释「图片为何旁挂一条 user 消息」的常规日志，不是待清理的诊断日志）
- **OCR 调用没有时间上界**（`OcrTransformer.performOcr` 走全局 10 分钟 readTimeout × 4 次重试）；与 `Call.await()` 缺 `invokeOnCancellation` 同源
- **HTML 渲染路径点 `file://` 链接会崩**（`FileUriExposedException`）
- **`ImageLazyLoadTransformer` 的降级路径**会把 upload/workspaces 之外的图标记成设备绝对路径（按新规则 read_image 读不到）
- **BMP 能识别但发不出去**（`FileEncoder.guessMimeType()` 缺 BMP 分支）
- **引用计数 LIKE 查询性能**（`countMessageNodesContaining` 全表扫描）
- **`workspaces/` 仍不在备份内**（见 memory `backup-coverage-gaps`）
- **本机 `FileDownload` 未使用的 import**（`PromptPage.kt`，非本次引入）

---

## 6. 技术约束 / 惯例（必须遵守）

### 工程流程
- **本机无 Android 编译器**：不运行 gradle。编译验证全靠 CI。
- **CI 判定铁律**：先 push 再 `gh workflow run nightly-build-debug.yml --repo DevLintMar/rikkahub --ref <branch>`；**`gh run watch <id> --exit-status`** 实时监控；`gh run view <id> --json conclusion,headSha` 核对 headSha；结果无论红绿主动汇报。
- **只有两处会因编译中止**：`Gradle Build`（`assembleDebug`）与 `Unit Tests`（`compileDebugUnitTestKotlin` + `testDebugUnitTest`）。合并类错误常分两轮暴露：先 `:app:compileDebugKotlin`，修完才轮到测试源码编译。
- **选 pin 的方法**：`gh run list --repo rikkahub/rikkahub --workflow "Daily Build" --limit 3 --json headSha,conclusion`，取最后 success 的 sha。
- 中文 conventional commit；字符串六 locale；工具 description/注入文本/JSON 用英文。
- 文件删除走 `~/.claude/scripts/trash.sh`（绝不 `rm`）；force-push 需用户明确要求。
- 本机 git 换行警告（LF→CRLF）为仓库既有状态，非错误。

### 本机静态检查（无编译器时比空等 CI 强）
1. **未解析 import 扫描**：把「出现在任意非 import 行的标识符」当作已声明 → 本次把 925 个 `me.rerere` import 缩到 1 个已知误报（`FileDownload`，且是既有问题）
2. **`libs.*` alias 全量比对** `gradle/libs.versions.toml`（本次 159 个 alias 全通过）
3. **`R.string.X` 全量比对** `values/strings.xml`（**这条抓到过真实的 `chat_message_tool_search_prefix` 缺失**）
4. 冲突标记全树扫描；5. 括号/花括号配平（合并易破坏）
- **注意 **`/tmp` 在本机映射到 `D:\Temp`，但 Python 不认 `/tmp` 路径，要写 `D:/Temp/...`。

### 并行子代理解冲突的规矩
- **必须禁止它们跑任何写操作 git**（`add`/`commit`/`checkout`/`restore`/`stash`/`merge`/`reset`），否则会踩 `.git/index.lock`
- 编辑可以并行（按文件集分组，组间不重叠），**暂存由主会话串行做**
- **子代理的结论必须逐条核验**：本次它们报的「ClaudeProvider 回归了空文本块」「Settings 只有 `searchServiceSelected`」「要删 `AppEvent.McpOAuthCallback` 分支」三条全是错的；而「`SearchPicker` 取 ours 会破坏调用点」一条是对的、且推翻了我的指令
- 让子代理明确报告「哪些是你验证过的」与「哪些是不确定的」，沉默的疑点比报出来的疑点危险

### 代码风格
- suspend 内并发用 try/catch，重抛 `CancellationException`；`runCatching` 不包 suspend。
- 使用请求日志页（`Logging.log`）而非 logcat 做设备诊断。
- `retryOnFailure` 的「不重试」分支**必须 throw**（`repeat` 会继续下一轮）。
- Room 迁移要**容忍已存在**（`PRAGMA table_info` 探测）—— fork 支持恢复外来备份。

---

## 7. 停靠点

- **已完成**：上游 158 提交同步（pin `2689e753`）、33 个冲突解决、5 个静默破绽 + 9 处编译错误 + 2 轮测试问题修复、生成循环重构（`GenerationLoop` + `ChatToolFactory`）、DB v27、版本 3.2.0、huge-icons 回退 1.3。**master `7c4e44f9` CI 全绿**（`34616257886`）。
- **待确认**：无阻塞项。设备核验见 §5-①。
- **下一阶段**：无明确用户需求。候选：设备核验本阶段改动（尤其生成循环与备份恢复）、清理诊断日志、release/pre CI 验证、OCR 超时/取消、Exa 请求侧证据参数（若要补全 §5.2 的偏差）。
- **恢复动作**：读本文档 §4/§5；开始前先让用户设备核验。
