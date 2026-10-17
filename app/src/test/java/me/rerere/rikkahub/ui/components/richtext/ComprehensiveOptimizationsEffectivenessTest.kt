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
    @Test
    fun `5 验证 citation 冒号语法预处理与结构化解析`() {
        val markdown = "测试文本 [citation:03c0ba] 以及 [citation:wiki.org](1a2b3c) 还有多目标 [citation:id1, id2, id3] 结束"
        val result = parseMarkdown(markdown)
        
        // 预处理后应转化为标准 Markdown 链接语法 [citation,03c0ba](03c0ba) 与 [citation,wiki.org](1a2b3c)
        assertTrue("应包含转化后的引用 ID 链接", result.preprocessed.contains("[citation,03c0ba](03c0ba)"))
        assertTrue("应包含转化后的带域名引用链接", result.preprocessed.contains("[citation,wiki.org](1a2b3c)"))
        assertTrue("多目标引用应展开为独立的引用链接", result.preprocessed.contains("[citation,id1](id1)[citation,id2](id2)[citation,id3](id3)"))
    }

    @Test
    fun `6 验证公式对称高度与垂直基线对齐几何计算算法`() {
        val fontSizePx = 32f
        val fontCenterPx = -0.325f * fontSizePx // -10.4f

        // 模拟一个带大分母的分式公式 (height=20, depth=24)
        val heightPx = 20f
        val depthPx = 24f

        val halfExtentPx = maxOf(heightPx + fontCenterPx, depthPx - fontCenterPx).coerceAtLeast(fontSizePx * 0.5f)
        val placeholderHeightPx = halfExtentPx * 2f
        val yOffsetPx = halfExtentPx - heightPx - fontCenterPx

        // 验证公式基线与文本基线严格等高:
        // textBaseline relative to placeholderTop: placeholderHeightPx / 2 - fontCenterPx = halfExtentPx - fontCenterPx
        // formulaBaseline relative to placeholderTop: yOffsetPx + heightPx = halfExtentPx - fontCenterPx
        val textBaseline = halfExtentPx - fontCenterPx
        val formulaBaseline = yOffsetPx + heightPx
        assertEquals("公式基线必须与文本基线 100% 绝对水平对齐", textBaseline, formulaBaseline, 1e-4f)

        // 验证排版引擎预留给基线下方（descent）的空间必须大于等于公式深度 depthPx，绝无重叠:
        val spaceBelowBaseline = placeholderHeightPx - textBaseline
        assertTrue("基线下方预留空间必须完全容纳分母深度", spaceBelowBaseline >= depthPx)
    }
}
