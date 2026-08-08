# 交接文档：聊天滚动性能 + 工作区独立备份 + 上游同步 — 下一阶段入口

**日期**：2026-08-08
**目的**：上下文清理前的完整交接。新会话读本文档即可续接。master HEAD = `dd0062b`，工作树干净，debug CI 全绿（`31251322304` success）。

---

## 0. 一句话概况

接上一交接 `db8693e`（跨包名恢复 + 时间线）。本阶段四件事：**① 聊天滚动性能优化**（日志定位 → 图片解码上限 1024px、长消息段落合并、静态 shimmer、ImageRequest remember，加滚动/图片诊断日志 debug-only）；**② 用户消息连续图片合并为一行右对齐**；**③ 同步上游 6 提交**（`git merge upstream/master`：convention plugin 构建重构 + keep rules 迁移 + UIMessagePart 拆分 + MCP 开关 + 2 个模型，冲突逐个解决）；**④ 工作区独立备份/还原**（单工作区导出/导入 zip 含 rootfs，符号链接语义保留）。全程 CI 绿。master `db8693e` → `dd0062b`（9 commits）。

---

## 1. 已完成工作（commit 链）

### 一、聊天滚动性能（数据驱动：日志定位 → 对症修复）

| commit | 内容 |
|---|---|
| f3a9a4a | **诊断**：`ScrollFrameSampler`（滚动帧间隔分布 good/slow16-33/jank33-66/severe>66，每秒一行）+ `ZoomableAsyncImage` onSuccess 记 `src=mem/disk/net + dur`（debug-only） |
| 65d1ff3 | 静态 shimmer 去 `BlendMode.DstIn`——每帧离屏 buffer（saveLayer）元凶 |
| 568386c | **图片解码上限 `.size(1024,1024)`**：日志显示 markdown 行内图宽高无界 → Coil 按原始尺寸解码（手机照片 4000px+），单张 515ms、内存缓存每条目 48MB 挤掉缩略图、大纹理每帧被 GPU 采样 → 修复后 disk 解码 5-36ms |

**用户日志结论**（决定性证据）：`src=disk dur=515ms ×5`（同秒 5 张全尺寸解码）→ 加 size 上限后 `src=disk dur=5ms`。

| commit | 内容 |
|---|---|
| 8f8ce3d | **相邻纯文本段落合并为单 Text**：日志显示图片已修好但 `slow16-33` 全程主导——超长 AI 消息是单个 LazyColumn item，滚入时几十上百个段落 Text 主线程逐个 measure/换行 → 几十 ms 尖峰（"每次翻到那条消息卡顿一下"）。顶层把相邻纯文本段落（无图片/块公式/行内公式/引用链接）合并为一个 AnnotatedString + 单 Text（段间 \n），几十段 → 几个 Text；复用 paragraphRenderCache（无 latex 时 maxWidth/fontSize 归一 -1） |

**用户确认状态**：长消息卡顿已修复（用户反馈"还是较严重"→ 修后未再报）。图片区滚动 `slow16-33` 仍偏高（30fps 级，非 60fps），但已从"卡顿感严重"降到可接受；后续若要继续压，方向是组合侧（recompose 范围/beyondBounds 预热）。

### 二、用户消息连续图片合并

| commit | 内容 |
|---|---|
| efcf820 | `groupMessageParts` 加 `mergeConsecutiveImages` 参数 → `ImageGroupBlock`（index=首图），`MessageImageRow` 用 FlowRow 右对齐并排（窄图缩窄、宽图占整行）。仅用户消息启用，assistant/导出预览原样 |
| 6b7eb45 | 补 Export.kt 预览 when 穷尽性空分支（默认不合并，不可达） |
| a8661ce | **根因修复**：合并逻辑首张图从未进组（合并条件只查 `lastOrNull() is ImageGroupBlock`，首图落成 ContentBlock，第 2 张不满足条件又落 ContentBlock → 永不合并）。改三路判断（组追加/单图升级组/新建组）+ `GroupMessagePartsTest`（5 例） |

### 三、上游同步（`git merge upstream/master` = 88a45ff）

**背景**：fork 落后上游 6 提交但改动幅度大无法直接 cherry-pick；GitHub "落后 N" 按 commit 祖先算，必须真实 merge 才消指示器。merge-base `8349ef2`（fork 领先 424）。

| 上游提交 | 合并方式 |
|---|---|
| 4268f89 convention plugin | 采用。新增 `build-logic/`（`rikkahub.android.library(.compose)` 两插件），9 个库模块 build.gradle.kts 改插件 id。**fork 保留**：各模块 `pre` buildType（app pre 变体需库同步暴露）、optIn、minSdk 覆盖 |
| de3399b keep rules | 采用（调整）。模块 proguard-rules.pro 全删、keep 规则移入 `app/src/main/keepRules/rikkahub.keep`；release 用 `optimization { enable = true }`（AGP 9.3.1 新 API，删 isMinifyEnabled/proguardFiles）。**fork 追加规则**保留：RaTeX FontCache、serialization、`-dontobfuscate`、auth0/jackson |
| ba85b8f MCP 开关 | 自动合并（SettingMcpPage.kt + PasswordVisualTransformation） |
| b106e8b UIMessagePart 拆分 | 拆到 `UIMessagePart.kt`，但**用 fork 版本**（保留 fork 超集 `ToolState`/`toolState`/`liveOutput`；上游版无这些字段）；`UIMessageAnnotation.kt` 独立文件 |
| fac1c6c / 4b6449e 模型 | 自动合并（qwen-3.8-max + mimo v3 定义/注册都在） |

**冲突手动处理**：9 个库模块 build.gradle.kts（取上游插件 + fork pre 保留）；`ai/build.gradle.kts`（嵌套冲突最脏，重写）；`Message.kt`（删 HEAD 内嵌 UIMessagePart 块，imports 清理 Transient/JsonElement/JsonObject——fork 拆分版用 Transient，保留在 UIMessagePart.kt 内）；`app/proguard-rules.pro`（上游删/我改 → 采用上游删，规则并入 rikkahub.keep）；**workspace/build.gradle.kts 自动合并括号错位**（buildTypes 嵌进 externalNativeBuild）→ 重写纠正。consumer-rules.pro 保留 8 个（0 字节）+ document（MuPDF JNI 规则）。

**结果**：`git rev-list --left-right --count upstream/master...HEAD` = `0 425`（落后 0）。build-logic 编译通过（debug CI 验证）。

### 四、工作区独立备份/还原（应用备份不含 workspaces/，补独立功能）

| commit | 内容 |
|---|---|
| 89eb5fa | **`WorkspaceBackup.kt`**（`data/sync/`）：zip = `workspace.json`（name/toolApprovals/createdAt/updatedAt/shellStatus）+ `files/` + `linux/`（rootfs），排除 `tmp/` 与 `.l2s.*`，全相对路径跨包名兼容；`export`/`parseMeta`/`extractTo` 纯逻辑可测；`extractTo` 防路径穿越。**WorkspaceRepository** 加 `exportWorkspace(id)`/`importWorkspace(zip)`（构造注入 cacheDir；导入=新建工作区：新 UUID id/root、重名加序号 `Name (2)`、恢复 toolApprovals/shellStatus，rootfs 完整 checkIntegrity 兜底）。**WorkspaceVM** 转发；**WorkspacePage** 卡片菜单"导出"+ 顶栏"导入"，SAF launchers + 进度态。六 locale 字符串 |
| 6e64183 | DI 修复：`cacheDir` 已是 File，去掉多余 `File()` 包装（编译错） |
| dd0062b | **导出失败修复**：rootfs（linux/）全是 busybox applet 软链，target 多为绝对路径（`bin/awk → /bin/busybox`），原导出对软链 `file.inputStream()` → Java 把绝对 target 解析到设备根 → FileNotFound。改为**保留链接语义**：导出软链记为 `symlinks/<path>` 条目（内容 = target 文本，不读内容）；导入先解普通文件再统一 `Files.createSymbolicLink` 重建（相对链接原样相对、绝对链接 rootfs 命名空间内解析） |

---

## 2. 关键设计决策（认知遗留）

### 滚动性能
- **数据驱动修性能**：先上 debug-only 诊断（`ScrollFrameSampler` 帧分布 + 图片 src/dur），用户滚真机发日志，再对症。两次都一击命中（515ms 全尺寸解码 / 长消息段落布局尖峰）。
- **Coil3 API 注意**：`SuccessResult.dataSource`（MEMORY_CACHE/MEMORY/DISK/NETWORK，无 memoryCacheHit）；`AsyncImage` 传预构建 ImageRequest 时若未显式 `.size()`，ConstraintsSizeResolver 拿无界约束 → 原始尺寸解码。**markdown 行内图 widthIn/heightIn 只有下限无上限** = 无界 → 必须显式 size 上限。
- **静态 shimmer 也别用 DstIn**：`drawRect(blendMode=DstIn)` 每帧 saveLayer 离屏 buffer，多图 loading 并发叠加 = GPU 抖动。改透明边渐变普通 alpha 合成。
- **长消息 item 布局尖峰**：段落合并成单 Text 大幅减少 item 内 measure 次数；合并前提（无图片/公式/引用）保证视觉不变。

### 上游同步（merge 而非 cherry-pick）
- GitHub "落后 N" 是 commit 祖先关系 → **必须 `git merge` 让上游提交真实进入 fork 历史**。
- merge-base 之前 fork 已领先 424 → 冲突只在 6 提交触碰的文件，集中在库模块 build.gradle.kts。
- **fork 保留项原则**：pre buildType（全模块）、`-dontobfuscate`（release 可读堆栈）、UIMessagePart 超集字段。上游的构建重构（convention plugin）照单全收，改动大但收益明确。
- **AGP 9.3.1**：release 用 `optimization { enable = true }`，isMinifyEnabled/proguardFiles 从库模块消失，keep 规则统一进 `rikkahub.keep`。

### 工作区备份
- **zip 全相对路径**（同 RestorePathRebaser 思路）→ 跨包名天然兼容，无需 rebase。
- **导入=新建工作区**（用户选定）：新 UUID 不冲突、重名序号、不覆盖现有。
- **符号链接是 rootfs 备份的坑**：软链不能按内容读（绝对 target 解析到设备根），必须 `symlinks/<path>` 记 target + `Files.createSymbolicLink` 重建。**同包名/跨包名导入后 rootfs 完整可用**（hasRootfs 为 true）。
- 已知边界：`toolApprovals` 导入时原样恢复（含超集字段），`shellStatus` 恢复导出时值，启动 `checkIntegrity` 兜底（rootfs 缺失降级 DISABLED/BROKEN）。

---

## 3. git 状态 / CI

- 分支 `master`，HEAD = `dd0062b`（`fix(workspace): 导出失败——rootfs 符号链接按内容读导致 FileNotFound`），工作树干净
- **debug CI 全绿**：dd0062b `31251322304` / 6e64183 `31249940995` / 88a45ff `31246494867` / 8f8ce3d `31244778066`（均核对 headSha）
- 中途失败两处已修：89eb5fa `31249674387`（cacheDir File 包装编译错 → 6e64183）、f3a9a4a（memoryCacheHit 不存在 → 71dec49 / e7b9b1f）
- release / pre 工作流**未跑**（convention plugin + keep 规则在 release 路径未验证，见 §5-②）

## 4. 恢复地图

| 台账/文档 | 路径 |
|---|---|
| 上一交接（跨包名恢复+时间线） | `docs/superpowers/handoffs/2026-08-08-cross-package-restore-timeline-next-phase.md` |
| 本阶段核心文件 | `app/src/main/java/me/rerere/rikkahub/data/sync/WorkspaceBackup.kt`、`data/repository/WorkspaceRepository.kt`、`ui/pages/extensions/workspace/WorkspacePage.kt`、`ui/components/richtext/Markdown.kt`（MergedParagraphs/isMergeableParagraph）、`ui/components/richtext/ZoomableAsyncImage.kt`、`ui/modifier/Shimmer.kt`、`ui/hooks/ScrollFrameSampler.kt`、`ui/components/message/ChatMessageCot.kt`（ImageGroupBlock）、`build-logic/` |
| 单元测试 | `app/src/test/java/me/rerere/rikkahub/ui/components/message/GroupMessagePartsTest.kt` |
| 相关 memory | `backup-coverage-gaps`（workspaces/ 现已独立备份）、`highlight-prism-revert` |

## 5. 待办 / 挂起项（按优先级）

### ① 设备核验（唯一需用户上手）

装 `dd0062b` debug 包：
1. **工作区导出**：卡片 ⋮ → 导出（含 rootfs，zip 较大需等待）→ 不再报 `bin/awk No such file`；**导入** → 新工作区（重名带序号）、文件可见、shell 可直接用（rootfs 完整 = 本次核验重点）
2. **滚动性能**：多图片对话上下滚动——长消息不再卡顿尖峰、图片区滚动比之前顺
3. **多图合并**：用户消息发 2+ 窄图 → 一行并排右对齐
4. **上游新特性**：MCP 设置页 header 值显示/隐藏开关；新模型 mimo-v3 / qwen-3.8-max 可选

### ② 遗留 Minor / defer（不阻塞）

- **release / pre CI 未跑**：convention plugin + `rikkahub.keep` + `optimization{enable}` 在混淆路径未验证。建议触发 `nightly-build.yml` + `nightly-build-pre.yml` 确认 release 构建链（keep 规则真正生效的构建）。
- **图片区滚动 slow16-33 仍偏高**（30fps 级）：已从"严重卡顿"改善到可接受；若继续压，方向 = 组合侧（recompose 范围 / `beyondBoundsItemCount` 预热 / 背景图+渐变+haze 模糊每帧成本）
- **诊断日志仍在**（ScrollFrameSampler + ChatImg）：真机确认没问题后可清理（debug-only，release 零开销，不急着删）
- 上一阶段遗留：workspaces/ 已独立备份但**应用主备份仍不含**（设计如此）；存储格式深改（相对路径持久化）未做；Firecrawl crawl/batch、Jina 多 URL、Exa ids 复用、Metaso scope（见 2026-08-07 交接 §5-②）
- **历史挂起**：乱召回（语义搜索 bug）、Task 12 ripgrep artifact 流水线、UpdateChecker.kt 删 `return@flow`

### ③ 下一阶段（用户已提需求，未开工）

**Markdown 用 `file://` 链接指向工作区/uploads 文件**：AI 发送消息时无法在 markdown 里插入工作区图片。目标：`![alt](file://workspace/...)` / `file://` 指向工作区 `files/` 及 uploads 文件夹的文件，markdown 渲染时解析为可显示图片/可点击链接。

已探路（进入 plan mode 前）：`WorkspaceTools.kt` 是工具输出文件路径的源头（`createReadFileTool` 等，`workspaceId` 从 conversation 拿），尚未细看工具返回的路径格式与 markdown 渲染侧 `ZoomableAsyncImage`/`INLINE_LINK` 的处理。**开工先做两件事**：① 查工具返回的工作区路径格式（相对 `files/`？`/workspace/...`？）；② 查 `Markdown.kt` 的 `IMAGE`/`INLINE_LINK` 分支如何解析 URL，file:// 协议要新增解析层。核心问题：**工作区路径 → 应用内 File 的映射**（哪一层把逻辑路径解析成物理路径）。

## 6. 技术约束 / 惯例（必须遵守）

- **本机无编译器**：不运行 gradle。静态编写 + review，编译验证全靠 CI。
- **CI 判定铁律**：`gh run list ...` 权威判定 + **核对 headSha**。流程：先 push 再 `gh workflow run nightly-build-debug.yml --repo DevLintMar/rikkahub --ref master`。
- **库模块 build.gradle.kts 用 convention plugin**（`rikkahub.android.library(.compose)`），pre buildType 保留（app pre 变体需要）；keep 规则进 `app/src/main/keepRules/rikkahub.keep`；release 用 `optimization { enable = true }`（AGP 9.3.1，别再用 isMinifyEnabled/proguardFiles）。
- **Coil3**：AsyncImage 传预构建 ImageRequest 需显式 `.size()`（无界约束=原始尺寸解码）；缓存来源用 `SuccessResult.dataSource`。
- **Compose 绘制期状态铁律**：drawBehind 绘制期读取的状态必须组合期固化（CompositionLocal/remember），绝不放共享可变 var。
- runCatching 不能包 suspend；suspend 用 try/catch + 重抛 `CancellationException`。
- 字符串双写 en+zh；六 locale 占位符串全写（`%1$s`）。
- 中文 conventional commit；文件删除走 `~/.claude/scripts/trash.sh`；force-push 需用户明确要求。
- 本机 git 换行警告（LF→CRLF）为仓库既有状态，非错误。

## 7. 停靠点

- **已完成**：滚动性能（解码上限+段落合并+静态 shimmer+request remember+诊断）、多图合并、上游 6 提交同步（落后 0）、工作区独立备份/还原（含 rootfs，符号链接语义保留）。master `dd0062b`，debug CI 全绿。
- **待确认**：设备核验（§5-①，重点工作区导出/导入含 rootfs 完整）。
- **下一阶段**：markdown `file://` 指向工作区/uploads 文件（§5-③）。
- **恢复动作**：读本文档 §4/§5；开始下一阶段前先让用户核验工作区备份闭环 + 触发 release/pre CI。
