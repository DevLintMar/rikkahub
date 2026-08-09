package me.rerere.rikkahub.data.ai.tools

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.provider.Modality
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.ai.transformers.OcrTransformer
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.datastore.findModelById
import me.rerere.rikkahub.data.datastore.getCurrentAssistant
import me.rerere.rikkahub.data.files.FilesManager
import me.rerere.rikkahub.data.repository.WorkspaceRepository
import org.koin.java.KoinJavaComponent.getKoin
import java.io.File

/**
 * read_image 工具：图片懒加载的读取端。
 * 用户附加的图片默认不进上下文（见 ImageLazyLoadTransformer，只注入路径标记），
 * AI 需要查看时调用本工具按路径读取：
 * - 视觉模型：返回图片本体（三家 provider 的工具结果图片通道已存在）
 * - 非视觉模型：并行调用 OCR 模型转文本后一次性返回
 *
 * 与工作区绑定门控：助手未启用工作区时不注册（路径解析依赖 rootfs）。
 */
const val READ_IMAGE_MAX_IMAGES_PER_CALL = 8

private val READ_IMAGE_EXTENSIONS = setOf(
    "png", "jpg", "jpeg", "gif", "webp", "bmp", "heic", "heif", "avif",
)

fun createReadImageTool(
    workspaceId: String?,
    workspaceRepository: WorkspaceRepository,
): List<Tool> {
    if (workspaceId.isNullOrBlank()) return emptyList()
    return listOf(
        Tool(
            name = "read_image",
            description = """
                Read one or more images by path. Accepts paths under /upload/... (user attachments) and workspace paths (/workspace/... or absolute rootfs paths).
                Up to $READ_IMAGE_MAX_IMAGES_PER_CALL paths per call; any extra paths are omitted and reported in the result.
                For vision-capable models, the images themselves are returned — you will see them directly.
                For models without vision, each image is OCR'd by a dedicated vision model and returned as text wrapped in <image_file_ocr> tags (all images are processed in parallel).
                Use this tool to view images that the user attached in chat: their paths appear as /upload/... or /workspace/... in the message.
            """.trimIndent(),
            parameters = {
                InputSchema.Obj(
                    properties = buildJsonObject {
                        put("paths", buildJsonObject {
                            put("type", "array")
                            put("description", "Image paths to read, e.g. [\"/upload/xxx.jpg\", \"/workspace/report.png\"]. Max $READ_IMAGE_MAX_IMAGES_PER_CALL per call.")
                            put("items", buildJsonObject { put("type", "string") })
                        })
                    },
                    required = listOf("paths"),
                )
            },
            execute = { args ->
                val paths = parseReadImagePaths(args)
                when {
                    paths.isEmpty() -> listOf(
                        UIMessagePart.Text("""{"type":"read_image","error":"no valid paths provided"}""")
                    )

                    else -> {
                        val processed = paths.take(READ_IMAGE_MAX_IMAGES_PER_CALL)
                        val omitted = paths.size - processed.size
                        val parts = mutableListOf<UIMessagePart>()
                        if (omitted > 0) {
                            parts.add(
                                UIMessagePart.Text(
                                    "[Note: only the first $READ_IMAGE_MAX_IMAGES_PER_CALL images are processed; $omitted path(s) omitted.]"
                                )
                            )
                        }
                        val settings = getKoin().get<SettingsStore>().settingsFlow.value
                        val assistant = settings.getCurrentAssistant()
                        val model = settings.findModelById(assistant.chatModelId ?: settings.chatModelId)
                        val hasVision = model?.inputModalities?.contains(Modality.IMAGE) == true

                        // 并行读取各图（视觉直接读文件；非视觉并行 OCR），全部完成后一次性返回
                        val perImageParts = coroutineScope {
                            processed.map { path ->
                                async { readSingleImage(workspaceId, workspaceRepository, path, hasVision) }
                            }.awaitAll()
                        }
                        perImageParts.forEach { parts.addAll(it) }
                        parts
                    }
                }
            },
        )
    )
}

/** 从工具参数中提取 paths 数组（字符串条目、去空白、去空项）。供单测直接调用。 */
internal fun parseReadImagePaths(args: JsonElement): List<String> =
    runCatching {
        args.jsonObject["paths"]?.jsonArray
            ?.mapNotNull { it.jsonPrimitive.content.takeIf(String::isNotBlank)?.trim() }
            .orEmpty()
    }.getOrDefault(emptyList())

private suspend fun readSingleImage(
    workspaceId: String,
    workspaceRepository: WorkspaceRepository,
    path: String,
    hasVision: Boolean,
): List<UIMessagePart> {
    val extension = path.substringAfterLast('.', "").lowercase()
    if (extension !in READ_IMAGE_EXTENSIONS) {
        return listOf(
            UIMessagePart.Text(
                buildJsonObject {
                    put("type", "read_image")
                    put("path", path)
                    put("error", "Not an image file. Use workspace_read for non-image files.")
                }.toString()
            )
        )
    }

    val uri = try {
        resolveImageFileUri(workspaceId, workspaceRepository, path)
    } catch (e: Exception) {
        return listOf(
            UIMessagePart.Text(
                buildJsonObject {
                    put("type", "read_image")
                    put("path", path)
                    put("error", "Failed to read image: ${e.message}")
                }.toString()
            )
        )
    }

    return if (hasVision) {
        listOf(
            UIMessagePart.Image(url = uri),
            UIMessagePart.Text(
                buildJsonObject {
                    put("type", "read_image")
                    put("path", path)
                    put("description", "Image returned above")
                }.toString()
            ),
        )
    } else {
        val ocrText = OcrTransformer.performOcr(UIMessagePart.Image(url = uri))
        listOf(
            UIMessagePart.Text(
                buildJsonObject {
                    put("type", "read_image")
                    put("path", path)
                    put("description", "Current model has no vision; OCR text of the image follows.")
                }.toString()
            ),
            UIMessagePart.Text(ocrText),
        )
    }
}

/**
 * 路径 → 图片文件的 file:// URL。
 * /upload/... → filesDir/upload（proot 挂载点）；file:// → 校验在 filesDir 下；
 * 其余按工作区 rootfs 读取后落盘 upload/（readImageInRootfs 同款路径）。
 * canonicalFile 防路径穿越。
 */
internal suspend fun resolveImageFileUri(
    workspaceId: String,
    workspaceRepository: WorkspaceRepository,
    path: String,
): String {
    val context = getKoin().get<Context>()
    val filesDir = context.filesDir
    if (path.startsWith("/upload/")) {
        val rel = path.removePrefix("/upload/")
        val root = File(filesDir, "upload").canonicalFile
        val target = root.resolve(rel).canonicalFile
        require(target.startsWith(root) && target.isFile) { "Image not found: $path" }
        return "file://" + target.absolutePath.replace('\\', '/')
    }
    if (path.startsWith("file://")) {
        val target = runCatching { File(java.net.URI(path)).canonicalFile }.getOrNull()
            ?: throw IllegalArgumentException("Invalid image URI: $path")
        val root = filesDir.canonicalFile
        require(target.startsWith(root) && target.isFile) { "Image not found: $path" }
        return "file://" + target.absolutePath.replace('\\', '/')
    }
    // 工作区 / rootfs 路径：读取字节后落盘为 file:// 图片
    val bytes = withContext(Dispatchers.IO) {
        val size = workspaceRepository.rootfsFileSize(workspaceId, path)
        require(size in 1..8L * 1024 * 1024) { "Image is too large or empty: $path" }
        val buffer = java.io.ByteArrayOutputStream(size.toInt())
        workspaceRepository.exportRootfsFile(workspaceId, path, buffer)
        buffer.toByteArray()
    }
    val filesManager = getKoin().get<FilesManager>()
    val uri = filesManager.createChatFilesByByteArrays(listOf(bytes)).first()
    return uri.toString()
}
