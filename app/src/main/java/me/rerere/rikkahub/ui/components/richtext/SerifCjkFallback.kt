package me.rerere.rikkahub.ui.components.richtext

import android.graphics.Typeface
import io.ratex.RaTeXFontLoader
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * 给 RaTeX FontCache 中每个 KaTeX 字体附加系统衬线体回退。
 *
 * 原理：RaTeX 将 KaTeX 字体解压到系统临时目录 → 找到临时 .ttf 文件 →
 * Typeface.Builder(file).setFallback("serif").build() 放入 FontCache，
 * 使 KaTeX 缺字（中文）时自动走系统衬线体。
 */
suspend fun registerSerifCjkFallback() {
    // 1. 触发 RaTeX 加载字体（解压到临时目录）
    RaTeXFontLoader.ensureLoaded()

    // 2. 找到临时目录中 KaTeX 字体文件
    val tmpDir = File(System.getProperty("java.io.tmpdir") ?: "/data/local/tmp")
    val katexFiles = tmpDir.listFiles { f ->
        f.isFile && f.name.startsWith("ratex-") && f.name.endsWith(".ttf")
    }?.toList() ?: return

    // 3. 反射 FontCache → 按文件名匹配字体 → 重建带 serif fallback
    try {
        val fontCacheClass = Class.forName("io.ratex.FontCache")
        val instanceField = fontCacheClass.getDeclaredField("INSTANCE")
        instanceField.isAccessible = true
        val fontCache = instanceField.get(null)

        val cacheField = fontCacheClass.getDeclaredField("cache")
        cacheField.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        val cache = cacheField.get(fontCache) as ConcurrentHashMap<String, Typeface>

        for ((fontId, _) in cache) {
            if (fontId.startsWith("CJK-") || fontId.startsWith("Emoji-")) continue

            val matched = katexFiles.firstOrNull { it.name.contains(fontId, ignoreCase = true) } ?: continue
            val merged = Typeface.Builder(matched.absolutePath)
                .setFallback("serif")
                .build()
            cache[fontId] = merged
        }
    } catch (_: Exception) {
        // 反射失败不影响已有渲染
    }
}
