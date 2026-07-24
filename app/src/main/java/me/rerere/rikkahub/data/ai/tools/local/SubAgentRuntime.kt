package me.rerere.rikkahub.data.ai.tools.local

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import me.rerere.ai.core.Tool
import me.rerere.ai.provider.ProviderManager
import me.rerere.ai.provider.TextGenerationParams
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.ai.ui.handleMessageChunk
import me.rerere.rikkahub.AppScope
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.datastore.findModelById
import me.rerere.rikkahub.data.datastore.findProvider
import me.rerere.rikkahub.data.event.AppEvent
import me.rerere.rikkahub.data.event.AppEventBus
import java.util.concurrent.ConcurrentHashMap
import kotlin.uuid.Uuid

data class SubAgentResult(
    val success: Boolean,
    val text: String,
    val error: String? = null,
)

data class AsyncSubAgentHandle(
    val taskId: String,
    val job: Job,
)

enum class TaskStatus { IN_PROGRESS, COMPLETED, FAILED }

data class TaskInfo(
    val taskId: String,
    val description: String,
    val prompt: String,
    val status: TaskStatus,
    val result: String? = null,
    val error: String? = null,
)

class SubAgentRuntime(
    private val providerManager: ProviderManager,
    private val settingsStore: SettingsStore,
    private val appScope: AppScope,
    private val eventBus: AppEventBus,
) {
    companion object {
        private val DEFAULT_SYSTEM_PROMPT = """
            You are a helpful sub-agent. Complete the following task concisely and accurately.
            You can use the available tools to gather information when needed.
            After using tools, synthesize the results and provide your final answer.
            Keep your response focused on the task.
        """.trimIndent()
    }

    /** 所有异步任务的追踪状态 */
    private val tasks = ConcurrentHashMap<String, TaskInfo>()

    suspend fun executeSync(
        prompt: String,
        modelOverride: Uuid? = null,
        tools: List<Tool> = emptyList(),
        systemPrompt: String? = null,
    ): SubAgentResult = try {
        val settings = settingsStore.settingsFlow.first()
        val resolvedModelId = resolveModelId(modelOverride, settings)
        val model = settings.findModelById(resolvedModelId) ?: settings.findModelById(settings.chatModelId)
            ?: error("No model available for sub-agent")

        val providerSetting = model.findProvider(settings.providers)
            ?: error("Provider not found for model: ${model.id}")
        val provider = providerManager.getProviderByType(providerSetting)

        val effectiveSystemPrompt = systemPrompt ?: DEFAULT_SYSTEM_PROMPT
        val combinedMessage = "$effectiveSystemPrompt\n\n$prompt"
        val messages = listOf(
            UIMessage.user(prompt = combinedMessage),
        )

        var currentMessages: List<UIMessage> = messages
        var textResponse = ""
        while (true) {
            val resultFlow = provider.streamText(
                providerSetting = providerSetting,
                messages = currentMessages,
                params = TextGenerationParams(
                    model = model,
                    tools = tools,
                )
            )
            resultFlow.collect { chunk ->
                currentMessages = currentMessages.handleMessageChunk(chunk, model)
            }

            val lastMsg = currentMessages.lastOrNull() ?: break
            val toolParts = lastMsg.parts.filterIsInstance<UIMessagePart.Tool>()
            val textParts = lastMsg.parts.filterIsInstance<UIMessagePart.Text>()

            textResponse = textParts.joinToString("") { it.text }

            if (toolParts.isEmpty()) break

            for (toolPart in toolParts) {
                val toolDef = tools.find { it.name == toolPart.toolName }
                if (toolDef == null) {
                    currentMessages = currentMessages + UIMessage.user(prompt = "Tool '${toolPart.toolName}' not found.")
                    continue
                }
                val output = toolDef.execute(runCatching {
                    Json.parseToJsonElement(toolPart.input)
                }.getOrDefault(buildJsonObject { }))
                currentMessages = currentMessages + UIMessage.user(
                    prompt = output.joinToString("\n") { part ->
                        when (part) {
                            is UIMessagePart.Text -> part.text
                            else -> "[${part::class.simpleName}]"
                        }
                    }
                )
            }
        }

        SubAgentResult(success = true, text = textResponse)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        SubAgentResult(success = false, text = "", error = e.message ?: "Unknown error")
    }

    fun executeAsync(
        prompt: String,
        description: String,
        conversationId: Uuid,
        modelOverride: Uuid? = null,
        tools: List<Tool> = emptyList(),
        systemPrompt: String? = null,
    ): AsyncSubAgentHandle {
        val taskId = "sub_${Uuid.random().toString().take(8)}"
        tasks[taskId] = TaskInfo(
            taskId = taskId,
            description = description,
            prompt = prompt,
            status = TaskStatus.IN_PROGRESS,
        )
        val job = appScope.launch {
            val result = executeSync(prompt = prompt, modelOverride = modelOverride, tools = tools, systemPrompt = systemPrompt)
            tasks[taskId] = tasks.getValue(taskId).copy(
                status = if (result.success) TaskStatus.COMPLETED else TaskStatus.FAILED,
                result = if (result.success) result.text else null,
                error = if (!result.success) result.error else null,
            )
            eventBus.emit(
                AppEvent.SubAgentCompleted(
                    conversationId = conversationId,
                    taskId = taskId,
                    description = description,
                    prompt = prompt,
                    result = if (result.success) result.text else "Error: ${result.error ?: "Unknown"}",
                    success = result.success,
                )
            )
        }
        return AsyncSubAgentHandle(taskId = taskId, job = job)
    }

    /**
     * 解析模型 ID。
     * 优先级：modelOverride > settings.subAgentModelId > settings.chatModelId
     */
    private fun resolveModelId(modelOverride: Uuid?, settings: Settings): Uuid? {
        if (modelOverride != null) return modelOverride
        return settings.subAgentModelId
    }

    /** 供 TaskList/TaskGet 工具读取的任务列表快照 */
    fun getTaskInfos(): List<TaskInfo> = tasks.values.toList()

    /** 供 TaskGet 工具读取的单个任务详情 */
    fun getTaskInfo(taskId: String): TaskInfo? = tasks[taskId]
}
