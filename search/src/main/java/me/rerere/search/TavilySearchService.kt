package me.rerere.search

import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.ai.core.InputSchema
import me.rerere.search.SearchResult.SearchResultItem
import me.rerere.search.SearchService.Companion.httpClient
import me.rerere.search.SearchService.Companion.json
import me.rerere.search.SearchService.Companion.keyRoulette
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

private const val TAG = "TavilySearchService"

object TavilySearchService : SearchService<SearchServiceOptions.TavilyOptions> {
    override val name: String = "Tavily"

    @Composable
    override fun Description() {
        val urlHandler = LocalUriHandler.current
        TextButton(
            onClick = {
                urlHandler.openUri("https://app.tavily.com/home")
            }
        ) {
            Text(stringResource(R.string.click_to_get_api_key))
        }
    }

    override fun parameters(options: SearchServiceOptions.TavilyOptions): InputSchema? =
        InputSchema.Obj(
            properties = buildJsonObject {
                put("query", buildJsonObject {
                    put("type", "string")
                    put("description", "search keyword")
                })
                put("topic", buildJsonObject {
                    put("type", "string")
                    put("description", "search topic (one of `general`, `news`, `finance`)")
                    put("enum", buildJsonArray {
                        add("general")
                        add("news")
                        add("finance")
                    })
                })
                put("time_range", buildJsonObject {
                    put("type", "string")
                    put("enum", buildJsonArray {
                        add("day")
                        add("week")
                        add("month")
                        add("year")
                    })
                    put("description", "restrict results to this time range (recommended together with topic=news)")
                })
                put("start_date", buildJsonObject {
                    put("type", "string")
                    put("description", "earliest publish date, format YYYY-MM-DD (recommended together with topic=news)")
                })
                put("end_date", buildJsonObject {
                    put("type", "string")
                    put("description", "latest publish date, format YYYY-MM-DD (recommended together with topic=news)")
                })
                put("include_domains", buildJsonObject {
                    put("type", "array")
                    put("items", buildJsonObject {
                        put("type", "string")
                    })
                    put("description", "only include results from these domains")
                })
                put("exclude_domains", buildJsonObject {
                    put("type", "array")
                    put("items", buildJsonObject {
                        put("type", "string")
                    })
                    put("description", "exclude results from these domains")
                })
                put("country", buildJsonObject {
                    put("type", "string")
                    put("description", "boost results from this country (full country name, e.g. 'Japan'); boosts relevance rather than filtering")
                })
                put("exact_match", buildJsonObject {
                    put("type", "boolean")
                    put(
                        "description",
                        "Require exact phrase match. The query MUST contain the target phrase wrapped in double quotes, e.g. query=\"\\\"John Smith\\\" CEO\" — without a quoted phrase the API rejects the request. Only set to true when the query already includes quoted phrases."
                    )
                })
            },
            required = listOf("query")
        )

    override fun scrapingParameters(options: SearchServiceOptions.TavilyOptions): InputSchema? =
        InputSchema.Obj(
            properties = buildJsonObject {
                put("urls", buildJsonObject {
                    put("type", "array")
                    put("items", buildJsonObject { put("type", "string") })
                    put("description", "URLs to extract content from (up to 20)")
                })
                put("extract_depth", buildJsonObject {
                    put("type", "string")
                    put("description", "basic = default; advanced = more data (tables, embedded content), better for LinkedIn/protected sites, slightly slower")
                    put("enum", buildJsonArray {
                        add("basic")
                        add("advanced")
                    })
                })
                put("query", buildJsonObject {
                    put("type", "string")
                    put("description", "user intent to rerank extracted content chunks by relevance")
                })
                put("chunks_per_source", buildJsonObject {
                    put("type", "integer")
                    put("description", "max relevant chunks per source, only effective when query is provided (default 3)")
                })
                put("include_images", buildJsonObject {
                    put("type", "boolean")
                    put("description", "include image URLs extracted from each page")
                })
                put("include_favicon", buildJsonObject {
                    put("type", "boolean")
                    put("description", "include favicon URL for each result")
                })
                put("format", buildJsonObject {
                    put("type", "string")
                    put("description", "output format of extracted content (markdown default; text may increase latency)")
                    put("enum", buildJsonArray {
                        add("markdown")
                        add("text")
                    })
                })
            },
            required = listOf("urls")
        )

    override suspend fun search(
        params: JsonObject,
        commonOptions: SearchCommonOptions,
        serviceOptions: SearchServiceOptions.TavilyOptions
    ): Result<SearchResult> = withContext(Dispatchers.IO) {
        runCatching {
            val query = params["query"]?.jsonPrimitive?.content ?: error("query is required")
            val topic = params["topic"]?.jsonPrimitive?.contentOrNull ?: "general"

            // Validate topic
            if (topic !in listOf("general", "news", "finance")) {
                error("topic must be one of `general`, `news`, `finance`")
            }

            val body = buildJsonObject {
                put("query", query)
                put("max_results", commonOptions.resultSize)
                put("search_depth", serviceOptions.depth.ifEmpty { "advanced" })
                put("topic", topic)
                put("include_answer", "advanced")
                put("include_images", true)
                params["time_range"]?.jsonPrimitive?.contentOrNull?.let { put("time_range", it) }
                params["start_date"]?.jsonPrimitive?.contentOrNull?.let { put("start_date", it) }
                params["end_date"]?.jsonPrimitive?.contentOrNull?.let { put("end_date", it) }
                params["include_domains"].asSearchStringList()?.takeIf { it.isNotEmpty() }?.let { domains ->
                    put("include_domains", buildJsonArray {
                        domains.forEach { add(JsonPrimitive(it)) }
                    })
                }
                params["exclude_domains"].asSearchStringList()?.takeIf { it.isNotEmpty() }?.let { domains ->
                    put("exclude_domains", buildJsonArray {
                        domains.forEach { add(JsonPrimitive(it)) }
                    })
                }
                params["country"]?.jsonPrimitive?.contentOrNull?.let { put("country", it) }
                params["exact_match"]?.jsonPrimitive?.booleanOrNull?.let { put("exact_match", it) }
            }
            val apiKey = keyRoulette.next(serviceOptions.apiKey, serviceOptions.id.toString())

            val request = Request.Builder()
                .url("https://api.tavily.com/search")
                .post(body.toString().toRequestBody())
                .addHeader("Authorization", "Bearer $apiKey")
                .build()
            val response = httpClient.newCall(request).await()
            if (response.isSuccessful) {
                val response = response.body.string().let {
                    json.decodeFromString<SearchResponse>(it)
                }

                return@withContext Result.success(
                    SearchResult(
                        answer = response.answer,
                        items = response.results.map {
                            SearchResultItem(
                                title = it.title,
                                url = it.url,
                                text = it.content
                            )
                        },
                        images = response.images,
                    ))
            } else {
                error("response failed #${response.code}")
            }
        }
    }

    override suspend fun scrape(
        params: JsonObject,
        commonOptions: SearchCommonOptions,
        serviceOptions: SearchServiceOptions.TavilyOptions
    ): Result<ScrapedResult> = withContext(Dispatchers.IO) {
        runCatching {
            // 三键一致性：schema 键(snake_case) = 本处读取键 = 聚合键；Tavily body 本身就是 snake_case，直接透传
            val urls = params["urls"].asSearchStringList()?.takeIf { it.isNotEmpty() }
                ?: error("urls is required")
            val body = buildJsonObject {
                put("urls", buildJsonArray { urls.forEach { add(JsonPrimitive(it)) } })
                params["extract_depth"]?.jsonPrimitive?.contentOrNull?.takeIf(String::isNotBlank)?.let { put("extract_depth", it) }
                params["query"]?.jsonPrimitive?.contentOrNull?.takeIf(String::isNotBlank)?.let { put("query", it) }
                params["chunks_per_source"]?.jsonPrimitive?.contentOrNull?.toIntOrNull()?.let { put("chunks_per_source", it) }
                params["include_images"]?.jsonPrimitive?.booleanOrNull?.let { put("include_images", it) }
                params["include_favicon"]?.jsonPrimitive?.booleanOrNull?.let { put("include_favicon", it) }
                params["format"]?.jsonPrimitive?.contentOrNull?.takeIf(String::isNotBlank)?.let { put("format", it) }
            }
            val apiKey = keyRoulette.next(serviceOptions.apiKey, serviceOptions.id.toString())
            val request = Request.Builder()
                .url("https://api.tavily.com/extract")
                .post(body.toString().toRequestBody())
                .addHeader("Authorization", "Bearer $apiKey")
                .build()
            val response = httpClient.newCall(request).await()
            if (response.isSuccessful) {
                val response = response.body.string().let {
                    json.decodeFromString<ScrapeResponse>(it)
                }
                val includeImages = params["include_images"]?.jsonPrimitive?.booleanOrNull ?: false
                val includeFavicon = params["include_favicon"]?.jsonPrimitive?.booleanOrNull ?: false
                // 成功项 + 失败项：failed_results 是 HTTP 200 内上报的单 URL 失败，不能静默丢弃
                val entries = buildList {
                    response.results.forEach { item ->
                        add(
                            ScrapedResultUrl(
                                url = item.url,
                                content = item.rawContent,
                                images = if (includeImages) item.images else emptyList(),
                                metadata = if (includeFavicon) {
                                    ScrapedResultMetadata(favicon = item.favicon)
                                } else null,
                            )
                        )
                    }
                    response.failedResults.forEach { failed ->
                        add(ScrapedResultUrl(url = failed.url, error = failed.error))
                    }
                }
                return@withContext Result.success(ScrapedResult(urls = entries))
            } else {
                // 解析统一错误结构 {detail: {error}}，AI 拿到具体失败原因
                val bodyText = response.body.string()
                val detail = runCatching {
                    json.parseToJsonElement(bodyText).jsonObject["detail"]?.jsonObject?.get("error")?.jsonPrimitive?.contentOrNull
                }.getOrNull()
                error(detail ?: "response failed #${response.code}: $bodyText")
            }
        }
    }

    @Serializable
    data class SearchResponse(
        val query: String,
        val followUpQuestions: String? = null,
        val answer: String? = null,
        val images: List<String> = emptyList(),
        val results: List<TavilySearchService.SearchResultItem>,
    )

    @Serializable
    data class SearchResultItem(
        val title: String,
        val url: String,
        val content: String,
        val score: Double,
    )

    @Serializable
    data class ScrapeResponse(
        val results: List<ScrapedResultItem>,
        @SerialName("failed_results")
        val failedResults: List<FailedResultItem> = emptyList(),
    )

    @Serializable
    data class ScrapedResultItem(
        val url: String,
        @SerialName("raw_content")
        val rawContent: String,
        val images: List<String> = emptyList(),
        val favicon: String? = null,
    )

    @Serializable
    data class FailedResultItem(
        val url: String,
        val error: String? = null,
    )
}
