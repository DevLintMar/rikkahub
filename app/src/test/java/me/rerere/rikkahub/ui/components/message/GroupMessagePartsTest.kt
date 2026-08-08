package me.rerere.rikkahub.ui.components.message

import me.rerere.ai.ui.UIMessagePart
import kotlinx.serialization.json.buildJsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class GroupMessagePartsTest {

    private val meta = buildJsonObject { }

    private fun image(url: String) = UIMessagePart.Image(url = url, metadata = meta)
    private fun text(content: String) = UIMessagePart.Text(text = content, metadata = meta)

    @Test
    fun `连续图片合并为一行`() {
        val blocks = listOf(image("a.jpg"), image("b.jpg"), image("c.jpg"))
            .groupMessageParts(mergeConsecutiveImages = true)

        assertEquals(1, blocks.size)
        val group = assertIs<MessagePartBlock.ImageGroupBlock>(blocks[0])
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
        assertIs<MessagePartBlock.ImageGroupBlock>(blocks[0])      // a 单独
        assertEquals(listOf("a.jpg"), (blocks[0] as MessagePartBlock.ImageGroupBlock).images.map { it.url })
        assertIs<MessagePartBlock.ContentBlock>(blocks[1])          // text
        assertIs<MessagePartBlock.ImageGroupBlock>(blocks[2])       // b+c 合并
        assertEquals(listOf("b.jpg", "c.jpg"), (blocks[2] as MessagePartBlock.ImageGroupBlock).images.map { it.url })
        // b 的原始 index 2
        assertEquals(2, (blocks[2] as MessagePartBlock.ImageGroupBlock).index)
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
        assertIs<MessagePartBlock.ImageGroupBlock>(blocks[0])
        assertIs<MessagePartBlock.ThinkingBlock>(blocks[1])
        assertIs<MessagePartBlock.ImageGroupBlock>(blocks[2])
    }

    @Test
    fun `默认模式不合并图片`() {
        val blocks = listOf(image("a.jpg"), image("b.jpg"))
            .groupMessageParts() // mergeConsecutiveImages = false

        assertEquals(2, blocks.size)
        blocks.forEach { assertIs<MessagePartBlock.ContentBlock>(it) }
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
        assertIs<MessagePartBlock.ContentBlock>(blocks[0])
        val group = assertIs<MessagePartBlock.ImageGroupBlock>(blocks[1])
        assertEquals(listOf("a.jpg", "b.jpg"), group.images.map { it.url })
        assertIs<MessagePartBlock.ContentBlock>(blocks[2])
        assertTrue(blocks.all { it !is MessagePartBlock.ThinkingBlock })
    }
}
