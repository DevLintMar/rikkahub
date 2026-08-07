# search_web 全参数支持：六渠道参数全量暴露与发送

## Context

上一轮（commit a1c40aa）已把各渠道 `parameters()` 声明的参数动态聚合进 `search_web` 的 schema 与描述，且 execute 全量透传 args。但调研（docs/references/search-providers-api-research.md，本地未入库）发现渠道端"水龙头"只开了一角：Tavily 只发 topic、Exa 只发 type、Firecrawl 只发 sources/categories、Metaso scope 硬编码、LinkUp 没发 maxResults、Jina 没发 num。官方 API 支持的其余参数 AI 无法使用。

本计划：把 6 个选定渠道的**全部实用搜索参数**接入——`parameters()` 声明（自动进入聚合）+ `search()` 读取并写入请求体。BASE = `5ec0bfa`（含共享工具函数 `asSearchStringList`，SearchService.kt 内部函数，接收 `JsonElement?`，兼容数组与逗号分隔字符串）。

## Global Constraints

- CI-only 编译（不跑 gradle）；每个 service 文件独立修改，**不得修改** SearchTools.kt / SearchService.kt / 其他 service（共享 helper 已在 BASE）
- 参数命名 snake_case（工具入参），映射到各家 API 的 camelCase/原样键名；所有新参数**可选**（required 仍只含 query），null 时不写入请求体（保持现有行为不变）
- 不改动任何 service 的鉴权/端点/响应解析结构；响应模型加固（字段改 nullable/默认值）属于任务范围时明确列出
- runCatching 只包非 suspend；suspend 用 try/catch（现有模式：`withContext(IO) { runCatching {...} }` 保留）
- 中文注释与现有风格一致

## Task 1：Tavily 全参数（TavilySearchService.kt）

`parameters()` 在 query/topic 后新增 8 个参数声明（type/description/enum）：
- `time_range`：string，enum `day`/`week`/`month`/`year`，描述 "restrict results to this time range"
- `start_date`：string，"earliest publish date, format YYYY-MM-DD"
- `end_date`：string，"latest publish date, format YYYY-MM-DD"
- `include_domains`：array of string，"only include results from these domains"
- `exclude_domains`：array of string，"exclude results from these domains"
- `country`：string，"boost results from this country (full country name, e.g. 'Japan')"
- `exact_match`：boolean，"require the exact phrase"
- `include_raw_content`：boolean，"include full page content instead of snippet"

`search()` 请求体条件写入（key 存在且非 null 才 put）：`time_range`、`start_date`、`end_date`、`include_domains`（用 `params["include_domains"].asSearchStringList()` 转 JsonArray）、`exclude_domains`（同上）、`country`、`exact_match`、`include_raw_content`。响应映射：`text = if (include_raw_content == true) it.rawContent ?: it.content else it.content`。

## Task 2：Exa 全参数（ExaSearchService.kt）

`parameters()` 在 query/type 后新增：
- `category`：string，enum `company`/`publication`/`news`/`personal site`/`financial report`/`people`
- `include_domains` / `exclude_domains`：array of string（同 Task 1 描述模式）
- `start_published_date` / `end_published_date`：string，ISO 8601 (YYYY-MM-DD)
- `user_location`：string，"two-letter ISO country code for geo-targeting (e.g. 'US')"
- `max_age_hours`：integer，"max cache age in hours; 0 = always fetch fresh"
- `content_type`：string，enum `text`（默认）/`highlights`，"text = full page content; highlights = key excerpts only (saves tokens)"

`search()`：body 条件写入 `category`、`includeDomains`、`excludeDomains`、`startPublishedDate`、`endPublishedDate`、`userLocation`；`contents` 块：`content_type == "highlights"` 时 `{ "highlights": true }`，否则现有 `{ "text": true }`；`max_age_hours` 存在时写入 `contents.maxAgeHours`。响应映射：highlights 模式下 `text = it.highlights?.joinToString("\n") ?: ""`（ExaResult 新增 `highlights: List<String>? = null` 字段，@SerialName("highlights")）。其余（answer/output、images）不变。

## Task 3：Firecrawl 全参数（FirecrawlSearchService.kt）

`parameters()` 在 query/sources/categories 后新增：
- `include_domains` / `exclude_domains`：array of string（互斥，描述注明 mutually exclusive）
- `tbs`：string，"time filter: qdr:h/d/w/m/y or cdr:1,cd_min:MM/DD/YYYY,cd_max:MM/DD/YYYY"
- `location`：string，"geo location, e.g. 'San Francisco,California,United States'"
- `country`：string，"two-letter country code, default US"
sources 描述更新为 "Optional list of sources: `web`, `news`, `images`, default value is `web`"。

`search()`：body 条件写入 `includeDomains`/`excludeDomains`（用本文件已有 private `asStringList()`）、`tbs`、`location`、`country`。响应：`FirecrawlSearchResultData` 新增 `images: List<FirecrawlSearchResultImageItem>? = emptyList()`（@Serializable：`title: String? = null, imageUrl: String, url: String? = null`）；SearchResult 的 `images = resultData.images?.mapNotNull { it.imageUrl.takeIf(String::isNotBlank) } ?: emptyList()`。web/news 映射不变。

## Task 4：Metaso 全参数 + 响应加固（MetasoSearchService.kt）

`parameters()` 在 query 后新增：
- `scope`：string，enum `webpage`/`document`/`scholar`/`podcast`/`video`/`image`，描述 "search scope, default webpage"
- `include_raw_content`：boolean，"include full page text for each result (webpage scope only, costs more credits)"
- `concise_snippet`：boolean，"return concise matched snippets"

`search()`：`scope` 从 params 读（默认 "webpage"），条件写入 `includeRawContent`、`conciseSnippet`。text 映射：`webpage.rawContent?.takeIf { it.isNotBlank() } ?: webpage.snippet ?: ""`。

响应模型加固（保持向后兼容解码）：
- `MetasoSearchResponse`：`credits: Int? = null`；`webpages: List<MetasoWebpage> = emptyList()`；新增 `total: Int? = null`、`podcasts: List<MetasoPodcast> = emptyList()`（新类：`title: String, link: String, snippet: String? = null, date: String? = null`）
- `MetasoSearchParameters`：全部字段加默认值（`q: String = "", scope: String = "", size: Int = 0`）
- `MetasoWebpage`：`score: String? = null, snippet: String? = null, position: Int? = null, date: String? = null`；新增 `rawContent: String? = null, authors: List<String> = emptyList()`
- items 构建：webpages 优先，webpages 为空时回退 podcasts（同 SearchResultItem 映射）

## Task 5：LinkUp 全参数 + 崩溃修复（LinkUpService.kt）

`parameters()` 在 query 后新增：
- `from_date` / `to_date`：string，"ISO date YYYY-MM-DD"
- `include_domains` / `exclude_domains`：array of string

`search()` body：新增 `put("maxResults", JsonPrimitive(commonOptions.resultSize))`（恒发）；条件写入 `fromDate`、`toDate`、`includeDomains`、`excludeDomains`（asSearchStringList）。

崩溃修复：`Source.snippet: String? = null`（官方可返回 null，现非空声明会 SerializationException）；`Source.favicon: String? = null` 新增（ignoreUnknownKeys 会忽略，但显式声明更稳）；`LinkUpSearchResponse.answer: String? = null`。items 映射 `text = it.snippet ?: ""`。scrape() 不变。

## Task 6：Jina 全参数（JinaSearchService.kt）

`parameters()` 在 query 后新增：
- `gl`：string，"two-letter country code for search localization (e.g. 'jp')"
- `hl`：string，"two-letter language code (e.g. 'ja')"

`search()` body：恒发 `put("num", JsonPrimitive(commonOptions.resultSize))`；条件写入 `gl`、`hl`。其余不变（响应 take(resultSize) 保留作双保险）。

## Verification

- 每任务：implementer commit → task reviewer（spec+quality）→ 通过记台账
- 全部完成后：终审（opus，全分支 diff）→ 修复波 → scoped re-review
- CI：全部 commit 完成后统一 push + `gh workflow run nightly-build-debug.yml --repo DevLintMar/rikkahub --ref master`，`gh run list` 判定 + headSha 核对
- 设备核验：启用 Tavily+Exa，让 AI 分别用 time_range/category 等参数搜索，确认参数生效；只启用 Bing 等无额外参数渠道时 search_web 描述无 Extra parameters 段落
