package me.rerere.rikkahub.data.ai.tools.local

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.uuid.Uuid

class SubAgentTaskRegistryTest {

    @Test
    fun `登记后任务处于存活状态`() {
        val registry = SubAgentTaskRegistry()
        val conversationId = Uuid.random()

        registry.register("sub_1", conversationId, "搜索 AI 新闻", "找到三条新闻")

        assertTrue(registry.isLive("sub_1"))
        assertEquals(1, registry.liveCount())
        assertEquals(conversationId, registry.get("sub_1")?.conversationId)
        assertEquals("搜索 AI 新闻", registry.get("sub_1")?.description)
        assertEquals(TaskStatus.IN_PROGRESS, registry.get("sub_1")?.status)
    }

    @Test
    fun `未登记的任务不是存活任务且查询为空`() {
        val registry = SubAgentTaskRegistry()

        assertFalse(registry.isLive("sub_missing"))
        assertNull(registry.get("sub_missing"))
        assertEquals(0, registry.liveCount())
        assertTrue(registry.all().isEmpty())
    }

    @Test
    fun `终态后不再是存活任务且带着结果与原因`() {
        val registry = SubAgentTaskRegistry()
        registry.register("sub_1", Uuid.random(), "搜索 AI 新闻", "找到三条新闻")

        registry.finish(
            taskId = "sub_1",
            status = TaskStatus.COMPLETED,
            reason = null,
            result = "三条新闻……",
            error = null,
        )

        assertFalse(registry.isLive("sub_1"))
        assertEquals(0, registry.liveCount())
        assertEquals(TaskStatus.COMPLETED, registry.get("sub_1")?.status)
        assertEquals("三条新闻……", registry.get("sub_1")?.result)
        assertNull(registry.get("sub_1")?.reason)
    }

    @Test
    fun `终态不会被第二次覆盖`() {
        val registry = SubAgentTaskRegistry()
        registry.register("sub_1", Uuid.random(), "搜索 AI 新闻", "找到三条新闻")
        registry.finish("sub_1", TaskStatus.COMPLETED, null, "成功的结果", null)

        // 取消与正常完成可能同时到达；先到者胜，避免「已完成」被改写成「已取消」
        val second = registry.finish(
            "sub_1",
            TaskStatus.FAILED,
            SubAgentFailReason.USER_CANCELLED,
            null,
            "cancelled",
        )

        assertEquals(TaskStatus.COMPLETED, second?.status)
        assertEquals(TaskStatus.COMPLETED, registry.get("sub_1")?.status)
        assertEquals("成功的结果", registry.get("sub_1")?.result)
    }

    @Test
    fun `取消未登记或已终态的任务是空操作`() {
        val registry = SubAgentTaskRegistry()
        assertNull(registry.finish("sub_missing", TaskStatus.FAILED, SubAgentFailReason.USER_CANCELLED, null, "x"))

        registry.register("sub_1", Uuid.random(), "任务", "提示")
        registry.finish("sub_1", TaskStatus.FAILED, SubAgentFailReason.APP_EXIT, null, "app_exit")
        registry.finish("sub_1", TaskStatus.FAILED, SubAgentFailReason.USER_CANCELLED, null, "cancelled")

        assertEquals(SubAgentFailReason.APP_EXIT, registry.get("sub_1")?.reason)
    }

    @Test
    fun `liveCount 只统计进行中的任务`() {
        val registry = SubAgentTaskRegistry()
        registry.register("sub_1", Uuid.random(), "A", "a")
        registry.register("sub_1b", Uuid.random(), "B", "b")
        registry.register("sub_1c", Uuid.random(), "C", "c")
        registry.finish("sub_1b", TaskStatus.COMPLETED, null, "done", null)

        assertEquals(2, registry.liveCount())
        assertEquals(3, registry.all().size)
    }
}
