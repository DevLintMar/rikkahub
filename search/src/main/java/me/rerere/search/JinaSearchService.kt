package me.rerere.search

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
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.ai.core.InputSchema
import me.rerere.search.SearchResult.SearchResultItem
import me.rerere.search.SearchService.Companion.httpClient
import me.rerere.search.SearchService.Companion.json
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

object JinaSearchService : SearchService<SearchServiceOptions.JinaOptions> {
    private const val DEFAULT_SEARCH_URL = "https://s.jina.ai/"
    private const val DEFAULT_SCRAPE_URL = "https://r.jina.ai/"

    override val name: String = "Jina"

    @Composable
    override fun Description() {
        val urlHandler = LocalUriHandler.current
        TextButton(
            onClick = {
                urlHandler.openUri("https://jina.ai/")
            }
        ) {
            Text(stringResource(R.string.click_to_get_api_key))
        }
    }

    override fun parameters(options: SearchServiceOptions.JinaOptions): InputSchema? =
        InputSchema.Obj(
            properties = buildJsonObject {
                put("query", buildJsonObject {
                    put("type", "string")
                    put("description", "search keyword")
                })
                put("gl", buildJsonObject {
                    put("type", "string")
                    put("description", "two-letter country code for search localization (e.g. 'jp')")
                })
                put("hl", buildJsonObject {
                    put("type", "string")
                    put("description", "two-letter language code (e.g. 'ja')")
                })
            },
            required = listOf("query")
        )

    override fun scrapingParameters(options: SearchServiceOptions.JinaOptions): InputSchema? =
        InputSchema.Obj(
            properties = buildJsonObject {
                put("url", buildJsonObject {
                    put("type", "string")
                    put("description", "url to scrape (single URL)")
                })
                put("respond_with", buildJsonObject {
                    put("type", "string")
                    put("description", "content format: content = readability-extracted text (default), markdown = raw markdown, html = raw HTML, text = plain body text")
                    put("enum", buildJsonArray {
                        add("content")
                        add("markdown")
                        add("html")
                        add("text")
                    })
                })
                put("target_selector", buildJsonObject {
                    put("type", "string")
                    put("description", "CSS selector to only extract content from matching elements (e.g. 'article', '.main-content')")
                })
                put("timeout", buildJsonObject {
                    put("type", "integer")
                    put("description", "max seconds to wait for page load (max 180)")
                })
                put("engine", buildJsonObject {
                    put("type", "string")
                    put("description", "auto (default) = hybrid; browser = headless Chrome with JS rendering; direct = lightweight no-JS")
                    put("enum", buildJsonArray {
                        add("auto")
                        add("browser")
                        add("direct")
                    })
                })
                put("no_cache", buildJsonObject {
                    put("type", "boolean")
                    put("description", "bypass internal cache for fresh content (costs more quota)")
                })
                put("retain_links", buildJsonObject {
                    put("type", "string")
                    put("description", "link retention mode: all (default), none, text (anchor text only), gpt-oss (numbered citations + footnote list)")
                    put("enum", buildJsonArray {
                        add("all")
                        add("none")
                        add("text")
                        add("gpt-oss")
                    })
                })
                put("max_tokens", buildJsonObject {
                    put("type", "integer")
                    put("description", "truncate response to at most N tokens (min 500), a guardrail for long pages")
                })
            },
            required = listOf("url")
        )

    override suspend fun search(
        params: JsonObject,
        commonOptions: SearchCommonOptions,
        serviceOptions: SearchServiceOptions.JinaOptions
    ): Result<SearchResult> = withContext(Dispatchers.IO) {
        runCatching {
            val query = params["query"]?.jsonPrimitive?.content ?: error("query is required")

            val body = buildJsonObject {
                put("q", query)
                put("num", JsonPrimitive(commonOptions.resultSize))
                params["gl"]?.jsonPrimitive?.contentOrNull?.takeIf(String::isNotBlank)?.let { put("gl", it) }
                params["hl"]?.jsonPrimitive?.contentOrNull?.takeIf(String::isNotBlank)?.let { put("hl", it) }
            }

            val searchUrl = serviceOptions.searchUrl.ifBlank { DEFAULT_SEARCH_URL }

            val request = Request.Builder()
                .url(searchUrl)
                .post(body.toString().toRequestBody())
                .addHeader("Authorization", "Bearer ${serviceOptions.apiKey}")
                .addHeader("Accept", "application/json")
                .addHeader("Content-Type", "application/json")
                .build()

            val response = httpClient.newCall(request).await()
            if (response.isSuccessful) {
                val responseData = response.body.string().let {
                    json.decodeFromString<JinaSearchResponse>(it)
                }

                return@withContext Result.success(
                    SearchResult(
                        items = responseData.data.take(commonOptions.resultSize).map {
                            SearchResultItem(
                                title = it.title,
                                url = it.url,
                                text = it.description
                            )
                        }
                    )
                )
            } else {
                error("response failed #${response.code}")
            }
        }
    }

    override suspend fun scrape(
        params: JsonObject,
        commonOptions: SearchCommonOptions,
        serviceOptions: SearchServiceOptions.JinaOptions
    ): Result<ScrapedResult> = withContext(Dispatchers.IO) {
        runCatching {
            // 三键一致性：schema 键(snake_case) = 本处读取键；Jina 参数全部走 X-* 请求头（不支持 query 参数）
            val url = params["urls"].asSearchStringList()?.firstOrNull()
                ?: params["url"]?.jsonPrimitive?.contentOrNull
                ?: error("url is required")

            val body = buildJsonObject {
                put("url", url)
            }

            val scrapeUrl = serviceOptions.scrapeUrl.ifBlank { DEFAULT_SCRAPE_URL }

            val request = Request.Builder()
                .url(scrapeUrl)
                .post(body.toString().toRequestBody())
                .addHeader("Accept", "application/json")
                .addHeader("Content-Type", "application/json")
                .apply {
                    // 修 bug：免 key 时不要发空 Bearer 头（可能被当作非法凭据/触发异常限流路径）
                    if (serviceOptions.apiKey.isNotBlank()) {
                        addHeader("Authorization", "Bearer ${serviceOptions.apiKey}")
                    }
                    // 未传 respond_with 时不加该头，走官方默认 content（readability 提取正文）
                    params["respond_with"]?.jsonPrimitive?.contentOrNull?.takeIf(String::isNotBlank)?.let {
                        addHeader("X-Respond-With", it)
                    }
                    params["target_selector"]?.jsonPrimitive?.contentOrNull?.takeIf(String::isNotBlank)?.let {
                        addHeader("X-Target-Selector", it)
                    }
                    params["timeout"]?.jsonPrimitive?.contentOrNull?.toIntOrNull()?.let {
                        addHeader("X-Timeout", it.toString())
                    }
                    params["engine"]?.jsonPrimitive?.contentOrNull?.takeIf(String::isNotBlank)?.let {
                        addHeader("X-Engine", it)
                    }
                    if (params["no_cache"]?.jsonPrimitive?.booleanOrNull == true) {
                        addHeader("X-No-Cache", "true")
                    }
                    params["retain_links"]?.jsonPrimitive?.contentOrNull?.takeIf(String::isNotBlank)?.let {
                        addHeader("X-Retain-Links", it)
                    }
                    params["max_tokens"]?.jsonPrimitive?.contentOrNull?.toIntOrNull()?.let {
                        addHeader("X-Max-Tokens", it.toString())
                    }
                }
                .build()

            val response = httpClient.newCall(request).await()
            if (!response.isSuccessful) {
                // 修 bug：解析官方错误 JSON（name/readableMessage），AI 拿具体失败原因
                val bodyText = response.body.string()
                val officialError = runCatching {
                    val obj = json.parseToJsonElement(bodyText).jsonObject
                    val name = obj["name"]?.jsonPrimitive?.contentOrNull
                    val message = obj["readableMessage"]?.jsonPrimitive?.contentOrNull
                        ?: obj["message"]?.jsonPrimitive?.contentOrNull
                    when {
                        name != null && message != null -> "$name: $message"
                        name != null -> name
                        else -> null
                    }
                }.getOrNull()
                error(officialError ?: "response failed for url $url #${response.code}: $bodyText")
            }
            val responseData = response.body.string().let {
                json.decodeFromString<JinaScrapeResponse>(it)
            }

            ScrapedResult(
                urls = listOf(
                    ScrapedResultUrl(
                        url = responseData.data.url,
                        content = responseData.data.content,
                        publishedDate = responseData.data.publishedTime,
                        metadata = ScrapedResultMetadata(
                            title = responseData.data.title,
                            description = responseData.data.description
                        )
                    )
                )
            )
        }
    }

    @Serializable
    data class JinaSearchResponse(
        val code: Int,
        val status: Int,
        val data: List<JinaSearchResultItem>
    )

    @Serializable
    data class JinaSearchResultItem(
        val title: String,
        val url: String,
        val description: String,
        val content: String = "",
        val usage: JinaUsage? = null
    )

    @Serializable
    data class JinaUsage(
        val tokens: Int
    )

    @Serializable
    data class JinaScrapeResponse(
        val code: Int,
        val status: Int,
        val data: JinaScrapeData
    )

    @Serializable
    data class JinaScrapeData(
        val title: String,
        val description: String = "",
        val url: String,
        val content: String,
        val publishedTime: String? = null,
        val usage: JinaUsage? = null
    )
}
