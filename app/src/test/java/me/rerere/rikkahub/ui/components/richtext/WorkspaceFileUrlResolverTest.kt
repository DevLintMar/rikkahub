package me.rerere.rikkahub.ui.components.richtext

import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import java.io.File
import java.nio.file.Files

class WorkspaceFileUrlResolverTest {

    private lateinit var filesDir: File

    @BeforeTest
    fun setUp() {
        filesDir = Files.createTempDirectory("workspace-url-resolver-test").toFile()
    }

    @AfterTest
    fun tearDown() {
        filesDir.deleteRecursively()
    }

    private fun canonical(path: String): File = File(filesDir, path).canonicalFile

    @Test
    fun `file workspace 前缀解析为 FILES 区`() {
        val result = WorkspaceFileUrlResolver.resolveFile(filesDir, "w1", "file:///workspace/a.png")
        assertEquals(canonical("workspaces/w1/files/a.png"), result)
    }

    @Test
    fun `file upload 前缀解析为 upload 区且无需 workspaceId`() {
        val result = WorkspaceFileUrlResolver.resolveFile(filesDir, null, "file:///upload/photo.png")
        assertEquals(canonical("upload/photo.png"), result)
    }

    @Test
    fun `裸路径不带 file 前缀同样解析`() {
        assertEquals(
            canonical("workspaces/w1/files/notes.md"),
            WorkspaceFileUrlResolver.resolveFile(filesDir, "w1", "/workspace/notes.md"),
        )
        assertEquals(
            canonical("upload/photo.png"),
            WorkspaceFileUrlResolver.resolveFile(filesDir, "w1", "/upload/photo.png"),
        )
    }

    @Test
    fun `无 workspaceId 时 workspace 前缀返回 null 而 upload 仍解析`() {
        assertNull(WorkspaceFileUrlResolver.resolveFile(filesDir, null, "file:///workspace/a.png"))
        assertEquals(
            canonical("upload/photo.png"),
            WorkspaceFileUrlResolver.resolveFile(filesDir, null, "file:///upload/photo.png"),
        )
    }

    @Test
    fun `路径穿越被拦截`() {
        assertNull(WorkspaceFileUrlResolver.resolveFile(filesDir, "w1", "file:///workspace/../../secret"))
        assertNull(WorkspaceFileUrlResolver.resolveFile(filesDir, "w1", "file:///upload/../../../etc/passwd"))
    }

    @Test
    fun `非工作区协议原样返回 null`() {
        assertNull(WorkspaceFileUrlResolver.resolveFile(filesDir, "w1", "http://example.com/a.png"))
        assertNull(WorkspaceFileUrlResolver.resolveFile(filesDir, "w1", "https://example.com/a.png"))
        assertNull(WorkspaceFileUrlResolver.resolveFile(filesDir, "w1", "data:image/png;base64,xxx"))
        assertNull(WorkspaceFileUrlResolver.resolveFile(filesDir, "w1", "content://media/123"))
        // 宿主绝对路径（既有 file:// 机制）不属于工作区逻辑路径
        assertNull(
            WorkspaceFileUrlResolver.resolveFile(
                filesDir, "w1", "file:///data/user/0/me.rerere.rikkahub/files/upload/x.png",
            ),
        )
    }

    @Test
    fun `Rootfs 内部路径不支持`() {
        assertNull(WorkspaceFileUrlResolver.resolveFile(filesDir, "w1", "file:///etc/hostname"))
    }

    @Test
    fun `URL 编码路径解码后解析`() {
        val result = WorkspaceFileUrlResolver.resolveFile(filesDir, "w1", "file:///workspace/my%20file.png")
        assertEquals(canonical("workspaces/w1/files/my file.png"), result)
    }

    @Test
    fun `加号不被当作空格解码`() {
        val result = WorkspaceFileUrlResolver.resolveFile(filesDir, "w1", "file:///workspace/a+b.png")
        assertEquals(canonical("workspaces/w1/files/a+b.png"), result)
    }

    @Test
    fun `workspace 根目录本身解析为 FILES 区根`() {
        assertEquals(
            canonical("workspaces/w1/files"),
            WorkspaceFileUrlResolver.resolveFile(filesDir, "w1", "file:///workspace"),
        )
    }

    @Test
    fun `空链接返回 null`() {
        assertNull(WorkspaceFileUrlResolver.resolveFile(filesDir, "w1", ""))
        assertNull(WorkspaceFileUrlResolver.resolveFile(filesDir, "w1", "   "))
    }
}
