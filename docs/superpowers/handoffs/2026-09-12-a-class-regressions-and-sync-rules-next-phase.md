# 交接文档：A 类回归修复 + C 类同步规则 + E 类过时更正

**日期**：2026-09-12（当日第三份；前两份为 `2026-09-12-post-sync-regression-fixes-next-phase.md`、
`2026-09-12-signing-keys-fixed-audit-complete-next-phase.md`）
**状态**：master HEAD = `eeef61cc`；代码已 push 且 **debug CI 绿**（§3），文档随之提交
**目的**：按用户指令「先修复 A 类，C 类写规则，E 类把过时的修好，其他先不动」执行完毕的交接。

> **A/B/C/D/E 是什么**：上一轮"告知我所有问题"的 62 项清单分组 ——
> **A** 本次合并已静默破坏的 11 项 ｜ **B** 「半截」/行为变化 23 项（需产品判断）｜
> **C** 结构性风险：下次同步必踩 17 项 ｜ **D** 历史挂起（非本次引入）｜ **E** 文档/memory 已过时 9 项。
> 分类全文见 `audits/2026-09-12-*.md`，本轮只动 A/C/E。

---

## 0. 一句话概况

A 类 11 项**全部消化**：**8 项改代码**，**3 项经复核改为"保留上游"**，1 项定为"有意不做"+改文档。
C 类 17 项从审计文档里的陈述固化成仓库内的**可机械执行清单** `upstream-sync-checklist.md` + 3 个脚本；
E 类的过时结论就地更正（含 7 份历史交接里的 11 处「release/pre CI 未跑」）。

> ⚠️ **A4/A5 是本轮最大的一个弯**：初判当成"上游顺手改掉、无人表态"的静默回归并动手恢复，
> 结果 A5 被 CI 的 `ThinkTagTransformerTest` 当场顶回来、A4 回头一查也是有专门 `fix:` 提交的行为 ——
> 两条都已撤回，判据与证据见 §2.2。**审计里的"无人做过这个决定"这类措辞，落笔前必须用 commit 复查。**

---

## 1. 已完成（commit 链）

```
a9a2bf31 docs: A4/A5 更正为「保留上游」+ 同步清单补判性质方法；新增本轮交接文档  ← 本轮文档提交
eeef61cc fix(ui): 撤回 A4/A5 的行为"恢复" —— 上游两处都是有意的 fix          ← 代码最终态（debug CI 在此绿）
7d8beb6f docs: 更正交接文档里与事实不符的结论（E 类过时项）
8ea43a44 docs(sync): 上游同步检查清单 + 三个可复用脚本（C 类防复发规则）
397c5afb chore(docs): 取回 .editorconfig、补回 AGENTS.md 被顶替的贡献者章节
d8b8fc3c fix(ui): 恢复合并前被静默改掉的三处界面/解析行为
d08fed4f fix(db): Migration_25_26 补建 message_embeddings，外来 v25 备份才能升到 v27
33b62752 fix(workspace): Shell 兼容模式接进流式路径与工作区备份
354108ac fix(settings): persistSettings 补写三个 fork 键
```

### 1.1 A 类 11 项逐条

| # | 问题 | 处置 | 落点 |
|---|---|---|---|
| A1 | `persistSettings` 漏写 `SUB_AGENT_MODEL`/`EMBEDDER`/`KEEP_ALIVE_ENABLED`（改完重启即回退、备份恢复不落地） | ✅ 修 | `354108ac` |
| A2 | `shellCompatibilityMode` 没接流式 shell（AI 的 `workspace_shell` 拿不到开关） | ✅ 修 | `33b62752` |
| A3 | `isPending` 同文件自相矛盾（被「停止生成」掐断的工具显示死按钮） | ✅ 修 | `d8b8fc3c` |
| A4 | 输入框 IME 形态（键盘弹出时不再变直角贴住） | ❌ **误判：保留上游**（`f86d6e82`「fix: 键盘弹出时 ChatInput 保持圆角和底部间距」；恢复已撤回） | 无代码改动（仅留说明注释） |
| A5 | `<think>` 正则只认正文开头（行内思考不抽成 reasoning 块） | ❌ **误判：保留上游**（`85402745`「fix(thinking): ignore inline think tags」+ 7 个单测；恢复已撤回） | 无代码改动（仅留说明注释） |
| A6 | 合成消息不再过消息模版 | ✅ **决策：保留上游**（理由见 §2.1） | 无代码改动 |
| A7 | `.editorconfig` 被上游静默删除 | ✅ 按 blob 逐字节取回（`0464e05e`） | `397c5afb` |
| A8 | `AGENTS.md` 被上游版整体顶替 | ✅ 以上游版为基底补回五段 | `397c5afb` |
| A9 | `liveOutput` 有生产者无 UI 消费者 | ✅ 定为「有意不做」+ 更正文档（§1.3） | `7d8beb6f` |
| A10 | 工作区导出/导入丢 Shell 兼容模式 | ✅ 修（字段带默认值，旧 zip 仍可读） | `33b62752` |
| A11 | 外来 v25 备份升 v27 开库失败 | ✅ 修 | `d08fed4f` |

**A1 的复核**：用脚本比对「声明 / 读取 / 写入」三集合，HEAD 与合并前 `7042fa80` 已一致 ——
读写集合的唯一差异是上游**故意移除**的 `TITLE_MODEL`/`SUGGESTION_MODEL`；「读取 − 写入」只剩
`SEARCH_SELECTED`（只读的旧版迁移源，已在脚本白名单里注明）。

**A11 为什么这么修**：`message_embeddings` 只由 `AutoMigration(24,25)` 的**编译期产物**创建，全仓没有手写
`CREATE TABLE`。我们自己的 v24/v25 库升上来必然有，但 fork 支持恢复**别人的**备份，而上游 rikkahub 的 v25
库里没有这张表、也不会经过 24→25（已核实：上游 schema 最大版本是 **25**，且其 v25 有 `workspaces.shell_compatibility_mode`
与自己的 `MemoryEntity`，唯独没有 `message_embeddings`）→ 那种库走到 v27 后会以
`Migration didn't properly handle: message_embeddings(…)` 开库失败，备份恢复的暂存校验（`BackupManager`）
走的是同一条链。补建语句与 `app/schemas/…/27.json` 的 `createSql` **逐字一致**（已用脚本比对），已有该表时是空操作。

### 1.2 C 类：写规则（不是改代码）

新增 **`docs/superpowers/upstream-sync-checklist.md`** —— 把「下次同步必踩」的 17 项从"文档里的陈述"
变成**可机械执行**的清单：

- §0 铁律 7 条（pin 上游最后一个 CI 绿提交 / 起 sync 分支 / 本机无编译器 / **查 CI 必须看 `--json jobs`** /
  冲突数 ≠ 难度 / 子代理只读 / 密钥纪律）
- §1 合并前预检（含清单生成脚本用法）
- §2 **fork 身份标识**：包名、三套 signingConfig、ABI、工作流、**密钥来源（`keytool -genkey` 必须为空；`keystore` 只允许"从 secret 解码 / `keytool -list` 打印指纹 / `local.properties` 的 `*.storeFile=`"三种出现）**、单测门禁 —— 丢了任何一条 CI 立刻红。（⚠️ 别把 workflow 里的 `actions/cache@v4` 误判成签名缓存：那是 Gradle 缓存）
- §3 静默破坏扫描：第 11 类（双构造点）、第 12 类（键集合）、第 9 类（fork 刻意改掉的行为，含 5 个具体锚点）、
  第 8 类（被删/顶替的 config）、第 1/3 类（上游新文件按上游 API 写）
- §4 「下次必踩」20 行表：每行带命令 + 期望 + **现状标记**（哪几条已有代码级防护、哪几条仍是人工）
- §5 CI 判定 ｜ §6 设备核验 ｜ §7 脚本索引

配套脚本（原来只在本机 `D:\Temp\`，本轮收进仓库）：

| 脚本 | 用途 |
|---|---|
| `scripts/prefs_key_audit.py` | Settings 键三集合比对（第 12 类），**带退出码**，可直接接进 CI |
| `scripts/sync_audit_lists.sh` | 生成上游/fork/双方都改 的文件与提交清单（原来是散在手敲命令里） |
| `scripts/apk_signer.py` | 从任意 APK 抽 v2 签名者证书 SHA-256（核对签名漂移） |

入口可见性：`CLAUDE.md` 与 `AGENTS.md` 各加了一节指向清单（CLAUDE.md 是每次会话加载的项目指令，
放这里才算"规则"而不只是"文档"）。

### 1.3 E 类：过时结论就地更正

已修（本轮）：

- 「**release / pre CI 未跑**」**11 处 / 7 份交接** → 统一追加 ✅ 已解决（release 早已多次真跑且绿；
  pre 自 `1a542d72` 起真跑并绿；此前那些 success 是 `check` 判「24h 无提交」把 `build` 整个 skip 后的**假绿**）
- 「libs 并集含 `nav2`/`androidx-navigation2`」→ 这两个 alias 已被上游删除且静默生效，现仓库无
- 「记忆工具 **11 参**」×2 → 实为 12 参
- 「fork v26 = `message_embeddings` + 记忆三列」→ `message_embeddings` 是 **v25** 引入
- 「工具实时输出（`ToolOutput.OutputDelta` 通路）应验收」×2 → **做不到**（见 A9），按「有意不做」处理
- `ChatService.kt:811` → **`:812`** ×2
- 诊断日志列表里的 `hoisted-image` → **已不存在**（`ChatCompletionsAPI` 里那条是解释"图片为何旁挂 user 消息"
  的常规日志，不是待清理项）
- `CLAUDE.md`/`AGENTS.md` 模块列表缺 `videogen`/`oauth` → 已补（两份都补）

已在上轮修（memory）：`upstream-sync-procedure` 第 8 类举例（改为 `.editorconfig`/`AGENTS.md`）、新增第 11/12 类、
`signing-key-drift`（daily-build 已删、nightly 已转 Release）、`memory-system-handoff-chain`（master sha）。

无需改：**「`maybeTruncateToolOutput` 是本次上游新增」**这个说法只出现在对话里，文档中并无此表述
（`grep` 复核过：交接里那两处说的是"两者都保留"，是准确的）。

---

## 2. 关键决策与认知

### 2.1 A6 决策：**保留上游行为**（合成消息不过 messageTemplate）

这是本轮唯一需要用户拍板的项，已在执行中确认。依据是查到了上游那条 commit 的正文：

```
942d0d28 fix: 避免合成消息参与消息模版   close #1790
```

**#1790 的机制**：时间提醒（`TimeReminderTransformer`）每轮重建、`createdAt` 是当次请求时间，所以用户
`messageTemplate` 里的 `{{time}}`/`{{date}}` 每轮渲染结果都不同；这条消息位于 **`messages[0]`**，于是
**整段 Anthropic prompt cache 前缀逐轮失效**（每轮全量重算 = 又慢又贵）。代价（已知并接受）：自定义模版
不再包裹 system prompt / 注入段。默认模版 `{{ message }}` 是恒等变换 → 对绝大多数用户零影响。

> **可复用的一招**：判定"上游的静默行为变化"是回归还是修复时，先 `git show -s --format=%B <sha>` 看它是不是
> 在 close 一个 issue。是的话多半是**修复**，别急着"回退到 fork 行为"。

### 2.2 A4/A5 的"恢复"被撤回：判据是"上游有没有为它做过决定"

初判把 A4/A5 当成"上游顺手改掉、无人表态"的静默回归，动手恢复后被两件事纠正：

1. **CI 当场抓住 A5**：`ThinkTagTransformerTest > think tag in visible answer should be preserved FAILED`
   —— 上游 `85402745`「fix(thinking): ignore inline think tags」**带 7 个单测**，"行内/后置的 `<think>`
   属可见文本、应保留"是明确定义的行为。要保住"恢复"就得删掉上游的测试，那是比撤销这一行大得多的决定。
2. **回头查 A4 的来历**：`git log --oneline -S 'isImeVisible' 4b6449e3..2689e753 -- <ChatInput.kt>`
   命中 `f86d6e82 fix: 键盘弹出时 ChatInput 保持圆角和底部间距` —— **提交标题就是那个决定**。

于是两条都改为"保留上游"，只在代码里留注释说明**为什么别改回去**（否则下一个会话会再"修"一次）。

- **现行判据**：先 `git log --oneline -S '<被删/被改的标识符>' <分叉点>..<pin> -- <file>` 找到那条提交，
  再 `git show -s --format=%B <sha>`（是不是 `fix:`、有没有 close issue）+
  `git show --stat --format='' <sha>`（有没有**配套单测**）。三者任一成立 = 上游有意的行为，**别回退**；
  三者皆无（纯重构顺带）= 静默回归，才该恢复。
- A6（合成消息过模版）同理：`942d0d28` close 了 #1790 → 保留。
- 若你更想要 A4/A5 的旧观感与旧语义：改动量分别是 6 行与 1 行，说一声就翻。

### 2.3 A9：为什么不是"恢复"，而是"确认为有意不做"

`liveOutput` 的 UI 读取端是**更早的 fork 提交 `c21225e1` 自己删掉的**（不是本次合并弄丢的），
`ui/` 目录下读取点为 0。因此「工作区 shell 应流式显示」这条验收项本身就是错的 —— 改文档，不改代码。

### 2.4 为什么没给 `persistSettings` 加 Kotlin 单测

审计原本建议"补一个键集合往返单测锁住"。**源码文本比对**（`prefs_key_audit.py`）是更直接的判据，而且它
可以同时覆盖"读取 − 写入"这种 DataStore 往返测不出来的缺口（往返测需要真 Context + DataStore，
在 JVM 单测里得搭一整套 fake）。折中：脚本进仓库并写了退出码，**随时可以接进 CI 成为门禁**（见 §5.3）。

### 2.5 本机白盒验证（CI 覆盖不到的那类，本轮用上了）

`<think>` 正则这条**没法靠 CI 证明行为**（CI 只证明编译 + 单测），于是在本机用 JDK 直接跑
`java.util.regex`（Kotlin 的 `Regex` 就是它）对三种写法做对照 —— 结果反过来推翻了"恢复"这个动作：

| 输入 | 合并后的 `\A\s*<think>…`（**上游有意行为**） | 我曾改的宽松版（**已撤回**） |
|---|---|---|
| `preamble <think>inner</think> body` | NO MATCH → 标签按可见文本保留 | 抽成 reasoning（= 回头看，这才是误判的方向） |
| `<think>inner</think> body` | 抽成 reasoning、closed=true | 同左 |
| `preamble <think>unfinished`（流式中） | NO MATCH（保留原文） | reasoning=`unfinished`、closed=false |
| `preamble without any tag` | NO MATCH | NO MATCH |

**白盒能证明"发生了什么"，不能证明"该发生什么"** —— 后者由 `ThinkTagTransformerTest`（上游 7 个断言）
与 `f86d6e82`/`85402745` 的提交意图给出。教训：白盒结论要拿去和**上游的测试与提交意图**对账，
别只拿它当"回归证据"。

> 本机踩坑：PATH 上的 `javac` 是 **JDK 17**、`java` 却是 **JRE 8** → `UnsupportedClassVersionError`。
> 用 `"/c/Program Files/Java/jdk-17/bin/java"` 跑；另外源码里写中文注释会被按 GBK 解析报错，
> 白盒脚本一律用 ASCII，或 `javac -encoding UTF-8`。

---

## 3. git / CI 状态

- 分支 `master`。**代码 HEAD = `eeef61cc`**（A 类修复 + 撤回，debug CI 在此绿）；其上只有文档提交
  （`a9a2bf31` 及可能的收尾文档提交）。本阶段共 9 个提交，全部已 push。
- 回滚锚点：本阶段起点 `de612cd4`；同步前 `7042fa80`。
- CI：**按用户要求只跑 `nightly-build-debug.yml`**（release/pre 有各自的每日 cron，18:00 / 19:00 UTC，会自己跑）。

| run | 工作流 | conclusion | 说明 |
|---|---|---|---|
| `34694966706` | debug | ❌ **failure** | **这一红很有价值**：315 个测试只挂 1 个 —— `ThinkTagTransformerTest > think tag in visible answer should be preserved`，当场揭穿 A5 的"恢复"是误判（见 §2.2） |
| `34695359242` | debug | ✅ **success**（`eeef61cc`） | `Gradle Build` 与 `Unit Tests` **都 success**（用 `--json jobs` 核过，不是 "24h 无提交 → build 被 skip" 的假绿）；`Publish nightly debug prerelease` 成功 |
| `34694970643` / `34694974559` / `34695364018` / `34695368737` | pre / release | 取消 | 前两个是我多触发的、后两个在撤回提交后已过期，按"平时只跑 debug"一并取消 |

签名未漂移：debug run 的 `Prepare signing key` 打印 `SHA256: 47:B7:DE:…:5C:CD`，与 memory `signing-key-drift` 里冻结的 debug 指纹逐位一致。

---

## 4. 恢复地图

| 文档 | 说明 |
|---|---|
| **`docs/superpowers/upstream-sync-checklist.md`** | **本轮新增的主产物**：同步前/后的机械检查清单 |
| `docs/superpowers/audits/2026-09-12-upstream-merge-2689e753-audit.md` | 62 项事故复盘（已加"后续状态"批注） |
| `2026-09-12-signing-keys-fixed-audit-complete-next-phase.md` | 密钥固定 + 审计（§5.1 已标记 A 类全修） |
| `2026-09-12-post-sync-regression-fixes-next-phase.md` | 同步后三个设备回归（图标/haze/信封） |
| `2026-09-11-upstream-sync-2689e753-next-phase.md` | 上游同步主体（157 提交 / 33 冲突 / DB v27） |
| `docs/superpowers/scripts/` | 三个可复用脚本 |

| 本轮改动文件 | 说明 |
|---|---|
| `PreferencesStore.kt` | 补三个写入 |
| `WorkspaceManager.kt` / `WorkspaceRepository.kt` / `WorkspaceBackup.kt` | 兼容模式透传 + Meta 加字段 |
| `Migration_25_26.kt` / `MigrationUtils.kt` / `Migration_26_27.kt` | 补建表 + `hasColumn` 共用 |
| `ChatMessageTools.kt` / `ChatInput.kt` / `ThinkTagTransformer.kt` | 三处行为恢复 |
| `.editorconfig` / `AGENTS.md` / `CLAUDE.md` | 取回 + 补段 + 同步入口 |
| `docs/superpowers/upstream-sync-checklist.md` + `scripts/*` | C 类规则 |
| 8 份交接 + 1 份审计 | E 类更正 |

相关 memory：`upstream-sync-procedure`（已指向清单，并补了「先判性质再回退」的方法）、`synthetic-message-skip-template`（**新增**，A6 决策）、`gh-build-trigger-order`（**已更新**：平时验证只跑 debug、`daily-build` 已删）、`signing-key-drift`、`memory-system-handoff-chain`（已指向本文档）、`tool-detail-sheet-no-vertical-lazy`、`huge-icons-pinned-1-3`、`haze-pinned-alpha03`。

---

## 5. 待办（本轮**未动**的部分）

### 5.1 B 类 23 项（「半截」/行为变化，需产品判断）

`enableWebSearch` 对带内置搜索的模型静默失效 ｜ `SearchMode.BUILT_IN` 死值 ｜ Exa 请求侧只进来一半 ｜
赞助商 provider 自动注入 ｜ MCP 非法服务器名会 `pause()` 消息队列 ｜ 标题/建议模型改走快速模型 ｜
`isEmpty()` 语义（只贴图片时回车直接发送）｜ 网络自动重试默认开 ｜ 审批语义改为"等前一个 job 结束" ｜
子代理链路绕过本轮所有新机制 ｜ 32KB 落盘的是已被 100KB 裁剪过的文本 ｜ `/tool_outputs` 冷启清空 ｜
Qwen Audio 3.0 TTS 无迁移 ｜ 终端 bind mount 第三处硬编码 ｜ html/svg 预览按钮硬编码中文 ｜
5 个未翻译串 ｜ baselineProfiles 过期 ｜ `compose_compiler_config.conf` 悬空 ｜ 变体 intent 无后缀 ｜ `pre` 用旧混淆 DSL ｜ `ExampleInstrumentedTest` 断言旧包名 ｜ …
（**完整清单见审计文档 §2 与问题清单 B 组**。）

### 5.2 审计文档 §6 的产品决策：9 条里还剩 8 条

剩下：`enableWebSearch` 门控语义 / `SearchMode.BUILT_IN` / 赞助商 provider / `.gitignore:14` 的 `references` 改锚定 /
keep 规则拆文件 / `pre` 改用 `matchingFallbacks` / `.editorconfig` 取回（**本轮已做**）/ `AGENTS.md` 用哪版（**本轮已做**）/
`liveOutput`（**本轮已决**：不做）。
即：**还剩 5 条真正待拍板**（前 5 条）。

### 5.3 C 类里"仍需动代码"的结构性防复发（本轮只写了规则）

`WorkspaceShellContext` 两个构造点合并（或去掉字段默认值逼编译器报错）｜ fork 的 keep 追加块拆成
`app/src/main/keepRules/fork.keep` ｜ `pre` 用 `matchingFallbacks` ｜ `.gitignore` 的 `references` → `/references/` ｜
补 `persistSettings` 键集合门禁（脚本进 CI）｜ 重新生成 baselineProfiles ｜ 清 `compose_compiler_config.conf` 的两条悬空并声明 `StreamChunk` ｜
`androidTest` 纳入 CI（现在 8 个测试形同死代码）｜ 补 5 个未翻译串 ｜ 13 处 `create("pre")`（app + 12 个库模块） → `matchingFallbacks`。

### 5.4 设备核验（**CI 全绿 ≠ 功能对**，沿用前几份交接，仍未做）

本轮**真改了代码**的四处必须上设备复验：① 开工具审批 → 触发需审批的工具 → 停止生成 → 该行
**不应**再出现点了没反应的 ✓/✕（A3）；② 改语义搜索配置 / 保活开关 / 子代理模型 → 杀进程重进仍在，
再从备份恢复一次看是否落地（A1）；③ 工作区开 shell 兼容模式 → 导出 → 导入后开关保留（A10）；
④ 在需要 `PROOT_NO_SECCOMP` 的设备上让 **AI** 跑 `workspace_shell`，表现应与终端页/详情页命令框一致（A2，流式路径）。
A4/A5 已撤回（保留上游行为），**不需要**验。

### 5.5 D 类历史挂起（延续，非本次引入）

`workspaces/` 不在整机备份 ｜ `file://` 链接点击崩（`ImageLazyLoadTransformer` 的降级路径还会主动造出这类 URL）｜
OCR 无时间上界 ｜ BMP 能识别不能发 ｜ 备份诊断字段缩水 ｜ S3/WebDAV 恢复无确认对话框。

---

## 6. 技术约束 / 惯例（必须遵守）

- **本机无 Android 编译器**：不跑 gradle，编译/单测结论只从 CI 拿。
- **CI 判定铁律**：先 push 再 `gh workflow run nightly-build-debug.yml --repo DevLintMar/rikkahub --ref master`；
  `gh run watch <id> --exit-status`；`gh run view <id> --json conclusion,headSha` 核对 headSha；
  **再用 `--json jobs` 确认 `build` 真跑了**（"24h 无提交"会把 build 整个 skip，run 仍报 success）。
- 中文 conventional commit；字符串六 locale；工具 description/注入文本/JSON 用英文；`runCatching` 不包 suspend。
- 文件删除走 `~/.claude/scripts/trash.sh`（绝不 `rm`）；force-push 需用户明确要求。
- **密钥纪律**：不进日志、不进 release asset、不入库；本机副本 `D:\AndroidKeys\rikkahub\`。
- **CRLF 陷阱**：本仓 `CLAUDE.md`/`AGENTS.md` 是 CRLF，`.kt`/`.md`（docs）是 LF；用 Edit/Write 工具改 CRLF
  文件容易写坏（本次改用 python `newline=''` 显式处理，并逐文件校验"孤立 LF/CR = 0"）。

---

## 7. 停靠点

- **已完成**：A 类 11 项全部消化（8 项改代码 + 3 项改为保留上游）；C 类 17 项固化为清单 + 3 个脚本 + CLAUDE.md/AGENTS.md 入口；
  E 类过时结论就地更正（11 处 CI 结论 + 6 处事实错误 + 两份模块列表）。
- **待确认**：审计文档 §6 剩下的 5 条产品决策 ｜ §5.3 的结构性改动 ｜ §5.4 的设备核验（**最该先做**）。
- **下一阶段候选**：① 上设备过 §5.4 的四条；② 拍板 §6 剩下的 5 条；③ 挑 §5.3 里"零风险高收益"的两条
  （`matchingFallbacks` 与 keep 拆文件）。
- **恢复动作**：读本文档 §5，再读 `upstream-sync-checklist.md`。**开工前先让用户挑一批。**
