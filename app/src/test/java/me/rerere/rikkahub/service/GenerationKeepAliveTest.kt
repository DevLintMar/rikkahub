package me.rerere.rikkahub.service

import me.rerere.rikkahub.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class GenerationKeepAliveTest {

    @Test
    fun `只有后台任务时显示后台任务文案`() {
        assertEquals(
            R.string.notification_sub_agent_running,
            foregroundNotificationLabelRes(listOf(true, true)),
        )
    }

    @Test
    fun `只要有一个聊天生成就显示生成中文案`() {
        assertEquals(
            R.string.notification_live_update_title,
            foregroundNotificationLabelRes(listOf(true, false)),
        )
        assertEquals(
            R.string.notification_live_update_title,
            foregroundNotificationLabelRes(listOf(false)),
        )
        assertEquals(
            R.string.notification_live_update_title,
            foregroundNotificationLabelRes(emptyList()),
        )
    }

    @Test
    fun `前提哨兵：两个资源 id 必须是互不相同的非零值`() {
        // 前两个用例都靠「两个 R.string 常量互不相同」才有分辨力。若哪天本地单测的 R 常量
        // 退化成 0，它们会双双变成 0 == 0 的恒真断言——绿，但什么都没测（假门禁比没有门禁更糟）。
        // 这一条红了，说明是**前提**坏了，不是标签逻辑坏了。
        assertNotEquals(R.string.notification_sub_agent_running, R.string.notification_live_update_title)
        assertNotEquals(0, R.string.notification_sub_agent_running)
    }
}
