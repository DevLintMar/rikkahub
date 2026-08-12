package me.rerere.rikkahub.data.repository

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class UploadCleanupTest {

    private val uploadA = "file:///data/user/0/xyz.lynsei.rikkahub.debug/files/upload/88110845-0154-4a54-8286-6bc6535aa440.png"
    private val uploadB = "file:///data/user/0/xyz.lynsei.rikkahub.debug/files/upload/e539383a-a4c8-4659-862d-9c90dd1ec9b3.png"

    @Test
    fun `uploadFileNameOrNull 提取 upload 文件名的 uuid 部分`() {
        assertEquals("88110845-0154-4a54-8286-6bc6535aa440.png", uploadA.uploadFileNameOrNull())
        assertEquals("e539383a-a4c8-4659-862d-9c90dd1ec9b3.png", uploadB.uploadFileNameOrNull())
    }

    @Test
    fun `非 upload 路径返回 null`() {
        val nonUpload = "file:///data/user/0/xyz.lynsei.rikkahub.debug/files/images/abc.png"
        assertEquals(null, nonUpload.uploadFileNameOrNull())
    }

    @Test
    fun `失去引用的文件若被其他消息引用则保留`() {
        // isReferenced 模拟 DAO：uploadB 仍被某会话消息引用 → 不删；uploadA 无引用 → 删
        val toDelete = filterUnreferencedUploadUrls(
            lostUrls = listOf(uploadA, uploadB),
            isReferenced = { fileName -> fileName == uploadB.uploadFileNameOrNull() },
        )
        assertEquals(listOf(uploadA), toDelete)
    }

    @Test
    fun `全部仍被引用则一个不删`() {
        val toDelete = filterUnreferencedUploadUrls(
            lostUrls = listOf(uploadA, uploadB),
            isReferenced = { true },
        )
        assertTrue(toDelete.isEmpty())
    }

    @Test
    fun `全部无引用则全部删除`() {
        val toDelete = filterUnreferencedUploadUrls(
            lostUrls = listOf(uploadA, uploadB),
            isReferenced = { false },
        )
        assertEquals(listOf(uploadA, uploadB), toDelete)
    }

    @Test
    fun `空输入返回空`() {
        assertTrue(filterUnreferencedUploadUrls(emptyList()) { false }.isEmpty())
    }
}
