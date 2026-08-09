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
    fun `paths 数组正常解析并去除空白项`() {
        val args = buildJsonObject {
            putJsonArray("paths") {
                add("/upload/a.jpg")
                add("   ")
                add("  /workspace/b.png  ")
            }
        }
        assertEquals(
            listOf("/upload/a.jpg", "/workspace/b.png"),
            parseReadImagePaths(args)
        )
    }

    @Test
    fun `缺少 paths 参数返回空列表`() {
        val args = buildJsonObject { put("limit", 1) }
        assertTrue(parseReadImagePaths(args).isEmpty())
    }

    @Test
    fun `paths 非数组时返回空列表`() {
        val args = buildJsonObject { put("paths", "/upload/a.jpg") }
        assertTrue(parseReadImagePaths(args).isEmpty())
    }

    @Test
    fun `空对象参数返回空列表`() {
        assertTrue(parseReadImagePaths(buildJsonObject {}).isEmpty())
    }
}
