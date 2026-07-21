package me.rerere.rikkahub.data.ai.tools.local

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import me.rerere.ai.core.MessageRole
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
import kotlin.uuid.Uuid

/**
 * 子代理执行请求的结果。
 */
data class SubAgentResult(
    val success: Boolean,
    val text: String,
    val error: String? = null,
)

/**
 * 异步子代理的 handle，用于追踪执行状态。
 */
data class AsyncSubAgentHandle(
    val agentId: String,
    val job: Job,
)

/**
 * 子代理执行运行时。
 * 由 SubAgentTool 和 WorkflowEngine 共享使用。
 *
 * @param providerManager 提供者管理器
 * @param settingsStore 设置存储
 * @param appScope 应用级协程作用域
 * @param eventBus 事件总线
 */
class SubAgentRuntime(
    private val providerManager: ProviderManager,
    private val settingsStore: SettingsStore,
    private val appScope: AppScope,
    private val eventBus: AppEventBus,
) {
    companion object {
        private val DEFAULT_SYSTEM_PROMPT = """
            You are a helpful sub-agent. Complete the following task concisely and accurately.
            Do not ask follow-up questions or request clarification.
            Provide your best answer based on your knowledge and the instructions given.
            Keep your response focused on the task.
        """.trimIndent()
    }

    /**
     * 同步执行子代理。
     * 阻塞当前协程直到子代理完成。
     */
    suspend fun executeSync(
        task: String,
        customSystemPrompt: String? = null,
        modelId: Uuid? = null,
    ): SubAgentResult = try {
        val settings = settingsStore.settingsFlow.first()
        val resolvedModelId = resolveModelId(modelId, settings)
        val model = settings.findModelById(resolvedModelId) ?: settings.findModelById(settings.chatModelId)
            ?: error("No model available for sub-agent")

        val providerSetting = model.findProvider(settings.providers)
            ?: error("Provider not found for model: ${model.id}")
        val provider = providerManager.getProviderByType(providerSetting)

        val systemPrompt = customSystemPrompt ?: DEFAULT_SYSTEM_PROMPT
        val messages = listOf(
            UIMessage(role = MessageRole.SYSTEM, parts = listOf(UIMessagePart.Text(systemPrompt))),
            UIMessage(role = MessageRole.USER, parts = listOf(UIMessagePart.Text(task)))
        )

        val result = provider.generateText(
            providerSetting = providerSetting,
            messages = messages,
            params = TextGenerationParams(model = model, tools = emptyList())
        )

        val responseMessages = emptyList<UIMessage>().handleMessageChunk(result, model)
        val text = responseMessages.lastOrNull()?.parts?.joinToString("") { part ->
            when (part) {
                is UIMessagePart.Text -> part.text
                else -> ""
            }
        } ?: ""

        SubAgentResult(success = true, text = text)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        SubAgentResult(success = false, text = "", error = e.message ?: "Unknown error")
    }

    /**
     * 异步执行子代理。
     * 立即返回 handle，后台执行完成后通过 AppEventBus 发送通知。
     */
    fun executeAsync(
        task: String,
        conversationId: Uuid,
        customSystemPrompt: String? = null,
        modelId: Uuid? = null,
    ): AsyncSubAgentHandle {
        val agentId = "sub_${Uuid.random().toString().take(8)}"
        val job = appScope.launch {
            val result = executeSync(task, customSystemPrompt, modelId)
            eventBus.emit(
                AppEvent.SubAgentCompleted(
                    conversationId = conversationId,
                    agentId = agentId,
                    task = task,
                    result = if (result.success) result.text else "Error: ${result.error ?: "Unknown"}",
                    success = result.success,
                )
            )
        }
        return AsyncSubAgentHandle(agentId = agentId, job = job)
    }

    /**
     * 解析模型 ID。
     * 优先级：customModelId > settings.subAgentModelId > settings.chatModelId
     */
    private fun resolveModelId(customModelId: Uuid?, settings: Settings): Uuid? {
        if (customModelId != null) return customModelId
        return settings.subAgentModelId
    }
}
