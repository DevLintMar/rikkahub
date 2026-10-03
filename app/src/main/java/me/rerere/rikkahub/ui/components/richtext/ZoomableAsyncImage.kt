package me.rerere.rikkahub.ui.components.richtext

import android.os.SystemClock
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
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import coil3.request.allowHardware
import coil3.request.crossfade
import coil3.request.placeholder
import me.rerere.common.android.Logging
import me.rerere.rikkahub.BuildConfig
import me.rerere.rikkahub.R
import me.rerere.rikkahub.ui.components.ui.ImagePreviewDialog
import me.rerere.rikkahub.ui.components.ui.LocalExportContext
import me.rerere.rikkahub.ui.modifier.shimmer
import me.rerere.rikkahub.ui.theme.LocalDarkMode

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
    var cachedAspectRatio by remember(model) {
        mutableStateOf<Float?>(ImageAspectRatioCache.get(model))
    }
    var hasError by remember(model) { mutableStateOf(false) }
    var retryCount by remember(model) { mutableIntStateOf(0) }
    // remember：item 存活期间父级重组不再重建 ImageRequest → Coil 状态机不重启（内存缓存命中直接复用绘制结果）
    val coilModel = remember(model, export, darkMode, cachedAspectRatio) {
        val displayMetrics = context.resources.displayMetrics
        val screenWidth = displayMetrics.widthPixels.coerceAtLeast(1080)
        // 动态计算解码尺寸上限：避免长图（如 1000x10000）按 1024x1024 Fit 裁剪导致宽度被压缩至 100px 造成严重模糊。
        // 长图以屏幕物理宽度为短边基准，高度允许延伸至 4096px 纹理上限；超大宽图则宽上限 2048px。
        val (targetWidth, targetHeight) = if (cachedAspectRatio != null && cachedAspectRatio!! > 0f) {
            val ratio = cachedAspectRatio!!
            if (ratio < 1f) {
                // 竖长图：保证宽度至少达到屏幕物理分辨率，高度受限在 4096px 纹理上限以内
                val h = (screenWidth / ratio).toInt().coerceIn(screenWidth, 4096)
                screenWidth to h
            } else {
                // 横长图 / 方图：高度保证 1080px，宽度自适应至至多 2048px
                val w = (1080 * ratio).toInt().coerceIn(1080, 2048)
                w to 1080
            }
        } else {
            // 未知比例时（首次加载）：宽度至少 1440px，高度上限 4096px，确保长图首次解码不模糊
            screenWidth.coerceAtLeast(1440) to 4096
        }

        ImageRequest.Builder(context)
            .data(model)
            .placeholder(placeholder)
            .crossfade(false)
            .allowHardware(!export)
            .size(targetWidth, targetHeight)
            .build()
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
    var loadStartMs by remember(model) { mutableLongStateOf(0L) }
    var lastImgLogMs by remember(model) { mutableLongStateOf(0L) }
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
                    ImageAspectRatioCache.put(model, image.width, image.height)
                    cachedAspectRatio = ImageAspectRatioCache.get(model)
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
            ImageAspectRatioCache.remove(model)
        },
    )
    }
    if (showImageViewer) {
        ImagePreviewDialog(images = listOf(model ?: "")) {
            showImageViewer = false
        }
    }
}
