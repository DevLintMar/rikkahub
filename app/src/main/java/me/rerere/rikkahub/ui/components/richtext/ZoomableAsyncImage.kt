package me.rerere.rikkahub.ui.components.richtext

import android.os.SystemClock
import androidx.compose.foundation.clickable
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
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
) {
    var showImageViewer by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val darkMode = LocalDarkMode.current
    val placeholder = if (darkMode) R.drawable.placeholder_dark else R.drawable.placeholder
    val export = LocalExportContext.current
    // remember：item 存活期间父级重组不再重建 ImageRequest → Coil 状态机不重启（内存缓存命中直接复用绘制结果）
    val coilModel = remember(model, export, darkMode) {
        ImageRequest.Builder(context)
            .data(model)
            .placeholder(placeholder)
            .crossfade(false)
            .allowHardware(!export)
            .build()
    }
    var loading by remember { mutableStateOf(false) }
    // debug 诊断：记录加载起点，onSuccess 时输出缓存来源与耗时（定位滚动卡顿是否图片解码）
    var loadStartMs by remember(model) { mutableLongStateOf(0L) }
    var lastImgLogMs by remember(model) { mutableLongStateOf(0L) }
    AsyncImage(
        model = coilModel,
        contentDescription = contentDescription,
        modifier = modifier
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
            if (BuildConfig.DEBUG) {
                val now = SystemClock.elapsedRealtime()
                val dur = now - loadStartMs
                val src = when {
                    state.result.memoryCacheHit -> "mem"
                    state.result.diskCacheHit -> "disk"
                    else -> "load"
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
        },
    )
    if (showImageViewer) {
        ImagePreviewDialog(images = listOf(model ?: "")) {
            showImageViewer = false
        }
    }
}
