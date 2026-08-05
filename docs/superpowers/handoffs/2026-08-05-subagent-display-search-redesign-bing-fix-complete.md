# 交接文档：子代理展示修正 + 搜索工具重做（多选/service/num_results/多key）+ 全部变 Bing 根因修复 — 下一阶段入口

**日期**：2026-08-05
**目的**：上下文清理前的完整交接。新会话先读本文档 + SDD 台账（恢复地图），即可无缝续接。当前 master 已推送至 `81cd510`，工作树干净；最后 CI（`81cd510` 的 nightly-build-debug）绿。

---

## 0. 一句话概况

本阶段（自上一交接 `2db9205` 起）完成：**4 项新需求**（子代理标题/prompt 展示、拒绝/掐断报错只显示右侧、搜索工具重做）→ 走 brainstorming→spec→plan→subagent-driven 全流程执行（6 任务，`1308d17`..`a49792b`），外加用户后续提的**搜索多选体验修复**（添加默认非 Bing + 新增自动选中、图标 SearchRemove/Search01 默认黑色），并修复了**"所有搜索服务变成 Bing"的根因 bug**（decodeFromString reified 类型推断），`81cd510` 已推送。

---

## 1. 已完成工作

### A. 子代理展示修正 + 报错位置（`e49d37b`、`58d2ce2`）
- `SubAgentToolUI.title`：loading 期（content=null）默认"运行子代理"，仅 mode=background 才"运行子代理（后台）"（不再依赖 arguments.run_in_background）
- `SubAgentToolUI.Preview`：description 后显示 prompt（前后台一致，取自 arguments）
- `ChatMessageToolStep`：拒绝/掐断（approvalState=Denied）红色报错只显示在 extra（右侧），删除 content 块重复 + `hasExtraContent` 去掉 isDenied

### B. search 模块多 key 重试 helpers（`76f2f8c`）
- `splitApiKeys`（逗号/空格/换行拆分+trim+去重）、`withSingleKey`（复制成单 key options）、`retryOnQuota`（shuffled 逐个试，`isRetryable` 判定 quota/rate limit/429/402/insufficient/limit exceeded/401/403/unauthorized/invalid key/api key 时换 key，非此类立即返回，全失败返回最后错误）

### C. Settings 多选模型 + 全链路原子改动（`02c9036`，8 文件一 commit）
- `Settings.searchServiceSelected: Int` → `searchServiceSelectedIds: List<Uuid>`，DataStore 新 key `SEARCH_SELECTED_IDS`，读取迁移（旧 int index → 对应 service id，空/失效回退第一个）
- `selectedSearchServices` 扩展（多选有效服务，为空回退第一个）
- SearchPicker 多选网格（至少保留一个）、ChatInput/ChatPage 签名改 `(List<Uuid>) -> Unit`、移除"结果数量"、Web 路由 `/search/service` 改 `{serviceIds}`、web-ui 类型/多选同步（`f899634`）

### D. SearchTools 重做 + 测试段（`3e8f6bc`/`9671af4`/`1234819`）
- `search_web` 参数通用化 `{query(required), service(enum=多选 displayName), num_results}`；`scrape_web` `{url(required), service}`
- execute 解析 service 按 displayName、num_results→resultSize、走 retryOnQuota；`SettingSearchDetailPage` 测试段也走 retryOnQuota
- 修复轮：`9671af4`（用户裁定防御式 firstOrNull 回退）、`1234819`（补 kotlinx.serialization.json put/add import）

### E. 搜索多选体验修复（`d197237`、`1fe78d6`）
- `AddProviderDialog` 默认类型 `TYPES.keys.first()`(Bing) → 第一个非 Bing；新增服务 id 自动加入 `searchServiceSelectedIds`
- 搜索图标：开启 `HugeIcons.Search01` / 关闭 `HugeIcons.SearchRemove`，均 `tint = Color.Black`（默认黑色，不随 ToggleSurface 切主题色）

### F. **"所有搜索服务变成 Bing" 根因修复（`81cd510`）**
- **根因**：Task 4 把 `searchServices` 从 `Settings(...)` 构造参数（期望类型 `List<SearchServiceOptions>` 驱动多态解码）提成局部 val 后，`decodeFromString(it)` 被 `?: listOf(SearchServiceOptions.DEFAULT)`（DEFAULT=BingLocalOptions）推断成 `List<BingLocalOptions>`（具体类型）→ 每个元素按具体 Bing 解码、忽略 `type` 判别符（ignoreUnknownKeys 丢弃）→ 所有服务变 Bing
- **修复**：`val searchServices: List<SearchServiceOptions>` + `decodeFromString<List<SearchServiceOptions>>(it)` 显式多态类型
- **回滚**：e978b08（移除基类 apiKey）/c1a5c19（测试+workflow）/6e98f92（诊断日志）/b54e4b5（自定义序列化器）4 个基于错误"序列化模型"假设的提交，reset + **force-push** 移除
- 排查证据（诊断日志）：`stored=[{"type":"tavily",...}]` → `decodedTypes=[BingLocalOptions, BingLocalOptions]` —— 存储正确、解码错 → 定位到解码端类型推断

---

## 2. 当前 git 状态

- 分支：`master`，HEAD = `81cd510`，工作树干净
- 提交链：`2db9205`（上交接）→ `deb90e8`(spec) → `1308d17`(plan) → `e49d37b`..`a49792b`(6 任务 SDD + fix rounds) → `d197237`/`1fe78d6`(多选体验) → `81cd510`(Bing 根因修复)
- **注意强推**：远程 master 已被 force-push 改写（移除上述 4 个错误提交）；本地/远程一致在 `81cd510`
- CI：最后 run = `81cd510` 的 nightly-build-debug = success（headSha 核对）

---

## 3. 恢复地图

| 台账 | 路径 | 内容 |
|---|---|---|
| 设计文档 | `docs/superpowers/specs/2026-08-04-subagent-display-search-redesign-design.md` | 4 项需求设计 |
| 实现计划 | `docs/superpowers/plans/2026-08-04-subagent-display-search-redesign.md` | 6 任务全代码 |
| SDD 台账 | `.superpowers/sdd/2026-08-04-subagent-display-search-redesign/progress.md`（git-ignored） | 每任务 commit、fix rounds、deferred minors |
| 上一交接 | `docs/superpowers/handoffs/2026-08-04-tool-fixes-delete-buttons-conversation-tools-complete-next-phase.md` | 更早历史（含乱召回等挂起） |

---

## 4. 待确认 / 挂起项（按优先级）

### ① 设备核验 Bing 修复（最高优先，唯一需用户上手）
- 新增 Tavily（对话框选 Tavily）→ 应显示 Tavily、自动选中、搜索用它
- 原有服务（存储 JSON 一直是正确 `"type":"tavily"`）→ 现在能正确解码、恢复显示
- 图标：关闭 SearchRemove / 开启 Search01，均黑色
- 多 key：Tavily 配置填逗号分隔的多个 key，额度错自动换 key（当前用户有 8 个 tavily key，第 1 个冻结，会自动跳过重试）

### ② 历史最高优先：乱召回（语义搜索 bug）
- 需用户提供 rawTop logs。入口：`ConversationRepository.search` / `SemanticIndexManager.search`

### ③ Task 12（workspace_grep → 原生 ripgrep）+ 原生 ripgrep artifact 流水线
- 计划 `docs/superpowers/plans/2026-08-02-tool-call-ripgrep-task12-reserved.md`；交接 `docs/superpowers/handoffs/2026-08-02-native-ripgrep-artifact-pipeline.md`

### ④ 更新检查恢复点
- `UpdateChecker.kt` 的 `checkUpdate()` 有 `return@flow` 短路 + 注释，删除该行即恢复

### ⑤ 台账 deferred minors（约 30 项）
- 见 `.superpowers/sdd/2026-08-04-subagent-display-search-redesign/progress.md`（本阶段新增：SettingsRoutes 未 distinct、旧 SEARCH_SELECTED key 保留、associateBy displayName 去重、web-ui index 未用、spinner 全行等）

---

## 5. 技术约束 / 惯例（必须遵守）

- **本机无编译器**：不运行 gradle。静态编写 + review，编译验证全靠 CI。
- **CI 判定铁律**：`gh run list --workflow nightly-build-debug.yml --repo DevLintMar/rikkahub --limit 1 --json databaseId,headSha,status,conclusion` 权威判定 + **核对 headSha**；`gh run watch` 可能误报。
- CI 流程：先 `git push origin master` 再 `gh workflow run nightly-build-debug.yml --repo DevLintMar/rikkahub --ref master`。
- **CI 不跑单测**：`nightly-build-debug.yml` 只 `assembleDebug`（上次加 `:search:testDebugUnitTest` 无测试输出，慎用）；单测验证不可靠。
- **reified 类型推断陷阱（本阶段最大教训）**：`JsonInstant.decodeFromString(it)` 在**局部 val** 且带 `?: listOf(具体类型)` 时会被推断成具体类型，破坏 sealed 多态解码 → 必须显式 `decodeFromString<List<SearchServiceOptions>>(it)` 或给 val 加类型注解。详见记忆 `kotlin-reified-type-inference-sealed-decode`。
- **设备运行时诊断用应用内"请求日志"**（`Logging.log(tag, message)` 写入，`LogPage` 显示 TextLog），比 logcat 方便。
- 字符串双写 en+zh（ja/ko/ru/zh-rTW 有 key 时一并改）；删串先 grep 零引用；撇号转义 `\'`。
- 文件删除走 `~/.claude/scripts/trash.sh`（回收站），禁止 rm。
- 图标名（HugeIcons）以 CI 编译为准；本项目只用已验证图标（Search01/SearchRemove 已确认存在）。
- **force-push 需用户明确要求**（本阶段为回滚错误提交已强推一次）。

---

## 6. 关键架构决策（本阶段遗留给后续的认知）

- **搜索服务多选**：`Settings.searchServiceSelectedIds: List<Uuid>`，读取迁移自旧 `searchServiceSelected: Int`；`selectedSearchServices` 为空/失效回退第一个。至少保留一个（UI 禁取消最后一个 + 路由拒绝空 + 读取归一化三层防御）。
- **search_web 参数通用化**：`{query, service(enum=多选 displayName), num_results}`，不再嵌入单个服务的 `parameters()`；服务特有参数（如 Tavily topic）不再暴露给 AI，走各自默认。
- **多 key 轮询**：`retryOnQuota` = `splitApiKeys`（逗号分隔）→ `keys.shuffled()` 随机序逐个 → `withSingleKey` 传单 key → `isRetryable`（quota/限流/401/403 等）才换 key。服务内部 `KeyRoulette`（LRU）在单 key 下退场。
- **Bing 修复**：解码端必须显式多态类型；基类 `open val apiKey`（computed，不序列化）保持恢复后的状态（`1fe78d6` 之后未动）。
- **删除键/其它**：见更早交接（`2026-08-04-...complete-next-phase.md`）。

---

## 7. 参考文件索引

| 文件 | 用途 |
|---|---|
| `docs/superpowers/specs/2026-08-04-subagent-display-search-redesign-design.md` | 设计 |
| `docs/superpowers/plans/2026-08-04-subagent-display-search-redesign.md` | 计划 |
| `.superpowers/sdd/2026-08-04-subagent-display-search-redesign/progress.md` | 台账 + deferred minors |
| 关键文件 | `PreferencesStore.kt`（多选模型+迁移+**decode 显式类型**）/ `SearchService.kt`（retryOnQuota/splitApiKeys/withSingleKey/isRetryable）/ `SearchTools.kt`（service/num_results）/ `SearchPicker.kt`（图标+多选）/ `SettingSearchPage.kt`（添加默认非 Bing+自动选中）/ `ChatMessageTools.kt`（报错位置）/ `BuiltinToolUIs.kt`（SubAgentToolUI） |

---

## 8. 下一阶段建议

1. **设备核验**（§4-①）：Bing 修复是否生效（新增 Tavily / 原有服务 / 图标 / 多 key 重试）。
2. 若 Bing 修复验证通过，可消化台账 deferred minors（§4-⑤）。
3. 历史挂起（§4-②③④）：乱召回（需 logs）、Task 12 ripgrep、更新检查恢复点。

---

## 9. 停靠点

- **已完成**：4 项新需求（子代理展示/报错位置/搜索工具重做）+ 搜索多选体验修复 + Bing 根因修复（`81cd510`），master 工作树干净，CI 绿。
- **待确认**：设备核验 Bing 修复是否生效（§4-①）。
- **恢复动作**：读本文档 §3 台账 + §4 待确认/挂起项，先让用户装包验证 Bing 修复。
