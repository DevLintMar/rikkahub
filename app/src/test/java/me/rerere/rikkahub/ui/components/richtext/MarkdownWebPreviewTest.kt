package me.rerere.rikkahub.ui.components.richtext

import me.rerere.rikkahub.ui.components.webview.buildLocalFileUrl
import me.rerere.rikkahub.ui.components.webview.decodeSandboxPath
import me.rerere.rikkahub.ui.components.webview.encodeSandboxPath
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * 网页视图预览的图片改写。
 *
 * 预览页 origin 是 `https://rikkahub.local`，从它引用 `file://` 子资源必被 WebView 拦掉，
 * 所以本地图片必须改写成 [buildLocalFileUrl] 的 URL，由 `WebViewLocalAssets` 拦截读文件。
 * 这里只测改写规则本身（纯 JVM），实际读文件那一步在设备上验。
 */
class MarkdownWebPreviewTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private fun filesDir(): File = tempFolder.root

    private fun uploadFile(name: String): File =
        File(filesDir(), "upload").apply { mkdirs() }.resolve(name).apply { createNewFile() }

    private fun workspaceFile(id: String, relative: String): File =
        File(filesDir(), "workspaces/$id/files").apply { mkdirs() }
            .resolve(relative).apply {
                parentFile?.mkdirs()
                createNewFile()
            }

    // ---- 编码往返 ----

    @Test
    fun `沙箱路径编码可逆且不含 URL 保留字符`() {
        val path = "/workspace/我的 图/diagram (1).png"
        val encoded = encodeSandboxPath(path)

        assertEquals(path, decodeSandboxPath(encoded))
        assertTrue("编码后不应出现 / 或空格：$encoded", encoded.none { it == '/' || it == ' ' })
    }

    @Test
    fun `非法 hex 输入解码返回 null`() {
        assertNull(decodeSandboxPath("abc"))      // 奇数长度
        assertNull(decodeSandboxPath(""))         // 空
        assertNull(decodeSandboxPath("zz"))       // 非 hex
    }

    @Test
    fun `无工作区时 workspaceId 段写作短横线`() {
        val url = buildLocalFileUrl(null, "/upload/a.png")

        assertEquals("https://rikkahub.local/local/-/" + encodeSandboxPath("/upload/a.png"), url)
    }

    // ---- markdown 改写 ----

    @Test
    fun `upload 里的 file 图片链接被改写为预览 URL`() {
        uploadFile("abc.png")

        val result = buildPreviewMarkdown(
            filesDir = filesDir(),
            markdown = "看图：![图](file:///upload/abc.png)",
        )

        assertTrue(result.contains("![图](${buildLocalFileUrl(null, "/upload/abc.png")})"))
        assertTrue("原文的 file:// 不该残留", !result.contains("file:///upload/abc.png"))
    }

    @Test
    fun `工作区图片链接按 workspaceId 解析`() {
        workspaceFile("ws1", "notes/x.png")

        val result = buildPreviewMarkdown(
            filesDir = filesDir(),
            markdown = "![n](file:///workspace/notes/x.png)",
            workspaceId = "ws1",
        )

        assertTrue(result.contains(buildLocalFileUrl("ws1", "/workspace/notes/x.png")))
    }

    @Test
    fun `解析不到文件的图片链接原样保留`() {
        val markdown = "![missing](file:///upload/never.png)"

        assertEquals(
            markdown,
            buildPreviewMarkdown(filesDir = filesDir(), markdown = markdown),
        )
    }

    @Test
    fun `http 图片链接不受影响`() {
        val markdown = "![remote](https://example.com/a.png)"

        assertEquals(
            markdown,
            buildPreviewMarkdown(filesDir = filesDir(), markdown = markdown),
        )
    }

    @Test
    fun `HTML img 标签里的 file 也被改写`() {
        uploadFile("tag.png")

        val result = buildPreviewMarkdown(
            filesDir = filesDir(),
            markdown = """<img src="file:///upload/tag.png" alt="t">""",
        )

        val served = buildLocalFileUrl(null, "/upload/tag.png")
        assertTrue(result.contains("src=\"$served\""))
    }

    @Test
    fun `普通链接不改写（沙箱文件当网页点开没有意义）`() {
        uploadFile("doc.png")
        val markdown = "[点我](file:///upload/doc.png)"

        assertEquals(
            markdown,
            buildPreviewMarkdown(filesDir = filesDir(), markdown = markdown),
        )
    }

    // ---- 消息附件 ----

    @Test
    fun `附件图片追加到末尾`() {
        val file = uploadFile("attached.jpg")
        // 附件的 url 是宿主绝对路径。Windows 上绝对路径不是 '/' 开头，本仓 Android 场景
        // 与 CI（Linux）都是；真机上这里是 /data/user/0/<pkg>/files/upload/xxx.jpg
        val hostUrl = "file://" + file.absolutePath.replace('\\', '/')
        assumeTrue(
            "宿主绝对路径需为 '/' 开头（Android / Linux）；当前平台为 ${File.separator}",
            hostUrl.removePrefix("file://").startsWith("/"),
        )

        val result = buildPreviewMarkdown(
            filesDir = filesDir(),
            markdown = "正文",
            attachmentUrls = listOf(hostUrl),
        )

        assertTrue(result.startsWith("正文"))
        assertTrue(result.contains("![](${buildLocalFileUrl(null, "/upload/attached.jpg")})"))
    }

    @Test
    fun `没有附件时不会多出空行`() {
        assertEquals(
            "只有正文",
            buildPreviewMarkdown(filesDir = filesDir(), markdown = "只有正文", attachmentUrls = emptyList()),
        )
    }
}
