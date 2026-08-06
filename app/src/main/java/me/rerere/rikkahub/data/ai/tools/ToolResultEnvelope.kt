package me.rerere.rikkahub.data.ai.tools

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import me.rerere.ai.ui.ToolState
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.utils.JsonInstant

/** 从工具输出文本解析信封 JSON。非 JSON 或空返回 null。 */
fun parseEnvelope(parts: List<UIMessagePart>): JsonObject? {
    val text = parts.filterIsInstance<UIMessagePart.Text>().joinToString("\n").trim()
    if (text.isBlank()) return null
    return runCatching { JsonInstant.parseToJsonElement(text).jsonObject }.getOrNull()
}

/** 从结果信封粗推断工具状态（展示层再细化 EMPTY/STOPPED）。 */
fun inferToolState(parts: List<UIMessagePart>): ToolState {
    if (parts.isEmpty()) return ToolState.EMPTY
    val envelope = parseEnvelope(parts) ?: return ToolState.SUCCEEDED
    if (envelope["error"] != null) return ToolState.FAILED
    val exitCode = (envelope["exitCode"] as? JsonPrimitive)?.intOrNull
    if (exitCode != null && exitCode != 0) return ToolState.FAILED
    return ToolState.SUCCEEDED
}

/** 工具错误消息：只保留原因本身，不暴露异常类名等堆栈细节 */
fun toolErrorMessage(throwable: Throwable): String =
    throwable.message ?: throwable.javaClass.simpleName
