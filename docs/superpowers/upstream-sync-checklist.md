# 上游同步检查清单（rikkahub fork）

**什么时候用**：每次同步上游**前**预检、**后**逐项过一遍。本文只放**可机械执行**的检查。
事故复盘在 `audits/`（最新：`2026-09-12-upstream-merge-2689e753-audit.md`），流程与「无冲突但静默破坏」的
12 类分类在 memory `upstream-sync-procedure`。本文是那 12 类的**仓库内落地版**：每条都给出可复制的命令。

> 为什么需要这份清单：2026-09 那次同步的 33 个冲突文件花了不到一天解决，但**合并完成后**又陆续发现
> 一批 CI 抓不到的问题（丢持久化写入、漏传开关、自相矛盾的审批判断、被顶替的配置文件），
> 其中两条根因（第 11/12 类）是**带默认值的字段**与**被抽取的函数**——git 与编译器都不会报错。

---

## 0. 铁律

1. **pin 到上游最后一个 CI 绿提交**，永不直接合上游 HEAD。
   `gh run list --repo rikkahub/rikkahub --workflow "Daily Build" --limit 20` → 取最后一个 success 的 sha。
   反例：`288a034c`（「chore: 更新依赖」）删掉 `gradle/libs.versions.toml` 且无替代，而
   `build-logic/settings.gradle.kts` 仍 `from(files("../gradle/libs.versions.toml"))`、全仓 `libs.*` 引用 500+ 处
   → 那个提交**配置阶段就红**，且没有任何 check-run。
2. **起 `sync/upstream-<date>` 分支**，不直接合 `master`；机械解冲突与结构性改动分开提交。
3. **本机无 Android 编译器**：先 push，再
   `gh workflow run nightly-build-debug.yml --repo DevLintMar/rikkahub --ref <branch>` →
   `gh run watch <id> --exit-status` → `gh run view <id> --json conclusion,headSha` 核对 headSha。
4. **查 CI 必须看 jobs**：`gh run view <id> --json jobs`。`check` 步骤判"24h 内无提交"时会把整个 `build`
   skip 掉，**run 仍然报 success**（假绿）——「pre 从未验证过」这个误判就是这么来的。
5. **冲突数 ≠ 难度**：真正危险的是「无冲突但编译不过 / 编译过了但语义变了」。下面的清单按这个优先级排。
6. **子代理只读**：并行解冲突时禁止它们跑任何写操作 git（`add`/`commit`/`checkout`/`stash`/`merge`），
   否则踩 `.git/index.lock`；暂存由主会话串行做。它们的结论**必须逐条复核**（2026-09 那轮 4 条指控被推翻）。
7. **密钥纪律**：仓库是 public。签名密钥只从 repo secret 解码，**绝不打印到日志、绝不当 release asset、绝不入库**。

---

## 1. 合并前（预检，不写任何东西）

```bash
git rev-parse master                       # 记录回滚锚点
git status --short                         # 必须干净
git merge-base master <pin>                # 应等于上次同步的分叉点
git log -1 --oneline <pin>                 # 确认这条提交就是你 pin 的那条
bash docs/superpowers/scripts/sync_audit_lists.sh <分叉点> <pin> <fork-合并前master> /tmp/sync-audit
```

- [ ] 上一节第 1 条的 pin 校验通过（有 CI 绿记录）
- [ ] 清单生成：上游改动 N 文件 / fork 改动 M 文件 / **双方都改 X 文件**（X 就是冲突面）
- [ ] 确认上游**没有**删掉 `gradle/libs.versions.toml`（若删了：fork 必须自带一份，否则 Gradle 配置阶段即红）

---

## 2. 合并后：fork 身份标识（任何一条丢了，CI 立刻红）

这些是 fork 与上游**故意不同**的地方，上游改动会**无冲突地**把它们覆盖掉。

| 检查项 | 命令 | 期望 |
|---|---|---|
| 包名 | `grep -n 'applicationId' app/build.gradle.kts` | `xyz.lynsei.rikkahub`（上游是 `me.rerere.rikkahub`） |
| 三套签名配置 | `grep -n 'create("release")\|create("nightlyDebug")\|create("pre")' app/build.gradle.kts` | 三条都在，且都读 `local.properties` |
| ABI 收窄 | `grep -n 'abiFilters' app/build.gradle.kts` | 只有 `arm64-v8a` |
| 工作流 | `ls .github/workflows/` | 3 个 `nightly-*` + `close-blank-issues`（**`daily-build.yml` 已删**） |
| 密钥来源（**签名漂移回归检测**） | `grep -n 'keytool -genkey' .github/workflows/nightly-build*.yml`（必须为空）<br>`grep -n 'keystore' .github/workflows/nightly-build*.yml`（只允许三种：从 secret `base64 -d` 解出、`keytool -list` 打印指纹、生成的 `local.properties` 里 `*.storeFile=`） | ⚠️ 注意别误判：workflow 里的 `actions/cache@v4` 是 **Gradle 缓存**（正常）。2026-09 那次漂移的成因是 `keytool -genkey` 现场生成钥匙 + `actions/cache` 存 keystore —— 缓存 7 天不被访问即淘汰，而 build job 有"24h 内无提交则 skip"门槛，停更期过后重跑就静默换了一把新钥匙 |
| 单测门禁 | `grep -n 'testDebugUnitTest' .github/workflows/nightly-build-debug.yml` | 在（这是唯一的单测门禁） |

---

## 3. 合并后：静默破坏扫描（CI 抓不到的那一类）

> **动手前先判性质：这是回归还是上游有意的修复？**
> `git log --oneline -S '<被删/被改的标识符>' <分叉点>..<pin> -- <file>` 找到那条提交，再看
> ① 标题是不是 `fix:`/有没有 close issue、② 有没有**配套单测**（`git show --stat --format='' <sha>`）。
> 任一成立 = 上游有意的行为，**别"回退到 fork 行为"**；三者皆无（纯重构顺带）才是该恢复的静默回归。
> 反例（2026-09 那轮真实踩过）：曾经把 `<think>` 行内标签与输入框 IME 形态当回归恢复，前者被 CI 的
> `ThinkTagTransformerTest` 当场顶回、后者回头查到 `f86d6e82`「fix: 键盘弹出时 ChatInput 保持圆角和底部间距」。

### 3.1 第 11 类：data class 加字段 + 字段有默认值 ⇒ 平行代码路径静默漏传

**实例（2026-09）**：`WorkspaceShellContext.shellCompatibilityMode` 只传给了同步路径，
fork 独有的**流式**路径（AI 的 `workspace_shell` 走这条）落在默认值 `false` 上 → 需要
`PROOT_NO_SECCOMP` 的设备上"手敲同样命令能跑、AI 跑不动"，且不报错。

```bash
grep -rn --include='*.kt' 'WorkspaceShellContext(' .        # 构造点：应为 2 处 + 1 处 data class 声明
grep -rn --include='*.kt' 'shellCompatibilityMode = ' .     # 传参处：每个构造点都必须有一条
```
> 现状：已修（`WorkspaceRepository` 的流式路径与导入路径都传了）。**根治方案**（未做，见 §4 第 9 条）：
> 把两个构造点合并成一个私有 `buildContext(...)`，或**去掉该字段的默认值**逼编译器报错 ——
> 默认值正是这个 bug 的成因。

### 3.2 第 12 类：采纳上游抽出的写入函数 ⇒ fork 自有键漏搬

**实例（2026-09）**：上游把内联 `dataStore.edit{}` 抽成 `persistSettings(dataStore, settings)`，
解冲突时只搬了 `SEARCH_SELECTED_IDS`，漏掉 `SUB_AGENT_MODEL` / `EMBEDDER` / `KEEP_ALIVE_ENABLED`
→ 三项设置**改完重启即回退、备份恢复也不落地**。

```bash
python docs/superpowers/scripts/prefs_key_audit.py <分叉点之前fork的master>
```
判据：**「读取 − 写入」必须是空集**（脚本内置一条白名单：`SEARCH_SELECTED` 是只读的旧版迁移源）。
脚本同时打印「合并前有写入、现在没有写入的键」= 迁移中丢掉的写入。

### 3.3 第 9 类：fork 刻意改掉的行为被上游覆盖（设计分歧，不是冲突）

| 锚点 | 期望 | 命令 |
|---|---|---|
| 更新检查被禁用 | `return@flow` 还在 | `grep -n 'return@flow' app/src/main/java/me/rerere/rikkahub/utils/UpdateChecker.kt` |
| `cur_time` 占位符恢复 | 还在 | `grep -rn 'cur_time' app/src/main/java/me/rerere/rikkahub/data/ai/transformers/PlaceholderTransformer.kt` |
| 高亮引擎 | Prism+QuickJS（**禁止复活上游纯 Kotlin 引擎**） | `git ls-files highlight/ \| wc -l` 必须仍是 **8**（`res/raw/prism.js` + `Highlighter.kt`/`HighlightText.kt`；上游是 **143** 个纯 Kotlin 文件） |
| 连字修复 | `fontFeatureSettings = "'calt' 0, 'liga' 0, 'clig' 0"` | `grep -rn 'clig' highlight/` |
| 工具信封容器 | **非懒加载 `Column`**（`ToolDetailSheet` 内容区是 `verticalScroll`，同轴嵌 LazyColumn 会拿无限高约束并抛 IllegalStateException，点开即崩） | `grep -n 'LazyColumn(' app/src/main/java/me/rerere/rikkahub/ui/components/message/tools/BuiltinToolUIs.kt` **必须为空**（`LazyRow(` 是横向、不同轴，允许；文件里提到 `LazyColumn` 的注释是防崩说明，不是调用） |
| 行内 `<think>` 不抽取 | 只认正文开头的 `<think>`（上游 `85402745` + `ThinkTagTransformerTest` 7 个断言）；**别改回 `<think>([\s\S]*?)(</think>|$)` 宽松匹配**，那会挂测试并吞掉字面标签 | `grep -n 'THINKING_REGEX =' app/src/main/java/me/rerere/rikkahub/data/ai/transformers/ThinkTagTransformer.kt` → 必须以 `\A\s*<think>` 开头 |
| 输入框 IME 形态 | 键盘弹出时**保持圆角与 8dp 底间距**（上游 `f86d6e82`）；别加回 `isImeVisible` → 直角 + 0 间距 | `grep -n 'isImeVisible' app/src/main/java/me/rerere/rikkahub/ui/components/ai/ChatInput.kt` 应为空 |
| 审批判断单一口径 | 谓词只用 `tool.isPending`（`Tool.isPending = !isExecuted && approvalState is Pending`） | `grep -rn 'val isPending = tool\.approvalState' app/src/main` 应为空；`when (tool.approvalState)` 的**分支**匹配（如 `GenerationLoop.kt:191`）是合法的，别误删 |

### 3.4 第 8 类：上游删掉 fork 未改过的文件（连带 config）

```bash
for f in .editorconfig AGENTS.md CLAUDE.md; do echo "$f -> $(git ls-tree HEAD -- $f | awk '{print $3}')"; done
git ls-tree HEAD -d .claude/skills | head     # .claude/skills 必须是实体目录，不是 120000 符号链接
```
**别按名字猜**：2026-09 那轮 `.editorconfig` 彻底消失、`AGENTS.md` 被整体顶替，而
`CLAUDE.md`/`.claude/skills` 反而被解冲突挡回了。逐个比 blob 才准。
本机 `core.symlinks=false` → 上游的符号链接会退化成普通文本文件。

### 3.5 第 1/3 类：上游新增文件按上游 API 写

- 新文件引用 fork 上不存在的签名 → 只能等 `:app:compileDebugKotlin` 报错。
- 上游 rename 掉的东西，fork 专有文件可能还在用（`handleMessageChunk` → `StreamChunkHandler` 先例）。
- 机械兜底：未解析 import 扫描 + `libs.*` alias 全量比对 `gradle/libs.versions.toml` +
  `R.string.*` 全量比对 `res/values*/strings.xml`（最后这条抓到过真实的缺失串）。

```bash
git grep -oh 'R\.string\.[a-z_0-9]*' -- app/src/main | sort -u > /tmp/used.txt   # 再与 values/strings.xml 的 name 集合求差
```

---

## 4. 「下次同步必踩」清单（按上游文件/机制组织）

| # | 项 | 为什么必踩 | 命令 / 期望 | 现状 |
|---|---|---|---|---|
| 1 | `gradle/libs.versions.toml` | 上游 HEAD 删过它且无替代 → 配置阶段红 | 存在，且 `ratex`/`haze-blur-materials` 在 | ✅ 上次自动合并成功 |
| 2 | `AppDatabaseFactory.kt` 迁移注册 | 上游版只到 `Migration_15_16`（已踩：已有装机开库即崩） | `grep -n 'Migration_' app/src/main/java/me/rerere/rikkahub/data/db/AppDatabaseFactory.kt` 应含 `25_26`/`26_27` | ✅ 已注册 |
| 3 | `app/schemas/<v>.json` 同名不同构 | 双方各自从 24 加了不同东西（fork `message_embeddings` / 上游 `workspaces.shell_compatibility_mode`） | 三向 `identityHash` 比对，取 ours | ✅ 取 ours |
| 4 | 迁移链对外来库的兼容 | fork 支持恢复**别人的**备份；上游 v25 没有 `message_embeddings` → 升到 v27 会 `Migration didn't properly handle` | `grep -n 'CREATE TABLE IF NOT EXISTS' app/src/main/java/me/rerere/rikkahub/data/db/migrations/Migration_25_26.kt` | ✅ 已补（并给三条 ALTER 加了 `hasColumn` 探测） |
| 5 | `BuiltinToolUIs.SearchWebPreview` | 上游会带回 query 前缀行/参数药丸/日期行/**LazyColumn 容器** | 与 `git show <pre-merge>:<file>` 比对 | ✅ fork 定制版 |
| 6 | `huge-icons` / `haze` 钉版本 | 会被带回 1.4 / beta02，静默改变图标字形与输入框透明语义 | `grep -n 'huge-icons = \|haze = ' gradle/libs.versions.toml` → `1.3` / `2.0.0-alpha03` | ✅ 钉住 |
| 7 | `.claude/skills` 符号链接 + `CLAUDE.md` 被删 | 本机 `core.symlinks=false` → 退化成文本文件 | §3.4 的命令 | ✅ 已还原实体目录 |
| 8 | fork 的 keep 规则追加块贴在 `rikkahub.keep` **文件尾部** | 与上游的尾部编辑形成冲突块 → 人工取 ours 时**会吃掉上游的 keep 修复**（已吃掉一条 jlatexmath 规则） | `git diff <分叉点>..<pre-merge> -- app/src/main/keepRules/rikkahub.keep` | ⚠️ **待拆成独立文件**（AGP 会合并同源集所有 `*.keep`） |
| 9 | `WorkspaceShellContext` 双构造点 + 字段默认值 | 见 §3.1 | §3.1 的命令 | ⚠️ **待合并构造点** |
| 10 | `persistSettings` 类抽取函数 | 见 §3.2 | §3.2 的脚本 | ⚠️ **待补键集合单测** |
| 11 | 13 处手工 `create("pre")` | 上游每加一个库模块都会漏（已踩：`assemblePre` 自合并起必红） | `grep -rl 'create("pre")' --include='build.gradle.kts' . \| wc -l` → 13；**上游新增模块后这个数必须 +1** | ⚠️ **待改 `matchingFallbacks`** |
| 12 | `.gitignore:14` 的 `references` 过宽 | 裸模式匹配任意层级的 `references/` → 上游往 `.agents/skills/*/references/` 加文件会被**静默忽略**（`.agents/skills/gemini-interactions-api/references/` 就在被忽略） | `git check-ignore -v .agents/skills/*/references/x.md` | ⚠️ **待改 `/references/`** |
| 13 | `daily-build.yml` 已删 | 上游仍有该文件 → modify/delete 冲突 | 人工保留删除 | ⚠️ 每次都要人工 |
| 14 | `ChatMessageTools.kt` / `ChatMessageCot.kt` / `ChatMessage.kt` | 上游持续在同一函数加分支（ServerToolStep / ask_user / isPending），fork 同区域有聚合思考块交互 | 逐 hunk 人工解，**禁止整段 take-theirs** | ⚠️ |
| 15 | `richtext/Markdown.kt` | fork 相对上游改了 597 行（段落合并/缓存/INLINE_MATH），上游一碰就是巨型冲突 | — | ⚠️ |
| 16 | `highlight/**` | 同名 `Highlighter.kt`/`HighlightToken` 两侧语义不同 → 半套同步即重复声明 | — | ⚠️ |
| 17 | `search/` 的 `withSingleKey` 有 `else -> this` | 上游再加带 apiKey 的渠道会静默失去多 key 轮询（豆包先例） | 新增渠道后必须补分支；可改成 `error(...)` 由编译器兜底 | ⚠️ |
| 18 | 终端 bind mount 第三处硬编码 | `WorkspaceTerminalSession.kt` 自己拼挂载表，与 `FileFolders.ROOTFS_BIND_MOUNTS` 不同步 → 终端看不到 `/upload` | 三处表逐条比对 | ⚠️ |
| 19 | `values*/strings.xml` ×6 | 双向重改；fork 删过的串可能被上游新代码重新引用（`chat_message_tool_search_prefix` 先例） | §3.5 的 `R.string.*` 比对 | ⚠️ |
| 20 | `compose_compiler_config.conf` / `baselineProfiles` | 前者有两条悬空类 + 漏声明 `StreamChunk`；后者两份 md5 相同且过期 → ART 静默忽略，启动优化正好失效在生成循环上 | `grep -c 'StreamChunk' app/compose_compiler_config.conf app/src/release/generated/baselineProfiles/*.txt` | ⚠️ **待重新生成** |

---

## 5. CI 判定

```bash
git push -u origin <branch>                                        # 必须先 push
gh workflow run nightly-build-debug.yml --repo DevLintMar/rikkahub --ref <branch>
gh run watch <id> --exit-status                                    # 用 watch，不手写轮询
gh run view <id> --json conclusion,headSha                         # 核对 headSha 是本次提交
gh run view <id> --json jobs                                       # 确认 build 真跑了（不是被 skip 的假绿）
```

- `nightly-build-debug.yml` = 编译 + `:app:testDebugUnitTest`（唯一单测门禁）。
- **合并类错误常分两轮暴露**：先 `:app:compileDebugKotlin`，修完才轮到测试源码编译。
- **平时验证只跑 `nightly-build-debug.yml` 这一个**（用户 2026-09-12 明确要求）：它是唯一的单测门禁。release / pre 各有每日 cron（18:00 / 19:00 UTC）会自己跑，只有改动了**构建 / 混淆 / 变体**相关配置时才手动补跑它们。
- 发布产物的签名可用 `python docs/superpowers/scripts/apk_signer.py <apk>` 反查指纹；
  三把密钥的预期指纹见 memory `signing-key-drift`。**签名一变，所有已装用户必须卸载重装。**

## 6. 设备核验（**CI 全绿 ≠ 功能对**）

2026-09 那次 CI 全绿并合入 master 之后，用户才陆续发现三个 CI 根本发现不了的回归
（图标集字形、输入框透明、工具信封点开即崩）。同步后至少过一遍：

1. 聊天输入框：键盘弹出时**保持圆角与 8dp 底间距**（上游 `f86d6e82` 的有意行为，别当回归）；haze 层应仍是半透明。
2. 工具信封：点开不崩；流式执行的 shell 应有实时输出。
3. 生成循环：连续审批、生成中点审批、网络自动重试、消息队列、语音模式。
4. 备份/恢复：本地导入 + S3/WebDAV + **跨包名**（debug↔release↔pre）。
5. DB：从上一个正式版升级不崩（含 v25/v26 老库与外来的官方备份）。
6. 设置持久化：改语义搜索/保活/子代理模型 → 杀进程重进仍在。
7. 工作区：shell 兼容模式开关对 AI 的 `workspace_shell` 也生效；导出→导入后开关保留；
   终端多 Tab；`ls /upload` 与 AI 侧一致。

## 7. 可复用脚本

| 脚本 | 用途 |
|---|---|
| `scripts/prefs_key_audit.py` | Settings 键「声明/读取/写入」三集合比对（第 12 类） |
| `scripts/sync_audit_lists.sh` | 生成上游/fork/双方都改 的文件与提交清单 |
| `scripts/apk_signer.py` | 从任意 APK 抽 v2 签名者证书 SHA-256（核对签名漂移） |
| `scripts/baseline_profile_audit.py` | baselineProfiles 过期检测：两份文件是否逐字节相同 + 规则里指向已删类的条目（`--strict` 时过期即退出码 1） |

**本机还能做的白盒验证**：纯逻辑（正则、序列化、解析器）可以用本机 JDK 直接跑 —— Kotlin 的 `Regex`
就是 `java.util.regex`，`<think>` 那条改动就是这么对照的（对照结果最终判定它**不是**回归，见 §3 前言）。注意 PATH 上的 `javac` 是 JDK 17 而
`java` 可能是 JRE 8（`UnsupportedClassVersionError`），用 `"/c/Program Files/Java/jdk-17/bin/java"`；
源码别写中文注释（会按 GBK 解析报错），或加 `javac -encoding UTF-8`。

**其余静态检查**（比空等 CI 强）：未解析 import 扫描、`libs.*` alias 全量比对、
`R.string.*` 全量比对、依赖版本/图标名交叉核对、冲突标记全树扫描、括号配平、重复 import 扫描。
涉及 Compose 测量/滚动/布局约束的判断**先查 Context7 官方文档**再下结论。
