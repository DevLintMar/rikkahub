package me.rerere.rikkahub.ui.components.webview

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「这个子资源是不是图片」是「要不要用 app 的 HTTP 客户端去取」的分流条件：
 * 判宽了会把 JS/CSS/HTML 也拖进 app 客户端，判窄了外网图继续裂。
 */
class WebViewRemoteImagesTest {

    @Test
    fun `WebView 给 img 发的 Accept 头即判为图片`() {
        assertTrue(
            looksLikeRemoteImage(
                url = "https://cdn.example.com/img?id=7",
                acceptHeader = "image/avif,image/webp,image/apng,image/svg+xml,image/*,*/*;q=0.8",
            )
        )
    }

    @Test
    fun `没有 Accept 时按扩展名判断`() {
        assertTrue(looksLikeRemoteImage("https://a.com/x.JPG", null))
        assertTrue(looksLikeRemoteImage("https://a.com/x.webp?v=2", null))
        assertTrue(looksLikeRemoteImage("https://a.com/x.svg#frag", null))
    }

    @Test
    fun `非图片子资源不接管`() {
        assertFalse(looksLikeRemoteImage("https://a.com/app.js", "text/javascript"))
        assertFalse(looksLikeRemoteImage("https://a.com/page.html", null))
        assertFalse(looksLikeRemoteImage("https://a.com/no-extension", null))
        assertFalse(looksLikeRemoteImage("https://esm.sh/markdown-it@14.0.0", null))
    }
}
