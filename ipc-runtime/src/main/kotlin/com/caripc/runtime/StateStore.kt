package com.caripc.runtime

import android.os.SystemClock
import com.caripc.contract.*
import com.caripc.protocol.IpcPayload
import com.caripc.protocol.SubscriptionEnvelope
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

class StateStore(val serviceInstanceId: String) {

    private class StoredProperty(
        val key: PropertyKey<*>,
        var value: Any?,
        var quality: Quality,
        var revision: Long,
        var sourceElapsedMs: Long,
        var writeRevision: Long,
        var lastNotifiedElapsedMs: Long = 0L,
        var lastNotifiedValue: Any? = null
    ) {
        fun writeToken(instanceId: String): String = "$instanceId:${key.id}:$writeRevision"
    }

    private val properties = ConcurrentHashMap<String, StoredProperty>()
    private val globalRevision = AtomicLong(1L)
    private val lock = Any()

    fun registerProperty(key: PropertyKey<*>) {
        synchronized(lock) {
            if (!properties.containsKey(key.id)) {
                IpcLog.d("StateStore", "registerProperty: ${key.id} (serviceInstanceId=$serviceInstanceId)")
                val stored = StoredProperty(
                    key = key,
                    value = null,
                    quality = Quality.UNINITIALIZED,
                    revision = 0L,
                    sourceElapsedMs = TimeProvider.elapsedRealtime(),
                    writeRevision = 1L
                )
                properties[key.id] = stored
            }
        }
    }

    fun <T : Any> update(
        key: PropertyKey<T>,
        value: T,
        quality: Quality = Quality.VALID,
        sourceElapsedMs: Long = TimeProvider.elapsedRealtime(),
        causeOperationId: String? = null
    ): SubscriptionEnvelope? {
        key.validate(value)

        synchronized(lock) {
            val stored = properties[key.id] ?: run {
                val prop = StoredProperty(
                    key = key,
                    value = value,
                    quality = quality,
                    revision = globalRevision.getAndIncrement(),
                    sourceElapsedMs = sourceElapsedMs,
                    writeRevision = 1L
                )
                properties[key.id] = prop
                prop
            }

            // 推进版本与权威值更新
            val newRevision = globalRevision.getAndIncrement()
            stored.value = value
            stored.quality = quality
            stored.revision = newRevision
            stored.sourceElapsedMs = sourceElapsedMs
            stored.writeRevision++

            // 死区与限频策略：过滤通知，但不过滤权威存储
            val policy = key.notificationPolicy
            var shouldNotify = true

            if (policy != null && quality == Quality.VALID && stored.lastNotifiedValue != null) {
                // 1. 绝对死区判断
                if (policy.minDelta > 0.0) {
                    val delta = when (value) {
                        is Number -> {
                            val lastNum = (stored.lastNotifiedValue as? Number)?.toDouble() ?: 0.0
                            Math.abs(value.toDouble() - lastNum)
                        }
                        else -> Double.MAX_VALUE
                    }
                    if (delta < policy.minDelta) {
                        shouldNotify = false
                        IpcLog.d("StateStore", "Property ${key.id} value=$value updated in store, but notification suppressed by deadband (delta=$delta < minDelta=${policy.minDelta})")
                    }
                }

                // 2. 限频间隔判断
                if (shouldNotify && policy.minNotificationIntervalMs > 0) {
                    val elapsedSinceLast = sourceElapsedMs - stored.lastNotifiedElapsedMs
                    if (elapsedSinceLast < policy.minNotificationIntervalMs) {
                        shouldNotify = false
                        IpcLog.d("StateStore", "Property ${key.id} value=$value updated in store, but notification suppressed by throttle (${elapsedSinceLast}ms < ${policy.minNotificationIntervalMs}ms)")
                    }
                }
            }

            if (shouldNotify) {
                IpcLog.i("StateStore", "update: ${key.id} = $value (rev=$newRevision, quality=$quality)")
                stored.lastNotifiedElapsedMs = sourceElapsedMs
                stored.lastNotifiedValue = value

                val payload = IpcPayload.ofAny(value)
                return SubscriptionEnvelope(
                    "", // 由调用方填充 subscriptionId
                    serviceInstanceId,
                    SubscriptionEnvelope.KIND_PROPERTY,
                    key.id,
                    0L, // 由调用方推进 deliverySeq
                    newRevision,
                    payload,
                    quality.value,
                    sourceElapsedMs,
                    causeOperationId,
                    null,
                    0,
                    false,
                    null
                )
            } else {
                return null
            }
        }
    }

    @Suppress("UNCHECKED_CAST")
    fun <T : Any> readSnapshot(key: PropertyKey<T>): PropertySnapshot<T> {
        synchronized(lock) {
            val stored = properties[key.id]
            if (stored == null) {
                return PropertySnapshot(
                    key = key,
                    value = null,
                    quality = Quality.UNINITIALIZED,
                    revision = 0L,
                    sourceElapsedMs = TimeProvider.elapsedRealtime(),
                    serviceInstanceId = serviceInstanceId,
                    writeToken = null
                )
            }
            return PropertySnapshot(
                key = key,
                value = stored.value as? T,
                quality = stored.quality,
                revision = stored.revision,
                sourceElapsedMs = stored.sourceElapsedMs,
                serviceInstanceId = serviceInstanceId,
                writeToken = stored.writeToken(serviceInstanceId)
            )
        }
    }

    fun readAllSnapshots(keys: List<String>): List<PropertySnapshot<*>> {
        synchronized(lock) {
            return keys.mapNotNull { keyId ->
                val stored = properties[keyId]
                if (stored != null) {
                    @Suppress("UNCHECKED_CAST")
                    PropertySnapshot(
                        key = stored.key as PropertyKey<Any>,
                        value = stored.value,
                        quality = stored.quality,
                        revision = stored.revision,
                        sourceElapsedMs = stored.sourceElapsedMs,
                        serviceInstanceId = serviceInstanceId,
                        writeToken = stored.writeToken(serviceInstanceId)
                    )
                } else null
            }
        }
    }

    fun validateAndAdvanceWriteToken(keyId: String, expectedToken: String?): String {
        synchronized(lock) {
            val stored = properties[keyId] ?: throw IpcError(
                ErrorCode.UNKNOWN_CAPABILITY,
                "Capability $keyId not found in store"
            )
            val currentToken = stored.writeToken(serviceInstanceId)
            if (expectedToken != null && expectedToken != currentToken) {
                throw IpcError(
                    ErrorCode.CONCURRENT_CONFLICT,
                    "Write token mismatch for $keyId. Current: $currentToken, Expected: $expectedToken",
                    currentWriteToken = currentToken
                )
            }
            stored.writeRevision++
            return stored.writeToken(serviceInstanceId)
        }
    }
}
