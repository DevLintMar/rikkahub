package me.rerere.rikkahub.data.ai.tools

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.datastore.selectedSearchServices
import me.rerere.rikkahub.utils.JsonInstantPretty
import me.rerere.rikkahub.utils.toLocalString
import me.rerere.search.SearchService
import me.rerere.search.retryOnQuota
import java.time.LocalDate
import kotlin.uuid.Uuid

private const val MAX_SCRAPE_TEXT_CHARS = 32 * 1024

fun createSearchTools(settings: Settings): Set<Tool> {
    val selected = settings.selectedSearchServices
    val selectedByName = selected.associateBy { it.displayName }
    val scrapeCapable = selected.filter {
        SearchService.getService(it).scrapingParameters(it) != null
    }
    val scrapeCapableByName = scrapeCapable.associateBy { it.displayName }

    // 渠道特有参数：从各已选渠道 parameters() 提取（排除通用参数 query/service/num_results），按参数名聚合。
    // execute 会把整个 args.jsonObject 透传给 service.search，各渠道自行读取认识的键，因此同名参数无运行时冲突。
    val commonSearchParams = setOf("query", "service", "num_results")
    val perServiceExtra = LinkedHashMap<String, JsonObject>()
    selected.forEach { options ->
        val schema = SearchService.getService(options).parameters(options)
        (schema as? InputSchema.Obj)?.properties?.forEach { (key, value) ->
            if (key in commonSearchParams) return@forEach
            val desc = value.jsonObject["description"]?.jsonPrimitive?.contentOrNull.orEmpty()
            val serviceLabel = "[${options.displayName}]"
            val existing = perServiceExtra[key]
            if (existing == null) {
                val tagged = value.jsonObject.toMutableMap().apply {
                    this["description"] = JsonPrimitive("$serviceLabel $desc".trim())
                }
                perServiceExtra[key] = JsonObject(tagged)
            } else {
                val old = existing["description"]?.jsonPrimitive?.contentOrNull.orEmpty()
                val merged = existing.toMutableMap().apply {
                    this["description"] = JsonPrimitive("$old / $serviceLabel $desc".trim())
                }
                perServiceExtra[key] = JsonObject(merged)
            }
        }
    }

    return buildSet {
        add(
            Tool(
                name = "search_web",
                description = buildString {
                    appendLine("Search the web for up-to-date or specific information.")
                    appendLine("Use this when the user asks for the latest news, current facts, or needs verification.")
                    appendLine("Available search services: ${selected.joinToString(", ") { it.displayName }}.")
                    if (perServiceExtra.isNotEmpty()) {
                        appendLine("Each search service supports its own extra parameters, marked with [Service] in the parameter descriptions below.")
                    }
                    appendLine("Choose one via the `service` parameter (must be one of the listed values);")
                    appendLine("`num_results` controls how many results to return (default: 10).")
                    appendLine("Today is ${LocalDate.now().toLocalString(true)}.")
                    appendLine()
                    appendLine("Response format:")
                    appendLine("- items[].id (short id), title, url, text")
                    appendLine("- images[]: image urls related to the query (may be empty)")
                    appendLine()
                    appendLine("Citations:")
                    appendLine("- After using results, add `[citation,domain](id)` after the sentence.")
                    appendLine("- Multiple citations are allowed.")
                    appendLine("- If no results are cited, omit citations.")
                    appendLine()
                    appendLine("Images:")
                    appendLine("- When images help the user understand the answer, embed relevant ones using Markdown: `![](url)`.")
                    appendLine("- Embed 2 to 4 images, and only use urls from `images[]` (never fabricate or alter urls).")
                    appendLine("- Usually place the images at the very beginning of your reply; skip them entirely if none are relevant.")
                    appendLine()
                    appendLine("Example:")
                    appendLine("The capital of France is Paris. [citation,example.com](abc123)")
                    appendLine("The population is about 2.1 million. [citation,example.com](abc123) [citation,example2.com](def456)")
                }.trimEnd(),
                parameters = {
                    InputSchema.Obj(
                        properties = buildJsonObject {
                            put("query", buildJsonObject {
                                put("type", "string")
                                put("description", "Search keywords to look up")
                            })
                            put("service", buildJsonObject {
                                put("type", "string")
                                put("description", "Search service to use; one of the listed available services")
                                put("enum", buildJsonArray { selected.forEach { add(it.displayName) } })
                            })
                            put("num_results", buildJsonObject {
                                put("type", "integer")
                                put("description", "Number of results to return (default: 10)")
                            })
                            perServiceExtra.forEach { (key, value) -> put(key, value) }
                        },
                        required = listOf("query", "service")
                    )
                },
                execute = { args ->
                    val options = args.jsonObject["service"]?.jsonPrimitive?.contentOrNull
                        ?.let { name -> selectedByName[name] }
                        ?: error("service is required and must be one of: ${selected.joinToString(", ") { it.displayName }}")
                    val service = SearchService.getService(options)
                    val numResults = (args.jsonObject["num_results"]?.jsonPrimitive?.intOrNull ?: 10).coerceIn(1, 50)
                    val commonOptions = settings.searchCommonOptions.copy(resultSize = numResults)
                    val result = retryOnQuota(options) { o ->
                        service.search(args.jsonObject, commonOptions, o)
                    }
                    val results =
                        JsonInstantPretty.encodeToJsonElement(result.getOrThrow()).jsonObject.let { json ->
                            val map = json.toMutableMap()
                            map["items"] =
                                JsonArray(map["items"]!!.jsonArray.mapIndexed { index, item ->
                                    JsonObject(item.jsonObject.toMutableMap().apply {
                                        put("id", JsonPrimitive(Uuid.random().toString().take(6)))
                                        put("index", JsonPrimitive(index + 1))
                                    })
                                })
                            JsonObject(map)
                        }
                    val query = args.jsonObject["query"]?.jsonPrimitive?.contentOrNull
                    listOf(
                        UIMessagePart.Text(
                            buildJsonObject {
                                put("type", JsonPrimitive("web_search"))
                                query?.let { q -> put("query", JsonPrimitive(q)) }
                                put("answer", results["answer"] ?: JsonNull)
                                put("items", results["items"] ?: JsonArray(emptyList()))
                                put("images", results["images"] ?: JsonArray(emptyList()))
                            }.toString()
                        )
                    )
                }
            )
        )

        if (scrapeCapable.isNotEmpty()) {
            add(
                Tool(
                    name = "scrape_web",
                    description = """
                        Scrape a URL for detailed page content.
                        Use this when the user requests content from a specific page or when search snippets are insufficient.
                        Available scraping services: ${scrapeCapable.joinToString(", ") { it.displayName }}.
                        """.trimIndent(),
                    parameters = {
                        InputSchema.Obj(
                            properties = buildJsonObject {
                                put("url", buildJsonObject {
                                    put("type", "string")
                                    put("description", "URL to scrape")
                                })
                                put("service", buildJsonObject {
                                    put("type", "string")
                                    put("description", "Search service to use for scraping")
                                    put("enum", buildJsonArray { scrapeCapable.forEach { add(it.displayName) } })
                                })
                            },
                            required = listOf("url")
                        )
                    },
                    execute = { args ->
                        val options = args.jsonObject["service"]?.jsonPrimitive?.contentOrNull
                            ?.let { name -> scrapeCapableByName[name] }
                            ?: scrapeCapable.first()
                        val service = SearchService.getService(options)
                        // 归一化 url/urls 参数：CustomJs 需要 urls(数组)，其余 scrape 服务读 url，多余的键会被忽略
                        val scrapeUrl = args.jsonObject["url"]?.jsonPrimitive?.contentOrNull ?: ""
                        val scrapeParams = buildJsonObject {
                            put("url", JsonPrimitive(scrapeUrl))
                            put("urls", buildJsonArray { add(scrapeUrl) })
                        }
                        val result = retryOnQuota(options) { o ->
                            service.scrape(scrapeParams, settings.searchCommonOptions, o)
                        }
                        val payload = JsonInstantPretty.encodeToJsonElement(result.getOrThrow()).jsonObject
                        val urls = payload["urls"]?.jsonArray.orEmpty()
                        val url = args.jsonObject["url"]?.jsonPrimitive?.contentOrNull
                            ?: urls.firstOrNull()?.jsonObject?.get("url")?.jsonPrimitive?.contentOrNull
                            ?: ""
                        // 聚合所有 URL 的正文 (scrape 服务支持一次传入多个 URL), 避免只返回第一个 URL 的内容
                        val fullText = urls.joinToString("\n\n") { entry ->
                            entry.jsonObject["content"]?.jsonPrimitive?.contentOrNull ?: ""
                        }
                        val totalChars = fullText.length
                        val truncated = totalChars > MAX_SCRAPE_TEXT_CHARS
                        val clippedText = if (truncated) fullText.take(MAX_SCRAPE_TEXT_CHARS) else fullText
                        listOf(
                            UIMessagePart.Text(
                                buildJsonObject {
                                    put("type", JsonPrimitive("web_fetch"))
                                    put("url", JsonPrimitive(url))
                                    put("text", JsonPrimitive(clippedText))
                                    put("truncated", JsonPrimitive(truncated))
                                    put("totalChars", JsonPrimitive(totalChars))
                                }.toString()
                            )
                        )
                    }
                )
            )
        }
    }
}
