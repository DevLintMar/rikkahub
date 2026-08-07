# 交接文档：scrape_web 重做（四渠道全参数 + 多 URL + 信封展示完善）— 下一阶段入口

**日期**：2026-08-07
**目的**：上下文清理前的完整交接。新会话读本文档即可续接。master 已推送至 `6ecd3db`，工作树干净。

---

## 0. 一句话概况

接上一交接 `49c67c5`（search_web 全参数阶段）。本阶段完成 **scrape_web 工具整体重做**：先并行 4 个子代理调研 Tavily/Exa/Jina/Firecrawl 的抓取 REST + MCP 接口（报告落 `docs/references/scrape-interface-research/`），随后把四渠道抓取专属参数全量接入、`scrape_web` 按 search_web 同款机制聚合渠道参数进 schema、支持多 URL、修掉 3 处静默失败 bug 与 2 处渠道 bug，信封展示从"裸 markdown 全文"重做为"每 URL 独立卡片（元信息/图片/失败项）"。末尾按用户要求给搜索服务删除加二次确认。全程 CI 绿。master `49c67c5` → `6ecd3db`。

---

## 1. 已完成工作（commit 链）

### 零、前置调研（4 并行子代理，产物 git-ignored）

`docs/references/scrape-interface-research/{tavily,exa,jina,firecrawl}-scrape.md` —— 每份覆盖 REST 全参数表 / 响应结构逐层 / MCP 工具 schema 与映射 / 与现有实现对照。核心结论见 §2。

### 一、scrape 四渠道全参数接入（search 模块）

| commit | 渠道 | 新增参数 |
|---|---|---|
| d34c1db | **全部** | `ScrapedResultUrl` 扩展 `error/images/statusCode/publishedDate/author`，`ScrapedResultMetadata` 加 `favicon`（全默认值兼容 CustomJs）；四个渠道 `scrapingParameters()` 全量重写 |
| d34c1db | Tavily | urls(≤20)/extract_depth/query/chunks_per_source/include_images/include_favicon/format + `failed_results` 失败项 + 错误体 `{detail:{error}}` 解析 |
| d34c1db | Exa | urls(≤100)/content_type(text/highlights/summary)/max_characters/max_age_hours/summary_query/extract_links + **statuses[] 检查修静默失败** + 错误体解析 |
| d34c1db | Jina | X-* header 族：respond_with/target_selector/timeout/engine/no_cache/retain_links/max_tokens + **修免 key 空 Bearer 头** + 官方错误 JSON（name/readableMessage）解析 |
| d34c1db | Firecrawl | formats/wait_for/timeout/country/include_tags/exclude_tags/max_age/remove_base64_images + **修 parsers:[] PDF 空内容** + metadata.error 失败项 + 错误体解析 |

### 二、scrape_web 工具层（聚合 + 多 URL + 信封）

| commit | 内容 |
|---|---|
| dccabb3 | **聚合机制**：`createSearchTools` 遍历 scrapeCapable 渠道 `scrapingParameters()`，聚合除 url/urls/service 外的参数进 scrape_web schema，描述加 `[渠道]` 标注；schema 改 `urls`(array) + `service`(required，缺失报错不再静默回退)；execute 归一化 url/urls 双写 + 透传渠道参数；信封改 `{type:"web_fetch", service, urls[], truncated, totalChars}`，逐 URL 均分预算截断 |
| a9e2a6e | **信封 UI 重做**：`ScrapeWebToolUI` Summary 显示页数+失败数（红色）、favicon row；`ScrapeWebPreview` 每 URL 独立卡片（标题/URL/HTTP 状态码/发布时间/作者 pill，失败红色 errorContainer，图片缩略图 LazyRow，Markdown 正文）；旧信封数据回退；新增 5 字符串 × 6 locale |

### 三、健壮性与修复波

| commit | 内容 |
|---|---|
| 5991b90 | Exa 未传 max_characters 时 text 默认回退 `true`（避免空对象）；Firecrawl images 解析兼容字符串数组与 `{imageUrl,url}` 对象数组；ScrapeWebToolUI 旧信封 Summary 回退显示 URL |
| 3888406 | **CI 修复**：Jina `scrapingParameters()` 用了 enum 数组但缺 `buildJsonArray`/`add` import → compileDebugKotlin 失败，补 import |
| 6ecd3db | **搜索服务删除二次确认**：`SearchProviderCard` 删除菜单项改弹 AlertDialog（标题 confirm_delete + 正文带服务名），确认才删；新增 `setting_page_search_delete_message` × 6 locale |

---

## 2. 关键设计决策（认知遗留）

### 抓取接口调研结论（写死认知，四家通用）

- **MCP 全是极简封装**：`tavily_extract` 只透 6 参数（漏 chunks_per_source/timeout/include_usage）、`web_fetch_exa` 只透 urls+maxCharacters、Jina `read_url` 只透 url/withAllLinks/withAllImages、`firecrawl_scrape` 最多但也非全量。"让 AI 传专属抓取参数"官方 MCP 没解决，RikkaHub 必须自己映射。
- **三家单 URL 失败都走 HTTP 200 旁路字段**（整体不报错码）：Tavily `failed_results`、Exa `statuses[]`、Firecrawl `metadata.error`。这是静默失败 bug 的根源，修复模式 = DTO 补字段 + 转成 `ScrapedResultUrl.error`。
- **Jina 参数只能走请求头**：X-* header + POST body（url/file），query 参数形式不存在（实测 `?target_selector=h1` 会拼进目标 URL）。
- **Exa `/contents` 内容控制是顶层参数**（text/highlights/summary），不要像 `/search` 那样包在 `contents` 对象里；`maxAgeHours`（0=强制现爬/-1=仅缓存）替代已废弃 `livecrawl`。
- **Firecrawl `parsers: []` ≠ 官方默认 `[{"type":"pdf"}]`**：空数组使 PDF 以 base64 返回（markdown 为空）；走官方默认才能让 PDF 页出 markdown。
- **Firecrawl v2 无独立 `/crawl/{id}/results`**（那是 v1）；结果内联在 `GET /v2/crawl/{id}`，`next` 游标分页；`maxDepth`→`maxDiscoveryDepth`、`ignoreSitemap`→`sitemap:'skip'`。crawl/batch 未接入（见 §5）。
- Exa 认证 Bearer 与 x-api-key 都合法（OpenAPI 同时声明），现实现 Bearer 可用。

### 实现决策

- **scrape_web 聚合 = search_web 同款机制**：聚合 `scrapingParameters()` 渠道特有参数进 schema，`[渠道]` 标注描述，execute 把**整个 args.jsonObject** 透传给 `service.scrape`，各渠道自取认识的键。聚合排除集 `{url, urls, service}`。
- **三键一致性扩展到 scrape**：schema 键(snake_case) = params 读取键 = 聚合键；body/header 写入键按各家原生（Tavily snake 透传 / Exa、Firecrawl camel / Jina X-* header）。**改渠道抓取参数必须三处同步**。
- **多 URL 策略**：schema 只暴露 `urls`(array)；Tavily/Exa 原生多 URL 直接透传，Jina/Firecrawl 单 URL 渠道取第一个；execute 归一化 `url`(首元素) + `urls`(全量) 双写，未改造渠道（Zhipu/Bing 等 13 家）继续读 url，CustomJs 读 urls——互不破坏。
- **service 必选**：`required = ["urls", "service"]`，execute 缺失/非法 `error("service is required...")`，不再 `?: scrapeCapable.first()` 静默回退。
- **信封结构变更**：旧 `{type, url, text, truncated, totalChars}` → 新 `{type:"web_fetch", service, urls:[{url, content, metadata, error, images, statusCode, publishedDate, author}], truncated, totalChars}`。UI 层对旧信封（无 urls 数组）有回退渲染，历史消息不崩。
- **截断策略**：整体预算 MAX_SCRAPE_TEXT_CHARS(32KB)，每 URL 上限 = 预算/urls.size（下限 1024），逐 URL 截断；`truncated`/`totalChars` 全局汇总。多 URL 时不再拼接成一个大字符串（旧实现拼接后单点截断）。
- **渠道错误原样透出**：Tavily `{detail:{error}}`、Exa `detail` 或 `response failed #code`、Jina `name: readableMessage`、Firecrawl `code: error`，替代旧的 `response failed #code` 裸码。

---

## 3. git 状态 / CI

- 分支 `master`，HEAD = `6ecd3db`，工作树干净
- CI：`gh run list --workflow nightly-build-debug.yml --repo DevLintMar/rikkahub --limit 1 --json databaseId,headSha,status,conclusion`
  - **3888406 绿**（31178078028，import 修复后首个绿）/ **6ecd3db 绿**（31182436501）
  - 中途失败：d34c1db（31177230459 failure）、5991b90（31177463411 failure）——**同一根因：Jina 缺 `buildJsonArray`/`add` import**，3888406 修复后全绿
- 交接文档推送后补 6ecd3db 已绿确认（headSha `6ecd3dbc17...` 核对一致）

## 4. 恢复地图

| 台账/文档 | 路径 |
|---|---|
| 抓取接口调研报告（4 份，**git-ignored** 本地参考） | `docs/references/scrape-interface-research/{tavily,exa,jina,firecrawl}-scrape.md` |
| 上一交接（search_web 全参数） | `docs/superpowers/handoffs/2026-08-07-search-web-full-params-next-phase.md` |
| search 供应商调研（旧，全渠道） | `docs/references/search-providers-api-research.md`（git-ignored） |
| 本阶段计划 | `C:\Users\LintMar\.claude\plans\atomic-frolicking-hare.md`（已从旧 search_web 计划覆盖为本阶段 scrape_web 重做计划） |

## 5. 待确认 / 挂起项（按优先级）

### ① 设备核验（唯一需用户上手，两个阶段合并）

装最新 debug APK（含 6ecd3db 起）验证：
1. **scrape_web 多 URL**：Tavily 传 2 个 URL 一次抓完；一个失效 URL → 信封出现红色失败项
2. **Exa**：`content_type: highlights` 返回关键句（token 省）；`max_age_hours: 0` 强制现爬；一个 404 URL → 失败项显示 `CRAWL_NOT_FOUND 404`
3. **Jina**：`target_selector: article` 只抓正文；`respond_with: html` 返回 HTML；免 key 场景不再发空 Bearer 头
4. **Firecrawl**：`formats: [markdown, links]` 同时出正文+链接；`max_age: 0` 强制实时；`country: JP` 地域生效
5. **信封**：多 URL 各自卡片、失败红色、图片缩略图、HTTP 状态码/标题/时间/作者 pill
6. **搜索服务删除二次确认**：删除菜单弹出确认框，取消不删、确认才删；仅剩 1 个服务时删除仍禁用
7. **上一阶段遗留**（search_web，未变）：Exa deep 综合答案、Exa 默认 highlights、Tavily 全参数（time_range+topic=news 等）、service 必选、Metaso scope=scholar 等

### ② 遗留 Minor / defer（不阻塞）

- **Firecrawl crawl/batch 未接入**：本次只做单页 scrape。crawl（异步多页）与 batch 需要 `id` + 状态轮询 + `next` 游标（v2 无独立 /results 端点），是新工具而非 scrape_web 参数扩展，后续单独评估
- Firecrawl `json`/`summary` format 的 schema 配置（REST 用 `formats:[{type:"json", schema, prompt}]` 对象形态）未暴露给 AI（schema 只列字符串枚举）
- Jina `read_url` 多 URL 并行（官方 `url` 可数组）未接——Jina scrape 仍单 URL
- Exa `ids` 复用 `/search` 文档 id 免重复抓取未接（ExaResult.id 现成可用）
- Exa `content["answer"]?.jsonPrimitive` 非受检强转（search 侧遗留，runCatching 内）
- Metaso image/video scope 响应未建模（search 侧遗留，defer 自上一交接）

### ③ 历史挂起（更早遗留，未变）

- 乱召回（语义搜索 bug，需 rawTop logs）；Task 12 ripgrep artifact 流水线；更新检查恢复点（UpdateChecker.kt 删 `return@flow`）
- plan mode 旧计划 atomic-frolicking-hare.md 已被本阶段计划覆盖（旧"切换对话转圈遮罩"内容如需保留见会话历史）

## 6. 技术约束 / 惯例（必须遵守）

- **本机无编译器**：不运行 gradle。静态编写 + review，编译验证全靠 CI。
- **CI 判定铁律**：`gh run list ...` 权威判定 + **核对 headSha**。流程：先 push 再 `gh workflow run nightly-build-debug.yml --repo DevLintMar/rikkahub --ref master`。
- **SDD**（用户指定开发模式）：fresh implementer + task review + 终审 + 修复波 + scoped re-review；小改可直修但需 CI 验证。
- **工具参数三键一致性**（最大教训，scrape 同样适用）：schema 键(snake_case) = params 读取键 = 聚合键 ≠ body/header 写入键（各家 API 原生）。
- **Kotlin import 陷阱**：`buildJsonArray`/`add` 等 kotlinx.serialization.json 顶层函数**必须显式 import**，用了却没 import 是 CI 最常见的编译失败源（本阶段 Jina 踩过）。改完 grep 核对每个用到的函数都有 import。
- runCatching 不能包 suspend；suspend 用 try/catch + 重抛 `CancellationException`。
- 字符串双写 en+zh；六 locale 占位符串全写。
- 中文 conventional commit；文件删除走 `~/.claude/scripts/trash.sh`；force-push 需用户明确要求。

## 7. 停靠点

- **已完成**：scrape_web 重做（四渠道全参数 + 多 URL + 信封重做 + 静默失败修复）+ 搜索服务删除二次确认，master HEAD `6ecd3db`，全 CI 绿。
- **待确认**：设备核验（§5-①，两阶段合并）。
- **恢复动作**：读本文档 §4/§5；下一阶段开始前先让用户装包核验本轮改动。
