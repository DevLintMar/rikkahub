package me.rerere.rikkahub.data.ai.tools.local

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import me.rerere.ai.core.ReasoningLevel
import me.rerere.ai.core.Tool
import me.rerere.ai.provider.ProviderManager
import me.rerere.ai.provider.TextGenerationParams
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.ai.ui.StreamChunkHandler
import me.rerere.rikkahub.AppScope
import me.rerere.rikkahub.data.ai.tools.clipToolOutput
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.datastore.findModelById
import me.rerere.rikkahub.data.datastore.findProvider
import me.rerere.rikkahub.data.datastore.getCurrentAssistant
import me.rerere.rikkahub.data.event.AppEvent
import me.rerere.rikkahub.data.event.AppEventBus
import me.rerere.rikkahub.service.GenerationKeepAlive
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


class SubAgentRuntime(
    private val providerManager: ProviderManager,
    private val settingsStore: SettingsStore,
    private val appScope: AppScope,
    private val eventBus: AppEventBus,
    private val registry: SubAgentTaskRegistry,
    private val keepAlive: GenerationKeepAlive,
) {
    companion object {
        private val DEFAULT_SYSTEM_PROMPT = """
            You are a helpful sub-agent. Complete the following task concisely and accurately.
            You can use the available tools to gather information when needed.
            After using tools, synthesize the results and provide your final answer.
            Keep your response focused on the task.
        """.trimIndent()
    }

    /** 正在跑的 job，用于「取消任务」。与 registry 同生命周期，两者一起在终态时移除。 */
    private val jobs = ConcurrentHashMap<String, Job>()

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

        // 参考主 agent 的 generateInternal 构建系统提示 + 注入 tool.systemPrompt
        val effectiveSystemPrompt = systemPrompt ?: DEFAULT_SYSTEM_PROMPT
        val fullSystemPrompt = buildString {
            append(effectiveSystemPrompt)
            tools.forEach { tool ->
                val toolPrompt = tool.systemPrompt(model, emptyList())
                if (toolPrompt.isNotBlank()) {
                    appendLine()
                    append(toolPrompt)
                }
            }
        }
        val messages = buildList {
            if (fullSystemPrompt.isNotBlank()) {
                add(UIMessage.system(prompt = fullSystemPrompt))
            }
            add(UIMessage.user(prompt = prompt))
        }

        // 参考主 agent 传入完整的 TextGenerationParams
        val assistant = settings.getCurrentAssistant()
        val params = TextGenerationParams(
            model = model,
            temperature = assistant.temperature,
            topP = assistant.topP,
            maxTokens = assistant.maxTokens,
            tools = tools,
            reasoningLevel = assistant.reasoningLevel,
            // 与主循环一样带上会话 id（provider 侧映射成 X-Session-ID 等请求头），
            // 同一次子代理任务的多轮请求因此落在同一会话上，prompt cache 能命中
            sessionId = Uuid.random().toString(),
        )

        var currentMessages: List<UIMessage> = messages
        var textResponse = ""
        while (true) {
            val resultFlow = provider.streamText(
                providerSetting = providerSetting,
                messages = currentMessages,
                params = params,
            )
            // 上游把流式处理收敛到 StreamChunkHandler（每次尝试新建，与 GenerationLoop 一致）
            val streamChunkHandler = StreamChunkHandler(model)
            resultFlow.collect { chunk ->
                currentMessages = streamChunkHandler.handle(currentMessages, chunk)
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
                val output = runCatching {
                    val args = runCatching {
                        Json.parseToJsonElement(toolPart.input)
                    }.getOrDefault(buildJsonObject { })
                    toolDef.execute(args)
                }.onFailure { error ->
                    if (error is CancellationException) throw error
                }.getOrElse { error ->
                    listOf(UIMessagePart.Text(
                        """{"error":"[${error.javaClass.name}] ${error.message ?: "Unknown error"}","stack":"${error.stackTraceToString().replace("\"", "\\\"")}"}"""
                    ))
                }
                // 子代理此前完全没有上限：工具输出有多大就原样拼进下一条 user 消息。
                // 与主循环共用 clipToolOutput，一次大输出不再撑爆上下文。
                currentMessages = currentMessages + UIMessage.user(
                    prompt = clipToolOutput(output).joinToString("\n") { part ->
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
        registry.register(
            taskId = taskId,
            conversationId = conversationId,
            description = description,
            prompt = prompt,
        )
        val job = appScope.launch {
            // 子代理的网络流必须自己持有前台服务：父生成一结束就会释放它，而任务可能还要跑很久。
            val token = keepAlive.hold(conversationId, backgroundTask = true)
            try {
                val result = executeSync(
                    prompt = prompt,
                    modelOverride = modelOverride,
                    tools = tools,
                    systemPrompt = systemPrompt,
                )
                finish(
                    taskId = taskId,
                    status = if (result.success) TaskStatus.COMPLETED else TaskStatus.FAILED,
                    reason = null,
                    result = result.text.takeIf { result.success },
                    error = result.error.takeIf { !result.success },
                )
            } catch (e: CancellationException) {
                // 用户点了「取消任务」：仍然要留一条终态回执，否则会话里永远停在 started。
                withContext(NonCancellable) {
                    finish(
                        taskId = taskId,
                        status = TaskStatus.FAILED,
                        reason = SubAgentFailReason.USER_CANCELLED,
                        result = null,
                        error = "cancelled",
                    )
                }
                throw e
            } finally {
                keepAlive.release(token)
                jobs.remove(taskId)
            }
        }
        jobs[taskId] = job
        return AsyncSubAgentHandle(taskId = taskId, job = job)
    }

    /** 取消一个还在跑的任务。返回 false 表示任务不存在或已经到达终态。 */
    fun cancel(taskId: String): Boolean {
        val job = jobs.remove(taskId) ?: return false
        job.cancel()
        return true
    }

    private suspend fun finish(
        taskId: String,
        status: TaskStatus,
        reason: SubAgentFailReason?,
        result: String?,
        error: String?,
    ) {
        val info = registry.finish(taskId, status, reason, result, error) ?: return
        eventBus.emit(
            AppEvent.SubAgentTaskFinished(
                conversationId = info.conversationId,
                taskId = info.taskId,
                description = info.description,
                status = status,
                reason = reason,
                result = result,
                error = error,
            )
        )
    }

    /**
     * 解析模型 ID。
     * 优先级：modelOverride > settings.subAgentModelId > settings.chatModelId
     */
    private fun resolveModelId(modelOverride: Uuid?, settings: Settings): Uuid? {
        if (modelOverride != null) return modelOverride
        return settings.subAgentModelId
    }
}
