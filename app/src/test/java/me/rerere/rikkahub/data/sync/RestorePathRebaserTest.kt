package me.rerere.rikkahub.data.sync

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RestorePathRebaserTest {
    private val currentFilesDir = File("/data/user/0/xyz.lynsei.rikkahub/files")

    // 期望的当前包前缀：与生产代码一致用 absolutePath，保证跨平台（Linux/Windows）稳定
    private fun current(relPath: String) = "file://${currentFilesDir.absolutePath}/$relPath"

    @Test
    fun `rebase rebinds foreign package path to current package`() {
        val input = "file:///data/user/0/xyz.lynsei.rikkahub.debug/files/upload/a.png"

        val rebased = RestorePathRebaser.rebase(input, currentFilesDir)

        assertEquals(current("upload/a.png"), rebased)
    }

    @Test
    fun `rebase is idempotent for current package`() {
        val input = current("upload/a.png")

        val rebased = RestorePathRebaser.rebase(input, currentFilesDir)

        assertEquals(input, rebased)
        assertEquals(0, RestorePathRebaser.foreignPrefixCount(input, currentFilesDir))
    }

    @Test
    fun `rebase handles legacy data-data path`() {
        val input = "file:///data/data/me.rerere.rikkahub/files/upload/a.png"

        val rebased = RestorePathRebaser.rebase(input, currentFilesDir)

        assertEquals(current("upload/a.png"), rebased)
    }

    @Test
    fun `rebase handles work profile user id`() {
        val input = "file:///data/user/10/xyz.lynsei.rikkahub.pre/files/upload/a.png"

        val rebased = RestorePathRebaser.rebase(input, currentFilesDir)

        assertEquals(current("upload/a.png"), rebased)
    }

    @Test
    fun `rebase rewrites every ref in a settings json blob`() {
        val input = """
            {"assistants":[{"avatar":{"url":"file:///data/user/0/me.rerere.rikkahub/files/upload/avatar.png"},
            "background":"file:///data/user/0/me.rerere.rikkahub/files/upload/bg.png"}],
            "displaySetting":{"userAvatar":{"url":"file:///data/user/0/xyz.lynsei.rikkahub.debug/files/upload/u.png"}}}
        """.trimIndent()

        val rebased = RestorePathRebaser.rebase(input, currentFilesDir)

        assertFalse(rebased.contains("me.rerere.rikkahub/files"))
        assertFalse(rebased.contains("xyz.lynsei.rikkahub.debug/files"))
        assertEquals(3, RestorePathRebaser.foreignPrefixCount(input, currentFilesDir))
        assertTrue(rebased.contains(current("upload/avatar.png")))
        assertTrue(rebased.contains(current("upload/bg.png")))
        assertTrue(rebased.contains(current("upload/u.png")))
    }

    @Test
    fun `rebase leaves non-file-prefixed urls untouched`() {
        val input = listOf(
            "https://example.com/files/a.png",
            "content://media/external/images/media/1",
            "file:///android_asset/icons/logo.png",
            "/data/user/0/xyz.lynsei.rikkahub.debug/files/x.png",
        ).joinToString(",")

        val rebased = RestorePathRebaser.rebase(input, currentFilesDir)

        // 无 file:///data/.../files/ 形态的 URI 不应被改动
        assertTrue(rebased.contains("https://example.com/files/a.png"))
        assertTrue(rebased.contains("content://media/external/images/media/1"))
        assertTrue(rebased.contains("file:///android_asset/icons/logo.png"))
        // 裸路径（无 file:// 前缀）不受影响
        assertTrue(rebased.contains("/data/user/0/xyz.lynsei.rikkahub.debug/files/x.png"))
        assertEquals(0, RestorePathRebaser.foreignPrefixCount(input, currentFilesDir))
    }

    @Test
    fun `foreignPrefixCount counts only foreign refs`() {
        val input = """
            file:///data/user/0/me.rerere.rikkahub/files/a.png
            ${current("b.png")}
            file:///data/user/0/xyz.lynsei.rikkahub.debug/files/c.png
        """.trimIndent()

        assertEquals(2, RestorePathRebaser.foreignPrefixCount(input, currentFilesDir))
    }
}
