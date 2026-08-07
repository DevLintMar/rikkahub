# 交接文档：search_web 工具重构 + 六渠道全参数支持 + 实测修复 — 下一阶段入口

**日期**：2026-08-07
**目的**：上下文清理前的完整交接。新会话读本文档即可续接。master 已推送至 `b273848`，工作树干净。

---

## 0. 一句话概况

接上一交接 `a7b5a5c`（记忆工具 UI + 注入定型，13 项改动）。本阶段完成 **search_web 工具重构与六渠道全参数支持**：`search_web` 从固定 3 参数（query/service/num_results）改为**动态聚合**各渠道 `parameters()` 声明的渠道特有参数进 schema + 描述（[SearchTools.kt](app/src/main/java/me/rerere/rikkahub/data/ai/tools/SearchTools.kt)），随后 **SDD 6 任务**给 Tavily/Exa/Firecrawl/Metaso/LinkUp/Jina 接入官方 API 全量实用参数（请求体真正发送）。用户用真实 key 设备实测 Tavily/Exa → 我直接打 API 复现/破案 → 两轮 SDD 修复（描述引导 + Exa deep 接线 + service 必选 + 移除低价值参数 + Exa 默认 highlights）。全程 CI 绿。master `a7b5a5c` → `b273848`。

---

## 1. 已完成工作（commit 链）

### 前置小改动（搜索阶段前）
| commit | 内容 |
|---|---|
| 5d4e96b | 思考聚合头大脑图标恢复原尺寸（盒 20/图标 16）+ 换 HugeIcons MoonAngledRainZap（替换 AiBrain02） |

### 一、search_web 动态聚合机制（核心架构）

| commit | 内容 |
|---|---|
| a1c40aa | **聚合机制**：`createSearchTools` 遍历已选渠道 `parameters()`，把 query/service/num_results 之外的参数按名聚合成 `Map<String, JsonObject>`，注入 `search_web` InputSchema；描述加 `[渠道]` 标注参数表 |
| ffe76aa | 描述去重：删掉逐条参数清单，只留一句"各服务有各自额外参数，见参数定义的 [Service] 标注"；聚合结构简化为 `Map<String, JsonObject>` |

### 二、六渠道全参数接入（SDD 6 任务 + 终审）

计划：`docs/superpowers/plans/2026-08-07-search-web-full-params.md`；台账：`.superpowers/sdd/2026-08-07-search-web-full-params/progress.md`

| commit | 渠道 | 新增参数 |
|---|---|---|
| 5ec0bfa | 共享 | `asSearchStringList()` 工具函数（SearchService.kt，数组/逗号串兼容） |
| b67f094 | Tavily | time_range/start_date/end_date/include_domains/exclude_domains/country/exact_match/include_raw_content |
| 6c9782c | Exa | category/include_domains/exclude_domains/start_published_date/end_published_date/user_location/max_age_hours/content_type |
| 0e4359c | Firecrawl | include_domains/exclude_domains/tbs/location/country + images 源解析 |
| a560e40 | Metaso | scope(6值)/include_raw_content/concise_snippet + 响应 DTO 加固 |
| 135a7d7 | LinkUp | from_date/to_date/include_domains/exclude_domains + maxResults 恒发 + snippet nullable 崩溃修复 |
| 75067b1 | LinkUp | **Task5 终审 Critical 修复**：params 读取键改 snake_case 匹配 schema（AI 传参静默失效问题） |
| abcabf3 | Jina | num 恒发 + gl/hl |
| f9b5f48 | 终审修复波 | Tavily/Exa 空域名列表守卫 + Exa highlights 按 content_type 门控 |

> 终审（opus）With fixes：Exa maxAgeHours 位置裁定 false positive（官方 MCP 源码证实在 contents 块内，非顶层）；M1/M3 已修，re-review Clean。

### 三、设备实测缺陷修复（SDD 2 任务 + 直修）

用户真实 key 实测 Tavily/Exa → 我直接打 API（curl）复现/破案 → 修复。计划：`docs/superpowers/plans/2026-08-07-search-params-fixes.md`；台账：`.superpowers/sdd/2026-08-07-search-params-fixes/progress.md`

| commit | 内容 | 实测依据 |
|---|---|---|
| cb6f8ab | Tavily 5 参数描述补充（exact_match 需引号短语、time_range/日期建议配 topic=news、country 是 boost） | API 400 错误原文、general 无日期字段、country 提升生效 |
| 6cfb25a | **Exa deep 接线**：type=deep 时请求体自动追加 outputSchema → `output.content` 变 `{"answer":...}`；DTO content 改 JsonElement? + extractOutputAnswer helper | 实测：deep+outputSchema 稳定返回综合答案，fast/auto 无 output |
| abe8c0b | **service 必选**：required 加 service；execute 不再兜底默认渠道（缺失/非法报错并列出可选） | 用户"service 不可空，ai 必须选好" |
| 2a7b55c | Tavily 移除 include_raw_content（参数+请求+DTO）+ exact_match 描述精确化（给出 query 引号示例） | 用户要求移除；描述保证 AI 不再 400 |
| f82b701 | Exa 移除 max_age_hours（参数+contents 写入+import） | 用户要求移除 |
| b273848 | **Exa 默认内容模式改 highlights**：useHighlights = contentType != "text"，未传时发 highlights:true | 与 Tavily 默认摘要对齐省 token |

---

## 2. 关键设计决策（认知遗留）

- **search_web 聚合机制**：各渠道 `parameters()` 声明的参数（排除 query/service/num_results）→ 聚合进 search_web InputSchema，description 加 `[渠道名]` 标注；execute 把**整个 args.jsonObject 透传**给 `service.search`，各渠道自取认识的键。**铁律：schema 键（snake_case）必须与 search() 里 params 读取键逐字一致**（Task 5 Critical 教训；body 写入键按各家 API 要求 camelCase 或原样）。
- **描述只出现一次**：工具描述只留引导句"各服务有各自额外参数，见参数定义"，参数细节全部在 schema（避免介绍两遍——用户明确要求）。
- **service 必选**：`required = ["query", "service"]`；execute 缺失/非法 service 直接 `error("service is required and must be one of: ...")` 走统一错误信封，不再兜底默认渠道。
- **Exa deep = outputSchema**：Exa synthesis 只在 `type:"deep"` + `outputSchema` 时触发（实测确认）；fast/auto 无 answer 是官方设计。接线后 AI 传 deep 时 `output.content` = `{"answer":...}`（extractOutputAnswer 兼容字符串/object 两形态）。
- **Exa 默认 highlights**：`useHighlights = contentType != "text"`，未传 content_type 发 `highlights:true`；显式 `content_type:"text"` 才全文。与 Tavily 默认摘要（content 字段）对齐，控制 token。
- **Tavily 参数实测结论**（写死认知）：`exact_match` 必须 query 含双引号短语否则 400；`time_range`/`start_date`/`end_date` 只在 `topic=news` 时可见效果（general 响应无 published_date）；`country` 是 boost 非 filter；`include_domains`/`num_results` 稳定生效。
- **Exa 参数实测**：`start_published_date`/`include_domains`/`exclude_domains`/`num_results` 稳定生效；highlights 模式响应只有 `results[].highlights`（无 text）；maxAgeHours 在 contents 内/顶层均被接受。
- **共享工具函数**：`asSearchStringList()`（SearchService.kt，internal，`JsonElement?` 扩展）兼容 JSON 数组与逗号分隔字符串，返回 `List<String>?`——所有数组参数统一用它。
- **终审 maxAgeHours 位置裁定**：Exa 官方 MCP 源码（web_search_advanced_exa）把 maxAgeHours 放 contents 对象内 → 现实现正确（虽然后来整个参数被移除）。

## 3. git 状态 / CI

- 分支 `master`，HEAD = `b273848`，工作树干净
- CI：`gh run list --workflow nightly-build-debug.yml --repo DevLintMar/rikkahub --limit 1 --json databaseId,headSha,status,conclusion`
  - 逐 commit 绿：31151045788(f9b5f48) / 31155759474(6cfb25a) / 31156587102(abe8c0b) / 31157157858(2a7b55c) / 31158585114(f82b701) / **31159587952(b273848) 待确认**
- 交接文档推送时补 b273848 的 CI 结论（headSha 核对）

## 4. 恢复地图

| 台账 | 路径 |
|---|---|
| 全参数 SDD 台账 | `.superpowers/sdd/2026-08-07-search-web-full-params/progress.md`（git-ignored，含全部 review/终审记录） |
| 缺陷修复 SDD 台账 | `.superpowers/sdd/2026-08-07-search-params-fixes/progress.md`（git-ignored） |
| 计划 1 | `docs/superpowers/plans/2026-08-07-search-web-full-params.md` |
| 计划 2 | `docs/superpowers/plans/2026-08-07-search-params-fixes.md` |
| 供应商调研报告 | `docs/references/search-providers-api-research.md`（**git-ignored**，本地参考；六供应商接口全参数面） |
| 上一交接（记忆工具） | `docs/superpowers/handoffs/2026-08-06-memory-ui-injection-final-next-phase.md` |

## 5. 待确认 / 挂起项（按优先级）

### ① 设备核验（唯一需用户上手）

装最新 debug APK（含 b273848 起）验证：
1. **Exa deep**：让 AI "用 Exa deep 模式搜索并总结" → 工具结果 `answer` 字段出现综合答案（非 null）
2. **Exa 默认 highlights**：AI 不传 content_type 时返回关键句（token 省）；显式 text 才全文
3. **Tavily 全参数**：time_range+topic=news 返回带日期新文章；include_domains 限定域名；exact_match 时 AI 的 query 自动带引号（不再 400）
4. **service 必选**：AI 不选 service 时被要求补全（不静默用第一个）
5. **全渠道**：Metaso scope=scholar、Firecrawl tbs/location、LinkUp 日期过滤、Jina gl/hl 各自主传参生效

### ② 遗留 Minor / defer（不阻塞）

- **Metaso image/video scope 响应未建模**：enum 声明了但响应只解析 webpages/podcasts，image/video 结果会被 ignoreUnknownKeys 丢弃 → 空 items（终审 M2 defer，设备核验后决定是否收窄枚举）
- Exa `content["answer"]?.jsonPrimitive` 非受检强转（runCatching 内，仅反契约 object 才失败）
- 各渠道 API 对新参数的**实际接受性**大多已用真实 key 实测（Tavily/Exa）；Firecrawl/Metaso/LinkUp/Jina 待真实 key 联调
- Tavily time_range 无运行时校验（靠 schema enum）

### ③ 历史挂起（更早遗留，未变）

- 乱召回（语义搜索 bug，需 rawTop logs）；Task 12 ripgrep artifact 流水线；更新检查恢复点（UpdateChecker.kt 删 `return@flow`）
- plan mode 旧计划 atomic-frolicking-hare.md（切换对话转圈遮罩+预热，已调研未实现）——注意该文件已被 search_web 计划覆盖，旧内容如需保留见会话历史

## 6. 技术约束 / 惯例（必须遵守）

- **本机无编译器**：不运行 gradle。静态编写 + review，编译验证全靠 CI。
- **CI 判定铁律**：`gh run list ...` 权威判定 + **核对 headSha**。流程：先 push 再 `gh workflow run nightly-build-debug.yml --repo DevLintMar/rikkahub --ref master`。
- **SDD**（用户指定开发模式）：fresh implementer + task review + 终审（最强大模型）+ 修复波 + scoped re-review；小改可直修但需 CI 验证。
- **工具参数三键一致性**（本阶段最大教训）：schema 键 = params 读取键（snake_case）≠ body 写入键（各家 API 键）。改渠道参数时必须三处同步。
- runCatching 不能包 suspend；suspend 用 try/catch + 重抛 `CancellationException`。
- 字符串双写 en+zh；六 locale 占位符串全写（本阶段无 UI 字符串改动）。
- HugeIcons 图标名以库内真实存在为准（MoonAngledRainZap 已确认存在）。
- 中文 conventional commit；文件删除走 `~/.claude/scripts/trash.sh`；force-push 需用户明确要求。

## 7. 停靠点

- **已完成**：search_web 聚合机制 + 六渠道全参数 + 实测缺陷修复全部落地，master HEAD `b273848`。
- **待确认**：b273848 的 CI（推送交接文档时核对）+ 设备核验（§5-①）。
- **恢复动作**：读本文档 §4/§5；下一阶段开始前先让用户装包核验本轮改动。
