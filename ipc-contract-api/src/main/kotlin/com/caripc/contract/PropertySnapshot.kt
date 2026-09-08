package com.caripc.contract

data class PropertySnapshot<T : Any>(
    val key: PropertyKey<T>,
    val value: T?,
    val quality: Quality,
    val revision: Long,
    val sourceElapsedMs: Long,
    val serviceInstanceId: String,
    val writeToken: String? = null
)

enum class SetStatus {
    ACCEPTED,
    APPLIED,
    REJECTED
}

data class SetReceipt(
    val status: SetStatus,
    val operationId: String? = null,
    val writeToken: String? = null,
    val error: IpcError? = null
) {
    val isSuccess: Boolean get() = status != SetStatus.REJECTED

    companion object {
        @JvmStatic
        @JvmOverloads
        fun accepted(writeToken: String? = null, operationId: String? = null): SetReceipt =
            SetReceipt(SetStatus.ACCEPTED, operationId, writeToken)

        @JvmStatic
        @JvmOverloads
        fun applied(writeToken: String? = null, operationId: String? = null): SetReceipt =
            SetReceipt(SetStatus.APPLIED, operationId, writeToken)

        @JvmStatic
        fun rejected(error: IpcError): SetReceipt =
            SetReceipt(SetStatus.REJECTED, null, null, error)
    }
}
