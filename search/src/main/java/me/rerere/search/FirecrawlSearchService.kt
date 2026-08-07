package me.rerere.search

import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.ai.core.InputSchema
import me.rerere.search.SearchResult.SearchResultItem
import me.rerere.search.SearchService.Companion.httpClient
import me.rerere.search.SearchService.Companion.json
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

object FirecrawlSearchService : SearchService<SearchServiceOptions.FirecrawlOptions> {
    override val name: String = "Firecrawl"

    @Composable
    override fun Description() {
        val urlHandler = LocalUriHandler.current
        TextButton(
            onClick = {
                urlHandler.openUri("https://docs.firecrawl.dev/features/search")
            }
        ) {
            Text(stringResource(R.string.click_to_get_api_key))
        }
    }

    override fun parameters(options: SearchServiceOptions.FirecrawlOptions): InputSchema? =
        InputSchema.Obj(
            properties = buildJsonObject {
                put("query", buildJsonObject {
                    put("type", "string")
                    put("description", "Search query string")
                })
                put("sources", buildJsonObject {
                    put("type", "array")
                    put("description", "Optional list of sources: `web`, `news`, `images`, default value is `web`")
                    put("items", buildJsonObject {
                        put("type", "string")
                    })
                })
                put("categories", buildJsonObject {
                    put("type", "array")
                    put(
                        "description",
                        "Optional list of categories to filter search results by: `github`, `research`, empty value means no filtering, default value is empty"
                    )
                    put("items", buildJsonObject {
                        put("type", "string")
                    })
                })
                put("include_domains", buildJsonObject {
                    put("type", "array")
                    put(
                        "description",
                        "Optional list of domains to include in search results, mutually exclusive with the other"
                    )
                    put("items", buildJsonObject {
                        put("type", "string")
                    })
                })
                put("exclude_domains", buildJsonObject {
                    put("type", "array")
                    put(
                        "description",
                        "Optional list of domains to exclude from search results, mutually exclusive with the other"
                    )
                    put("items", buildJsonObject {
                        put("type", "string")
                    })
                })
                put("tbs", buildJsonObject {
                    put("type", "string")
                    put("description", "time filter: qdr:h/d/w/m/y or cdr:1,cd_min:MM/DD/YYYY,cd_max:MM/DD/YYYY")
                })
                put("location", buildJsonObject {
                    put("type", "string")
                    put("description", "geo location, e.g. 'San Francisco,California,United States'")
                })
                put("country", buildJsonObject {
                    put("type", "string")
                    put("description", "two-letter country code, default US")
                })
            },
            required = listOf("query")
        )

    override fun scrapingParameters(options: SearchServiceOptions.FirecrawlOptions): InputSchema? =
        InputSchema.Obj(
            properties = buildJsonObject {
                put("url", buildJsonObject {
                    put("type", "string")
                    put("description", "URL to scrape (single URL)")
                })
                put("only_main_content", buildJsonObject {
                    put("type", "boolean")
                    put("description", "Only scrape main content (deterministic HTML-level filter, default is true)")
                })
                put("formats", buildJsonObject {
                    put("type", "array")
                    put("items", buildJsonObject {
                        put("type", "string")
                        put("enum", buildJsonArray {
                            add("markdown")
                            add("html")
                            add("rawHtml")
                            add("links")
                            add("images")
                            add("summary")
                        })
                    })
                    put("description", "output formats, default ['markdown']; rawHtml = untouched raw HTML, links = page link list, images = page images, summary = LLM summary")
                })
                put("wait_for", buildJsonObject {
                    put("type", "integer")
                    put("description", "extra milliseconds to wait before scraping (for JS-rendered pages), on top of smart wait")
                })
                put("timeout", buildJsonObject {
                    put("type", "integer")
                    put("description", "request timeout in milliseconds (min 1000, max 300000, default 60000)")
                })
                put("country", buildJsonObject {
                    put("type", "string")
                    put("description", "two-letter ISO country code for location simulation (e.g. 'JP', 'US')")
                })
                put("include_tags", buildJsonObject {
                    put("type", "array")
                    put("items", buildJsonObject { put("type", "string") })
                    put("description", "only keep these HTML tags in the output")
                })
                put("exclude_tags", buildJsonObject {
                    put("type", "array")
                    put("items", buildJsonObject { put("type", "string") })
                    put("description", "exclude these HTML tags from the output")
                })
                put("max_age", buildJsonObject {
                    put("type", "integer")
                    put("description", "cache age in milliseconds; 0 = force live scrape (fresh), omit = use 2-day cache when fresh enough")
                })
                put("remove_base64_images", buildJsonObject {
                    put("type", "boolean")
                    put("description", "remove base64 images from markdown output (URL replaced by placeholder, keeps alt text), default is true")
                })
            },
            required = listOf("url")
        )

    override suspend fun search(
        params: JsonObject,
        commonOptions: SearchCommonOptions,
        serviceOptions: SearchServiceOptions.FirecrawlOptions
    ): Result<SearchResult> = withContext(Dispatchers.IO) {
        runCatching {
            val query = params["query"]?.jsonPrimitive?.content ?: error("query is required")

            val sources = params["sources"].asStringList()
            val categories = params["categories"].asStringList()
            val includeDomains = params["include_domains"].asStringList()
            val excludeDomains = params["exclude_domains"].asStringList()
            val tbs = params["tbs"]?.jsonPrimitive?.contentOrNull
            val location = params["location"]?.jsonPrimitive?.contentOrNull
            val country = params["country"]?.jsonPrimitive?.contentOrNull

            val body = buildJsonObject {
                put("query", query)
                put("limit", commonOptions.resultSize)
                sources?.takeIf { it.isNotEmpty() }?.let { list ->
                    put("sources", buildJsonArray {
                        list.forEach { add(it) }
                    })
                }
                categories?.takeIf { it.isNotEmpty() }?.let { list ->
                    put("categories", buildJsonArray {
                        list.forEach { add(it) }
                    })
                }
                includeDomains?.takeIf { it.isNotEmpty() }?.let { list ->
                    put("includeDomains", buildJsonArray {
                        list.forEach { add(it) }
                    })
                }
                excludeDomains?.takeIf { it.isNotEmpty() }?.let { list ->
                    put("excludeDomains", buildJsonArray {
                        list.forEach { add(it) }
                    })
                }
                tbs?.takeIf { it.isNotBlank() }?.let { put("tbs", it) }
                location?.takeIf { it.isNotBlank() }?.let { put("location", it) }
                country?.takeIf { it.isNotBlank() }?.let { put("country", it) }
            }

            val request = Request.Builder()
                .url("https://api.firecrawl.dev/v2/search")
                .post(body.toString().toRequestBody())
                .addHeader("Content-Type", "application/json")
                .apply {
                    if (serviceOptions.apiKey.isNotBlank()) {
                        addHeader("Authorization", "Bearer ${serviceOptions.apiKey}")
                    }
                }
                .build()

            val response = httpClient.newCall(request).await()
            if (!response.isSuccessful) {
                error("response failed #${response.code}")
            }

            val bodyString = response.body.string()
            val payload = json.parseToJsonElement(bodyString).jsonObject
            val data = payload["data"]?.jsonObject ?: error("empty response data")
            val resultData = json.decodeFromJsonElement<FirecrawlSearchResultData>(data)
            val result = buildList {
                resultData.web?.forEach { item ->
                    add(SearchResultItem(title = item.title, url = item.url, text = item.description))
                }

                resultData.news?.forEach { item ->
                    add(
                        SearchResultItem(
                            title = item.title,
                            url = item.url,
                            text = """
                                ${item.snippet}
                                ${item.date}
                            """.trimIndent()
                        )
                    )
                }
            }
            SearchResult(
                items = result,
                images = resultData.images?.mapNotNull { it.imageUrl.takeIf(String::isNotBlank) } ?: emptyList()
            )
        }
    }

    override suspend fun scrape(
        params: JsonObject,
        commonOptions: SearchCommonOptions,
        serviceOptions: SearchServiceOptions.FirecrawlOptions
    ): Result<ScrapedResult> = withContext(Dispatchers.IO) {
        runCatching {
            // 三键一致性：schema 键(snake_case) = 本处读取键 = 聚合键；body 写入键按 Firecrawl 原生 camelCase
            val url = params["urls"].asSearchStringList()?.firstOrNull()
                ?: params["url"]?.jsonPrimitive?.contentOrNull
                ?: error("url is required")
            val onlyMainContent = params["only_main_content"]?.jsonPrimitive?.booleanOrNull ?: true
            val formats = params["formats"].asSearchStringList()?.takeIf { it.isNotEmpty() } ?: listOf("markdown")
            val waitFor = params["wait_for"]?.jsonPrimitive?.contentOrNull?.toIntOrNull()
            val timeout = params["timeout"]?.jsonPrimitive?.contentOrNull?.toIntOrNull()
            val country = params["country"]?.jsonPrimitive?.contentOrNull
            val includeTags = params["include_tags"].asSearchStringList()
            val excludeTags = params["exclude_tags"].asSearchStringList()
            val maxAge = params["max_age"]?.jsonPrimitive?.contentOrNull?.toLongOrNull()
            val removeBase64Images = params["remove_base64_images"]?.jsonPrimitive?.booleanOrNull

            val body = buildJsonObject {
                put("url", url)
                put("onlyMainContent", onlyMainContent)
                put("formats", buildJsonArray { formats.forEach { add(JsonPrimitive(it)) } })
                // 修 parsers 坑：不再发空数组（空数组使 PDF 以 base64 返回导致 markdown 为空），走官方默认 PDF→markdown
                waitFor?.takeIf { it > 0 }?.let { put("waitFor", it) }
                timeout?.let { put("timeout", it) }
                country?.takeIf(String::isNotBlank)?.let { put("location", buildJsonObject { put("country", it) }) }
                includeTags?.takeIf { it.isNotEmpty() }?.let { tags ->
                    put("includeTags", buildJsonArray { tags.forEach { add(JsonPrimitive(it)) } })
                }
                excludeTags?.takeIf { it.isNotEmpty() }?.let { tags ->
                    put("excludeTags", buildJsonArray { tags.forEach { add(JsonPrimitive(it)) } })
                }
                maxAge?.let { put("maxAge", it) }
                removeBase64Images?.let { put("removeBase64Images", it) }
            }

            val request = Request.Builder()
                .url("https://api.firecrawl.dev/v2/scrape")
                .post(body.toString().toRequestBody())
                .addHeader("Content-Type", "application/json")
                .apply {
                    if (serviceOptions.apiKey.isNotBlank()) {
                        addHeader("Authorization", "Bearer ${serviceOptions.apiKey}")
                    }
                }
                .build()

            val response = httpClient.newCall(request).await()
            if (!response.isSuccessful) {
                // 修错误体：解析 REST 的 {error, code}，AI 拿具体失败原因
                val bodyText = response.body.string()
                val apiError = runCatching {
                    val obj = json.parseToJsonElement(bodyText).jsonObject
                    val error = obj["error"]?.jsonPrimitive?.contentOrNull
                    val code = obj["code"]?.jsonPrimitive?.contentOrNull
                    when {
                        error != null && code != null -> "$code: $error"
                        error != null -> error
                        else -> null
                    }
                }.getOrNull()
                error(apiError ?: "response failed #${response.code}: $bodyText")
            }

            val bodyString = response.body.string()
            val payload = json.parseToJsonElement(bodyString).jsonObject

            val success = payload["success"]?.jsonPrimitive?.contentOrNull?.toBoolean() ?: false
            if (!success) {
                val apiError = payload["error"]?.jsonPrimitive?.contentOrNull
                error(apiError ?: "scrape request failed")
            }

            val data = payload["data"]?.jsonObject ?: error("empty response data")
            val metadata = data["metadata"]?.jsonObject
            // 修静默失败：页面级失败落在 metadata.error（HTTP 仍 200），必须带回而非丢弃
            val pageError = metadata?.get("error")?.jsonPrimitive?.contentOrNull
            val statusCode = metadata?.get("statusCode")?.jsonPrimitive?.contentOrNull?.toIntOrNull()
            val title = metadata?.get("title")?.jsonPrimitive?.contentOrNull
            val markdown = data["markdown"]?.jsonPrimitive?.contentOrNull ?: ""
            // images 格式返回形态不固定：兼容字符串数组与 {imageUrl,url} 对象数组
            val images = data["images"]?.jsonArray
                ?.mapNotNull { el ->
                    when (el) {
                        is JsonPrimitive -> el.contentOrNull
                        is JsonObject -> el["imageUrl"]?.jsonPrimitive?.contentOrNull
                            ?: el["url"]?.jsonPrimitive?.contentOrNull
                        else -> null
                    }
                }
                ?.filter { it.isNotBlank() }
                ?: emptyList()

            ScrapedResult(
                urls = listOf(
                    ScrapedResultUrl(
                        url = url,
                        content = markdown,
                        error = pageError,
                        images = images,
                        statusCode = statusCode,
                        metadata = ScrapedResultMetadata(
                            title = title,
                        )
                    )
                )
            )
        }
    }

    private fun JsonElement?.asStringList(): List<String>? {
        return when (this) {
            is JsonArray -> this.mapNotNull { element ->
                element.jsonPrimitive.contentOrNull?.takeIf { it.isNotBlank() }
            }

            is JsonPrimitive -> this.contentOrNull?.split(',')
                ?.mapNotNull { it.trim().takeIf(String::isNotEmpty) }

            else -> null
        }
    }
}

@Serializable
data class FirecrawlSearchResultWebItem(
    val url: String,
    val title: String,
    val description: String,
)

@Serializable
data class FirecrawlSearchResultNewsItem(
    val title: String,
    val url: String,
    val snippet: String,
    val date: String,
)

@Serializable
data class FirecrawlSearchResultImageItem(
    val title: String? = null,
    val imageUrl: String,
    val url: String? = null,
)

@Serializable
data class FirecrawlSearchResultData(
    val web: List<FirecrawlSearchResultWebItem>? = emptyList(),
    val news: List<FirecrawlSearchResultNewsItem>? = emptyList(),
    val images: List<FirecrawlSearchResultImageItem>? = emptyList(),
)



