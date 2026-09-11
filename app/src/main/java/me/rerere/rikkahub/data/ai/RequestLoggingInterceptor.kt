package me.rerere.rikkahub.data.ai

import me.rerere.common.android.LogEntry
import me.rerere.common.android.Logging
import okhttp3.Interceptor
import okhttp3.Response
import okio.Buffer

/** 写入日志页的请求体上限（字符数） */
private const val LOG_REQUEST_BODY_MAX_CHARS = 64 * 1024

/** 写入日志页的响应体上限（字节数，仅失败响应） */
private const val LOG_ERROR_BODY_MAX_BYTES = 8 * 1024

/** 超过这个长度的连续 base64 字符会被折叠成占位符 */
private const val LOG_INLINE_BASE64_MIN_CHARS = 2 * 1024

/**
 * 长 base64 串（data URI 的 payload、Google inlineData 的 data 字段等）。
 * 不折叠的话，带图请求的日志页就是一堵无法阅读、还会撑爆内存的墙。
 *
 * 只要连续 2048+ 个字母数字（base64 字母表）就折叠，不区分它到底是不是 base64——
 * 正常文本有空格与标点，这么长的连续字母数字实际上只可能是载荷。
 */
private val LONG_BASE64 = Regex("[A-Za-z0-9+/]{$LOG_INLINE_BASE64_MIN_CHARS,}={0,2}")

/**
 * 折叠长 base64 并截断超长内容。**只影响日志页展示**，不影响真正发出的请求。
 * 折叠后的占位符不含引号与反斜杠，因此仍是合法 JSON 字符串内容，日志页的 JsonTree 照样能解析。
 */
internal fun compactLoggedBody(body: String, maxChars: Int = LOG_REQUEST_BODY_MAX_CHARS): String {
    val collapsed = LONG_BASE64.replace(body) { match ->
        "<<${match.value.length} chars of base64 omitted>>"
    }
    return if (collapsed.length <= maxChars) {
        collapsed
    } else {
        collapsed.take(maxChars) + "\n<<${collapsed.length - maxChars} chars truncated>>"
    }
}

class RequestLoggingInterceptor : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        if (!Logging.isRequestLoggingEnabled()) {
            return chain.proceed(chain.request())
        }

        val request = chain.request()
        val startTime = System.currentTimeMillis()

        val requestHeaders = request.headers.toMap()
        val requestBody = request.body?.let { body ->
            val buffer = Buffer()
            body.writeTo(buffer)
            compactLoggedBody(buffer.readUtf8())
        }

        val response: Response
        var error: String? = null

        try {
            response = chain.proceed(request)
        } catch (e: Exception) {
            error = e.message
            Logging.logRequest(
                LogEntry.RequestLog(
                    tag = "HTTP",
                    url = request.url.toString(),
                    method = request.method,
                    requestHeaders = requestHeaders,
                    requestBody = requestBody,
                    error = error
                )
            )
            throw e
        }

        val durationMs = System.currentTimeMillis() - startTime
        val responseHeaders = response.headers.toMap()

        // 非 2xx 时把服务端返回的错误体一起记下：provider 报错的原因（哪个字段、哪个参数路径不对）
        // 只在 body 里。之前只记状态码，日志页看不到 "Invalid input"、"Invalid content type" 这类
        // 关键信息，400 只能靠猜。peekBody 只读副本，不消耗真正的响应流。
        val errorBody = if (!response.isSuccessful) {
            runCatching { response.peekBody(LOG_ERROR_BODY_MAX_BYTES.toLong()).string() }
                .getOrNull()
                ?.takeIf { it.isNotBlank() }
                ?.let { compactLoggedBody(it) }
        } else {
            null
        }

        Logging.logRequest(
            LogEntry.RequestLog(
                tag = "HTTP",
                url = request.url.toString(),
                method = request.method,
                requestHeaders = requestHeaders,
                requestBody = requestBody,
                responseCode = response.code,
                responseHeaders = responseHeaders,
                durationMs = durationMs,
                error = error ?: errorBody
            )
        )

        return response
    }

    private fun okhttp3.Headers.toMap(): Map<String, String> {
        return names().associateWith { name ->
            if (name.equals("Proxy-Authorization", ignoreCase = true)) {
                "██"
            } else {
                get(name) ?: ""
            }
        }
    }
}
