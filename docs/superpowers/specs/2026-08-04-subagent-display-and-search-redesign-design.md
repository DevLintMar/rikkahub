# 子代理展示修正 + 搜索工具重做 — 设计文档

**日期**：2026-08-04
**状态**：设计已确认（用户批准）
**范围**：Android 端（app/search 模块）+ Web 端（web-api 路由 + web-ui 前端）

---

## 1. 目标

1. 子代理工具 loading 期标题误显示"运行子代理（后台）" → 默认"运行子代理"，确认为后台时才加"（后台）"
2. 子代理调用详情页在 description 后显示 prompt（前后台一致）
3. 工具调用被拒绝/掐断时的红色报错简介只在工具卡右侧显示，不在下方重复显示
4. 重做 search_web 与抓取网页工具：
   - 聊天页搜索开启时图标改为 HugeIcons.AiSearch02（不再显示服务提供商标识图标），关闭时保留原图标
   - 搜索服务选择从单选改为多选（至少保留一个）
   - 工具参数加 `service`（枚举列出多选中的所有服务）+ `num_results`（AI 指定结果数量）
   - 搜索服务配置界面移除"结果数量"
   - API 密钥支持以逗号分隔多个，使用时随机取一个，报额度/限流错误自动换 key 重试

---

## 2. 全局约束

- **本机无编译器**：静态编写 + review，编译验证全靠 CI（`git push origin master` THEN `gh workflow run nightly-build-debug.yml --ref master`）
- **CI 判定铁律**：`gh run list --workflow nightly-build-debug.yml --limit 1 --json databaseId,headSha,status,conclusion` + 核对 headSha
- 字符串双写 en `values/` + zh `values-zh/`（ja/ko/ru/zh-rTW 有 key 时一并改/删）；删串先 grep 零引用
- 图标名（HugeIcons）以 CI 编译为准；只用已验证图标（AiSearch02 已在本项目使用）
- 文件删除走 `~/.claude/scripts/trash.sh`（回收站），禁止 rm
- `JsonInstant` 为 `ignoreUnknownKeys = true`，Settings 数据模型可安全演进
- `UIMessagePart.Tool.toolState`：STOPPED（掐断）/FAILED；被拒绝与掐断均置 `approvalState = Denied`（`ChatService.kt:909`）

---

## 3. Task 1：子代理标题 loading 期显示修正

**文件**：`app/src/main/java/me/rerere/rikkahub/ui/components/message/tools/BuiltinToolUIs.kt` — `SubAgentToolUI.title`

**现状问题**：`else -> context.arguments.getStringContent("run_in_background") != "false"`。工具参数未填完时 `run_in_background` 为 null，`null != "false"` → true，误判为后台，loading 期显示"运行子代理（后台）"。

**修改**：
```kotlin
override fun title(context: ToolUIContext): String {
    val isBackground = context.content.getStringContent("mode") == "background"
    return stringResource(
        if (isBackground) R.string.chat_message_tool_sub_agent_background
        else R.string.chat_message_tool_sub_agent
    )
}
```

- loading（content=null）→ "运行子代理"
- 执行后 mode=background → "运行子代理（后台）"
- 执行后 mode=synchronous → "运行子代理"

## 4. Task 2：子代理详情显示 prompt

**文件**：`BuiltinToolUIs.kt` — `SubAgentToolUI.Preview`

description 之后追加 prompt（来自 `context.arguments.getStringContent("prompt")`，前后台均可用）：
```kotlin
description?.takeIf { it.isNotBlank() }?.let {
    Text(it, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
}
context.arguments.getStringContent("prompt")?.takeIf { it.isNotBlank() }?.let {
    Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}
```

## 5. Task 3：拒绝/掐断报错只显示右侧

**文件**：`app/src/main/java/me/rerere/rikkahub/ui/components/message/ChatMessageTools.kt` — `ChatMessageToolStep`

`isDenied` 的红色报错文本（`chat_message_tool_denied` + reason）当前在 `extra`（步骤头右侧）与 `content`（卡片下方）各渲染一次。**删除 `content` 块内的重复 Text（约 195-203 行）**，保留 `extra` 块。

拒绝与掐断（`Generation cancelled by user`）都走 `approvalState = Denied`，一处修改同时覆盖两种场景。

## 6. Task 4：搜索工具重做

### 6a. 聊天页图标

**文件**：
- `app/src/main/java/me/rerere/rikkahub/ui/components/ai/SearchPicker.kt` — `SearchPickerButton`
- `web-ui/app/components/input/search-picker.tsx`

**Android**：`SearchPickerButton` 中 `else if (enableSearch && currentService != null)` 分支由 `AutoAIIcon(name = currentService.displayName, color = Color.Transparent)` 改为 `Icon(HugeIcons.AiSearch02, ...)`。关闭分支保留 `HugeIcons.Search01`；内置模型搜索分支（`model.tools.contains(Search)`）保持 `AiSearch02` 不变。

**web-ui**：开启时（`searchEnabled && currentService`）的 `<AIIcon name=.../>` 改为 lucide `Search` 图标（web-ui 无 hugeicons，用等价图标）。

### 6b. 搜索服务多选

**Settings 模型**（`app/src/main/java/me/rerere/rikkahub/data/datastore/PreferencesStore.kt`）：
- `Settings.searchServiceSelected: Int` → `searchServiceSelectedIds: List<Uuid> = emptyList()`
- 新 DataStore key `SEARCH_SELECTED_IDS`（string，JSON 编码 List<Uuid>）
- **读取迁移**：`SEARCH_SELECTED_IDS` 无 → 用旧 `SEARCH_SELECTED` int 对应 `searchServices` 的 id seed；结果为空或 index 越界 → 回退第一个 service 的 id
- **写入**：改写 `SEARCH_SELECTED_IDS`；移除旧 `SEARCH_SELECTED` 写入行（旧 key 残留但不读）

**Android UI**：
- `SearchPicker.kt` `AppSearchSettings`：服务卡片从单选（`searchServiceSelected == index`）改为多选（id 是否在 `searchServiceSelectedIds`），点击 toggle 选中/取消；**至少保留一个**（取消最后一个时忽略）
- `ChatPage.kt:398` `onUpdateSearchService: (Int) -> Unit` → `(List<Uuid>) -> Unit`，传 `searchServiceSelectedIds`

**Web 路由**（`app/src/main/java/me/rerere/rikkahub/web/routes/SettingsRoutes.kt` + `web/dto/WebDto.kt`）：
- DTO `UpdateSearchServiceRequest(index: Int)` → `UpdateSearchServicesRequest(serviceIds: List<String>)`
- 路由 `/search/service`：校验所有 id 都存在于 `searchServices`，设置 `settings.copy(searchServiceSelectedIds = serviceIds)`；同时更新 `searchServiceSelected` 兼容逻辑不保留（字段已删）

**web-ui**（`web-ui/app/types/settings.ts` + `search-picker.tsx`）：
- `Settings.searchServiceSelected: number` → `searchServiceSelectedIds: string[]`
- 单选 `selectServiceMutation` → 多选 toggle，每次点击发送更新后的完整 id 列表；至少保留一个

### 6c. 工具参数：service + num_results

**文件**：`app/src/main/java/me/rerere/rikkahub/data/ai/tools/SearchTools.kt`

构造 `createSearchTools(settings)` 时：
- `selectedServices = settings.searchServices.filter { it.id in settings.searchServiceSelectedIds }`，为空则 `searchServices.firstOrNull()`（回退）
- 服务标识值用 `displayName`（与用户所见一致）

**search_web**：
- 参数 schema 改通用（不再嵌入单个 service 的 `parameters()`）：
  - `query`（string，required）
  - `service`（string，enum = selectedServices.map { it.displayName }，description 列出全部）
  - `num_results`（integer，description 说明默认为 10）
- 描述开头注明可用服务列表（与 enum 一致）
- execute：
  - `service` 参数 → 在 selectedServices 中按 displayName 匹配，未匹配/未传 → 第一个
  - `num_results` → `commonOptions.copy(resultSize = numResults)`（缺省 10）
  - 调用 `service.searchWithRetry(params, commonOptions, options)`

**scrape_web**：
- 任一 selectedService 的 `scrapingParameters() != null` 即创建
- 参数：`url`（string，required）+ `service`（string，enum = 支持抓取的 selectedServices displayName）
- execute：解析 `service` → 对应 options（默认第一个支持抓取的），调用 `service.scrapeWithRetry(...)`

### 6d. 移除"结果数量"

**文件**：`app/src/main/java/me/rerere/rikkahub/ui/pages/setting/SettingSearchPage.kt`

删除 `CommonOptions` 卡片（唯一内容是 resultSize）。`SearchCommonOptions`（`search` 模块）保留为内部默认（resultSize=10），供 `SettingSearchDetailPage.SearchTestSection` 与 num_results 缺省使用；`Settings.searchCommonOptions` 字段保留不再由 UI 修改。

### 6e. 多 API 密钥 + 自动重试

**文件**：
- `search/src/main/java/me/rerere/search/SearchService.kt`（新增 helpers）
- `app/src/main/java/me/rerere/rikkahub/data/ai/tools/SearchTools.kt`（使用）
- `app/src/main/java/me/rerere/rikkahub/ui/pages/setting/SettingSearchDetailPage.kt`（测试段使用）

**新增 helper（search 模块）**：
```kotlin
// 逗号/空格/换行分隔（与 KeyRoulette.splitKey 一致）
fun splitApiKeys(keys: String): List<String>

// 生成只含单个 key 的 options 副本；无 apiKey 的服务原样返回
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
    else -> this   // BingLocal / SearXNG / CustomJs 无 apiKey
} as T

// 泛型重试包装：额度/限流错误换 key 重试
suspend fun <T : SearchServiceOptions, R> retryOnQuota(
    options: T,
    run: suspend (T) -> Result<R>,
): Result<R>
```

**retryOnQuota 流程**：
1. `keys = splitApiKeys(options.apiKey)`；`keys.size <= 1` → 直接 `run(options)`
2. 否则 `for (key in keys.shuffled())`：`run(options.withSingleKey(key))`
   - 成功 → 返回
   - 失败且错误为 **quota 类**（消息含 quota / rate limit / 429 / 402 / insufficient / limit exceeded）→ 记下继续下一个 key
   - 失败且**非** quota 类 → 立即返回该失败
3. 全部 key 失败 → 返回最后一条错误

**使用点**：
- `SearchTools` search_web execute：`retryOnQuota(options) { o -> service.search(params, commonOptions, o) }`
- scrape_web execute：`retryOnQuota(options) { o -> service.scrape(params, commonOptions, o) }`
- `SearchTestSection`：同样走 `retryOnQuota`（验证多 key 自动重试）

**API key 输入 UI**：现有 `OutlinedTextField` 直接支持逗号分隔输入；`SearchService.init` 已用 `KeyRoulette.lru`（LRU 轮询）作为单次选择的 key，本次重试包装在单次选择之上做 quota 级自动换 key。

---

## 7. 验证

- 每任务独立 commit → push → CI；CI 红时 `--log-failed` 区分 flake 与编译错误
- 设备核验：
  - 子代理同步执行 loading 期标题为"运行子代理"，后台执行显示"运行子代理（后台）"
  - 子代理详情 description 下显示 prompt
  - 拒绝/掐断工具调用卡片仅右侧红色报错
  - 搜索开启时聊天页图标为 AiSearch02；关闭时为原搜索图标
  - 搜索设置服务多选（至少一个）；聊天页设置弹层多选
  - search_web/scrape_web 工具描述列出多选服务；AI 传入 service/num_results 生效
  - 设置页无"结果数量"；API key 逗号多填时 search 报错自动换 key

## 8. 影响面清单

| 模块 | 文件 |
|---|---|
| app UI 工具展示 | `BuiltinToolUIs.kt`（Task 1/2）、`ChatMessageTools.kt`（Task 3） |
| app 设置模型 | `PreferencesStore.kt`（多选 + 迁移） |
| app UI 搜索 | `SearchPicker.kt`、`ChatPage.kt`、`SettingSearchPage.kt`、`SettingSearchDetailPage.kt` |
| app 工具 | `SearchTools.kt` |
| web 路由/DTO | `SettingsRoutes.kt`、`WebDto.kt` |
| web-ui | `settings.ts`、`search-picker.tsx` |
| search 模块 | `SearchService.kt`（helpers）、`SearchServiceOptions.kt`（同文件，withSingleKey） |
