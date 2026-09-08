package com.caripc.contract

/**
 * Java 友好的标准两方法异步结果回调
 */
interface IpcCallback<T> {
    fun onSuccess(value: T)
    fun onError(error: IpcError)
}

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

data class SubscribeOptions @JvmOverloads constructor(
    val replayLatest: Boolean = true,
    val windowSize: Int = 16
)
