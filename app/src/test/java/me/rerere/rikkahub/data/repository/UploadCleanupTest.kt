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

    // ---- 删除消息后的"失去引用"判定（按 upload 文件名，不按 URL 字符串）----

    /** 同一物理文件的另一种拼写：context.filesDir 经 /data/data ↔ /data/user/0 符号链接 */
    private val uploadACanonical = uploadA.replace("/data/user/0/", "/data/data/")

    @Test
    fun `同一文件的两种 URL 拼写 删掉其中一条引用时不算失去引用`() {
        // 复现用户报的 bug：用户消息里是 /data/user/0/...，read_image 工具结果里是 canonical 化的
        // /data/data/...。删除 AI 回复后只剩用户消息那条引用，但那是同一张图 → 不能判为失去引用
        // （否则引用计数排除当前会话 → refs=0 → 图片被物理删除，重新生成时图没了）
        val lost = lostUploadUrlsAfterDelete(
            oldUrls = listOf(uploadA, uploadACanonical),
            newUrls = listOf(uploadA),
        )
        assertTrue("同文件的另一种拼写仍被引用，不应判为失去引用：$lost", lost.isEmpty())
    }

    @Test
    fun `反向 删掉用户消息但工具结果仍引用同一文件时不算失去引用`() {
        val lost = lostUploadUrlsAfterDelete(
            oldUrls = listOf(uploadA, uploadACanonical),
            newUrls = listOf(uploadACanonical),
        )
        assertTrue(lost.isEmpty())
    }

    @Test
    fun `真正不再被任何消息引用的 upload 附件会被回收`() {
        assertEquals(
            listOf(uploadA),
            lostUploadUrlsAfterDelete(oldUrls = listOf(uploadA, uploadB), newUrls = listOf(uploadB)),
        )
    }

    @Test
    fun `非 upload 文件按 URL 精确比较`() {
        val workspaceA = "file:///data/user/0/x/files/workspaces/w1/files/a.png"
        val workspaceB = "file:///data/user/0/x/files/workspaces/w2/files/a.png"
        assertEquals(
            listOf(workspaceA),
            lostUploadUrlsAfterDelete(oldUrls = listOf(workspaceA, workspaceB), newUrls = listOf(workspaceB)),
        )
    }

    @Test
    fun `引用全部保留时没有丢失文件`() {
        assertTrue(lostUploadUrlsAfterDelete(listOf(uploadA, uploadB), listOf(uploadA, uploadB)).isEmpty())
        assertTrue(lostUploadUrlsAfterDelete(emptyList(), listOf(uploadA)).isEmpty())
    }
}
