package me.rerere.rikkahub.data.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

class WorkspaceBackupProgressTest {
    @get:Rule
    val temporary = TemporaryFolder()

    @Test
    fun extractToInvokesProgressCallback() {
        val zipFile = File(temporary.root, "test.zip")
        ZipOutputStream(zipFile.outputStream()).use { out ->
            out.putNextEntry(ZipEntry(WorkspaceBackup.META_ENTRY))
            out.write("""{"name":"test","toolApprovals":"","createdAt":0,"updatedAt":0,"shellStatus":""}""".toByteArray())
            out.closeEntry()

            out.putNextEntry(ZipEntry("files/hello.txt"))
            out.write("hello".toByteArray())
            out.closeEntry()

            out.putNextEntry(ZipEntry("files/world.txt"))
            out.write("world".toByteArray())
            out.closeEntry()
        }

        val targetDir = File(temporary.root, "target")
        targetDir.mkdirs()

        val progressReports = mutableListOf<Triple<Int, Int, String>>()
        ZipFile(zipFile).use { zip ->
            WorkspaceBackup.extractTo(zip, targetDir) { current, total, path ->
                progressReports.add(Triple(current, total, path))
            }
        }

        assertTrue("Progress callback should be invoked", progressReports.isNotEmpty())
        assertEquals(3, progressReports.last().second) // 3 entries
        assertTrue(File(targetDir, "files/hello.txt").exists())
    }
}
