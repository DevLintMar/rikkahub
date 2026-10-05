package me.rerere.rikkahub.ui.components.richtext

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MarkdownPrewarmCollectorsTest {

    private val d = '$'

    @Test
    fun `测试从 Markdown 收集行内与块级 LaTeX 公式`() {
        val markdown = """
            这是正文，包含行内公式 ${d}E = mc^2${d} 以及另一个公式 ${d}\alpha + \beta${d}。
            
            ```python
            # 代码块内的公式不应被提取
            val = "${d}not_math${d}"
            ```
            
            下面是块级公式：
            ${d}${d}
            \int_{0}^{\infty} e^{-x^2} dx = \frac{\sqrt{\pi}}{2}
            ${d}${d}
            
            最后一行普通文本。
        """.trimIndent()

        val formulas = collectLatexFormulas(markdown)
        
        // 应该提取出 3 个公式：2 个行内，1 个块级
        assertEquals(3, formulas.size)
        
        // 块级公式
        val block = formulas.find { it.second }
        assertTrue("应包含块级公式", block != null)
        assertTrue(block!!.first.contains("int_{0}"))
        
        // 行内公式
        val inlines = formulas.filter { !it.second }
        assertEquals(2, inlines.size)
        assertTrue(inlines.any { it.first == "E = mc^2" })
        assertTrue(inlines.any { it.first.contains("alpha") })
    }

    @Test
    fun `测试从 Markdown 收集图片 URL`() {
        val markdown = """
            # 示例文档
            
            ![这是本地图片](file:///workspace/images/chart.png)
            
            文字段落
            
            ![网络图片](https://example.com/avatar.jpg)
        """.trimIndent()

        val urls = collectImageUrls(markdown)
        assertEquals(2, urls.size)
        assertEquals("file:///workspace/images/chart.png", urls[0])
        assertEquals("https://example.com/avatar.jpg", urls[1])
    }
}
