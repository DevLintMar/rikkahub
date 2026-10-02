package me.rerere.rikkahub.ui.components.richtext

import org.intellij.markdown.ast.ASTNode
import org.intellij.markdown.flavours.gfm.GFMElementTypes
import org.intellij.markdown.flavours.gfm.GFMFlavourDescriptor
import org.intellij.markdown.flavours.gfm.GFMTokenTypes
import org.intellij.markdown.parser.MarkdownParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MarkdownTaskListTest {
    private val flavour = GFMFlavourDescriptor(makeHttpsAutoLinks = true, useSafeLinks = true)

    @Test
    fun testTaskListParsingAndStateExtraction() {
        val md = """
            - [ ] 1323
            - [x] 121323
            - [X] 343431
            - Normal list item
        """.trimIndent()

        val parsedTree = MarkdownParser(flavour).buildMarkdownTreeFromString(md)
        val listItems = mutableListOf<ASTNode>()

        fun collectListItems(node: ASTNode) {
            if (node.type.name == "LIST_ITEM") {
                listItems.add(node)
            }
            node.children.forEach { collectListItems(it) }
        }

        collectListItems(parsedTree)
        assertEquals(4, listItems.size)

        // Item 0: [ ] 1323
        val item0 = listItems[0]
        val cb0 = item0.children.find { it.type == GFMTokenTypes.CHECK_BOX }
        assertNotNull(cb0)
        val text0 = md.substring(cb0!!.startOffset, cb0.endOffset).trim()
        assertFalse(text0.startsWith("[x]", ignoreCase = true))

        // Item 1: [x] 121323
        val item1 = listItems[1]
        val cb1 = item1.children.find { it.type == GFMTokenTypes.CHECK_BOX }
        assertNotNull(cb1)
        val text1 = md.substring(cb1!!.startOffset, cb1.endOffset).trim()
        assertTrue(text1.startsWith("[x]", ignoreCase = true))

        // Item 2: [X] 343431 (uppercase X)
        val item2 = listItems[2]
        val cb2 = item2.children.find { it.type == GFMTokenTypes.CHECK_BOX }
        assertNotNull(cb2)
        val text2 = md.substring(cb2!!.startOffset, cb2.endOffset).trim()
        assertTrue(text2.startsWith("[x]", ignoreCase = true))

        // Item 3: Normal list item (no checkbox)
        val item3 = listItems[3]
        val cb3 = item3.children.find { it.type == GFMTokenTypes.CHECK_BOX }
        assertNull(cb3)
    }
}
