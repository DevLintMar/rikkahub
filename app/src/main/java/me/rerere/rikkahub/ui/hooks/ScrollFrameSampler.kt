package me.rerere.rikkahub.ui.hooks

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.withFrameNanos
import me.rerere.common.android.Logging
import me.rerere.rikkahub.BuildConfig

/**
 * debug 专用滚动帧间隔采样：滚动期间统计帧间隔分布（<16ms 正常 / 16-33 慢 / 33-66 卡 / >66 严重），
 * 每 1s 输出一行到日志页，用于定位滚动卡顿是主线程组合/绘制超帧，还是图片解码等外部延迟。
 * release 构建零开销（直接跳过）。
 */
@Composable
fun ScrollFrameSampler(active: Boolean, tag: String = "ChatScroll") {
    if (!BuildConfig.DEBUG) return
    LaunchedEffect(active, tag) {
        if (!active) return@LaunchedEffect
        var prev = 0L
        var frames = 0
        var good = 0
        var slow = 0
        var jank = 0
        var severe = 0
        var lastLog = 0L
        while (true) {
            val nano = withFrameNanos { it }
            if (prev != 0L) {
                val dtMs = (nano - prev) / 1_000_000
                when {
                    dtMs < 16 -> good++
                    dtMs < 33 -> slow++
                    dtMs < 66 -> jank++
                    else -> severe++
                }
                frames++
            }
            prev = nano
            if (nano - lastLog >= 1_000_000_000L && frames > 0) {
                Logging.log(
                    tag,
                    "frames=$frames good=$good slow16-33=$slow jank33-66=$jank severe>66=$severe"
                )
                frames = 0; good = 0; slow = 0; jank = 0; severe = 0; lastLog = nano
            }
        }
    }
}
