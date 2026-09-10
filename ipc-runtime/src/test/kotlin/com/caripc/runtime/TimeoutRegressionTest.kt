package com.caripc.runtime

import com.caripc.contract.CompletionState
import com.caripc.contract.ErrorCode
import com.caripc.contract.IpcError
import com.caripc.contract.ResultCallback
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/**
 * 请求超时链路（工单 P0-1 / P2-2）。
 *
 * 修复前 checkTimeouts() 只在单元测试里被调用，生产代码没有任何调度器，
 * 因此「对端不响应」等于回调永不触发 + pendingRequests 永久泄漏。
 */
class TimeoutRegressionTest {

    @Test
    fun testSchedulerDrivesRequestTimeoutWithoutManualTick() {
        val previous = TimeProvider.timeSource
        TimeProvider.timeSource = { System.nanoTime() / 1_000_000 }
        val scheduler = ExecutorTimeoutScheduler()
        try {
            val tracker = RequestTracker()
            val latch = CountDownLatch(1)
            val observed = AtomicReference<IpcError?>()

            tracker.register("req-timeout", 30L, ResultCallback<Unit> { result ->
                result.onFailure { observed.set(it as IpcError) }
                latch.countDown()
            })
            val tick = scheduler.schedulePeriodic(5L) { tracker.checkTimeouts() }

            assertTrue("timeout must fire automatically", latch.await(3, TimeUnit.SECONDS))
            assertEquals(ErrorCode.TIMEOUT, observed.get()?.code)
            assertEquals(CompletionState.UNKNOWN, observed.get()?.completionState)
            assertEquals(0, tracker.pendingCount())
            assertEquals(1L, tracker.timeoutCount.get())
            tick.cancel()
        } finally {
            scheduler.shutdown()
            TimeProvider.timeSource = previous
        }
    }

    @Test
    fun testLateResponseAfterTimeoutIsDropped() {
        val tracker = RequestTracker()
        val completions = AtomicInteger(0)
        tracker.register("req-late", 100L, ResultCallback<String> { completions.incrementAndGet() })

        tracker.checkTimeouts(nowElapsedMs = TimeProvider.elapsedRealtime() + 200L)
        assertEquals(1, completions.get())

        assertFalse("late response must not complete again", tracker.completeSuccess("req-late", "late"))
        assertEquals(1, completions.get())
        assertEquals(1L, tracker.lateResponseCount.get())
    }

    @Test
    fun testDuplicateRequestIdFailsPreviousRequestInsteadOfSilentlyDroppingIt() {
        val tracker = RequestTracker()
        val firstResult = AtomicReference<IpcError?>()
        tracker.register("same-id", 5_000L, ResultCallback<String> { res ->
            res.onFailure { firstResult.set(it as IpcError) }
        })

        // 复用同一个 requestId 注册第二个请求：第一个必须被显式失败，而不是永久悬挂
        tracker.register("same-id", 5_000L, ResultCallback<String> { })
        assertNotNull("previous pending request must be failed explicitly", firstResult.get())
        assertEquals(ErrorCode.INTERNAL_ERROR, firstResult.get()?.code)
        assertEquals(1, tracker.pendingCount())
    }

    @Test
    fun testConnectionLossCompletesAllPendingRequests() {
        val tracker = RequestTracker()
        val errors = AtomicInteger(0)
        repeat(3) { index ->
            tracker.register("req-$index", 5_000L, ResultCallback<String> { res ->
                res.onFailure { error -> if ((error as IpcError).code == ErrorCode.CONNECTION_LOST) errors.incrementAndGet() }
            })
        }
        assertTrue(tracker.hasPending())
        tracker.clearAllWithConnectionLost()
        assertEquals(3, errors.get())
        assertFalse(tracker.hasPending())
    }

    @Test
    fun testExecutorSchedulerRunsPeriodicTaskAndStopsAfterCancel() {
        val scheduler = ExecutorTimeoutScheduler()
        try {
            val counter = AtomicInteger(0)
            val latch = CountDownLatch(1)
            val tick = scheduler.schedulePeriodic(5L) {
                if (counter.incrementAndGet() >= 3) latch.countDown()
            }
            assertTrue(latch.await(3, TimeUnit.SECONDS))
            tick.cancel()
            val afterCancel = counter.get()
            Thread.sleep(50)
            assertTrue("cancelled task must stop running", counter.get() <= afterCancel + 1)
        } finally {
            scheduler.shutdown()
        }
    }
}
