package me.rerere.rikkahub.data.files

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files

class WorkspaceFileUrlResolverTest {

    private lateinit var filesDir: File

    @Before
    fun setUp() {
        filesDir = Files.createTempDirectory("workspace-url-resolver-test").toFile()
    }

    @After
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
    fun `Rootfs 其它绝对路径落到 linux 区`() {
        assertEquals(
            canonical("workspaces/w1/linux/tmp/chart.png"),
            WorkspaceFileUrlResolver.resolveFile(filesDir, "w1", "file:///tmp/chart.png"),
        )
        assertEquals(
            canonical("workspaces/w1/linux/etc/hostname"),
            WorkspaceFileUrlResolver.resolveFile(filesDir, "w1", "/etc/hostname"),
        )
        assertEquals(
            canonical("workspaces/w1/linux/root/out.png"),
            WorkspaceFileUrlResolver.resolveFile(filesDir, "w1", "file:///root/out.png"),
        )
    }

    @Test
    fun `Rootfs 其它绝对路径需要 workspaceId`() {
        assertNull(WorkspaceFileUrlResolver.resolveFile(filesDir, null, "file:///tmp/chart.png"))
        assertNull(WorkspaceFileUrlResolver.resolveFile(filesDir, "  ", "file:///tmp/chart.png"))
    }

    @Test
    fun `bind mount 路径解析为宿主目录且无需 workspaceId`() {
        assertEquals(
            canonical("skills/pptx/SKILL.md"),
            WorkspaceFileUrlResolver.resolveFile(filesDir, null, "file:///skills/pptx/SKILL.md"),
        )
        assertEquals(
            canonical("tool_outputs/out.png"),
            WorkspaceFileUrlResolver.resolveFile(filesDir, null, "/tool_outputs/out.png"),
        )
    }

    @Test
    fun `Rootfs 根目录解析为 linux 区根`() {
        assertEquals(
            canonical("workspaces/w1/linux"),
            WorkspaceFileUrlResolver.resolveFile(filesDir, "w1", "file:///"),
        )
    }

    @Test
    fun `内核伪文件系统返回 null`() {
        assertNull(WorkspaceFileUrlResolver.resolveFile(filesDir, "w1", "file:///proc/cpuinfo"))
        assertNull(WorkspaceFileUrlResolver.resolveFile(filesDir, "w1", "file:///dev/null"))
        assertNull(WorkspaceFileUrlResolver.resolveFile(filesDir, "w1", "file:///sys/class/net"))
    }

    @Test
    fun `路径穿越被拦截`() {
        assertNull(WorkspaceFileUrlResolver.resolveFile(filesDir, "w1", "file:///workspace/../../secret"))
        assertNull(WorkspaceFileUrlResolver.resolveFile(filesDir, "w1", "file:///upload/../../../etc/passwd"))
        assertNull(WorkspaceFileUrlResolver.resolveFile(filesDir, "w1", "file:///tmp/../workspace/../../x"))
    }

    @Test
    fun `非 Rootfs 协议返回 null`() {
        assertNull(WorkspaceFileUrlResolver.resolveFile(filesDir, "w1", "http://example.com/a.png"))
        assertNull(WorkspaceFileUrlResolver.resolveFile(filesDir, "w1", "https://example.com/a.png"))
        assertNull(WorkspaceFileUrlResolver.resolveFile(filesDir, "w1", "data:image/png;base64,xxx"))
        assertNull(WorkspaceFileUrlResolver.resolveFile(filesDir, "w1", "content://media/123"))
        assertNull(WorkspaceFileUrlResolver.resolveFile(filesDir, "w1", "relative/path.png"))
    }

    @Test
    fun `真机私有目录路径不解析 即使它就是应用自己的目录`() {
        // file:// 的根是工作区沙箱根，不是设备根 → 应用私有目录的宿主路径一律拒绝
        assertNull(
            WorkspaceFileUrlResolver.resolveFile(
                filesDir, "w1", "file://" + File(filesDir, "upload/x.png").path.replace('\\', '/'),
            ),
        )
        assertNull(
            WorkspaceFileUrlResolver.resolveFile(
                filesDir, "w1", "file://" + filesDir.canonicalPath.replace('\\', '/') + "/upload/x.png",
            ),
        )
    }

    @Test
    fun `设备上的绝对路径不解析`() {
        // Windows 上 `/data` 是盘符相对路径，这条真机路径断言只对 POSIX 绝对路径有意义
        assumeTrue(File("/data").path.startsWith("/"))
        assertNull(
            WorkspaceFileUrlResolver.resolveFile(
                filesDir, "w1", "file:///data/user/0/me.rerere.rikkahub/files/upload/x.png",
            ),
        )
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
