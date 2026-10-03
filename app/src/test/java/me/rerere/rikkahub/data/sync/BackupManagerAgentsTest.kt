package me.rerere.rikkahub.data.sync

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BackupManagerAgentsTest {
    @Test
    fun agentsFolderIsRecognizedAsAttachment() {
        assertTrue(BackupManager.isAttachment("agents/coding-agent.md"))
        assertTrue(BackupManager.isAttachment("agents/researcher.md"))
        assertTrue(BackupManager.isAttachment("upload/photo.jpg"))
        assertTrue(BackupManager.isAttachment("images/gen_01.png"))
        assertTrue(BackupManager.isAttachment("skills/test-skill/skill.json"))
    }

    @Test
    fun unknownOrInvalidFoldersAreRejected() {
        assertFalse(BackupManager.isAttachment("unknown/file.txt"))
        assertFalse(BackupManager.isAttachment("workspaces/ws1/file.txt"))
        assertFalse(BackupManager.isAttachment("agents"))
    }
}
