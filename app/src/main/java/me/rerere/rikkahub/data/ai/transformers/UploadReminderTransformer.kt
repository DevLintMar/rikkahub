package me.rerere.rikkahub.data.ai.transformers

import android.content.Context
import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.files.FileFolders
import me.rerere.rikkahub.data.files.WorkspaceFileUrlResolver
import org.koin.core.component.KoinComponent
import org.koin.core.component.get
import java.io.File

/**
 * 无工作区时的「附件可贴性」提示注入。
 *
 * 绑了工作区的助手由 [WorkspaceReminderTransformer] 说明 `file://` 用法（连挂载点、`/upload`
 * 只读一起讲）；**没有工作区的助手此前完全不知道这回事**：它的消息里用户附件的 URL 已经被
 * [ImageLazyLoadTransformer] 改写成 `file:///upload/<文件名>`，模型**看得见这个 URL、却不知道
 * 能把它贴回回复里**，于是「把用户刚发来的图再显示给他看」这类需求模型只会干瞪眼。
 *
 * 因此只在**本次消息里确实带了 /upload 文件**时注入（空对话/纯文字对话一个字都不加）。
 * 落点与 [WorkspaceReminderTransformer] 相同：追加到第一条 system 消息、没有就插一条。
 *
 * 判定用的是 [WorkspaceFileUrlResolver.toSandboxPath] —— 「宿主文件 → 沙箱路径」的映射全仓
 * 只有这一份，这里不另写一张表。
 */
object UploadReminderTransformer : InputMessageTransformer, KoinComponent {

    override suspend fun transform(
        ctx: TransformerContext,
        messages: List<UIMessage>,
    ): List<UIMessage> {
        // 绑了工作区就交给 WorkspaceReminderTransformer，别重复讲
        if (ctx.assistant.workspaceId != null) return messages

        val filesDir = get<Context>().filesDir
        val uploads = messages
            .filter { it.role == MessageRole.USER }
            .flatMap { it.parts }
            .mapNotNull { part -> uploadPath(part.urlOrNull(), filesDir) }
            .distinct()
        if (uploads.isEmpty()) return messages

        val prompt = buildString {
            appendLine("<uploaded_files>")
            appendLine("The user attached these files to the conversation; they are addressable as `file://` URLs:")
            uploads.forEach { appendLine("- file://$it") }
            appendLine("To show one in your reply, reference it the same way — for example `![photo](file:///upload/photo.jpg)` — and the app renders it inline (images as pictures, other files as clickable links). Do that when the file actually matters to what you are saying.")
            appendLine("The `read_image` tool can read any of them back if you need another look.")
            append("</uploaded_files>")
        }

        val systemIndex = messages.indexOfFirst { it.role == MessageRole.SYSTEM }
        return if (systemIndex >= 0) {
            messages.toMutableList().apply {
                this[systemIndex] = this[systemIndex]
                    .appendText("\n\n$prompt")
                    .copy(isSynthetic = true)
            }
        } else {
            listOf(UIMessage.system(prompt).copy(isSynthetic = true)) + messages
        }
    }

    private fun UIMessagePart.urlOrNull(): String? = when (this) {
        is UIMessagePart.Image -> url
        is UIMessagePart.Video -> url
        is UIMessagePart.Audio -> url
        is UIMessagePart.Document -> url
        else -> null
    }

    /** 宿主文件 URL → `/upload/<文件名>`；不是 upload 目录下的真实文件时返回 null。 */
    private fun uploadPath(url: String?, filesDir: File): String? {
        if (url.isNullOrBlank() || !url.startsWith("file:")) return null
        val file = runCatching { File(url.removePrefix("file://")) }.getOrNull() ?: return null
        val sandboxPath = WorkspaceFileUrlResolver.toSandboxPath(file, filesDir) ?: return null
        return sandboxPath.takeIf { it.startsWith("/${FileFolders.UPLOAD}/") }
    }
}
