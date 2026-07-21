package me.rerere.rikkahub.data.ai.tools.local

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart
import kotlin.uuid.Uuid

private val workflowJson = Json {
    ignoreUnknownKeys = true
    isLenient = true
}

/**
 * 构建工作流工具。
 *
 * conversationId 通过 [getConversationId] 提供，此函数在 ChatService 组合工具列表时
 * 被替换为捕获 conversationId 的闭包。
 *
 * 参数：workflow（必填，含 steps 和 parallel）
 */
internal fun buildWorkflowTool(
    engine: WorkflowEngine,
    getConversationId: () -> Uuid = { error("run_workflow: conversationId not available") },
): Tool = Tool(
    name = "run_workflow",
    description = """
        Execute a multi-step workflow that orchestrates multiple sub-agents.
        Each step defines a task for a sub-agent. Steps can run sequentially (default)
        or in parallel. The entire workflow requires only one user approval upfront.
        Results from all steps are returned in a structured format.

        Use this when a complex task can be broken into independent subtasks that
        sub-agents can work on simultaneously or in sequence.
    """.trimIndent().replace("\n", " "),
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("workflow", buildJsonObject {
                    put("type", "object")
                    put("description", "Workflow definition with steps and execution mode")
                    put("properties", buildJsonObject {
                        put("steps", buildJsonObject {
                            put("type", "array")
                            put("description", "List of workflow steps to execute")
                            put("items", buildJsonObject {
                                put("type", "object")
                                put("properties", buildJsonObject {
                                    put("type", buildJsonObject {
                                        put("type", "string")
                                        put("enum", buildJsonArray { add("agent") })
                                        put("description", "Step type: 'agent' for a sub-agent call")
                                    })
                                    put("task", buildJsonObject {
                                        put("type", "string")
                                        put("description", "The task for this step's sub-agent")
                                    })
                                    put("model", buildJsonObject {
                                        put("type", "string")
                                        put("description", "Optional model UUID override for this step")
                                    })
                                    put("run_in_background", buildJsonObject {
                                        put("type", "boolean")
                                        put("description", "If true, run this step in background")
                                    })
                                })
                                put("required", buildJsonArray { add("type"); add("task") })
                            })
                        })
                        put("parallel", buildJsonObject {
                            put("type", "boolean")
                            put("description", "If true, run all steps concurrently; if false (default), run sequentially")
                        })
                    })
                    put("required", buildJsonArray { add("steps") })
                })
            },
            required = listOf("workflow")
        )
    },
    needsApproval = { true },
    execute = { args ->
        val workflowJsonElement = args.jsonObject["workflow"]
            ?: error("run_workflow: 'workflow' parameter is required")

        val definition = workflowJson.decodeFromJsonElement<WorkflowDefinition>(workflowJsonElement)

        val convId = getConversationId()

        val results = engine.execute(definition, convId)

        val allSucceeded = results.all { it.success }
        val allFailed = results.all { !it.success }

        val stepsJson = buildJsonArray {
            results.forEach { stepResult ->
                add(buildJsonObject {
                    put("index", JsonPrimitive(stepResult.index))
                    put("task", JsonPrimitive(stepResult.step.task))
                    put("run_in_background", JsonPrimitive(stepResult.step.run_in_background))
                    if (stepResult.success) {
                        put("status", JsonPrimitive("completed"))
                        put("result", JsonPrimitive(stepResult.text))
                    } else {
                        put("status", JsonPrimitive("failed"))
                        put("error", JsonPrimitive(stepResult.error ?: "Unknown error"))
                    }
                })
            }
        }

        val topLevelStatus = when {
            allSucceeded -> "completed"
            allFailed -> "failed"
            else -> "partially_completed"
        }

        val payload = buildJsonObject {
            put("status", JsonPrimitive(topLevelStatus))
            put("parallel", JsonPrimitive(definition.parallel))
            put("steps", stepsJson)
        }
        listOf(UIMessagePart.Text(payload.toString()))
    }
)
