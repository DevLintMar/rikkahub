package me.rerere.rikkahub.data.ai.transformers

import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class ImageLazyLoadTransformerTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private fun filesDir(): File = tempFolder.root

    @Test
    fun `upload 目录文件映射为 upload 挂载 URL`() {
        val filesDir = filesDir()
        val file = File(filesDir, "upload").apply { mkdirs() }.resolve("abc123.jpg")
        assertEquals("file:///upload/abc123.jpg", ImageLazyLoadTransformer.resolveDisplayPath(file, filesDir))
    }

    @Test
    fun `工作区文件映射为 workspace 挂载 URL`() {
        val filesDir = filesDir()
        val file = File(filesDir, "workspaces/wsid/files").apply { mkdirs() }.resolve("report.png")
        assertEquals("file:///workspace/report.png", ImageLazyLoadTransformer.resolveDisplayPath(file, filesDir))
    }

    @Test
    fun `工作区嵌套目录保留相对层级`() {
        val filesDir = filesDir()
        val file = File(filesDir, "workspaces/wsid/files/diagrams").apply { mkdirs() }.resolve("sub.png")
        assertEquals("file:///workspace/diagrams/sub.png", ImageLazyLoadTransformer.resolveDisplayPath(file, filesDir))
    }

    @Test
    fun `filesDir 外已存在的文件回退为自身 file URL`() {
        val filesDir = filesDir()
        val outside = tempFolder.newFolder("outside")
        val file = File(outside, "x.png").apply { createNewFile() }
        val expected = "file://" + file.absolutePath.replace('\\', '/')
        assertEquals(expected, ImageLazyLoadTransformer.resolveDisplayPath(file, filesDir))
    }

    @Test
    fun `不存在的文件回退为占位标记`() {
        val filesDir = filesDir()
        val outside = tempFolder.newFolder("outside")
        val missing = File(outside, "nope.png")
        assertEquals("[Image]", ImageLazyLoadTransformer.resolveDisplayPath(missing, filesDir))
    }

    @Test
    fun `空文件回退为占位标记`() {
        assertEquals("[Image]", ImageLazyLoadTransformer.resolveDisplayPath(null, filesDir()))
    }
}
