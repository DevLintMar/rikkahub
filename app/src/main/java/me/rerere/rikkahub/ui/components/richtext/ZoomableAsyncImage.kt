package me.rerere.rikkahub.ui.components.richtext

import android.net.Uri
import android.os.SystemClock
import coil3.imageLoader
import java.io.File
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.ui.draw.clip
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Image02
import me.rerere.hugeicons.stroke.Refresh01
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.unit.dp
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.DefaultAlpha
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.core.net.toUri
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import coil3.request.allowHardware
import coil3.request.crossfade
import coil3.request.placeholder
import coil3.size.Dimension
import coil3.size.Precision
import kotlinx.coroutines.delay
import me.rerere.common.android.Logging
import me.rerere.rikkahub.BuildConfig
import me.rerere.rikkahub.R
import me.rerere.rikkahub.ui.components.ui.ImagePreviewDialog
import me.rerere.rikkahub.ui.components.ui.LocalExportContext
import me.rerere.rikkahub.ui.modifier.shimmer
import me.rerere.rikkahub.ui.theme.LocalDarkMode

private fun resolveActualImageSource(
    model: String?,
    workspaceResolver: ((String) -> File?)?,
): Any? {
    if (model.isNullOrBlank()) return null

    // 1. 宿主物理文件优先：去掉 file:// 后直接检查物理文件是否存在且非空
    val hostPath = if (model.startsWith("file://")) {
        Uri.parse(model).path ?: model.removePrefix("file://")
    } else if (model.startsWith("/")) {
        model
    } else null

    if (hostPath != null) {
        val file = File(hostPath)
        if (file.exists() && file.isFile && file.length() > 0) {
            return file
        }
    }

    // 2. 工作区沙箱路径解析器
    if (workspaceResolver != null) {
        var resolved = workspaceResolver(model)?.takeIf { it.isFile && it.length() > 0 }
        // 兼容 file://workspace/... 补齐三个斜杠
        if (resolved == null && model.startsWith("file://") && !model.startsWith("file:///")) {
            val fixed = "file:///" + model.removePrefix("file://")
            resolved = workspaceResolver(fixed)?.takeIf { it.isFile && it.length() > 0 }
        }
        // 兼容相对路径如 ./chart.png 或 chart.png
        if (resolved == null && !model.startsWith("/") && !model.contains("://")) {
            val fixed = "/workspace/${model.removePrefix("./")}"
            resolved = workspaceResolver(fixed)?.takeIf { it.isFile && it.length() > 0 }
        }
        if (resolved != null) {
            return resolved
        }
    }

    return model
}

@Composable
fun ZoomableAsyncImage(
    model: String?,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    alignment: Alignment = Alignment.Center,
    contentScale: ContentScale = ContentScale.Fit,
    alpha: Float = DefaultAlpha,
    /**
     * 用 [ImageAspectRatioCache] 里记下的真实宽高比锁定高度。
     *
     * 只对**尺寸交给内容决定**的调用点开启（markdown 行内图这类）。传了固定高宽的调用点
     * （缩略图 `.height(64.dp)`、`.height(72.dp)`）不要开：`aspectRatio` 会给子项固定尺寸，
     * 与调用方的固定高宽冲突。
     *
     * 开启后：首次加载仍是「占位图正方形 → 真实比例」一跳，之后任何一次重新组合
     * （列表回收后滚回来）都直接用缓存的真实比例，不再跳。
     */
    sizeFromCachedAspectRatio: Boolean = false,
) {
    var showImageViewer by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val darkMode = LocalDarkMode.current
    val placeholder = if (darkMode) R.drawable.placeholder_dark else R.drawable.placeholder
    val export = LocalExportContext.current
    val workspaceResolver = LocalWorkspaceFileProvider.current

    var hasError by remember(model) { mutableStateOf(false) }
    var retryCount by remember(model) { mutableIntStateOf(0) }

    // 动态解析实际加载数据源：物理文件返回 java.io.File，沙箱路径通过 resolver 还原，网络路径保持 String
    var resolvedSource by remember(model, retryCount) {
        val initial = resolveActualImageSource(model, workspaceResolver) ?: model
        mutableStateOf(initial)
    }

    // 时序自愈：针对 AI 正在执行写入、文件落盘存在时差的工作区图片，延迟短轮询探测
    LaunchedEffect(model, retryCount) {
        if (!model.isNullOrBlank() && workspaceResolver != null && resolvedSource == model) {
            for (d in listOf(300L, 800L, 1800L)) {
                delay(d)
                val found = resolveActualImageSource(model, workspaceResolver)
                if (found != null && found != resolvedSource) {
                    resolvedSource = found
                    hasError = false
                    break
                }
            }
        }
    }

    val modelKey = when (val s = resolvedSource) {
        is File -> s.absolutePath
        else -> s?.toString()
    }

    var cachedAspectRatio by remember(modelKey, model) {
        mutableStateOf<Float?>(ImageAspectRatioCache.get(modelKey) ?: ImageAspectRatioCache.get(model))
    }

    // remember：item 存活期间父级重组不再重建 ImageRequest；retryCount 改变时主动重建触发重新请求
    val coilModel = remember(resolvedSource, retryCount, export, darkMode, cachedAspectRatio) {
        val displayMetrics = context.resources.displayMetrics
        val screenWidth = displayMetrics.widthPixels.coerceAtLeast(1080)

        val builder = ImageRequest.Builder(context)
            .data(resolvedSource)
            .placeholder(placeholder)
            .crossfade(false)
            .precision(coil3.size.Precision.INEXACT)

        if (retryCount > 0) {
            // 用户点击重新加载时，禁用缓存读取，强制重新解码
            builder.memoryCachePolicy(coil3.request.CachePolicy.WRITE_ONLY)
            builder.diskCachePolicy(coil3.request.CachePolicy.WRITE_ONLY)
        }

        val ratio = cachedAspectRatio
        if (ratio != null && ratio > 0f) {
            if (ratio < 1f) {
                // 竖长图（高 > 宽）：彻底解除高度限制，仅以屏幕物理像素宽度为基准点对点采样解码！
                // 严禁传入 4096px 等硬高度上限，否则 Coil 在默认 FIT 模式下会反向等比压窄宽度至 200px 造成严重缩略与模糊！
                builder.size(coil3.size.Size(Dimension(screenWidth), Dimension.Undefined))
                // 超长图（高宽比超过 2:1）走软件解码，避免超出 GPU 纹理限制
                builder.allowHardware(!export && ratio >= 0.5f)
            } else {
                // 横长图 / 方图：高度保证 1080px，宽度自适应至至多 2048px
                val w = (1080 * ratio).toInt().coerceIn(1080, 2048)
                builder.size(w, 1080)
                builder.allowHardware(!export)
            }
        } else {
            // 未知比例时（首次加载）：宽度设定为屏幕物理宽度，高度保持 Undefined 无界，确保长图首次解码不模糊
            builder.size(coil3.size.Size(Dimension(screenWidth), Dimension.Undefined))
            builder.allowHardware(false)
        }

        builder.build()
    }
    // aspectRatio 放在链尾（最贴近 AsyncImage）：它会给子项 Constraints.fixed，
    // 从而让占位图的内在尺寸不再参与布局 —— 否则 1024×1024 的占位图会先撑成正方形。
    val sizedModifier = if (sizeFromCachedAspectRatio) {
        // 缓存的**必须**是「宽 / 高」，与 Modifier.aspectRatio 同向（见 ImageAspectRatioCache 注释）
        cachedAspectRatio?.let { modifier.aspectRatio(it) } ?: modifier
    } else {
        modifier
    }
    var loading by remember { mutableStateOf(false) }
    // debug 诊断：记录加载起点，onSuccess 时输出缓存来源与耗时（定位滚动卡顿是否图片解码）
    var loadStartMs by remember(resolvedSource) { mutableLongStateOf(0L) }
    var lastImgLogMs by remember(resolvedSource) { mutableLongStateOf(0L) }
    if (hasError) {
        // 优雅错误占位卡片：替代几千像素空白黑洞，提供微字提示与点击重试
        Surface(
            modifier = modifier
                .fillMaxWidth()
                .height(80.dp)
                .clip(RoundedCornerShape(8.dp))
                .clickable {
                    hasError = false
                    retryCount++
                    // 彻底清除 Coil 内存缓存
                    context.imageLoader.memoryCache?.clear()
                    // 重新探测工作区宿主物理文件，若已落盘立即自愈
                    val fresh = resolveActualImageSource(model, workspaceResolver)
                    if (fresh != null) {
                        resolvedSource = fresh
                    }
                },
            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
            tonalElevation = 1.dp
        ) {
            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Icon(
                    imageVector = HugeIcons.Image02,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                    modifier = Modifier.size(24.dp)
                )
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "图片加载失败",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = "点击重新加载",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.outline
                    )
                }
                Icon(
                    imageVector = HugeIcons.Refresh01,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(18.dp)
                )
            }
        }
    } else {
        AsyncImage(
            model = coilModel,
        contentDescription = contentDescription,
        modifier = sizedModifier
            .shimmer(isLoading = loading, animate = false)
            .clickable {
                showImageViewer = true
            },
        contentScale = contentScale,
        alpha = alpha,
        alignment = alignment,
        onLoading = {
            loading = true
            loadStartMs = SystemClock.elapsedRealtime()
        },
        onSuccess = { state ->
            loading = false
            hasError = false
            if (sizeFromCachedAspectRatio && cachedAspectRatio == null) {
                val image = state.result.image
                if (image.width > 0 && image.height > 0) {
                    ImageAspectRatioCache.put(modelKey, image.width, image.height)
                    ImageAspectRatioCache.put(model, image.width, image.height)
                    cachedAspectRatio = ImageAspectRatioCache.get(modelKey) ?: ImageAspectRatioCache.get(model)
                }
            }
            if (BuildConfig.DEBUG) {
                val now = SystemClock.elapsedRealtime()
                val dur = now - loadStartMs
                val src = when (state.result.dataSource) {
                    coil3.decode.DataSource.MEMORY_CACHE -> "mem"
                    coil3.decode.DataSource.MEMORY -> "raw"
                    coil3.decode.DataSource.DISK -> "disk"
                    coil3.decode.DataSource.NETWORK -> "net"
                }
                // 节流：真正解码/IO 或耗时超 30ms 才记，且 500ms 内至多一条
                if ((src != "mem" || dur > 30) && now - lastImgLogMs >= 500) {
                    lastImgLogMs = now
                    Logging.log("ChatImg", "src=$src dur=${dur}ms")
                }
            }
        },
        onError = {
            loading = false
            hasError = true
            // 发生错误（如 404/网络异常）时，立即从缓存逐出并重置本地比例，杜绝幽灵高度缓存撑开几千像素空白黑洞
            cachedAspectRatio = null
            ImageAspectRatioCache.remove(modelKey)
            ImageAspectRatioCache.remove(model)
        },
    )
    }
    if (showImageViewer) {
        val previewTarget = when (val s = resolvedSource) {
            is File -> s.toURI().toString()
            else -> s?.toString() ?: model ?: ""
        }
        ImagePreviewDialog(images = listOf(previewTarget)) {
            showImageViewer = false
        }
    }
}
