package me.rerere.rikkahub.utils

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.fail
import org.junit.Test

class RetryTest {

    @Test
    fun `首次成功只调用一次 block`() = runBlocking {
        var calls = 0
        val result = retryOnFailure(attempts = 4, delayMillis = 0) {
            calls++
            "ok"
        }
        assertEquals("ok", result)
        assertEquals(1, calls)
    }

    @Test
    fun `失败后按重试次数直到成功`() = runBlocking {
        var calls = 0
        val retried = mutableListOf<Int>()
        val result = retryOnFailure(
            attempts = 4,
            delayMillis = 0,
            onRetry = { attempt, _ -> retried.add(attempt) },
        ) {
            calls++
            if (calls < 3) throw IllegalStateException("boom")
            "done"
        }
        assertEquals("done", result)
        assertEquals(3, calls) // 失败 2 次 + 成功 1 次
        assertEquals(listOf(1, 2), retried)
    }

    @Test
    fun `全部失败后抛出最后一次异常且总共尝试 attempts 次`() = runBlocking {
        var calls = 0
        val boom = IllegalStateException("boom")
        try {
            retryOnFailure(attempts = 4, delayMillis = 0) {
                calls++
                throw boom
            }
            fail("should have thrown")
        } catch (e: IllegalStateException) {
            assertSame(boom, e)
        }
        assertEquals(4, calls) // 首次 + 3 次重试
    }

    @Test
    fun `CancellationException 不重试直接重抛`() = runBlocking {
        var calls = 0
        val cancel = CancellationException("cancelled")
        try {
            retryOnFailure(attempts = 4, delayMillis = 0) {
                calls++
                throw cancel
            }
            fail("should have thrown")
        } catch (e: CancellationException) {
            assertSame(cancel, e)
        }
        assertEquals(1, calls)
    }
}
