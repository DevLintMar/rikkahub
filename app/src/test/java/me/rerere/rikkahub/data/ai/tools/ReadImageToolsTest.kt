package me.rerere.rikkahub.data.ai.tools

import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ReadImageToolsTest {

    @Test
    fun `urls 数组正常解析并去除空白项`() {
        val args = buildJsonObject {
            putJsonArray("urls") {
                add("file:///upload/a.jpg")
                add("   ")
                add("  https://example.com/b.png  ")
            }
        }
        assertEquals(
            listOf("file:///upload/a.jpg", "https://example.com/b.png"),
            parseReadImagePaths(args)
        )
    }

    @Test
    fun `缺少 urls 参数返回空列表`() {
        val args = buildJsonObject { put("limit", 1) }
        assertTrue(parseReadImagePaths(args).isEmpty())
    }

    @Test
    fun `urls 非数组时返回空列表`() {
        val args = buildJsonObject { put("urls", "file:///upload/a.jpg") }
        assertTrue(parseReadImagePaths(args).isEmpty())
    }

    @Test
    fun `空对象参数返回空列表`() {
        assertTrue(parseReadImagePaths(buildJsonObject {}).isEmpty())
    }
}
