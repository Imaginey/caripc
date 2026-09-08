package com.caripc.runtime

import com.caripc.contract.ErrorCode
import com.caripc.contract.IpcError
import com.caripc.contract.ResultCallback
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

class RequestTrackerTest {

    @Test
    fun testExactlyOnceCompletionSuccess() {
        val tracker = RequestTracker()
        val counter = AtomicInteger(0)

        tracker.register("req-1", 5000L, ResultCallback<String> { res ->
            if (res.isSuccess) {
                counter.incrementAndGet()
            }
        })

        val first = tracker.completeSuccess("req-1", "Hello")
        val second = tracker.completeSuccess("req-1", "Duplicate")

        assertTrue(first)
        assertFalse(second)
        assertEquals(1, counter.get())
        assertEquals(1L, tracker.lateResponseCount.get())
    }

    @Test
    fun testTimeoutHandling() {
        val tracker = RequestTracker()
        val errorCounter = AtomicInteger(0)

        tracker.register("req-2", 100L, ResultCallback<String> { res ->
            if (res.isFailure) {
                errorCounter.incrementAndGet()
                val err = res.exceptionOrNull() as? IpcError
                assertEquals(ErrorCode.TIMEOUT, err?.code)
            }
        })

        // 模拟超时
        tracker.checkTimeouts(nowElapsedMs = System.currentTimeMillis() + 200L)

        assertEquals(1, errorCounter.get())
        assertEquals(1L, tracker.timeoutCount.get())

        // 超时后迟到响应必须被丢弃且不再触发回调
        val late = tracker.completeSuccess("req-2", "Late")
        assertFalse(late)
        assertEquals(1, errorCounter.get())
        assertEquals(1L, tracker.lateResponseCount.get())
    }
}
