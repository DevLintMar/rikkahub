package me.rerere.rikkahub.data.ai.tools.local

import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart

internal fun buildTaskListTool(runtime: SubAgentRuntime): Tool = Tool(
    name = "task_list",
    description = """
        List all sub-agent tasks and their current status.
        Each task shows task_id, description, and status (in_progress / completed / failed).
        Use this to check on background sub-agents you launched.

        Returns a summary of each task:
        - task_id: Task identifier (use with task_get)
        - description: Brief description of the task
        - status: 'in_progress', 'completed', or 'failed'
    """.trimIndent().replace("\n", " "),
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject { },
            required = emptyList()
        )
    },
    execute = {
        val infos = runtime.getTaskInfos()
        val payload = buildJsonObject {
            put("tasks", buildJsonArray {
                infos.forEach { info ->
                    add(buildJsonObject {
                        put("task_id", JsonPrimitive(info.taskId))
                        put("description", JsonPrimitive(info.description))
                        put("status", JsonPrimitive(info.status.name.lowercase()))
                        if (info.result != null) put("result", JsonPrimitive(info.result.take(200)))
                        if (info.error != null) put("error", JsonPrimitive(info.error))
                    })
                }
            })
        }
        listOf(UIMessagePart.Text(payload.toString()))
    }
)

internal fun buildTaskGetTool(runtime: SubAgentRuntime): Tool = Tool(
    name = "task_get",
    description = """
        Get details and full results for a specific sub-agent task by task_id.
        Use this when task_list shows a task has completed and you need its full output.

        Returns full task details:
        - task_id: Task identifier
        - description: Brief description of the task
        - status: 'in_progress', 'completed', or 'failed'
        - result: Full output text (if completed)
        - error: Error message (if failed)
    """.trimIndent().replace("\n", " "),
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("task_id", buildJsonObject {
                    put("type", "string")
                    put("description", "The ID of the sub-agent task to retrieve")
                })
            },
            required = listOf("task_id")
        )
    },
    execute = { args ->
        val taskId = args.jsonObject["task_id"]?.jsonPrimitive?.contentOrNull
            ?: error("task_get: 'task_id' parameter is required")
        val info = runtime.getTaskInfo(taskId)
        if (info == null) {
            return@Tool listOf(UIMessagePart.Text("{\"error\":\"Task not found: $taskId\"}"))
        }
        val payload = buildJsonObject {
            put("task_id", JsonPrimitive(info.taskId))
            put("description", JsonPrimitive(info.description))
            put("status", JsonPrimitive(info.status.name.lowercase()))
            if (info.result != null) put("result", JsonPrimitive(info.result))
            if (info.error != null) put("error", JsonPrimitive(info.error))
        }
        listOf(UIMessagePart.Text(payload.toString()))
    }
)
