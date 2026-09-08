package com.caripc.contract

sealed interface SubscriptionMessagePayload

data class PropertyUpdate<T : Any>(
    val key: PropertyKey<T>,
    val snapshot: PropertySnapshot<T>
) : SubscriptionMessagePayload

data class EventEmission<T : Any>(
    val key: EventKey<T>,
    val payload: T,
    val deliverySeq: Long
) : SubscriptionMessagePayload

data class SubscriptionGap(
    val subscriptionId: String,
    val fromSeq: Long,
    val toSeq: Long,
    val reason: String
) : SubscriptionMessagePayload

data class SubscriptionErrorMessage(
    val error: IpcError
) : SubscriptionMessagePayload

interface SubscriptionMessage {
    val subscriptionId: String
    val serviceInstanceId: String
    val payload: SubscriptionMessagePayload

    @Suppress("UNCHECKED_CAST")
    fun <T : Any> valueFor(key: PropertyKey<T>): T? {
        val p = payload as? PropertyUpdate<*> ?: return null
        if (p.key.id == key.id) {
            return p.snapshot.value as? T
        }
        return null
    }

    @Suppress("UNCHECKED_CAST")
    fun <T : Any> snapshotFor(key: PropertyKey<T>): PropertySnapshot<T>? {
        val p = payload as? PropertyUpdate<*> ?: return null
        if (p.key.id == key.id) {
            return p.snapshot as? PropertySnapshot<T>
        }
        return null
    }
}
