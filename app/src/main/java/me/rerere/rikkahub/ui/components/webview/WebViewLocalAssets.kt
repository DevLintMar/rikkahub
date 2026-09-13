package me.rerere.rikkahub.ui.components.webview

import android.content.Context
import android.net.Uri
import android.webkit.WebResourceResponse
import me.rerere.rikkahub.data.files.WorkspaceFileUrlResolver
import java.io.ByteArrayInputStream

/**
 * 应用内 WebView 使用的虚拟域名，用于给 loadDataWithBaseURL 提供一个稳定的 origin。
 */
const val WEB_VIEW_BASE_URL = "https://rikkahub.local"

/**
 * 虚拟域名下的静态资源前缀，会被映射到 app 的 assets 目录。
 *
 * 例如 `https://rikkahub.local/assets/html/mermaid.min.js` 对应 `assets/html/mermaid.min.js`，
 * 这样 mermaid/katex 之类的库可以本地加载，而不用从 CDN 下载（首次加载可能需要几十秒）。
 */
const val WEB_VIEW_ASSET_URL = "$WEB_VIEW_BASE_URL/assets"

/**
 * 虚拟域名下引用**沙箱本地文件**的前缀。
 *
 * 完整形式：`https://rikkahub.local/local/<workspaceId|->/<base64url 沙箱逻辑路径)>`
 *
 * 为什么要绕这一层：预览页的 origin 是 `https://rikkahub.local`，从这个 origin 引用
 * `file://` 子资源会被 WebView 直接拦掉（`allowFileAccessFromFileURLs` 对非 file origin
 * 不生效），所以本地图片必须换成本域名下的 URL，再由 [WebViewLocalAssets] 在
 * `shouldInterceptRequest` 里读文件返回。
 *
 * 逻辑路径整体做 base64url（无 padding）编码，避开 `/`、`%`、空格、非 ASCII 在 URL 里的
 * 转义问题；没有工作区时 workspaceId 段写 [NO_WORKSPACE_ID]。
 */
const val WEB_VIEW_LOCAL_URL = "$WEB_VIEW_BASE_URL/local"

internal const val NO_WORKSPACE_ID = "-"

/**
 * 用沙箱逻辑路径拼出预览页可用的 URL。
 *
 * 必须与 [WebViewLocalAssets] 的解析保持一致 —— 两边的格式定义都在本文件里。
 *
 * @param sandboxPath Rootfs 逻辑路径，如 `/upload/a.jpg`、`/workspace/notes/x.png`
 */
fun buildLocalFileUrl(workspaceId: String?, sandboxPath: String): String {
    val id = workspaceId?.takeIf { it.isNotBlank() } ?: NO_WORKSPACE_ID
    return "$WEB_VIEW_LOCAL_URL/$id/${encodeSandboxPath(sandboxPath)}"
}

private val HEX_DIGITS = "0123456789abcdef".toCharArray()

/**
 * 沙箱路径 → hex。
 *
 * 用 hex 而不是 base64：路径里有 `/`、空格、非 ASCII，直接塞进 URL 会踩转义；hex 是纯
 * JVM 实现，不依赖 `android.util.Base64`，单测能直接跑编码/解码这一对。
 */
internal fun encodeSandboxPath(path: String): String {
    val bytes = path.toByteArray(Charsets.UTF_8)
    val out = StringBuilder(bytes.size * 2)
    bytes.forEach { byte ->
        val value = byte.toInt() and 0xFF
        out.append(HEX_DIGITS[value ushr 4])
        out.append(HEX_DIGITS[value and 0x0F])
    }
    return out.toString()
}

/** [encodeSandboxPath] 的逆运算；长度非法或含非 hex 字符时返回 null。 */
internal fun decodeSandboxPath(encoded: String): String? {
    if (encoded.isEmpty() || encoded.length % 2 != 0) return null
    val bytes = ByteArray(encoded.length / 2)
    for (index in bytes.indices) {
        val high = Character.digit(encoded[index * 2], 16)
        val low = Character.digit(encoded[index * 2 + 1], 16)
        if (high < 0 || low < 0) return null
        bytes[index] = ((high shl 4) or low).toByte()
    }
    return String(bytes, Charsets.UTF_8)
}

private const val ASSET_HOST = "rikkahub.local"
private const val ASSET_PATH_PREFIX = "/assets/"
private const val LOCAL_PATH_PREFIX = "/local/"

internal object WebViewLocalAssets {
    fun intercept(context: Context, uri: Uri): WebResourceResponse? {
        if (!uri.host.equals(ASSET_HOST, ignoreCase = true)) return null
        val path = uri.path ?: return null
        return when {
            path.startsWith(ASSET_PATH_PREFIX) ->
                interceptAsset(context, path.removePrefix(ASSET_PATH_PREFIX))

            path.startsWith(LOCAL_PATH_PREFIX) ->
                interceptLocalFile(context, path.removePrefix(LOCAL_PATH_PREFIX))

            else -> null
        }
    }

    private fun interceptAsset(context: Context, assetPath: String): WebResourceResponse? {
        if (assetPath.isEmpty() || assetPath.contains("..")) return null

        val mimeType = mimeTypeOf(assetPath)
        return runCatching {
            WebResourceResponse(
                mimeType,
                encodingFor(mimeType),
                context.assets.open(assetPath)
            )
        }.getOrNull()
    }

    /**
     * `local/<workspaceId|->/<base64url 沙箱路径>`。
     *
     * 路径解析交给 [WorkspaceFileUrlResolver]：路径穿越防护、拒绝内核伪文件系统、拒绝真机
     * 绝对路径都在那里 —— 所以即使 markdown 是被模型输出牵着走的，也拿不到沙箱外的文件。
     */
    private fun interceptLocalFile(context: Context, rest: String): WebResourceResponse {
        val separator = rest.indexOf('/')
        if (separator <= 0) return notFound()

        val workspaceId = rest.substring(0, separator).takeIf { it != NO_WORKSPACE_ID }
        val sandboxPath = decodePath(rest.substring(separator + 1)) ?: return notFound()

        val file = WorkspaceFileUrlResolver.resolveFile(context.filesDir, workspaceId, sandboxPath)
            ?: return notFound()
        if (!file.isFile) return notFound()

        val mimeType = mimeTypeOf(file.name)
        return runCatching {
            WebResourceResponse(
                mimeType,
                encodingFor(mimeType),
                200,
                "OK",
                emptyMap(),
                file.inputStream(),
            )
        }.getOrDefault(notFound())
    }

    /**
     * 明确回 404，而不是返回 null 让 WebView 去访问 `rikkahub.local` 这个不存在的真域名
     * （那会变成一次必然失败的网络请求，报错也更难读）。
     *
     * 注意 `shouldInterceptRequest` 跑在 WebView 的 IO 线程上，不是主线程。
     */
    private fun notFound(): WebResourceResponse = WebResourceResponse(
        "text/plain",
        "UTF-8",
        404,
        "Not Found",
        emptyMap(),
        ByteArrayInputStream(ByteArray(0)),
    )

    private fun encodingFor(mimeType: String): String? =
        if (mimeType.startsWith("text/") ||
            mimeType.endsWith("json") ||
            mimeType.endsWith("xml") ||
            mimeType.endsWith("javascript")
        ) {
            "UTF-8"
        } else {
            null
        }

    private fun mimeTypeOf(path: String): String = when (path.substringAfterLast('.', "").lowercase()) {
        "js", "mjs" -> "text/javascript"
        "css" -> "text/css"
        "html", "htm" -> "text/html"
        "json" -> "application/json"
        "svg" -> "image/svg+xml"
        "png" -> "image/png"
        "jpg", "jpeg" -> "image/jpeg"
        "webp" -> "image/webp"
        "gif" -> "image/gif"
        "bmp" -> "image/bmp"
        "avif" -> "image/avif"
        "ico" -> "image/x-icon"
        "woff2" -> "font/woff2"
        "woff" -> "font/woff"
        "ttf" -> "font/ttf"
        "txt", "log", "md" -> "text/plain"
        else -> "application/octet-stream"
    }

    private fun decodePath(encoded: String): String? = decodeSandboxPath(encoded)
}
