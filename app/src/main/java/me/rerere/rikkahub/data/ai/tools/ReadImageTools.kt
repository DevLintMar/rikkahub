package me.rerere.rikkahub.data.ai.tools

import android.content.Context
import kotlinx.coroutines.CancellationException
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
import me.rerere.rikkahub.data.files.FileFolders
import me.rerere.rikkahub.data.files.FilesManager
import me.rerere.rikkahub.data.files.WorkspaceFileUrlResolver
import me.rerere.rikkahub.utils.isTimeoutFailure
import me.rerere.rikkahub.utils.retryOnFailure
import org.koin.java.KoinJavaComponent.getKoin
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * read_image 工具：图片懒加载的读取端。
 * 用户附加的图片默认不进上下文（见 ImageLazyLoadTransformer，只注入 URL 标记），
 * AI 需要查看时调用本工具按 URL 读取：
 * - 视觉模型：返回图片本体（三家 provider 的工具结果图片通道已存在）
 * - 非视觉模型：并行调用 OCR 模型转文本后一次性返回
 *
 * 恒注册。URL 解析与 markdown 渲染共用一套 Rootfs 逻辑路径规则（[WorkspaceFileUrlResolver]）：
 * - `file:///upload/...` 本地附件（**无需工作区**）
 * - 工作区内任意绝对路径（`/workspace/...`、`/tmp/...`、`/skills/...` 等，需工作区）
 * - `http(s)://` 网页图片（下载到 upload/，8MB 上限）
 * 历史记录在 [chat_message_tool_read_image_failed] 的封装统一见 [ReadImageResult]。
 */
const val READ_IMAGE_MAX_IMAGES_PER_CALL = 8

private const val TAG = "ReadImageTools"

private const val READ_IMAGE_MAX_DOWNLOAD_BYTES = 8L * 1024 * 1024

/** http 下载失败自动重试：1 次初始 + 3 次重试 = 4 次尝试（网络波动等瞬时错误） */
private const val DOWNLOAD_MAX_ATTEMPTS = 4

/**
 * 图片下载的整体超时（秒）：覆盖 DNS、建连、TLS、重定向与读正文。
 *
 * 全局 OkHttp 客户端的 readTimeout 是 **10 分钟**（那是给流式 LLM 响应用的），且没有
 * callTimeout；只连得上但不返回内容的图片（tarpit、挂死的图床）会让 connectTimeout(20s)
 * 完全失效——TCP 已经连上了——一次调用就挂 10 分钟，再乘 4 次重试。
 * 因此这里用独立的 callTimeout 兜住，且超时后**不再重试**（见 [isTimeoutFailure]）。
 */
private const val READ_IMAGE_TIMEOUT_SECONDS = 20L

/**
 * 图片下载专用客户端：共享全局客户端的连接池与调度器，只收紧超时。
 */
private val imageHttpClient by lazy {
    getKoin().get<okhttp3.OkHttpClient>()
        .newBuilder()
        .callTimeout(READ_IMAGE_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .build()
}

/** 判断图片类型需要读取的文件头长度 */
private const val IMAGE_HEADER_BYTES = 16

fun createReadImageTool(
    workspaceId: String?,
): List<Tool> {
    return listOf(
        Tool(
            name = "read_image",
            description = """
                Read one or more images by URL. Accepts:
                - file:///upload/... — images the user attached to the chat (always available, works without a workspace)
                - file:///workspace/... — files in the workspace files area
                - any other absolute path inside the workspace rootfs, e.g. file:///tmp/chart.png or file:///skills/.../x.png
                - http:// or https:// links — downloaded and read (max 8 MB; the download is capped at 20 seconds and is not retried on timeout, so an unreachable or very slow link fails fast)
                The file:// prefix may be omitted. Paths inside the workspace need a workspace-enabled assistant.
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
                        async { readSingleImage(workspaceId, url, hasVision) }
                    }.awaitAll()
                }
                results.forEach { result ->
                    // 诊断写入请求日志页：图片解析不到/格式不对/编码不出时，这里能看到实际路径与原因
                    Logging.log(
                        TAG,
                        "read_image ${result.mode}: ${result.url}" +
                            (result.imageUri?.let { " -> $it" } ?: "") +
                            (result.text?.let { " | ${it.take(200)}" } ?: "")
                    )
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
    url: String,
    hasVision: Boolean,
): ReadImageResult {
    val uri = try {
        resolveImageFileUri(workspaceId, url)
    } catch (e: CancellationException) {
        throw e
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
 * URL → 可直接读取的图片 file:// URL。
 * - `http(s)://...` → 下载到 upload/
 * - 已在 upload 里的附件 → 原样引用，不复制
 * - 工作区内的文件（`/workspace/...`、`/tmp/...`、`/skills/...`）→ **复制**一份进 upload 再引用
 *
 * 复制而不是直接引用工作区原文件：工具结果里的 Image part 会进 `Conversation.files`，
 * 删除该消息时清理逻辑会把它当附件删掉（checkFilesDelete 只对 upload 目录留手），
 * 直接引用工作区路径会导致工作区里的原文件被删。上限 8MB；非图片内容、路径穿越一律拒绝。
 */
internal suspend fun resolveImageFileUri(
    workspaceId: String?,
    url: String,
): String {
    val trimmed = url.trim()
    if (trimmed.startsWith("http://", ignoreCase = true) || trimmed.startsWith("https://", ignoreCase = true)) {
        return downloadImageToUpload(trimmed)
    }
    val filesDir = getKoin().get<Context>().filesDir
    val file = WorkspaceFileUrlResolver.resolveFile(filesDir, workspaceId, trimmed)
        ?: error("Image not found: $url (file:// paths are resolved inside the workspace sandbox, not on the device)")
    val extension = inspectLocalImage(file, url)
    return if (isInUploadDir(filesDir, file)) {
        "file://" + file.absolutePath.replace('\\', '/')
    } else {
        val bytes = withContext(Dispatchers.IO) { file.readBytes() }
        getKoin().get<FilesManager>().createChatFilesByByteArrays(listOf(bytes), extension).first().toString()
    }
}

/** 本地图片校验：必须存在、非空、不超限、内容确实是图片；返回按文件头判断出的扩展名。 */
private suspend fun inspectLocalImage(file: File, url: String): String = withContext(Dispatchers.IO) {
    require(file.isFile) { "Image not found: $url" }
    val size = file.length()
    require(size in 1..READ_IMAGE_MAX_DOWNLOAD_BYTES) {
        "Image is empty or too large (> ${READ_IMAGE_MAX_DOWNLOAD_BYTES / 1024 / 1024}MB): $url"
    }
    val header = ByteArray(IMAGE_HEADER_BYTES)
    val read = runCatching { file.inputStream().use { it.read(header) } }.getOrDefault(-1)
    require(read > 0) { "Image is empty: $url" }
    sniffImageExtension(header.copyOf(read))
        ?: error("Not an image file. Use workspace_read for non-image files: $url")
}

/** 文件是否位于 `<filesDir>/upload`（符号链接两种拼写都认） */
private fun isInUploadDir(filesDir: File, file: File): Boolean {
    val uploadRoot = runCatching { File(filesDir, FileFolders.UPLOAD).canonicalFile.path }.getOrNull()
        ?: return false
    val canonical = runCatching { file.canonicalFile.path }.getOrNull() ?: return false
    return canonical.startsWith(uploadRoot + File.separator)
}

/**
 * 按文件头判断图片格式，返回扩展名；不是图片返回 null。
 * 比看扩展名可靠：AI/网页给的 URL 常常没有扩展名、带 query，或扩展名与实际内容不符。
 * 纯逻辑，供单测直接调用。
 */
internal fun sniffImageExtension(header: ByteArray): String? {
    fun ascii(from: Int, to: Int): String =
        if (header.size >= to) header.copyOfRange(from, to).toString(Charsets.US_ASCII) else ""

    // HEIF/HEIC/AVIF：ISO-BMFF 容器，"ftyp" box 在字节 4..8，主品牌码在 8..12
    if (ascii(4, 8) == "ftyp") {
        when (ascii(8, 12)) {
            "heic", "heix", "heim", "heis",
            "hevc", "hevx", "hevm", "hevs",
            "mif1", "msf1", "heif",
                -> return "heic"

            "avif", "avis" -> return "avif"
        }
    }
    if (header.size >= 2 && header[0] == 0xFF.toByte() && header[1] == 0xD8.toByte()) return "jpg"
    if (header.size >= 8 && header.copyOfRange(0, 8).contentEquals(
            byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)
        )
    ) {
        return "png"
    }
    if (ascii(0, 4) == "RIFF" && ascii(8, 12) == "WEBP") return "webp"
    val gifHeader = ascii(0, 6)
    if (gifHeader == "GIF89a" || gifHeader == "GIF87a") return "gif"
    if (header.size >= 2 && header[0] == 'B'.code.toByte() && header[1] == 'M'.code.toByte()) return "bmp"
    return null
}

/**
 * 下载 http(s) 图片到 upload/，返回 file:// URL。上限 8MB，超限或非图片抛出。
 *
 * **整体 20 秒超时**（[READ_IMAGE_TIMEOUT_SECONDS]），超时直接失败且不重试——访问不到的图片
 * 重试只是再等一轮，会把工具调用一直挂住。其余瞬时错误（5xx、连接被重置等）仍自动重试
 * （1 次初始 + 3 次重试）；文件落盘只做一次。
 */
private suspend fun downloadImageToUpload(url: String): String {
    val bytes = try {
        retryOnFailure(
            attempts = DOWNLOAD_MAX_ATTEMPTS,
            onRetry = { attempt, e ->
                Logging.log(TAG, "downloadImageToUpload: attempt $attempt failed, retrying: ${e.message}")
            },
            retryIf = { !isTimeoutFailure(it) },
        ) {
            val response = withContext(Dispatchers.IO) {
                imageHttpClient.newCall(okhttp3.Request.Builder().url(url).build()).execute()
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
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        if (isTimeoutFailure(e)) {
            Logging.log(
                TAG,
                "downloadImageToUpload: timed out after ${READ_IMAGE_TIMEOUT_SECONDS}s, not retrying: $url"
            )
            throw IllegalStateException(
                "Download timed out after ${READ_IMAGE_TIMEOUT_SECONDS}s (image unreachable or too slow): $url",
                e,
            )
        }
        throw e
    }
    // 扩展名按下载到的内容判断：网页图片常常没有扩展名或带 query（?w=100），URL 后缀不可信
    val extension = sniffImageExtension(bytes)
        ?: error("Downloaded content is not an image: $url")
    return getKoin().get<FilesManager>()
        .createChatFilesByByteArrays(listOf(bytes), extension)
        .first()
        .toString()
}
