# 交接文档：B 类 23 项逐条拍板并实施完毕

**日期**：2026-09-12（当日第四份；前三份为 `2026-09-11-upstream-sync-2689e753-next-phase.md`、
`2026-09-12-post-sync-regression-fixes-next-phase.md`、`2026-09-12-signing-keys-fixed-audit-complete-next-phase.md`、
`2026-09-12-a-class-regressions-and-sync-rules-next-phase.md`）
**状态**：master HEAD = `5bf5d59a`；4 个批次提交已 push，debug CI 见 §3
**目的**：用户要求「B 类 23 项，逐个向我提问，让我选择或输入方案」，逐条拍板后按四个批次全部实施。

> **A/B/C/D/E 是什么**：更早一轮「告知我所有问题」的 62 项清单分组 ——
> **A** 合并已静默破坏的 11 项｜**B** 「半截」/行为变化 23 项（需产品判断）｜
> **C** 结构性风险：下次同步必踩 17 项｜**D** 历史挂起｜**E** 文档/memory 已过时。
> A 类已在上一个阶段消化完（见上一份交接）；本轮处理 **B 类 23 项全部**。

---

## 0. 一句话概况

B 类 23 项**全部消化**：**19 项改代码**（分 4 个批次提交），**4 项结论是「不用改」**
（B7 纯附件可发、B8 自动重试默认开、B9 审批语义 —— 都是上游有意的 `fix:`；B6 只需写更新日志）。

决策表在 **审计文档 §7.2**，逐条带落地要点；**§7.1 更正了审计原文的三处事实错误**（都是本轮
复核时才发现，见 §2.2）。

---

## 1. 已完成（commit 链）

```
5bf5d59a fix(providers): 移除赞助商供应商、新增更新日志与 baselineProfiles 过期守卫（B 类批次 4）
7d246286 fix: 工具输出落盘顺序、子代理截断与会话 id、/tool_outputs 保留策略、TTS 迁移、终端挂载表（批次 3）
476d100f fix(search): Exa 请求侧补齐、删 BUILT_IN 死枚举、判据同源、搜索提示说实话（批次 2）
226896ec fix: pre 混淆 DSL 对齐、自定义 action 按变体隔离、补 6 locale 与断言修正（批次 1）
a10e76dc docs(audit): 记录 B 类 23 项决策结果 + 更正三处事实错误   ← 本阶段起点
```

### 1.1 逐条落点

| # | 项 | 处置 | 落点 |
|---|---|---|---|
| B1 | Exa 请求侧只进来一半 | ✅ 补齐：`contents` 同时请求正文与摘录 + 支持 `max_age_hours` | `ExaSearchService.kt`；`ExaSearchServiceTest.kt` **+3 用例** |
| B2 | `SearchMode.BUILT_IN` 无人产出 | ✅ 删掉枚举值与 `ChatPage` 不可达分支 | `SearchPicker.kt`、`ChatPage.kt` |
| B3 | 两条判据不同源 | ✅ 判据改为供应商 + 协议（Claude/Google 恒可，OpenAI 仅 `useResponseApi`） | `SearchPicker.kt`（新增 `supportsBuiltInServerSearch`） |
| B4 | `enableWebSearch` 静默失效 | ✅ 提示说实话：新增 `web_search_builtin_active` × 6 locale | `ChatInput.kt` |
| B5 | MCP 非法名 pause 队列 | ✅ 保留 pause，报错文案写明「队列已暂停」 | `ChatService.kt` + `error_mcp_invalid_server_name_queue_paused` × 6 |
| B6 | 标题/建议改走快速模型 | ✅ 接受上游（`9365c297` **close #1768**），写进更新日志 | **新增 `CHANGELOG.md`** |
| B7 | `isEmpty()` 语义变化 | ✅ **接受上游，不改代码**（`isBlank` + 计入附件） | — |
| B8 | 自动重试默认开 | ✅ **接受默认开，不改代码** | — |
| B9 | 审批「等前一个 job」 | ✅ **接受上游，不改代码**（`55506496` 是 `fix:`） | — |
| B10 | 子代理绕过新机制 | ✅ 只补截断 + `sessionId` | `SubAgentRuntime.kt`、**新增 `ToolOutputLimits.kt`** |
| B11 | 32KB 落盘的是已裁剪文本 | ✅ 调换顺序：先落盘完整输出再裁给模型 | `GenerationLoop.kt` |
| B12 | `/tool_outputs` 冷启清空 | ✅ 改为 TTL 7 天 / 总量 64MB 增量清理 | `RikkaHubApp.kt` |
| B13 | 赞助商 provider 自动注入 | ✅ 两处列表都移除 APIMart / MaruCode | `DefaultProviders.kt`、`RecommendedProviders.kt` |
| B14 | Qwen Audio 3.0 无迁移 | ✅ 读配置时就地迁移 `qwen3-tts*` → `qwen-audio-3.0-tts-plus` | `PreferencesStore.kt` |
| B15 | 上游搜索 UI 未采纳 | ✅ 保留 fork UI，删 6 个孤儿串 × 6 locale | 6× `strings.xml` |
| B16 | 终端 bind mount 第三处硬编码 | ✅ 改为消费 `FileFolders.ROOTFS_BIND_MOUNTS` | `WorkspaceTerminalSession.kt` |
| B17 | 预览按钮硬编码中文 | ✅ 走 `stringResource` + 六 locale | `WorkspaceFileEditorPage.kt` + 6× `strings.xml` |
| B18 | 5 个未翻译串 | ✅ 补 4 个终端无障碍串 + 删 1 个零引用死串 | 6× `strings.xml` |
| B19 | baselineProfiles 过期 | ✅ 保留 + 新增过期检测守卫脚本 | **新增 `scripts/baseline_profile_audit.py`** |
| B20 | compose 稳定性声明悬空 | ✅ 删两条悬空 + 补 `me.rerere.ai.ui.StreamChunk` | `app/compose_compiler_config.conf` |
| B21 | action/scheme 未按变体隔离 | ✅ 改 `${applicationId}` 派生 | `AndroidManifest.xml`、`RouteActivity.kt`、`KeepAliveService.kt`、`shortcuts.xml` |
| B22 | `pre` 用旧混淆 DSL | ✅ 对齐 release 的 `optimization { enable = true }` | `app/build.gradle.kts` |
| B23 | 测试断言 / 发布说明包名错 | ✅ 断言改 `BuildConfig.APPLICATION_ID` + 修 `.pre` 文案 | `ExampleInstrumentedTest.kt`、`nightly-build-pre.yml` |

### 1.2 本轮新增的文件

| 文件 | 用途 |
|---|---|
| `CHANGELOG.md` | fork 的用户可见改动清单（B6 的「更新日志」落点，也是以后每轮的惯例入口） |
| `app/.../data/ai/tools/ToolOutputLimits.kt` | `clipToolOutput` + `MAX_TOOL_RESULT_LENGTH`，主循环与子代理共用 |
| `docs/superpowers/scripts/baseline_profile_audit.py` | baselineProfiles 过期检测（B19）；已登记进同步清单 §7 |

---

## 2. 关键决策与认知

### 2.1 「先判性质」的判据又救回三条（B6 / B9 / B7）

上一阶段 A4/A5 的教训固化成判据之后，本轮有四项直接按判据定案，**没有再出现"恢复"被 CI 或
commit 复查推翻的情况**：

| 项 | 找到的提交 | 判据命中 | 结论 |
|---|---|---|---|
| B6 | `9365c297 fix: 支持配置快速模型思考级别，并移除单独的标题和建议模型配置` | `fix:` + **close #1768** | 保留上游 |
| B9 | `55506496 fix: 修复连续工具审批丢失及取消状态误标` | `fix:` | 保留上游 |
| B7 | 与上游新抽出的 `SendButton(empty=)` 是一套设计 | 配套设计 | 保留上游 |
| B13 | `0533acde chore: 移除默认rikkahub提供商`、`8b3e094b docs: 新增apimart赞助商，移除ack ai` | 上游有意轮换 | 只处理 fork 立场（移除） |

> **可复用的一招**（与上一阶段同）：判定"上游的静默行为变化"是回归还是修复时，
> 先 `git show -s --format=%B <sha>` 看是不是 `fix:`、有没有 close issue，
> 再看 `git show --stat --format='' <sha>` 有没有配套单测。三者任一成立 = 上游有意的行为。

### 2.2 本轮又推翻审计文档里的三处说法

**复核时才发现，已写进审计文档 §7.1**：

1. **B7「以前是换行」是错的** —— 旧逻辑在"只贴图不打字"场景是**按键完全没反应**
   （`sendOnEnter` 开着时 `imeAction = ImeAction.Send`，那颗键是动作键、不插换行，
   而 `isEmpty()=true` 让守卫直接短路）。顺带发现上游其实改了**两处**
   （`isEmpty()` → `isBlank()` 防空格空消息；再加 `&& messageContent.isEmpty()` 让纯附件可发），
   审计把两条混成了一条。
2. **B2 不是「死值」** —— 是合并把两侧拼在了一起：上游 `8c3f8240` 的设计本身自洽
   （两张 `SearchModeCard` 都走 `onUpdateSearchMode`），而 fork 侧当时**根本没有** `SearchMode`
   枚举（用的是 `onToggleSearch: (Boolean)`）。合并取了 fork 的界面 + 上游的管道名，
   内置搜索那张卡保留 fork 直写 `model.tools` 的写法 → 枚举留下了却无人产出。
3. **`optimization {}` 不是本次新增** —— 它在分叉点 `4b6449e3:app/build.gradle.kts:73-77` 就有；
   用旧 DSL 的是 **fork 自己后加的** `pre` build type（`8b1a140c`）。

> 教训：审计文档写「上游改主意、无人表态」这类**动机判断**时，必须用 commit 复查；
> 写「新增/删除」这类**事实判断**时，要跟分叉点比对，别跟"上一次同步"比对。

### 2.3 两个「顺序」问题（本轮的隐藏主题）

- **B11 落盘顺序**：`maybeTruncateToolOutput(clipToolOutput(parts))` 是"先裁再落盘"，
  于是 500KB 的输出落到 `/tool_outputs` 的只有 100KB，模型 `cat` 也拿不到全量。
  改成"先落盘完整输出、再把预览裁给模型"。无 shell 权限时仍落到 100KB 兜底，安全网不丢。
- **B16 挂载表来源**：终端会话是**第三处**独立硬编码（前两处是 `RepositoryModule` 与
  `WorkspaceFileUrlResolver`，二者共用 `FileFolders.ROOTFS_BIND_MOUNTS`）。
  终端少挂 `/upload` 与 `/tool_outputs` —— 提示词已经向模型承诺 `/upload/<file>` 可读，
  用户在同一台设备的终端里 `ls /upload` 却是空的。改为一并消费那张表。

### 2.4 本机踩的两个坑（下次直接用现成写法）

1. **Bash heredoc 会吃掉 `\\`** —— python 脚本里写 `joinToString("\\n")` 到了文件里变成真换行，
   Kotlin 直接语法错误。规避：`BS = chr(92)` 拼字符串，或用 `Write` 工具直接落文件。
2. **`grep -c $'\r'` 测换行不可靠** —— 它对每个文件都返回 `行数`（模式被吃成空）。
   要判断 CRLF/LF 用 python 数 `s.count("\r\n")` 与 `s.replace("\r\n","").count("\n")`。
   本仓并非清一色 CRLF：`KeepAliveService.kt` 就是 **LF**，`ChatInput.kt` 上轮还留了 1 处孤立 LF
   （本轮顺手归一）。**逐文件探测**，别按扩展名想当然。

---

## 3. git / CI 状态

- 分支 `master`。本阶段 5 个提交（4 代码 + 1 文档），起点 `fe61b8c4`，全部已 push。
- CI：按用户要求**只跑 `nightly-build-debug.yml`**。

| run | 工作流 | conclusion | 说明 |
|---|---|---|---|
| `34697391688` | debug | ✅ **success**（`5bf5d59a`） | 覆盖四个批次全部改动。`Gradle Build` 与 `Unit Tests` **都 success**（含本轮新增的 3 个 Exa 用例），`Prepare signing key` / `Upload Room schema JSON` / `Point tag` / `Publish nightly debug prerelease` 全绿；**`--json jobs` 核过 0 个 skipped** —— 不是「24h 无提交」把 build 整个 skip 的假绿 |

签名未漂移：debug run 的 `Prepare signing key` 打印 `SHA256: 47:B7:DE:…:5C:CD`，
与 memory `signing-key-drift` 里冻结的 debug 指纹逐位一致。

**未跑**：`pre` / `release`。B22 想真验证「`assemblePre` 用新 DSL 能出包」需要额外跑一次 pre 工作流 ——
那会破例一次「平时只跑 debug」，已列为待办（§5.2），**尚未执行**。

---

## 4. 恢复地图

| 文档 | 说明 |
|---|---|
| `docs/superpowers/audits/2026-09-12-upstream-merge-2689e753-audit.md` | 62 项事故复盘；**§7 是 B 类 23 项的决策表与三处更正** |
| `docs/superpowers/upstream-sync-checklist.md` | 下次同步的机械检查清单（§7 脚本索引已加 baseline 守卫） |
| `docs/superpowers/handoffs/2026-09-12-a-class-regressions-and-sync-rules-next-phase.md` | 上一阶段（A/C/E 类） |
| `CHANGELOG.md` | fork 用户可见改动清单（本轮新增） |
| `docs/superpowers/scripts/` | 四个可复用脚本（prefs 键集合 / 同步清单 / APK 签名 / baseline 过期） |

**本轮改动文件（按批次）**：

- 批次 1：`app/build.gradle.kts`、`compose_compiler_config.conf`、`AndroidManifest.xml`、
  `shortcuts.xml`、`RouteActivity.kt`、`KeepAliveService.kt`、`WorkspaceFileEditorPage.kt`、
  6× `strings.xml`、`ExampleInstrumentedTest.kt`、`nightly-build-pre.yml`
- 批次 2：`ExaSearchService.kt`(+test)、`SearchPicker.kt`、`ChatPage.kt`、`ChatInput.kt`、6× `strings.xml`
- 批次 3：`GenerationLoop.kt`、`ToolOutputLimits.kt`(新)、`SubAgentRuntime.kt`、`RikkaHubApp.kt`、
  `PreferencesStore.kt`、`WorkspaceTerminalSession.kt`、`ChatService.kt`、6× `strings.xml`
- 批次 4：`DefaultProviders.kt`、`RecommendedProviders.kt`、`CHANGELOG.md`(新)、
  `baseline_profile_audit.py`(新)、`upstream-sync-checklist.md`

---

## 5. 待办

### 5.1 设备核验（**CI 全绿 ≠ 功能对**）

B 类里**真改了行为**的这些需要上设备：

1. **B11 工具输出落盘**：让 AI 跑一个输出 >100KB 的命令，确认 `/tool_outputs/<id>.txt` 是**完整原文**
   （此前只有 100KB），模型 `cat` 能拿到全量。
2. **B12 `/tool_outputs` 保留**：跑一次产生落盘的工具 → 杀进程重进 → 在同一会话里让模型
   `cat /tool_outputs/<id>.txt`，应仍然存在（此前冷启即 404）。
3. **B16 终端挂载**：终端里 `ls /upload`、`ls /tool_outputs` 应与 AI 侧 `workspace_shell` 一致（非空）。
4. **B14 TTS 迁移**：老配置（`qwen3-tts*`）升级后打开 TTS 设置页，模型名应已变成
   `qwen-audio-3.0-tts-plus`；若报音色无效则重选一次。
5. **B1 Exa 证据**：默认路径下搜索，模型应能同时看到摘录与正文；带 `max_age_hours` 的搜索应生效。
6. **B3 内置搜索开关**：用第三方网关（自建 modelId + Responses 协议）的模型，**应该能看到**
   内置搜索开关（此前被 modelId 启发式挡住）。
7. **B4 提示**：模型带内置搜索时打开「联网搜索」，toast 应提示「该模型使用内置搜索」。
8. **B21 快捷方式**：长按图标进翻译页仍可用；三变体同机共存时不再互抢 intent。
9. **B13 供应商列表**：新装机不应出现 APIMart / MaruCode；删掉之后重启不应被补回。

### 5.2 需要另跑一次 CI 才能验的

- **B22**：`assemblePre` 用新 DSL 能否出包 —— 需要跑一次 `nightly-build-pre.yml`（**破例一次**）。

### 5.3 仍未动（不属于 B 类）

- 审计 §6 剩下的 **C 类结构性防复发 4 条**：`.gitignore` 的 `references` 改锚定、
  keep 规则拆 `fork.keep`、`pre` 用 `matchingFallbacks`、`WorkspaceShellContext` 双构造点合并。
- **D 类历史挂起**：`workspaces/` 不在整机备份、`file://` 链接点击崩、OCR 无时间上界、
  BMP 能识别不能发、备份诊断字段缩水、S3/WebDAV 恢复无确认对话框。
- **baselineProfiles 重新生成**：需要连着设备/模拟器的机器（`./gradlew :app:generateReleaseBaselineProfile`）。
  当前 7 个过期项（`GenerationHandler`、`LocalTools`/`LocalToolOption` 换包、三个 provider 换子包、
  `SponsorAPI` 已删），守卫脚本可以随时复现。

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
- **换行符**：本仓**不是**清一色 CRLF（有 LF 文件，也有混行的）。改文件前用 python 探测该文件的实际换行，
  写回后校验"孤立 LF/CR = 0"。用 `Edit`/`Write` 工具改 CRLF 文件容易写坏。
- **写 python 脚本处理源码时不要用 `\\`**：Bash heredoc 会吃掉反斜杠，用 `chr(92)` 拼。

---

## 7. 停靠点

- **已完成**：B 类 23 项全部消化（19 项改代码分 4 批 + 4 项确认「不用改」）；**debug CI 在 `5bf5d59a` 全绿**（§3）；审计文档 §7 记决策与三处更正；
  新增 `CHANGELOG.md`、`ToolOutputLimits.kt`、`baseline_profile_audit.py`。
- **待确认**：§5.1 的 9 条设备核验 ｜ §5.2 的一次 pre CI ｜ §5.3 里 C 类 4 条 + D 类历史挂起。
- **下一阶段候选**：① 上设备过 §5.1；② 挑 §5.3 里"零风险高收益"的 C 类两条
  （`matchingFallbacks` 与 keep 拆文件）；③ 有设备时重新生成 baselineProfiles。
- **恢复动作**：读本文档 §5，再读审计文档 §7（决策表），再读 `upstream-sync-checklist.md`。
  **开工前先让用户挑一批。**
