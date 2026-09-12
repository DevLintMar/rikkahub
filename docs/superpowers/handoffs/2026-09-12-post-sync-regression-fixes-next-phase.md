# 交接文档：上游同步后的三个回归修复（图标 / 输入框透明 / 信封崩溃）— 下一阶段入口

**日期**：2026-09-12（当日第一份；上一份为 `2026-09-11-upstream-sync-2689e753-next-phase.md`）
**目的**：上下文清理前的完整交接。新会话读本文档即可续接。
**状态**：master HEAD = `895a1f7f`，工作树干净，CI 全绿（`34689873457` success）。

> **上游同步本身的细节不在这里** —— 见 `2026-09-11-upstream-sync-2689e753-next-phase.md`（158 个上游提交、33 个冲突、DB v27、生成循环重构、5 个静默破绽）。本文档只覆盖**同步之后**在设备上发现并修掉的问题。

---

## 0. 一句话概况

上游同步（`1695064a`）CI 全绿并合入 master 后，用户在设备上陆续发现**三个 CI 发现不了的回归**，全部定位并修掉：① 图标集被换成新字形 → 回退 `huge-icons` 到 1.3；② 聊天输入框从半透明变全透明 → 回退 `haze` 到 2.0.0-alpha03 并把 API 调用改回去；③ **点开搜索网页信封直接崩溃** → 把信封容器从 `LazyColumn` 换回非懒加载 `Column`。

**三个回归的共同点（本阶段最重要的教训）**：都是「合并采纳了上游的依赖升级或实现，与 fork 既有的约束/观感冲突」，而且**三者都编译通过、单测全过、CI 全绿** —— CI 只能证明编译和单测，证明不了 UI 行为。**同步上游之后必须上设备过一遍界面。**

---

## 1. 已完成工作（commit 链）

```
895a1f7f fix(ui): 修复搜索网页信封点开即崩——LazyColumn 嵌在 verticalScroll 里   ← master HEAD
c58f198d docs: 交接文档补记 haze 回退与 master CI 全绿
bb88cd06 docs: 交接文档头部状态行改正为当前的代码提交与 CI
0aff63d7 fix(ui): haze 回滚到 2.0.0-alpha03，修聊天输入框变全透明
9b8aad17 docs: 交接文档补记 huge-icons 回退 1.3 与 master CI 全绿
7c4e44f9 chore: huge-icons 换回 1.3
09ac2c19 docs: 交接文档——同步上游 2689e753（158 commits）+ 生成循环重构
```

相对同步前（`7042fa80`）：352 files changed, 23525 insertions(+), 6080 deletions(-)。

### 一、图标集换了一套 → `huge-icons` 回退 1.3（`7c4e44f9`）

**定位**：上游 `02a0c81c`（2026-08-23「chore: 更新依赖」）把 `huge-icons` 从 `1.3` 升到 `1.4`，整套图标字形改版。这是区间内唯一一次动该版本的提交（`lucide-icons` 未变；其余改图标行的提交都是新功能在用图标，不是换版）。

**连带必改**：1.4 做过一次大小写规范化，本仓库受影响**仅一处** —— `HugeIcons.Fullscreen`(1.4) → `HugeIcons.FullScreen`(1.3)，位于 `ChatInput.kt` 与 `TextArea.kt`。只改版本号会编译不过。

**核对方法**（本机 Gradle 缓存没有这个库，`.claude/skills/find-hugeicons` 查 JAR 的路子走不通）：两份独立证据 ——
- `D:/Temp/hugeicons-list.txt`（4688 个名字，含 `FullScreen`、不含 `Fullscreen` ⇒ 1.3 命名）
- `app/src/release/generated/baselineProfiles/baseline-prof.txt`（fork 自身在 1.3 时代的构建产物，含 `FullScreenKt`）

把源码全部 156 个 `HugeIcons.*` 与之交叉比对：**只有 `Fullscreen` 一个缺失**；其余 155 个（含 `Internet`/`Tiktok`/`DragDropVertical` 这些 1.4 之后才引入代码的名字）在 1.3 中都存在。改后复查 1.3 缺失 0 个 → 一次过，无需靠 CI 试错。

### 二、输入框从半透明变全透明 → `haze` 回退 2.0.0-alpha03（`0aff63d7`）

**根因**：合并采纳了上游对 haze 的 2.0 大版本升级（`alpha03 → beta02`）与其 API 迁移，而这次迁移**改变了样式语义**：

| | 合并前 alpha03 | 合并采纳的 beta02 |
|---|---|---|
| 样式 | `HazeMaterials.thin(containerColor = hazeTintColor)` —— **自带半透明着色** | `HazeBlurStyle.Material3 { blurRadius(12.dp) }` —— **只有模糊半径，没有着色** |
| 调用 | `hazeEffect(state) { blurEffect { style } }` | `hazeBlur(input = HazeInput.Sources(state), style)` |

**放大器**在 `ChatInput.kt`：开启模糊时 Surface 底色是**硬编码全透明**
`color = if (settings.displaySetting.enableBlurEffect) Color.Transparent else hazeTintColor`
——「半透明」完全由 haze 层提供 → haze 层只剩模糊、没了着色，就露出全透明底。

**改动三处**（少任一处都不行：只改版本号编译不过，只改 API 继续全透明）：
- `gradle/libs.versions.toml`：`haze = "2.0.0-alpha03"`；别名 `haze-blur-material3` → **`haze-blur-materials`**（模块 `dev.chrisbanes.haze:haze-blur-materials`）
- `app/build.gradle.kts`：`libs.haze.blur.material3` → `libs.haze.blur.materials`
- `ChatInput.kt`：导入与调用改回 `blurEffect` / `HazeMaterials` / `hazeEffect`

`ChatList.kt` / `ChatPage.kt` **未改** —— `HazeState` / `hazeSource` / `rememberHazeState` 是核心 API，两个版本一致（diff 过，合并前后零差异）。核对：`ChatInput.kt` 的 haze 行与 `7042fa80` **完全一致**。

### 三、搜索网页信封点开即崩 → 换回非懒加载 `Column`（`895a1f7f`）

**根因**：解冲突时采纳了上游 `SearchWebPreview` 的实现，其容器是 `LazyColumn`；而它渲染的落点 `ToolDetailSheet.kt` 内容区是**垂直滚动**的父容器：

```kotlin
Column(modifier = Modifier.fillMaxSize()
    .nestedScroll(sheetScrollConnection)
    .verticalScroll(scrollState)   // ← 垂直滚动
    ...) { if (...) jsonBody() else content() }   // ← renderer.Preview() → SearchWebPreview()
```

「无固定尺寸的垂直可滚动子组件」放进「同方向的滚动父容器」→ 子组件拿到**无限高约束** → 抛
`IllegalStateException: Vertically scrollable component was measured with an infinity maximum height constraints`。**点开必崩**，不是偶发。

官方文档就是这个例子（`developer.android.com/develop/ui/compose/lists`）：
`Column(Modifier.verticalScroll(state)) { LazyColumn { } }` // throws

合并前 fork 该函数用的是非懒加载 `Column`，所以从不触发；合并把容器换成 `LazyColumn` 就坏了。而且外层 sheet 本来就在滚动，信封自身再套一层滚动**既多余又非法**。

**修法**：容器换回 `Column`（`fillMaxWidth().padding(16.dp)`，`spacedBy(8.dp)` 不变），去掉 `item {}` 包裹、`items(items) { … }` → `items.forEach { … }`（`return@items` → `return@forEach`）。**内容全部原样保留**（合并新带进来的 query 前缀行、参数药丸、answer 卡片、结果卡片的 `publishedDate`、空结果时的 JSON 回退）。图片条**保留 `LazyRow`** —— 横向，与纵向滚动父容器不同轴，文档明确允许。顺带去掉循环内的 `remember(publishedDate)`（非懒加载容器里没有 per-item key）。清理两个已无用的 import（`LazyColumn`、`fillMaxHeight`）。

**同类隐患已全仓库排查**：所有文件里唯一的 `LazyColumn` 就在这一处；其余 `ToolUIRenderer` 实现（`MemoryToolsUIs` / `WorkspaceToolUIs` / `ToolUI`）都没有纵向懒列表。

---

## 2. 关键设计决策（认知遗留）

### 合并后的「绿 CI ≠ 对」
- **CI 只证明编译 + 单测，证明不了 UI 行为**。本阶段三个问题全部是 CI 绿之后才在设备上暴露的。**同步上游后必须上设备过界面**，尤其是：图标观感、模糊/透明效果、每个工具信封的详情页、滚动容器的嵌套。
- **「合并采纳上游实现」时要问一句：这段代码的运行环境变了吗？** 上游的 `SearchWebPreview` 在上游那边可能配的是 LazyColumn 容器，直接搬到我们的 `verticalScroll` sheet 里就是非法的。**冲突解决不能只看被冲突的那几十行，要看它的调用方和容器。**

### 刻意不跟随上游的依赖（会**每次同步都重现**）
| 依赖 | fork | 上游 | 为什么 |
|---|---|---|---|
| `huge-icons` | **1.3** | 1.4 | 1.4 改了整套图标字形，观感变化 |
| `haze` | **2.0.0-alpha03** | 2.0.0-beta02 | beta 的样式语义让输入框变全透明 |
两者**各自独立**，别混在一次改动里。详见 memory `huge-icons-pinned-1-3` 与 `haze-pinned-alpha03`。

### 上游同步本身
见 `2026-09-11-upstream-sync-2689e753-next-phase.md` §2 与 memory `upstream-sync-procedure`（含「无冲突但静默破坏」的 10 类分类）。

---

## 3. git 状态 / CI

- 分支 `master`，HEAD = `895a1f7f`；`sync/upstream-2026-09-10` 保留在 `4ca2bafe` 供对照
- 回滚锚点：合并前的 master = **`7042fa80`**

| run | sha | 结果 | 说明 |
|---|---|---|---|
| `34612251023` | 4ca2bafe | ✅ | sync 分支全绿（同步本身）|
| `34616257886` | 7c4e44f9 | ✅ | huge-icons 回退后 |
| `34617957749` | 0aff63d7 | ✅ | haze 回退后 |
| `34689873457` | **895a1f7f** | ✅ | **信封崩溃修复后 ← 当前 HEAD** |

全部为 `nightly-build-debug.yml`，含 `assembleDebug` + `:app:testDebugUnitTest`。

---

## 4. 恢复地图

| 文档 | 说明 |
|---|---|
| `2026-09-11-upstream-sync-2689e753-next-phase.md` | **上游同步主体**（158 提交 / 33 冲突 / DB v27 / 生成循环重构 / 5 个静默破绽 / CI 红→绿 4 次）|
| `2026-09-11-file-url-sandbox-tool-image-timeout-next-phase.md` | 再上一阶段（file:// 沙箱语义 + 工具结果图片通道 + 下载超时）|

| 本阶段改动文件 | 说明 |
|---|---|
| `gradle/libs.versions.toml` | `huge-icons = "1.3"`、`haze = "2.0.0-alpha03"`、`haze-blur-materials` |
| `app/build.gradle.kts` | `libs.haze.blur.materials` |
| `ui/components/ai/ChatInput.kt` | haze 用回 `hazeEffect`/`blurEffect`/`HazeMaterials.thin`；`HugeIcons.FullScreen` |
| `ui/components/ui/TextArea.kt` | `HugeIcons.FullScreen` |
| `ui/components/message/tools/BuiltinToolUIs.kt` | `SearchWebPreview` 容器 `LazyColumn` → `Column`（**崩溃修复**）|
| `ui/components/message/tools/ToolDetailSheet.kt` | **只读参考**：内容区是 `Column + verticalScroll`（崩溃的成因方）|

| 相关 memory | 说明 |
|---|---|
| `huge-icons-pinned-1-3` | **新增**：图标集刻意保持 1.3 + 怎么核对 1.3 的图标名 |
| `haze-pinned-alpha03` | **新增**：模糊库刻意保持 alpha03 + 三处必改 |
| `upstream-sync-procedure` | 同步流程 + 10 类静默破坏分类 |
| `windows-core-symlinks-false` | 本机符号链接退化 |
| `memory-system-handoff-chain` | 入口已指向本文档 |

---

## 5. 待办 / 挂起项

### ① 设备核验（唯一需用户上手，重点）

装 master `895a1f7f`，按三件事逐个确认：

1. **搜索网页信封**：点开 `search_web` 工具详情 —— 应正常显示（query 行 + 参数药丸 + answer 卡片 + 图片条 + 结果卡片含日期），**不再崩溃**；空结果时显示 JSON 回退
2. **输入框**：聊天页底部输入框恢复**半透明毛玻璃**（设置里「模糊效果」开启时）；关掉模糊设置时是不透明的底色
3. **图标**：图标恢复旧字形（不是 1.4 的新版）；输入框右下角的**全屏/展开**图标正常显示（就是 `FullScreen` 那个，改名后最容易注意到）

再叠加**上游同步本身的核验**（见 9-11 文档 §5-①，仍未做）：

4. **生成循环重构**（本次改动最大处）：普通对话、工具调用、工具审批（含连续审批）、停止生成、重新生成
5. **工具实时输出**：工作区 shell 命令应流式显示（`ToolOutput.OutputDelta` 通路）
6. **read_image**：视觉模型出图、不再 `invalid input`、死链 http 图片 20 秒失败
7. **上游新功能抽验**：工作区 Shell 兼容模式 + 终端多 Tab + html/svg 预览、消息发送队列、语音模式
8. **备份/恢复**：S3 与 WebDAV 备份 → 恢复，确认 `upload/`、`images/`、头像都在；跨包名恢复（debug↔release）仍能重定位 `file://`
9. **DB 迁移**：从 3.1.1（v26）升级，确认开库不崩（`Migration_26_27`）
10. **记忆**：7 个记忆工具、活跃记忆注入
11. **搜索**：snake_case 渠道参数生效、豆包渠道可用、结果的 `retrievedAt`/`publishedDate`

### ② 下次同步上游时的必做项（会重现）

- `huge-icons` 会被带回 1.4 → 改回 1.3，并把 `Fullscreen` 反向改回 `FullScreen`
- `haze` 会被带回 beta → 改回 `alpha03`，并同步改别名 / `app/build.gradle.kts` / `ChatInput.kt` 的 API 调用（见 §1-二 三处）
- 参见 memory `upstream-sync-procedure` 的 10 类静默破坏分类

### ③ 本阶段有意留下的偏差（不是漏做）

- **Exa 请求侧证据参数未采纳**：上游 `a8f8c3a1` 把证据参数改成 camelCase 并始终请求 text+highlights，与 fork 的 snake_case 契约冲突。已「输出侧保留证据字段、请求侧保持 fork 语义」，该特性只进来一半
- **`SearchPicker` 用混合方案**（保留 fork 多选 UI + 恢复上游 `SearchMode` 枚举），因为已合入的 `ChatInput.kt`/`ChatPage.kt` 同时需要两者
- **MCP 无效服务器名行为改为上游版**（现在会暂停消息队列）
- **搜索门控用上游的 `shouldUseExternalWebSearch`**（若要求总是用外挂搜索，需同时改 `ChatToolFactory.kt` 与 `ChatService.kt:811`）
- **`pre` 变体未验证**（`nightly-build-pre.yml` 历史上从未跑过）
- **`baselineProfiles/*.txt` 已过期**（里面还记录着已不存在的 `GenerationHandler` 构造器）

### ④ 历史挂起（延续）

- release / pre CI 未跑；诊断日志仍在（设备确认后清理）
- **OCR 调用没有时间上界**（`OcrTransformer.performOcr` 走全局 10 分钟 readTimeout × 4 次重试）；与 `Call.await()` 缺 `invokeOnCancellation` 同源
- **HTML 渲染路径点 `file://` 链接会崩**（`FileUriExposedException`）
- **`ImageLazyLoadTransformer` 的降级路径**会把 upload/workspaces 之外的图标记成设备绝对路径
- **BMP 能识别但发不出去**（`FileEncoder.guessMimeType()` 缺 BMP 分支）
- **`workspaces/` 仍不在备份内**（见 memory `backup-coverage-gaps`）
- **引用计数 LIKE 查询性能**；`PromptPage.kt` 一个既有的未使用 import（`FileDownload`）

---

## 6. 技术约束 / 惯例（必须遵守）

### 工程流程
- **本机无 Android 编译器**：不运行 gradle。编译验证全靠 CI。
- **CI 判定铁律**：先 push 再 `gh workflow run nightly-build-debug.yml --repo DevLintMar/rikkahub --ref master`；**`gh run watch <id> --exit-status`** 实时监控；`gh run view <id> --json conclusion,headSha` 核对 headSha；**结果无论红绿主动汇报**。
- **CI 绿 ≠ 功能对**（本阶段的最大教训）。三处回归都是 CI 绿之后设备上才发现的。
- 中文 conventional commit；字符串六 locale；工具 description/注入文本/JSON 用英文。
- 文件删除走 `~/.claude/scripts/trash.sh`（绝不 `rm`）；force-push 需用户明确要求。
- 本机 git 换行警告（LF→CRLF）为仓库既有状态，非错误。

### 排查「合并引入的 UI 崩溃」时
- **先确认崩溃点的运行时环境**：容器链、约束、父级滚动方向 —— 光看被改的那几行看不出来（信封崩溃就是这样：改动在被冲突的函数里，祸根在调用方的 sheet）。
- **对照合并前版本**（`git show <pre-merge-sha>:<file>`）找出「能跑的写法」，这是最快的对照实验。
- 涉及 **Compose 测量/滚动/布局约束** 的判断，**先查 Context7 的官方文档再下结论**（本阶段用 `developer.android.com/develop/ui/compose/lists` 确认了同轴嵌套滚动的非法性）。
- 改完顺手**全仓库扫同类隐患**（本阶段扫了所有 `ToolUIRenderer` 是否有纵向懒列表）。

### 无编译器时的本机静态检查链（比空等 CI 强）
1. **未解析 import 扫描**：把「出现在任意非 import 行的标识符」当作已声明
2. **`libs.*` alias 全量比对** `gradle/libs.versions.toml`
3. **`R.string.X` 全量比对** `values/strings.xml`（曾抓到真实缺口）
4. **依赖版本/图标名交叉核对**（本阶段用 1.3 清单核对 156 个图标名）
5. 冲突标记全树扫描；6. 括号/花括号配平；7. 重复 import 扫描（`grep -oE '^import [A-Za-z0-9_.]+' f | sort | uniq -d`）
- 注意 `/tmp` 在本机映射到 `D:\Temp`，但 **Python 不认 `/tmp`**，要写 `D:/Temp/...`

### 代码风格
- suspend 内并发用 try/catch，重抛 `CancellationException`；`runCatching` 不包 suspend。
- 使用请求日志页（`Logging.log`）而非 logcat 做设备诊断。
- `retryOnFailure` 的「不重试」分支**必须 throw**。
- Room 迁移要**容忍已存在**（`PRAGMA table_info` 探测）—— fork 支持恢复外来备份。

---

## 7. 停靠点

- **已完成**：上游 158 提交同步（详见 9-11 文档）+ 同步后的三个设备回归修复（图标回退 1.3、haze 回退 alpha03、信封崩溃修复）。master `895a1f7f`，CI 全绿。
- **待确认**：§5-① 设备核验 —— **优先确认这三件事**：信封点开不崩、输入框恢复半透明、图标恢复旧字形；再叠加同步本身的核验（生成循环、工具实时输出、备份恢复、DB 迁移）。
- **下一阶段**：无明确用户需求。候选：设备核验（必须）、清理诊断日志、release/pre CI 验证、OCR 超时/取消（顺带修「停止生成不生效」）。
- **恢复动作**：读本文档 §4/§5；开始前先让用户设备核验。
