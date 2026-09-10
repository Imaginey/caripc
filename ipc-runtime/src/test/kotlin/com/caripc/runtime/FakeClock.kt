package com.caripc.runtime

import java.util.concurrent.atomic.AtomicLong

/**
 * 单元测试用可控单调时钟。
 *
 * android.os.SystemClock 在 JVM 单测中恒为 0（returnDefaultValues），
 * 因此凡是依赖时间推进的用例都必须替换 [TimeProvider.timeSource]。
 */
class FakeClock(initial: Long = 1_000L) : AutoCloseable {
    private val now = AtomicLong(initial)
    private val previous = TimeProvider.timeSource

    init {
        TimeProvider.timeSource = { now.get() }
    }

    fun advance(ms: Long) {
        now.addAndGet(ms)
    }

    fun now(): Long = now.get()

    override fun close() {
        TimeProvider.timeSource = previous
    }
}
