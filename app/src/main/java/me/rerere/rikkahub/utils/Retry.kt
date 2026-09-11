package me.rerere.rikkahub.utils

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import java.io.InterruptedIOException
import java.net.SocketTimeoutException

/**
 * 失败自动重试：执行 [block]，抛出非 [CancellationException] 异常时延迟 [delayMillis] 后重试，
 * 最多共 [attempts] 次尝试（首次 + [attempts] - 1 次重试）；全部失败把最后一次异常原样抛出。
 * 用于 read_image 工具的 OCR 模型调用 / http 下载等网络波动场景（1 次初始 + 3 次重试）。
 * [onRetry] 每次重试前回调，attempt 从 1 计数（指第几次重试），可用于写请求日志。
 * [retryIf] 判断某次失败是否值得重试（默认都重试）。对**超时**返回 false：连不上的图片
 * 重试也只是再等一轮，不如尽快把错误交回模型。
 */
suspend fun <T> retryOnFailure(
    attempts: Int,
    delayMillis: Long = 1_000L,
    onRetry: (attempt: Int, error: Exception) -> Unit = { _, _ -> },
    retryIf: (Exception) -> Boolean = { true },
    block: suspend () -> T,
): T {
    require(attempts >= 1) { "attempts must be >= 1" }
    repeat(attempts) { attempt ->
        try {
            return block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // 没有剩余次数，或该错误不值得重试（如超时）→ 立刻把错误交出去。
            // 注意必须 throw 而不是只跳过重试：repeat 会继续下一轮，等于换个方式重试。
            if (attempt >= attempts - 1 || !retryIf(e)) {
                throw e
            }
            onRetry(attempt + 1, e)
            delay(delayMillis)
        }
    }
    error("unreachable: retryOnFailure always returns or throws")
}

/**
 * 是否超时类失败（连接/读/整体超时）。
 *
 * OkHttp 的 `callTimeout` 与读超时都抛 [InterruptedIOException]（读超时是它的子类
 * [SocketTimeoutException]），所以判这一个类型即可覆盖。
 */
fun isTimeoutFailure(error: Throwable): Boolean = error is InterruptedIOException
