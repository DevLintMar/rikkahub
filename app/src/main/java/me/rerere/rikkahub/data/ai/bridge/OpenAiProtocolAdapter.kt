package me.rerere.rikkahub.data.ai.bridge

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.MessageRole
import me.rerere.ai.core.ReasoningLevel
import me.rerere.ai.core.Tool
import me.rerere.ai.provider.TextGenerationResult
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import kotlin.time.Clock

@Serializable
data class OpenAiChatCompletionRequest(
    val model: String? = null,
    val messages: List<OpenAiMessageDto> = emptyList(),
    val tools: List<OpenAiToolDto>? = null,
    val temperature: Float? = null,
    @SerialName("top_p")
    val topP: Float? = null,
    @SerialName("max_tokens")
    val maxTokens: Int? = null,
    @SerialName("max_completion_tokens")
    val maxCompletionTokens: Int? = null,
    @SerialName("reasoning_effort")
    val reasoningEffort: String? = null,
)

@Serializable
data class OpenAiMessageDto(
    val role: String,
    val content: JsonElement? = null,
    @SerialName("tool_calls")
    val toolCalls: List<OpenAiToolCallDto>? = null,
    @SerialName("tool_call_id")
    val toolCallId: String? = null,
    val name: String? = null,
)

@Serializable
data class OpenAiToolDto(
    val type: String = "function",
    val function: OpenAiFunctionDto,
)

@Serializable
data class OpenAiFunctionDto(
    val name: String,
    val description: String? = null,
    val parameters: JsonObject? = null,
)

@Serializable
data class OpenAiToolCallDto(
    val id: String,
    val type: String = "function",
    val function: OpenAiFunctionCallDto,
)

@Serializable
data class OpenAiFunctionCallDto(
    val name: String,
    val arguments: String,
)

@Serializable
data class OpenAiChatCompletionResponse(
    val id: String,
    @SerialName("object")
    val objectType: String = "chat.completion",
    val created: Long,
    val model: String,
    val choices: List<OpenAiChoiceDto>,
    val usage: OpenAiUsageDto? = null,
)

@Serializable
data class OpenAiChoiceDto(
    val index: Int = 0,
    val message: OpenAiResponseMessageDto,
    @SerialName("finish_reason")
    val finishReason: String? = null,
)

@Serializable
data class OpenAiResponseMessageDto(
    val role: String = "assistant",
    val content: String? = null,
    @SerialName("tool_calls")
    val toolCalls: List<OpenAiToolCallDto>? = null,
)

@Serializable
data class OpenAiUsageDto(
    @SerialName("prompt_tokens")
    val promptTokens: Int = 0,
    @SerialName("completion_tokens")
    val completionTokens: Int = 0,
    @SerialName("total_tokens")
    val totalTokens: Int = 0,
)

object OpenAiProtocolAdapter {
    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    fun parseRequest(jsonText: String): OpenAiChatCompletionRequest {
        return json.decodeFromString(OpenAiChatCompletionRequest.serializer(), jsonText)
    }

    fun toUIMessages(dtos: List<OpenAiMessageDto>): List<UIMessage> {
        return dtos.map { dto ->
            val role = when (dto.role.lowercase()) {
                "system" -> MessageRole.SYSTEM
                "user" -> MessageRole.USER
                "assistant" -> MessageRole.ASSISTANT
                "tool" -> MessageRole.TOOL
                else -> MessageRole.USER
            }

            val parts = mutableListOf<UIMessagePart>()

            val textContent = when (val c = dto.content) {
                null -> ""
                else -> {
                    if (c is JsonObject && c.containsKey("text")) {
                        c["text"]?.jsonPrimitive?.content ?: ""
                    } else {
                        try {
                            c.jsonPrimitive.content
                        } catch (e: Exception) {
                            c.toString()
                        }
                    }
                }
            }

            if (textContent.isNotBlank()) {
                parts.add(UIMessagePart.Text(textContent))
            }

            dto.toolCalls?.forEach { tc ->
                parts.add(
                    UIMessagePart.Tool(
                        toolCallId = tc.id,
                        toolName = tc.function.name,
                        input = tc.function.arguments,
                    )
                )
            }

            UIMessage(
                role = role,
                parts = parts,
            )
        }
    }

    fun toTools(dtos: List<OpenAiToolDto>?): List<Tool> {
        if (dtos.isNullOrEmpty()) return emptyList()

        return dtos.map { dto ->
            val fn = dto.function
            val props = fn.parameters?.get("properties")?.let {
                if (it is JsonObject) it else null
            } ?: buildJsonObject {}

            val requiredList = fn.parameters?.get("required")?.let { req ->
                try {
                    req.jsonArray.map { it.jsonPrimitive.content }
                } catch (e: Exception) {
                    null
                }
            }

            Tool(
                name = fn.name,
                description = fn.description ?: "",
                parameters = {
                    InputSchema.Obj(
                        properties = props,
                        required = requiredList,
                    )
                },
                execute = { emptyList() },
            )
        }
    }

    fun toOpenAiResponse(
        result: TextGenerationResult,
        requestedModel: String?,
    ): OpenAiChatCompletionResponse {
        val parts = result.message.parts
        val textBuilder = StringBuilder()
        val toolCalls = mutableListOf<OpenAiToolCallDto>()

        parts.forEach { part ->
            when (part) {
                is UIMessagePart.Text -> {
                    textBuilder.append(part.text)
                }
                is UIMessagePart.Tool -> {
                    toolCalls.add(
                        OpenAiToolCallDto(
                            id = part.toolCallId,
                            type = "function",
                            function = OpenAiFunctionCallDto(
                                name = part.toolName,
                                arguments = part.input,
                            ),
                        )
                    )
                }
                else -> {}
            }
        }

        val contentStr = textBuilder.toString().ifBlank { null }
        val finishReason = when {
            toolCalls.isNotEmpty() -> "tool_calls"
            result.finishReason != null -> result.finishReason
            else -> "stop"
        }

        val responseMessage = OpenAiResponseMessageDto(
            role = "assistant",
            content = contentStr,
            toolCalls = toolCalls.ifEmpty { null },
        )

        val choice = OpenAiChoiceDto(
            index = 0,
            message = responseMessage,
            finishReason = finishReason,
        )

        val usage = result.usage?.let { u ->
            OpenAiUsageDto(
                promptTokens = u.promptTokens,
                completionTokens = u.completionTokens,
                totalTokens = u.totalTokens,
            )
        }

        return OpenAiChatCompletionResponse(
            id = result.id.ifBlank { "chatcmpl-rikkahub-" + Clock.System.now().toEpochMilliseconds() },
            objectType = "chat.completion",
            created = Clock.System.now().epochSeconds,
            model = requestedModel ?: result.model,
            choices = listOf(choice),
            usage = usage,
        )
    }

    fun serializeResponse(response: OpenAiChatCompletionResponse): String {
        return json.encodeToString(OpenAiChatCompletionResponse.serializer(), response)
    }

    /** Map an OpenAI `reasoning_effort` string onto RikkaHub's [ReasoningLevel]. */
    fun parseReasoningEffort(effort: String?): ReasoningLevel? {
        return when (effort?.trim()?.lowercase()) {
            null, "" -> null
            "none", "off" -> ReasoningLevel.OFF
            "auto" -> ReasoningLevel.AUTO
            "minimal", "low" -> ReasoningLevel.LOW
            "medium" -> ReasoningLevel.MEDIUM
            "high" -> ReasoningLevel.HIGH
            "xhigh" -> ReasoningLevel.XHIGH
            "max" -> ReasoningLevel.MAX
            else -> null
        }
    }
}
