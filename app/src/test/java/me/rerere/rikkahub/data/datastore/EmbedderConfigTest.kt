package me.rerere.rikkahub.data.datastore

import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EmbedderConfigTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `default config is disabled with dashscope defaults`() {
        val c = EmbedderConfig()
        assertFalse(c.enabled)
        assertEquals("https://dashscope.aliyuncs.com/compatible-mode/v1", c.baseUrl)
        assertEquals("qwen3-embedding-8b", c.model)
        assertEquals(8, c.batchSize)
    }

    @Test
    fun `config survives json round trip`() {
        val c = EmbedderConfig(enabled = true, model = "text-embedding-3-small", batchSize = 16)
        val decoded = json.decodeFromString<EmbedderConfig>(json.encodeToString(c))
        assertEquals(c, decoded)
    }

    @Test
    fun `settings missing embedder field decodes to default`() {
        // 旧版本 Settings 序列化不含 embedder 字段 → 应回落到默认值
        val oldSettingsJson = """{"init":false,"dynamicColor":true}"""
        val s = json.decodeFromString<Settings>(oldSettingsJson)
        assertFalse(s.embedder.enabled)
    }
}
