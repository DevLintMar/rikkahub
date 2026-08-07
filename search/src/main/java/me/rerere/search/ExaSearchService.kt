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
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.ai.core.InputSchema
import me.rerere.search.SearchResult.SearchResultItem
import me.rerere.search.SearchService.Companion.httpClient
import me.rerere.search.SearchService.Companion.json
import me.rerere.search.SearchService.Companion.keyRoulette
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

object ExaSearchService : SearchService<SearchServiceOptions.ExaOptions> {
    override val name: String = "Exa"

    @Composable
    override fun Description() {
        val urlHandler = LocalUriHandler.current
        TextButton(
            onClick = {
                urlHandler.openUri("https://dashboard.exa.ai/api-keys")
            }
        ) {
            Text(stringResource(R.string.click_to_get_api_key))
        }
    }

    override fun parameters(options: SearchServiceOptions.ExaOptions): InputSchema? =
        InputSchema.Obj(
            properties = buildJsonObject {
                put("query", buildJsonObject {
                    put("type", "string")
                    put("description", "search keyword")
                })
                put("type", buildJsonObject {
                    put("type", "string")
                    put("description", "Search type: fast (quick results), auto (default, balanced), deep (synthesized answer with citations, slower)")
                    put("enum", buildJsonArray {
                        add("fast")
                        add("auto")
                        add("deep")
                    })
                })
                put("category", buildJsonObject {
                    put("type", "string")
                    put("description", "news category to restrict results to")
                    put("enum", buildJsonArray {
                        add("company")
                        add("publication")
                        add("news")
                        add("personal site")
                        add("financial report")
                        add("people")
                    })
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
                put("start_published_date", buildJsonObject {
                    put("type", "string")
                    put("description", "earliest publish date, ISO 8601 (YYYY-MM-DD)")
                })
                put("end_published_date", buildJsonObject {
                    put("type", "string")
                    put("description", "latest publish date, ISO 8601 (YYYY-MM-DD)")
                })
                put("user_location", buildJsonObject {
                    put("type", "string")
                    put("description", "two-letter ISO country code for geo-targeting (e.g. 'US')")
                })
                put("content_type", buildJsonObject {
                    put("type", "string")
                    put("description", "highlights = key excerpts only (default, saves tokens); text = full page content")
                    put("enum", buildJsonArray {
                        add("text")
                        add("highlights")
                    })
                })
            },
            required = listOf("query")
        )

    override fun scrapingParameters(options: SearchServiceOptions.ExaOptions): InputSchema? =
        InputSchema.Obj(
            properties = buildJsonObject {
                put("urls", buildJsonObject {
                    put("type", "array")
                    put("items", buildJsonObject { put("type", "string") })
                    put("description", "URLs to fetch contents for (up to 100)")
                })
                put("content_type", buildJsonObject {
                    put("type", "string")
                    put("description", "text = full page markdown (default); highlights = key excerpts only (saves tokens); summary = LLM-generated summary")
                    put("enum", buildJsonArray {
                        add("text")
                        add("highlights")
                        add("summary")
                    })
                })
                put("max_characters", buildJsonObject {
                    put("type", "integer")
                    put("description", "max characters per URL (1-10000), controls response size and cost")
                })
                put("max_age_hours", buildJsonObject {
                    put("type", "integer")
                    put("description", "max acceptable cache age in hours; 0 = always crawl live (fresh), -1 = cache only (fast), omit = cache when available else crawl")
                })
                put("summary_query", buildJsonObject {
                    put("type", "string")
                    put("description", "custom query guiding the LLM summary, only used when content_type=summary")
                })
                put("extract_links", buildJsonObject {
                    put("type", "integer")
                    put("description", "number of links to extract from each page (0-1000), returned per page")
                })
            },
            required = listOf("urls")
        )

    override suspend fun search(
        params: JsonObject,
        commonOptions: SearchCommonOptions,
        serviceOptions: SearchServiceOptions.ExaOptions
    ): Result<SearchResult> = withContext(Dispatchers.IO) {
        runCatching {
            val query = params["query"]?.jsonPrimitive?.content ?: error("query is required")
            val contentType = params["content_type"]?.jsonPrimitive?.contentOrNull
            val useHighlights = contentType != "text"
            val searchType = params["type"]?.jsonPrimitive?.content ?: "auto"
            val body = buildJsonObject {
                put("query", JsonPrimitive(query))
                put("numResults", JsonPrimitive(commonOptions.resultSize))
                put("type", JsonPrimitive(searchType))
                if (searchType == "deep") {
                    put("outputSchema", buildJsonObject {
                        put("type", "object")
                        put("properties", buildJsonObject {
                            put("answer", buildJsonObject { put("type", "string") })
                        })
                    })
                }
                params["category"]?.jsonPrimitive?.contentOrNull?.let { put("category", it) }
                params["include_domains"].asSearchStringList()?.takeIf { it.isNotEmpty() }?.let { domains ->
                    put("includeDomains", buildJsonArray {
                        domains.forEach { add(JsonPrimitive(it)) }
                    })
                }
                params["exclude_domains"].asSearchStringList()?.takeIf { it.isNotEmpty() }?.let { domains ->
                    put("excludeDomains", buildJsonArray {
                        domains.forEach { add(JsonPrimitive(it)) }
                    })
                }
                params["start_published_date"]?.jsonPrimitive?.contentOrNull?.let { put("startPublishedDate", it) }
                params["end_published_date"]?.jsonPrimitive?.contentOrNull?.let { put("endPublishedDate", it) }
                params["user_location"]?.jsonPrimitive?.contentOrNull?.let { put("userLocation", it) }
                put("contents", buildJsonObject {
                    if (useHighlights) {
                        put("highlights", true)
                    } else {
                        put("text", true)
                    }
                })
            }
            val apiKey = keyRoulette.next(serviceOptions.apiKey, serviceOptions.id.toString())

            val request = Request.Builder()
                .url("https://api.exa.ai/search")
                .post(json.encodeToString(body).toRequestBody("application/json".toMediaType()))
                .addHeader("Authorization", "Bearer $apiKey")
                .build()

            val response = httpClient.newCall(request).execute()
            if (response.isSuccessful) {
                val bodyRaw = response.body.string()
                val response = runCatching {
                    json.decodeFromString<ExaData>(bodyRaw)
                }.onFailure {
                    it.printStackTrace()
                    println(bodyRaw)
                    error("Failed to decode response: $bodyRaw")
                }.getOrThrow()

                return@withContext Result.success(
                    SearchResult(
                        answer = extractOutputAnswer(response.output),
                        items = response.results.map {
                            SearchResultItem(
                                title = it.title,
                                url = it.url,
                                text = if (useHighlights && it.highlights != null) it.highlights.joinToString("\n").ifBlank { it.text ?: "" } else it.text ?: ""
                            )
                        },
                        images = response.results.mapNotNull { it.image?.takeIf { url -> url.isNotBlank() } },
                    ))
            } else {
                println(response.body.string())
                error("response failed #${response.code}")
            }
        }
    }

    override suspend fun scrape(
        params: JsonObject,
        commonOptions: SearchCommonOptions,
        serviceOptions: SearchServiceOptions.ExaOptions
    ): Result<ScrapedResult> = withContext(Dispatchers.IO) {
        runCatching {
            // 三键一致性：schema 键(snake_case) = 本处读取键 = 聚合键；body 写入键按 Exa 原生 camelCase
            val urls = params["urls"].asSearchStringList()?.takeIf { it.isNotEmpty() }
                ?: error("urls is required")
            val contentType = params["content_type"]?.jsonPrimitive?.contentOrNull
            val maxCharacters = params["max_characters"]?.jsonPrimitive?.contentOrNull?.toIntOrNull()
            val maxAgeHours = params["max_age_hours"]?.jsonPrimitive?.contentOrNull?.toIntOrNull()
            val summaryQuery = params["summary_query"]?.jsonPrimitive?.contentOrNull
            val extractLinks = params["extract_links"]?.jsonPrimitive?.contentOrNull?.toIntOrNull()

            val body = buildJsonObject {
                put("urls", buildJsonArray { urls.forEach { add(JsonPrimitive(it)) } })
                when (contentType) {
                    "highlights" -> put("highlights", buildJsonObject {
                        maxCharacters?.let { put("maxCharacters", it) }
                    })
                    "summary" -> put("summary", buildJsonObject {
                        summaryQuery?.takeIf(String::isNotBlank)?.let { put("query", it) }
                    })
                    // text 默认：未传 max_characters 时用官方默认 true，否则用对象控制体积
                    else -> if (maxCharacters != null) {
                        put("text", buildJsonObject { put("maxCharacters", maxCharacters) })
                    } else {
                        put("text", JsonPrimitive(true))
                    }
                }
                maxAgeHours?.let { put("maxAgeHours", it) }
                extractLinks?.takeIf { it > 0 }?.let { put("extras", buildJsonObject { put("links", it) }) }
            }
            val apiKey = keyRoulette.next(serviceOptions.apiKey, serviceOptions.id.toString())

            val request = Request.Builder()
                .url("https://api.exa.ai/contents")
                .post(json.encodeToString(body).toRequestBody("application/json".toMediaType()))
                .addHeader("Authorization", "Bearer $apiKey")
                .build()

            val response = httpClient.newCall(request).execute()
            if (response.isSuccessful) {
                val bodyRaw = response.body.string()
                val data = runCatching {
                    json.decodeFromString<ExaData>(bodyRaw)
                }.onFailure {
                    it.printStackTrace()
                    println(bodyRaw)
                    error("Failed to decode response: $bodyRaw")
                }.getOrThrow()

                // 成功项 + 失败项：statuses 是 HTTP 200 内上报的单 URL 失败，不能静默丢弃
                val entries = buildList {
                    data.results.forEach { item ->
                        add(
                            ScrapedResultUrl(
                                url = item.url,
                                content = when (contentType) {
                                    "highlights" -> item.highlights?.joinToString("\n").orEmpty()
                                    "summary" -> item.summary.orEmpty()
                                    else -> item.text.orEmpty()
                                },
                                images = item.image?.takeIf { it.isNotBlank() }?.let { listOf(it) } ?: emptyList(),
                                publishedDate = item.publishedDate,
                                author = item.author,
                                metadata = ScrapedResultMetadata(
                                    title = item.title,
                                    favicon = item.favicon,
                                ),
                            )
                        )
                    }
                    data.statuses.filter { it.status == "error" }.forEach { status ->
                        val tag = status.error?.tag ?: "CRAWL_UNKNOWN_ERROR"
                        val code = status.error?.httpStatusCode?.let { " $it" } ?: ""
                        add(ScrapedResultUrl(url = status.id, error = "$tag$code"))
                    }
                }

                return@withContext Result.success(ScrapedResult(urls = entries))
            } else {
                val bodyText = response.body.string()
                val detail = runCatching {
                    json.parseToJsonElement(bodyText).jsonObject["detail"]?.jsonPrimitive?.contentOrNull
                }.getOrNull()
                println(bodyText)
                error(detail ?: "response failed #${response.code}: $bodyText")
            }
        }
    }

    @Serializable
    data class ExaData(
        @SerialName("requestId")
        val requestId: String? = null,
        @SerialName("autopromptString")
        val autopromptString: String? = null,
        @SerialName("resolvedSearchType")
        val resolvedSearchType: String? = null,
        @SerialName("results")
        val results: List<ExaResult>,
        @SerialName("output")
        val output: ExaOutput? = null,
        @SerialName("statuses")
        val statuses: List<ExaStatus> = emptyList(),
    )

    @Serializable
    data class ExaOutput(
        @SerialName("content")
        val content: JsonElement? = null,
        @SerialName("grounding")
        val grounding: List<ExaGrounding> = emptyList(),
    )

    @Serializable
    data class ExaGrounding(
        @SerialName("field")
        val field: String? = null,
        @SerialName("citations")
        val citations: List<ExaCitation> = emptyList(),
        @SerialName("confidence")
        val confidence: String? = null,
    )

    @Serializable
    data class ExaCitation(
        @SerialName("url")
        val url: String,
        @SerialName("title")
        val title: String,
    )

    @Serializable
    data class ExaResult(
        @SerialName("id")
        val id: String,
        @SerialName("title")
        val title: String,
        @SerialName("url")
        val url: String,
        @SerialName("publishedDate")
        val publishedDate: String?,
        @SerialName("author")
        val author: String?,
        @SerialName("text")
        val text: String? = null,
        @SerialName("image")
        val image: String? = null,
        @SerialName("highlights")
        val highlights: List<String>? = null,
        @SerialName("summary")
        val summary: String? = null,
        @SerialName("favicon")
        val favicon: String? = null,
    )

    /** /contents 单 URL 抓取状态：status=error 表示该 URL 失败（整体请求仍 200），必须检查 */
    @Serializable
    data class ExaStatus(
        @SerialName("id")
        val id: String,
        @SerialName("status")
        val status: String,
        @SerialName("source")
        val source: String? = null,
        @SerialName("error")
        val error: ExaStatusError? = null,
    )

    @Serializable
    data class ExaStatusError(
        @SerialName("tag")
        val tag: String? = null,
        @SerialName("httpStatusCode")
        val httpStatusCode: Int? = null,
    )

    /** deep 模式综合答案提取：outputSchema 时 content 为 {"answer": ...}，旧模式为纯字符串 */
    private fun extractOutputAnswer(output: ExaOutput?): String? {
        val content = output?.content ?: return null
        return when (content) {
            is JsonPrimitive -> content.contentOrNull
            is JsonObject -> content["answer"]?.jsonPrimitive?.contentOrNull
            else -> null
        }
    }
}
