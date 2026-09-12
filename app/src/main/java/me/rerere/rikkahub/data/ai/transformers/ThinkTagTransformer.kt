package me.rerere.rikkahub.data.ai.transformers

import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import kotlin.time.Clock
import kotlin.time.Instant

// 只认"正文开头"的 <think>…</think>；**行内/后置的 <think> 一律当可见文本保留**。
// 这是上游 85402745「fix(thinking): ignore inline think tags」的有意行为（配 7 个单测，
// 见 ThinkTagTransformerTest），不是合并事故 —— 答题里字面提到 `<think>` 时不该被吞掉。
// 别把它改回 `<think>([\s\S]*?)(</think>|$)` 那种宽松匹配：那会让"字面标签"与"后置思考"
// 一起被抽走，直接挂掉 ThinkTagTransformerTest。
private val THINKING_REGEX = Regex("\\A\\s*<think>([\\s\\S]*?)(</think>|$)")

// 部分供应商不会返回reasoning parts, 所以需要这个transformer
object ThinkTagTransformer : OutputMessageTransformer {
    override suspend fun visualTransform(
        ctx: TransformerContext,
        messages: List<UIMessage>,
    ): List<UIMessage> {
        return messages.transformThinkTags(
            now = Clock.System.now(),
            generationFinished = false,
        )
    }

    override suspend fun onGenerationFinish(
        ctx: TransformerContext,
        messages: List<UIMessage>,
    ): List<UIMessage> {
        return messages.transformThinkTags(
            now = Clock.System.now(),
            generationFinished = true,
        )
    }
}

internal fun List<UIMessage>.transformThinkTags(
    now: Instant,
    generationFinished: Boolean,
): List<UIMessage> = map { message ->
    if (message.role != MessageRole.ASSISTANT) {
        return@map message
    }
    if (message.hasPart<UIMessagePart.Reasoning>()) {
        return@map if (generationFinished) {
            message.copy(
                parts = message.parts.map { part ->
                    if (part is UIMessagePart.Reasoning && part.finishedAt == null) {
                        part.copy(finishedAt = now)
                    } else {
                        part
                    }
                }
            )
        } else {
            message
        }
    }

    val textPartIndex = message.parts.indexOfFirst { part ->
        part is UIMessagePart.Text && part.text.isNotBlank()
    }
    val textPart = message.parts.getOrNull(textPartIndex) as? UIMessagePart.Text
        ?: return@map message
    val match = THINKING_REGEX.find(textPart.text) ?: return@map message
    val hasClosingTag = match.groups[2]?.value == "</think>"
    val reasoning = UIMessagePart.Reasoning(
        reasoning = match.groupValues[1].trim(),
        createdAt = message.createdAt.toInstant(timeZone = TimeZone.currentSystemDefault()),
        finishedAt = if (generationFinished || hasClosingTag) now else null,
    )
    val strippedText = textPart.copy(text = textPart.text.removeRange(match.range))

    message.copy(
        parts = buildList {
            addAll(message.parts.subList(0, textPartIndex))
            add(reasoning)
            add(strippedText)
            addAll(message.parts.subList(textPartIndex + 1, message.parts.size))
        }
    )
}
