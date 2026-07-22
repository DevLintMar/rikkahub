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
 * 同步模式不需要 conversationId；
 * 后台模式需要 conversationId（通过 [getConversationId] 提供，
 * 此函数在 ChatService 组合工具列表时被替换为捕获 conversationId 的闭包）。
 *
 * 参数：task（必填）、run_in_background（可选）、model_id（可选）、system_prompt（可选）
 */
internal fun buildSubAgentTool(
    runtime: SubAgentRuntime,
    availableTools: List<Tool> = emptyList(),
    getConversationId: () -> Uuid = { error("sub_agent: conversationId not available") },
): Tool = Tool(
    name = "sub_agent",
    description = """
        Launch an independent sub-agent to complete a task on your behalf.
        Give it a short, descriptive name via the 'name' parameter so you
        can identify the result when it completes (e.g. 'Research RAG').
        The sub-agent runs independently with its own AI model instance.
        It has access to web search, time info, skills, and MCP tools.

        - Synchronous mode (default): blocks and waits for the sub-agent to complete,
          then returns the result. Use this when you need the result before continuing.
        - Background mode (run_in_background=true): starts the sub-agent in the background
          and returns immediately. When it finishes, you'll see "Agent 'name' finished"
          and can review the results.

        Provide a clear, self-contained task description with all necessary context.
        The sub-agent does NOT have access to the current conversation history.
        Always requires user approval before execution.
    """.trimIndent().replace("\n", " "),
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("task", buildJsonObject {
                    put("type", "string")
                    put("description", "The complete, self-contained task for the sub-agent. Include all necessary context.")
                })
                put("name", buildJsonObject {
                    put("type", "string")
                    put("description", "A short, descriptive name for this agent task (e.g. 'Search AI news', 'Analyze code'). The agent will be referred to by this name when reporting results.")
                })
                put("run_in_background", buildJsonObject {
                    put("type", "boolean")
                    put("description", "If true, run in background and notify when done. If false (default), block and return result.")
                })
                put("model_id", buildJsonObject {
                    put("type", "string")
                    put("description", "Optional model UUID to use for the sub-agent. Uses the current model if not specified.")
                })
                put("system_prompt", buildJsonObject {
                    put("type", "string")
                    put("description", "Optional custom system prompt for the sub-agent.")
                })
            },
            required = listOf("task")
        )
    },
    needsApproval = { true },
    execute = { args ->
        val obj = args.jsonObject
        val task = obj["task"]?.jsonPrimitive?.contentOrNull
            ?: error("sub_agent: 'task' parameter is required")

        val runInBackground = obj["run_in_background"]?.jsonPrimitive?.booleanOrNull ?: false
        val customModelId = obj["model_id"]?.jsonPrimitive?.contentOrNull?.let {
            runCatching { Uuid.parse(it) }.getOrNull()
        }
        val customSystemPrompt = obj["system_prompt"]?.jsonPrimitive?.contentOrNull
        val agentName = obj["name"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() } ?: task.take(30)

        if (runInBackground) {
            val conversationId = getConversationId()
            val handle = runtime.executeAsync(
                task = task,
                agentName = agentName,
                conversationId = conversationId,
                customSystemPrompt = customSystemPrompt,
                modelId = customModelId,
                tools = availableTools,
            )
            val payload = buildJsonObject {
                put("status", JsonPrimitive("started"))
                put("agent_id", JsonPrimitive(handle.agentId))
                put("task", JsonPrimitive(task))
                put("mode", JsonPrimitive("background"))
            }
            listOf(UIMessagePart.Text(payload.toString()))
        } else {
            val result = runtime.executeSync(
                task = task,
                customSystemPrompt = customSystemPrompt,
                modelId = customModelId,
                tools = availableTools,
            )
            val payload = buildJsonObject {
                put("status", JsonPrimitive(if (result.success) "completed" else "failed"))
                put("task", JsonPrimitive(task))
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
