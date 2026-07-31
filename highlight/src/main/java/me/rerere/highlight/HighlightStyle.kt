package me.rerere.highlight

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle

fun AnnotatedString.Builder.buildHighlightText(
    token: HighlightToken,
    colors: HighlightTextColorPalette,
) {
    when (token) {
        is HighlightToken.Plain -> append(token.content)
        is HighlightToken.Styled -> {
            withStyle(getStyleForTokenType(token.type, colors)) {
                append(token.content)
            }
        }
    }
}

data class HighlightTextColorPalette(
    val keyword: Color,
    val string: Color,
    val number: Color,
    val comment: Color,
    val function: Color,
    val operator: Color,
    val punctuation: Color,
    val className: Color,
    val property: Color,
    val boolean: Color,
    val variable: Color,
    val tag: Color,
    val attrName: Color,
    val attrValue: Color,
    val fallback: Color,
) {
    companion object {
        val Default = HighlightTextColorPalette(
            keyword = Color(0xFFC678DD),
            string = Color(0xFF98C379),
            number = Color(0xFFD19A66),
            comment = Color(0xFF5C6370),
            function = Color(0xFF61AFEF),
            operator = Color(0xFF56B6C2),
            punctuation = Color(0xFFABB2BF),
            className = Color(0xFFE5C07B),
            property = Color(0xFFE06C75),
            boolean = Color(0xFFD19A66),
            variable = Color(0xFFE06C75),
            tag = Color(0xFFE06C75),
            attrName = Color(0xFFD19A66),
            attrValue = Color(0xFF98C379),
            fallback = Color(0xFFABB2BF),
        )
    }
}

/**
 * Resolves a token scope to a span style.
 *
 * Scopes follow the `highlight.js` vocabulary and can be tiered, such as `title.function` or
 * `char.escape`. An unknown tier falls back to its parent scope, which is what the upstream CSS
 * themes do by emitting one class per tier.
 */
private fun getStyleForTokenType(
    type: String,
    colors: HighlightTextColorPalette,
): SpanStyle {
    var scope = type
    while (true) {
        styleForScope(scope, colors)?.let { return it }
        val separator = scope.lastIndexOf('.')
        if (separator == -1) return SpanStyle(color = colors.fallback)
        scope = scope.substring(0, separator)
    }
}

/**
 * Colours follow the Atom One theme, the same palette the upstream stylesheet of that name uses,
 * so several scopes deliberately share a slot.
 *
 * The scope→slot assignment mirrors the pre-merge Prism.js classification so the on-screen colours
 * match the old engine: `type`/`title.class`/`property` are class-name yellow, `literal` is
 * boolean orange, `regexp`/`template-variable` are variable red, `built_in` is fallback grey
 * (Prism had no slot for it). Tiered scopes like `title.function` resolve via [getStyleForTokenType].
 */
private fun styleForScope(
    scope: String,
    colors: HighlightTextColorPalette,
): SpanStyle? = when (scope) {
    "comment", "quote" -> SpanStyle(color = colors.comment, fontStyle = FontStyle.Italic)

    "keyword", "formula" -> SpanStyle(color = colors.keyword)

    "doctag" -> SpanStyle(color = colors.comment, fontStyle = FontStyle.Italic)

    "string", "addition" -> SpanStyle(color = colors.string)

    "regexp" -> SpanStyle(color = colors.variable)

    "char" -> SpanStyle(color = colors.string)

    "attribute" -> SpanStyle(color = colors.attrValue)

    "attr", "selector-attr" -> SpanStyle(color = colors.attrName)

    "template-variable" -> SpanStyle(color = colors.variable)

    "number" -> SpanStyle(color = colors.number)

    "type", "selector-class" -> SpanStyle(color = colors.className)

    "literal" -> SpanStyle(color = colors.boolean)

    "operator" -> SpanStyle(color = colors.operator)

    "built_in" -> SpanStyle(color = colors.fallback)

    "title.class" -> SpanStyle(color = colors.className)

    "title", "function", "symbol", "bullet", "link", "meta", "selector-id" ->
        SpanStyle(color = colors.function)

    "section", "name", "selector-tag", "deletion", "subst" ->
        SpanStyle(color = colors.property)

    "property" -> SpanStyle(color = colors.className)

    "tag" -> SpanStyle(color = colors.tag)

    "variable" -> SpanStyle(color = colors.variable)

    "punctuation", "params" -> SpanStyle(color = colors.punctuation)

    "emphasis" -> SpanStyle(color = colors.fallback, fontStyle = FontStyle.Italic)

    "strong" -> SpanStyle(color = colors.fallback, fontWeight = FontWeight.Bold)

    else -> null
}
