package me.rerere.rikkahub.ui.components.richtext

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MarkdownHtmlTagFilterTest {

    @Test
    fun `纯文本或标准Markdown不触发HTML降级`() {
        val md = """
            # 标题
            这是普通段落，带 **加粗** 和 *斜体*。
            
            - 列表项 1
            - 列表项 2
            
            ${'$'}${'$'}f(x) = x^2${'$'}${'$'}
        """.trimIndent()
        val result = parseMarkdown(md)
        assertFalse("纯 Markdown 不应被判定为 hasHtml", result.hasHtml)
    }

    @Test
    fun `表格内含 br 换行标签不应触发 HTML 降级`() {
        val md = """
            | 条件骤变 | 首要受阻/受促进环节 | 传导因果链 | 短时间内微观指标瞬时变化 |
            | :--- | :--- | :--- | :--- |
            | **突然停止光照**（或光强骤降） | 光反应停止 | 阻断 | **${'$'}\text{C}_3${'$'} 瞬时增加**<br>**${'$'}\text{C}_5${'$'} 瞬时减少**<br/>ATP/NADPH 减少 |
        """.trimIndent()
        val result = parseMarkdown(md)
        assertFalse("表格内只含 <br> 或 <br/> 不应触发 hasHtml 全局降级", result.hasHtml)
    }

    @Test
    fun `段落内含无害 hr 或 br 不应触发 HTML 降级`() {
        val md = "第一行<br>第二行<br />第三行<hr>"
        val result = parseMarkdown(md)
        assertFalse("仅含 <br>、<hr> 不应触发 hasHtml", result.hasHtml)
    }

    @Test
    fun `包含真正 HTML 扩展标签如 details 或 progress 应正确识别 hasHtml`() {
        val mdDetails = """
            <details>
            <summary>点击展开</summary>
            这里是隐藏内容
            </details>
        """.trimIndent()
        assertTrue("<details> 标签必须触发 hasHtml", parseMarkdown(mdDetails).hasHtml)

        val mdProgress = """
            任务进度：
            <progress value="70" max="100"></progress>
        """.trimIndent()
        assertTrue("<progress> 标签必须触发 hasHtml", parseMarkdown(mdProgress).hasHtml)

        val mdDiv = """
            <div style="color: red;">红色文字</div>
        """.trimIndent()
        assertTrue("带样式的 <div> 必须触发 hasHtml", parseMarkdown(mdDiv).hasHtml)
    }
}
