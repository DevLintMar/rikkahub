package me.rerere.rikkahub.data.ai.tools.local

import java.util.concurrent.ConcurrentHashMap
import kotlin.uuid.Uuid

enum class TaskStatus { IN_PROGRESS, COMPLETED, FAILED }

/**
 * 子代理失败的性质。`null`（不在枚举里）表示模型或网络错误。
 *
 * 写进会话的 `reason` 用它，UI 据此显示分类短文案，原始 error 正文只进标记 metadata 给 AI。
 */
enum class SubAgentFailReason(val wire: String) {
    USER_CANCELLED("user_cancelled"),
    APP_EXIT("app_exit"),
}

data class SubAgentTaskInfo(
    val taskId: String,
    val conversationId: Uuid,
    val description: String,
    val prompt: String,
    val startedAt: Long,
    val status: TaskStatus,
    val reason: SubAgentFailReason? = null,
    val result: String? = null,
    val error: String? = null,
)

/**
 * 异步子代理任务的进程内登记表：只回答「这一趟进程里还有什么活着」。
 *
 * 终态一律由 ChatService 写进会话（工具结果 + 标记），所以进程被杀之后这里为空也不需要恢复——
 * 中断对账靠会话里工具结果的 `status: started` 判据，不靠这张表的历史。
 */
class SubAgentTaskRegistry {
    private val tasks = ConcurrentHashMap<String, SubAgentTaskInfo>()

    fun register(
        taskId: String,
        conversationId: Uuid,
        description: String,
        prompt: String,
    ): SubAgentTaskInfo = SubAgentTaskInfo(
        taskId = taskId,
        conversationId = conversationId,
        description = description,
        prompt = prompt,
        startedAt = System.currentTimeMillis(),
        status = TaskStatus.IN_PROGRESS,
    ).also { tasks[taskId] = it }

    /**
     * 写终态。**已终态的任务不再被覆盖**：用户取消与正常完成可能同时到达，
     * 先到者胜，避免把「已完成」改写成「已取消」。
     *
     * 用 `ConcurrentHashMap.compute` 而不是「读 → 判断 → 写」：后者两次调用都读到
     * `IN_PROGRESS` 时会双双写入、最后写入者胜，正好是这个守卫要挡的情形。`compute`
     * 把整段判断与写入放进同一个原子操作里。
     *
     * 返回值语义：任务不存在 → `null`；已被别人写成终态 → 返回**已有的**那条（写入被拒绝）；
     * 本次写入成功 → 返回新终态。
     */
    fun finish(
        taskId: String,
        status: TaskStatus,
        reason: SubAgentFailReason? = null,
        result: String? = null,
        error: String? = null,
    ): SubAgentTaskInfo? = tasks.compute(taskId) { _, previous ->
        when {
            previous == null -> null
            previous.status != TaskStatus.IN_PROGRESS -> previous
            else -> previous.copy(status = status, reason = reason, result = result, error = error)
        }
    }

    fun isLive(taskId: String): Boolean = tasks[taskId]?.status == TaskStatus.IN_PROGRESS

    fun get(taskId: String): SubAgentTaskInfo? = tasks[taskId]

    fun liveCount(): Int = tasks.values.count { it.status == TaskStatus.IN_PROGRESS }

    fun all(): List<SubAgentTaskInfo> = tasks.values.sortedBy { it.startedAt }
}
