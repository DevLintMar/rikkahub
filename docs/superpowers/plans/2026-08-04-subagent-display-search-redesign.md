# 子代理展示修正 + 搜索工具重做 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 修复子代理工具展示的 3 处 UI 问题，并重做搜索工具（多选服务 + service/num_results 参数 + 多 API key 自动重试），Android + Web 端同步。

**Architecture:** 4 个独立子任务：(1) `SubAgentToolUI` 标题/详情修正；(2) `ChatMessageToolStep` 报错位置；两者纯 UI 展示。搜索重做为数据模型驱动的多选改造：`Settings` 增加 `searchServiceSelectedIds: List<Uuid>` 迁移自旧单选 index，全链路消费者（设置页/聊天页/SearchTools/Web 路由/web-ui）同一 commit 原子替换；search 模块新增多 key 重试 helper，SearchTools 改为通用 `service`/`num_results` 参数并走重试包装。

**Tech Stack:** Kotlin/Jetpack Compose、kotlinx.serialization（`JsonInstant` ignoreUnknownKeys）、Room/DataStore、React Router 7 + TypeScript（web-ui）、CI-only 编译验证。

## Global Constraints

- **本机无编译器**：静态编写 + review，编译验证全靠 CI。流程：`git push origin master` THEN `gh workflow run nightly-build-debug.yml --repo DevLintMar/rikkahub --ref master`。
- **CI 判定铁律**：`gh run list --workflow nightly-build-debug.yml --repo DevLintMar/rikkahub --limit 1 --json databaseId,headSha,status,conclusion` 权威判定 + **核对 headSha == 目标 commit**；`gh run watch --exit-status` 可能误报。
- **Settings 模型原子改动**：改 `searchServiceSelected` 字段会破坏所有引用方编译，Task 4 必须在**一个 commit** 内完成模型 + 全部 Android 消费者（PreferencesStore/SearchPicker/ChatInput/ChatPage/SettingSearchPage/SearchTools 最小适配/WebDto/SettingsRoutes）。
- 图标只用已验证：`HugeIcons.AiSearch02`（已在本项目使用）；**库中无 AiSearch01**。
- 字符串双写 en+zh；本阶段无新增/删除字符串（工具描述在代码内，英文）。
- 文件删除走 `~/.claude/scripts/trash.sh`（回收站），禁止 rm。
- Kotlin 未使用 import 仅 warning 不阻断编译，但删除死代码保持整洁。

---

### Task 1: 子代理标题 + 详情 prompt（SubAgentToolUI）

**Files:**
- Modify: `app/src/main/java/me/rerere/rikkahub/ui/components/message/tools/BuiltinToolUIs.kt`

**Interfaces:**
- Produces: `SubAgentToolUI.title(context)` loading 期（content=null）返回基础标签；`SubAgentToolUI.Preview` 在 description 后渲染 prompt。

- [ ] **Step 1: 修正 `title` — loading 期不再误判后台**

当前（根因：参数未填时 `run_in_background` 为 null，`null != "false"` → true）：
```kotlin
@Composable
override fun title(context: ToolUIContext): String {
    val isBackground = when (context.content.getStringContent("mode")) {
        "background" -> true
        "synchronous" -> false
        else -> context.arguments.getStringContent("run_in_background") != "false"
    }
    return stringResource(
        if (isBackground) R.string.chat_message_tool_sub_agent_background
        else R.string.chat_message_tool_sub_agent
    )
}
```
改为：
```kotlin
@Composable
override fun title(context: ToolUIContext): String {
    // loading（content=null）默认"运行子代理"；确认为后台执行（mode=background）才加"（后台）"
    val isBackground = context.content.getStringContent("mode") == "background"
    return stringResource(
        if (isBackground) R.string.chat_message_tool_sub_agent_background
        else R.string.chat_message_tool_sub_agent
    )
}
```

- [ ] **Step 2: `Preview` 在 description 后追加 prompt**

`ToolDetailContainer` 内，description 的 `Text` 之后（`Row(horizontalArrangement...)` 之前）插入：
```kotlin
context.arguments.getStringContent("prompt")?.takeIf { it.isNotBlank() }?.let { prompt ->
    Text(
        text = prompt,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}
```

- [ ] **Step 3: Commit + 推送 + CI**

```bash
git add app/src/main/java/me/rerere/rikkahub/ui/components/message/tools/BuiltinToolUIs.kt
git commit -m "fix(ui): 子代理 loading 期标题默认'运行子代理'，详情显示 prompt"
git push origin master
gh workflow run nightly-build-debug.yml --repo DevLintMar/rikkahub --ref master
```
等 CI 完成后：`gh run list --workflow nightly-build-debug.yml --repo DevLintMar/rikkahub --limit 1 --json databaseId,headSha,status,conclusion`，**核对 headSha == 本 commit 哈希**。红时 `gh run view <id> --log-failed` 区分 flake 与编译错误。

---

### Task 2: 拒绝/掐断报错只显示右侧

**Files:**
- Modify: `app/src/main/java/me/rerere/rikkahub/ui/components/message/ChatMessageTools.kt`

**Interfaces:**
- Consumes: `ToolApprovalState.Denied`（拒绝与掐断 `Generation cancelled by user` 均置此状态）。
- Produces: `ChatMessageToolStep` 在 `isDenied` 时仅 `extra`（右侧）显示红色报错，卡片下方不再重复。

- [ ] **Step 1: 删除 content 块内的重复报错 Text**

删除 `content = if (hasExtraContent) { ... }` 内的这段（约 195-203 行）：
```kotlin
                    if (isDenied) {
                        val reason = (tool.approvalState as ToolApprovalState.Denied).reason
                        Text(
                            text = stringResource(R.string.chat_message_tool_denied) +
                                if (reason.isNotBlank()) ": $reason" else "",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
```

- [ ] **Step 2: `hasExtraContent` 不再因 isDenied 展开空内容区**

```kotlin
val hasExtraContent = renderer.hasSummary(context) || isDenied || images.isNotEmpty()
```
改为：
```kotlin
val hasExtraContent = renderer.hasSummary(context) || images.isNotEmpty()
```
（`isDenied` 仍被 `extra` 分支使用，无未使用变量。）

- [ ] **Step 3: Commit + 推送 + CI**（同 Task 1 Step 3 命令，commit message: `fix(ui): 拒绝/掐断报错只显示在工具卡右侧`）

---

### Task 3: search 模块多 key 重试 helpers

**Files:**
- Modify: `search/src/main/java/me/rerere/search/SearchService.kt`

**Interfaces:**
- Consumes: `SearchServiceOptions` sealed class（新增基类 `open val apiKey`）。
- Produces: `splitApiKeys(keys)`、`withSingleKey(key)`、`retryOnQuota(options, run)` —— 全部 public，供 app 模块 SearchTools/SettingSearchDetailPage 使用。

- [ ] **Step 1: 基类新增 `open val apiKey`**

`sealed class SearchServiceOptions` 内、`abstract val id: Uuid` 之后：
```kotlin
    /** 多 key 分隔字符串（逗号/空格/换行），无 apiKey 的服务返回空串 */
    open val apiKey: String get() = ""
```

- [ ] **Step 2: 15 个有 apiKey 的子类构造参数加 `override`**

给以下 data class 的 `val apiKey: String = ""` 加 `override`（与既有 `override val id` 一致）：
`ZhipuOptions`、`TavilyOptions`、`ExaOptions`、`LinkUpOptions`、`BraveOptions`、`MetasoOptions`、`OllamaOptions`、`PerplexityOptions`、`FirecrawlOptions`、`JinaOptions`、`BochaOptions`、`RikkaHubOptions`、`GrokOptions`、`TinyfishOptions`、`SerperOptions`。

```kotlin
    data class TavilyOptions(
        override val id: Uuid = Uuid.random(),
        override val apiKey: String = "",
        val depth: String = "advanced",
    ) : SearchServiceOptions()
```
（BingLocal/SearXNG/CustomJs 无 apiKey，继承基类默认。）

- [ ] **Step 3: 文件末尾新增三个 helper**（`Call.await()` 之后）：

```kotlin
private val SEARCH_KEY_SPLIT_REGEX = "[\\s,]+".toRegex()

/** 将逗号/空格/换行分隔的 API key 字符串拆分为去重列表 */
fun splitApiKeys(keys: String): List<String> =
    keys.split(SEARCH_KEY_SPLIT_REGEX).map { it.trim() }.filter { it.isNotBlank() }.distinct()

/**
 * 生成只含单个 key 的 options 副本（用于多 key 轮询重试）。
 * 无 apiKey 字段的服务（BingLocal/SearXNG/CustomJs）原样返回。
 */
@Suppress("UNCHECKED_CAST")
fun <T : SearchServiceOptions> T.withSingleKey(key: String): T = when (this) {
    is SearchServiceOptions.ZhipuOptions -> copy(apiKey = key)
    is SearchServiceOptions.TavilyOptions -> copy(apiKey = key)
    is SearchServiceOptions.ExaOptions -> copy(apiKey = key)
    is SearchServiceOptions.LinkUpOptions -> copy(apiKey = key)
    is SearchServiceOptions.BraveOptions -> copy(apiKey = key)
    is SearchServiceOptions.MetasoOptions -> copy(apiKey = key)
    is SearchServiceOptions.OllamaOptions -> copy(apiKey = key)
    is SearchServiceOptions.PerplexityOptions -> copy(apiKey = key)
    is SearchServiceOptions.FirecrawlOptions -> copy(apiKey = key)
    is SearchServiceOptions.JinaOptions -> copy(apiKey = key)
    is SearchServiceOptions.BochaOptions -> copy(apiKey = key)
    is SearchServiceOptions.RikkaHubOptions -> copy(apiKey = key)
    is SearchServiceOptions.GrokOptions -> copy(apiKey = key)
    is SearchServiceOptions.TinyfishOptions -> copy(apiKey = key)
    is SearchServiceOptions.SerperOptions -> copy(apiKey = key)
    else -> this
} as T

private fun Throwable.isQuotaExceeded(): Boolean {
    val msg = message?.lowercase() ?: return false
    return msg.contains("quota") || msg.contains("rate limit") || msg.contains("429") ||
        msg.contains("402") || msg.contains("insufficient") || msg.contains("limit exceeded")
}

/**
 * 多 key 自动重试：拆分 apiKey（逗号/空格/换行），随机顺序逐个尝试。
 * 额度/限流类错误自动换下一个 key；非该类错误立即返回；全部失败返回最后错误。
 */
suspend fun <T : SearchServiceOptions, R> retryOnQuota(
    options: T,
    run: suspend (T) -> Result<R>,
): Result<R> {
    val keys = splitApiKeys(options.apiKey)
    if (keys.size <= 1) return run(options)
    var lastError: Throwable? = null
    for (key in keys.shuffled()) {
        val attempt = run(options.withSingleKey(key))
        if (attempt.isSuccess) return attempt
        val err = attempt.exceptionOrNull() ?: return attempt
        lastError = err
        if (!err.isQuotaExceeded()) return attempt
    }
    return Result.failure(lastError ?: IllegalStateException("All API keys failed"))
}
```

- [ ] **Step 4: Commit + 推送 + CI**（commit message: `feat(search): 多 API key 拆分 + 额度错误自动换 key 重试 helpers`）

---

### Task 4: Settings 多选模型 + Android/Web 全链路原子改动

> 改 `Settings.searchServiceSelected` 破坏所有引用方编译，本 Task 必须一个 commit 内完成以下全部文件。

**Files:**
- Modify: `app/src/main/java/me/rerere/rikkahub/data/datastore/PreferencesStore.kt`
- Modify: `app/src/main/java/me/rerere/rikkahub/ui/components/ai/SearchPicker.kt`
- Modify: `app/src/main/java/me/rerere/rikkahub/ui/components/ai/ChatInput.kt`
- Modify: `app/src/main/java/me/rerere/rikkahub/ui/pages/chat/ChatPage.kt`
- Modify: `app/src/main/java/me/rerere/rikkahub/ui/pages/setting/SettingSearchPage.kt`
- Modify: `app/src/main/java/me/rerere/rikkahub/data/ai/tools/SearchTools.kt`（最小适配，Task 5 重做）
- Modify: `app/src/main/java/me/rerere/rikkahub/web/dto/WebDto.kt`
- Modify: `app/src/main/java/me/rerere/rikkahub/web/routes/SettingsRoutes.kt`

**Interfaces:**
- Produces: `Settings.searchServiceSelectedIds: List<Uuid>`（替换 `searchServiceSelected: Int`）；`val Settings.selectedSearchServices: List<SearchServiceOptions>`（扩展，多选有效服务，至少回退第一个）；`onUpdateSearchService: (List<Uuid>) -> Unit`；Web DTO `UpdateSearchServicesRequest(serviceIds)`。
- Consumes: `Uuid`、`kotlinx.serialization`、`SearchServiceOptions`。

- [ ] **Step 1: PreferencesStore — 新 key + 读取迁移 + 字段替换 + 写入 + 扩展属性**

(1) key 区（`SEARCH_SELECTED` 附近）：
```kotlin
        val SEARCH_SELECTED_IDS = stringPreferencesKey("search_selected_ids")
```

(2) 第一个 map（约 172 行 `.map { preferences -> Settings(...)`）开头、`Settings(` 之前插入局部变量，并把 `searchServices` 从构造内联提升为局部变量：
```kotlin
        }.map { preferences ->
            val searchServices = preferences[SEARCH_SERVICES]?.let {
                JsonInstant.decodeFromString(it)
            } ?: listOf(SearchServiceOptions.DEFAULT)
            val rawSelectedIds = preferences[SEARCH_SELECTED_IDS]?.let {
                runCatching { JsonInstant.decodeFromString<List<Uuid>>(it) }.getOrDefault(emptyList())
            } ?: emptyList()
            val oldSelectedIndex = preferences[SEARCH_SELECTED] ?: 0
            val effectiveSelectedIds = if (rawSelectedIds.isNotEmpty()) {
                rawSelectedIds
            } else if (searchServices.isNotEmpty()) {
                listOf(searchServices.getOrNull(oldSelectedIndex)?.id ?: searchServices.first().id)
            } else {
                emptyList()
            }
            Settings(
```

(3) 替换原 210-216 行三句：
```kotlin
                searchServices = preferences[SEARCH_SERVICES]?.let {
                    JsonInstant.decodeFromString(it)
                } ?: listOf(SearchServiceOptions.DEFAULT),
                searchCommonOptions = preferences[SEARCH_COMMON]?.let {
                    JsonInstant.decodeFromString(it)
                } ?: SearchCommonOptions(),
                searchServiceSelected = preferences[SEARCH_SELECTED] ?: 0,
```
为：
```kotlin
                searchServices = searchServices,
                searchCommonOptions = preferences[SEARCH_COMMON]?.let {
                    JsonInstant.decodeFromString(it)
                } ?: SearchCommonOptions(),
                searchServiceSelectedIds = effectiveSelectedIds,
```

(4) Settings data class 字段（约 574 行）：
```kotlin
    val searchServiceSelected: Int = 0,
```
为：
```kotlin
    val searchServiceSelectedIds: List<Uuid> = emptyList(),
```

(5) 写入函数（约 405-407 行）：
```kotlin
            preferences[SEARCH_SERVICES] = JsonInstant.encodeToString(settings.searchServices)
            preferences[SEARCH_COMMON] = JsonInstant.encodeToString(settings.searchCommonOptions)
            preferences[SEARCH_SELECTED] = settings.searchServiceSelected.coerceIn(0, settings.searchServices.size - 1)
```
为：
```kotlin
            preferences[SEARCH_SERVICES] = JsonInstant.encodeToString(settings.searchServices)
            preferences[SEARCH_COMMON] = JsonInstant.encodeToString(settings.searchCommonOptions)
            preferences[SEARCH_SELECTED_IDS] = JsonInstant.encodeToString(settings.searchServiceSelectedIds)
```

(6) 第二个 map（约 296 行 `settings.copy(...)` 内，`asrProviders = asrProviders,` 之后）追加清理，删除已失效 id 并保证至少一个：
```kotlin
                searchServiceSelectedIds = settings.searchServiceSelectedIds.filter { id ->
                    settings.searchServices.any { it.id == id }
                }.let { ids ->
                    if (ids.isEmpty() && settings.searchServices.isNotEmpty()) {
                        listOf(settings.searchServices.first().id)
                    } else ids
                },
```

(7) Settings data class 之后新增扩展：
```kotlin
/** 多选的有效服务列表（为空/全失效时回退第一个） */
val Settings.selectedSearchServices: List<SearchServiceOptions>
    get() {
        if (searchServices.isEmpty()) return emptyList()
        val picked = if (searchServiceSelectedIds.isEmpty()) {
            searchServices
        } else {
            searchServices.filter { it.id in searchServiceSelectedIds }
        }
        return picked.ifEmpty { listOf(searchServices.first()) }
    }
```

- [ ] **Step 2: SearchPicker.kt — 多选 UI + 聊天页图标 + 签名**

(1) 三处签名 `onUpdateSearchService: (Int) -> Unit` → `(List<Uuid>) -> Unit`（`SearchPickerButton`、`SearchPicker`、`AppSearchSettings`）。加 `import kotlin.uuid.Uuid`。

(2) 删除 `val currentService = settings.searchServices.getOrNull(settings.searchServiceSelected)`（约 70 行）。

(3) `SearchPickerButton` 图标分支（约 89-104 行）：
```kotlin
                if (model?.tools?.contains(BuiltInTools.Search) == true) {
                    Icon(
                        imageVector = HugeIcons.AiSearch02,
                        contentDescription = stringResource(R.string.use_web_search),
                    )
                } else if (enableSearch && currentService != null) {
                    AutoAIIcon(
                        name = currentService.displayName,
                        color = Color.Transparent
                    )
                } else {
                    Icon(
                        imageVector = HugeIcons.Search01,
                        contentDescription = stringResource(R.string.use_web_search),
                    )
                }
```
为（开启时不再显示服务提供商图标，统一 AiSearch02；关闭保留原 Search01）：
```kotlin
                if (enableSearch || model?.tools?.contains(BuiltInTools.Search) == true) {
                    Icon(
                        imageVector = HugeIcons.AiSearch02,
                        contentDescription = stringResource(R.string.use_web_search),
                    )
                } else {
                    Icon(
                        imageVector = HugeIcons.Search01,
                        contentDescription = stringResource(R.string.use_web_search),
                    )
                }
```

(4) `AppSearchSettings` 服务网格（约 244-294 行）改多选 + 至少保留一个：
```kotlin
        itemsIndexed(settings.searchServices) { _, service ->
            val selected = service.id in settings.searchServiceSelectedIds
            val isLastSelected = selected && settings.searchServiceSelectedIds.size == 1
            val containerColor = animateColorAsState(
                if (selected) {
                    MaterialTheme.colorScheme.primaryContainer
                } else {
                    MaterialTheme.colorScheme.surface
                }
            )
            val textColor = animateColorAsState(
                if (selected) {
                    MaterialTheme.colorScheme.onPrimaryContainer
                } else {
                    MaterialTheme.colorScheme.onSurface
                }
            )
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = containerColor.value,
                    contentColor = textColor.value,
                ),
                onClick = {
                    if (isLastSelected) return@Card  // 至少保留一个
                    val next = if (selected) {
                        settings.searchServiceSelectedIds - service.id
                    } else {
                        settings.searchServiceSelectedIds + service.id
                    }
                    onUpdateSearchService(next)
                },
                shape = MaterialTheme.shapes.large
            ) { /* 原卡片内容不变 */ }
        }
```
（`index` 参数不再使用，改为 `_`。）

- [ ] **Step 3: ChatInput.kt 签名**

`onUpdateSearchService: (Int) -> Unit`（约 129 行）→ `onUpdateSearchService: (List<Uuid>) -> Unit`。加 `import kotlin.uuid.Uuid`。

- [ ] **Step 4: ChatPage.kt 回调**

约 398-404 行：
```kotlin
                    onUpdateSearchService = { index ->
                        vm.updateSettings(
                            setting.copy(
                                searchServiceSelected = index
                            )
                        )
                    },
```
为：
```kotlin
                    onUpdateSearchService = { ids ->
                        vm.updateSettings(
                            setting.copy(
                                searchServiceSelectedIds = ids
                            )
                        )
                    },
```

- [ ] **Step 5: SettingSearchPage.kt — 移除"结果数量"卡片**

(1) 删除 `item("common_options") { CommonOptions(...) }` 块（约 164-173 行）。
(2) 删除 `CommonOptions` composable（约 370-410 行）。
(3) 清理未使用 import：`me.rerere.search.SearchCommonOptions`、`OutlinedNumberInput`、`FormItem`（确认无其他使用后删）。

- [ ] **Step 6: SearchTools.kt 最小适配（编译保底，Task 5 重做）**

将 5 处（约 55/62/99/113/120 行）：
```kotlin
                    val options = settings.searchServices.getOrElse(
                        index = settings.searchServiceSelected,
                        defaultValue = { SearchServiceOptions.DEFAULT })
```
统一替换为：
```kotlin
                    val options = settings.selectedSearchServices.firstOrNull()
                        ?: SearchServiceOptions.DEFAULT
```
（`selectedSearchServices` 扩展在同包 datastore，`Settings` 已 import；无需新 import。）

- [ ] **Step 7: WebDto.kt + SettingsRoutes.kt — 多选路由**

(1) WebDto.kt：
```kotlin
@Serializable
data class UpdateSearchServiceRequest(
    val index: Int,
)
```
为：
```kotlin
@Serializable
data class UpdateSearchServicesRequest(
    val serviceIds: List<String>,
)
```

(2) SettingsRoutes.kt：import `UpdateSearchServicesRequest`；`post("/search/service")` 路由（约 141-154 行）：
```kotlin
        post("/search/service") {
            val request = call.receive<UpdateSearchServicesRequest>()

            settingsStore.update { settings ->
                val validIds = settings.searchServices.map { it.id.toString() }.toSet()
                val unknown = request.serviceIds.filterNot { it in validIds }
                if (unknown.isNotEmpty()) {
                    throw BadRequestException("Unknown search service ids: $unknown")
                }
                if (request.serviceIds.isEmpty()) {
                    throw BadRequestException("At least one search service must be selected")
                }
                settings.copy(
                    searchServiceSelectedIds = request.serviceIds.mapNotNull { id ->
                        runCatching { Uuid.parse(id) }.getOrNull()
                    }
                )
            }
            call.respond(HttpStatusCode.OK, mapOf("status" to "ok"))
        }
```

- [ ] **Step 8: Commit + 推送 + CI**（commit message: `feat(search): 搜索服务多选（Settings.searchServiceSelectedIds）+ 聊天页 AiSearch02 图标 + 移除结果数量`；等 CI，核对 headSha）

---

### Task 5: SearchTools 重做（service/num_results + 多 key 重试）

**Files:**
- Modify: `app/src/main/java/me/rerere/rikkahub/data/ai/tools/SearchTools.kt`
- Modify: `app/src/main/java/me/rerere/rikkahub/ui/pages/setting/SettingSearchDetailPage.kt`

**Interfaces:**
- Consumes: `settings.selectedSearchServices`、`retryOnQuota`、`splitApiKeys`、`SearchServiceOptions`。
- Produces: `search_web` 参数 `{query, service(enum=多选displayName), num_results}`；`scrape_web` 参数 `{url, service}`；输出结构不变（`type: web_search`/`web_fetch`）。

- [ ] **Step 1: 重写 `createSearchTools`**

替换整个 `createSearchTools` 函数。关键点：
```kotlin
private const val MAX_SCRAPE_TEXT_CHARS = 32 * 1024

fun createSearchTools(settings: Settings): Set<Tool> {
    val selected = settings.selectedSearchServices
    val selectedByName = selected.associateBy { it.displayName }
    val scrapeCapable = selected.filter {
        SearchService.getService(it).scrapingParameters(it) != null
    }
    val scrapeCapableByName = scrapeCapable.associateBy { it.displayName }

    return buildSet {
        add(
            Tool(
                name = "search_web",
                description = """
                    Search the web for up-to-date or specific information.
                    Use this when the user asks for the latest news, current facts, or needs verification.
                    Available search services: ${selected.joinToString(", ") { it.displayName }}.
                    Choose one via the `service` parameter (must be one of the listed values);
                    `num_results` controls how many results to return (default: 10).
                    Today is ${LocalDate.now().toLocalString(true)}.

                    Response format:
                    - items[].id (short id), title, url, text
                    - images[]: image urls related to the query (may be empty)

                    Citations:
                    - After using results, add `[citation,domain](id)` after the sentence.
                    - Multiple citations are allowed.
                    - If no results are cited, omit citations.

                    Images:
                    - When images help the user understand the answer, embed relevant ones using Markdown: `![](url)`.
                    - Embed 2 to 4 images, and only use urls from `images[]` (never fabricate or alter urls).
                    - Usually place the images at the very beginning of your reply; skip them entirely if none are relevant.

                    Example:
                    The capital of France is Paris. [citation,example.com](abc123)
                    The population is about 2.1 million. [citation,example.com](abc123) [citation,example2.com](def456)
                """.trimIndent(),
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
                        },
                        required = listOf("query")
                    )
                },
                execute = { args ->
                    val options = args.jsonObject["service"]?.jsonPrimitive?.contentOrNull
                        ?.let { name -> selectedByName[name] }
                        ?: selected.first()
                    val service = SearchService.getService(options)
                    val numResults = args.jsonObject["num_results"]?.jsonPrimitive?.intOrNull ?: 10
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
                        val result = retryOnQuota(options) { o ->
                            service.scrape(args.jsonObject, settings.searchCommonOptions, o)
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
```

import 变更：
```kotlin
import kotlinx.serialization.json.buildJsonArray   // 新增
import kotlinx.serialization.json.intOrNull        // 新增
import me.rerere.rikkahub.data.datastore.selectedSearchServices   // 新增
import me.rerere.search.retryOnQuota               // 新增
```
（`SearchCommonOptions`/`SearchServiceOptions` 若不再直接引用则移除；`SearchServiceOptions.DEFAULT` 已不再用。）

- [ ] **Step 2: SettingSearchDetailPage 测试段走重试**

`SearchTestSection` 内（约 274-278 行）：
```kotlin
                                result = service.search(params, commonOptions, options)
```
为：
```kotlin
                                result = retryOnQuota(options) { o ->
                                    service.search(params, commonOptions, o)
                                }
```
加 `import me.rerere.search.retryOnQuota`。

- [ ] **Step 3: Commit + 推送 + CI**（commit message: `feat(tools): search_web/scrape_web 增加 service/num_results 参数并支持多 key 自动重试`）

---

### Task 6: web-ui 同步（多选 + 图标）

**Files:**
- Modify: `web-ui/app/types/settings.ts`
- Modify: `web-ui/app/components/input/search-picker.tsx`

**Interfaces:**
- Consumes: Kotlin `Settings.searchServiceSelectedIds: List<Uuid>`（SSE 流序列化为 `string[]`）、`POST /settings/search/service { serviceIds }`。
- Produces: web-ui 多选 toggle + 开启时搜索图标。

- [ ] **Step 1: settings.ts 类型**

`searchServiceSelected: number;`（约 158 行）→ `searchServiceSelectedIds: string[];`

- [ ] **Step 2: search-picker.tsx — 图标**

删除 `const currentService = settings?.searchServices?.[settings.searchServiceSelected] ?? null;`（约 115 行）。

图标分支（约 177-190 行）：
```tsx
          ) : searchEnabled && currentService ? (
            <AIIcon
              name={getServiceLabel(currentService, t)}
              size={16}
              className="bg-transparent"
              imageClassName="h-full w-full"
            />
          ) : builtInSearchEnabled ? (
            <Search className="size-4" />
          ) : (
            <Earth className="size-4" />
          )}
```
为（开启时统一 Search 图标，关闭 Earth；web-ui 无 hugeicons 用 lucide 等价）：
```tsx
          ) : checked ? (
            <Search className="size-4" />
          ) : (
            <Earth className="size-4" />
          )}
```

- [ ] **Step 3: search-picker.tsx — 多选 mutation**

`selectServiceMutation`（约 136-143 行）替换为：
```tsx
  const updateSearchServicesMutation = useMutation({
    mutationFn: ({ serviceIds }: { serviceIds: string[] }) =>
      api.post<{ status: string }>("settings/search/service", { serviceIds }),
    onError: (serviceError) => {
      setError(extractErrorMessage(serviceError, t("search.switch_service_failed")));
    },
    onSuccess: () => setError(null),
  });
```
`loading`（约 158-161 行）中 `selectServiceMutation.isPending` → `updateSearchServicesMutation.isPending`。

- [ ] **Step 4: search-picker.tsx — 服务网格多选**

网格（约 249-296 行）单选用例替换：
```tsx
                    {settings.searchServices.map((service, index) => {
                      const selectedIds = settings.searchServiceSelectedIds ?? [];
                      const selected = selectedIds.includes(service.id);
                      const isLastSelected = selected && selectedIds.length === 1;
                      const switching = updateSearchServicesMutation.isPending;

                      return (
                        <button
                          key={service.id}
                          type="button"
                          className={cn(
                            "hover:bg-muted flex w-full items-center gap-3 rounded-lg border px-3 py-2 text-left transition",
                            selected && "border-primary bg-primary/5",
                          )}
                          disabled={disabled || loading}
                          onClick={() => {
                            if (!canUse || !settings || isLastSelected) return;
                            const next = selected
                              ? selectedIds.filter((x) => x !== service.id)
                              : [...selectedIds, service.id];
                            updateSearchServicesMutation.mutate({ serviceIds: next });
                          }}
                        >
                          {/* 原 AIIcon + 名称/类型内容不变 */}
                        </button>
                      );
                    })}
```

- [ ] **Step 5: web-ui typecheck + 提交**

```bash
cd web-ui && pnpm run typecheck
git add web-ui/app/types/settings.ts web-ui/app/components/input/search-picker.tsx
git commit -m "feat(web-ui): 搜索服务多选 + 开启时搜索图标"
git push origin master
```
（web-ui 独立类型检查；Android CI 一并覆盖 web-ui 若其接入。）

---

## 验证清单（设备级，用户上手）

- 子代理同步执行 loading 期标题"运行子代理"，后台执行"运行子代理（后台）"
- 子代理详情 description 下显示 prompt（前后台）
- 拒绝/掐断工具调用卡片仅右侧红色报错，下方无重复
- 搜索开启时聊天页图标 AiSearch02；关闭时原搜索图标
- 设置页/聊天页服务多选（至少保留一个）；无"结果数量"
- search_web 工具描述列出多选服务，AI 传 service/num_results 生效
- API key 逗号多填时搜索报额度错自动换 key（日志/测试段可见）

## 自审（writing-plans self-review）

- **Spec 覆盖**：Task 1↔spec§3，Task 2↔§4，Task 3↔§5，Task 4↔§6a/6b/6d+Web，Task 5↔§6c/6e，Task 6↔§6a/6b Web 侧。全覆盖。
- **占位符扫描**：所有步骤含具体代码/命令，无 TBD。
- **类型一致性**：`searchServiceSelectedIds: List<Uuid>`（Kotlin）/`string[]`（TS）；`onUpdateSearchService: (List<Uuid>) -> Unit` 三处一致；`retryOnQuota(options){ o -> ... }` 签名 Task 3 定义与 Task 5 使用一致。
