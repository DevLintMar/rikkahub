package me.rerere.rikkahub.ui.components.richtext

import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.takeOrElse
import io.ratex.DisplayList
import io.ratex.RaTeXEngine
import io.ratex.compose.RaTeX
import io.ratex.measure
import java.util.LinkedHashMap

/**
 * Compose 单边最大允许像素数 (0xFFFFFF)。
 * 超过此值的尺寸会导致 IllegalStateException: "Size out of range".
 */
private const val MAX_COMPOSE_SIZE_PX = 16_777_215f

/**
 * 尺寸信息，替代原先 JLatexMath 的 Rect。
 * [depth] 用于 InlineTextContent 基线对齐。
 */
data class LatexMetrics(
    val widthPx: Float,
    val heightPx: Float,
    val depthPx: Float,
)

private data class DisplayListCacheKey(
    val latex: String,
    val displayMode: Boolean,
    val colorArgb: Int,
)

/**
 * 进程级 DisplayList LRU 缓存池（256 项上限，按最近最少使用淘汰）。
 *
 * 彻底解决 LazyColumn 上下滚动时块级公式和行内公式脱离 Compose 节点生命周期后被重复解析的问题。
 * 命中缓存直接复用已生成的 DisplayList，零 JNI 调用、零主线程卡顿。
 */
private val displayListLruCache = object : LinkedHashMap<DisplayListCacheKey, DisplayList>(256, 0.75f, true) {
    override fun removeEldestEntry(eldest: MutableMap.MutableEntry<DisplayListCacheKey, DisplayList>): Boolean = size > 256
}

/**
 * 线程安全地从缓存池获取或创建 DisplayList。
 */
fun getOrCreateDisplayList(
    latex: String,
    displayMode: Boolean = false,
    color: Color = Color.Unspecified,
): DisplayList? {
    val colorArgb = if (color == Color.Unspecified) 0 else color.toArgb()
    val key = DisplayListCacheKey(latex, displayMode, colorArgb)
    return synchronized(displayListLruCache) {
        displayListLruCache[key]
    } ?: runCatching {
        RaTeXEngine.parseBlocking(latex, displayMode = displayMode, color = color)
    }.getOrNull()?.also { dl ->
        synchronized(displayListLruCache) {
            displayListLruCache[key] = dl
        }
    }
}

/**
 * 同步计算公式尺寸，用于 [InlineTextContent] 的 Placeholder 预占位。
 *
 * 优先复用进程级 DisplayList 缓存；
 * 如果 RaTeX 返回的尺寸无效（NaN、Infinity 或超出 Compose 限制），
 * 返回 null。调用者应使用 0 作为安全降级 Placeholder 尺寸。
 *
 * 注：此函数使用 Color.Unspecified，因为 color 不影响 DisplayList 尺寸
 * （Rust 端 LayoutOptions.with_color 只设置默认绘制颜色，不改变布局算法）。
 * 主要用于 splitLatex 候选段宽度测量和独立 LatexText 备用路径。
 */
fun assumeLatexSize(
    latex: String,
    fontSizePx: Float,
    displayMode: Boolean = false,
): LatexMetrics? {
    return runCatching {
        val dl = getOrCreateDisplayList(latex, displayMode = displayMode, color = Color.Unspecified) ?: return null
        val m = dl.measure(fontSizePx)
        if (m.widthPx.isFinite() && m.heightPx.isFinite() && m.depthPx.isFinite()
            && m.widthPx <= MAX_COMPOSE_SIZE_PX
            && m.heightPx <= MAX_COMPOSE_SIZE_PX
            && m.depthPx <= MAX_COMPOSE_SIZE_PX
        ) {
            LatexMetrics(m.widthPx, m.heightPx, m.depthPx)
        } else null
    }.getOrNull()
}

/**
 * 用 RaTeX 引擎渲染 LaTeX 公式的 Composable。
 *
 * [displayList] 为可选预解析结果；投喂时直接使用无需解析，
 * 用于 [InlineTextContent] 场景下与 Placeholder 测量共享同一 DisplayList。
 * 未投喂时优先从进程级 [getOrCreateDisplayList] LRU 缓存池复用，
 * 彻底消除块级公式在 LazyColumn 滚动回收滑回时的主线程 JNI 重复解析开销。
 */
@Composable
fun LatexText(
    latex: String,
    modifier: Modifier = Modifier,
    fontSize: TextUnit = TextUnit.Unspecified,
    color: Color = Color.Unspecified,
    style: TextStyle = LocalTextStyle.current,
    displayMode: Boolean = false,
    displayList: DisplayList? = null,
) {
    val resolvedColor = if (color == Color.Unspecified) style.color else color
    val resolvedFontSize = fontSize.takeOrElse { style.fontSize }

    val dl = if (displayList != null) {
        displayList
    } else {
        remember(latex, displayMode, resolvedColor) {
            getOrCreateDisplayList(latex, displayMode = displayMode, color = resolvedColor)
        }
    }

    if (dl != null) {
        RaTeX(
            displayList = dl,
            modifier = modifier,
            fontSize = resolvedFontSize,
        )
    } else {
        // 降级：显示原始 LaTeX 文本（异步解析未完成或解析失败时）
        Text(
            text = latex,
            style = style.merge(color = resolvedColor, fontSize = resolvedFontSize),
            modifier = modifier,
        )
    }
}
