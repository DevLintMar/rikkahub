package me.rerere.rikkahub.ui.pages.chat

import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessagePart
import me.rerere.highlight.Highlighter
import me.rerere.highlight.prewarmHighlight
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.model.AssistantAffectScope
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.data.model.replaceRegexes
import me.rerere.rikkahub.ui.components.richtext.collectCodeFences
import me.rerere.rikkahub.ui.components.richtext.prewarmMarkdown
import me.rerere.rikkahub.utils.JsonInstant

/**
 * 切换对话遮罩期间后台预热：把对话所有 markdown 解析与代码高亮写入进程级缓存，
 * 露出后 MarkdownBlock/HighlightText 首帧即命中，滚动无首轮解析卡顿。
 * 预热顺序：最新（底部，打开时可见区）优先，超时部分由运行时兜底。
 */
suspend fun prewarmConversation(
    conversation: Conversation,
    assistant: Assistant?,
    highlighter: Highlighter,
) {
    conversation.currentMessages.asReversed().forEach { message ->
        val scope = if (message.role == MessageRole.USER) AssistantAffectScope.USER else AssistantAffectScope.ASSISTANT
        message.parts.forEach { part ->
            when (part) {
                is UIMessagePart.Text -> prewarmText(part.text, assistant, scope, highlighter)
                is UIMessagePart.Reasoning -> prewarmText(part.reasoning, assistant, AssistantAffectScope.ASSISTANT, highlighter)
                is UIMessagePart.Tool -> prewarmToolOutput(highlighter, part)
                else -> {}
            }
        }
    }
}

private suspend fun prewarmText(
    text: String,
    assistant: Assistant?,
    scope: AssistantAffectScope,
    highlighter: Highlighter,
) {
    val transformed = text.replaceRegexes(assistant, scope, visual = true)
    prewarmMarkdown(transformed)
    collectCodeFences(transformed).forEach { (code, language) ->
        prewarmHighlight(highlighter, code, language)
    }
}

private suspend fun prewarmToolOutput(highlighter: Highlighter, tool: UIMessagePart.Tool) {
    val text = tool.output.filterIsInstance<UIMessagePart.Text>().joinToString("\n") { it.text }.trim()
    if (text.isBlank()) return
    // 与 DefaultToolPreview 一致：非 JSON 输出才走 HighlightCodeBlock(plaintext)
    if (runCatching { JsonInstant.parseToJsonElement(text) }.isFailure) {
        prewarmHighlight(highlighter, text, "plaintext")
    }
}
