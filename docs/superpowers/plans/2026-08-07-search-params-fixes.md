# 搜索参数实测缺陷修复：Tavily 描述引导 + Exa deep 综合答案

## Context

全参数接入（上一计划，master f9b5f48）后，用户用真实 key 对 Tavily/Exa 做了设备实测，发现两类问题：

**Tavily（描述误导，非代码 bug）**——直接打 API 复测确认：
- `exact_match=true` 报 400，API 主动返回 `exact_match=true requires a quoted phrase in the query`。参数本身有效（query 带引号 `"machine learning"` 时 200），但描述没告诉 AI 这个要求 → AI 乱传会 400
- `time_range`/`start_date`/`end_date` 只有配 `topic=news` 时才有可见效果（general 响应无 published_date 字段），描述没引导
- `country` 是**提升（boost）非过滤**，泛查询下无日本源可提升，描述"boost results"已对但不清晰

**Exa（真实逻辑缺口）**——直接打 API 复测确认：
- `type=deep` 单独返回格式与 fast/auto 相同、`answer` 恒 null；综合答案只在请求体带 `outputSchema` 时生成（实测 `type=deep`+`outputSchema` → `output.content` = `{"answer":"..."}`）
- 现有 `search()` 不发 outputSchema → AI 传 `type:"deep"` 拿到的是普通列表，"deep 无效"是准确的
- 附带：当前 DTO `ExaOutput.content: String?` 无法解码 outputSchema 返回的 object（`{"answer":...}`）→ 会解码失败

## Global Constraints

- 只改 TavilySearchService.kt 与 ExaSearchService.kt 两个文件；不得触碰其他文件
- 不改任何鉴权/端点/其他参数逻辑；请求体默认行为（未传对应参数）保持字节级不变
- 参数名 snake_case（工具入参）与 API 键名映射沿用现约定
- CI-only 编译（不跑 gradle）；静态自查 + review
- 中文 conventional commit；中文注释克制

## Task 1：Tavily 参数描述修复（TavilySearchService.kt）

只改 `parameters()` 里 5 个参数的 description 文案（type/enum/required 全不动，search() 全不动）：

- `exact_match` → `"Require exact phrase match; the query must contain the phrase in double quotes, e.g. \\\"John Smith\\\" CEO (otherwise the API rejects the request)"`（Kotlin 字符串里双引号需转义 `\"`）
- `time_range` → `"restrict results to this time range (recommended together with topic=news)"`
- `start_date` → `"earliest publish date, format YYYY-MM-DD (recommended together with topic=news)"`
- `end_date` → `"latest publish date, format YYYY-MM-DD (recommended together with topic=news)"`
- `country` → `"boost results from this country (full country name, e.g. 'Japan'); boosts relevance rather than filtering"`

## Task 2：Exa deep 综合答案接线（ExaSearchService.kt）

1. **请求体**：`search()` 里 `type` 读成局部变量后，当 `type == "deep"` 时在 body 追加：
   ```kotlin
   put("outputSchema", buildJsonObject {
       put("type", "object")
       put("properties", buildJsonObject {
           put("answer", buildJsonObject { put("type", "string") })
       })
   })
   ```
   （fast/auto 不加，避免 ~2s 合成延迟）

2. **DTO**：`ExaOutput.content` 从 `String?` 改为 `JsonElement?`（import kotlinx.serialization.json.JsonElement）——outputSchema 模式返回 object。

3. **answer 提取**：新增 private helper，search() 里 `answer = extractOutputAnswer(response.output)`：
   ```kotlin
   private fun extractOutputAnswer(output: ExaOutput?): String? {
       val content = output?.content ?: return null
       return when (content) {
           is JsonPrimitive -> content.contentOrNull
           is JsonObject -> content["answer"]?.jsonPrimitive?.contentOrNull
           else -> null
       }
   }
   ```
   兼容两种形态：旧模式 content 是字符串（JsonPrimitive）、outputSchema 模式 content 是 `{"answer":...}`。

4. `parameters()` 里 `type` 描述更新为点明 deep 会产出综合答案（如 `"...deep (synthesized answer with citations, slower)"`）。

## Verification

- 每任务 implementer(sonnet) + task reviewer(sonnet) 通过
- 合并终审（控制器）后统一 push + nightly-build-debug，headSha 核对
- 设备核验：Tavily AI 传 exact_match 时 query 自动带引号；Exa AI 传 type=deep 时返回带 answer 的综合答案；fast/auto 无输出变化
