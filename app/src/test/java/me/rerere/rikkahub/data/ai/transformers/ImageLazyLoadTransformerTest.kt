package me.rerere.rikkahub.data.ai.transformers

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class ImageLazyLoadTransformerTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private fun filesDir(): File = tempFolder.root

    @Test
    fun `upload 目录文件映射为 upload 挂载路径`() {
        val filesDir = filesDir()
        val file = File(filesDir, "upload").apply { mkdirs() }.resolve("abc123.jpg")
        assertEquals("/upload/abc123.jpg", ImageLazyLoadTransformer.resolveDisplayPath(file, filesDir))
    }

    @Test
    fun `工作区文件映射为 workspace 相对路径`() {
        val filesDir = filesDir()
        val file = File(filesDir, "workspaces/wsid/files").apply { mkdirs() }.resolve("report.png")
        assertEquals("/workspace/report.png", ImageLazyLoadTransformer.resolveDisplayPath(file, filesDir))
    }

    @Test
    fun `工作区嵌套目录保留相对层级`() {
        val filesDir = filesDir()
        val file = File(filesDir, "workspaces/wsid/files/diagrams").apply { mkdirs() }.resolve("sub.png")
        assertEquals("/workspace/diagrams/sub.png", ImageLazyLoadTransformer.resolveDisplayPath(file, filesDir))
    }

    @Test
    fun `filesDir 外文件回退为 file uri`() {
        val filesDir = filesDir()
        val outside = tempFolder.newFolder("outside")
        val file = File(outside, "x.png")
        val display = ImageLazyLoadTransformer.resolveDisplayPath(file, filesDir)
        assertTrue("expected file:// fallback, got $display", display.startsWith("file:"))
        assertTrue(display.contains("x.png"))
    }

    @Test
    fun `空文件回退为不可读标记`() {
        assertEquals("[unreadable image]", ImageLazyLoadTransformer.resolveDisplayPath(null, filesDir()))
    }
}
