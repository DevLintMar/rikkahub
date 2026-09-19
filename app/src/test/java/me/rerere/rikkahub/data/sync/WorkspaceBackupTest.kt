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
import java.nio.file.Paths
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

    /**
     * 导出只该跳过**工作区自己的顶层临时目录**。
     *
     * 早期实现按文件名在任意深度过滤 `tmp` 与 `.l2s.`，误伤两类真实内容：
     * `.l2s.*` 是 proot `--link2symlink` 的后备文件（沙箱文件系统的一部分），
     * `files/tmp/...` 是用户在沙箱里自建的目录。
     */
    @Test
    fun `导出只跳过顶层临时目录`() {
        val manager = newManager()
        val entity = newEntity("w-keep")
        val filesDir = manager.filesDir(entity.root)
        File(filesDir, ".l2s.data.bin.0002.0002").apply {
            parentFile?.mkdirs()
            writeText("backing\n")
        }
        File(filesDir, "tmp/keep.txt").apply {
            parentFile?.mkdirs()
            writeText("user data\n")
        }
        File(manager.tempDir(entity.root), "scratch.txt").apply {
            parentFile?.mkdirs()
            writeText("transient\n")
        }

        val restored = roundTrip(manager, entity)

        assertTrue(
            "proot 的 .l2s. 后备文件属于文件系统本身，必须原样搬走",
            File(restored, "files/.l2s.data.bin.0002.0002").isFile,
        )
        assertTrue(
            "files/ 下用户自建的 tmp 目录是用户数据，不能按名字滤掉",
            File(restored, "files/tmp/keep.txt").isFile,
        )
        assertFalse(
            "工作区自己的顶层临时目录不该进备份",
            File(restored, "tmp/scratch.txt").exists(),
        )
    }

    /** 后备文件与链接是一对：只搬链接会让导入后的文件变成断链。 */
    @Test
    fun `l2s 链接在导入后仍能解析到后备文件`() {
        val manager = newManager()
        val entity = newEntity("w-l2s")
        val filesDir = manager.filesDir(entity.root)
        val backingName = ".l2s.data.bin.0002.0002"
        File(filesDir, backingName).apply {
            parentFile?.mkdirs()
            writeText("backing\n")
        }
        val created = runCatching {
            Files.createSymbolicLink(File(filesDir, "data.bin").toPath(), Paths.get(backingName))
        }.isSuccess
        assumeTrue("本机文件系统不支持符号链接，跳过", created)

        val restored = roundTrip(manager, entity)

        assertTrue(
            "导入后 data.bin 是断链 —— 备份把它的后备文件丢了",
            File(restored, "files/data.bin").isFile,
        )
    }
}
