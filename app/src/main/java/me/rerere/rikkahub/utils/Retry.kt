package me.rerere.rikkahub.utils

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay

/**
 * 失败自动重试：执行 [block]，抛出非 [CancellationException] 异常时延迟 [delayMillis] 后重试，
 * 最多共 [attempts] 次尝试（首次 + [attempts] - 1 次重试）；全部失败把最后一次异常原样抛出。
 * 用于 read_image 工具的 OCR 模型调用 / http 下载等网络波动场景（1 次初始 + 3 次重试）。
 * [onRetry] 每次重试前回调，attempt 从 1 计数（指第几次重试），可用于写请求日志。
 */
suspend fun <T> retryOnFailure(
    attempts: Int,
    delayMillis: Long = 1_000L,
    onRetry: (attempt: Int, error: Exception) -> Unit = { _, _ -> },
    block: suspend () -> T,
): T {
    require(attempts >= 1) { "attempts must be >= 1" }
    var lastError: Exception? = null
    repeat(attempts) { attempt ->
        try {
            return block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            lastError = e
            if (attempt < attempts - 1) {
                onRetry(attempt + 1, e)
                delay(delayMillis)
            }
        }
    }
    throw (lastError ?: IllegalStateException("retry attempts exhausted"))
}
