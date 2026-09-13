# 交接文档：C 类剩余 7 项 + D 类 5 项落地

**日期**：2026-09-13（当日第二份；前一份为同日的 `2026-09-13-ui-image-fixes-and-cd-remaining-next-phase.md`）
**状态**：**代码 HEAD = `a02b3123`**（debug CI 在此绿，见 §3）；文档 HEAD = `b57f57ea`。工作树干净，全部已 push。
**目的**：按用户指令「动手做 C 项剩余内容，D 项除了 workspace 不备份都做」——
C 类 7 件全部动过（其中 1 件只做到可重生成、未真正重新生成，见 §5.2），D 类 5 件全部改完。

> **A/B/C/D/E 是什么**：更早一轮「告知我所有问题」的 62 项清单分组 ——
> **A** 合并已静默破坏的 11 项｜**B** 「半截」/行为变化 23 项｜**C** 结构性风险：下次同步必踩 17 项｜
> **D** 历史挂起 6 项｜**E** 文档/memory 已过时。
> A/B/C(部分)/E 已消化。本轮把 **C 类剩的 7 件**与 **D 类除「workspaces 不进备份」外的 5 件**做完。

---

## 0. 一句话概况

**12 个提交**（11 代码/配置 + 1 文档），33 文件、+848 / −219，新增 4 个文件。
C 类里 4 条「静默破坏」的结构性根因被一次性掐掉（`matchingFallbacks`、keep 拆分、
`.gitignore` 锚定、`WorkspaceShellContext` 唯一构造点），门禁从「写了没人跑」变成
**真跑**（两个审计脚本 + 全模块单测 + 每周 instrumentation），D 类 5 条历史挂起全部改完。
**接通 instrumented CI 花了 4 轮，每轮抓出一个此前完全不可见的坏状态**（见 §3.2）——
这是本轮最有说服力的一笔：门禁不跑，坏的东西就一直躺着。

---

## 1. 已完成（commit 链）

```
b57f57ea docs: 更新日志补 C/D 类改动；同步清单勾掉已根治的 4 条
a02b3123 fix(backup): 恢复诊断补回丢失字段；S3/WebDAV 恢复加确认对话框        ← D5 + D6
06d882ef fix(ai): 按文件头识别 BMP，修掉能识别不能发                          ← D4
fc1be055 fix(ocr): 单次调用加上界，超时不重试                                 ← D3
4a831043 fix(richtext): 没有处理者的链接 scheme 不再把会话崩掉                ← D2
fabab47a ci: 门禁脚本与全模块单测进 nightly；androidTest 与 baselineProfile 走模拟器 ← C5+C6+C7(部分)
c383f2fd refactor(workspace): WorkspaceShellContext 收敛到唯一构造点并去掉字段默认值 ← C4
c5777457 chore(build): fork 的 keep 规则拆成独立文件；.gitignore 的 references 改成锚定 ← C2+C3
c98b8fc8 refactor(build): pre 变体改用 matchingFallbacks，删掉 11 个库模块的重复 buildType ← C1
```

**回滚锚点 = `d903a378`**（上一阶段的 HEAD）。

### 1.1 逐条落点

| 项 | 处置 | 落点 |
|---|---|---|
| **C1** `pre` 用 `matchingFallbacks` | ✅ app 的 `pre` 声明 `matchingFallbacks += listOf("release")`，删掉 11 个库模块各自重复的 `pre` buildType。**语义等价已论证，但 `assemblePre` 未经 CI 验证**（见 §3.1） | `app/build.gradle.kts` + 11 个 `*/build.gradle.kts` |
| **C2** keep 追加块拆文件 | ✅ fork 规则移到 `app/src/main/keepRules/fork.keep`，上游文件只剩上游内容 | `rikkahub.keep`、**新增 `fork.keep`** |
| **C3** `.gitignore` 锚定 | ✅ 裸 `references` → `/references/` + `/docs/references/` 两条 | `.gitignore` |
| **C4** `WorkspaceShellContext` 双构造点 | ✅ 合并成私有 `buildShellContext` + **字段全部去掉默认值** + 反射单测锁死 | `WorkspaceManager.kt`、`WorkspaceShellRunner.kt`、**新增 `WorkspaceShellContextTest.kt`** |
| **C5** 门禁脚本进 CI | ✅ `prefs_key_audit.py` 与 `baseline_profile_audit.py --strict` 进 nightly-debug | `.github/workflows/nightly-build-debug.yml`、两个脚本 |
| **C6** `androidTest` 进 CI | ✅ 新增每周六 + 可手动的 `Android Instrumented` 工作流跑 `connectedDebugAndroidTest`；接通过程中顺带修了三处「静默的坏状态」（缺 submodule、三个模块缺 androidTest 依赖、`speech` 断言旧包名），见 §3.2 | **新增 `.github/workflows/android-instrumented.yml`**、`highlight`/`material3`/`search` 的 `build.gradle.kts`、`speech/src/androidTest/.../ExampleInstrumentedTest.kt` |
| **C7** 重新生成 baselineProfiles | ⚠️ **部分**：可重生成的入口做好了（模拟器任务 + 审计允许表），**profile 本身没有重新生成** | 同上 + `baseline_profile_audit.py` |
| **D2** `file://` 链接点击崩 | ✅ 收敛成唯一的 `openMarkdownLink`，全分支吞异常；`LocalUriHandler` 也换成安全实现 | `Markdown.kt` |
| **D3** OCR 无时间上界 | ✅ 单次调用 120s 上界、超时不重试；顺带修 `Call.await` 取消时没取消底层请求 | `OcrTransformer.kt`、`common/.../http/Request.kt` |
| **D4** BMP 能识别不能发 | ✅ `guessMimeType` 补 BMP 魔数；转码走既有的解码→JPEG 路径 | `ai/.../util/FileEncoder.kt` + **新增 `FileEncoderMimeTypeTest.kt`** |
| **D5** 备份诊断字段缩水 | ✅ 恢复诊断补回 `items=`、`userAvatar=`、头像数分母；备份侧计数拆 `uploadFiles` / `images` | `BackupManager.kt` |
| **D6** S3/WebDAV 恢复无确认框 | ✅ 两个 Tab 各加确认对话框（恢复逻辑抽成 `performRestore`） | `S3Tab.kt`、`WebDavTab.kt` |
| D1 `workspaces/` 不进备份 | ⛔ **用户明确排除**，未动 | — |

> 顺带（不在 C/D 清单里，见 §2.7）：单测从 `:app:testDebugUnitTest` 改成全模块
> `testDebugUnitTest` —— `ai`(25)/`speech`(11)/`workspace`(3) 等**约 49 个测试文件
> 此前从未在任何地方执行过**。

### 1.2 本轮新增的文件

| 文件 | 用途 |
|---|---|
| `.github/workflows/android-instrumented.yml` | 模拟器上跑 `connectedDebugAndroidTest`；可按需重新生成 baselineProfiles |
| `ai/.../test/.../util/FileEncoderMimeTypeTest.kt` | 7 个用例：BMP/JPEG/PNG/WebP/GIF/HEIC/AVIF 魔数 + 未知头必须失败 |
| `app/src/main/keepRules/fork.keep` | fork 自有 R8 规则（RaTeX FontCache、Jackson/Auth0 JWT） |
| `workspace/.../test/.../WorkspaceShellContextTest.kt` | 2 个用例：构造器必须唯一（= 没有字段带默认值）、字段集合必须不变 |

---

## 2. 关键决策与认知

### 2.1 C1：`matchingFallbacks` 为什么与原来的写法等价

原来 13 个文件里各写一份 `create("pre") { initWith(getByName("release")) }`。
这种「库模块手动跟一个 buildType」的写法，**上游每加一个库模块都会漏**
（`videogen`/`oauth` 就漏过，`assemblePre` 自上游合并起必红，直到 `5b4b9715` 才补）。

改成在 `app` 的 `pre` 上声明 `matchingFallbacks += listOf("release")`，AGP 在解析
`pre` 变体的项目依赖时，会让**缺少 pre 变体的模块回落到它的 release 变体**。
这与「库模块的 pre = release 的副本」在语义上完全相同 —— 那 13 份 `pre` 变体本来就
只是 `initWith(release)`，没有任何额外配置（`app/src/pre/` 目录不存在，也没有任何
`preImplementation` 依赖，已 grep 核实）。

**刻意留下的一处**：`app/baselineprofile/build.gradle.kts` 的 `create("pre")` 保留。
它是 `com.android.test` 模块，变体靠**名字**与 target 工程对齐（官方 macrobenchmark 文档：
`matchingFallbacks` 要同时加在 `:macrobenchmark` 与 `:app` 两侧）。基线 profile 的 pre
变体要测的是 app 的 pre 变体，用 fallback 解析到它的 release 会改变被测对象 ——
不是「同样的东西」，所以不动。这也是 14 处里**唯一**不该被机械统一掉的一处。

> **本次改动没有经过 `assemblePre` 验证**（见 §3.1 的新规矩）。等价性是**论证**出来的，
> 不是跑出来的：那 13 份 `pre` 变体本来就只是 `initWith(release)`、没有任何额外配置
> （已 grep 核实没有 `preImplementation` 之类依赖、没有 `app/src/pre/` 目录）。
> 若哪天 pre 包构建失败，第一个该怀疑的就是这里。

### 2.2 C4：为什么「去掉默认值」还不够，要再加一个反射单测

审计给的方案是二选一（合并构造点 **或** 去掉默认值）。本轮两个都做了，因为它们的
防护面不同：

- **合并构造点**（`WorkspaceManager.buildShellContext`）解决的是「同一件事写在两处」；
- **去掉默认值**解决的是「漏传不报错」；
- **反射单测**解决的是「上游给 data class 加了字段，而合并后的那一个构造点没跟着改」——
  合并构造点之后仍然会漏，只是从「两处都可能漏」变成「一处会漏」。

单测用 JDK 反射而不是 kotlin-reflect，避免为一条断言引入运行时反射库：

1. `declaredConstructors.size == 1` —— Kotlin **只要有任何一个字段带默认值**就会多生成一个
   `$default` 合成构造器，所以这一条同时断言了「字段一个默认值都没有」；
2. `declaredFields` 的名字集合必须完全一致 —— 上游加/删/改名字段直接红。

### 2.3 D2：两条崩溃路径，以及为什么不能把 listener 写进 AnnotatedString

`href` 完全由模型产出。`file:///data/user/0/<pkg>/files/...` 这类**真机绝对路径**
（`ImageLazyLoadTransformer` 的沙箱外降级分支就会主动造出这种 URL）以及任何拼错的 scheme，
系统里都没有处理者，`startActivity` 抛 `ActivityNotFoundException` → 未捕获 = 崩溃。
两条渲染路径各中一条：

- `MarkdownNode` 的 `INLINE_LINK` 分支：`.clickable { context.startActivity(Intent(ACTION_VIEW, linkDest.toUri())) }`
  —— **连 runCatching 都没有**；
- `appendMarkdownNodeContent` 的 `LinkAnnotation.Url`：没有显式 listener 时由 Compose 的
  `TextLinkScope` 交给 `LocalUriHandler.openUri` → `startActivity` —— 同样未捕获。

改法：收敛成唯一入口 `openMarkdownLink(context, resolver, href)`
（工作区 `file://` → FileProvider + 选择器；其余 → 系统 Intent；**所有分支 runCatching**），
再把这棵 markdown 子树的 `LocalUriHandler` 换成同一实现的 `UriHandler`。

**为什么用 `LocalUriHandler` 而不是给 `LinkAnnotation.Url` 传 `linkInteractionListener`**：
`linkInteractionListener` 是**构建 AnnotatedString 时**烘进去的闭包，而
`paragraphRenderCache` 会按文本内容把 AnnotatedString 跨消息、跨会话复用 ——
烘进去的 resolver 会属于第一条消息的工作区（与「含 citation 的段落不缓存」是同一个理由）。
`LocalUriHandler` 是绘制/点击时才读的 CompositionLocal，AnnotatedString 保持纯净、缓存安全。

> `LinkAnnotation.Url` 在没有 listener 时**只能**经 `LocalUriHandler` 到达平台
> （`TextLinkScope` 里没有别的路径，也没有 Context），所以这个替换是能拦住所有段落链接的。
> 顺带 `SimpleHtmlBlock`（在 markdown 子树内）也一起走安全实现。

### 2.4 D3：OCR「无上界」的真实链路

不是「没写超时」这么简单，是两件事叠在一起：

1. **provider 客户端的超时配置对 OCR 不适用**：全局客户端是
   `connectTimeout(20s)` + `readTimeout(10 分钟)` + `writeTimeout(120s)`，**没有 `callTimeout`**
   （`DataSourceModule.kt:145-151`）。10 分钟是**给流式 LLM 响应**留的。
   于是「连得上但不返回」的服务端能把一次 OCR 拖满 10 分钟，×4 次重试 = 40 分钟，
   **而且每张图各算一遍**。`read_image` 的 http 下载当初踩的是同一类问题
   （`ReadImageTools.kt` 顶部注释）。
2. **`Call.await()` 在协程取消时不取消底层请求**：`common/.../http/Request.kt` 的
   `await()` 是 `suspendCancellableCoroutine` + `enqueue`，但**没有 `invokeOnCancellation { cancel() }`**。
   协程会立刻带着 CancellationException 结束，HTTP 请求却在后台跑到自己超时 ——
   调用方以为放弃了，连接一直被占着。

改法：`withTimeoutOrNull(120s)` 包住单次调用，超时抛 `OcrTimeoutException` 并由
`retryIf` **停止重试**（与 `Retry.kt` 文档、`ReadImageTools` 同一条判断：超时重试只是再等一轮）；
同时给 `await()` 补上 `invokeOnCancellation`（注册在 `enqueue` **之前**，否则会漏掉竞态）。

### 2.5 D4：「能识别不能发」= 两个识别器不同步

三个地方认得出 bmp：`ReadImageTools.sniffImageExtension`（按魔数）、
`WorkspaceTools` / `WorkspaceFileType`（按扩展名白名单）。但**发送路径**
（`UIMessagePart.Image.encodeBase64` → `File.guessMimeType`）不认 —— 直接落到
`error("Failed to guess MIME type")`，`encodeBase64` 返回 failure，图片发不出去。

补上 `BITMAPFILEHEADER` 的 `BM` 判断即可，转码不需要特殊处理：它不是 GIF，
`compressAndEncode` 会走 `BitmapFactory` 解码后统一压成 JPEG（供应商普遍不支持 `image/bmp`）。
`guessMimeType` 顺手从 `private` 改成 `internal`，好让 JVM 单测直接喂字节头
（该函数不碰任何 Android 类）。

### 2.6 D5 / D6：诊断字段与确认框

`restore_diag.txt` 是**恢复专用的落盘诊断**（恢复会 `exitProcess` 重启，进程内日志全丢）。
上游把三份手写恢复收成 `BackupManager` 时丢了三个字段，而它们恰好是排查
「头像/附件没恢复」最需要的：

- `items=` —— 这次到底要恢复 db / 文件里的哪些；
- `userAvatar=` —— settings 解出来的用户头像类型（`Image` / `Dummy`）；
- `imageAssistantAvatars=N/总数` 的**分母** —— `0/N` 与「本来就没有图片头像」的区别。

备份侧另有一处口径问题：`uploadFiles` 的计数把 AI 生成的 `images/` 也算了进去，
字段名却没改，排查「备份里到底有没有图」时会被误导 → 拆成 `uploadFiles=` 与 `images=` 两个计数。

D6 直接复用本地导入那条现成文案 `backup_page_import_overwrite_confirm`
（「导入备份会覆盖 App 数据，是否继续？」），避免为六语言再造新串，三处恢复入口文案也一致了。
顺带把两个 Tab 里重复的恢复逻辑抽成 `performRestore(item)`。

### 2.7 C5/C6 顺带查出来的一件事：49 个测试文件从来没跑过

`nightly-build-debug.yml` 里原本是 `./gradlew :app:testDebugUnitTest` ——
**只跑 `:app` 一个模块**。其余模块的单测（`ai` 25 个、`speech` 11 个、`workspace` 3 个、
`search`/`oauth`/`common`/`document`/`highlight`/`material3`/`videogen`/`web` 各 1-2 个，
共约 49 个文件）**从未在任何地方执行过**。

改成根任务的 `./gradlew testDebugUnitTest`（Gradle 会在所有含该任务的子项目里跑），
CI 一次过 —— 说明它们本身是好的，只是没人跑。这与 C6「androidTest 形同死代码」是同一类问题：
**门禁写了不等于门禁在跑**。

**而 instrumented 测试比「死代码」更糟**：接通 C6 的过程中发现，
`highlight` / `material3` / `search` 三个模块有 `src/androidTest` 源文件，
却**一个 `androidTestImplementation` 都没声明** —— 于是它们不是「没跑」，
而是**从来就编译不过**（`:highlight:compileDebugAndroidTestKotlin` 报
`Unresolved reference 'AndroidJUnit4' / 'RunWith' / 'Test'`）。
已在 `89a87259` 补上 `androidx.test.ext:junit` 与 `espresso-core`，与其余 7 个模块一致。

> 这条正好回答了「为什么当初要把 androidTest 接进 CI」：接上之前，
> 仓库里同时躺着「从未执行」和「编译不过」两种坏状态，而且**都是静默的**。

### 2.8 C7：做到了「能重生成」，没做到「已重生成」

审计脚本实测：两份 profile **逐字节相同**（sha256 都是 `e51f505d…`），
且各有 **7 条规则指向已不存在的类**：

| 过期规则 | 原因 |
|---|---|
| `ai/provider/providers/{Claude,Google,OpenAI}Provider` | provider 已拆进 `providers/<vendor>/` 子包 |
| `data/ai/GenerationHandler` | 已被上游 `GenerationLoop` + `ChatToolFactory` 取代 |
| `data/ai/tools/{LocalToolOption,LocalTools}` | 已搬进 `tools/local/` 子包 |
| `data/api/SponsorAPI` | fork 已移除赞助商功能 |

已核实 profile 里**没有**对应的新名字（`grep -c 'providers/claude/ClaudeProvider'` = 0），
所以这些热点路径的启动优化是真丢了，**只能重新生成**，而重新生成需要一台 API 33+ 的设备/模拟器。

本轮做到的部分：

1. `android-instrumented.yml` 里加了 `baseline-profile` 任务：x86_64 模拟器上跑
   `:app:generateReleaseBaselineProfile -PwithX86_64`，产物作为 artifact 上传（**不自动提交**）；
2. `app` 新增 `-PwithX86_64` 开关（默认关闭）—— Hosted runner 的模拟器是 x86_64，
   而发布包只打 `arm64-v8a`，不加这个开关连手机都装不上，更谈不上生成；
3. 审计脚本引入 `KNOWN_STALE` 允许表：7 条已知过期项**照旧打印**（含「ART 静默忽略、
   启动优化失效」的说明），但 `--strict` **只对新增过期项**返回 1 —— 这样 nightly 的门禁
   不会被一条已知欠账废掉，同时新增的过期项依然拦得住。

**没有做**：真正跑一次重新生成、把新 profile 提交进来。这需要你触发一次那个任务
（或把设备插上跑 `./gradlew :app:generateReleaseBaselineProfile`），见 §5.2。

---

## 3. git / CI 状态

- 分支 `master`。**回滚锚点 = `d903a378`**。
- 代码/配置提交 11 个 + 文档提交 1 个（最后一个代码提交 `72f4826b`）。
  改动面用 `git diff --shortstat d903a378..HEAD` 现查。

| run | 工作流 | conclusion | 说明 |
|---|---|---|---|
| `34759847777` | debug | ✅ **success**（`a02b3123`） | `--json jobs` 核过：`build` **21 个 step、0 个 skipped**；新增的 `Unit Tests`（全模块）与 `Repo gate scripts` 两个 step 都 success —— 一次证明「编译 + 全模块单测 + 两个门禁脚本」全过。**这是本轮的因果基线**：后面所有失败都不是回退造成的 |
| `34761365637` | debug | ✅ **success**（`89a87259`） | 补了三个模块的 androidTest 依赖之后复跑，仍绿 |
| `34760386754` | instrumented | ❌ failure（`a02b3123`） | 第 1 轮：**工作流自己的缺陷** —— `actions/checkout` 漏了 `submodules: recursive`，`material3` 的 `material-color-utilities` 子模块没检出 → `Unresolved reference 'dynamiccolor'` 炸在 `:material3:compileDebugKotlin` |
| `34760850198` | instrumented | ❌ failure（`6cc4bec6`） | 第 2 轮：编译往前走了 389 个 task，停在 `:highlight:compileDebugAndroidTestKotlin` —— `highlight`/`material3`/`search` 三个模块**有 androidTest 源文件却没有任何 androidTest 依赖** |
| `34761366878` | instrumented | ❌ failure（`89a87259`） | 第 3 轮：**测试真的在模拟器上跑起来了**（620 个 task、9m42s），`:speech:ExampleInstrumentedTest` 断言旧包名失败：断言 `me.rerere.tts.test`，实际 `me.rerere.speech.test` |
| `34762230548` | instrumented | ✅ **success**（`72f4826b`） | 第 4 轮：`BUILD SUCCESSFUL in 8m 39s`、661 个 task、17 个 step 全 success。**12 个模块的 `connectedDebugAndroidTest` 全部执行**：`:app` **17 个测试**（DB 迁移 / 消息统计 / 备份 / 数据库备份 —— 此前从未执行过），其余 9 个模块各 1 个样板测试 |
| `34760385270` | pre | ⛔ **cancelled（人工取消）** | 我触发它用来验证 C1，被用户的新规矩叫停（见 §3.1）。取消发生在 `Gradle Build` 阶段，`Point tag` 与 `Publish` 两步都是 **skipped** —— 没有移动 `nightly-pre` tag、没有发布 prerelease，无残留 |

### 3.1 新规矩：不再构建 pre / release（2026-09-13 用户指令）

用户明确要求：**今后不构建 pre 与正式版，只构建 debug**。已与用户确认过的边界：

- `nightly-build.yml`（正式版）与 `nightly-build-pre.yml`（pre）的**每日 cron 原样保留**
  （用户答复：「保留现状，只管住我」）—— 新规矩约束的是**我不要再手动触发它们**，
  不改仓库既有的自动流程；
- `android-instrumented.yml` 的 `baseline-profile` 任务（release 变体、仅手动 dispatch）
  **保留**（用户答复：「保留，仅手动触发」）—— 它是 C7 重新生成 profile 的唯一免设备路径；
- `instrumented-tests` 任务本来就是 debug 构建（`assembleDebug` + `connectedDebugAndroidTest`），不受影响。

> **这条规矩的代价，必须记住**：**C1（`matchingFallbacks`）与 B22（`assemblePre` 能否出包）
> 从此在 CI 上无法验证** —— debug 变体完全不碰 `pre`。C1 的改动在语义上与原来的
> `create("pre") { initWith(getByName("release")) }` 等价（论证见 §2.1、已 grep 核实没有
> `preImplementation` 依赖、没有 `app/src/pre/`），但 `assemblePre` 这条**自上游合并起就一直是红的**、
> 修好后（`5b4b9715`）也**从未成功跑过一次**，现在连跑的机会都没有了。
> 哪天真要出 pre 包，先把这一条当成「未验证」对待。

### 3.2 接通 instrumented CI 一共花了 4 轮，**每轮都抓出一个真问题**

这 4 轮本身就是「为什么要接门禁」的最好的论据 —— 每一轮暴露的东西，
在「工作流存在但从没跑过」的状态下都是**不可见的**：

| 轮 | 暴露的问题 | 性质 |
|---|---|---|
| 1 | 新工作流的 `actions/checkout` 缺 `submodules: recursive` | 我写错了工作流（`material3` 有 `material-color-utilities` 子模块） |
| 2 | `highlight`/`material3`/`search` 有 androidTest 源文件但**零 androidTest 依赖** | **仓库里既有的坏状态**：这些测试不是「没跑」，是**从来编译不过** |
| 3 | `speech` 的断言写的是历史包名 `me.rerere.tts.test`，模块 namespace 却是 `me.rerere.speech` | **仓库里既有的坏测试**：接上 CI 才开始失败 |
| 4 | — **绿**：661 个 task / 8m39s，12 个模块全跑，`:app` 17 个测试通过 | 目标达成 |

第 3 轮之后我给 `connectedDebugAndroidTest` 加了 `--continue`：模拟器一轮十几分钟，
应该**一次报出所有模块的失败**而不是停在第一个（前两轮的失败其实也都有这个因素 ——
Gradle 失败后不再调度新任务，所以后面还没跑的模块是「未知」而不是「通过」）。

> **一句话**：C6 的价值不在「多跑 14 个测试」，而在**它把「静默的坏状态」变成了「红色的信号」**。
> 12 个模块 26 个测试里，9 个只是断言包名的样板；真正有内容的是 `:app` 的 **17 个**
> （DB 迁移 / 消息统计 / 备份 / 数据库备份，跑在真实 Android runtime 上）—— 而那 17 个
> 此前**同样从未执行过**，现在一次全过。

---

## 4. 恢复地图

| 文档 | 说明 |
|---|---|
| `docs/superpowers/audits/2026-09-12-upstream-merge-2689e753-audit.md` | 62 项事故复盘。**§2-5 = D5/D6 的原始描述**、**§2-7 = C4 的原始描述**、§3 = C 类清单、§5 = 设备核验清单 |
| `docs/superpowers/handoffs/2026-09-13-ui-image-fixes-and-cd-remaining-next-phase.md` | 上一份（五项界面修复），**其 §5.1 是那五项的设备核验清单，尚未勾** |
| `docs/superpowers/handoffs/2026-09-12-a-class-regressions-and-sync-rules-next-phase.md` | A/C/E 阶段，**§5.3 = C 类原始清单**、§5.5 = D 类原始清单 |
| `docs/superpowers/upstream-sync-checklist.md` | 下次同步上游的机械检查清单（**本轮已把 §3.1 与 §4 的 8/9/11/12 四条就地改成 ✅**） |
| `docs/superpowers/scripts/` | 4 个脚本；其中 `prefs_key_audit.py` 与 `baseline_profile_audit.py` 现在**进了 CI** |
| `CHANGELOG.md` | fork 用户可见改动（C/D 两轮已写入） |

**本轮改动文件（按提交）**：

- `c98b8fc8`：`app/build.gradle.kts`（`matchingFallbacks` + `-PwithX86_64`）+ 11 个库模块 `build.gradle.kts`
- `c5777457`：`rikkahub.keep`、**`fork.keep`(新)**、`.gitignore`
- `c383f2fd`：`WorkspaceManager.kt`、`WorkspaceShellRunner.kt`、**`WorkspaceShellContextTest.kt`(新)**
- `fabab47a`：`nightly-build-debug.yml`、**`android-instrumented.yml`(新)**、两个审计脚本
- `4a831043`：`Markdown.kt`
- `fc1be055`：`OcrTransformer.kt`、`common/.../http/Request.kt`
- `06d882ef`：`FileEncoder.kt`、**`FileEncoderMimeTypeTest.kt`(新)**
- `a02b3123`：`BackupManager.kt`、`S3Tab.kt`、`WebDavTab.kt`
- `b57f57ea`：`CHANGELOG.md`、`upstream-sync-checklist.md`

---

## 5. 待办

### 5.1 本轮改动的设备核验（**CI 全绿 ≠ 功能对**）

| 项 | 怎么做 | 期望 / 判据 |
|---|---|---|
| **D2 链接不崩** | 让模型回一条含 `file:///data/user/0/...` 或 `![x](file:///nope)` 的消息，点那条链接 | 不崩（点上去没反应）；logcat 里 `MarkdownLink` 标签有 `no activity for:`。<br>**同时确认没改坏正常的**：`https://` 链接仍能打开系统浏览器；`file:///workspace/xxx.png` 仍走应用内打开 |
| **D3 OCR 上界** | 把 OCR 模型指向一个能连上但不返回的地址（或断网），发一条带图消息 | 约 2 分钟内返回 `[ERROR, OCR failed: ... timed out ...]`，且**不再重试**；此前会一直转圈 |
| **D4 BMP** | 往会话里塞一张 `.bmp`（或让 AI 生成/下载一张），发送 | 图片能发出去（模型收到的 Description/内容不为空），不再报 `Failed to guess MIME type` |
| **D6 恢复确认框** | S3 / WebDAV 的备份列表里点「恢复」 | **先弹确认框**，取消则不恢复；确认后行为与之前一致（重启提示） |
| **D5 诊断** | 做一次备份 + 一次恢复，看请求日志页 | 备份日志有 `uploadFiles=N images=M imageAssistantAvatars=K/T`；`restore_diag.txt` 回放里有 `items=db:…,files:…`、`userAvatar=…`、`imageAssistantAvatars=N/T` |
| C1 的 pre 产物 | 用 pre CI 出的 APK 覆盖安装 | 能装能起（`xyz.lynsei.rikkahub.pre`）；主要靠 CI 结论 |
| C4 无界面 | — | 不需要上设备，单测 + CI 覆盖 |

> 上一份交接（五项界面修复）的 §5.1 六条**仍未核验**，一并过一遍。

### 5.2 C7 剩下的那一步（唯一没做完的 C 项）

**重新生成 baselineProfiles**，二选一：

```
A. 设备上（用户有真机）：
   连上手机，`./gradlew :app:generateReleaseBaselineProfile`，把
   app/src/release/generated/baselineProfiles/*.txt 的改动提交。

B. CI 上（无需设备）：
   Actions → Android Instrumented → Run workflow → task = baseline-profile
   跑完下载 baseline-profiles artifact，覆盖本地两个文件后提交；
   提交后 `python docs/superpowers/scripts/baseline_profile_audit.py` 的
   KNOWN_STALE 那 7 条应当消失（消失后请**从 KNOWN_STALE 里删掉它们**）。
```

顺带值得确认的一件事：两份 profile **逐字节相同**（`baseline-prof.txt` 与
`startup-prof.txt`）。职责不同却完全一致，本身就说明生成流程或提交方式有问题 ——
重新生成时留意它们是否还是两份一样的文件。

### 5.3 其余悬着的

- **`workspaces/` 不进整机备份**（D1）—— 用户本轮明确排除。现状：恢复后 DB 里的工作区记录
  会全变 `BROKEN`，而恢复报告里没有显式提示。相关 memory：`backup-coverage-gaps`。
- **`ChatMessage.kt:705` 的引用链接**仍用裸 `LinkAnnotation.Url(annotation.url)`
  （`UrlCitation` 的 URL 也是模型产出的）。本轮**刻意没动**：它在 `MarkdownBlock` 子树之外，
  拿不到那个安全 `UriHandler`，要改得单独包一层。风险低于 markdown 链接（引用基本都是 http），
  但同属 D2 那一类。
- **`speech/src/androidTest/.../ExampleInstrumentedTest.kt` 的包名仍是 `me.rerere.tts`**，
  与模块 namespace `me.rerere.speech` 不一致（文件路径也一样）。本轮只改了断言让它通过，
  没动文件位置/包名 —— 纯观感问题，想收拾的话用 `git mv` + 改 package。
- **`android-instrumented.yml` 的模拟器配置取自最省事的写法**（`api-level: 34` +
  `google_apis` + `x86_64` + `swiftshader_indirect`）。如果它以后变得不稳，
  优先怀疑模拟器本身而不是测试。
- **`app/baselineprofile` 的 `pre` 变体**保留（§2.1 的取舍），下次同步上游时不要「顺手统一掉」。
- **历史遗留的设备核验**：审计 §5 的 13 条 + B 类交接 §5.1 的 8 条 + UI 修复交接 §5.1 的 6 条，
  均未见勾掉。
- **`pre` / `release` 的每日 cron** 仍在跑；本轮因 C1 手动补跑了一次 pre。

---

## 6. 技术约束 / 惯例（必须遵守）

- **本机无 Android 编译器**：不跑 gradle，编译/单测结论只从 CI 拿。
- **CI 判定铁律**：先 push 再 `gh workflow run ... --ref master`；`gh run watch <id> --exit-status`；
  `gh run view <id> --json conclusion,headSha` 核对 headSha；**再用 `--json jobs` 确认 `build` 真跑了**
  （"24h 无提交"会把 build 整个 skip，run 仍报 success）。
- **只构建 debug（用户 2026-09-13 指令，见 §3.1）**：不要手动触发 `nightly-build.yml` /
  `nightly-build-pre.yml`。**代价是 `assemblePre` 与 `assembleRelease` 这条路在 CI 上不可验证** ——
  改动 `pre` 变体解析、混淆 DSL、keep 规则这类配置时，要**明确写出「本次未验证」**，不要
  因为 debug 绿就当成整体绿。两个工作流的每日 cron 仍在跑，所以它们迟早会自己验证一次，
  但不要拿它当选代。
- **新建工作流的 `actions/checkout` 必须带 `submodules: recursive`**：`material3` 模块的源码目录里
  有一份 git submodule（`material-color-utilities`），漏了会以 `Unresolved reference 'dynamiccolor'`
  炸在 `:material3:compileDebugKotlin`（本轮首次跑 instrumented 就是这么失败的）。
- 中文 conventional commit；字符串六 locale（**能复用现成文案就不要造新串**，D6 就是这么做的）；
  工具 description / 注入文本 / JSON 用英文；`runCatching` 不包 suspend。
- 文件删除走 `~/.claude/scripts/trash.sh`（绝不 `rm`）；force-push 需用户明确要求。
- **密钥纪律**：不进日志、不进 release asset、不入库；本机副本 `D:\AndroidKeys\rikkahub\`。
- **换行符**：本仓**不是**清一色 CRLF，且 `core.autocrlf=true` 会让 `git show HEAD:<file>`
  输出 LF 而工作区是 CRLF —— **不要用它判断「换行符被改了」**。
  查换行用 `git ls-files --eol`（`i/… w/…`），改完文件用 python 校验「孤立 LF/CR = 0」。
  新建文件要对齐**同目录兄弟文件**的 `w/` 值（本轮 4 个新文件从 LF 改成 CRLF 对齐）。
- **写 python 脚本不要用 `\\` 字面量**：Bash heredoc 会吃掉反斜杠，用 `BS = chr(92)` 或
  `bytes([13, 10])` 这类写法拼。
- **Kotlin raw string 不能以 `"` 直接收尾**：`"""...${x}""""` 是错的，用 `${'"'}` 或普通字符串 + `\"`。
- **Compose 的 `CompositionLocal.current` 不能在非 @Composable 的 lambda 里读**：
  `.clickable { LocalXxx.current }` 编译不过，必须在组合作用域里先取出来（本轮踩到一次）。
- **backtick 测试函数名不要带括号**：`` fun `xxx (yyy)`() `` 有 JVM 名字合法性风险，
  用逗号或 and 代替。

---

## 7. 停靠点

- **已完成**：C 类 7 件（6 件全做完 + C7 做到「可重生成」，见 §5.2）；D 类 5 件全做完（D1 按用户要求排除）。
  门禁从「写了没人跑」变成真跑：两个审计脚本 + 全模块单测 + **每周 instrumentation（已真跑通，
  12 个模块 26 个测试）**。同步清单里 4 条结构性风险就地改成已根治。
  debug CI 真绿（21 step / 0 skipped）；instrumented 真绿（17 step 全 success）。
- **未完成 / 需要人工**：
  1. **C7 的重新生成**（§5.2）—— 需要设备或手动触发一次 `baseline-profile` 任务（该任务已获准保留）；
  2. **C1 / B22 的 `assemblePre` 验证** —— 按新规矩不再跑 pre，只能靠每日 cron 自己触发，
     或者在真要出 pre 包时当成「未验证」对待（§3.1）；
  3. **本轮 5 项 + 上一轮 5 项的设备核验**（§5.1）；
  4. `ChatMessage.kt` 的引用链接（§5.3，刻意留的）。
- **建议的下一步顺序**：
  1. 触发一次 `baseline-profile` 任务（无需设备即可结掉 C7 的最后一步）；
  2. 上设备过 §5.1 的核验表（两轮一起过）。
- **恢复动作**：读本文档 §3.1 / §5，再读 `docs/superpowers/upstream-sync-checklist.md`
  与审计文档 §2（D 类原始描述在 §2-5、C4 在 §2-7）。
