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
                append("The image contents are NOT included automatically. Call the `read_image` tool with these URLs if you need to see them.]")
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
     * 物理文件 → AI 可见 URL：upload 目录映射为 file:///upload/<文件名>（proot 挂载点），
     * 工作区文件映射为 file:///workspace/<相对路径>，其余回退为自身 file:// URL。
     * 无法解析物理文件（如遗留 content://）时返回占位标记。
     */
    internal fun resolveDisplayPath(file: File?, filesDir: File): String {
        if (file == null) return "[Image]"
        val canonical = runCatching { file.canonicalFile }.getOrDefault(file)
        // upload 根也 canonical 化：context.filesDir 可能经符号链接（Android /data/data ↔ /data/user/0），
        // 与 canonicalFile 的字符串形式不一致会导致 == 失效，图片被错误回退为手机绝对路径
        val uploadRoot = runCatching { File(filesDir, "upload").canonicalFile }
            .getOrDefault(File(filesDir, "upload"))
        if (canonical.parentFile == uploadRoot) {
            return "file:///upload/${canonical.name}"
        }
        val workspacesDir = runCatching { File(filesDir, "workspaces").canonicalFile }
            .getOrDefault(File(filesDir, "workspaces"))
        val relative = runCatching { canonical.relativeTo(workspacesDir).path }.getOrNull()
        if (relative != null && !relative.startsWith("..")) {
            // workspaces/<id>/files/<rel> → file:///workspace/<rel>
            val segments = relative.split(File.separatorChar)
            if (segments.size >= 3 && segments[0].isNotEmpty() && segments[1] == "files") {
                return "file:///workspace/" + segments.drop(2).joinToString("/")
            }
        }
        // 回退：自身 file:// URL 字符串拼接（不依赖 Android toUri，纯 JVM 可测）
        return "file://" + file.absolutePath.replace('\\', '/')
    }
}
