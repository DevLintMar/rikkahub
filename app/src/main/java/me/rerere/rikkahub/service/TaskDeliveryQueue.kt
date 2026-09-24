package me.rerere.rikkahub.service

/**
 * 每会话的「待触发投递」队列：哪个 taskId 该在会话空闲时开一轮生成。
 *
 * 只负责顺序与去重。真正的终态入库由 `ChatService.deliverTaskResult` 做，且是幂等的
 * （判据是会话里标记的位置），所以这个队列在进程退出后为空是安全的——对账会重新判定。
 */
class TaskDeliveryQueue {
    private val pending = ArrayDeque<String>()

    @Synchronized
    fun enqueue(taskId: String): Boolean {
        // kotlin.collections.ArrayDeque.addLast 返回 Unit，不能直接当表达式返回值用
        if (pending.contains(taskId)) return false
        pending.addLast(taskId)
        return true
    }

    @Synchronized
    fun peek(): String? = pending.firstOrNull()

    @Synchronized
    fun takeNext(): String? = if (pending.isEmpty()) null else pending.removeFirst()

    @Synchronized
    fun remove(taskId: String): Boolean = pending.remove(taskId)

    @Synchronized
    fun contains(taskId: String): Boolean = pending.contains(taskId)

    @Synchronized
    fun size(): Int = pending.size

    @Synchronized
    fun clear() = pending.clear()
}
