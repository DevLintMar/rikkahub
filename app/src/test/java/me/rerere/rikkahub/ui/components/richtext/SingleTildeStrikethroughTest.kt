package me.rerere.rikkahub.ui.components.richtext

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SingleTildeStrikethroughTest {

    @Test
    fun `双波浪是删除线`() {
        assertTrue(isDoubleTildeStrikethrough("~~哈哈~~"))
        assertTrue(isDoubleTildeStrikethrough("~~重点内容~~"))
    }

    @Test
    fun `单波浪不是删除线`() {
        assertFalse(isDoubleTildeStrikethrough("~哈哈~"))
        assertFalse(isDoubleTildeStrikethrough("~there~"))
    }

    @Test
    fun `三波浪及以上不是删除线`() {
        assertFalse(isDoubleTildeStrikethrough("~~~哈哈~~~"))
        assertFalse(isDoubleTildeStrikethrough("~~~~"))
    }

    @Test
    fun `范围符号不是删除线`() {
        assertFalse(isDoubleTildeStrikethrough("30%~50%"))
        assertFalse(isDoubleTildeStrikethrough("~25度"))
    }

    @Test
    fun `波浪数量不匹配不是删除线`() {
        assertFalse(isDoubleTildeStrikethrough("~a~~"))
        assertFalse(isDoubleTildeStrikethrough("~~a~"))
    }

    @Test
    fun `过短或空文本不崩溃且不是删除线`() {
        assertFalse(isDoubleTildeStrikethrough(""))
        assertFalse(isDoubleTildeStrikethrough("~"))
        assertFalse(isDoubleTildeStrikethrough("~~"))
        assertFalse(isDoubleTildeStrikethrough("~~~"))
    }

    @Test
    fun `内容含波浪的双波浪仍是删除线`() {
        assertTrue(isDoubleTildeStrikethrough("~~a~b~~"))
    }
}
