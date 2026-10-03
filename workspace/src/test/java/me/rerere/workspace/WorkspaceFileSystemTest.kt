package me.rerere.workspace

import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class WorkspaceFileSystemTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun globSortsByLastModifiedDescending() {
        val root = tempFolder.newFolder("workspace")
        val oldFile = File(root, "old.txt").apply {
            writeText("old")
            setLastModified(1_000_000L)
        }
        val midFile = File(root, "mid.txt").apply {
            writeText("mid")
            setLastModified(2_000_000L)
        }
        val newFile = File(root, "new.txt").apply {
            writeText("new")
            setLastModified(3_000_000L)
        }

        val fs = WorkspaceFileSystem()
        val results = fs.glob(root, "*.txt")

        assertEquals(listOf("new.txt", "mid.txt", "old.txt"), results.map { it.name })
    }
}
