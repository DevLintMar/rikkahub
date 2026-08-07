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
                    put("description", "restrict results to this time range")
                })
                put("start_date", buildJsonObject {
                    put("type", "string")
                    put("description", "earliest publish date, format YYYY-MM-DD")
                })
                put("end_date", buildJsonObject {
                    put("type", "string")
                    put("description", "latest publish date, format YYYY-MM-DD")
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
                    put("description", "boost results from this country (full country name, e.g. 'Japan')")
                })
                put("exact_match", buildJsonObject {
                    put("type", "boolean")
                    put("description", "require the exact phrase")
                })
                put("include_raw_content", buildJsonObject {
                    put("type", "boolean")
                    put("description", "include full page content instead of snippet")
                })
            },
            required = listOf("query")
        )

    override fun scrapingParameters(options: SearchServiceOptions.TavilyOptions): InputSchema? =
        InputSchema.Obj(
            properties = buildJsonObject {
                put("url", buildJsonObject {
                    put("type", "string")
                    put("description", "url to scrape")
                })
            },
            required = listOf("url")
        )

    override suspend fun search(
        params: JsonObject,
        commonOptions: SearchCommonOptions,
        serviceOptions: SearchServiceOptions.TavilyOptions
    ): Result<SearchResult> = withContext(Dispatchers.IO) {
        runCatching {
            val query = params["query"]?.jsonPrimitive?.content ?: error("query is required")
            val topic = params["topic"]?.jsonPrimitive?.contentOrNull ?: "general"
            val includeRawContent = params["include_raw_content"]?.jsonPrimitive?.booleanOrNull == true

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
                params["include_domains"].asSearchStringList()?.let { domains ->
                    put("include_domains", buildJsonArray {
                        domains.forEach { add(JsonPrimitive(it)) }
                    })
                }
                params["exclude_domains"].asSearchStringList()?.let { domains ->
                    put("exclude_domains", buildJsonArray {
                        domains.forEach { add(JsonPrimitive(it)) }
                    })
                }
                params["country"]?.jsonPrimitive?.contentOrNull?.let { put("country", it) }
                params["exact_match"]?.jsonPrimitive?.booleanOrNull?.let { put("exact_match", it) }
                params["include_raw_content"]?.jsonPrimitive?.booleanOrNull?.let { put("include_raw_content", it) }
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
                                text = if (includeRawContent) it.rawContent ?: it.content else it.content
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
            val url = params["url"]?.jsonPrimitive?.content ?: error("url is required")
            val body = buildJsonObject {
                put("urls", buildJsonArray {
                    add(url)
                })
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
                return@withContext Result.success(
                    ScrapedResult(
                        urls = response.results.map {
                            ScrapedResultUrl(
                                url = it.url,
                                content = it.rawContent,
                            )
                        }
                    )
                )
            } else {
                error("response failed #${response.code}")
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
        val rawContent: String? = null
    )

    @Serializable
    data class ScrapeResponse(
        val results: List<ScrapedResultItem>,
    )

    @Serializable
    data class ScrapedResultItem(
        val url: String,
        @SerialName("raw_content")
        val rawContent: String,
    )
}
