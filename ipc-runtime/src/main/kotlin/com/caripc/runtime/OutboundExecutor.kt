package com.caripc.runtime

import android.util.Log
import java.util.concurrent.*
import java.util.concurrent.atomic.AtomicInteger

class OutboundExecutor(
    corePoolSize: Int = 4,
    maxPoolSize: Int = 8,
    queueCapacity: Int = 512
) {
    private val threadCounter = AtomicInteger(1)
    private val executor = ThreadPoolExecutor(
        corePoolSize,
        maxPoolSize,
        60L,
        TimeUnit.SECONDS,
        LinkedBlockingQueue(queueCapacity),
        { r ->
            val thread = Thread(r, "caripc-outbound-${threadCounter.getAndIncrement()}")
            thread.isDaemon = true
            thread
        },
        ThreadPoolExecutor.AbortPolicy()
    )

    fun submit(task: Runnable): Boolean {
        return try {
            executor.execute(task)
            true
        } catch (e: RejectedExecutionException) {
            Log.e("CarIpcOutbound", "Outbound executor queue full, task rejected", e)
            false
        }
    }

    fun shutdown() {
        executor.shutdown()
    }
}
