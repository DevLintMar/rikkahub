package me.rerere.rikkahub.ui.components.richtext

import org.intellij.markdown.ast.ASTNode
import org.intellij.markdown.ast.getTextInNode
import org.intellij.markdown.flavours.gfm.GFMElementTypes
import org.intellij.markdown.parser.MarkdownParser

/**
 * 单波浪假删除线抑制。
 *
 * GFM 解析器把 `~x~`（单波浪）也视为删除线，且 delimiter 平衡存在下标 0 不参与
 * 匹配的缺陷：`是~哈哈~`（波浪 opener 是全段第一个 delimiter）不渲染删除线，而
 * `**重点**是~哈哈~`（前面有 `*` delimiter 把波浪推到下标 >0）会把 `~哈哈~` 渲染成
 * 删除线——即"有没有删除线"取决于上文是否出现 `*`/`_`，行为不稳定。中文里 `~`
 * 常作范围/约数符号（30%~50%、~25度），被误渲染会歪曲内容。
 *
 * 产品预期：只有 `~~x~~`（双波浪）才是删除线。本函数解析 AST 找出所有
 * STRIKETHROUGH 节点，原文不是以 `~~` 开头并以 `~~` 结尾的（单波浪误判），
 * 把其原文中的 `~` 转义为 `\~`，使解析器不再将其视为 delimiter。双波浪节点
 * 原样保留。转义后再重新解析，两条渲染路径（AnnotatedString / HTML）共用。
 *
 * 转义只落在 STRIKETHROUGH 节点原文内——该节点由解析器确定，天然不会处于
 * code span / code fence / 链接 URL 内部，替换安全。
 */
fun escapeSingleTildeStrikethrough(parser: MarkdownParser, content: String): String {
    if (!content.contains('~')) return content

    val tree = parser.buildMarkdownTreeFromString(content)
    // 收集需要转义的 ~ 字符绝对偏移（相对 content）
    val offsets = mutableListOf<IntRange>()
    fun walk(node: ASTNode) {
        if (node.type == GFMElementTypes.STRIKETHROUGH) {
            val text = runCatching { node.getTextInNode(content) }.getOrNull()
            // 以单波浪包裹（首尾各恰好 1 个 ~，或首尾不是双波浪）→ 假删除线，转义其全部 ~
            if (text != null && !(text.startsWith("~~") && text.endsWith("~~"))) {
                var idx = node.startOffset
                val end = node.endOffset
                while (idx < end) {
                    if (content[idx] == '~') offsets.add(idx..idx)
                    idx++
                }
            }
        }
        node.children.forEach { walk(it) }
    }
    walk(tree)
    if (offsets.isEmpty()) return content

    return buildString(content.length) {
        var cursor = 0
        for (range in offsets.sortedBy { it.first }) {
            append(content, cursor, range.first)
            append('\\')
            cursor = range.last + 1
        }
        append(content, cursor, content.length)
    }
}
