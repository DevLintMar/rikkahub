package me.rerere.rikkahub.ui.components.message

import me.rerere.ai.ui.UIMessagePart
import kotlinx.serialization.json.buildJsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GroupMessagePartsTest {

    private val meta = buildJsonObject { }

    private fun image(url: String) = UIMessagePart.Image(url = url, metadata = meta)
    private fun text(content: String) = UIMessagePart.Text(text = content, metadata = meta)

    /** JUnit4 断言类型（无 assertInstanceOf），失败给出类型提示 */
    private fun assertImageGroup(block: MessagePartBlock): MessagePartBlock.ImageGroupBlock {
        assertTrue("expected ImageGroupBlock, got ${block::class.simpleName}", block is MessagePartBlock.ImageGroupBlock)
        return block as MessagePartBlock.ImageGroupBlock
    }

    @Test
    fun `连续图片合并为一行`() {
        val blocks = listOf(image("a.jpg"), image("b.jpg"), image("c.jpg"))
            .groupMessageParts(mergeConsecutiveImages = true)

        assertEquals(1, blocks.size)
        val group = assertImageGroup(blocks[0])
        assertEquals(listOf("a.jpg", "b.jpg", "c.jpg"), group.images.map { it.url })
        assertEquals(0, group.index) // index 取组内第一张图的原始 index
    }

    @Test
    fun `图片与其他 part 交错时不合并`() {
        val parts = listOf(
            image("a.jpg"),
            text("说明"),
            image("b.jpg"),
            image("c.jpg"),
        )
        val blocks = parts.groupMessageParts(mergeConsecutiveImages = true)

        assertEquals(3, blocks.size)
        assertEquals(listOf("a.jpg"), assertImageGroup(blocks[0]).images.map { it.url })  // a 单独
        assertTrue(blocks[1] is MessagePartBlock.ContentBlock)                            // text
        val group = assertImageGroup(blocks[2])                                           // b+c 合并
        assertEquals(listOf("b.jpg", "c.jpg"), group.images.map { it.url })
        assertEquals(2, group.index) // b 的原始 index 2
    }

    @Test
    fun `思考块打断图片连续性`() {
        val parts = listOf(
            image("a.jpg"),
            UIMessagePart.Reasoning(reasoning = "思考"),
            image("b.jpg"),
        )
        val blocks = parts.groupMessageParts(mergeConsecutiveImages = true)

        assertEquals(3, blocks.size)
        assertImageGroup(blocks[0])
        assertTrue(blocks[1] is MessagePartBlock.ThinkingBlock)
        assertImageGroup(blocks[2])
    }

    @Test
    fun `默认模式不合并图片`() {
        val blocks = listOf(image("a.jpg"), image("b.jpg"))
            .groupMessageParts() // mergeConsecutiveImages = false

        assertEquals(2, blocks.size)
        blocks.forEach { assertTrue("expected ContentBlock, got ${it::class.simpleName}", it is MessagePartBlock.ContentBlock) }
    }

    @Test
    fun `合并不吞掉非图片 part`() {
        val parts = listOf(
            text("前"),
            image("a.jpg"),
            image("b.jpg"),
            text("后"),
        )
        val blocks = parts.groupMessageParts(mergeConsecutiveImages = true)

        assertEquals(3, blocks.size)
        assertTrue(blocks[0] is MessagePartBlock.ContentBlock)
        val group = assertImageGroup(blocks[1])
        assertEquals(listOf("a.jpg", "b.jpg"), group.images.map { it.url })
        assertTrue(blocks[2] is MessagePartBlock.ContentBlock)
        assertTrue(blocks.all { it !is MessagePartBlock.ThinkingBlock })
    }
}
