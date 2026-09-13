package me.rerere.rikkahub.ui.components.richtext

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

/**
 * [ImageAspectRatioCache] 的语义测试。
 *
 * 背景见缓存类注释：markdown 行内图在 LazyColumn 里被回收后重新组合，必须能拿回
 * 真实宽高比，否则会退回占位图的 1024×1024 正方形、滚动时高度跳动。
 */
class ImageAspectRatioCacheTest {

    @Before
    fun setUp() = ImageAspectRatioCache.clear()

    @After
    fun tearDown() = ImageAspectRatioCache.clear()

    @Test
    fun `unknown model returns null`() {
        assertNull(ImageAspectRatioCache.get("file:///upload/never-loaded.png"))
    }

    @Test
    fun `blank and null models are not cached`() {
        ImageAspectRatioCache.put(null, 100, 200)
        ImageAspectRatioCache.put("", 100, 200)

        assertNull(ImageAspectRatioCache.get(null))
        assertNull(ImageAspectRatioCache.get(""))
    }

    @Test
    fun `ratio is height over width`() {
        ImageAspectRatioCache.put("a", width = 200, height = 100)

        // 宽 200 高 100 → 高/宽 = 0.5
        assertEquals(0.5f, ImageAspectRatioCache.get("a")!!, 1e-6f)
    }

    @Test
    fun `non positive dimensions are ignored`() {
        ImageAspectRatioCache.put("zero-width", 0, 100)
        ImageAspectRatioCache.put("zero-height", 100, 0)
        ImageAspectRatioCache.put("negative", -10, 100)

        assertNull(ImageAspectRatioCache.get("zero-width"))
        assertNull(ImageAspectRatioCache.get("zero-height"))
        assertNull(ImageAspectRatioCache.get("negative"))
    }

    @Test
    fun `re-caching the same model overwrites the ratio`() {
        ImageAspectRatioCache.put("a", 100, 100)
        ImageAspectRatioCache.put("a", 100, 400)

        assertEquals(4f, ImageAspectRatioCache.get("a")!!, 1e-6f)
    }

    @Test
    fun `cache is bounded and evicts the least recently used entry`() {
        val overflow = 20
        repeat(256 + overflow) { index ->
            ImageAspectRatioCache.put("model-$index", 100, 200)
        }

        // 最早的 overflow 条应已被淘汰
        assertNull(ImageAspectRatioCache.get("model-0"))
        assertNull(ImageAspectRatioCache.get("model-${overflow - 1}"))
        // 最后写入的那条还在
        assertEquals(2f, ImageAspectRatioCache.get("model-${256 + overflow - 1}")!!, 1e-6f)
    }

    @Test
    fun `reading an entry protects it from eviction`() {
        ImageAspectRatioCache.put("keep-me", 100, 200)

        // 填满并溢出，期间反复读 keep-me 让它保持"最近使用"
        repeat(300) { index ->
            ImageAspectRatioCache.put("filler-$index", 100, 200)
            ImageAspectRatioCache.get("keep-me")
        }

        assertEquals(2f, ImageAspectRatioCache.get("keep-me")!!, 1e-6f)
    }

    @Test
    fun `square placeholder sized image keeps ratio 1`() {
        ImageAspectRatioCache.put("a", 1024, 1024)

        assertEquals(1f, ImageAspectRatioCache.get("a")!!, 1e-6f)
    }
}
