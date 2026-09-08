package com.caripc.runtime

import android.os.SystemClock
import com.caripc.contract.CompletionState
import com.caripc.contract.ErrorCode
import com.caripc.contract.IpcError
import com.caripc.contract.ResultCallback
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

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

    fun <T> register(requestId: String, timeoutMs: Long, callback: ResultCallback<T>) {
        val deadline = TimeProvider.elapsedRealtime() + timeoutMs
        pendingRequests[requestId] = PendingRequest(requestId, deadline, callback)
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
        } else {
            lateResponseCount.incrementAndGet()
            return false
        }
    }

    fun completeError(requestId: String, error: IpcError): Boolean {
        val pending = pendingRequests.remove(requestId) ?: run {
            lateResponseCount.incrementAndGet()
            return false
        }
        if (pending.completed.compareAndSet(false, true)) {
            pending.callback.onError(error)
            return true
        } else {
            lateResponseCount.incrementAndGet()
            return false
        }
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

    fun checkTimeouts(nowElapsedMs: Long = TimeProvider.elapsedRealtime()) {
        val it = pendingRequests.entries.iterator()
        while (it.hasNext()) {
            val entry = it.next()
            val pending = entry.value
            if (nowElapsedMs >= pending.deadlineElapsedMs) {
                it.remove()
                if (pending.completed.compareAndSet(false, true)) {
                    timeoutCount.incrementAndGet()
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
