package me.rerere.rikkahub.data.ai.tools.local

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import me.rerere.ai.core.ReasoningLevel
import me.rerere.ai.core.Tool
import me.rerere.ai.provider.ProviderManager
import me.rerere.ai.provider.TextGenerationParams
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.ai.ui.StreamChunkHandler
import me.rerere.common.android.Logging
import me.rerere.rikkahub.AppScope
import me.rerere.rikkahub.data.ai.tools.clipToolOutput
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.datastore.findModelById
import me.rerere.rikkahub.data.datastore.findProvider
import me.rerere.rikkahub.data.datastore.getCurrentAssistant
import me.rerere.rikkahub.data.event.AppEvent
import me.rerere.rikkahub.data.event.AppEventBus
import me.rerere.rikkahub.utils.ProcessInfo
import me.rerere.rikkahub.service.GenerationKeepAlive
import java.util.concurrent.ConcurrentHashMap
import kotlin.uuid.Uuid

private const val TAG = "SubAgentRuntime"

/**
 * 子代理自身的轮数上限（一轮 = 一次 provider 往返）。
 *
 * 此处原先没有上限：`while (true)` 唯一的出口是「模型这一轮不再调工具」。而子代理看到的工具结果
 * 曾经是空的（见 `withToolOutputs`），模型会以为工具没返回东西、于是反复重调同一个工具——打转。
 * 现场表现是三个后台子代理一起跑时 CPU 打到 97%。给一个有界的轮数，用尽就如实报失败。
 */
private const val MAX_SUB_AGENT_STEPS = 32

/** 同一个 (工具, 参数) 允许重复调用的次数，超过即判「打转」并中止。 */
private const val MAX_IDENTICAL_TOOL_CALLS = 3

/**
 * 同一个工具名的**总**调用预算。
 *
 * 与上面那条互补：模型也可能每次换一个措辞去搜同一个东西（参数不同，重复判据抓不到）。
 * 现场反馈是「后台一直在报子代理调工具，从 step 1 到这会一直」——查一份情报没有道理调十几次搜索，
 * 所以给每个工具一个总预算，超了即中止并如实报失败，别让用户干等十几分钟。
 */
private const val MAX_CALLS_PER_TOOL = 10

/**
 * 子代理的墙钟期限（8 分钟）。
 *
 * 为什么除了轮数上限还需要它：一轮 = 一次 provider 往返，而单次往返的耗时不受我们控制——
 * 网络卡住、上游排队、息屏后被系统限流，都会让「32 轮」这个上限在时间维度上失效。
 * 现场反馈是「发出去十来分钟都没回来」，而用户侧看到的是卡片一直停在「运行中」、
 * 既没有结果也没有失败回执——这违反验收标准里那条「意外中断也要有失败信息正常返回」。
 * 到点即取消并如实报失败，至少让用户立刻拿到回执、知道该重试。
 */
private const val SUB_AGENT_DEADLINE_MS = 8L * 60 * 1000

data class SubAgentResult(
    val success: Boolean,
    val text: String,
    val error: String? = null,
)

data class AsyncSubAgentHandle(
    val taskId: String,
    val job: Job,
)


/** 诊断用：日志里只留开头一小段（工具参数里的查询串、工具输出的开头）。 */
private fun previewForLog(text: String, limit: Int = 120): String {
    val flat = text.replace(Regex("\\s+"), " ").trim()
    return if (flat.length <= limit) flat else flat.take(limit) + "…"
}

/**
 * 把本轮工具结果写回**承载那次调用的 Tool part**。
 *
 * 这是本文件原先最容易看漏的一处错误：工具结果被追加成一条 `UIMessage.user(...)`，而 Tool part 的
 * `output` 始终是空的。provider 正是把 Tool part 的 `output` 序列化成紧跟其 `tool_calls` 的
 * `role: "tool"` 消息（见 `ChatCompletionsAPI` 里的 `PartGroup.Tools`），于是子代理发出的请求长这样：
 *
 *     assistant: text「让我去看看最近的消息」 + tool_calls(search_web)
 *     tool:      name=search_web, tool_call_id=…, content=""   ← 空的
 *     user:      <真正的检索结果>                                ← 被当成「用户又说了句话」
 *
 * ⇒ 模型以为工具什么都没返回，于是把那句开场白当成结论交回来（现场表现：子代理报「已完成」而正文
 * 是一句「让我去看看最近的消息」），或者反复重调同一个工具。就地写回后请求与主循环同形——
 * `GenerationLoop` 也是 `tool.copy(output = clipToolOutput(…))`。
 */
internal fun List<UIMessage>.withToolOutputs(
    toolCallMessageId: Uuid,
    outputs: Map<String, List<UIMessagePart>>,
): List<UIMessage> = map { message ->
    if (message.id != toolCallMessageId) {
        message
    } else {
        message.copy(
            parts = message.parts.map { part ->
                if (part is UIMessagePart.Tool) {
                    part.copy(output = clipToolOutput(outputs[part.toolCallId] ?: emptyList()))
                } else {
                    part
                }
            },
        )
    }
}

/**
 * 子代理的收尾判定：**空回复不算成功**。
 *
 * 此前空回复会被当成 `success = true`、正文为空 ⇒ 投递写出的是一条「已完成」但 `<result>` 为空的
 * 通知（现场表现就是「白卷」），AI 与用户都看不出这其实是一次失败。如实报失败，让两边都能看见。
 */
internal fun subAgentCompletion(text: String): SubAgentResult =
    if (text.isBlank()) {
        SubAgentResult(
            success = false,
            text = "",
            error = "子代理没有产出任何正文（模型返回了空回复）",
        )
    } else {
        SubAgentResult(success = true, text = text)
    }

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
        // 同一 (工具, 参数) 的调用计数：模型「打转」的典型形态是反复发起同一个调用，
        // 而每一轮都在烧一次 provider 往返——现场表现为「后台一直在报子代理调工具」，
        // 从 step 1 一路涨到几十轮、最后由轮数上限或期限兜底（用户白等十几分钟）。
        // 这里直接掐掉：同一个调用重复超过 MAX_IDENTICAL_TOOL_CALLS 次即中止，如实报失败。
        val callCounts = mutableMapOf<String, Int>()
        val toolCounts = mutableMapOf<String, Int>()
        for (step in 1..MAX_SUB_AGENT_STEPS) {
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

            if (toolParts.isEmpty()) return subAgentCompletion(textResponse)

            // 执行本轮的每一个工具调用，结果**写回那次调用所在的 Tool part**（不是追加一条 user
            // 消息）。机制与理由见 `withToolOutputs` 的 KDoc——这一步错了，模型看到的就是
            // 「工具返回空 + 用户又说了一句话」，于是把开场白当结论交回来（白卷），或者反复重调
            // 同一个工具（打转、空烧 token 与 CPU）。
            val outputs = mutableMapOf<String, List<UIMessagePart>>()
            toolParts.forEach { toolPart ->
                val key = "${toolPart.toolName}|${toolPart.input}"
                val callCount = (callCounts[key] ?: 0) + 1
                callCounts[key] = callCount
                if (callCount > MAX_IDENTICAL_TOOL_CALLS) {
                    Logging.log(TAG, "step $step: 同一调用第 $callCount 次，中止")
                    return SubAgentResult(
                        success = false,
                        text = "",
                        error = "子代理反复调用同一个工具（${toolPart.toolName} 同一参数已第 $callCount 次），已中止",
                    )
                }
                val toolCount = (toolCounts[toolPart.toolName] ?: 0) + 1
                toolCounts[toolPart.toolName] = toolCount
                if (toolCount > MAX_CALLS_PER_TOOL) {
                    Logging.log(TAG, "step $step: ${toolPart.toolName} 已调 $toolCount 次，中止")
                    return SubAgentResult(
                        success = false,
                        text = "",
                        error = "子代理反复调用 ${toolPart.toolName}（已 $toolCount 次），已中止",
                    )
                }
                val output = executeSubAgentTool(toolPart, tools)
                outputs[toolPart.toolCallId] = output
                // 参数与输出都留一小段：判断「为什么打转」（同一查询重试？工具在报错？）只看这一行。
                Logging.log(
                    TAG,
                    "step $step: ${toolPart.toolName}(${previewForLog(toolPart.input)}) x$callCount -> " +
                        "${output.size} part(s): ${previewForLog(output.filterIsInstance<UIMessagePart.Text>().joinToString("") { it.text })}",
                )
            }
            currentMessages = currentMessages.withToolOutputs(toolCallMessageId = lastMsg.id, outputs = outputs)
        }

        Logging.log(TAG, "子代理达到 $MAX_SUB_AGENT_STEPS 轮仍未给出结论")
        SubAgentResult(
            success = false,
            text = "",
            error = "子代理 $MAX_SUB_AGENT_STEPS 轮内没有给出结论（工具调用可能打转）",
        )
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        SubAgentResult(success = false, text = "", error = e.message ?: "Unknown error")
    }

    /** 执行一个工具调用并返回它的输出。异常与「工具不存在」都变成**工具结果**（不再是一条 user 消息）。 */
    private suspend fun executeSubAgentTool(
        toolPart: UIMessagePart.Tool,
        tools: List<Tool>,
    ): List<UIMessagePart> {
        val toolDef = tools.find { it.name == toolPart.toolName }
            ?: return listOf(
                UIMessagePart.Text("""{"error":"Tool '${toolPart.toolName}' not found."}"""),
            )
        return runCatching {
            val args = runCatching {
                Json.parseToJsonElement(toolPart.input.ifBlank { "{}" })
            }.getOrDefault(buildJsonObject { })
            toolDef.execute(args)
        }.onFailure { error ->
            if (error is CancellationException) throw error
        }.getOrElse { error ->
            listOf(
                UIMessagePart.Text(
                    """{"error":"[${error.javaClass.name}] ${error.message ?: "Unknown error"}","stack":"${error.stackTraceToString().replace("\"", "\\\"")}"}""",
                ),
            )
        }
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
        // 诊断：登记必须发生在这张卡片被写出来**之前**，所以「卡片是 started 但本进程 registry
        // 里没有它」在同一个进程内**不可能**成立。这行把 registry 的身份与登记时刻钉下来，
        // 供日后与对账那行（`ChatService.reconcile`）逐条比对。
        Logging.log(
            TAG,
            "register $taskId conv=$conversationId registry=@${System.identityHashCode(registry)} " +
                ProcessInfo.describe(),
        )
        val job = appScope.launch {
            // 子代理的网络流必须自己持有前台服务：父生成一结束就会释放它，而任务可能还要跑很久。
            val token = keepAlive.hold(conversationId, backgroundTask = true)
            try {
                // 期限到点即取消（`withTimeoutOrNull` 吞掉自己的 CancellationException 并返回 null），
                // 于是任务照常走到终态、留下失败回执并触发那一轮——而不是永远停在「运行中」。
                val result = withTimeoutOrNull(SUB_AGENT_DEADLINE_MS) {
                    executeSync(
                        prompt = prompt,
                        modelOverride = modelOverride,
                        tools = tools,
                        systemPrompt = systemPrompt,
                    )
                } ?: SubAgentResult(
                    success = false,
                    text = "",
                    error = "子代理超过 ${SUB_AGENT_DEADLINE_MS / 60_000} 分钟仍未完成（已取消）",
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
        // 以 registry 返回的那条为准，而不是本次调用的实参：`SubAgentTaskRegistry.finish` 的契约是
        // 「先到者胜」，写入被拒时 `info` 是已存在的终态条目——若这里仍用实参发事件，事件就会报告
        // 输家想要的 status/reason/result，却带着赢家的 conversationId/description（两者被拆开）。
        val info = registry.finish(taskId, status, reason, result, error)
        Logging.log(
            TAG,
            "finish $taskId attempted=${status}/${reason?.wire} registry=@${System.identityHashCode(registry)} " +
                "stored=${info?.status}/${info?.reason?.wire} " + ProcessInfo.describe(),
        )
        // registry 返回 null = 它压根没有这条登记（正常路径走不到，出现即症状）。
        if (info == null) return
        eventBus.emit(
            AppEvent.SubAgentTaskFinished(
                conversationId = info.conversationId,
                taskId = info.taskId,
                description = info.description,
                status = info.status,
                reason = info.reason,
                result = info.result,
                error = info.error,
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
