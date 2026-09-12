package me.rerere.rikkahub.data.ai.tools

import me.rerere.ai.ui.UIMessagePart

/**
 * 工具输出直接回灌给模型时的硬上限（防单条 tool 结果撑爆上下文与 DB 行）。
 *
 * 硬性安全网：兜底 MCP / use_skill 这类不走信封的大输出。
 *
 * 主循环与子代理共用同一份实现：子代理链路此前完全没有上限，工具输出多大就原样拼进
 * 下一条 user 消息。注意这层裁剪在主循环里发生在**落盘之后** —— 先按 32KB 阈值把完整
 * 输出写进 /tool_outputs，再裁给模型看（顺序反了的话模型 cat 也拿不到全量）。
 */
internal const val MAX_TOOL_RESULT_LENGTH = 100_000

/** 把文本部分裁到 [MAX_TOOL_RESULT_LENGTH]，非文本部件原样保留。 */
internal fun clipToolOutput(parts: List<UIMessagePart>): List<UIMessagePart> {
    val textParts = parts.filterIsInstance<UIMessagePart.Text>()
    val nonTextParts = parts.filter { it !is UIMessagePart.Text }
    val totalChars = textParts.sumOf { it.text.length }
    if (totalChars <= MAX_TOOL_RESULT_LENGTH) return parts
    val clipped = textParts.joinToString("\n") { it.text }
        .take(MAX_TOOL_RESULT_LENGTH) + "…[truncated]"
    return listOf(UIMessagePart.Text(clipped)) + nonTextParts
}
