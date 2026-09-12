# 交接文档：签名密钥固定 + 上游合并审计（11 子代理）— 下一阶段入口

**日期**：2026-09-12（当日第二份；上一份为 `2026-09-12-post-sync-regression-fixes-next-phase.md`）
**状态**：master HEAD = `61c0b26b`，工作树干净（只剩 `docs/superpowers/audits/` 未跟踪 → 本提交一并入库）
**目的**：compact 前的完整交接。新会话读本文档即可续接。

> **本阶段有两件事**：① 把三条 nightly 的签名密钥**固定死**（+ tag/发布语义整理）；② 对本次上游合并做**横切审计**（11 个子代理 + 主会话逐条复验），产出 `docs/superpowers/audits/2026-09-12-upstream-merge-2689e753-audit.md`（338 行）。
> 上游同步本身的细节仍见 `2026-09-11-upstream-sync-2689e753-next-phase.md`；同步后三个设备回归见 `2026-09-12-post-sync-regression-fixes-next-phase.md`。

---

## 0. 一句话概况

搜索信封样式回退到同步前 → 三把签名密钥从 `actions/cache` 里取出、固定进 repo secrets → 补 `pre` 变体修好 `assemblePre` → nightly 转正式 Release、tag 跟随构建提交、删掉 daily-build（全部 CI 验证通过）；随后对整次合并做了横切审计，**捞出一批合并静默破坏（含一处配置持久化丢失、一处 shell 开关漏接、一处同文件自相矛盾）**，并产出一份带证据等级的完整审计报告。

---

## 1. 已完成（commit 链）

```
61c0b26b chore(ci): nightly 改为正式 Release + tag 跟随构建提交；移除 daily-build   ← master HEAD
1a542d72 chore: .gitignore 忽略 keystore/jks/p12/app.key/google-services.json
5b4b9715 build: 给 videogen/oauth 补 pre 变体，修 assemblePre 依赖解析失败
c77fd1e7 chore(ci): 三个 nightly 的签名密钥固定为 repo secrets，不再现场生成
c85b28ad chore(ci): 加一次性工作流，把三条 nightly 的签名密钥从 actions/cache 取出
528afe63 fix(ui): 搜索网页信封样式完全回退到同步前版本
f5ff0dd7 docs: 交接文档——同步后三个回归修复（上一份交接，非本阶段）
```

### 1.1 搜索信封样式整体回退（`528afe63`）

上游 `a8f8c3a1` 重写了 `SearchWebPreview`（query 前缀行、参数药丸 FlowRow、每条结果的 `publishedDate` 日期行、外层 `padding(16.dp)`），而 fork 早在 `23f4127b` 就**有意删掉**那行前缀 → 解冲突时整段采纳上游。现整体还原为 `7042fa80` 的实现（函数签名也退回单参），**该文件与 `7042fa80` 逐字节一致，仅多 3 行"容器必须是非懒加载 Column"的防崩注释**。

### 1.2 三把签名密钥固定（`c85b28ad` → `c77fd1e7`）

- **问题**：release/debug/pre 三把密钥只由 CI 现场 `keytool -genkey` 生成、只存在 `actions/cache`；缓存 7 天不被访问即淘汰（build job 有"24h 内无提交则 skip"门槛 → 停更期间自然淘汰），下次构建静默换一把新钥匙 → **签名漂移**。2026-08-13 停更后于 **2026-09-06 漂移过一次**（三个缓存的 `created_at` 全是 09-06）。
- **旧私钥不可找回**（五条证据）：GitHub 无下载缓存内容的 API；从未入 git（`git rev-list --all --objects` 无 keystore blob）；从未上传为 artifact（历史上只有 `rikkahub-debug-apk`/`pre-apk`/`room-schemas`）；release 包只作为 tag `nightly` 的 asset 被反复覆盖；从旧 APK 只能取到证书（公钥）。
- **做法**：加一次性工作流用 `actions/cache/restore` 取出三把密钥 → `openssl` 加密成 artifact → 解密后设成 repo secrets → **删除临时口令 secret、删除加密 artifact、删除导出工作流、清掉本地临时文件**。
- **用的是 2026-09-06 那一代，指纹未变**（即不产生新漂移）：

| 用途 | 证书 SHA-256 | secret |
|---|---|---|
| release | `BB:CE:6D:E1:28:55:DC:AE:BF:A4:0D:FA:8D:A2:C8:57:DF:52:D9:F0:7F:41:E3:40:B3:98:51:9A:25:25:5A:3C` | `RELEASE_KEYSTORE_BASE64` |
| debug | `47:B7:DE:23:C3:63:2E:75:FE:80:95:57:02:37:D5:E7:79:57:35:2D:C3:FA:7F:31:4C:54:3C:A9:C1:DA:5C:CD` | `DEBUG_KEYSTORE_BASE64` |
| pre | `62:4C:43:DE:62:03:0F:C7:66:5D:17:C5:F2:73:FA:E2:F2:11:EB:D7:21:57:8F:D6:2B:DA:1A:54:3E:1E:AD:29` | `PRE_KEYSTORE_BASE64` |

- 三条工作流改为从 secret 解码 keystore，**去掉 `actions/cache` + `keytool -genkey` 两步**；secret 缺失直接 `exit 1`（旧行为是静默生成新钥匙，现在没有这个可能）；构建日志打印证书指纹便于核对。
- **本地备份**：`D:\AndroidKeys\rikkahub\`（三把 keystore + `README.txt` 记着指纹/口令/用法）。⚠️ **请异地再备份一份**。

### 1.3 `pre` 变体修复（`5b4b9715`）

上游合并带进来的 `:videogen` / `:oauth` 只声明 debug/release，而 app 的 `pre` build type 在解 `:app:preRuntimeClasspath` 时找不到匹配变体 → `assemblePre` 自同步起每次必红。补上其余 9 个模块都有的 `create("pre") { initWith(getByName("release")) }` 后，**`assemblePre` 于 `1a542d72` 首次全绿**。

### 1.4 发布语义整理（`61c0b26b`）

- **tag 跟随构建提交**：三条 nightly 发布前 `git tag -f <tag> && git push -f origin refs/tags/<tag>`。原先 tag 只在首次创建时定下（`nightly`=07-12 / `nightly-debug`=07-13 / `nightly-pre`=07-15），release 页面显示的提交与实际代码对不上。
- **nightly 转正式 Release**：`prerelease: false` + `make_latest: 'true'`（成为仓库 Latest）；debug/pre 仍是 prerelease 并显式 `make_latest: 'false'`。
- **删除 `daily-build.yml`**（与 nightly-build 完全重复：同样 `assembleRelease`、同样发 tag `nightly`、互相覆盖；且因 `GOOGLE_SERVICES_JSON` secret 为空长期在 `processReleaseGoogleServices` 必挂），其专用的 `KEY_BASE64`/`SIGNING_CONFIG`/`GOOGLE_SERVICES_JSON` 三个 secret 一并删除。

### 1.5 CI 验证（全绿）

| run | 工作流 | 结果 | 核对项 |
|---|---|---|---|
| `34691671248` | debug | ✅ | 信封回退（`528afe63`） |
| `34691995840` / `34691997234` / `34691998821` | debug / pre / release | ✅✅✅ | 从 secret 解码密钥（`1a542d72`），日志指纹与上表逐位一致 |
| `34692710353` / `34692714273` / `34692712218` | debug / pre / release | ✅✅✅ | tag 跟随 + Release 标记（`61c0b26b`） |

发布态：三个 tag **全部指向 `61c0b26b`**；`nightly` `prerelease=false`、debug/pre `prerelease=true`。另用自写脚本（`D:\Temp\apk_signer.py`）从**已发布 APK** 里解出签名者证书，与上表逐位吻合（release 那把与改动前发布的包也一致 → 确认没有引入新漂移）。

### 1.6 上游合并审计（本阶段主要产出）

11 个只读子代理按模块并行（ai/oauth、生成循环、工具信封、workspace、数据层、备份、记忆/搜索/MCP、构建/CI、设置、聊天界面、跨文件横切），主会话逐条复验关键指控。

**产物**：`docs/superpowers/audits/2026-09-12-upstream-merge-2689e753-audit.md`（338 行）—— 结构：§0 摘要 / §1 上游带来的新特性 / §2 已确认缺陷（2-1…2-13）/ §3 下次同步必踩清单 / §4 更正既有结论 / §5 设备核验清单 / §6 待决策 / 附录（共用清单与脚本）。
**每条都标证据等级**：✅ 主会话已复验 ｜ ⚠️ 代理结论未复验 ｜ ❌ 已推翻。**结论汇总见该文档；下面的 §5 只列需要动手的部分。**

共用证据清单（可复用）：

```
D:\Temp\rikkahub-audit\{upstream_commits,fork_commits,post_merge_commits,upstream_files,fork_files,both_touched_files}.txt
D:\Temp\rikkahub-audit\both_touched_numstat.tsv      68 个双方都改过的文件逐文件增删行数
D:\Temp\prefs_key_audit.py                           Settings 键 声明/读取/写入 三集合严格比对
D:\Temp\apk_signer.py                                从任意 APK 抽 v2 签名者证书 SHA-256
```

---

## 2. 关键决策与认知（本阶段新增）

### 2.1 "CI 全绿"之外，还有一类"连冲突都没有"的破坏

审计新识别出**两类**比"无冲突但编译不过"更隐蔽的破坏，已写进 memory `upstream-sync-procedure`：

- **第 11 类**：上游给 data class 加字段 + 字段带默认值 ⇒ **fork 的平行代码路径静默漏传**。例：`WorkspaceShellContext` 有两个构造点（`WorkspaceManager.kt` 的同步路径与 fork 独有的流式路径），`shellCompatibilityMode` 只传给了同步路径 → AI 的 `workspace_shell` 拿不到开关，**不报错、CI 全绿**。机械检查：`grep -c 'WorkspaceShellContext('` 全仓应恒为 1。
- **第 12 类**：采纳上游抽出的写入函数时 **fork 自有键漏搬**。例：上游把内联 `dataStore.edit{}` 抽成 `persistSettings(dataStore, settings)`，fork 的三个自有键（`SUB_AGENT_MODEL`/`EMBEDDER`/`KEEP_ALIVE_ENABLED`）**只读不写**。机械检查：脚本求 声明/读取/写入 三集合，`读取 − 写入` 应恒为空集。

### 2.2 签名密钥的处置原则（供后人遵守）

- 三条 nightly 的密钥**只从 repo secret 解码**，任何人再往工作流里加 `keytool -genkey` 或 `actions/cache` 存 keystore 都是在重新引入漂移。
- 仓库是 **public**：密钥要么只进 secret，要么加密后当短 retention artifact（用完立刻删），**绝不打印到日志、绝不当 release asset**。
- 换签名 = 所有已装用户必须卸载重装（`INSTALL_FAILED_UPDATE_INCOMPATIBLE`）。

### 2.3 审计方法（可复用）

- `git diff 4b6449e3..2689e753` = 纯上游改动；`4b6449e3..7042fa80` = 纯 fork 改动；`7042fa80..HEAD` 含上游提交，看 fork 自身要加 `--first-parent`。
- 判"是否被静默覆盖"：`git ls-tree/rev-parse <ref>:<path>` 比 blob，或三方 diff（`7042fa80` / `2689e753` / HEAD）。
- 子代理必须**只读**（禁 git 写操作、禁 gradle），每条结论带 `文件:行号` + 引入 sha；主会话逐条复验 —— 本轮它们报的 4 条被推翻（WorkManager 未初始化 / Google ImageGeneration else / hasSummary 死代码 / S3WebDav "丢失"），也抓到了 3 条真实且此前无人报的（`.editorconfig` 被删、`AGENTS.md` 被顶替、`liveOutput` 无 UI 消费者）。

---

## 3. git / CI 状态

- 分支 `master`，HEAD = `61c0b26b`；三条 nightly 在本提交上全绿，tag 全跟随。
- 回滚锚点：合并前 `7042fa80`；同步合并 `cab3a656`；同步后回归修复完 `895a1f7f`。
- 本机 `git status` 在本提交后应干净（含新增的审计文档）。

---

## 4. 恢复地图

| 文档 | 说明 |
|---|---|
| **`docs/superpowers/audits/2026-09-12-upstream-merge-2689e753-audit.md`** | **本阶段主产物**：审计报告（新特性 / 已确认缺陷 / 下次同步必踩 / 更正 / 设备核验 / 待决策） |
| `2026-09-12-post-sync-regression-fixes-next-phase.md` | 同步后三个设备回归（图标 / haze / 信封崩溃） |
| `2026-09-11-upstream-sync-2689e753-next-phase.md` | 上游同步主体（157 提交 / 33 冲突 / DB v27 / 生成循环重构 / 5 个静默破绽） |
| `2026-09-11-file-url-sandbox-tool-image-timeout-next-phase.md` | 再上一阶段（file:// 沙箱语义 + 工具结果图片通道 + 下载超时） |

| 本阶段改动文件 | 说明 |
|---|---|
| `.github/workflows/nightly-build{,-debug,-pre}.yml` | 从 secret 解码密钥；发布前 tag 跟随；nightly 转 Release |
| `.github/workflows/daily-build.yml` | **已删除** |
| `app/.../message/tools/BuiltinToolUIs.kt` | 搜索信封整体回退（+ 3 行防崩注释） |
| `videogen/build.gradle.kts`、`oauth/build.gradle.kts` | 补 `create("pre")` |
| `.gitignore` | 忽略 `*.keystore`/`*.jks`/`*.p12`/`app.key`/`google-services.json` |
| `docs/superpowers/audits/2026-09-12-upstream-merge-2689e753-audit.md` | 新增审计报告 |

| 相关 memory | 说明 |
|---|---|
| `signing-key-drift` | 密钥漂移机制 + 三把指纹 + 已固定 + 本地备份位置 |
| `upstream-sync-procedure` | 同步流程 + **12 类**静默破坏（第 11/12 类本阶段新加） |
| `memory-system-handoff-chain` | 链入口（已指向本阶段两份文档） |
| `tool-detail-sheet-no-vertical-lazy` | 信封容器必须非懒加载 |
| `huge-icons-pinned-1-3` / `haze-pinned-alpha03` | 两个刻意钉住的依赖 |

---

## 5. 待办（唯一需要用户决策的部分）

### 5.1 立即可修的合并回归 —— ✅ 已全部修完（2026-09-12 同日）

见交接文档 `2026-09-12-a-class-regressions-and-sync-rules-next-phase.md`（A 类 11 项）。

| 状态 | 问题 | 落点 |
|---|---|---|
| ✅ | `persistSettings` 漏写 `SUB_AGENT_MODEL`/`EMBEDDER`/`KEEP_ALIVE_ENABLED` | `fix(settings): persistSettings 补写三个 fork 键` |
| ✅ | `shellCompatibilityMode` 没接流式 shell（AI 的 `workspace_shell` 拿不到开关） | `fix(workspace): Shell 兼容模式接进流式路径与工作区备份` |
| ✅ | 工作区导出/导入丢 Shell 兼容模式（A10） | 同上 |
| ✅ | `isPending` 同文件自相矛盾（死按钮） | `fix(ui): 恢复合并前被静默改掉的三处界面/解析行为` |
| ❌ 误判 | `<think>` 正则（行内思考不抽取）与输入框 IME 形态（A4）**都是上游有意的 `fix:` 提交**（`85402745` + 7 个单测 / `f86d6e82`）→ **保留上游行为**，不算回归 | 仅加说明注释 |
| ✅ | `.editorconfig` 被删 / `AGENTS.md` 被顶替（A7/A8） | `chore(docs): 取回 .editorconfig、补回 AGENTS.md…` |
| ✅ | 外来 v25 备份升 v27 开库失败（A11） | `fix(db): Migration_25_26 补建 message_embeddings…` |
| ✅ 决策 | 合成消息不再过消息模版（A6）：**保留上游行为** —— 理由见 §6 技术约束最后一条 | 无代码改动 |
| ✅ 结论 | `liveOutput` 无 UI 消费者（A9）：按「有意不做」处理 | 本文档 §7 与旧交接已就地更正 |

### 5.2 待用户拍板的产品类（审计文档 §6 共 9 条）

> 其中第 1 条（要不要现在修）已由本轮消化；`§6-1` 之外的 8 条**仍未动**。
### 5.2 待用户拍板的产品类（审计文档 §6 共 9 条）

`enableWebSearch` 对带内置搜索的模型失效（是否要"总是外挂"）｜`SearchMode.BUILT_IN` 死值如何处理｜赞助商 provider（APIMart/MaruCode）是否移除｜`.gitignore` 的 `references` 改锚定｜keep 规则是否拆成独立文件｜`pre` 是否改用 `matchingFallbacks`｜`.editorconfig` 是否取回｜`AGENTS.md` 用哪一版｜`liveOutput` 的 UI 端是"有意不做"还是遗漏（涉及交接文档 §5-①-5 能否通过）。

### 5.3 结构性防复发（建议随下次同步一起做）

把 `WorkspaceShellContext` 两个构造点合并（第 11 类）｜补 `persistSettings` 的键集合往返单测（第 12 类）｜fork 的 keep 追加块拆成 `fork.keep`｜`pre` 用 `matchingFallbacks`｜`compose_compiler_config.conf` 清两条悬空 + 声明 `StreamChunk`｜重新生成 baselineProfiles（现在两份 md5 相同且过期）｜补 5 个未翻译串｜`androidTest` 纳入 CI（现在 8 个测试形同死代码）。

### 5.4 历史挂起（延续，非本次引入）

`workspaces/` 不在整机备份｜`file://` 链接点击崩（+ `ImageLazyLoadTransformer` 降级路径会造出这类 URL）｜OCR 无时间上界｜BMP 能识别不能发｜备份诊断字段缩水｜S3/WebDAV 恢复无确认对话框｜`OcrTransformer` 超时/取消。

---

## 6. 技术约束 / 惯例（必须遵守）

- **本机无 Android 编译器**：不跑 gradle，编译/单测结论只从 CI 拿。
- **CI 判定铁律**：先 push 再 `gh workflow run nightly-build-debug.yml --repo DevLintMar/rikkahub --ref master`；`gh run watch <id> --exit-status` 监控；`gh run view <id> --json conclusion,headSha` 核对 headSha；**结果无论红绿主动汇报**。
- **查 CI 用 `--json jobs`**：`check` 判定"24h 无提交"会把 `build` 整个 skip，此时 run 仍报 success（假绿 —— "pre 从未验证"这个说法纠缠了一整轮的根源）。
- 中文 conventional commit；字符串六 locale；工具 description/注入文本/JSON 用英文；`runCatching` 不包 suspend。
- 文件删除走 `~/.claude/scripts/trash.sh`（绝不 `rm`）；force-push 需用户明确要求。
- **密钥纪律**：不进日志、不进 release asset、不入库；本机副本放仓库外。
- 密钥/`.claude`/`CLAUDE.md` 这类"本机环境相关"的坑，同步后必须复核（`core.symlinks=false`）。

---

- **合成消息（`isSynthetic`）不过消息模版是有意为之，别「修」回去**（2026-09-12 决策）：
  上游 `942d0d28` close 了 #1790 —— 时间提醒每轮重建、`createdAt` 变，模版里的 `{{time}}`/`{{date}}`
  渲染结果跟着变，而它在 `messages[0]`，会让整段 Anthropic prompt cache 前缀**逐轮失效**（每轮全量重算）。
  代价是自定义模版不再包裹 system prompt / 注入段。

## 7. 停靠点

- **已完成**：① 搜索信封回退；② 三把签名密钥固定（+ 本地备份）；③ `pre` 变体修复；④ 发布语义整理（正式 Release / tag 跟随 / 删 daily-build）；⑤ 三轮 CI 全绿并核对指纹与 tag；⑥ **上游合并审计完成并落盘**（11 子代理 + 逐条复验）。
- **待确认**：审计文档 §6 的 9 条产品决策 + §5.1 的五条回归修复 —— **全部等用户发话，本阶段没有擅自动手**。
- **设备核验（沿用前两份交接，仍未做）**：信封不崩 / 输入框半透明 / 图标旧字形；生成循环（含连续审批、生成中点审批）；备份恢复（S3 + WebDAV + 跨包名）；DB 从 3.1.1 升级；记忆 7 工具。
- **下一阶段候选**：按 §5.1 修回归 → 按 §5.2 决策产品项 → §5.3 结构性防复发。
- **恢复动作**：读本文档 §4/§5，再读审计报告 §0 摘要与 §6 决策清单；开始前先让用户挑一批动手。
