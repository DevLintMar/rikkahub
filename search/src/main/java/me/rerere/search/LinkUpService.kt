package me.rerere.search

import android.util.Log
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
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
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

private const val TAG = "LinkUpService"

object LinkUpService : SearchService<SearchServiceOptions.LinkUpOptions> {
    override val name: String = "LinkUp"

    @Composable
    override fun Description() {
        val urlHandler = LocalUriHandler.current
        TextButton(
            onClick = {
                urlHandler.openUri("https://www.linkup.so/")
            }
        ) {
            Text(stringResource(R.string.click_to_get_api_key))
        }
    }

    override fun parameters(options: SearchServiceOptions.LinkUpOptions): InputSchema? =
        InputSchema.Obj(
            properties = buildJsonObject {
                put("query", buildJsonObject {
                    put("type", "string")
                    put("description", "search keyword")
                })
                put("from_date", buildJsonObject {
                    put("type", "string")
                    put("description", "ISO date YYYY-MM-DD")
                })
                put("to_date", buildJsonObject {
                    put("type", "string")
                    put("description", "ISO date YYYY-MM-DD")
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
            },
            required = listOf("query")
        )

    override fun scrapingParameters(options: SearchServiceOptions.LinkUpOptions): InputSchema? =
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
        serviceOptions: SearchServiceOptions.LinkUpOptions
    ): Result<SearchResult> = withContext(Dispatchers.IO) {
        runCatching {
            val query = params["query"]?.jsonPrimitive?.content ?: error("query is required")
            val body = buildJsonObject {
                put("q", JsonPrimitive(query))
                put("maxResults", JsonPrimitive(commonOptions.resultSize))
                put("depth", JsonPrimitive(serviceOptions.depth))
                put("outputType", JsonPrimitive("sourcedAnswer"))
                put("includeImages", JsonPrimitive("false"))
                params["fromDate"]?.jsonPrimitive?.contentOrNull?.takeIf(String::isNotBlank)?.let { put("fromDate", it) }
                params["toDate"]?.jsonPrimitive?.contentOrNull?.takeIf(String::isNotBlank)?.let { put("toDate", it) }
                params["includeDomains"].asSearchStringList()?.takeIf { it.isNotEmpty() }?.let { domains ->
                    put("includeDomains", buildJsonArray {
                        domains.forEach { add(JsonPrimitive(it)) }
                    })
                }
                params["excludeDomains"].asSearchStringList()?.takeIf { it.isNotEmpty() }?.let { domains ->
                    put("excludeDomains", buildJsonArray {
                        domains.forEach { add(JsonPrimitive(it)) }
                    })
                }
            }
            val apiKey = keyRoulette.next(serviceOptions.apiKey, serviceOptions.id.toString())

            val request = Request.Builder()
                .url("https://api.linkup.so/v1/search")
                .post(body.toString().toRequestBody())
                .addHeader("Authorization", "Bearer $apiKey")
                .addHeader("Content-Type", "application/json")
                .build()

            Log.i(TAG, "search: $query")

            val response = httpClient.newCall(request).await()
            if (response.isSuccessful) {
                val responseBody = response.body.string().let {
                    json.decodeFromString<LinkUpSearchResponse>(it)
                }

                return@withContext Result.success(
                    SearchResult(
                        answer = responseBody.answer,
                        items = responseBody.sources.take(commonOptions.resultSize).map {
                            SearchResultItem(
                                title = it.name,
                                url = it.url,
                                text = it.snippet ?: ""
                            )
                        }
                    )
                )
            } else {
                error("response failed #${response.code}: ${response.body?.string()}")
            }
        }
    }

    override suspend fun scrape(
        params: JsonObject,
        commonOptions: SearchCommonOptions,
        serviceOptions: SearchServiceOptions.LinkUpOptions
    ): Result<ScrapedResult> = withContext(Dispatchers.IO) {
        runCatching {
            val url = params["url"]?.jsonPrimitive?.content ?: error("url is required")
            val body = buildJsonObject {
                put("url", JsonPrimitive(url))
                put("includeRawHtml", JsonPrimitive(false))
                put("renderJs", JsonPrimitive(false))
                put("extractImages", JsonPrimitive(false))
            }
            val apiKey = keyRoulette.next(serviceOptions.apiKey, serviceOptions.id.toString())

            val request = Request.Builder()
                .url("https://api.linkup.so/v1/fetch")
                .post(body.toString().toRequestBody())
                .addHeader("Authorization", "Bearer $apiKey")
                .addHeader("Content-Type", "application/json")
                .build()

            val response = httpClient.newCall(request).await()
            if (response.isSuccessful) {
                val responseBody = response.body.string().let {
                    json.decodeFromString<LinkUpFetchResponse>(it)
                }

                return@withContext Result.success(
                    ScrapedResult(
                        urls = listOf(
                            ScrapedResultUrl(
                                url = url,
                                content = responseBody.markdown
                            )
                        )
                    )
                )
            } else {
                error("response failed #${response.code}: ${response.body?.string()}")
            }
        }
    }

    @Serializable
    data class LinkUpSearchResponse(
        val answer: String? = null,
        val sources: List<Source>
    )

    @Serializable
    data class Source(
        val name: String,
        val url: String,
        val snippet: String? = null,
        val favicon: String? = null
    )

    @Serializable
    data class LinkUpFetchResponse(
        val markdown: String
    )
}
