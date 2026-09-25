package me.rerere.rikkahub.data.ai.tools.local

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import me.rerere.ai.core.MessageRole
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
 * 工具没有任何输出时写进 Tool part 的占位文本（见 `withToolOutputs`）。
 *
 * **它不是次数/时长限制。** 子代理的轮数、工具调用次数、墙钟时长都**不设上限**（用户要求）：
 * 那些护栏当初是给「打转」装的，而打转的真因是工具结果没送到模型（见 `withToolOutputs`）；
 * 真因修掉之后，上限只会砍掉合法的长任务。
 *
 * 这条占位文本只保证「执行过的工具一定带 output」——否则 `isExecuted` 为假 ⇒ provider 会把这条
 * 工具调用**整条丢掉**（模型看不到自己调过工具），而且下一轮还会被当成「还没执行」重跑。
 */
private const val TOOL_NO_OUTPUT_NOTE = "[tool returned no output]"

data class SubAgentResult(
    val success: Boolean,
    val text: String,
    val error: String? = null,
)

data class AsyncSubAgentHandle(
    val taskId: String,
    val job: Job,
)


/**
 * 这一轮**还没执行过**的工具调用。
 *
 * 判据用 `UIMessagePart.Tool.isExecuted`（即 `output.isNotEmpty()`），与主循环一致
 * （`GenerationLoop` 里是 `messages.last().getTools().filter { !it.isExecuted }`）。
 *
 * 为什么必须筛：模型下一轮的回复会**合并进同一条助手消息**（`StreamChunkHandler` 只在「列表末尾
 * 不是助手消息」时才新建一条），所以本轮已经带上 output 的 Tool part 会一直留在末尾消息里。
 * 不筛的话它每轮都会被重新执行一次——**任何工具调用都会陷入无限循环**（现场：让子代理用
 * eval_javascript 算 1+1 也循环），而且每轮的请求都多一份重复的 tool 消息。
 */
internal fun List<UIMessagePart>.unexecutedToolCalls(): List<UIMessagePart.Tool> =
    filterIsInstance<UIMessagePart.Tool>().filterNot { it.isExecuted }

/** 诊断用：日志里只留开头一小段（工具参数里的查询串、工具输出的开头）。 */
internal fun previewForLog(text: String, limit: Int = 120): String {
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
                    // **绝不能留空 output**：`isExecuted` 就是 `output.isNotEmpty()`，而 provider 的
                    // 分组（`groupPartsByToolBoundary`）只把 `isExecuted` 的 Tool part 当成
                    // 「工具调用 + 结果」发出去。留空的话这条调用会被**整条丢掉**（模型看不到自己
                    // 调过工具 ⇒ 于是把开场白当结论、白卷），而且下一轮还会被当成「还没执行」重跑。
                    val out = outputs[part.toolCallId]
                    val resolved = if (out == null) emptyList() else clipToolOutput(out)
                    part.copy(
                        output = resolved.ifEmpty { listOf(UIMessagePart.Text(TOOL_NO_OUTPUT_NOTE)) },
                    )
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
    ): SubAgentResult {
        try {
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
        // **轮数不设上限**（用户要求）。原先的四道护栏——轮数上限、同一 (工具, 参数) 重复上限、
        // 单工具调用上限、8 分钟墙钟期限——都已移除：它们是给「打转」装的，而打转的真因是工具结果
        // 没送到模型（见 `withToolOutputs`）。真因修掉之后，这些上限只会砍掉合法的长任务。
        // 兜底仍在：进程若被杀，未回复的结果会在打开会话时重新排队（ChatService）。
        var step = 0
        while (true) {
            step++
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
            val textParts = lastMsg.parts.filterIsInstance<UIMessagePart.Text>()

            textResponse = textParts.joinToString("") { it.text }

            // **只执行还没执行过的工具调用。** 这与主循环同形（`GenerationLoop` 也是
            // `messages.last().getTools().filter { !it.isExecuted }`），而且**不加这一条就会死循环**：
            // 下一轮的回复会**合并进同一条助手消息**（`StreamChunkHandler` 只在「末尾不是助手消息」时
            // 才新建一条），于是本轮那个已经带上 output 的 Tool part 会一直留在末尾消息里；若照旧
            // 全部重跑，就会每轮把同一个调用再执行一次——现场就是「任何工具调用都陷入循环」。
            val freshTools = lastMsg.parts.unexecutedToolCalls()
            if (freshTools.isEmpty()) return subAgentCompletion(textResponse)

            // 执行本轮的每一个工具调用，结果**写回那次调用所在的 Tool part**（不是追加一条 user
            // 消息）。机制与理由见 `withToolOutputs` 的 KDoc——这一步错了，模型看到的就是
            // 「工具返回空 + 用户又说了一句话」，于是把开场白当结论交回来（白卷）。
            val outputs = mutableMapOf<String, List<UIMessagePart>>()
            freshTools.forEach { toolPart ->
                val output = executeSubAgentTool(toolPart, tools)
                outputs[toolPart.toolCallId] = output
                // 参数与输出各留一小段：这一行就是「子代理到底在干什么」的全部现场。
                Logging.log(
                    TAG,
                    "step $step: ${toolPart.toolName}(${previewForLog(toolPart.input)}) -> " +
                        "${output.size} part(s): ${previewForLog(output.filterIsInstance<UIMessagePart.Text>().joinToString("") { it.text })}",
                )
            }
            currentMessages = currentMessages
                .withToolOutputs(toolCallMessageId = lastMsg.id, outputs = outputs)
                // 再补一条**空的助手消息作为轮次边界**：下一轮的回复会落进它，而不是与上一轮合并
                // （不补的话 `textResponse` 会把上一轮的开场白一起拼进来，且 `freshTools` 判据会失真）。
                // 这与主循环 `responseBaseMessages` 的做法相同。
                .let { it + UIMessage(role = MessageRole.ASSISTANT, parts = emptyList(), modelId = model.id) }
        }

        // `while (true)` 只有 `break`（消息列表为空）能走到这里。
        Logging.log(TAG, "子代理循环意外退出（消息列表为空）")
        return SubAgentResult(success = false, text = "", error = "子代理循环意外退出")
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return SubAgentResult(success = false, text = "", error = e.message ?: "Unknown error")
        }
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
                // 不设墙钟期限（用户要求）：任务跑多久由它自己决定，不再到点取消。
                // 进程若在这中间被系统杀掉，未回复的结果会在用户打开会话时重新排队
                // （见 ChatService.requeueUnrepliedTaskMarkers），不会永远没有回执。
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
