package me.rerere.rikkahub.ui.pages.chat

import android.content.Context
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.compose.ui.graphics.Color
import coil3.imageLoader
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import kotlinx.coroutines.yield
import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessagePart
import me.rerere.highlight.Highlighter
import me.rerere.highlight.prewarmHighlight
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.model.AssistantAffectScope
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.data.model.replaceRegexes
import me.rerere.rikkahub.ui.components.richtext.ImageAspectRatioCache
import me.rerere.rikkahub.ui.components.richtext.collectCodeFences
import me.rerere.rikkahub.ui.components.richtext.collectImageUrls
import me.rerere.rikkahub.ui.components.richtext.prewarmLatex
import me.rerere.rikkahub.ui.components.richtext.prewarmMarkdown
import me.rerere.rikkahub.utils.JsonInstant
import java.io.File

/**
 * 切换对话遮罩期间后台预热：把对话 markdown 解析、LaTeX 编译、代码高亮与图片尺寸写入进程级缓存。
 * 露出后 MarkdownBlock/LatexText/ZoomableAsyncImage 首帧即命中，彻底消灭首轮解析与高度弹跳卡顿。
 *
 * 预热策略：
 * - 倒序遍历（最新消息优先，即视口首屏可见区优先）；
 * - 双阶段就绪：首屏前 [priorityCount] 条消息预热完成后立即回调 [onPriorityReady]，
 *   通知 UI 快速揭开遮罩，剩余历史消息继续在后台静默预热。
 */
suspend fun prewarmConversation(
    context: Context,
    conversation: Conversation,
    assistant: Assistant?,
    highlighter: Highlighter,
    priorityCount: Int = 4,
    contentColor: Color = Color.Unspecified,
    workspaceResolver: ((String) -> File?)? = null,
    onPriorityReady: (() -> Unit)? = null,
) {
    // 扩展抓手 C: 后台静默预唤醒 QuickJS / Prism.js 引擎，将冷启动 40~60ms 转移至后台协程
    runCatching {
        prewarmHighlight(highlighter, " ", "plaintext")
    }

    var prioritySignaled = false
    var processedCount = 0

    conversation.currentMessages.asReversed().forEach { message ->
        yield()   // 让超时或切会话的取消在每条消息间可观察
        val scope = if (message.role == MessageRole.USER) AssistantAffectScope.USER else AssistantAffectScope.ASSISTANT
        message.parts.forEach { part ->
            try {
                when (part) {
                    is UIMessagePart.Text -> prewarmText(context, part.text, assistant, scope, highlighter, contentColor, workspaceResolver)
                    is UIMessagePart.Reasoning -> prewarmText(context, part.reasoning, assistant, AssistantAffectScope.ASSISTANT, highlighter, contentColor, workspaceResolver)
                    is UIMessagePart.Tool -> prewarmToolOutput(highlighter, part)
                    is UIMessagePart.Image -> prewarmSingleImage(context, part.url, workspaceResolver)
                    else -> {}
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e                       // 超时取消必须向上传播，不能吞
            } catch (_: Exception) {
                // 单条消息预热失败跳过，不中止整段
            }
        }

        processedCount++
        if (!prioritySignaled && processedCount >= priorityCount) {
            prioritySignaled = true
            onPriorityReady?.invoke()
        }
    }

    if (!prioritySignaled) {
        prioritySignaled = true
        onPriorityReady?.invoke()
    }
}

private suspend fun prewarmText(
    context: Context,
    text: String,
    assistant: Assistant?,
    scope: AssistantAffectScope,
    highlighter: Highlighter,
    contentColor: Color = Color.Unspecified,
    workspaceResolver: ((String) -> File?)? = null,
) {
    val transformed = text.replaceRegexes(assistant, scope, visual = true)
    // 抓手 3: AST 树预热
    prewarmMarkdown(transformed)
    // 抓手 1: LaTeX 公式预编译进进程级 LRU 缓存池（同时预热测量 Unspecified 与绘制 contentColor）
    prewarmLatex(transformed, contentColor)
    // 代码高亮预热
    collectCodeFences(transformed).forEach { (code, language) ->
        prewarmHighlight(highlighter, code, language)
    }
    // 抓手 2: Markdown 内嵌图片预取与尺寸缓存（支持沙箱与宿主物理文件）
    collectImageUrls(transformed).forEach { url ->
        prewarmSingleImage(context, url, workspaceResolver)
    }
}

private suspend fun prewarmSingleImage(
    context: Context,
    url: String?,
    workspaceResolver: ((String) -> File?)? = null,
) {
    if (url.isNullOrBlank()) return
    if (ImageAspectRatioCache.get(url) != null) return

    runCatching {
        // 扩展抓手 A: 宿主物理文件与工作区沙箱路径解析
        val file: File? = when {
            url.startsWith("file://") -> {
                val filePath = Uri.parse(url).path ?: url.removePrefix("file://")
                File(filePath).takeIf { it.isFile && it.length() > 0 }
            }
            url.startsWith("/") -> File(url).takeIf { it.isFile && it.length() > 0 }
            workspaceResolver != null -> {
                var f = workspaceResolver(url)?.takeIf { it.isFile && it.length() > 0 }
                if (f == null && url.startsWith("file://") && !url.startsWith("file:///")) {
                    f = workspaceResolver("file:///" + url.removePrefix("file://"))?.takeIf { it.isFile && it.length() > 0 }
                }
                if (f == null && !url.startsWith("/") && !url.contains("://")) {
                    f = workspaceResolver("/workspace/${url.removePrefix("./")}")?.takeIf { it.isFile && it.length() > 0 }
                }
                f
            }
            else -> null
        }

        if (file != null) {
            val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(file.absolutePath, opts)
            if (opts.outWidth > 0 && opts.outHeight > 0) {
                ImageAspectRatioCache.put(url, opts.outWidth, opts.outHeight)
                ImageAspectRatioCache.put(file.absolutePath, opts.outWidth, opts.outHeight)
            }
            val request = ImageRequest.Builder(context).data(file).build()
            context.imageLoader.execute(request)
            return
        }

        // 网络或 Content URI 走 Coil 预解码
        val request = ImageRequest.Builder(context)
            .data(url)
            .build()
        val result = context.imageLoader.execute(request)
        val image = (result as? SuccessResult)?.image
        if (image != null && image.width > 0 && image.height > 0) {
            ImageAspectRatioCache.put(url, image.width, image.height)
        }
    }
}

private suspend fun prewarmToolOutput(highlighter: Highlighter, tool: UIMessagePart.Tool) {
    val text = tool.output.filterIsInstance<UIMessagePart.Text>().joinToString("\n") { it.text }
    if (text.isBlank()) return
    // 与 DefaultToolPreview 一致：非 JSON 输出才走 HighlightCodeBlock(plaintext)
    if (runCatching { JsonInstant.parseToJsonElement(text) }.isFailure) {
        prewarmHighlight(highlighter, text, "plaintext")
    }
}
