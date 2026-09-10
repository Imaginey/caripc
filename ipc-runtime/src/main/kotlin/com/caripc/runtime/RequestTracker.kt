package com.caripc.runtime

import com.caripc.contract.CompletionState
import com.caripc.contract.ErrorCode
import com.caripc.contract.IpcError
import com.caripc.contract.ResultCallback
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * 请求关联表：保证每个 requestId 至多完成一次（响应/取消/超时/断连竞争）。
 *
 * 超时不再依赖外部手工调用：由 [TimeoutScheduler] 周期性调用 [checkTimeouts]。
 */
class RequestTracker {
    class PendingRequest<T>(
        val requestId: String,
        val deadlineElapsedMs: Long,
        val callback: ResultCallback<T>
    ) {
        val completed = AtomicBoolean(false)
    }

    private val pendingRequests = ConcurrentHashMap<String, PendingRequest<*>>()
    val lateResponseCount = AtomicLong(0L)
    val timeoutCount = AtomicLong(0L)

    fun <T> register(requestId: String, timeoutMs: Long, callback: ResultCallback<T>): PendingRequest<T> {
        val deadline = TimeProvider.elapsedRealtime() + timeoutMs
        val pending = PendingRequest(requestId, deadline, callback)
        val previous = pendingRequests.put(requestId, pending)
        if (previous != null) {
            // 同 id 重复注册：不能静默遗弃旧回调（工单 P2-2）
            IpcLog.w("RequestTracker", "Duplicate requestId $requestId registered; failing the previous one")
            @Suppress("UNCHECKED_CAST")
            val old = previous as PendingRequest<Any?>
            if (old.completed.compareAndSet(false, true)) {
                old.callback.onError(
                    IpcError(
                        ErrorCode.INTERNAL_ERROR,
                        "Request id $requestId was reused before completion",
                        requestId,
                        CompletionState.UNKNOWN
                    )
                )
            }
        }
        return pending
    }

    fun hasPending(): Boolean = pendingRequests.isNotEmpty()

    fun pendingCount(): Int = pendingRequests.size

    fun cancelPending(requestId: String) {
        pendingRequests.remove(requestId)?.let { it.completed.set(true) }
    }

    @Suppress("UNCHECKED_CAST")
    fun <T> completeSuccess(requestId: String, value: T): Boolean {
        val pending = pendingRequests.remove(requestId) as? PendingRequest<T> ?: run {
            lateResponseCount.incrementAndGet()
            return false
        }
        if (pending.completed.compareAndSet(false, true)) {
            pending.callback.onSuccess(value)
            return true
        }
        lateResponseCount.incrementAndGet()
        return false
    }

    fun completeError(requestId: String, error: IpcError): Boolean {
        val pending = pendingRequests.remove(requestId) ?: run {
            lateResponseCount.incrementAndGet()
            return false
        }
        if (pending.completed.compareAndSet(false, true)) {
            pending.callback.onError(error)
            return true
        }
        lateResponseCount.incrementAndGet()
        return false
    }

    fun cancel(requestId: String): Boolean {
        val pending = pendingRequests.remove(requestId) ?: return false
        if (pending.completed.compareAndSet(false, true)) {
            pending.callback.onError(
                IpcError(
                    ErrorCode.CANCELLED,
                    "Request $requestId was cancelled",
                    requestId,
                    CompletionState.NOT_EXECUTED
                )
            )
            return true
        }
        return false
    }

    /** 由调度器周期调用；也允许测试用显式时间推进。 */
    fun checkTimeouts(nowElapsedMs: Long = TimeProvider.elapsedRealtime()) {
        if (pendingRequests.isEmpty()) return
        val it = pendingRequests.entries.iterator()
        while (it.hasNext()) {
            val entry = it.next()
            val pending = entry.value
            if (nowElapsedMs >= pending.deadlineElapsedMs) {
                it.remove()
                if (pending.completed.compareAndSet(false, true)) {
                    timeoutCount.incrementAndGet()
                    IpcLog.w("RequestTracker", "Request ${pending.requestId} timed out")
                    pending.callback.onError(
                        IpcError(
                            ErrorCode.TIMEOUT,
                            "Request ${pending.requestId} timed out after deadline",
                            pending.requestId,
                            CompletionState.UNKNOWN
                        )
                    )
                }
            }
        }
    }

    fun clearAllWithConnectionLost() {
        val it = pendingRequests.entries.iterator()
        while (it.hasNext()) {
            val entry = it.next()
            it.remove()
            val pending = entry.value
            if (pending.completed.compareAndSet(false, true)) {
                pending.callback.onError(
                    IpcError(
                        ErrorCode.CONNECTION_LOST,
                        "Connection lost before request completed",
                        pending.requestId,
                        CompletionState.UNKNOWN
                    )
                )
            }
        }
    }
}
