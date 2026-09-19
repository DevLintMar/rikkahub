package me.rerere.rikkahub.ui.components.webview

import android.util.Log
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import okhttp3.OkHttpClient
import okhttp3.Request
import org.koin.core.component.KoinComponent
import org.koin.core.component.getKoin
import java.io.ByteArrayInputStream
import java.util.concurrent.TimeUnit

private const val TAG = "WebViewRemoteImg"
private const val MAX_BYTES = 12L * 1024 * 1024
private const val CALL_TIMEOUT_SECONDS = 20L

/**
 * 预览页里的**外网图片**改由 app 自己的 HTTP 客户端来取。
 *
 * **为什么**：`WebView` 走系统网络栈，**不用 app 里配的代理**（`SettingsProxySelector` 只挂在
 * Koin 那个 `OkHttpClient` 上），User-Agent 也是 WebView 自己的。于是同一张图会分裂成
 * 「原生能渲染（Coil → OkHttp，带代理与自定义 UA）／网页视图里是裂图（WebView 直连）」。
 * 2026-09-19 用户报的就是这个：搜索结果里的**部分**外网图在原生页正常、网页视图里损坏。
 *
 * **做法**：`shouldInterceptRequest` 里凡是「非主文档的 http(s) 图片」都先用 app 的客户端取一次，
 * 取到就返回；**取不到一律返回 null**，交回 WebView 自己再试 —— 所以这是纯增量，
 * 不会把原本能显示的图弄坏。
 */
internal object WebViewRemoteImages : KoinComponent {

    private val client: OkHttpClient by lazy {
        getKoin().get<OkHttpClient>()
            .newBuilder()
            // 复用同一个连接池与代理设置，只把「10 分钟 readTimeout」压到 20 秒 ——
            // 否则一张取不到的图会把 WebView 这个请求线程挂十分钟
            .callTimeout(CALL_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .build()
    }

    fun intercept(request: WebResourceRequest): WebResourceResponse? {
        if (request.isForMainFrame) return null
        val url = request.url
        val scheme = url.scheme?.lowercase() ?: return null
        if (scheme != "http" && scheme != "https") return null
        val href = url.toString()
        if (!looksLikeRemoteImage(href, request.requestHeaders["Accept"])) return null

        return runCatching { fetch(href) }
            .onFailure { Log.d(TAG, "交给 WebView 自己取：$href（${it.message}）") }
            .getOrNull()
    }

    /** 取不到（非 2xx / 不是图片 / 超限 / 超时 / 异常）一律返回 null。 */
    private fun fetch(url: String): WebResourceResponse? {
        val call = client.newCall(Request.Builder().url(url).build())
        call.execute().use { response ->
            if (!response.isSuccessful) {
                Log.d(TAG, "HTTP ${response.code}：$url")
                return null
            }
            val body = response.body ?: return null
            val contentType = body.contentType()?.toString()?.substringBefore(';')
                ?: guessMimeType(url)
                ?: return null
            if (!contentType.startsWith("image/")) return null

            // 先探一格再全读：避免把超大文件整个读进内存（沿用 ReadImageTools 的做法）
            val source = body.source()
            source.request(MAX_BYTES + 1)
            if (source.buffer.size > MAX_BYTES) {
                Log.d(TAG, "图片超过 ${MAX_BYTES / 1024 / 1024}MB，跳过：$url")
                return null
            }
            val bytes = source.readByteArray()
            if (bytes.isEmpty()) return null

            val headers = mapOf(
                "Content-Type" to contentType,
                // 让 WebView 缓一会儿，少走几次代理；上游给了 Cache-Control 就用它的
                "Cache-Control" to (response.header("Cache-Control") ?: "max-age=600"),
            )
            return WebResourceResponse(contentType, null, 200, "OK", headers, ByteArrayInputStream(bytes))
        }
    }
}

private val IMAGE_EXTENSIONS = setOf("jpg", "jpeg", "png", "webp", "gif", "bmp", "avif", "ico", "svg")

/**
 * 这个子资源是不是一张图片。
 *
 * WebView 给 `<img>` 发的 Accept 是 `image/avif,image/webp,image/apng,image/svg+xml,image/*,*/*;q=0.8`，
 * 认它最准；某些请求（如 CSS 里的 url()）不带 Accept，就退回看扩展名。
 */
internal fun looksLikeRemoteImage(url: String, acceptHeader: String?): Boolean {
    if (acceptHeader?.contains("image/", ignoreCase = true) == true) return true
    val extension = url.substringBefore('?').substringBefore('#').substringAfterLast('.', "")
    return extension.lowercase() in IMAGE_EXTENSIONS
}

/** 上游没给 Content-Type 时的兜底；认不出来返回 null（宁可交给 WebView 自己）。 */
private fun guessMimeType(url: String): String? {
    return when (url.substringBefore('?').substringAfterLast('.', "").lowercase()) {
        "jpg", "jpeg" -> "image/jpeg"
        "png" -> "image/png"
        "webp" -> "image/webp"
        "gif" -> "image/gif"
        "bmp" -> "image/bmp"
        "avif" -> "image/avif"
        "ico" -> "image/x-icon"
        "svg" -> "image/svg+xml"
        else -> null
    }
}
