# 交接文档：版本号 3.2.1 + 上游侦察（13 条）+ 贴图提示词盘点

**最后核对：2026-09-19** —— 仓库 `HEAD = 8f833f1e`（与 origin 同步），工作树干净。
**CI 全绿**（`8f833f1e` run `35433359615`）。上一份是
`2026-09-19-export-image-and-workspace-backup-modes.md`（其三条修复已设备核验通过）。

---

## 0. 一句话概况

本段**只有一个代码提交**（版本号），其余是两件「读出来但没动代码」的事：

1. **版本号 3.2.1 / 1176**（§1、§2.1）—— CI 绿，产物 tag 已核对。
2. **上游侦察**：我们 pin 的 `2689e753` 之后上游又走了 **13 条**，其中 **3 条踩 fork 的钉死依赖**
   （haze、quickjs、连字），是下次同步的主要风险点（§2.2）。**没动任何代码**。
3. **给 AI 的贴图提示词盘点**（§2.3）—— 用户问了一次现状，只读不写。

---

## 1. 提交链

| # | 提交 | 内容 |
|---|---|---|
| 1 | `8f833f1e` | `chore: 版本号 3.2.1（versionCode 1175 → 1176）` |

起点是上一份交接的收尾提交 `3dfeb707`。

---

## 2. 关键决策与认知

### 2.1 版本号只有一处来源，CHANGELOG 故意没动

```diff
-        versionCode = 1175
-        versionName = "3.2.0"
+        versionCode = 1176
+        versionName = "3.2.1"
```

`app/build.gradle.kts` 是**唯一**来源：三个 buildType 的 `BuildConfig.VERSION_NAME/VERSION_CODE`
都从 `defaultConfig` 取（:`125/133/151`），工作流里没有写死版本。核过三件事：

- **没有任何 buildType 覆盖版本号**（全仓无 `versionNameSuffix`）→ debug / pre / nightlyDebug
  报的都是 3.2.1；
- `nightly-debug` tag 现在指向 `8f833f1e`（`git ls-remote origin refs/tags/nightly-debug`），
  所以 release 页那个 `app-debug.apk` 就是带 3.2.1 的包 —— **产物文件名里不含版本号，只能靠 tag 对**；
- 与上次 `3.1.0 → 3.1.1`（同样只改这一个文件）的做法一致。

**`CHANGELOG.md` 故意没动**：它目前只有 `## 未发布` 一节，从没有版本号分节，文件头写明它的定位是
「本 fork 相对**上游**的用户可见改动」的累计表、不是逐版发布记录，而且上次 3.2.0 的 bump 也没动它。
→ **待用户拍板**：是否改成「每次发版把 `未发布` 落成一个版本号小节」（那样 3.2.1 会收纳本轮的
界面四项 + 导出图片 + 工作区备份修复）。用户没说，就没自作主张。

### 2.2 上游侦察：13 条，3 处踩 fork 的钉子

区间 `2689e753`（09-11，我们的 pin）→ `64319122`（09-18，上游 HEAD）。

**先看两个「可以同步」的前提**（上次逼我们不敢跟上游 HEAD 的两件事都好了）：

- 上游 HEAD `64319122` 的 **Daily Build 是 success（09-18）** → 符合本仓「pin 上游最后一个 CI 绿提交」的规则；
- 上游自己误删的 `gradle/libs.versions.toml` 已在 `bc9d2582` 补回（+197 行）—— 上次就是它逼我们停在 `2689e753`。

| 主题 | 提交 | 落点 |
|---|---|---|
| 输入栏毛玻璃 | `9a35e3f2`（升 haze）、`7ee13f2a`（blur/glass 效果） | `PreferencesStore` + `ChatInput.kt` + 设置页 + 6 locale + 版本目录 |
| quickjs 迁移 | `c8853531` | `JavascriptTool`、`CustomJsSearchService`、`common/js/QuickJSFetch`（+2 个新测试） |
| 连字修复 | `a7850967`（close #1920） | `Markdown.kt`(×2)、`MarkdownNew.kt`、`SimpleHtmlBlock.kt` |
| 提供商/默认值 | `64319122`、`5078ce19`、`8e304bb1` | 删「小马算力」默认项；MiniMax 区域/TTS 默认值；**新增火山引擎 TTS**（318 行） |
| 依赖与杂项 | `d47d13a6`、`bd936caa`、`d6ba728e`、`3428c60b`、`bc9d2582` | 6 locale 文案；session id 头修复；更新依赖；上游版本 → 2.5.2；补回版本目录 |

**三处风险（同步前必须单独处理，别照搬）：**

1. **haze**：上游现在是 `2.0.0-rc01`，且 artifact 名字变了 —— 我们用 `haze-blur-materials`，
   上游换成了 `haze-blur-material3` 并新增 `haze-glass` / `haze-glass-material3`。
   本仓刻意钉在 `2.0.0-alpha03`（memory `haze-pinned-alpha03`：beta 之后的样式语义会让输入框变全透明）。
   → 这两条要**只取效果、不取版本**，或者另找实现。
2. **quickjs**：`c8853531` 把 `wang.harlon.quickjs:wrapper-android` 换成了
   `io.github.dokar3:quickjs-kt:1.0.15`。而**本仓的 `highlight/build.gradle.kts:20` 就是
   `api(libs.quickjs)`**（Prism.js + QuickJS 那套高亮，见 memory `highlight-prism-revert`）。
   照搬这条提交，highlight 模块立刻解析不到依赖。→ 给 highlight 单独保留坐标，或把高亮也迁过去。
3. **连字 7 行**：给**行内 code span** 加 `fontFeatureSettings = "'calt' 0, 'liga' 0, 'clig' 0"`。
   上游更早那版（`7b92f89e`）我们**已经**移植进 `highlight/.../HighlightText.kt:135` 了，但这 7 行
   在 Markdown 渲染器里（那三个文件我们改过不少）→ 需要手工移植或解冲突。

其余 10 条属低风险常规合并；注意 locale 文案（6 个 locale 本仓都改过）与
`bd936caa`（`TextGenerationParams.sessionId` 默认值改成随机 `Uuid`、`backgroundTextGenerationParams`
多一个 `conversationId` 参数 —— `ChatService` 是合并冲突高发区）。

### 2.3 给 AI 的贴图提示词：四处（本段只读盘点，未改）

用户问了一次现状。记下来省得下次满仓找：

| # | 位置 | 触发条件 | 内容要点 |
|---|---|---|---|
| 1 | `WorkspaceReminderTransformer.kt:107`（`<workspace>` 块内一行） | 助手绑了工作区 **且** `shellStatus == READY` | 「用 `file://` URL 引用，应用会内联渲染」，给 `/workspace`、`/upload`、rootfs 内任意绝对路径三个例子 |
| 2 | `UploadReminderTransformer.kt:44`（`<uploaded_files>` 块） | **无**工作区 **且** 本次消息带 `/upload` 下的真实文件 | 列出本次附件的 `file:///upload/…` URL + 「这样贴回来，其它文件渲染成可点链接」+ 指向 `read_image` |
| 3 | `SearchTools.kt:124-128`（`search_web` 的**工具描述**，不是系统提示） | 用搜索工具时 | **先 `read_image` 看过再贴**、贴图是可选的（无意义就别贴）、最多 2–4 张、只用 `images[]` 里的 url |
| 4 | `ImageLazyLoadTransformer.kt:45`（懒加载标记） | 用户附了图（图片内容不随消息发送） | 给出 `file:///upload/…` URL 并说明「内容不会自动带上，要看就调 `read_image`」—— 贴图链路的前半段 |

1、2 都追加到**第一条 system 消息**（共用 `WorkspaceReminderTransformer.kt:115` 的 `internal fun
UIMessage.appendText`），没有就插一条 `UIMessage.system` 并标 `isSynthetic = true`（合成消息不过
messageTemplate，见 memory `synthetic-message-skip-template`）；注册点 `ChatService.kt:918-919`。
渲染侧对应能力在 `MarkdownBlock` 的 `LocalWorkspaceFileProvider` 那条链上（也就是上一份文档修的几处）。

---

## 3. git / CI 状态

- 代码 HEAD = `8f833f1e`，与 origin 同步，工作树干净。
- **三次修复 + 一次版本号，四次 run 全绿**：

| 提交 | run | 结论 |
|---|---|---|
| `e02edafd` 导出补 workspaceId | `35430010140` | success，build 21 步 0 skipped |
| `7e86ce18` 备份保权限位 | `35431237348` | 同上 |
| `76287884` 备份只跳顶层 tmp | `35432962717` | 同上 |
| `8f833f1e` 版本号 3.2.1 | `35433359615` | 同上 |

判定只认 `--json conclusion,headSha` + `--json jobs`（`gh run watch` 的退出码不可信，见 memory
`gh-run-watch-monitoring`；本段还遇到一次 watch 撞 GitHub API 的 EOF 提前退出 → 改用「轮询到
completed 再核」）。

---

## 4. 恢复地图

```bash
git log --oneline 3dfeb707..HEAD          # 本段（只有一个提交）
git show 8f833f1e -- app/build.gradle.kts # 版本号
git fetch upstream && git log --oneline 2689e753..upstream/master   # 上游 13 条
gh run view 35433359615 --json conclusion
```

- 版本号：`app/build.gradle.kts` 的 `defaultConfig`（唯一来源）。
- 上游侦察结论只在本文档 §2.2；同步流程读 `docs/superpowers/upstream-sync-checklist.md`。
- 贴图提示词四处：见 §2.3 的表。

---

## 5. 待办

### 5.1 设备核验（CI 绿 ≠ 功能对）

| 项 | 怎么验 | 状态 |
|---|---|---|
| 备份不再丢 `.l2s.*` 与嵌套 `tmp` | 在沙箱里 `ln -s` 建过链接的工作区：导出→导入，看链接还能用 / `files/tmp` 里的文件还在 | ⏳ 待验证（`76287884`，非阻塞） |
| 老 zip 仍可导入 | 用修复前导出的那份 zip 再导一次（走 `legacyOwnerMode` 兜底） | ⏳ 可选 |
| 版本号显示 3.2.1 | 装最新 debug 包 → 关于页 / 应用信息 | ⏳ 待验证（`8f833f1e`） |

上一份交接 §5.1 里那 5 条更早的设备核验仍未做（UI 四项里的图标、宽高比、`file://` 链接、两条提示词）。
上一份文档已验的两条（导出图片里的工作区图片、工作区导出→导入）**已通过**。

### 5.2 悬着的（本段已知、未做）

- **上游 13 条同步**（最大的一块）：建议分两步 —— 先把 haze / quickjs 之外的 11 条合进来跑绿，
  再单独处理那两条（各自单独提交、单独回滚）。pin 候选 = `64319122`（其 Daily Build 绿）。
- **CHANGELOG 约定**待用户拍板（§2.1）。
- 上一份交接 §5.2 剩下的：导出 markdown 图片仍走 Coil 异步 + `BitmapComposer` 固定 100ms 等待；
  `/upload` 非图片文件没有应用内预览；`ChatMessage.kt` 的文档/视频/音频 chip 与
  `WorkspaceTerminalSession.kt:253` 仍是裸 `startActivity`。

---

## 6. 技术约束 / 惯例（沿用，必须遵守）

- **本机无 Android 编译器**：编译/单测结论只从 GitHub Actions 取；先 `git push` 再
  `gh workflow run nightly-build-debug.yml --ref master`；只用 `--json` 判结论（§3）。
- **不再手动触发 pre / release 工作流**，只构建 debug（两个每日 cron 保留）。
- 文件删除一律走 `~/.claude/scripts/trash.sh`，禁止 `rm`；禁止命令行清空回收站。
- `.kt` 行尾是混合的（LF/CRLF 都有），`core.autocrlf=true` 提交时归一成 LF —— **改文件保持原行尾**。
- huge-icons 钉 1.3 且那个构建缺弧，`ForkIcons` 不能清（memory `huge-icons-pinned-1-3`）。

---

## 7. 停靠点

本段没有未完成的工作，**唯一的下一步是用户要不要同步上游**。若同步：

1. 先读 `docs/superpowers/upstream-sync-checklist.md`（铁律 + fork 身份标识 + 13 类静默破坏锚点）；
2. 起分支 `sync/upstream-2026-09-19`，pin `64319122`（不是上游 HEAD 也行，但它现在 CI 绿）；
3. **haze 与 quickjs 两条单独处理**（§2.2），别混进机械解冲突那个提交；
4. 连字 7 行手工移植到本仓的 Markdown 渲染器；
5. 合并后照例 push → `gh workflow run nightly-build-debug.yml` → `--json` 判结论。

若不同步，本仓当前状态是干净的：`8f833f1e`，四次 CI 全绿，唯一的非阻塞核验在 §5.1。
