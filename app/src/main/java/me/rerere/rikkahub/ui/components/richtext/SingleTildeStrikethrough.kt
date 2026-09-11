package me.rerere.rikkahub.ui.components.richtext

import org.intellij.markdown.IElementType
import org.intellij.markdown.ast.ASTNode
import org.intellij.markdown.ast.acceptChildren
import org.intellij.markdown.ast.getTextInNode
import org.intellij.markdown.flavours.gfm.GFMElementTypes
import org.intellij.markdown.flavours.gfm.GFMFlavourDescriptor
import org.intellij.markdown.html.GeneratingProvider
import org.intellij.markdown.html.HtmlGenerator
import org.intellij.markdown.html.URI
import org.intellij.markdown.parser.LinkMap

/**
 * 单波浪删除线语义修正：只有 `~~x~~`（双波浪）是删除线，`~x~`（单波浪）按纯文本渲染。
 *
 * 背景：GFM 规范允许单波浪删除线，本项目使用的解析器 fork 也实现了它，于是中文里
 * 作范围/约数符号的 `~` 会被误渲染（"30%~50%"、"~25度"）；更糟的是 fork 的
 * delimiter 平衡存在"下标 0 不参与匹配"的缺陷，`~x~` 是否被渲染成删除线取决于
 * 前面是否恰好出现 `*`/`_`（`**重点**是~哈哈~` 会渲染、`是~哈哈~` 不会），行为不稳定。
 *
 * 实现选择"渲染层抑制"而非改文本：解析树保持原样，两个渲染路径各自判断节点原文
 * 是否为 `~~` 包裹，不是就按纯文本渲染（波浪原样显示、不加删除线）。此前尝试过的
 * "把 `~` 转义成 `\~` 再重新解析"方案不可行——AnnotatedString 路径直接输出 TEXT
 * 原文、不消费反斜杠转义，反斜杠会显示在界面上。
 */

/**
 * 节点原文是否为双波浪删除线（`~~内容~~`）。
 * 单波浪（`~内容~`）、三波浪及以上（`~~~内容~~~`，GFM 中最内层也不是删除线）、
 * 以及范围符号（`30%~50%`）都返回 false。
 */
fun isDoubleTildeStrikethrough(raw: CharSequence): Boolean {
    if (raw.length < 4) return false
    if (!raw.startsWith("~~") || !raw.endsWith("~~")) return false
    // 恰为 2 个波浪包裹；三波浪及以上不算删除线
    return raw.takeWhile { it == '~' }.length == 2 &&
        raw.takeLastWhile { it == '~' }.length == 2
}

/**
 * 把单波浪假删除线按纯文本输出的 GFM flavour（HTML 渲染路径）。
 *
 * GFMFlavourDescriptor 默认把 STRIKETHROUGH 渲染成 `<span class="user-del">` 并修剪
 * 两端波浪，本子类对非 `~~` 包裹的节点改输出其 HTML 转义原文，使波浪如实显示。
 */
class SingleTildeSafeGfmFlavour(
    useSafeLinks: Boolean = true,
    absolutizeAnchorLinks: Boolean = false,
    makeHttpsAutoLinks: Boolean = true,
) : GFMFlavourDescriptor(
    useSafeLinks = useSafeLinks,
    absolutizeAnchorLinks = absolutizeAnchorLinks,
    makeHttpsAutoLinks = makeHttpsAutoLinks,
) {
    override fun createHtmlGeneratingProviders(
        linkMap: LinkMap,
        baseURI: URI?,
    ): Map<IElementType, GeneratingProvider> {
        val base = super.createHtmlGeneratingProviders(linkMap, baseURI)
        // 双波浪节点沿用默认渲染（span.user-del + 修剪波浪）
        val defaultStrikethrough = base[GFMElementTypes.STRIKETHROUGH]
        return base + mapOf(
            GFMElementTypes.STRIKETHROUGH to object : GeneratingProvider {
                override fun processNode(
                    visitor: HtmlGenerator.HtmlGeneratingVisitor,
                    text: String,
                    node: ASTNode,
                ) {
                    if (isDoubleTildeStrikethrough(node.getTextInNode(text))) {
                        defaultStrikethrough?.processNode(visitor, text, node)
                            ?: node.acceptChildren(visitor)
                    } else {
                        // 单波浪：输出转义后的纯文本（含波浪），无删除线
                        visitor.consumeHtml(HtmlGenerator.leafText(text, node))
                    }
                }
            }
        )
    }
}
