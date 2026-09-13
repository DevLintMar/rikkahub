package me.rerere.rikkahub.data.ai.transformers

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import me.rerere.ai.core.MessageRole
import me.rerere.ai.provider.Modality
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ProviderManager
import me.rerere.ai.provider.TextGenerationParams
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.common.android.Logging
import me.rerere.common.cache.LruCache
import me.rerere.common.cache.SingleFileCacheStore
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.datastore.findModelById
import me.rerere.rikkahub.data.datastore.findProvider
import me.rerere.rikkahub.utils.retryOnFailure
import org.koin.core.component.KoinComponent
import org.koin.core.component.get
import java.io.File
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.seconds

private const val TAG = "OcrTransformer"

/** OCR 模型调用失败自动重试：1 次初始 + 3 次重试 = 4 次尝试（网络波动等瞬时错误） */
private const val OCR_MAX_ATTEMPTS = 4

/**
 * 单次 OCR 调用的硬上界。全局 provider 客户端只有 `readTimeout = 10 分钟` 而**没有**
 * `callTimeout`，所以「连得上但不返回」的服务端能把一次调用拖满 10 分钟；再乘上 4 次重试
 * 就是 40 分钟，而且每张图各算一遍 —— 用户看到的就是「发消息后一直转圈」。
 * read_image 的 http 下载当初是同一类问题（见 `ReadImageTools.kt` 顶部注释）。
 */
private val OCR_ATTEMPT_TIMEOUT = 120.seconds

/** [OCR_ATTEMPT_TIMEOUT] 触发的超时。单独一个类型，好让 [retryOnFailure] 的 retryIf 认出它并停止重试。 */
private class OcrTimeoutException(message: String) : Exception(message)

object OcrTransformer : InputMessageTransformer, KoinComponent {
    private val cache by lazy {
        val context = get<Context>()
        val json = Json { allowStructuredMapKeys = true }
        val store = SingleFileCacheStore(
            file = File(context.cacheDir, "ocr_cache.json"),
            keySerializer = String.serializer(),
            valueSerializer = String.serializer(),
            json = json
        )
        LruCache(
            capacity = 64,
            store = store,
            deleteOnEvict = true,
            preloadFromStore = true,
            expireAfterWriteMillis = 3.days.inWholeMilliseconds,
        )
    }

    override suspend fun transform(
        ctx: TransformerContext,
        messages: List<UIMessage>,
    ): List<UIMessage> {
        if (ctx.model.inputModalities.contains(Modality.IMAGE)) {
            return messages
        }

        val hasImages = messages.any { message ->
            message.parts.any { it is UIMessagePart.Image && it.url.startsWith("file:") }
        }
        if (!hasImages) return messages

        return withContext(Dispatchers.IO) {
            try {
                ctx.processingStatus.value = "正在识别图片..."
                messages.map { message ->
                    message.copy(
                        parts = message.parts.map { part ->
                            when {
                                part is UIMessagePart.Image && part.url.startsWith("file:") -> {
                                    UIMessagePart.Text(performOcr(part))
                                }

                                else -> part
                            }
                        }
                    )
                }
            } finally {
                ctx.processingStatus.value = null
            }
        }
    }

    suspend fun performOcr(part: UIMessagePart.Image): String {
        // Check cache first
        cache.get(part.url)?.let { cachedResult ->
            Log.i(TAG, "performOcr: Using cached result for ${part.url}")
            return cachedResult
        }

        val settings = get<SettingsStore>().settingsFlow.value
        val model = settings.findModelById(settings.ocrModelId)
            ?: return "[ERROR, OCR model not configured]"
        val providerSetting = model.findProvider(settings.providers)
            ?: return "[ERROR, OCR model provider not found]"
        val provider = get<ProviderManager>().getProviderByType(providerSetting)

        return try {
            // 网络波动等瞬时错误自动重试（1 次初始 + 3 次重试），全部失败才把错误返回给模型。
            // **超时不重试**：服务端连得上却不返回时，重试只是再等一轮（与 Retry.kt、
            // ReadImageTools 的同一条判断），不如尽快把错误交回模型。
            val content = retryOnFailure(
                attempts = OCR_MAX_ATTEMPTS,
                onRetry = { attempt, e ->
                    Logging.log(TAG, "performOcr: attempt $attempt failed, retrying: ${e.message}")
                },
                retryIf = { it !is OcrTimeoutException },
            ) {
                val result = withTimeoutOrNull(OCR_ATTEMPT_TIMEOUT) {
                    provider.generateText(
                        providerSetting = providerSetting,
                        messages = listOf(
                            UIMessage.system(settings.ocrPrompt),
                            UIMessage(
                                role = MessageRole.USER,
                                parts = listOf(UIMessagePart.Image(part.url))
                            )
                        ),
                        params = TextGenerationParams(
                            model = model,
                            customHeaders = model.customHeaders,
                            customBody = model.customBodies,
                        ),
                    )
                } ?: throw OcrTimeoutException(
                    "OCR attempt timed out after ${OCR_ATTEMPT_TIMEOUT.inWholeSeconds}s"
                )
                // 空响应视为失败（交由 retryOnFailure 重试），与上游 checkNotNull 语义一致
                result.message.toText().ifBlank { error("OCR failed: empty response") }
            }
            Log.i(TAG, "performOcr: $content")
            val ocrResult = """
                <image_file_ocr>
                   $content
                </image_file_ocr>
            """.trimIndent()

            // Cache the result
            cache.put(part.url, ocrResult)
            ocrResult
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            "[ERROR, OCR failed: $e]"
        }
    }
}
