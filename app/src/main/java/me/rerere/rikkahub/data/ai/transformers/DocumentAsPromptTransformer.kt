package me.rerere.rikkahub.data.ai.transformers

import androidx.core.net.toFile
import androidx.core.net.toUri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.document.DocxParser
import me.rerere.document.EpubParser
import me.rerere.document.PdfParser
import me.rerere.document.PptxParser
import android.content.Context
import me.rerere.rikkahub.data.files.WorkspaceFileUrlResolver
import me.rerere.rikkahub.utils.isAllowedFileType
import org.koin.core.component.KoinComponent
import org.koin.core.component.get
import java.io.File

object DocumentAsPromptTransformer : InputMessageTransformer, KoinComponent {
    override suspend fun transform(
        ctx: TransformerContext,
        messages: List<UIMessage>,
    ): List<UIMessage> {
        return withContext(Dispatchers.IO) {
            messages.map { message ->
                message.copy(
                    parts = message.parts.toMutableList().apply {
                        val documents = filterIsInstance<UIMessagePart.Document>()
                        if (documents.isNotEmpty()) {
                            documents.forEach { document ->
                                val content = readDocumentContent(document)
                                val path = resolveWorkspacePath(document)
                                val pathAttr = path?.let { " path=\"$it\"" } ?: ""
                                val prompt = """
                                  <UploadFile name="${document.fileName}"$pathAttr>
                                  ```
                                  $content
                                  ```
                                  </UploadFile>
                                  """.trimMargin()
                                add(0, UIMessagePart.Text(prompt))
                            }
                        }
                    }
                )
            }
        }
    }

    private fun parsePdfAsText(file: File): String {
        return PdfParser.parserPdf(file)
    }

    private fun parseDocxAsText(file: File): String {
        return DocxParser.parse(file)
    }

    private fun parsePptxAsText(file: File): String {
        return PptxParser.parse(file)
    }

    private fun parseEpubAsText(file: File): String {
        return EpubParser.parse(file)
    }

    private val ARCHIVE_AND_BINARY_EXTENSIONS = setOf(
        "zip", "tar", "gz", "tgz", "bz2", "tbz2", "xz", "txz", "7z", "rar",
        "apk", "jar", "bin", "iso", "img", "so", "dylib", "dll", "exe",
        "db", "sqlite", "sqlite3", "parquet", "arrow", "feather", "pkl", "pickle",
        "npy", "npz", "h5", "hdf5", "onnx", "pt", "pth", "safetensors",
    )

    private fun isArchiveOrBinary(file: File, mime: String): Boolean {
        val ext = file.extension.lowercase()
        if (ext in ARCHIVE_AND_BINARY_EXTENSIONS) return true
        if (!isAllowedFileType(file.name, mime)) return true
        return false
    }

    // 上传文件保存在 filesDir/upload (全局 /upload) 或工作区 files/upload (/workspace/upload)
    // 返回文件在沙箱内的绝对路径, 便于 AI 用 workspace 工具或 bash 直接读取原始文件
    private fun resolveWorkspacePath(document: UIMessagePart.Document): String? {
        val file = runCatching { document.url.toUri().toFile() }.getOrNull() ?: return null
        val context = runCatching { get<Context>() }.getOrNull()
        if (context != null) {
            val sandbox = WorkspaceFileUrlResolver.toSandboxPath(file, context.filesDir)
            if (sandbox != null) return sandbox
        }
        if (file.parentFile?.name == "upload" && file.parentFile?.parentFile?.name == "files") {
            return "/workspace/upload/${file.name}"
        }
        if (file.parentFile?.name == "upload") {
            return "/upload/${file.name}"
        }
        return null
    }

    private fun readDocumentContent(document: UIMessagePart.Document): String {
        val file = runCatching { document.url.toUri().toFile() }.getOrNull()
            ?: return "[ERROR, invalid file uri: ${document.fileName}]"
        if (!file.exists() || !file.isFile) {
            return "[ERROR, file not found: ${document.fileName}]"
        }

        // 压缩包/二进制或落盘在 /workspace/upload 的文件：不读文本乱码，向 AI 说明路径与处理方式
        if (isArchiveOrBinary(file, document.mime)) {
            val path = resolveWorkspacePath(document) ?: "/workspace/upload/${file.name}"
            return """
                [Binary / Archive file uploaded to workspace: $path]
                This file has been placed in your workspace at `$path` (a read-write directory). You can inspect, unpack, or execute it using workspace bash commands (e.g. `unzip "$path" -d /workspace`, `tar -xf ...`, python) or workspace tools.
            """.trimIndent()
        }

        return runCatching {
            when (document.mime) {
                "application/pdf" -> parsePdfAsText(file)
                "application/vnd.openxmlformats-officedocument.wordprocessingml.document" -> parseDocxAsText(file)
                "application/vnd.openxmlformats-officedocument.presentationml.presentation" -> parsePptxAsText(file)
                "application/epub+zip" -> parseEpubAsText(file)
                else -> file.readText()
            }
        }.getOrElse {
            "[ERROR, failed to read file: ${document.fileName}]"
        }
    }
}
