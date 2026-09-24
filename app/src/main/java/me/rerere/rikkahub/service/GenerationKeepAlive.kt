package me.rerere.rikkahub.service

import android.app.Application
import kotlin.uuid.Uuid

/**
 * 前台服务持有权的薄封装：`acquire`/`release` 配对，token 即持有者 id。
 *
 * `ChatGenerationForegroundService` 本来就是按 id 引用计数的（`activeGenerations` 空了才停服务），
 * 所以聊天生成与后台子代理任务可以共用它，不需要各自的常驻通知。
 */
class GenerationKeepAlive(private val context: Application) {

    /** 返回 null 表示没能进前台（例如系统前台服务配额耗尽）——调用方继续跑但不受保护。 */
    fun hold(conversationId: Uuid, backgroundTask: Boolean = false): Uuid? {
        val token = Uuid.random()
        val started = ChatGenerationForegroundService.acquire(
            context = context,
            generationId = token,
            conversationId = conversationId,
            backgroundTask = backgroundTask,
        )
        return token.takeIf { started }
    }

    fun release(token: Uuid?) {
        if (token == null) return
        ChatGenerationForegroundService.release(context, token)
    }
}
