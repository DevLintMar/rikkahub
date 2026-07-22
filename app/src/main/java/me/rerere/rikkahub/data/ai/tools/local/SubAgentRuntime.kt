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
        private const val MAX_TOOL_STEPS = 10
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
        tools: List<Tool> = emptyList(),
    ): SubAgentResult = try {
        val settings = settingsStore.settingsFlow.first()
        val resolvedModelId = resolveModelId(modelId, settings)
        val model = settings.findModelById(resolvedModelId) ?: settings.findModelById(settings.chatModelId)
            ?: error("No model available for sub-agent")

        val providerSetting = model.findProvider(settings.providers)
            ?: error("Provider not found for model: ${model.id}")
        val provider = providerManager.getProviderByType(providerSetting)

        // 参照主 agent 的 system prompt 构建方式：
        //   基础 system prompt + 各工具追加的说明
        val systemPrompt = customSystemPrompt ?: DEFAULT_SYSTEM_PROMPT
        // 嵌入 system prompt 到 user message 中（对标标题生成/翻译的调用方式）
        val combinedMessage = "$systemPrompt\n\n$task"
        val messages = listOf(
            UIMessage.user(prompt = combinedMessage),
        )

        // 子代理工具循环：初始请求 + 处理工具调用
        var currentMessages = messages.toMutableList()
        var textResponse = ""
        for (step in 0 until MAX_TOOL_STEPS) {
            val result = provider.generateText(
                providerSetting = providerSetting,
                messages = currentMessages,
                params = TextGenerationParams(
                    model = model,
                    tools = if (step == 0) tools else emptyList(),
                )
            )

            val responseMsg = result.choices.firstOrNull()?.message
            val toolParts = responseMsg?.parts?.filterIsInstance<UIMessagePart.Tool>() ?: emptyList()
            val textParts = responseMsg?.parts?.filterIsInstance<UIMessagePart.Text>() ?: emptyList()

            // 收集文本回复
            textResponse = textParts.joinToString("") { it.text }

            // 没有工具调用 → 完成
            if (toolParts.isEmpty()) break

            // 执行工具并追加结果到对话
            currentMessages.add(responseMsg)
            for (toolPart in toolParts) {
                val toolDef = tools.find { it.name == toolPart.toolName }
                if (toolDef == null) {
                    currentMessages.add(
                        UIMessage.user(
                            prompt = "Tool '${toolPart.toolName}' not found."
                        )
                    )
                    continue
                }
                val output = toolDef.execute(runCatching {
                    Json.parseToJsonElement(toolPart.input)
                }.getOrDefault(buildJsonObject { }))
                currentMessages.add(
                    UIMessage.user(
                        prompt = output.joinToString("\n") { part ->
                            when (part) {
                                is UIMessagePart.Text -> part.text
                                else -> "[${part::class.simpleName}]"
                            }
                        }
                    )
                )
            }
        }

        SubAgentResult(success = true, text = textResponse)
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
        tools: List<Tool> = emptyList(),
    ): AsyncSubAgentHandle {
        val agentId = "sub_${Uuid.random().toString().take(8)}"
        val job = appScope.launch {
            val result = executeSync(task, customSystemPrompt, modelId, tools)
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
