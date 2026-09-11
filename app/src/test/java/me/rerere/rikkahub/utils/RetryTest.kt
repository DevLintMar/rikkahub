package me.rerere.rikkahub.utils

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.IOException
import java.io.InterruptedIOException
import java.net.SocketTimeoutException

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

    // ---- retryIf：超时不重试 ----

    @Test
    fun `retryIf 返回 false 时立即失败不再尝试`() = runBlocking {
        var calls = 0
        val retried = mutableListOf<Int>()
        val timeout = SocketTimeoutException("timeout")
        try {
            retryOnFailure(
                attempts = 4,
                delayMillis = 0,
                onRetry = { attempt, _ -> retried.add(attempt) },
                retryIf = { !isTimeoutFailure(it) },
            ) {
                calls++
                throw timeout
            }
            fail("should have thrown")
        } catch (e: SocketTimeoutException) {
            assertSame(timeout, e)
        }
        assertEquals("超时后只尝试一次", 1, calls)
        assertTrue("超时不该触发重试回调", retried.isEmpty())
    }

    @Test
    fun `retryIf 只拦超时 其它瞬时错误照常重试`() = runBlocking {
        var calls = 0
        try {
            retryOnFailure(
                attempts = 4,
                delayMillis = 0,
                retryIf = { !isTimeoutFailure(it) },
            ) {
                calls++
                throw IOException("connection reset")
            }
            fail("should have thrown")
        } catch (e: IOException) {
            assertEquals("connection reset", e.message)
        }
        assertEquals(4, calls)
    }

    @Test
    fun `isTimeoutFailure 识别 OkHttp 的各类超时`() {
        // OkHttp 的 callTimeout 抛 InterruptedIOException；读超时抛它的子类 SocketTimeoutException
        assertTrue(isTimeoutFailure(InterruptedIOException("timeout")))
        assertTrue(isTimeoutFailure(SocketTimeoutException("timeout")))
        // 非超时的网络错误、业务错误、取消都不算超时
        assertFalse(isTimeoutFailure(IOException("connection reset")))
        assertFalse(isTimeoutFailure(java.net.UnknownHostException("no dns")))
        assertFalse(isTimeoutFailure(IllegalStateException("Download failed with status 404")))
        assertFalse(isTimeoutFailure(CancellationException("cancelled")))
    }
}
