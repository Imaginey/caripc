package com.caripc.contract

enum class ErrorCode(val code: Int) {
    OK(0),
    SERVICE_UNAVAILABLE(1),
    CONNECTION_LOST(2),
    PERMISSION_DENIED(3),
    SERVICE_ID_CONFLICT(4),
    VERSION_MISMATCH(5),
    UNKNOWN_CAPABILITY(6),
    TYPE_MISMATCH(7),
    INVALID_ARGUMENT(8),
    READ_ONLY(9),
    UNINITIALIZED(10),
    DATA_UNAVAILABLE(11),
    TIMEOUT(12),
    CANCELLED(13),
    TOO_MANY_REQUESTS(14),
    RESOURCE_EXHAUSTED(15),
    PAYLOAD_LARGE(16),
    SLOW_CONSUMER(17),
    EVENT_GAP(18),
    SERVICE_CLOSED(19),
    INTERNAL_ERROR(20),
    CONCURRENT_CONFLICT(21),
    STALE_INSTANCE(22),
    CAPABILITY_NOT_SUPPORTED(23),
    OPERATION_EXPIRED(24);

    companion object {
        @JvmStatic
        fun fromCode(code: Int): ErrorCode = values().firstOrNull { it.code == code } ?: INTERNAL_ERROR
    }
}

enum class CompletionState {
    NOT_EXECUTED,
    MAYBE_EXECUTED,
    EXECUTED,
    UNKNOWN
}

/**
 * 中间件统一错误载体。
 *
 * 继承 [RuntimeException]（非受检）：它主要作为异步回调的错误载荷与内部信号使用，
 * 列入方法签名会给 Java 调用方带来纯噪音。需要显式处理时仍可 try/catch。
 *
 * @JvmOverloads 让 Java 可以 `new IpcError(ErrorCode.X, "msg")` 两参构造，无需写满全部字段。
 */
data class IpcError @JvmOverloads constructor(
    val code: ErrorCode,
    override val message: String,
    val requestId: String? = null,
    val completionState: CompletionState = CompletionState.UNKNOWN,
    val currentWriteToken: String? = null
) : RuntimeException("[$code] $message (req=$requestId, state=$completionState)")
