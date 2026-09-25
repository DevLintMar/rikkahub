package me.rerere.rikkahub.service

import kotlinx.datetime.LocalDateTime
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.data.model.toMessageNode
import me.rerere.rikkahub.utils.JsonInstant

internal const val SUB_AGENT_TOOL_NAME = "sub_agent"
internal const val SUB_AGENT_TASK_METADATA_KEY = "subAgentTask"

/** 子代理任务通知的辨识标签。**只能按内容判定**：`UIMessage.isSynthetic` 是 @Transient，不持久化。 */
internal const val TASK_NOTIFICATION_TAG = "<task-notification>"

/**
 * 是否是「派生注入的子代理通知」——写回会话时必须剔除它。
 *
 * 判据是**三者同时满足**：USER 角色 + `isSynthetic` + 文本含标签。**不能只看标签**：真实消息
 * （用户写的、或模型复述的）里若出现字面量 `<task-notification>`，只看标签会把它们一起剔掉——
 * 若命中的是最后一条 assistant，这轮回复会**静默不写回**（UI 读的是会话状态）且标记仍是 pending
 * ⇒ 下一轮再注入 ⇒ 反复；若命中的在中间，`updateCurrentMessages` 的下标合并会把其后每条消息
 * 都塞进前一个节点。取舍：漏过滤（注入块被写回）可恢复，误过滤（回复静默丢失 + 下标错位）不可。
 */
internal fun UIMessage.isInjectedTaskNotification(): Boolean =
    role == MessageRole.USER &&
        isSynthetic &&
        parts.any { it is UIMessagePart.Text && it.text.contains(TASK_NOTIFICATION_TAG) }

/**
 * 投递期写在**可见标记**上的机器可读部分。
 *
 * 载体选 `UIMessagePart.Text.metadata` 而不是「隐藏节点」：metadata 随 `nodes` blob 一起持久化、
 * 从不发给 provider、也不被任何渲染器读取（渲染器只读 `text`）。于是结果正文既能持久化、
 * 又只给 AI 看，且不需要任何展示层过滤。
 */
internal data class SubAgentTaskMarker(
    val taskId: String,
    val status: String,        // "completed" | "failed"
    val reason: String?,       // null | "user_cancelled" | "app_exit"
    val description: String,
    val result: String?,       // 结果正文；失败时为 null
    val error: String?,        // 原始错误正文；成功时为 null
)

internal fun UIMessage.subAgentTaskMarkerOrNull(): SubAgentTaskMarker? {
    if (role != MessageRole.SYSTEM) return null
    val part = parts.filterIsInstance<UIMessagePart.Text>().firstOrNull() ?: return null
    val meta = part.metadata?.get(SUB_AGENT_TASK_METADATA_KEY) as? JsonObject ?: return null
    val taskId = meta["taskId"]?.jsonPrimitive?.contentOrNull ?: return null
    val status = meta["status"]?.jsonPrimitive?.contentOrNull ?: return null
    return SubAgentTaskMarker(
        taskId = taskId,
        status = status,
        reason = meta["reason"]?.jsonPrimitive?.contentOrNull,
        description = meta["description"]?.jsonPrimitive?.contentOrNull ?: taskId,
        result = meta["result"]?.jsonPrimitive?.contentOrNull,
        error = meta["error"]?.jsonPrimitive?.contentOrNull,
    )
}

/**
 * 还没汇报给 AI 的标记，按出现顺序返回。
 *
 * 判据：标记**之后是否已经存在一条带非空文本的 assistant 消息**。标记总在投递时追加到末尾，
 * 所以任何排在它之后的 assistant 回复，其请求必然在标记已存在之后构建——也就必然已经派生过通知。
 * 于是这条规则不会漏发，同时天然做到「只通知一次」（缺陷④的结构性根因消失）。
 */
internal fun List<UIMessage>.pendingTaskMarkers(): List<SubAgentTaskMarker> {
    val found = mutableListOf<SubAgentTaskMarker>()
    var hasAssistantTextAfter = false
    for (index in indices.reversed()) {
        val message = this[index]
        val marker = message.subAgentTaskMarkerOrNull()
        if (marker != null) {
            if (!hasAssistantTextAfter) found += marker
        } else if (message.role == MessageRole.ASSISTANT &&
            message.parts.any { it is UIMessagePart.Text && it.text.isNotBlank() }
        ) {
            hasAssistantTextAfter = true
        }
    }
    return found.reversed()
}

internal fun xmlEscape(text: String): String = text
    .replace("&", "&amp;")
    .replace("<", "&lt;")
    .replace(">", "&gt;")

internal fun taskNotificationXml(marker: SubAgentTaskMarker, pendingTaskCount: Int): String = buildString {
    appendLine("<task-notification>")
    appendLine("  <task-id>${xmlEscape(marker.taskId)}</task-id>")
    appendLine("  <status>${xmlEscape(marker.status)}</status>")
    marker.reason?.let { appendLine("  <reason>${xmlEscape(it)}</reason>") }
    appendLine("  <pending-tasks>$pendingTaskCount</pending-tasks>")
    appendLine("  <summary>Agent \"${xmlEscape(marker.description)}\" ${xmlEscape(marker.status)}</summary>")
    val body = marker.result ?: marker.error
    if (!body.isNullOrBlank()) {
        appendLine("  <result>${xmlEscape(body)}</result>")
    }
    append("</task-notification>")
}

/**
 * 派生待汇报的任务通知，追加在请求消息列表末尾。
 *
 * 这些消息**只存在于这一次请求**：`ChatService.handleMessageComplete` 的 `.collect` 会按
 * [TASK_NOTIFICATION_TAG] 把它们剔除，不写回会话状态。
 */
internal fun injectTaskNotifications(
    messages: List<UIMessage>,
    pendingTaskCount: Int,
): List<UIMessage> {
    val markers = messages.pendingTaskMarkers()
    if (markers.isEmpty()) return messages
    val injected = markers.map { marker ->
        // **必须是 USER，不能是 SYSTEM**：本仓 `ClaudeProvider.buildMessages` 会滤掉**全部** SYSTEM
        // 消息（`ClaudeProvider.kt:558` 的 `it.role != MessageRole.SYSTEM`），Responses API 同样丢弃
        // 除首条外的 SYSTEM。用 SYSTEM 会让这条通知在那些 provider 上**根本送不到模型**——而通知正是
        // 结果正文唯一的去向（工具结果里已不正文），于是「AI 拿到子代理结果」这个核心承诺在 Claude 上
        // 直接不成立，症状恰好是「切屏之后回复里没有子代理的结果」。
        // `.copy(isSynthetic = true)` 让合成消息不过 messageTemplate（与 `TimeReminderTransformer.kt:77`
        // 的既有做法一致：那处也是「机器注入、只给模型看」的内容）。
        UIMessage.user(taskNotificationXml(marker, pendingTaskCount)).copy(isSynthetic = true)
    }
    return messages + injected
}

/** 结果正文的硬上限，与 `clipToolOutput` 的 100KB 惯例一致。 */
internal const val SUB_AGENT_RESULT_MAX_CHARS = 100_000

/** 一次投递的输入。`description` 为 null 时从工具结果里取。 */
internal data class TaskDelivery(
    val taskId: String,
    val status: String,        // "completed" | "failed"
    val reason: String?,       // null | "user_cancelled" | "app_exit"
    val description: String?,
    val result: String?,
    val error: String?,
)

private fun clipTaskResult(text: String?): String? = text?.let {
    if (it.length <= SUB_AGENT_RESULT_MAX_CHARS) it else it.take(SUB_AGENT_RESULT_MAX_CHARS) + "[truncated]"
}

private fun UIMessagePart.Tool.outputText(): String =
    output.filterIsInstance<UIMessagePart.Text>().joinToString("\n") { it.text }

/**
 * 本次投递要改写的工具结果：`sub_agent` 且输出 JSON 里 `task_id` 命中。
 *
 * 用内嵌 `task_id` 作锚点是因为 `Tool.execute` 拿不到自己的 `toolCallId`
 * （签名是 `suspend (JsonElement) -> List<UIMessagePart>`）。
 */
private fun UIMessagePart.Tool.matchesTask(taskId: String): Boolean =
    toolName == SUB_AGENT_TOOL_NAME && outputText().contains("\"task_id\":\"$taskId\"")

/**
 * 把一次终态投递写进会话：改写工具结果（只留状态）+ 追加一条可见标记。
 *
 * 返回 `null` 表示无需变更——唯一的判据是「该任务的标记已经在会话里」（幂等）。
 * **找不到对应工具结果不返回 null**（理由见下方注释）。
 * 结果正文只进标记 metadata，**绝不进工具结果**（决策 5）。
 *
 * `markerText` 是**函数**而不是成品字符串：可见文案要过 `stringResource`（Android 资源在本文件里用不了），
 * 而 description 可能来自工具结果（`delivery.description` 为 null 时）——所以由调用方拿着解析后的
 * description 去拼文案。中断对账那条路径就是靠它才能显示出子代理的名字而不是 taskId。
 */
internal fun Conversation.applyTaskDelivery(
    delivery: TaskDelivery,
    markerText: (description: String) -> String,
): Conversation? {
    if (messageNodes.any { node -> node.messages.any { it.subAgentTaskMarkerOrNull()?.taskId == delivery.taskId } }) {
        return null
    }

    val clipped = clipTaskResult(delivery.result)
    val clippedError = clipTaskResult(delivery.error)
    var description = delivery.description

    val newNodes = messageNodes.map { node ->
        val newMessages = node.messages.map { message ->
            val newParts = message.parts.map { part ->
                if (part !is UIMessagePart.Tool || !part.matchesTask(delivery.taskId)) return@map part
                val original = runCatching {
                    JsonInstant.parseToJsonElement(part.outputText()).jsonObject.toMutableMap()
                }.getOrNull()
                if (description == null) {
                    description = original?.get("description")?.jsonPrimitive?.contentOrNull
                }
                val rewritten = buildJsonObject {
                    // status/reason/task_id 由本次投递决定；result/error 一律不留在工具结果里（决策 5）
                    original?.forEach { (key, value) ->
                        if (key !in setOf("status", "reason", "task_id", "result", "error")) {
                            put(key, value)
                        }
                    }
                    put("type", JsonPrimitive(SUB_AGENT_TOOL_NAME))
                    put("status", JsonPrimitive(delivery.status))
                    if (delivery.reason != null) put("reason", JsonPrimitive(delivery.reason))
                    put("task_id", JsonPrimitive(delivery.taskId))
                    put("description", JsonPrimitive(description ?: delivery.taskId))
                }
                // 保留可能存在的非文本部件（子代理目前只产出文本，但别在这里埋雷）
                part.copy(
                    output = listOf(UIMessagePart.Text(rewritten.toString())) +
                        part.output.filter { it !is UIMessagePart.Text },
                )
            }
            if (newParts == message.parts) message else message.copy(parts = newParts)
        }
        if (newMessages == node.messages) node else node.copy(messages = newMessages)
    }

    // **找不到锚点不再返回 null**：`run_workflow` 的后台步骤走同一个 `runtime.executeAsync`
    // （`WorkflowEngine.kt:108`），但它的工具结果是 `run_workflow` 的文本、不含 `sub_agent` 的任务锚点。
    // 改造前的旧路径（`handleSubAgentRecall`）**不看工具结果**，直接从事件建通知并触发一轮，所以这类
    // 任务原本**有**回执与回复——按「找不到就不投递」处理是回归（症状：后台工作流步骤的结果正文静默消失、
    // 也不触发回复）。改写不到工具结果只是少了卡片上的状态 pill，而回执（标记）与触发必须照做。
    // 顺带覆盖另一种情形：历史编辑（重新生成越过该点、切换分支、压缩、删消息）移除了锚点。
    // 本函数剩余的唯一 null 返回是上面那条幂等守卫（该 taskId 的标记已存在）。

    val effectiveDescription = description ?: delivery.taskId
    val marker = UIMessage(
        role = MessageRole.SYSTEM,
        parts = listOf(
            UIMessagePart.Text(
                text = markerText(effectiveDescription),
                metadata = buildJsonObject {
                    put(SUB_AGENT_TASK_METADATA_KEY, buildJsonObject {
                        put("taskId", JsonPrimitive(delivery.taskId))
                        put("status", JsonPrimitive(delivery.status))
                        put("reason", delivery.reason?.let { JsonPrimitive(it) } ?: JsonNull)
                        put("description", JsonPrimitive(effectiveDescription))
                        put("result", clipped?.let { JsonPrimitive(it) } ?: JsonNull)
                        put("error", clippedError?.let { JsonPrimitive(it) } ?: JsonNull)
                    })
                },
            ),
        ),
    )

    return copy(messageNodes = newNodes + marker.toMessageNode())
}

/**
 * 一条「看起来像中断」的候选，连同它自带的证据。
 *
 * 证据为什么必须带出来：这条判据的产出是「应用退出（进程被回收）」回执，而**判据本身无法自证**——
 * 一张本进程刚写下的卡片，和一张上一个进程留下的卡片，在库里长得一模一样。所以把两侧可核的东西
 * 一起交出去：卡片自身的创建时刻，以及卡片 JSON 里记的「写它的那个进程的启动时刻」
 * （`SubAgentTool` 在发起时写入；比对表里没有这个键的老卡片为 null）。
 */
internal data class InterruptedTaskCandidate(
    val taskId: String,
    /** **承载这张卡片的 assistant 消息**的创建时刻（不是卡片被写入的时刻）。 */
    val cardCreatedAt: LocalDateTime,
    /** 卡片 JSON 里由 `SubAgentTool` 写下的 `launched_at`；老卡片（或同步路径）为 null。 */
    val cardLaunchedAt: Long?,
    /** 卡片 JSON 里的 `process_started_at`；老卡片（或同步路径）为 null。 */
    val cardProcessStartedAt: Long?,
)

/**
 * 只读一次、**不抛**的取值。
 *
 * `jsonPrimitive` 遇到对象/数组会抛 `IllegalArgumentException`，而这些 JSON 来自**持久化会话**
 * （备份导入、历史编辑都可能把字段弄成别的类型）。放任它抛出的后果很具体：对账是从
 * `ensureLoaded` 内部调的，异常会被 `handleTaskFinished` 的 `catch` 吞掉
 * ⇒ **整条投递被静默丢弃**（终态不入库、也不触发回复）。畸形卡片跳过即可，不值得炸掉投递。
 */
private fun JsonObject.readString(key: String): String? =
    runCatching { this[key]?.jsonPrimitive?.contentOrNull }.getOrNull()

private fun JsonObject.readLong(key: String): Long? =
    runCatching { this[key]?.jsonPrimitive?.longOrNull }.getOrNull()

/**
 * 中断对账的判据：工具结果仍是 `started` **且 registry 里没有这个 taskId**（`get(taskId) == null`），
 * 即「本进程完全不认识它」——只有这一种情形才等于「它随上一个进程一起死了」。
 *
 * 参数名是 `isTracked` 而不是 `isLive`：问法必须是「本进程是否登记过它」。若问「它是否还在跑」，
 * 本进程里刚完成、投递尚未落地的任务那一刻既不是 live、工具结果又还是 `started`，会被误判成中断，
 * 先写出一条假的「应用退出」回执，随后真正的投递因标记已存在而按幂等契约返回 null 被**静默丢弃**。
 *
 * 改写后 `status` 变终态，所以这条判据天然幂等。
 */
internal fun List<UIMessage>.interruptedSubAgentTaskCandidates(
    isTracked: (String) -> Boolean,
): List<InterruptedTaskCandidate> {
    val found = mutableListOf<InterruptedTaskCandidate>()
    forEach { message ->
        message.parts.filterIsInstance<UIMessagePart.Tool>().forEach { part ->
            if (part.toolName != SUB_AGENT_TOOL_NAME) return@forEach
            val json = runCatching { JsonInstant.parseToJsonElement(part.outputText()).jsonObject }.getOrNull() ?: return@forEach
            if (json.readString("status") != "started") return@forEach
            val taskId = json.readString("task_id") ?: return@forEach
            if (isTracked(taskId)) return@forEach
            found += InterruptedTaskCandidate(
                taskId = taskId,
                cardCreatedAt = message.createdAt,
                cardLaunchedAt = json.readLong("launched_at"),
                cardProcessStartedAt = json.readLong("process_started_at"),
            )
        }
    }
    return found
}

/** 只要 taskId 的简版（[interruptedSubAgentTaskCandidates] 的投影）。 */
internal fun List<UIMessage>.interruptedSubAgentTaskIds(isTracked: (String) -> Boolean): List<String> =
    interruptedSubAgentTaskCandidates(isTracked).map { it.taskId }
