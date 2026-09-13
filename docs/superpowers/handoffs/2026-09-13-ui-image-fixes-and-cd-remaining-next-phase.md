# 交接文档：五项界面问题修复完毕 + C/D 类剩余项清单

**日期**：2026-09-13（当日第一份；前一份为 `2026-09-12-b-class-23-items-implemented-next-phase.md`）
**状态**：**代码 HEAD = `88b840d5`**（debug CI 在此绿，见 §3）；工作树干净，全部已 push。
**目的**：用户从设备上一次性报了五项界面问题（网页视图图片、图片导出、设置图标、选项位置、滚动高度跳动），
本轮全部修完并过 CI。**下一阶段按用户指令做 C 类与 D 类的剩余项**（清单见 §5.2 / §5.3）。

> **A/B/C/D/E 是什么**：更早一轮「告知我所有问题」的 62 项清单分组 ——
> **A** 合并已静默破坏的 11 项｜**B** 「半截」/行为变化 23 项｜**C** 结构性风险：下次同步必踩 17 项｜
> **D** 历史挂起 6 项｜**E** 文档/memory 已过时。
> A/B/E 已全部消化；C 类剩 7 件、D 类 6 件**一件未动** —— 就是下一阶段的目标。
> 本轮这五项**不属于 ABCDE 任何一类**，是用户新报的问题。

---

## 0. 一句话概况

五项全部修完，各自**根因都不是表面现象**（详见 §2.1）：网页视图是三条独立原因叠加，导出是固定 100ms
与异步加载竞速，图标是「名字存在但字形是空壳」，高度跳动是占位图方形内在尺寸被列表回收放大。
**5 个提交**，新增 3 个文件（1 源码 + 2 测试），debug CI 全绿、0 个 skipped。

---

## 1. 已完成（commit 链）

```
88b840d5 docs: 更新日志补本轮五项界面修复
a3ead8a7 fix(setting): 网络项换用地球图标；时间提醒间隔移入提示词板块
a87f0c90 fix(chat): 缓存图片宽高比，消除翻页时列表高度跳动
036395a2 fix(chat): 图片导出改为同步预加载，修掉导出图里图片空白
cab09b3f feat(webview): 网页视图渲染本地图片（消息附件与 file:// 图片链接）  ← 本阶段首个提交
```

**回滚锚点 = `66b49f44`**（上一个阶段的 HEAD）。改动面 17 文件、+797 / −104。

### 1.1 逐条落点

| # | 用户报告 | 处置 | 落点 |
|---|---|---|---|
| 1 | 网页视图里图片链接（url、file）渲染不出来 | ✅ 本地图片改写为应用内虚拟域名的 URL + 拦截器读文件；附件一并渲染；放开混合内容 | `MarkdownWeb.kt`、`WebViewLocalAssets.kt`、`WebViewPage.kt`、`ChatMessage.kt`、`ChatMessageActions.kt`、`WorkspaceFileUrlResolver.kt`、`ImageLazyLoadTransformer.kt` |
| 2 | 按图片导出时插入的图片渲染不出来 | ✅ 导出前把图片**同步**预加载成 `ImageBitmap`，内容改用 `Image(bitmap=)` | `Export.kt` |
| 3 | 偏好设置-网络这一项没有图标 | ✅ `HugeIcons.Internet` → `HugeIcons.Globe02` | `SettingPreferencesPage.kt` |
| 4 | 时间提醒间隔移到提示词板块、和时间提醒开关放一起 | ✅ 连对话框一起从「记忆」页搬到「提示词」页 | `AssistantMemoryPage.kt`、`AssistantPromptPage.kt` |
| 5 | 翻页时图片卸载重载导致高度跳动 | ✅ 缓存首次加载的真实宽高比，重新组合直接按比例占位 | **新增 `ImageAspectRatioCache.kt`**、`ZoomableAsyncImage.kt`、`Markdown.kt` |

> 用户当时把第 2 项描述成「思考与工具调用链图片也有所不同，将导出向正常聊天页面修复」。
> **追问后用户选了「最小修复：图片能出就行」** —— 工具链渲染**刻意没动**：导出的工具步骤仍是
> `ExportedToolStep` 那套按工具名手写的 `when`（`contentVisible = false, content = null`，不画输出），
> 没有接入聊天页的 `BuiltinToolUIs` renderer 注册表。**这是有意取舍，不是漏做。**

### 1.2 本轮新增的文件

| 文件 | 用途 |
|---|---|
| `app/.../ui/components/richtext/ImageAspectRatioCache.kt` | 图片宽高比 LRU 缓存（256 条，key = Coil model） |
| `app/.../test/.../richtext/ImageAspectRatioCacheTest.kt` | 8 个用例：未知/空 key/比例方向/非正尺寸/覆写/容量淘汰/命中保护 |
| `app/.../test/.../richtext/MarkdownWebPreviewTest.kt` | 11 个用例：编码往返、`file://` 图片改写、死链保留、`https` 不受影响、`<img src>`、普通链接不动、附件追加 |

### 1.3 顺手做的结构收敛

`resolveDisplayPath`（懒加载标记注入）原本自带一份「宿主 File → 沙箱路径」映射，网页视图要用同一种映射
就变成**第三份**。已把映射收进 `WorkspaceFileUrlResolver.toSandboxPath(file, filesDir)`，
`ImageLazyLoadTransformer.resolveDisplayPath` 改为委托它（**保留原有的沙箱外 `file://<绝对路径>` 降级分支**，
`ImageLazyLoadTransformerTest` 的 6 个断言全部不变）。这与 **B16**（终端挂载表第三处硬编码）是同一类问题，
见 §2.3。

---

## 2. 关键决策与认知

### 2.1 五项各自的根因（都不是表面现象）

**① 网页视图的图片 —— 三条独立原因叠加，只修一条不会好**

- **`file://` 链接必然失败**：预览页 origin 是 `https://rikkahub.local`（`WebViewLocalAssets.kt` 的 `WEB_VIEW_BASE_URL`），
  从这个 origin 引用 `file://` 子资源会被 WebView 直接拦掉 —— `allowFileAccessFromFileURLs` 对**非 file origin 不生效**。
  拦截器此前**只**认 `https://rikkahub.local/assets/*`。Compose 侧有 `WorkspaceFileUrlResolver` 把沙箱路径
  解析成真实文件，**WebView 这条路完全没有这个解析器**。
- **`http://` 图片被混合内容策略挡下**：`WebView.kt` 只设了 `javaScriptEnabled` / `domStorageEnabled` /
  `allowContentAccess`，**没设 `mixedContentMode`** → 默认 `MIXED_CONTENT_NEVER_ALLOW`。
- **消息附件根本进不了 HTML**：`ChatMessage.kt` 只收集 `UIMessagePart.Text` 拼 markdown，
  `UIMessagePart.Image` 是独立的 part，从头到尾没被带上。

改法：本地图片改写为 `https://rikkahub.local/local/{workspaceId 或短横线}/{hex 编码的沙箱路径}`，
拦截器按沙箱路径读文件返回。
**路径解析仍走 `WorkspaceFileUrlResolver`** —— 路径穿越防护、拒绝内核伪文件系统、拒绝真机绝对路径的逻辑全部复用，
所以即使 markdown 是被模型输出牵着走的，也拿不到沙箱外的文件。预览页设 `MIXED_CONTENT_COMPATIBILITY_MODE`
（只放行图片/媒体子资源，脚本与 XHR 仍禁止），**只对 data 分支设，URL 分支不动**。

**② 图片导出 —— 固定等待 vs 异步加载的竞速**

`BitmapComposer.composableToBitmap` 在 `Handler.postDelayed(..., 100)` 之后截图，注释写着「delay to allow ComposeView
to finish rendering」。但 Coil 的 `AsyncImage` 是异步加载的，网络图几百毫秒到数秒 —— **100ms 截到的就是空白**。
改成导出前用 `imageLoader.execute()` 把图片全部同步加载成 `ImageBitmap`，内容里 `Image(bitmap=...)` 渲染，
竞速彻底消失。取不到的图直接跳过、不留空白框。

**③ 网络图标 —— 名字存在 ≠ 字形能显示**

`SettingPreferencesPage.kt` 里那一行**本来就有** `leadingContent = { Icon(HugeIcons.Internet, null) }`，
`HugeIcons.Internet` 在钉住的 huge-icons **1.3** 里也确实存在（按 1.3 清单核对过）、CI 也编译得过 ——
**但界面上是空白**。1.4 的整套字形改版让 1.3 里这个名字成了空壳，「名字存在」只能保证编译、保证不了画得出东西。
排查手法：某个图标**只有它自己不显示**、同组其他项正常、且该组走同一条渲染路径（`CardGroup` 五项同构）时，
先怀疑字形本身，别去查布局。

**④ 时间提醒间隔 —— 开关与参数拆在两个页面**

`enableTimeReminder` 开关在 `AssistantPromptPage`，而它的参数 `timeReminderIntervalMinutes` 在 `AssistantMemoryPage`。
两件同一件事的东西拆在两页不合直觉。连对话框带状态一起搬到开关旁边；**仍然只在开关打开时显示**（保持原行为，
不做「显示但点不动」的死控件 —— 死按钮是本仓单独盯过的一类问题）。

**⑤ 高度跳动 —— 占位图的方形内在尺寸被列表回收放大**

`ZoomableAsyncImage` 用 `.placeholder(placeholder)`，而 `placeholder.png` / `placeholder_dark.png` 是
**1024×1024 正方形**。配合 `ContentScale.Fit`：**加载前由占位图决定内在尺寸（方形），加载后才换成真实宽高比**。
消息列表是 `LazyColumn`，滚出屏幕的项被回收，滚回来重新组合 → 又退回方形 → **再跳一次**。

改法：`ImageAspectRatioCache` 记录首次成功加载得到的真实比例，`aspectRatio` 放在修饰符**链尾**
（`AspectRatioModifier` 会给子项 `Constraints.fixed`，占位图的内在尺寸不再参与布局）。
**首次加载那一次仍会跳**（比例还没测出来，无解），用户要的也正是「第一次渲染完成后缓存好」。

> **开关只对「尺寸交给内容决定」的调用点开启**（markdown 行内图，`widthIn(min=120.dp).heightIn(min=120.dp)`）。
> 传了固定高宽的缩略图调用点（`ChatMessageTools` 的 64dp、`ChatMessage.kt` 的 72dp）**不能开** ——
> `aspectRatio` 会给子项固定尺寸，与调用方的固定高宽冲突。参数名就是为这个取舍起的：
> `sizeFromCachedAspectRatio`。

### 2.2 「名字存在 ≠ 字形能显示」（新的判据）

本仓无 Android 编译器，「编译得过」是唯一能自动拿到的信号 —— **但它证明不了图标画得出来**。
`HugeIcons.Internet` 就是反例：清单里有、CI 绿、屏幕上空白。
已把结论写进 memory `huge-icons-pinned-1-3`（含已确认可用的 1.3 字形：`Globe02` / `Globe` / `Wifi01` /
`InternetAntenna01` / `Earth`）。

### 2.3 同一张表不允许各存一份（第 3 次了）

`FileFolders.ROOTFS_BIND_MOUNTS` 这张挂载表此前已经有过两个消费者，终端会话自己拼了第三份 → 少了 `/upload`
（B16）。本轮「宿主 File → 沙箱路径」的映射又要被网页视图使用，若各写一份就是同样的错误第 3 次。
已收敛到 `WorkspaceFileUrlResolver.toSandboxPath`，**两个消费者共用**。

### 2.4 本机踩的坑

1. **hex 而不是 base64**：沙箱路径要放进 URL 路径段，`/`、空格、非 ASCII 都得转义。`android.util.Base64`
   是 Android-only（JVM 单测里会抛 "not mocked"），`kotlin.io.encoding.Base64` 是实验 API。
   **最终选 hex**：纯 JVM、无实验注解、无平台依赖，编码/解码这一对可以直接单测。
2. **Bash heredoc 又吃掉反斜杠**：写 python 校验脚本时 `'\\'` 变成 `'\'` → SyntaxError。
   按交接文档既有写法用 `BS = chr(92)` 解决。**这个坑第三次踩到了**。
3. **Raw string 尾部引号**：Kotlin 里 `"""...${x}""""` 是**错的** —— `""""` 会被切成 `"""`（闭合）+ `"`（新开字符串）
   → 未闭合。要写 `"""...${x}${'"'}"""`，或干脆改用普通字符串 + `\"` 转义。本次单测里就踩了。
4. **`git ls-files --eol` 才是查换行的可靠办法**：`grep -c $'\r'` 会谎报（模式被吃成空）。
   输出 `i/lf w/crlf` = blob 是 LF、工作区是 CRLF；`w/lf` 就是工作区 LF。
   **本仓不是清一色 CRLF**：`docs/**` 与 `CHANGELOG.md` 是 LF，`.kt` 绝大多数是 CRLF，
   `SingleTildeStrikethrough.kt` / `KeepAliveService.kt` 本来就是 LF。

### 2.5 一处文档出入（已核实）

B 类交接的 §5.3 把剩余 C 类写成「**4 条**」，但上一份交接（A 类）的 §5.3 实际列了 **10 项**、其中 3 项已在
B 类顺带做掉 —— **实际剩 7 件**（清单见 §5.2）。B 类那份是按「性价比」挑了高收益的两条措辞，
读起来像只剩 4 件。**下一阶段以 §5.2 为准**（每条都对着代码实测过）。

---

## 3. git / CI 状态

- 分支 `master`。**回滚锚点 = `66b49f44`**。
- **本轮代码提交 4 个 + 文档提交 1 个**（§1）。精确列表用 `git log --oneline 66b49f44..HEAD` 现查。
- CI：按用户要求**只跑 `nightly-build-debug.yml`**。

| run | 工作流 | conclusion | 说明 |
|---|---|---|---|
| `34749736659` | debug | ✅ **success**（`88b840d5`） | `--json jobs` 核过：`check` success、`build` success（**20 个 step**）。`Gradle Build` / `Unit Tests` / `Prepare signing key` / `Upload Room schema JSON` / `Point tag` / `Publish nightly debug prerelease` 全 success；**skipped step 数 = 0** —— 不是「24h 无提交」把 build 整个 skip 的假绿 |

签名未漂移：`Prepare signing key` 打印 `SHA256: 47:B7:DE:…:5C:CD`，与 memory `signing-key-drift` 里冻结的
debug 指纹逐位一致。

**未跑**：`pre` / `release`。B22（`assemblePre` 用新混淆 DSL 能否出包）仍需一次 pre CI（§5.4）。

---

## 4. 恢复地图

| 文档 | 说明 |
|---|---|
| `docs/superpowers/audits/2026-09-12-upstream-merge-2689e753-audit.md` | 62 项事故复盘。**§3 = C 类清单**、**§5 = 设备核验清单**、**§6 = 待决策项**、**§7 = B 类决策表** |
| `docs/superpowers/handoffs/2026-09-12-b-class-23-items-implemented-next-phase.md` | 上一阶段（B 类 23 项），**其 §5.1 是 B 类的设备核验清单** |
| `docs/superpowers/handoffs/2026-09-12-a-class-regressions-and-sync-rules-next-phase.md` | A/C/E 类阶段。**§5.3 是 C 类「仍需动代码」原始清单**、**§5.5 是 D 类清单** |
| `docs/superpowers/upstream-sync-checklist.md` | 下次同步上游的机械检查清单 |
| `CHANGELOG.md` | fork 用户可见改动清单（本轮五项已写入） |
| `docs/superpowers/scripts/` | 4 个可复用脚本（prefs 键集合 / 同步清单 / APK 签名 / baseline 过期） |

**本轮改动文件（按提交）**：

- `cab09b3f`：`MarkdownWeb.kt`、`WebViewLocalAssets.kt`、`WebViewPage.kt`、`ChatMessage.kt`、
  `ChatMessageActions.kt`、`WorkspaceFileUrlResolver.kt`、`ImageLazyLoadTransformer.kt`、`MarkdownWebPreviewTest.kt`(新)
- `036395a2`：`Export.kt`
- `a87f0c90`：`ImageAspectRatioCache.kt`(新)、`ZoomableAsyncImage.kt`、`Markdown.kt`、`ImageAspectRatioCacheTest.kt`(新)
- `a3ead8a7`：`SettingPreferencesPage.kt`、`AssistantMemoryPage.kt`、`AssistantPromptPage.kt`
- `88b840d5`：`CHANGELOG.md`

---

## 5. 待办

### 5.1 本轮改动的设备核验（**CI 全绿 ≠ 功能对**）

本轮真改了运行时行为，四条都要上设备：

| 项 | 怎么做 | 期望 / 判据 |
|---|---|---|
| **网页视图图片** | 找一条带图片的消息 → 网页视图 | 附件图片与文本里 `file:///workspace/…` 的链接都显示。**若不出来**：看网页视图右上角菜单的 **Console Logs**，404 vs 200 直接区分「改写规则错」还是「拦截器读文件错」 |
| **图片导出** | 选一条含图对话 → 导出图片 | 图片不再是空白。**特别注意**：第一次导出时图片走的是网络/首次解码，最能暴露竞速是否真的没了 |
| **滚动高度** | 含多张 markdown 行内图的会话来回快速翻 | 不再上下跳。**首次加载那一次仍会跳一次**（比例还没测出来），这是设计如此；要验的是**第二次以后不再跳** |
| **网络图标** | 偏好设置 → 网络 | 能看到地球图标（`Globe02`） |
| **时间提醒间隔** | 助手 → 提示词 → 打开时间提醒 | 间隔项出现在开关正下方，点开能改、改完生效；关掉开关时该项消失 |
| **网页视图附件** | 只发图片、不发文字的对话 → 网页视图 | 现在也能打开（此前该入口对纯图片消息是隐藏的） |

### 5.2 C 类剩余 **7 件**（**下一阶段主目标**；每条都对着代码实测过）

| # | 项 | 实测现状 | 为什么值得做 |
|---|---|---|---|
| 1 | `pre` 变体用 `matchingFallbacks` | 全仓 `matchingFallbacks` **零使用**；手工 `create("pre")` **14 处 / 13 个文件**（`ai`、`app`×2、`app/baselineprofile`、`common`、`document`、`highlight`、`material3`、`oauth`、`search`、`speech`、`videogen`、`web`、`workspace`） | **上游每加一个库模块都会漏**（已踩：`videogen`/`oauth` 的 pre 变体漏了，`assemblePre` 自合并起必红，`5b4b9715` 才修好）。一处改动顶掉 14 处维护 |
| 2 | fork 的 keep 追加块拆 `app/src/main/keepRules/fork.keep` | `keepRules/` 里**只有** `rikkahub.keep` | 追加块贴在上游文件尾部 → 与上游的尾部编辑形成冲突块 → 人工取 ours 时**会吃掉上游的 keep 修复**（已经吃过一条 jlatexmath 规则）。AGP 会合并同源集所有 `*.keep` |
| 3 | `.gitignore:14` 的 `references` → `/references/` | 仍是裸 `references` | 裸模式匹配任意层级 → 上游往 `.agents/skills/*/references/` 加文件会被**静默忽略**（已用 `git check-ignore` 实测） |
| 4 | `WorkspaceShellContext` 双构造点合并 | 仍是两处：`workspace/.../WorkspaceManager.kt:233` 与 `:269` | 上游给该 data class 加字段时，fork 的流式路径**不报错、只静默漏传**（`shellCompatibilityMode` 就是这么漏的）。合并成一个私有 `buildContext(...)`，或**去掉字段默认值逼编译器报错** |
| 5 | `prefs_key_audit.py` 等门禁脚本进 CI | `scripts/` 有 4 个脚本，但**没有任何工作流引用 `scripts/`** | 门禁等于没接。`persistSettings` 漏键（A1）就是靠这个脚本发现的，不接 CI 下次同步还会漏 |
| 6 | `androidTest` 纳入 CI | 工作流只有 `assembleDebug` + `testDebugUnitTest` | 8 个 androidTest **形同死代码**，从没跑过 |
| 7 | 重新生成 baselineProfiles | 实跑守卫脚本：仍 **7 个**过期项；且两份 profile **逐字节相同**（职责不同却完全一致，本身就可疑） | 规则指向已删类 → ART 静默忽略，优化在不知不觉中失效。**需要连着设备/模拟器**：`./gradlew :app:generateReleaseBaselineProfile` |

> **已做掉的 3 项**（B 类顺带）：清 `compose_compiler_config.conf` 两条悬空并声明 `StreamChunk`（B20）、
> 补 5 个未翻译串（B18）、补 6 个孤儿串 ×6 locale（B15）。

### 5.3 D 类历史挂起 **6 件**（一件未动，非本次引入）

| 项 | 备注 |
|---|---|
| `workspaces/` 不在整机备份 | `BackupManager.kt:34` 只有 `IMAGES_FOLDER = "images"`，**没有 workspaces 相关常量**。见 memory `backup-coverage-gaps` |
| `file://` 链接点击崩 | `ImageLazyLoadTransformer` 的降级路径对沙箱外文件仍会拼出 `file://<真机绝对路径>`，模型可能原样写进回复 → UI 渲染成链接（`Markdown.kt:1321/1453/1468` 用 `LinkAnnotation.Url`）→ 点击走 `ACTION_VIEW` 崩。**本轮刻意保留了这个降级分支**（有单测断言），状态不变 |
| OCR 无时间上界 | — |
| BMP 能识别不能发 | — |
| 备份诊断字段缩水 | — |
| S3/WebDAV 恢复无确认对话框 | — |

### 5.4 其它悬着的

- **跑一次 pre CI 定 B22**（`assemblePre` 用新 `optimization {}` DSL 能否出包）—— 需破例一次「平时只跑 debug」。
- **历史遗留的设备核验**：审计 §5 的 13 条 + B 类交接 §5.1 的 8 条，**均未见勾掉**。
  （本轮这五项本身就是设备观察来的，说明用户在真机测；但那两份清单没有被逐条确认过。）

---

## 6. 技术约束 / 惯例（必须遵守）

- **本机无 Android 编译器**：不跑 gradle，编译/单测结论只从 CI 拿。
- **CI 判定铁律**：先 push 再 `gh workflow run nightly-build-debug.yml --repo DevLintMar/rikkahub --ref master`；
  `gh run watch <id> --exit-status`；`gh run view <id> --json conclusion,headSha` 核对 headSha；
  **再用 `--json jobs` 确认 `build` 真跑了**（"24h 无提交"会把 build 整个 skip，run 仍报 success）。
  平时**只跑 debug**，pre / release 有各自的每日 cron。
- 中文 conventional commit；字符串六 locale；工具 description/注入文本/JSON 用英文；`runCatching` 不包 suspend。
- 文件删除走 `~/.claude/scripts/trash.sh`（绝不 `rm`）；force-push 需用户明确要求。
- **密钥纪律**：不进日志、不进 release asset、不入库；本机副本 `D:\AndroidKeys\rikkahub\`。
- **换行符**：本仓**不是**清一色 CRLF（`docs/**`、`CHANGELOG.md` 是 LF；`SingleTildeStrikethrough.kt`、
  `KeepAliveService.kt` 本来就是 LF）。**查换行用 `git ls-files --eol`**（`grep -c $'\r'` 会谎报）。
  改文件后用 python 校验「孤立 LF/CR = 0」。
- **写 python 脚本不要用 `\\` 字面量**：Bash heredoc 会吃掉反斜杠，用 `BS = chr(92)` 拼。
- **Kotlin raw string 不能以 `"` 直接收尾**：`"""...${x}""""` 是错的，用 `${'"'}` 或改普通字符串 + `\"`。

---

## 7. 停靠点

- **已完成**：五项界面问题全部修完并过 CI（§1、§3）；顺手收敛了「宿主 File → 沙箱路径」映射（§1.3）；
  更新日志与 memory `huge-icons-pinned-1-3` 已同步。
- **下一阶段（用户已指定）**：**做 C 类与 D 类的剩余项** —— §5.2 的 7 件 + §5.3 的 6 件。
- **建议的实施顺序**（按性价比，不是按编号）：
  1. **§5.2-1 `matchingFallbacks`** 与 **§5.2-2 keep 拆 `fork.keep`** —— 零风险、一劳永逸，
     且**下次同步上游必然再踩**。先做这两条。
  2. **§5.2-4 `WorkspaceShellContext` 双构造点** —— 同类「静默漏传」，改动小。
     做法上**优先考虑「去掉字段默认值逼编译器报错」**：比合并构造点更省事，而且以后上游再加字段必红。
  3. **§5.2-3 `.gitignore` 锚定** 与 **§5.2-5 脚本进 CI** —— 都是几行的事。
  4. **§5.2-6 `androidTest` 进 CI** —— 涉及工作流改动，先确认那 8 个测试在 CI 环境下能过。
  5. **§5.3 D 类** —— 都不紧急，其中 `file://` 链接点击崩是用户能碰到的。
  6. **§5.2-7 / §5.4** —— 都需要设备，需要时一起做。
- **恢复动作**：读本文档 §5.2 / §5.3，再读审计文档 §3（C 类原始描述）与 §6（待决策项）。
  **开工前先让用户确认要动的条目**（D 类里几条涉及备份/恢复行为，值得先对一下预期）。
