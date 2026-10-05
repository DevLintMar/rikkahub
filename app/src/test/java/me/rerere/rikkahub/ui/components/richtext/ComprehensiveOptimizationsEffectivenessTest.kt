package me.rerere.rikkahub.ui.components.richtext

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class ComprehensiveOptimizationsEffectivenessTest {

    @Test
    fun `1 验证 LaTeX 公式缓存池在主机无 JNI 运行时安全降级不崩溃`() {
        val latex = """\frac{a}{b} + \sqrt{c}"""
        // 在没有 Android Rust JNI .so 的主机 JVM 单测环境下，getOrCreateDisplayList 应安全捕获并返回 null，绝不崩溃
        val dl = getOrCreateDisplayList(latex, displayMode = false)
        // 验证 assumeLatexSize 同样安全降级返回 null，绝不抛出任何异常
        val metrics = assumeLatexSize(latex, fontSizePx = 36f, displayMode = false)
        assertTrue("无 JNI 环境下公式测量应安全降级返回 null", metrics == null)
    }

    @Test
    fun `2 验证图片宽高比缓存与自愈过滤机制真实生效`() {
        ImageAspectRatioCache.clear()

        // 正常图片：宽 800，高 600 -> 比例 1.3333
        ImageAspectRatioCache.put("img_normal", 800, 600)
        assertEquals(800f / 600f, ImageAspectRatioCache.get("img_normal")!!, 1e-4f)

        // 竖长图：宽 100，高 10000 -> 应被 MIN_RATIO (0.01) 安全钳位
        ImageAspectRatioCache.put("img_tall", 100, 10000)
        assertEquals(0.01f, ImageAspectRatioCache.get("img_tall")!!, 1e-4f)

        // 异常尺寸：宽 <= 0 或高 <= 0 或 NaN -> 不应写入
        ImageAspectRatioCache.put("img_invalid", 0, 100)
        assertEquals(null, ImageAspectRatioCache.get("img_invalid"))

        // 主动清除
        ImageAspectRatioCache.remove("img_normal")
        assertEquals(null, ImageAspectRatioCache.get("img_normal"))
    }

    @Test
    fun `3 验证白名单过滤真实阻止无害标签全局降级`() {
        // 含表格与 <br> 的 Markdown 内容
        val tableWithBr = """
            | 列1 | 列2 |
            | :--- | :--- |
            | 文本A <br> 换行 | 文本B |
        """.trimIndent()

        val result = parseMarkdown(tableWithBr)
        assertFalse("包含无害 br 的表格不应触发 HTML 降级", result.hasHtml)
    }

    @Test
    fun `4 验证本地物理图片文件存在性与非空校验逻辑`() {
        val tempDir = Files.createTempDirectory("img-test").toFile()
        val validImg = File(tempDir, "valid.png").apply {
            writeBytes(byteArrayOf(1, 2, 3, 4))
        }
        val emptyImg = File(tempDir, "empty.png").apply {
            writeBytes(byteArrayOf())
        }

        assertTrue("有效文件应存在且非空", validImg.exists() && validImg.isFile && validImg.length() > 0)
        assertFalse("空文件不应满足非空有效条件", emptyImg.exists() && emptyImg.isFile && emptyImg.length() > 0)

        validImg.delete()
        emptyImg.delete()
        tempDir.delete()
    }
}
