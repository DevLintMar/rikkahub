package me.rerere.rikkahub.data.ai.tools.local

import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart
import kotlin.uuid.Uuid

/**
 * 构建子代理工具。
 *
 * [availableTools] 是子代理可继承的工具列表（如搜索、时间信息、MCP、Skill）。
 *
 * 参数：prompt（必填）、description（必填）、run_in_background（可选）、model（可选）
 */
internal fun buildSubAgentTool(
    runtime: SubAgentRuntime,
    availableTools: List<Tool> = emptyList(),
    getConversationId: () -> Uuid = { error("sub_agent: conversationId not available") },
): Tool = Tool(
    name = "sub_agent",
    description = """
        Launch a new agent to handle complex, multi-step tasks.
        The agent runs independently with its own AI model instance.
        It has access to web search, time info, skills, and MCP tools.

        - Synchronous mode (default): blocks and waits for the agent to complete,
          then returns the result. Use this when you need the result before continuing.
        - Background mode (run_in_background=true): runs the agent in the background;
          you will be notified when it completes. Use this for independent tasks.

        When the agent finishes, relay what matters rather than dumping raw output.
        Do not fabricate or predict a pending agent's results.
    """.trimIndent().replace("\n", " "),
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("prompt", buildJsonObject {
                    put("type", "string")
                    put("description", "The complete, self-contained prompt for the agent. Include all necessary context.")
                })
                put("description", buildJsonObject {
                    put("type", "string")
                    put("description", "A short (3-5 word) description of the task")
                })
                put("run_in_background", buildJsonObject {
                    put("type", "boolean")
                    put("description", "If true, run in background and notify when done. If false (default), block and return result.")
                })
                put("model", buildJsonObject {
                    put("type", "string")
                    put("description", "Optional model UUID override for this agent. Uses the current model if not specified.")
                    // TODO: support model name enum like ["sonnet", "opus", "haiku"]
                })
            },
            required = listOf("prompt", "description")
        )
    },
    needsApproval = { true },
    execute = { args ->
        val obj = args.jsonObject
        val prompt = obj["prompt"]?.jsonPrimitive?.contentOrNull
            ?: error("sub_agent: 'prompt' parameter is required")
        val description = obj["description"]?.jsonPrimitive?.contentOrNull
            ?: error("sub_agent: 'description' parameter is required")

        val runInBackground = obj["run_in_background"]?.jsonPrimitive?.booleanOrNull ?: false
        // TODO: support model enum values
        val modelOverride = obj["model"]?.jsonPrimitive?.contentOrNull?.let {
            runCatching { Uuid.parse(it) }.getOrNull()
        }

        if (runInBackground) {
            val conversationId = getConversationId()
            val handle = runtime.executeAsync(
                prompt = prompt,
                description = description,
                conversationId = conversationId,
                modelOverride = modelOverride,
                tools = availableTools,
            )
            val payload = buildJsonObject {
                put("status", JsonPrimitive("started"))
                put("task_id", JsonPrimitive(handle.taskId))
                put("description", JsonPrimitive(description))
                put("mode", JsonPrimitive("background"))
            }
            listOf(UIMessagePart.Text(payload.toString()))
        } else {
            val result = runtime.executeSync(
                prompt = prompt,
                modelOverride = modelOverride,
                tools = availableTools,
            )
            val payload = buildJsonObject {
                put("status", JsonPrimitive(if (result.success) "completed" else "failed"))
                put("description", JsonPrimitive(description))
                put("mode", JsonPrimitive("synchronous"))
                if (result.success) {
                    put("result", JsonPrimitive(result.text))
                } else {
                    put("error", JsonPrimitive(result.error ?: "Unknown error"))
                }
            }
            listOf(UIMessagePart.Text(payload.toString()))
        }
    }
)
