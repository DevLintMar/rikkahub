package me.rerere.rikkahub.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TaskDeliveryQueueTest {

    @Test
    fun `先进先出`() {
        val queue = TaskDeliveryQueue()
        queue.enqueue("sub_a")
        queue.enqueue("sub_b")

        assertEquals("sub_a", queue.peek())
        assertEquals("sub_a", queue.takeNext())
        assertEquals("sub_b", queue.takeNext())
        assertNull(queue.takeNext())
    }

    @Test
    fun `同一个任务只入队一次`() {
        val queue = TaskDeliveryQueue()

        assertTrue(queue.enqueue("sub_a"))
        assertFalse(queue.enqueue("sub_a"))
        assertEquals(1, queue.size())
    }

    @Test
    fun `取出后再入队是允许的`() {
        val queue = TaskDeliveryQueue()
        queue.enqueue("sub_a")
        queue.takeNext()

        assertTrue(queue.enqueue("sub_a"))
        assertEquals("sub_a", queue.peek())
    }

    @Test
    fun `remove 只删指定任务并报告是否删到`() {
        val queue = TaskDeliveryQueue()
        queue.enqueue("sub_a")
        queue.enqueue("sub_b")

        assertTrue(queue.remove("sub_b"))
        assertFalse(queue.remove("sub_b"))
        assertEquals(1, queue.size())
        assertEquals("sub_a", queue.peek())
    }

    @Test
    fun `contains 与 clear`() {
        val queue = TaskDeliveryQueue()
        queue.enqueue("sub_a")

        assertTrue(queue.contains("sub_a"))
        assertFalse(queue.contains("sub_missing"))
        queue.clear()
        assertTrue(queue.size() == 0)
        assertFalse(queue.contains("sub_a"))
    }
}
