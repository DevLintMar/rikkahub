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
                    put("description", "text = full page content; highlights = key excerpts only (saves tokens)")
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
        serviceOptions: SearchServiceOptions.ExaOptions
    ): Result<SearchResult> = withContext(Dispatchers.IO) {
        runCatching {
            val query = params["query"]?.jsonPrimitive?.content ?: error("query is required")
            val useHighlights = params["content_type"]?.jsonPrimitive?.contentOrNull == "highlights"
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
                val contentType = params["content_type"]?.jsonPrimitive?.contentOrNull
                put("contents", buildJsonObject {
                    if (contentType == "highlights") {
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
            val url = params["url"]?.jsonPrimitive?.content ?: error("url is required")
            val body = buildJsonObject {
                put("urls", buildJsonArray {
                    add(JsonPrimitive(url))
                })
                put("text", JsonPrimitive(true))
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

                return@withContext Result.success(
                    ScrapedResult(
                        urls = data.results.map {
                            ScrapedResultUrl(
                                url = it.url,
                                content = it.text ?: "",
                                metadata = ScrapedResultMetadata(
                                    title = it.title,
                                )
                            )
                        }
                    )
                )
            } else {
                println(response.body.string())
                error("response failed #${response.code}")
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
