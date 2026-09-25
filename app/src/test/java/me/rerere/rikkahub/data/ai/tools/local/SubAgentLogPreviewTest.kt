package me.rerere.rikkahub.data.ai.tools.local

import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 日志预览的两条性质：**白空格被压平**、**长度有上限**。
 *
 * 它直接决定应用内「日志」页会不会被一行巨大的工具输出撑爆（环形缓冲只有 100 条，
 * 行长没有上限就等于把其它日志全挤掉）。
 */
class SubAgentLogPreviewTest {

    @Test
    fun `换行与连续空白被压成单个空格`() {
        val preview = previewForLog("第一行\n\n第二行\t\t值   值")

        assertEquals("第一行 第二行 值 值", preview)
    }

    @Test
    fun `超长文本被截断并留下省略号`() {
        val preview = previewForLog("x".repeat(500))

        assertTrue(preview.length <= 121)
        assertTrue(preview.endsWith("…"))
        assertFalse(preview.contains("\n"))
    }

    @Test
    fun `短文本原样返回`() {
        assertEquals("原神 6.1 前瞻", previewForLog("  原神 6.1 前瞻  "))
    }
}
