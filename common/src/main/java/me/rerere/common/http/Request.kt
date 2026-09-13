package me.rerere.common.http

import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Response
import okhttp3.internal.closeQuietly
import okio.IOException
import kotlin.coroutines.resumeWithException

/**
 * 等待一个 OkHttp 调用返回，可被协程取消。
 *
 * [invokeOnCancellation] 里 cancel 掉底层调用：**必须注册在 `enqueue` 之前**，否则
 * 「注册前就被取消」的竞态会漏掉。少了这一句，协程虽然会立刻带着 CancellationException
 * 结束，但 HTTP 请求仍在后台跑到自己超时（全局客户端 readTimeout = 10 分钟），
 * 调用方以为已经放弃、连接却一直被占着。调用方典型的例子是 OCR 的单次调用超时
 * （`OcrTransformer`）——它要保证「超时就真的不再占用连接」。
 */
suspend fun Call.await(): Response {
    return suspendCancellableCoroutine { continuation ->
        // 显式取别名，避免 lambda 里的 cancel() 解析到别的东西上
        val call = this
        continuation.invokeOnCancellation { call.cancel() }
        enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (continuation.isActive) {
                    continuation.resumeWithException(e)
                }
            }

            override fun onResponse(call: Call, response: Response) {
                continuation.resume(response) { cause, _, _ ->
                    response.closeQuietly()
                }
            }
        })
    }
}
