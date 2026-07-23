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

        ## When to use
        Reach for this when the task matches an available agent type, when you
        have independent work to run in parallel, or when answering would mean
        reading across several files — delegate it and you keep the conclusion,
        not the file dumps. For a single-fact lookup where you already know the
        file, symbol, or value, search directly. Once you've delegated a search,
        don't also run it yourself — wait for the result.

        - The agent's final report is not shown to the user — relay what matters.
        - Subagents run in the background by default; you'll be notified when one
          completes. Pass run_in_background: false for a synchronous run when you
          need the result before continuing. Never fabricate or predict a pending
          agent's results — the notification is never something you write yourself;
          if the user asks before it arrives, say it's still running.
    """.trimIndent().replace("\n", " "),
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("description", buildJsonObject {
                    put("type", "string")
                    put("description", "A short (3-5 word) description of the task")
                })
                put("prompt", buildJsonObject {
                    put("type", "string")
                    put("description", "The task for the agent to perform")
                })
                put("model", buildJsonObject {
                    put("type", "string")
                    put("description", "Optional model UUID override for this agent. Uses the current model if not specified.")
                    // TODO: support model name enum like ["sonnet", "opus", "haiku"]
                })
                put("run_in_background", buildJsonObject {
                    put("type", "boolean")
                    put("description", "Agents run in the background by default; you will be notified when one completes. Set to false to run this agent synchronously when you need its result before continuing.")
                })
            },
            required = listOf("description", "prompt")
        )
    },
    needsApproval = { true },
    execute = { args ->
        val obj = args.jsonObject
        val prompt = obj["prompt"]?.jsonPrimitive?.contentOrNull
            ?: error("sub_agent: 'prompt' parameter is required")
        val description = obj["description"]?.jsonPrimitive?.contentOrNull
            ?: error("sub_agent: 'description' parameter is required")

        val runInBackground = obj["run_in_background"]?.jsonPrimitive?.booleanOrNull ?: true
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
