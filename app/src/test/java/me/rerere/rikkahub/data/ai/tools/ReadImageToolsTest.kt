package me.rerere.rikkahub.data.ai.tools

import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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

    // 本地路径解析规则（沙箱根 = file:// 的根、真机路径拒绝）见
    // me.rerere.rikkahub.data.files.WorkspaceFileUrlResolverTest

    private fun bytes(vararg values: Int): ByteArray = ByteArray(values.size) { values[it].toByte() }

    @Test
    fun `按文件头识别常见图片格式`() {
        assertEquals("png", sniffImageExtension(bytes(0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 0x00)))
        assertEquals("jpg", sniffImageExtension(bytes(0xFF, 0xD8, 0xFF, 0xE0, 0x00, 0x10)))
        assertEquals("gif", sniffImageExtension("GIF89a....".toByteArray(Charsets.US_ASCII)))
        assertEquals("webp", sniffImageExtension("RIFF????WEBPVP8 ".toByteArray(Charsets.US_ASCII)))
        assertEquals("bmp", sniffImageExtension(bytes(0x42, 0x4D, 0x00, 0x00)))
        assertEquals("heic", sniffImageExtension("????ftypheic????".toByteArray(Charsets.US_ASCII)))
        assertEquals("avif", sniffImageExtension("????ftypavif????".toByteArray(Charsets.US_ASCII)))
    }

    @Test
    fun `非图片内容识别为 null`() {
        assertNull(sniffImageExtension("<html><body>hi".toByteArray(Charsets.US_ASCII)))
        assertNull(sniffImageExtension(ByteArray(0)))
        assertNull(sniffImageExtension(bytes(0x00, 0x01)))
        // 文件头太短：不足以判定 ftyp 品牌码时不能误判为 heic
        assertNull(sniffImageExtension("????ftyp".toByteArray(Charsets.US_ASCII)))
        // 空字节数组与短数组都不能越界
        assertNull(sniffImageExtension(bytes(0xFF)))
    }
}
