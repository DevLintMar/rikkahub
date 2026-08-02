package me.rerere.ai.core

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import me.rerere.ai.provider.Model
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart

/** 工具执行输出。一次性工具由默认 adapter 只发 [Completed]；流式工具可先发 [OutputDelta]。 */
sealed interface ToolOutput {
    /** 增量输出：只给用户看（实时显示），绝不发给模型。 */
    data class OutputDelta(val text: String) : ToolOutput
    /** 生命周期提示（可选）。 */
    data class Progress(val message: String) : ToolOutput
    /** 唯一权威结果，发给模型。 */
    data class Completed(val parts: List<UIMessagePart>) : ToolOutput
}

@Serializable
data class Tool(
    val name: String,
    val description: String,
    val parameters: () -> InputSchema? = { null },
    val systemPrompt: (model: Model, messages: List<UIMessage>) -> String = { _, _ -> "" },
    val needsApproval: (JsonElement) -> Boolean = { false },
    val execute: suspend (JsonElement) -> List<UIMessagePart>,
    val executeFlow: suspend (JsonElement) -> Flow<ToolOutput> = { args ->
        flow { emit(ToolOutput.Completed(execute(args))) }
    },
)

@Serializable
sealed class InputSchema {
    @Serializable
    @SerialName("object")
    data class Obj(
        val properties: JsonObject,
        val required: List<String>? = null,
    ) : InputSchema()
}
