package me.rerere.rikkahub.data.ai

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RequestLoggingInterceptorTest {

    /** 足够长、会被折叠的 base64 片段（> 2048 字符） */
    private val longBase64 = "A".repeat(4096)

    @Test
    fun `长 base64 折叠为占位符`() {
        val body = """{"type":"image_url","image_url":{"url":"data:image/jpeg;base64,$longBase64"}}"""
        val compacted = compactLoggedBody(body)
        assertFalse("原始 base64 不应出现在日志里", compacted.contains(longBase64))
        assertTrue(compacted.contains("<<4096 chars of base64 omitted>>"))
        // 结构信息必须保留：否则日志页看不出请求长什么样
        assertTrue(compacted.contains("data:image/jpeg;base64,"))
        assertTrue(compacted.contains(""""type":"image_url""""))
    }

    @Test
    fun `折叠后的内容仍是合法 JSON`() {
        val body = """{"messages":[{"role":"user","content":[{"type":"image_url","image_url":{"url":"data:image/png;base64,$longBase64"}}]}]}"""
        // 占位符里不能含引号或反斜杠，否则日志页的 JsonTree 解析会失败
        assertEquals(body.count { it == '"' }, compactLoggedBody(body).count { it == '"' })
        assertFalse(compactLoggedBody(body).contains('\\'))
        val parsed = Json.parseToJsonElement(compactLoggedBody(body)).jsonObject
        assertTrue(parsed["messages"].toString().contains("chars of base64 omitted"))
    }

    @Test
    fun `短内容不受影响`() {
        val body = """{"model":"gpt-5","messages":[{"role":"user","content":"hello"}]}"""
        assertEquals(body, compactLoggedBody(body))
        // 常见的短字符串（含 base64 字母表字符）不能被误折叠
        val short = """{"a":"QUJDREVGR0hJSktMTU5P"}"""
        assertEquals(short, compactLoggedBody(short))
    }

    @Test
    fun `超长请求体被截断`() {
        // 填充内容必须含 base64 字母表之外的字符，否则这一长串本身会先被当作 base64 折叠掉
        val body = "word ".repeat(20_000)
        val compacted = compactLoggedBody(body)
        assertTrue("应带截断说明", compacted.contains("chars truncated"))
        assertTrue("截断后应明显变短", compacted.length < 70_000)
        assertTrue(compacted.startsWith("word ".repeat(100)))
    }

    @Test
    fun `长字母数字串按折叠处理而非截断`() {
        // 文档化行为：2048+ 的连续字母数字基本只可能是 base64/hex 之类的载荷，一律折叠
        assertTrue(compactLoggedBody("x".repeat(100_000)).contains("chars of base64 omitted"))
    }

    @Test
    fun `空请求体不炸`() {
        assertEquals("", compactLoggedBody(""))
    }

    @Test
    fun `JSON 里夹着长 base64 时结构与折叠说明共存`() {
        val body = """{"messages":[{"role":"tool","content":"${"B".repeat(3000)}"}]}"""
        val compacted = compactLoggedBody(body)
        assertTrue(compacted.contains("<<3000 chars of base64 omitted>>"))
        assertTrue(compacted.contains(""""role":"tool""""))
        assertEquals(body.count { it == '"' }, compacted.count { it == '"' })
    }

    @Test
    fun `base64 尾部的等号填充一并折叠`() {
        val body = """{"d":"${"C".repeat(2100)}=="}"""
        val compacted = compactLoggedBody(body)
        assertTrue(compacted.contains("<<2102 chars of base64 omitted>>"))
        assertFalse(compacted.contains("""=="""))
    }
}
