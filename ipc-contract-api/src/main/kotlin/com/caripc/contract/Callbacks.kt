package com.caripc.contract

fun interface ResultCallback<T> {
    fun onResult(result: Result<T>)

    fun onSuccess(value: T) {
        onResult(Result.success(value))
    }

    fun onError(error: IpcError) {
        onResult(Result.failure(error))
    }
}

fun interface CancelHandle {
    fun cancel()
}

data class SubscribeOptions(
    val replayLatest: Boolean = true,
    val windowSize: Int = 16
)
