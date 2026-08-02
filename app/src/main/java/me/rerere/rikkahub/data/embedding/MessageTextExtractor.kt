package me.rerere.rikkahub.data.embedding

import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart

object MessageTextExtractor {
    fun messageToSearchText(message: UIMessage): String =
        message.parts
            .filterIsInstance<UIMessagePart.Text>()
            .joinToString("\n") { it.text }
            .take(10_000)
}
