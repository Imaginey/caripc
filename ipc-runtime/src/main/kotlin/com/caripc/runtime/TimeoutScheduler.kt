package com.caripc.runtime

import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** 可取消的周期任务句柄。 */
fun interface ScheduledTick {
    fun cancel()
}

/**
 * 周期定时抽象。
 *
 * 生产实现基于单个守护线程的 ScheduledExecutorService；单元测试注入
 * [ManualTimeoutScheduler] 手动推进，避免 sleep 型不确定性测试。
 */
interface TimeoutScheduler {
    /** 以固定间隔周期执行 [task]，返回可取消句柄。 */
    fun schedulePeriodic(intervalMs: Long, task: () -> Unit): ScheduledTick

    fun shutdown()
}

/** 生产实现：单守护线程调度器。 */
class ExecutorTimeoutScheduler(threadName: String = "caripc-timer") : TimeoutScheduler {

    private val threadCounter = AtomicInteger(1)

    private val executor = ScheduledThreadPoolExecutor(1) { r ->
        Thread(r, "$threadName-${threadCounter.getAndIncrement()}").apply { isDaemon = true }
    }.apply {
        removeOnCancelPolicy = true
        executeExistingDelayedTasksAfterShutdownPolicy = false
        continueExistingPeriodicTasksAfterShutdownPolicy = false
    }

    override fun schedulePeriodic(intervalMs: Long, task: () -> Unit): ScheduledTick {
        val interval = intervalMs.coerceAtLeast(1L)
        val future = executor.scheduleWithFixedDelay({
            try {
                task()
            } catch (t: Throwable) {
                IpcLog.e("TimeoutScheduler", "periodic task failed", t)
            }
        }, interval, interval, TimeUnit.MILLISECONDS)
        return ScheduledTick { future.cancel(false) }
    }

    override fun shutdown() {
        executor.shutdownNow()
    }
}

/** 测试实现：手动推进时间。 */
class ManualTimeoutScheduler : TimeoutScheduler {

    private class Entry(val intervalMs: Long, val task: () -> Unit) {
        @Volatile
        var active = true
    }

    private val entries = CopyOnWriteArrayList<Entry>()

    override fun schedulePeriodic(intervalMs: Long, task: () -> Unit): ScheduledTick {
        val entry = Entry(intervalMs, task)
        entries.add(entry)
        return ScheduledTick {
            entry.active = false
            entries.remove(entry)
        }
    }

    val activeTaskCount: Int get() = entries.count { it.active }

    /** 模拟一次定时到期，执行所有仍激活的任务。 */
    fun tick() {
        entries.toList().forEach { if (it.active) it.task() }
    }

    fun tick(times: Int) = repeat(times) { tick() }

    override fun shutdown() {
        entries.clear()
    }
}
