package me.rerere.rikkahub.data.ai.transformers

import android.content.Context
import androidx.core.net.toFile
import androidx.core.net.toUri
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import org.koin.core.component.KoinComponent
import org.koin.core.component.get
import java.io.File

/**
 * 图片懒加载：用户附加的图片不再直接发送给模型（避免每轮请求全量重编码重发、
 * 以及切换到非视觉模型时对全部历史图片的巨量 OCR 开销），
 * 改为注入一条包含图片路径的文本标记，AI 需要时调用 read_image 工具按需查看。
 *
 * 每轮生成都会对发送副本重新执行本 transform（不写回原消息），
 * 消息本体的 Image part 保持不变，UI / 导出 / 多图合并均不受影响。
 */
object ImageLazyLoadTransformer : InputMessageTransformer, KoinComponent {

    override suspend fun transform(
        ctx: TransformerContext,
        messages: List<UIMessage>,
    ): List<UIMessage> {
        val filesDir = get<Context>().filesDir
        return messages.map { message ->
            val images = message.parts.filterIsInstance<UIMessagePart.Image>()
                .filter { it.url.startsWith("file:") }
            if (images.isEmpty()) return@map message

            val paths = images.map { image ->
                val file = runCatching { image.url.toUri().toFile() }.getOrNull()
                resolveDisplayPath(file, filesDir)
            }
            val marker = buildString {
                appendLine("[The user attached ${images.size} image(s) to this message:")
                paths.forEach { appendLine("- $it") }
                append("The image contents are NOT included automatically. Call the `read_image` tool with these paths if you need to see them.]")
            }

            var markerInjected = false
            message.copy(
                parts = message.parts.mapNotNull { part ->
                    when {
                        part is UIMessagePart.Image && part.url.startsWith("file:") ->
                            if (markerInjected) null
                            else UIMessagePart.Text(marker).also { markerInjected = true }

                        else -> part
                    }
                }
            )
        }
    }

    /**
     * 物理文件 → AI 可见路径：upload 目录映射为 /upload/<文件名>（proot 挂载点），
     * 工作区文件映射为 /workspace/<相对路径>，其余回退为 file:// URL。
     */
    internal fun resolveDisplayPath(file: File?, filesDir: File): String {
        if (file == null) return "[unreadable image]"
        val canonical = runCatching { file.canonicalFile }.getOrDefault(file)
        if (canonical.parentFile == File(filesDir, "upload")) {
            return "/upload/${canonical.name}"
        }
        val workspacesDir = File(filesDir, "workspaces")
        val relative = runCatching { canonical.relativeTo(workspacesDir).path }.getOrNull()
        if (relative != null && !relative.startsWith("..")) {
            // workspaces/<id>/files/<rel> → /workspace/<rel>
            val segments = relative.split(File.separatorChar)
            if (segments.size >= 3 && segments[1].isNotEmpty() && segments[2] == "files") {
                return "/workspace/" + segments.drop(3).joinToString("/")
            }
        }
        return file.toUri().toString()
    }
}
