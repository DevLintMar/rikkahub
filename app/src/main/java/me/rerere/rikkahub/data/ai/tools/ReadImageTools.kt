package me.rerere.rikkahub.data.ai.tools

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.provider.Modality
import me.rerere.ai.ui.UIMessagePart
import me.rerere.common.android.Logging
import me.rerere.rikkahub.data.ai.transformers.OcrTransformer
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.datastore.findModelById
import me.rerere.rikkahub.data.datastore.getCurrentAssistant
import me.rerere.rikkahub.data.files.FilesManager
import me.rerere.rikkahub.data.repository.WorkspaceRepository
import me.rerere.rikkahub.utils.retryOnFailure
import org.koin.java.KoinJavaComponent.getKoin
import java.io.File

/**
 * read_image 工具：图片懒加载的读取端。
 * 用户附加的图片默认不进上下文（见 ImageLazyLoadTransformer，只注入 URL 标记），
 * AI 需要查看时调用本工具按 URL 读取：
 * - 视觉模型：返回图片本体（三家 provider 的工具结果图片通道已存在）
 * - 非视觉模型：并行调用 OCR 模型转文本后一次性返回
 *
 * 恒注册（workspaceId 仅用于解析 file:///workspace/... 等 rootfs 路径；不传则只可用
 * file:///upload/... 与 http(s) URL，完全满足用户附加图片的读取）。
 * 历史记录在 [chat_message_tool_read_image_failed] 的封装统一见 [ReadImageResult]。
 */
const val READ_IMAGE_MAX_IMAGES_PER_CALL = 8

private const val TAG = "ReadImageTools"

private const val READ_IMAGE_MAX_DOWNLOAD_BYTES = 8L * 1024 * 1024

/** http 下载失败自动重试：1 次初始 + 3 次重试 = 4 次尝试（网络波动等瞬时错误） */
private const val DOWNLOAD_MAX_ATTEMPTS = 4

private val READ_IMAGE_EXTENSIONS = setOf(
    "png", "jpg", "jpeg", "gif", "webp", "bmp", "heic", "heif", "avif",
)

fun createReadImageTool(
    workspaceId: String?,
    workspaceRepository: WorkspaceRepository,
): List<Tool> {
    return listOf(
        Tool(
            name = "read_image",
            description = """
                Read one or more images by URL. Accepts:
                - file:///upload/... — images the user attached to the chat (always available)
                - file:///workspace/... or other absolute workspace paths (requires a workspace-enabled assistant)
                - http:// or https:// links — downloaded and read
                Up to $READ_IMAGE_MAX_IMAGES_PER_CALL images per call; any extra URLs are omitted and reported in the result.
                For vision-capable models, the images themselves are returned — you will see them directly.
                For models without vision, each image is OCR'd by a dedicated vision model and returned as text wrapped in <image_file_ocr> tags (all images are processed in parallel).
                Use this tool to view images that the user attached in chat: their URLs appear as file:///upload/... in the message.
            """.trimIndent(),
            parameters = {
                InputSchema.Obj(
                    properties = buildJsonObject {
                        put("urls", buildJsonObject {
                            put("type", "array")
                            put("description", "Image URLs to read, e.g. [\"file:///upload/xxx.jpg\", \"https://example.com/a.png\"]. Max $READ_IMAGE_MAX_IMAGES_PER_CALL per call.")
                            put("items", buildJsonObject { put("type", "string") })
                        })
                    },
                    required = listOf("urls"),
                )
            },
            execute = { args ->
                val urls = parseReadImagePaths(args)
                if (urls.isEmpty()) {
                    return@Tool listOf(
                        UIMessagePart.Text(
                            buildJsonObject {
                                put("type", "read_image")
                                put("mode", "error")
                                put("text", "no valid urls provided")
                            }.toString()
                        )
                    )
                }

                val processed = urls.take(READ_IMAGE_MAX_IMAGES_PER_CALL)
                val omitted = urls.size - processed.size

                val settings = getKoin().get<SettingsStore>().settingsFlow.value
                val assistant = settings.getCurrentAssistant()
                val model = settings.findModelById(assistant.chatModelId ?: settings.chatModelId)
                val hasVision = model?.inputModalities?.contains(Modality.IMAGE) == true

                // 并行读取各图（视觉直接读文件；非视觉并行 OCR），全部完成后一次性汇总
                val results = coroutineScope {
                    processed.map { url ->
                        async { readSingleImage(workspaceId, workspaceRepository, url, hasVision) }
                    }.awaitAll()
                }
                // 图片本体作为 Image parts 返回（provider 侧编码后视觉模型真正看到）
                val imageParts = results.mapNotNull { it.imageUri }
                    .map { UIMessagePart.Image(url = it) }
                val envelope = buildJsonObject {
                    put("type", "read_image")
                    if (omitted > 0) {
                        put("note", "only the first $READ_IMAGE_MAX_IMAGES_PER_CALL images processed; $omitted URL(s) omitted")
                    }
                    put("results", buildJsonArray {
                        results.forEach { result ->
                            add(buildJsonObject {
                                put("url", result.url)
                                put("mode", result.mode)
                                if (result.text != null) put("text", result.text)
                            })
                        }
                    })
                }
                imageParts + UIMessagePart.Text(envelope.toString())
            },
        )
    )
}

/** 从工具参数中提取 urls 数组（字符串条目、去空白、去空项）。供单测直接调用。 */
internal fun parseReadImagePaths(args: JsonElement): List<String> =
    runCatching {
        args.jsonObject["urls"]?.jsonArray
            ?.mapNotNull { it.jsonPrimitive.content.takeIf(String::isNotBlank)?.trim() }
            .orEmpty()
    }.getOrDefault(emptyList())

/** 单张图片读取结果。mode: "base64"（图片本体）/ "ocr"（识别文本）/ "error"（失败信息）。 */
private data class ReadImageResult(
    val url: String,
    val mode: String,
    val imageUri: String? = null,
    val text: String? = null,
)

private suspend fun readSingleImage(
    workspaceId: String?,
    workspaceRepository: WorkspaceRepository,
    url: String,
    hasVision: Boolean,
): ReadImageResult {
    val extension = url.substringAfterLast('.', "").lowercase()
    if (extension !in READ_IMAGE_EXTENSIONS) {
        return ReadImageResult(
            url = url,
            mode = "error",
            text = "Not an image file. Use workspace_read for non-image files.",
        )
    }

    val uri = try {
        resolveImageFileUri(workspaceId, workspaceRepository, url)
    } catch (e: Exception) {
        return ReadImageResult(
            url = url,
            mode = "error",
            text = "Failed to read image: ${e.message}",
        )
    }

    return if (hasVision) {
        ReadImageResult(url = url, mode = "base64", imageUri = uri)
    } else {
        val ocrText = OcrTransformer.performOcr(UIMessagePart.Image(url = uri))
        ReadImageResult(url = url, mode = "ocr", text = ocrText)
    }
}

/**
 * URL → 图片文件的 file:// URL（本地缓存副本）。
 * - file:///upload/... → filesDir/upload（全局可解析；没有工作区时该挂载依然存在）
 * - file:///workspace/... 与其它绝对路径 → 工作区 rootfs 读取后落盘 upload/
 * - http(s)://... → 下载到 upload/（上限 8MB）
 * canonicalFile 防路径穿越。其它 scheme 一律拒绝。
 */
internal suspend fun resolveImageFileUri(
    workspaceId: String?,
    workspaceRepository: WorkspaceRepository,
    url: String,
): String {
    val context = getKoin().get<Context>()
    val filesDir = context.filesDir
    if (url.startsWith("file:///upload/")) {
        val rel = url.removePrefix("file:///upload/")
        val root = File(filesDir, "upload").canonicalFile
        val target = root.resolve(rel).canonicalFile
        require(target.startsWith(root)) { "Image not found under /upload: $url" }
        require(target.isFile) { "Image not found under /upload: $url" }
        return "file://" + target.absolutePath.replace('\\', '/')
    }
    if (url.startsWith("http://") || url.startsWith("https://")) {
        return downloadImageToUpload(url)
    }
    // 工作区 / rootfs 路径：读取字节后落盘为 file:// 图片（保留扩展名）
    require(!workspaceId.isNullOrBlank()) {
        "Workspace paths require a workspace-enabled assistant; only file:///upload/... and http(s) URLs are available here"
    }
    val extension = url.substringAfterLast('.', "").lowercase().ifEmpty { "png" }
    val bytes = withContext(Dispatchers.IO) {
        val size = workspaceRepository.rootfsFileSize(workspaceId, url)
        require(size in 1..8L * 1024 * 1024) { "Image is too large or empty: $url" }
        val buffer = java.io.ByteArrayOutputStream(size.toInt())
        workspaceRepository.exportRootfsFile(workspaceId, url, buffer)
        buffer.toByteArray()
    }
    val filesManager = getKoin().get<FilesManager>()
    val uri = filesManager.createChatFilesByByteArrays(listOf(bytes), extension).first()
    return uri.toString()
}

/**
 * 下载 http(s) 图片到 upload/，返回 file:// URL。上限 8MB，超限或失败抛出。
 * 网络波动等瞬时错误自动重试（1 次初始 + 3 次重试），全部失败才抛出；文件落盘只做一次。
 */
private suspend fun downloadImageToUpload(url: String): String {
    val okHttpClient = getKoin().get<okhttp3.OkHttpClient>()
    val bytes = retryOnFailure(
        attempts = DOWNLOAD_MAX_ATTEMPTS,
        onRetry = { attempt, e ->
            Logging.log(TAG, "downloadImageToUpload: attempt $attempt failed, retrying: ${e.message}")
        },
    ) {
        val response = withContext(Dispatchers.IO) {
            okHttpClient.newCall(okhttp3.Request.Builder().url(url).build()).execute()
        }
        try {
            if (!response.isSuccessful) error("Download failed with status ${response.code}")
            withContext(Dispatchers.IO) {
                response.body?.let { body ->
                    val source = body.source()
                    source.request(READ_IMAGE_MAX_DOWNLOAD_BYTES + 1)
                    check(source.buffer.size <= READ_IMAGE_MAX_DOWNLOAD_BYTES) {
                        "Image too large to download (> ${READ_IMAGE_MAX_DOWNLOAD_BYTES / 1024 / 1024}MB): $url"
                    }
                    source.readByteArray()
                } ?: error("Empty response body")
            }
        } finally {
            response.close()
        }
    }
    val extension = url.substringBefore('?').substringAfterLast('.', "").lowercase().ifEmpty { "png" }
    return getKoin().get<FilesManager>()
        .createChatFilesByByteArrays(listOf(bytes), extension)
        .first()
        .toString()
}
