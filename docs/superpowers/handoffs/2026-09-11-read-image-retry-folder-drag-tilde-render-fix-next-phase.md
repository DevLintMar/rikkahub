# 交接文档：read_image 重试 + 文件夹拖拽排序 + 单波浪删除线语义修正 — 下一阶段入口

**日期**：2026-09-11
**目的**：上下文清理前的完整交接。新会话读本文档即可续接。master HEAD = `7b4599a`，工作树干净，debug CI 全绿（`34594595672` success，headSha `7b4599a` 核对一致）。

---

## 0. 一句话概况

本阶段三件事：① 给 `read_image` 的 OCR 调用与 http 下载加**失败自动重试**（1 次初始 + 3 次重试）；② 文件夹 chip 支持**长按拖拽排序**（过程中踩到并修掉"完全拖不动"的手势冲突）；③ 修正 **单波浪 `~x~` 被误渲染成删除线**的问题——中途尝试的"转义成 `\~`"方案因 AnnotatedString 路径不消费反斜杠而在界面上显示出反斜杠，已废弃并改为**渲染层抑制**。master `19b2772`（上一交接）→ `7b4599a`（6 commits），沿途 4 轮 CI 红均当场定位修复，最终绿。

---

## 1. 已完成工作（commit 链）

### 一、read_image 工具失败自动重试（7ae7f93）

- 新增 [Retry.kt](app/src/main/java/me/rerere/rikkahub/utils/Retry.kt)：通用 suspend 重试助手
  ```kotlin
  suspend fun <T> retryOnFailure(
      attempts: Int, delayMillis: Long = 1_000L,
      onRetry: (attempt: Int, error: Exception) -> Unit = { _, _ -> },
      block: suspend () -> T,
  ): T
  ```
  `CancellationException` 立即重抛（不吞取消），其余异常延迟 1s 后重试，全部失败抛最后一次异常
- [OcrTransformer.performOcr](app/src/main/java/me/rerere/rikkahub/data/ai/transformers/OcrTransformer.kt)：`provider.generateText` 包进重试（`OCR_MAX_ATTEMPTS = 4`），**空 choices / 空响应文本也走重试**（原来是直接返回 `[ERROR, OCR failed: empty response]`）；缓存只命中成功结果
- [ReadImageTools.downloadImageToUpload](app/src/main/java/me/rerere/rikkahub/data/ai/tools/ReadImageTools.kt)：http 下载包进重试（`DOWNLOAD_MAX_ATTEMPTS = 4`）；**文件落盘只在下载成功后做一次**，不随重试产生重复文件
- 每次重试写一条 `Logging.log` 到**请求日志设置页**（`performOcr: attempt N failed, retrying: ...` / `downloadImageToUpload: ...`），设备上无需 logcat 即可观察
- 新增 [RetryTest.kt](app/src/test/java/me/rerere/rikkahub/utils/RetryTest.kt)（4 用例）
- **设计边界**：重试放在**单图操作层**（单次 OCR 调用 / 单次下载），不是整次工具调用重试——一批 8 张图里只有失败的那张重试，已成功的图不重复消耗；顺带覆盖视觉模型走 http 下载的场景。工作区 rootfs 读取、本地文件缺失等非瞬时错误不重试

### 二、文件夹长按拖拽排序（eec4956 → 540beac → 7b4599a）

数据层：
- [FolderDAO](app/src/main/java/me/rerere/rikkahub/data/db/dao/FolderDAO.kt)：新增 `updateSortIndex(id, sortIndex)`
- [FolderRepository](app/src/main/java/me/rerere/rikkahub/data/repository/FolderRepository.kt)：新增 `reorderFolders(folders)`（下标即顺序，逐条写 `sort_index`）；`createFolder` 改为分配 `sortIndex = max+1`（新文件夹排最后）
  - 注意：`getFoldersOfAssistant` 返回 **Flow**，取 max 必须 `.first()`（漏了导致 CI 编译错 `Unresolved reference 'maxOfOrNull'`，540beac 修）
- [ChatDrawerVM](app/src/main/java/me/rerere/rikkahub/ui/pages/chat/ChatDrawerVM.kt)：`reorderFolders(folders: List<Folder>)`

UI（[ChatDrawer.kt](app/src/main/java/me/rerere/rikkahub/ui/pages/chat/ChatDrawer.kt) `FolderBar`）：
- 接入 `sh.calvin.reorderable`（项目内 7 处先例，依赖已在 `libs.versions.toml`）
- **拖拽期间用本地镜像列表 `localFolders`**：`onMove` 立即交换（跟手），**拖拽结束的一次性持久化**（`snapshotFlow { reorderableState.isAnyItemDragging }` 下降沿）。不以 Room Flow 顺序做交换源——Flow 回流有延迟，拖拽中多次 onMove 会索引错乱
- 拖拽中忽略 Flow 回流（`if (!reorderableState.isAnyItemDragging) localFolders = folders`），避免跳动
- 位移→下标映射：首项「聊天」chip 占 index 0，故 `from.index - 1` / `to.index - 1`

**"完全拖不动"的根因与修法（重要）**：
- `FolderChip` 原用 `combinedClickable(onClick, onLongClick = {})`。**即使 `onLongClick` 是空实现，它也会消费长按**——长按事件到达了它（此前长按确实能弹出重命名/删除菜单就是证据），`longPressDraggableHandle` 因此永远等不到长按 → 拖拽完全无法启动
- 修法：改为**纯 `clickable`（不带长按）**，并把拖拽手柄放到**修饰符链最内层**（`.clip().clickable{}.then(dragHandleModifier)`）——指针事件先到内层，长按稳定抢占为拖拽；轻点仍正常触发 clickable
- 对照先例：助手页标签行（AssistantPage:306）、设置页搜索服务列表（SettingSearchPage:149）都是"可点 chip + 长按拖拽手柄"，**它们都不带 `onLongClick`**

**交互变更（需用户知悉）**：长按原是操作菜单，现被拖拽占用 → **菜单改为单击已选中的文件夹时弹出**（单击未选中文件夹仍是切换选中）。

### 三、单波浪 `~x~` 假删除线（eec4956 → bad441c → 3b6db3a → 7b4599a）

**根因（白盒实测实锤，非推测）**：
GFM 规范允许单波浪删除线，本项目 fork 也实现了它，但 fork 的 `StrikeThroughDelimiterParser.process()` 用 `while (index > 0)` 遍历 delimiter 数组——**下标 0 的 opener 永远不被处理**。于是：

| 输入 | tilde opener 下标 | 是否渲染删除线 |
|---|---|---|
| `是~哈哈~` | 0 | ❌ 不渲染 |
| `**重点内容**是~哈哈~` | 4（`**` 的 4 个 delimiter 排前面） | ✅ 渲染 |

即**有没有删除线取决于上文是否恰好出现 `*`/`_`**，行为不稳定；中文里 `~` 常作范围/约数符号（`30%~50%`、`~25度`），被误渲染会歪曲内容。

**第一轮尝试（已废弃）：转义 `\~` 后重新解析**
- 做法：解析 AST 找出原文非 `~~` 包裹的 STRIKETHROUGH 节点 → 把其中 `~` 转义为 `\~` → 重新解析
- **失败原因**：AnnotatedString 渲染路径（[Markdown.kt](app/src/main/java/me/rerere/rikkahub/ui/components/richtext/Markdown.kt) 的 `LeafASTNode` 分支）**直接输出 TEXT token 原文、不消费反斜杠转义** → 界面上显示出 `\~内容\~`（用户报告）。HTML 路径会消费转义，两条路径表现还不一致
- 相关文件已 **trash 掉**：`SingleTildeStrikeGuard.kt` / `SingleTildeStrikeGuardTest.kt`（回收站可恢复，但无恢复价值）

**最终方案：渲染层抑制**（解析树保持原样，只在渲染时判断节点原文）
- 新增 [SingleTildeStrikethrough.kt](app/src/main/java/me/rerere/rikkahub/ui/components/richtext/SingleTildeStrikethrough.kt)：
  - `isDoubleTildeStrikethrough(raw: CharSequence): Boolean` — 纯函数谓词，判断原文是否恰为 2 个波浪包裹（`~~~x~~~`、`~a~~`、`30%~50%` 都 false）
  - `SingleTildeSafeGfmFlavour : GFMFlavourDescriptor` — 覆盖 `createHtmlGeneratingProviders`，把 `STRIKETHROUGH` 换成：双波浪沿用默认 provider（`span.user-del`）、非双波浪则 `consumeHtml(HtmlGenerator.leafText(text, node))` 输出纯文本原文
- [Markdown.kt](app/src/main/java/me/rerere/rikkahub/ui/components/richtext/Markdown.kt)：**两条** STRIKETHROUGH 分支都按谓词判断——
  - `MarkdownNode` 块级分支（原会连 `~~` 分隔符一起渲染出来，顺带修正）
  - `appendMarkdownNodeContent` 行内分支（段落/合并段落走这条）
  - 双波浪时保留 `children.trim(TILDE, 2)` 修剪；非双波浪时递归渲染全部 children（已验证 children 文本拼接 == 节点原文，7 用例全 OK）
- [MarkdownNew.kt](app/src/main/java/me/rerere/rikkahub/ui/components/richtext/MarkdownNew.kt)：flavour 换为 `SingleTildeSafeGfmFlavour`；`appendHtmlInlineElement` 的 `span.user-del` → `LineThrough` 分支**保留**（双波浪节点仍产出该 span）
- 新增 [SingleTildeStrikethroughTest.kt](app/src/test/java/me/rerere/rikkahub/ui/components/richtext/SingleTildeStrikethroughTest.kt)（7 用例，纯 JVM 谓词测试）

**语义定型**：只有 `~~x~~` 是删除线，`~x~` 按纯文本渲染（波浪原样显示）。

**设计上的一个稳健性**：渲染层守卫判的是**节点原文**而非解析器行为，所以即使将来有人去改 fork 的 off-by-one（`while (index > 0)` → `>= 0`），守卫结论不变。

---

## 2. 关键设计决策（认知遗留）

### 单波浪删除线
- **不改 fork**：fork（`github.com/rikkahub/markdown`）是用户自己的仓库，理论上可修，但修那个 off-by-one 会让单波浪删除线**在更多场景生效**——与产品预期相反。选择在 app 渲染层抑制。
- **渲染层抑制优于改文本**：任何"先改文本再解析"的方案都要面对"两条渲染路径对转义/原文的处理不一致"（AnnotatedString 不消费 `\`，HTML 会），改渲染层是唯一对两条路径都成立的落点。
- **两条渲染路径**：`MarkdownBlock` 走 AnnotatedString（`hasHtml == false`）或 HTML（`hasHtml == true`，转 `MarkdownNew`）。改 markdown 渲染相关逻辑**必须同时核对这两个文件**。
- **解析器 fork 的行为不可假设**：本项目用的是 fork（`com.github.rikkahub:markdown`，版本号就是 commit sha），且 fork 有自研改动（CJK flanking 修复、内联数学货币符号修复、单/双波浪删除线实现）。遇到 markdown 渲染问题**先白盒实测**（见 §6 调试工具链），不要照 CommonMark 规范或上游 JetBrains/markdown 行为推断。

### 文件夹拖拽
- **chip 上的点击与长按拖拽不能共存于同一 modifier**：`combinedClickable` 的长按会吃掉 `longPressDraggableHandle` 的长按。要么纯 `clickable`+手柄、要么把菜单放别处——本阶段选了前者 + "单击已选中项弹菜单"。
- **拖拽排序的持久化时机**：拖拽中只动本地镜像、松手才落库。理由：Room Flow 回流异步，边拖边写会让"库顺序"和"屏幕顺序"互相打架。
- **`getFoldersOfAssistant` 是 Flow**：Repository 里任何"取当前列表做计算"都要 `.first()`。

---

## 3. git 状态 / CI

- 分支 `master`，HEAD = `7b4599a`，工作树干净
- **debug CI 全绿**：`34594595672` success（headSha `7b4599a` 核对一致）
- 本阶段 CI 红→绿记录：
  | run | sha | 失败原因 | 修复 |
  |---|---|---|---|
  | `34028668015` | eec4956 | `maxOfOrNull` 在 Flow 上不存在 | 加 `.first()`（540beac） |
  | `34029035067` | 540beac | 守卫单测 5 例失败：转义把 `~` **替换**成 `\` 而非前置插入 | 补 `append('~')`（bad441c） |
  | `34029644000` | bad441c | 余 2 例失败：孤立单波浪本就不产出节点，测试期望写错 | 修正期望（3b6db3a） |
  | `34030138689` | 3b6db3a | — 绿（但用户随后报告 `\~` 显示 + 拖不动） | 渲染层重写（7b4599a） |
  | `34594595672` | 7b4599a | — **绿** | — |

---

## 4. 恢复地图

| 台账/文档 | 路径 |
|---|---|
| 上一交接（图片懒加载） | `docs/superpowers/handoffs/2026-08-12-image-lazy-load-refcount-next-phase.md` |
| 本阶段核心文件 | `utils/Retry.kt`、`data/ai/transformers/OcrTransformer.kt`（performOcr 重试）、`data/ai/tools/ReadImageTools.kt`（下载重试）、`ui/components/richtext/SingleTildeStrikethrough.kt`（谓词 + flavour）、`ui/components/richtext/Markdown.kt`（两条 STRIKETHROUGH 分支）、`ui/components/richtext/MarkdownNew.kt`（flavour + span.user-del）、`ui/pages/chat/ChatDrawer.kt`（FolderBar/FolderChip）、`ui/pages/chat/ChatDrawerVM.kt`、`data/repository/FolderRepository.kt`、`data/db/dao/FolderDAO.kt` |
| 相关 memory | `memory-system-handoff-chain`（本文档为最新入口） |

---

## 5. 待办 / 挂起项（按优先级）

### ① 设备核验（唯一需用户上手）

装 `7b4599a` debug 包：

**单波浪删除线**
1. `**重点内容**是~哈哈~` → 显示纯文本 `~哈哈~`（**无反斜杠、无删除线**）— 本次修复的直接目标
2. `~~删除线~~` → 正常删除线；`**重点**是~~哈哈~~` → 正常
3. `30%~50%`、`~25度` → 纯文本
4. 含 HTML 的消息（表格/`<div>` 等，走 `MarkdownNew` 路径）里的单波浪同样正确

**文件夹拖拽**
5. 长按文件夹 chip → 可拖动、左右交换顺序、松手后顺序保持（**重启 app 顺序仍在**）
6. 轻点文件夹 → 切换选中；**再次轻点已选中的文件夹 → 弹出重命名/删除菜单**
7. 「聊天」「新建」两个固定 chip 不可拖、不被拖过头

**read_image 重试**
8. 弱网/断网时让 AI 调 `read_image` → 请求日志页出现 `performOcr: attempt N failed, retrying:`，网络恢复后成功返回而非直接报错
9. 视觉模型走 http 图片下载失败重试同理

**上一阶段遗留（仍未核验）**
10. 图片懒加载闭环：URL 标记 `file:///upload/xxx`、read_image 信封（Image03 图标/比例宽度图）、重新生成不占位、引用计数回收（`refs=0 -> DELETE`）、Gemini 视觉 read_image 出图（`$ref` 工具结果通道，重点）

### ② 遗留 Minor / defer（不阻塞）

- **release / pre CI 未跑**：convention plugin + rikkahub.keep + optimization 混淆路径未验证（历史挂起延续）—— ✅ **2026-09-12 已解决**：`release` 已多次真跑且绿；`pre` 自 `1a542d72` 起真跑并绿 —— 此前那些 success 是 `check` 判「24h 无提交」把 `build` 整个 skip 后的假绿）
- **诊断日志仍在**（belt skipped / FILES_DELETE / cleanup refs / ChatImg / ScrollFrameSampler）：设备确认后清理
- **引用计数 LIKE 查询性能**：`countMessageNodesContaining` 全表 LIKE 扫描，会话多时删除操作可能略慢——当前规模可接受
- **`MarkdownUtils.stripMarkdown` 的 `~~` 处理**：该函数（TTS 用）不涉及本次改动，但若将来统一删除线语义可一并核对
- **重组性能**：`FolderBar` 每次 `onMove` 都会重建 `localFolders` 列表（文件夹数量少，无影响）
- **历史挂起**（更早交接延续）：Firecrawl crawl/batch、Jina 多 URL、Exa ids 复用、Metaso scope、乱召回、Task 12 ripgrep、UpdateChecker `return@flow`

### ③ 下一阶段候选（未开工）

无明确用户需求。可选项：设备核验、诊断日志清理、release/pre CI 验证、或用户新需求。

---

## 6. 技术约束 / 惯例（必须遵守）

### 工程流程
- **本机无 Android 编译器**：不运行 gradle。静态编写 + review，编译验证全靠 CI。
- **CI 判定铁律**：先 push 再 `gh workflow run nightly-build-debug.yml --repo DevLintMar/rikkahub --ref master`；**`gh run watch <id> --exit-status`** 实时监控（不手写轮询）；`gh run view <id> --json conclusion,headSha` 核对 headSha；**结果无论红绿主动汇报**。
- **JVM 单测风格**：`testImplementation` 只有 junit 4.13.2，无 kotlin-test；`org.junit.*`；方法名反引号内禁 `/`；**不要用 kotlin.test**。
- 中文 conventional commit；字符串六 locale（values/zh/zh-rTW/ja/ko-rKR/ru）；工具 description/注入文本/JSON 用英文。
- 文件删除走 `~/.claude/scripts/trash.sh`（绝不 `rm`）；force-push 需用户明确要求。
- 本机 git 换行警告（LF→CRLF）为仓库既有状态，非错误。

### markdown 渲染（本阶段新增的高价值经验）
- **改 markdown 渲染必须同时改两个文件**：`Markdown.kt`（AnnotatedString 路径，含段落行内 + 块级两个 STRIKETHROUGH 分支）与 `MarkdownNew.kt`（HTML 路径）。
- **两条路径对转义的处理不同**：AnnotatedString 的 `LeafASTNode` 分支直接输出原文（**不消费 `\` 转义**），HTML 路径经 `HtmlGenerator.leafText` 会消费。任何"改文本再解析"的方案都会在此分裂 → 优先改渲染层。
- **flavour 是解析与 HTML 生成的双入口**：`MarkdownNew.kt` 的 `flavour` 同时喂给 `MarkdownParser` 和 `HtmlGenerator`，换 flavour 两处一起变。

### markdown 解析器调试工具链（本机可用的白盒手段）
本机**没有 Android 编译器，但有 JDK 和 kotlinc**，可以直接跑 fork 的解析器做白盒实验——本阶段的根因就是靠它实锤的：

```bash
JDK="D:/IDEA/gradleRepository/jdks/jetbrains_s_r_o_-21-amd64-windows.2"   # JBR 21
KOTLINC="D:/Temp/kotlinc-dist/kotlinc/bin/kotlinc.bat"                     # 已下载
# 1) 取部署中的 fork jar（版本 sha 见 gradle/libs.versions.toml 的 markdown = "d79a97cc8e"）
curl -sL -o D:/Temp/markdown.jar \
  https://jitpack.io/com/github/rikkahub/markdown/d79a97cc8e/markdown-d79a97cc8e.jar
# 2) 编译 + 运行（-include-runtime 打包 kotlin stdlib）
"$KOTLINC" -cp D:/Temp/markdown.jar Probe.kt -include-runtime -d probe.jar
"$JDK/bin/java" -cp "probe.jar;D:/Temp/markdown.jar" ProbeKt
```
- **编码坑（重要）**：本机 JVM stdout 是 **GBK**，中文输出要 `| iconv -f GBK -t UTF-8` 才能读；且**用 Write 工具写的 UTF-8 源文件里的中文字符串会解析错乱**（`-Dfile.encoding=UTF-8` 未能可靠修复）→ **探测解析器行为时优先用纯 ASCII 输入**（结论完全等价），中文场景交给 CI 上的 JVM 单测（Linux/UTF-8 无此问题）。
- **白盒反射钻取 delimiter 状态**（本阶段定位根因的关键手段）：
  ```kotlin
  // 拿到 EmphasisLikeParser 的 private 方法
  val collectM = EmphasisLikeParser::class.java.getDeclaredMethod(
      "collectDelimiters", TokensCache::class.java, TokensCache.Iterator::class.java)
  val balanceM = EmphasisLikeParser::class.java.getDeclaredMethod(
      "balanceDelimiters", java.util.ArrayList::class.java)
  // 构造 iterator：RangesListIterator 的公开构造是 (TokensCache outer, List<IntRange>)
  val iterCtor = TokensCache.RangesListIterator::class.java
      .getDeclaredConstructor(TokensCache::class.java, List::class.java)
  // 然后逐个打印 DelimiterParser.Info 的 canOpen/canClose/closerIndex
  ```
- **fork 源码查询**：`gh api repos/rikkahub/markdown/contents/<path>?ref=<sha> --jq '.content' | base64 -d`；目录树 `gh api "repos/rikkahub/markdown/git/trees/<sha>?recursive=1"`。

### 图片/懒加载（上一阶段，仍有效）
- `checkFilesDelete` belt：upload 附件不随会话更新自动删除；删除动作走 `cleanupUploadFilesIfUnreferenced(files, excludeConversationId)`。
- 引用计数**排除当前会话**（并发取消 job 的兜底保存会在当前会话残留引用）。
- `ImageLazyLoadTransformer` 只处理 USER role；只作用于发送副本（不写回 session.state）。
- 标记前缀 `IMAGE_LAZY_LOAD_MARKER_PREFIX = "[The user attached "`（Conversation.kt 顶层 const）。

### 代码风格
- suspend 内并发用 try/catch，重抛 `CancellationException`；`runCatching` 不包 suspend。
- 工具层只调 Repository/FilesManager 公开 API。
- 使用请求日志页（`Logging.log`）而非 logcat 做设备诊断。

---

## 7. 停靠点

- **已完成**：read_image OCR/下载自动重试、文件夹长按拖拽排序（含"拖不动"根因修复）、单波浪删除线语义修正（转义方案废弃 → 渲染层抑制）。master `7b4599a`，debug CI 全绿。
- **待确认**：设备核验（§5-①，重点是 `**重点**是~哈哈~` 无反斜杠无删除线、文件夹可拖动且顺序持久、弱网下 read_image 重试日志）。
- **下一阶段**：无明确需求；候选 = 设备核验、诊断日志清理、release/pre CI 验证。
- **恢复动作**：读本文档 §4/§5；开始前先让用户设备核验本阶段三项修复。
