package me.rerere.search

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withLink
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
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.ai.core.InputSchema
import me.rerere.search.SearchResult.SearchResultItem
import me.rerere.search.SearchService.Companion.httpClient
import me.rerere.search.SearchService.Companion.json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

object MetasoSearchService : SearchService<SearchServiceOptions.MetasoOptions> {
    override val name: String = "Metaso"

    @Composable
    override fun Description() {
        Text(buildAnnotatedString {
            append("秘塔搜索: ")
            withLink(LinkAnnotation.Url("https://metaso.cn/")) {
                append("https://metaso.cn/")
            }
        })
    }

    override fun parameters(options: SearchServiceOptions.MetasoOptions): InputSchema? =
        InputSchema.Obj(
            properties = buildJsonObject {
                put("query", buildJsonObject {
                    put("type", "string")
                    put("description", "search keyword")
                })
                put("scope", buildJsonObject {
                    put("type", "string")
                    put("description", "search scope, default webpage")
                    put("enum", buildJsonArray {
                        add("webpage")
                        add("document")
                        add("scholar")
                        add("podcast")
                        add("video")
                        add("image")
                    })
                })
                put("include_raw_content", buildJsonObject {
                    put("type", "boolean")
                    put("description", "include full page text for each result (webpage scope only, costs more credits)")
                })
                put("concise_snippet", buildJsonObject {
                    put("type", "boolean")
                    put("description", "return concise matched snippets")
                })
            },
            required = listOf("query")
        )

    override fun scrapingParameters(options: SearchServiceOptions.MetasoOptions): InputSchema? = null

    override suspend fun search(
        params: JsonObject,
        commonOptions: SearchCommonOptions,
        serviceOptions: SearchServiceOptions.MetasoOptions
    ): Result<SearchResult> = withContext(Dispatchers.IO) {
        runCatching {
            val query = params["query"]?.jsonPrimitive?.content ?: error("query is required")

            val scope = params["scope"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() } ?: "webpage"

            val requestBody = buildJsonObject {
                put("q", JsonPrimitive(query))
                put("scope", JsonPrimitive(scope))
                put("size", JsonPrimitive(commonOptions.resultSize))
                put("includeSummary", JsonPrimitive(false))
                if (params["include_raw_content"]?.jsonPrimitive?.booleanOrNull == true) {
                    put("includeRawContent", JsonPrimitive(true))
                }
                if (params["concise_snippet"]?.jsonPrimitive?.booleanOrNull == true) {
                    put("conciseSnippet", JsonPrimitive(true))
                }
            }

            val request = Request.Builder()
                .url("https://metaso.cn/api/v1/search")
                .post(requestBody.toString().toRequestBody("application/json".toMediaType()))
                .addHeader("Authorization", "Bearer ${serviceOptions.apiKey}")
                .addHeader("Accept", "application/json")
                .addHeader("Content-Type", "application/json")
                .build()

            val response = httpClient.newCall(request).await()
            if (response.isSuccessful) {
                val bodyRaw = response.body?.string() ?: error("Failed to get response body")
                val searchResponse = runCatching {
                    json.decodeFromString<MetasoSearchResponse>(bodyRaw)
                }.onFailure {
                    it.printStackTrace()
                    println("Failed to decode Metaso response: $bodyRaw")
                    error("Failed to decode response: $bodyRaw")
                }.getOrThrow()

                val items = if (searchResponse.webpages.isNotEmpty()) {
                    searchResponse.webpages.map { webpage ->
                        SearchResultItem(
                            title = webpage.title,
                            url = webpage.link,
                            text = webpage.rawContent?.takeIf { it.isNotBlank() } ?: webpage.snippet ?: ""
                        )
                    }
                } else {
                    searchResponse.podcasts.map { podcast ->
                        SearchResultItem(
                            title = podcast.title,
                            url = podcast.link,
                            text = podcast.snippet ?: ""
                        )
                    }
                }

                return@withContext Result.success(
                    SearchResult(items = items)
                )
            } else {
                val errorBody = response.body?.string()
                println("Metaso search failed with code ${response.code}: $errorBody")
                error("Search request failed with code ${response.code}: $errorBody")
            }
        }
    }

    override suspend fun scrape(
        params: JsonObject,
        commonOptions: SearchCommonOptions,
        serviceOptions: SearchServiceOptions.MetasoOptions
    ): Result<ScrapedResult> {
        return Result.failure(Exception("Scraping is not supported for Metaso"))
    }

    @Serializable
    data class MetasoSearchResponse(
        @SerialName("credits")
        val credits: Int? = null,
        @SerialName("searchParameters")
        val searchParameters: MetasoSearchParameters,
        @SerialName("webpages")
        val webpages: List<MetasoWebpage> = emptyList(),
        @SerialName("total")
        val total: Int? = null,
        @SerialName("podcasts")
        val podcasts: List<MetasoPodcast> = emptyList(),
    )

    @Serializable
    data class MetasoSearchParameters(
        @SerialName("q")
        val q: String = "",
        @SerialName("scope")
        val scope: String = "",
        @SerialName("size")
        val size: Int = 0,
    )

    @Serializable
    data class MetasoWebpage(
        @SerialName("title")
        val title: String,
        @SerialName("link")
        val link: String,
        @SerialName("score")
        val score: String? = null,
        @SerialName("snippet")
        val snippet: String? = null,
        @SerialName("summary")
        val summary: String? = null,
        @SerialName("position")
        val position: Int? = null,
        @SerialName("date")
        val date: String? = null,
        @SerialName("rawContent")
        val rawContent: String? = null,
        @SerialName("authors")
        val authors: List<String> = emptyList(),
    )

    @Serializable
    data class MetasoPodcast(
        val title: String,
        val link: String,
        @SerialName("snippet")
        val snippet: String? = null,
        @SerialName("date")
        val date: String? = null,
    )
}
