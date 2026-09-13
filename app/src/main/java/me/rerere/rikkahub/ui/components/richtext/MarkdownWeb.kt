package me.rerere.rikkahub.ui.components.richtext

import android.content.Context
import androidx.compose.material3.ColorScheme
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.files.WorkspaceFileUrlResolver
import me.rerere.rikkahub.ui.components.webview.buildLocalFileUrl
import me.rerere.rikkahub.utils.base64Encode
import me.rerere.rikkahub.utils.toCssHex
import java.io.File

/**
 * Build HTML page for markdown preview with support for:
 * - Markdown rendering via marked.js
 * - LaTeX math via KaTeX
 * - Mermaid diagrams
 * - Syntax highlighting via highlight.js
 */
fun buildMarkdownPreviewHtml(
    context: Context,
    markdown: String,
    colorScheme: ColorScheme,
    images: List<UIMessagePart.Image> = emptyList(),
    workspaceId: String? = null,
): String {
    val htmlTemplate = context.assets.open("html/mark.html").bufferedReader().use { it.readText() }
    val content = buildPreviewMarkdown(
        filesDir = context.filesDir,
        markdown = markdown,
        attachmentUrls = images.map { it.url },
        workspaceId = workspaceId,
    )

    return htmlTemplate
        .replace("{{MARKDOWN_BASE64}}", content.base64Encode())
        .replace("{{BACKGROUND_COLOR}}", colorScheme.background.toCssHex())
        .replace("{{ON_BACKGROUND_COLOR}}", colorScheme.onBackground.toCssHex())
        .replace("{{SURFACE_COLOR}}", colorScheme.surface.toCssHex())
        .replace("{{ON_SURFACE_COLOR}}", colorScheme.onSurface.toCssHex())
        .replace("{{SURFACE_VARIANT_COLOR}}", colorScheme.surfaceVariant.toCssHex())
        .replace("{{ON_SURFACE_VARIANT_COLOR}}", colorScheme.onSurfaceVariant.toCssHex())
        .replace("{{PRIMARY_COLOR}}", colorScheme.primary.toCssHex())
        .replace("{{OUTLINE_COLOR}}", colorScheme.outline.toCssHex())
        .replace("{{OUTLINE_VARIANT_COLOR}}", colorScheme.outlineVariant.toCssHex())
}

/** `![alt](file:///workspace/x.png)`、`![alt](/upload/x.png)`、`![alt](<file://…>)` */
private val IMAGE_LINK_REGEX =
    Regex("""!\[([^\]]*)]\(\s*<?((?:file://)?/[^)\s>]+)>?\s*\)""")

/** markdown-it 开了 `html: true`，模型也可能直接写 `<img src="file://…">` */
private val HTML_IMG_SRC_REGEX =
    Regex("""(<img\b[^>]*?\ssrc\s*=\s*["'])(file://[^"']+)(["'])""", RegexOption.IGNORE_CASE)

/**
 * 把预览页要渲染的 markdown 整理成**本地图片都能显示**的形态。
 *
 * 预览页的 origin 是 `https://rikkahub.local`，直接引用 `file://` 子资源会被 WebView
 * 拦掉，所以本地图片一律改写成 [buildLocalFileUrl] 产出的本域名 URL，由
 * `WebViewLocalAssets` 在 `shouldInterceptRequest` 里读文件返回。
 *
 * 两件事：
 * 1. markdown 里已写好的图片链接（`file://` 前缀可写可不写）—— 解析得到真实文件才改写；
 * 2. 消息附件（[attachmentUrls]，`UIMessagePart.Image` 的宿主路径）—— 追加到末尾。
 *
 * 普通链接（非图片）不改写：沙箱文件不是网页，在预览页里当链接点开没有意义。
 *
 * 只依赖 [File]，可以在 JVM 单测里直接跑。
 */
internal fun buildPreviewMarkdown(
    filesDir: File,
    markdown: String,
    attachmentUrls: List<String> = emptyList(),
    workspaceId: String? = null,
): String {
    var text = IMAGE_LINK_REGEX.replace(markdown) { match ->
        val served = localPreviewUrl(filesDir, workspaceId, match.groupValues[2])
        if (served == null) match.value else "![${match.groupValues[1]}]($served)"
    }
    text = HTML_IMG_SRC_REGEX.replace(text) { match ->
        val served = localPreviewUrl(filesDir, workspaceId, match.groupValues[2])
        if (served == null) match.value
        else "${match.groupValues[1]}$served${match.groupValues[3]}"
    }

    val attachments = attachmentUrls.mapNotNull { localPreviewUrl(filesDir, workspaceId, it) }
    if (attachments.isEmpty()) return text

    return buildString {
        append(text.trimEnd())
        repeat(2) { appendLine() }
        attachments.forEach { appendLine("![]($it)") }
    }
}

/**
 * 一个本地图片引用 → 预览页可用的 URL；不是本地图片、或解析不到真实文件时返回 null
 * （调用方保留原文，行为与改动前一致）。
 *
 * @param href 消息附件的宿主路径（`file:///data/user/0/<pkg>/files/upload/a.jpg`）
 *             或模型写的沙箱逻辑路径（`file:///workspace/a.png`、`/upload/a.png`）
 */
private fun localPreviewUrl(filesDir: File, workspaceId: String?, href: String): String? {
    if (href.isBlank()) return null
    val path = href.removePrefix("file://")
    if (!path.startsWith("/")) return null

    // a) 宿主真实文件：反推沙箱路径（消息附件的 url 就是这种）
    val fromHost = runCatching { File(path) }
        .getOrNull()
        ?.let { WorkspaceFileUrlResolver.toSandboxPath(it, filesDir) }
    if (fromHost != null) return buildLocalFileUrl(workspaceId, fromHost)

    // b) 已经是沙箱逻辑路径 —— 必须真解析得到文件才改写，避免把死链接包装成 404
    val resolved = WorkspaceFileUrlResolver.resolveFile(filesDir, workspaceId, path) ?: return null
    if (!resolved.isFile) return null
    return buildLocalFileUrl(workspaceId, path)
}
