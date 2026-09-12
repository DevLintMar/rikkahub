package me.rerere.search

import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.ai.core.InputSchema
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ExaSearchServiceTest {
    @Test
    fun `search mapping preserves publication date and highlights`() {
        val result = ExaSearchService.mapSearchResult(
            ExaSearchService.ExaData(
                results = listOf(
                    ExaSearchService.ExaResult(
                        id = "result-1",
                        title = "Official release",
                        url = "https://example.com/release",
                        publishedDate = "2026-08-31T06:26:20Z",
                        highlights = listOf("Current release evidence"),
                        text = "Release text",
                    )
                )
            )
        )

        assertEquals("2026-08-31T06:26:20Z", result.items.single().publishedDate)
        assertEquals(listOf("Current release evidence"), result.items.single().highlights)
        assertEquals("https://example.com/release", result.items.single().url)
    }

    @Test
    fun `missing publication date decodes as null`() {
        val data = SearchService.json.decodeFromString<ExaSearchService.ExaData>(
            """
            {
              "results": [{
                "id": "result-1",
                "title": "Undated result",
                "url": "https://example.com/undated"
              }]
            }
            """.trimIndent()
        )

        assertNull(ExaSearchService.mapSearchResult(data).items.single().publishedDate)
    }

    @Test
    fun `current query does not inject domain or freshness filters`() {
        val body = ExaSearchService.buildSearchRequestBody(
            params = buildJsonObject {
                put("query", "What is the current stable RikkaHub version?")
            },
            resultSize = 10,
        )

        assertTrue("includeDomains" !in body)
        assertTrue("maxAgeHours" !in body["contents"]!!.jsonObject)
    }

    @Test
    fun `search mapping preserves provider result order`() {
        val result = ExaSearchService.mapSearchResult(
            ExaSearchService.ExaData(
                results = listOf(
                    ExaSearchService.ExaResult("old", "Old", "https://example.com/old", "2026-01-01T00:00:00Z"),
                    ExaSearchService.ExaResult("undated", "Undated", "https://example.com/undated"),
                    ExaSearchService.ExaResult("new", "New", "https://example.com/new", "2026-09-01T00:00:00Z"),
                )
            )
        )

        assertEquals(
            listOf("old", "undated", "new"),
            result.items.map { it.url.substringAfterLast('/') })
    }

    @Test
    fun `normal search request keeps legacy contents shape`() {
        val body = ExaSearchService.buildSearchRequestBody(
            params = buildJsonObject {
                put("query", "stable knowledge")
                put("content_type", "text")
            },
            resultSize = 10,
        )

        assertEquals(true, body["contents"]!!.jsonObject["text"]!!.jsonPrimitive.boolean)
        assertTrue("startPublishedDate should be absent", "startPublishedDate" !in body)
        assertTrue("includeDomains should be absent", "includeDomains" !in body)
    }

    @Test
    fun `request serializes snake_case dates and domains to exa native camelCase`() {
        val body = ExaSearchService.buildSearchRequestBody(
            params = buildJsonObject {
                put("query", "latest release")
                put("start_published_date", "2026-08-01T00:00:00Z")
                put("end_published_date", "2026-09-03T23:59:59Z")
                put("include_domains", buildJsonArray { add(JsonPrimitive("example.com")) })
                put("exclude_domains", buildJsonArray { add(JsonPrimitive("old.example.com")) })
            },
            resultSize = 10,
        )

        assertEquals("2026-08-01T00:00:00Z", body["startPublishedDate"]!!.jsonPrimitive.content)
        assertEquals("2026-09-03T23:59:59Z", body["endPublishedDate"]!!.jsonPrimitive.content)
        assertEquals("example.com", body["includeDomains"]!!.jsonArray.single().jsonPrimitive.content)
        assertEquals("old.example.com", body["excludeDomains"]!!.jsonArray.single().jsonPrimitive.content)

        val schema = ExaSearchService.parameters(SearchServiceOptions.ExaOptions()) as InputSchema.Obj
        assertTrue("start_published_date" in schema.properties)
        assertTrue("end_published_date" in schema.properties)
        assertTrue("include_domains" in schema.properties)
        assertTrue("exclude_domains" in schema.properties)
        assertTrue("camelCase keys must not leak into the schema", "startPublishedDate" !in schema.properties)
    }

    @Test
    fun `empty and null optional values keep legacy request shape`() {
        val body = ExaSearchService.buildSearchRequestBody(
            params = buildJsonObject {
                put("query", "stable knowledge")
                put("end_published_date", JsonNull)
                put("include_domains", buildJsonArray { add(JsonPrimitive("")) })
                put("exclude_domains", buildJsonArray {})
            },
            resultSize = 10,
        )

        val contents = body["contents"]!!.jsonObject
        assertTrue("null end date should be absent", "endPublishedDate" !in body)
        assertTrue("empty include domains should be absent", "includeDomains" !in body)
        assertTrue("empty exclude domains should be absent", "excludeDomains" !in body)
        assertEquals(true, contents["highlights"]!!.jsonPrimitive.boolean)
    }

    @Test
    fun `search request asks for both excerpts and bounded text`() {
        val defaultShape = ExaSearchService.buildSearchRequestBody(
            params = buildJsonObject { put("query", "stable knowledge") },
            resultSize = 10,
        )["contents"]!!.jsonObject

        assertEquals(true, defaultShape["highlights"]!!.jsonPrimitive.boolean)
        assertEquals(
            "默认路径（只有摘录）也要带上有上限的正文，避免整页正文撑爆响应体",
            8_000,
            defaultShape["text"]!!.jsonObject["maxCharacters"]!!.jsonPrimitive.int,
        )

        val fullTextShape = ExaSearchService.buildSearchRequestBody(
            params = buildJsonObject {
                put("query", "stable knowledge")
                put("content_type", "text")
            },
            resultSize = 10,
        )["contents"]!!.jsonObject

        assertEquals(true, fullTextShape["text"]!!.jsonPrimitive.boolean)
        assertEquals(true, fullTextShape["highlights"]!!.jsonPrimitive.boolean)
    }

    @Test
    fun `search request serializes max_age_hours into contents`() {
        val body = ExaSearchService.buildSearchRequestBody(
            params = buildJsonObject {
                put("query", "latest release")
                put("max_age_hours", 0)
            },
            resultSize = 10,
        )

        val contents = body["contents"]!!.jsonObject
        assertEquals(0, contents["maxAgeHours"]!!.jsonPrimitive.int)
        assertEquals(true, contents["highlights"]!!.jsonPrimitive.boolean)

        val schema = ExaSearchService.parameters(SearchServiceOptions.ExaOptions()) as InputSchema.Obj
        assertTrue("max_age_hours" in schema.properties)
        assertTrue("camelCase keys must not leak into the schema", "maxAgeHours" !in schema.properties)
    }

    @Test
    fun `out of range max_age_hours is dropped`() {
        listOf(-2, 721).forEach { value ->
            val contents = ExaSearchService.buildSearchRequestBody(
                params = buildJsonObject {
                    put("query", "latest release")
                    put("max_age_hours", value)
                },
                resultSize = 10,
            )["contents"]!!.jsonObject

            assertTrue("$value is out of range and must be dropped", "maxAgeHours" !in contents)
        }
    }

    @Test
    fun `scrape request serializes content freshness`() {
        val body = ExaSearchService.buildScrapeRequestBody(
            buildJsonObject {
                put("urls", buildJsonArray { add(JsonPrimitive("https://example.com/release")) })
                put("max_age_hours", 0)
            }
        )

        assertEquals("https://example.com/release", body["urls"]!!.jsonArray.single().jsonPrimitive.content)
        assertEquals(0, body["maxAgeHours"]!!.jsonPrimitive.int)
        assertEquals(true, body["text"]!!.jsonPrimitive.boolean)
    }

    @Test
    fun `scrape request supports bounded text and extras`() {
        val body = ExaSearchService.buildScrapeRequestBody(
            buildJsonObject {
                put("urls", buildJsonArray { add(JsonPrimitive("https://example.com/release")) })
                put("max_characters", 4000)
                put("extract_links", 5)
            }
        )

        assertEquals(4000, body["text"]!!.jsonObject["maxCharacters"]!!.jsonPrimitive.int)
        assertEquals(5, body["extras"]!!.jsonObject["links"]!!.jsonPrimitive.int)
    }
}
