package me.rerere.rikkahub.ui.components.richtext

/**
 * 图片宽高比缓存（key = 传给 Coil 的 model 字符串）。
 *
 * **要解决的问题**：聊天气泡里的 markdown 行内图由 [ZoomableAsyncImage] 渲染，
 * 加载前由占位图（`placeholder.png`，**1024×1024 正方形**）决定内在尺寸，
 * 加载完成后才换成图片真实的宽高比。消息列表是 `LazyColumn`，滚出屏幕的消息项会被
 * 回收，滚回来重新组合 → 又退回占位图的正方形 → **高度再跳一次**。
 *
 * 这里把**首次成功加载**得到的宽高比记下来，此后同一进程内任何一次重新组合都能立刻
 * 按正确比例占位，滚动时不再抖动。首次加载那一次仍会有占位图→真实高度的跳变，
 * 这是无法避免的（比例本来就还没测出来）。
 *
 * 同一个 URL 的图片内在比例恒定，所以只用 model 做 key。
 */
internal object ImageAspectRatioCache {

    private const val MAX_ENTRIES = 256

    // accessOrder = true：命中即刷新为最近使用；迭代顺序首位就是最久未用的
    private val entries = LinkedHashMap<String, Float>(16, 0.75f, true)

    /** @return 高 / 宽；从未成功加载过时返回 null */
    @Synchronized
    fun get(model: String?): Float? {
        if (model.isNullOrEmpty()) return null
        return entries[model]
    }

    /**
     * 记录一次成功加载。
     *
     * @param width  解码后的实际宽度（Coil 保比例缩放后的值）
     * @param height 解码后的实际高度
     */
    @Synchronized
    fun put(model: String?, width: Int, height: Int) {
        if (model.isNullOrEmpty() || width <= 0 || height <= 0) return
        val ratio = height.toFloat() / width.toFloat()
        // 过滤 NaN / Inf / 0，避免把异常比例写进去撑坏布局
        if (!ratio.isFinite() || ratio <= 0f) return
        entries[model] = ratio

        // 显式淘汰最久未用的：不用 removeEldestEntry 覆写，少一层平台类型推断的坑
        while (entries.size > MAX_ENTRIES) {
            val eldest = entries.keys.firstOrNull() ?: break
            entries.remove(eldest)
        }
    }

    /** 仅供测试与诊断使用。 */
    @Synchronized
    fun clear() {
        entries.clear()
    }
}
