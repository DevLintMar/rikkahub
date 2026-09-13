package me.rerere.ai.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * 按文件头（魔数）判断图片类型。
 *
 * **BMP 那条是回归测试**：此前只有 read_image 认得 bmp（`sniffImageExtension`）与工作区
 * 文件列表（`WorkspaceFileType`）认得，发送路径的 `encodeBase64` 会在 guessMimeType 里
 * 直接 error → 图片发不出去（"能识别不能发"）。
 *
 * 本测试走纯 JVM：`guessMimeType` 只读字节头，不碰任何 Android 类。
 */
class FileEncoderMimeTypeTest {

    @get:Rule
    val folder = TemporaryFolder()

    private fun sniff(bytes: ByteArray): Result<String> {
        val padded = ByteArray(maxOf(bytes.size, 16))
        bytes.copyInto(padded)
        val file = folder.newFile()
        file.writeBytes(padded)
        return file.guessMimeType()
    }

    private fun ascii(text: String): ByteArray = text.toByteArray(Charsets.US_ASCII)

    @Test
    fun `bmp is recognised`() {
        // BITMAPFILEHEADER: bfType "BM" + 文件长度 4B + 保留 4B + 像素数据偏移 4B
        val bmp = ascii("BM") + byteArrayOf(0x3A, 0x00, 0x00, 0x00) + ByteArray(4) +
            byteArrayOf(0x36, 0x00, 0x00, 0x00) + ByteArray(2)
        assertEquals("image/bmp", sniff(bmp).getOrThrow())
    }

    @Test
    fun `jpeg is recognised`() {
        assertEquals("image/jpeg", sniff(byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte())).getOrThrow())
    }

    @Test
    fun `png is recognised`() {
        val png = byteArrayOf(
            0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A,
        )
        assertEquals("image/png", sniff(png).getOrThrow())
    }

    @Test
    fun `webp is recognised`() {
        val webp = ascii("RIFF") + byteArrayOf(0x20, 0x00, 0x00, 0x00) + ascii("WEBP")
        assertEquals("image/webp", sniff(webp).getOrThrow())
    }

    @Test
    fun `gif is recognised in both revisions`() {
        assertEquals("image/gif", sniff(ascii("GIF89a") + ByteArray(10)).getOrThrow())
        assertEquals("image/gif", sniff(ascii("GIF87a") + ByteArray(10)).getOrThrow())
    }

    @Test
    fun `heic and avif brands are recognised`() {
        assertEquals(
            "image/heic",
            sniff(byteArrayOf(0, 0, 0, 0x20) + ascii("ftypheic")).getOrThrow(),
        )
        assertEquals(
            "image/avif",
            sniff(byteArrayOf(0, 0, 0, 0x20) + ascii("ftypavif")).getOrThrow(),
        )
    }

    @Test
    fun `unknown header fails instead of guessing`() {
        assertTrue(sniff(ByteArray(16)).isFailure)
    }
}
