package me.rerere.rikkahub.data.ai.tools.local

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.Serializable
import kotlin.uuid.Uuid

/**
 * 工作流步骤定义。
 */
@Serializable
data class WorkflowStep(
    val type: String,          // "agent"
    val task: String,
    val model: String? = null, // optional model UUID override
    val run_in_background: Boolean = false,
)

/**
 * 工作流定义。
 */
@Serializable
data class WorkflowDefinition(
    val steps: List<WorkflowStep>,
    val parallel: Boolean = false,
)

/**
 * 单个步骤的执行结果。
 */
data class StepResult(
    val index: Int,
    val step: WorkflowStep,
    val success: Boolean,
    val text: String,
    val error: String? = null,
)

/**
 * 工作流执行引擎。
 * 解析 WorkflowDefinition 并按序或并发执行各步骤。
 */
class WorkflowEngine(
    val runtime: SubAgentRuntime,
) {
    /**
     * 执行完整的工作流定义。
     *
     * @param definition 工作流定义
     * @param conversationId 所在对话 ID（用于异步 recall）
     * @return 各步骤结果列表
     */
    suspend fun execute(
        definition: WorkflowDefinition,
        conversationId: Uuid,
    ): List<StepResult> {
        return if (definition.parallel) {
            executeParallel(definition.steps, conversationId)
        } else {
            executeSequential(definition.steps, conversationId)
        }
    }

    private suspend fun executeSequential(
        steps: List<WorkflowStep>,
        conversationId: Uuid,
    ): List<StepResult> {
        val results = mutableListOf<StepResult>()
        for ((index, step) in steps.withIndex()) {
            val result = executeStep(index, step, conversationId)
            results.add(result)
        }
        return results
    }

    private suspend fun executeParallel(
        steps: List<WorkflowStep>,
        conversationId: Uuid,
    ): List<StepResult> = coroutineScope {
        val deferreds = steps.mapIndexed { index, step ->
            async {
                executeStep(index, step, conversationId)
            }
        }
        deferreds.awaitAll()
    }

    private suspend fun executeStep(
        index: Int,
        step: WorkflowStep,
        conversationId: Uuid,
    ): StepResult {
        if (step.type != "agent") {
            return StepResult(
                index = index,
                step = step,
                success = false,
                text = "",
                error = "Unsupported step type: ${step.type}. Only 'agent' is supported.",
            )
        }

        val modelId = step.model?.let { runCatching { Uuid.parse(it) }.getOrNull() }

        return if (step.run_in_background) {
            // Background mode: start async
            val handle = runtime.executeAsync(
                task = step.task,
                conversationId = conversationId,
                modelId = modelId,
            )
            StepResult(
                index = index,
                step = step,
                success = true,
                text = "[Background task started: agent_id=${handle.agentId}, task: ${step.task.take(50)}...]",
            )
        } else {
            // Synchronous mode: block and wait
            val result = runtime.executeSync(
                task = step.task,
                modelId = modelId,
            )
            StepResult(
                index = index,
                step = step,
                success = result.success,
                text = if (result.success) result.text else "",
                error = if (!result.success) result.error else null,
            )
        }
    }
}
