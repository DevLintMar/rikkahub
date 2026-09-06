package me.rerere.rikkahub.ui.components.richtext

import org.intellij.markdown.flavours.gfm.GFMFlavourDescriptor
import org.intellij.markdown.parser.MarkdownParser
import org.junit.Assert.assertEquals
import org.junit.Test

class SingleTildeStrikeGuardTest {

    private val parser = MarkdownParser(GFMFlavourDescriptor())

    private fun escaped(content: String): String = escapeSingleTildeStrikethrough(parser, content)

    @Test
    fun `无波浪文本原样返回`() {
        val text = "**重点内容**是哈哈\n"
        assertEquals(text, escaped(text))
    }

    @Test
    fun `双波浪删除线保留不转义`() {
        val text = "是~~哈哈~~\n"
        assertEquals(text, escaped(text))
    }

    @Test
    fun `星号后的单波浪假删除线被转义`() {
        // 用户报告场景：**重点内容**是~哈哈~ 会误渲染删除线
        val escaped = escaped("**重点内容**是~哈哈~\n")
        assertEquals("**重点内容**是\\~哈哈\\~\n", escaped)
    }

    @Test
    fun `无前置delimiter的单波浪不产出假删除线节点原样返回`() {
        // 解析器的 delimiter 平衡缺陷反而使孤立的单波浪（opener 在下标 0）不配对，
        // 无 STRIKETHROUGH 节点 → 无需转义，原样返回（渲染本就是纯文本）
        assertEquals("30%~50%", escaped("30%~50%"))
        assertEquals("**x** 30%~50%", escaped("**x** 30%~50%"))
    }

    @Test
    fun `双波浪与单波浪混合只转义单波浪`() {
        assertEquals("~~真删~~ 和 \\~假删\\~", escaped("~~真删~~ 和 ~假删~"))
    }

    @Test
    fun `单波浪开双波浪闭的混合包裹不产出节点原样返回`() {
        // a~b~~: 单波浪 opener 在下标 0 不配对，双波浪 closer 无匹配 opener → 无节点
        val escaped = escaped("a~b~~\n")
        assertEquals("a~b~~\n", escaped)
    }

    @Test
    fun `多段文本只转义假删除线段`() {
        val text = "标题\n\n**重点**是~哈哈~\n\n正常~~删除~~文字\n"
        assertEquals("标题\n\n**重点**是\\~哈哈\\~\n\n正常~~删除~~文字\n", escaped(text))
    }
}
