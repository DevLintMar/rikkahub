package me.rerere.rikkahub.data.sync

import me.rerere.rikkahub.data.db.entity.WorkspaceEntity
import me.rerere.workspace.WorkspaceManager
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.nio.file.attribute.PosixFileAttributeView
import java.util.zip.ZipFile

/**
 * 工作区备份 zip 的往返。
 *
 * **zip 不携带 Unix 权限位**（tar 才带），所以权限位必须另行记录：
 * 2026-09-19 用户报「导出工作区再导入之后报错、无法使用」，proot 的报错是
 * `proot error: '/usr/bin/env' is not executable` —— 导入出来的 rootfs 每个文件都是
 * 新建的普通文件，没有可执行位，沙箱根本起不来。这里把两条往返都钉住。
 *
 * POSIX 权限位与符号链接只有 Linux/macOS 才有意义（CI 跑在 ubuntu-latest）：
 * 不支持时跳过，避免在 Windows 上假绿。
 */
class WorkspaceBackupTest {

    private lateinit var baseDir: File

    @Before
    fun setUp() {
        baseDir = Files.createTempDirectory("workspace-backup-test").toFile()
    }

    @After
    fun tearDown() {
        baseDir.deleteRecursively()
    }

    private fun supportsPosix(): Boolean =
        runCatching {
            Files.getFileStore(baseDir.toPath())
                .supportsFileAttributeView(PosixFileAttributeView::class.java)
        }.getOrDefault(false)

    private fun newManager(): WorkspaceManager = WorkspaceManager(baseDir)

    private fun newEntity(root: String) = WorkspaceEntity(
        id = root,
        name = root,
        root = root,
        createdAt = 0L,
        updatedAt = 0L,
    )

    private fun roundTrip(manager: WorkspaceManager, entity: WorkspaceEntity): File {
        val zip = File(baseDir, "${entity.root}.zip")
        WorkspaceBackup.export(manager, entity, zip)
        val restored = File(baseDir, "${entity.root}-restored").apply { mkdirs() }
        ZipFile(zip).use { WorkspaceBackup.extractTo(it, restored) }
        return restored
    }

    @Test
    fun `导出导入后 rootfs 文件仍带可执行位`() {
        assumeTrue("需要 POSIX 权限位（CI 是 ubuntu-latest）", supportsPosix())

        val manager = newManager()
        val entity = newEntity("w-exec")
        val env = File(manager.linuxDir(entity.root), "usr/bin/env").apply {
            parentFile?.mkdirs()
            writeText("#!/bin/sh\n")
        }
        env.setExecutable(true, false)
        assertTrue("前置条件：导出前 env 必须是可执行的", env.canExecute())

        val restored = roundTrip(manager, entity)

        assertTrue(
            "导入后的 rootfs 丢了可执行位 —— proot 会报 '/usr/bin/env' is not executable",
            File(restored, "linux/usr/bin/env").canExecute(),
        )
    }

    @Test
    fun `导出导入保留文件的可读写位与非可执行位`() {
        assumeTrue("需要 POSIX 权限位（CI 是 ubuntu-latest）", supportsPosix())

        val manager = newManager()
        val entity = newEntity("w-mode")
        val notes = File(manager.filesDir(entity.root), "notes.md").apply {
            parentFile?.mkdirs()
            writeText("hello\n")
        }
        notes.setExecutable(false, false)
        notes.setWritable(true, false)
        notes.setReadable(true, false)

        val restored = roundTrip(manager, entity)
        val restoredNotes = File(restored, "files/notes.md")

        assertTrue("普通文件应保持可读", restoredNotes.canRead())
        assertTrue("普通文件应保持可写", restoredNotes.canWrite())
        assertFalse("普通文件不应凭空变成可执行", restoredNotes.canExecute())
    }

    @Test
    fun `导出导入保留符号链接语义`() {
        val manager = newManager()
        val entity = newEntity("w-link")
        val usrBin = File(manager.linuxDir(entity.root), "usr/bin").apply { mkdirs() }
        File(usrBin, "env").writeText("#!/bin/sh\n")
        val binLink = File(manager.linuxDir(entity.root), "bin")
        val created = runCatching {
            Files.createSymbolicLink(binLink.toPath(), File("usr/bin").toPath())
        }.isSuccess
        assumeTrue("本机文件系统不支持符号链接，跳过", created)

        val restored = roundTrip(manager, entity)
        val restoredLink = File(restored, "linux/bin").toPath()

        assertTrue("符号链接被解成了普通文件/目录", Files.isSymbolicLink(restoredLink))
        assertEquals("usr/bin", Files.readSymbolicLink(restoredLink).toString())
    }
}
