package me.rerere.rikkahub.data.ai.transformers

import android.content.Context
import androidx.core.net.toFile
import androidx.core.net.toUri
import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.files.WorkspaceFileUrlResolver
import me.rerere.rikkahub.data.model.IMAGE_LAZY_LOAD_MARKER_PREFIX
import org.koin.core.component.KoinComponent
import org.koin.core.component.get
import java.io.File

/**
 * 图片懒加载：用户附加的图片不再直接发送给模型（避免每轮请求全量重编码重发、
 * 以及切换到非视觉模型时对全部历史图片的巨量 OCR 开销），
 * 改为注入一条包含图片路径的文本标记，AI 需要时调用 read_image 工具按需查看。
 *
 * 只处理 USER role 消息中的图片（用户附加的才是"待查看"图片）；
 * assistant 出图与工具结果图片是模型已可见的产物，保持原样，
 * 避免重新生成含图消息时把它们错误替换成标记、导致图片丢失或找不到文件。
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
            // 仅懒加载用户附加的图片；assistant 出图/工具结果图片保持原样
            if (message.role != MessageRole.USER) return@map message
            val images = message.parts.filterIsInstance<UIMessagePart.Image>()
                .filter { it.url.startsWith("file:") }
            if (images.isEmpty()) return@map message

            val paths = images.map { image ->
                val file = runCatching { image.url.toUri().toFile() }.getOrNull()
                resolveDisplayPath(file, filesDir)
            }
            val marker = buildString {
                appendLine("${IMAGE_LAZY_LOAD_MARKER_PREFIX}${images.size} image(s) to this message:")
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
        // 文件已不存在：不映射成看似可读的 URL，模型读到 [Image] 就不会去调 read_image 报"找不到图片"
        if (!canonical.isFile) return "[Image]"
        // 宿主文件 → 沙箱路径的映射只此一份（WorkspaceFileUrlResolver.toSandboxPath），
        // 网页视图预览那边也走它，避免两处各存一张表而漂移
        WorkspaceFileUrlResolver.toSandboxPath(canonical, filesDir)?.let { return "file://$it" }
        // 回退：自身 file:// URL 字符串拼接（不依赖 Android toUri，纯 JVM 可测）
        return "file://" + file.absolutePath.replace('\\', '/')
    }
}
