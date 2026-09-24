package me.rerere.rikkahub.service

import me.rerere.rikkahub.R
import org.junit.Assert.assertEquals
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
    }
}
