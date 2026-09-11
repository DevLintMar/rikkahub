package me.rerere.rikkahub.data.ai.tools

import me.rerere.ai.core.InputSchema
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.search.SearchServiceOptions
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 搜索工具的 schema 断言。
 *
 * 与上游同名测试的差异（有意为之，不是待办）：
 * - 上游把 Exa 的参数改成了 camelCase（startPublishedDate / maxAgeHours …），fork 刻意保留
 *   snake_case（start_published_date / max_age_hours）—— 这是模型已经适应、提示词也按此写的
 *   渠道参数契约，合并上游时未采纳其改名（见 SearchTools.kt 的 [Service] 标注机制）。
 * - fork 的 search_web / scrape_web 是多渠道工具，必须显式给 `service`；抓取支持多 URL 故用 `urls`。
 * 因此这里断言的是 fork 实际保证的性质：必填项 + Exa 证据/新鲜度参数确实暴露在 schema 里。
 */
class SearchToolsTest {
    private fun exaSettings() = Settings(
        searchServices = listOf(SearchServiceOptions.ExaOptions()),
    )

    @Test
    fun `exa search tool exposes optional evidence parameters`() {
        val searchTool = createSearchTools(exaSettings()).single { it.name == "search_web" }
        val schema = searchTool.parameters() as InputSchema.Obj

        // query 必填；service 也必须显式给（多渠道工具）
        assertTrue(schema.required.orEmpty().contains("query"))
        assertTrue(schema.required.orEmpty().contains("service"))
        // 日期区间与域名过滤：Exa 的证据/新鲜度参数（fork 的 snake_case 键）
        assertTrue(schema.properties.containsKey("start_published_date"))
        assertTrue(schema.properties.containsKey("end_published_date"))
        assertTrue(schema.properties.containsKey("include_domains"))
        assertTrue(schema.properties.containsKey("exclude_domains"))
    }

    @Test
    fun `exa scrape tool keeps urls required and freshness optional`() {
        val scrapeTool = createSearchTools(exaSettings()).single { it.name == "scrape_web" }
        val schema = scrapeTool.parameters() as InputSchema.Obj

        assertTrue(schema.required.orEmpty().contains("urls"))
        assertTrue(schema.properties.containsKey("max_age_hours"))
    }
}
