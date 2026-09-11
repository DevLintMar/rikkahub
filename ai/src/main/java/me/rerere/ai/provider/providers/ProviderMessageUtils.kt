package me.rerere.ai.provider.providers

import me.rerere.ai.provider.Modality
import me.rerere.ai.ui.UIMessagePart

/**
 * 工具结果图片被搬到紧随其后的 user 消息时，tool 消息里的占位说明。
 */
const val TOOL_RESULT_IMAGE_ATTACHED_NOTE = "[Image output attached in the following user message]"

/**
 * 模型不支持图片输入时，工具结果图片在 tool 消息里的占位说明。
 */
const val TOOL_RESULT_IMAGE_OMITTED_NOTE = "[Image output omitted: current model does not support image input]"

/**
 * tool 消息里能携带的文本。
 *
 * OpenAI Chat Completions 的 tool 角色 content **仅支持文本**（API reference："The content for
 * these messages is restricted to text format"），把 image_url 塞进 tool 消息会被服务端拒绝
 * （常见 400："Invalid input"（param=messages.N.content）、"Invalid content type"、
 * LM Studio 的 "Invalid 'messages' in payload"）。因此图片一律不放进 tool 消息，
 * 由 [toolResultImagesForUserMessage] 取出、作为**所有 tool 消息之后**的一条 user 消息随附。
 */
fun UIMessagePart.Tool.toolResultText(supportInputModalities: List<Modality>): String =
    output.mapNotNull { part ->
        when (part) {
            is UIMessagePart.Text -> part.text.takeIf { it.isNotBlank() }
            is UIMessagePart.Image ->
                if (Modality.IMAGE in supportInputModalities) TOOL_RESULT_IMAGE_ATTACHED_NOTE
                else TOOL_RESULT_IMAGE_OMITTED_NOTE

            else -> null
        }
    }.joinToString("\n")

/**
 * 需要搬出 tool 消息、改由后续 user 消息随附的工具结果图片；模型不支持图片输入时返回空列表
 * （此时 [toolResultText] 已给出省略说明）。
 */
fun UIMessagePart.Tool.toolResultImagesForUserMessage(
    supportInputModalities: List<Modality>,
): List<UIMessagePart.Image> =
    if (Modality.IMAGE in supportInputModalities) output.filterIsInstance<UIMessagePart.Image>()
    else emptyList()

/**
 * 消息 parts 按工具边界分组的结果
 * - Content: 普通内容（Text、Image、Reasoning 等）
 * - Tools: 连续的已执行工具
 */
internal sealed class PartGroup {
    data class Content(val parts: List<UIMessagePart>) : PartGroup()
    data class Tools(val tools: List<UIMessagePart.Tool>) : PartGroup()
}

/**
 * 将消息 parts 按工具边界分组
 *
 * 例如 [Text1, Tool1, Tool2, Text2, Tool3] 会分组为:
 * - Content([Text1])
 * - Tools([Tool1, Tool2])
 * - Content([Text2])
 * - Tools([Tool3])
 *
 * 这样可以确保 tool_use/functionCall 后面紧跟 tool_result/functionResponse
 */
internal fun groupPartsByToolBoundary(parts: List<UIMessagePart>): List<PartGroup> {
    val groups = mutableListOf<PartGroup>()
    val currentContent = mutableListOf<UIMessagePart>()
    val currentTools = mutableListOf<UIMessagePart.Tool>()

    fun flushContent() {
        if (currentContent.isNotEmpty()) {
            groups.add(PartGroup.Content(currentContent.toList()))
            currentContent.clear()
        }
    }

    fun flushTools() {
        if (currentTools.isNotEmpty()) {
            groups.add(PartGroup.Tools(currentTools.toList()))
            currentTools.clear()
        }
    }

    for (part in parts) {
        if (part is UIMessagePart.Tool && part.isExecuted) {
            flushContent()
            currentTools.add(part)
        } else {
            flushTools()
            currentContent.add(part)
        }
    }

    flushContent()
    flushTools()
    return groups
}
