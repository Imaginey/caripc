package com.caripc.runtime

import com.caripc.contract.*
import com.caripc.protocol.IpcPayload
import com.caripc.protocol.SubscriptionEnvelope
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * 服务端权威状态存储。
 *
 * 设计要点（对应工单 P1-2 / P1-4）：
 * 1. 权威值、版本、通知基线与「发射队列」全部由同一把锁保护，任何通知帧都在持锁期间生成并入队，
 *    因此同一订阅收到的帧顺序与版本推进顺序一致。
 * 2. 被死区/限频抑制的变化不会丢：记入待发集合，由 [scheduler] 在窗口到期后用**当前权威值**重判，
 *    合格则补发（规格 5.4 规则 3）。
 * 3. 通知基线只在**真正产生通知帧**时推进，不在每次 update 时推进（规格 5.4 规则 6）。
 * 4. 限频计时使用运行时单调时钟，不使用业务传入的 sourceElapsedMs（业务可能传传感器时间戳）。
 */
class StateStore(
    val serviceInstanceId: String,
    private val scheduler: TimeoutScheduler? = null,
    private val sink: ((SubscriptionEnvelope) -> Unit)? = null
) {

    companion object {
        /** 限频窗口到期后的补发检查周期。 */
        const val TRAILING_EDGE_TICK_MS = 20L
    }

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

    private class PendingNotify(
        var causeOperationId: String?,
        var revision: Long
    )

    private val properties = ConcurrentHashMap<String, StoredProperty>()
    private val pending = HashMap<String, PendingNotify>()
    private val emissionQueue = ArrayDeque<SubscriptionEnvelope>()
    private val globalRevision = AtomicLong(1L)
    private var emitting = false
    private var tick: ScheduledTick? = null
    private var closed = false
    private val lock = Any()

    /** 允许 SessionManager 在同一临界区内完成「读快照 + 注册订阅」，避免与并发更新倒序。 */
    fun <R> withStateLock(block: () -> R): R = synchronized(lock) { block() }

    fun registerProperty(key: PropertyKey<*>) {
        synchronized(lock) {
            if (!properties.containsKey(key.id)) {
                IpcLog.d("StateStore", "registerProperty: ${key.id} (serviceInstanceId=$serviceInstanceId)")
                properties[key.id] = StoredProperty(
                    key = key,
                    value = null,
                    quality = Quality.UNINITIALIZED,
                    revision = 0L,
                    sourceElapsedMs = TimeProvider.elapsedRealtime(),
                    writeRevision = 1L
                )
            }
        }
    }

    /**
     * 更新权威值。
     *
     * @return 需要**立即**广播的通知帧；被过滤时返回 null（若配置了 scheduler + sink，稍后由补发逻辑发出）。
     */
    fun <T : Any> update(
        key: PropertyKey<T>,
        value: T,
        quality: Quality = Quality.VALID,
        sourceElapsedMs: Long = TimeProvider.elapsedRealtime(),
        causeOperationId: String? = null
    ): SubscriptionEnvelope? {
        key.validate(value)
        var immediate: SubscriptionEnvelope? = null
        synchronized(lock) {
            checkNotClosed()
            val stored = properties[key.id] ?: StoredProperty(
                key = key,
                value = value,
                quality = quality,
                revision = globalRevision.getAndIncrement(),
                sourceElapsedMs = sourceElapsedMs,
                writeRevision = 1L
            ).also { properties[key.id] = it }

            // 推进版本与权威值更新
            val newRevision = globalRevision.getAndIncrement()
            stored.value = value
            stored.quality = quality
            stored.revision = newRevision
            stored.sourceElapsedMs = sourceElapsedMs
            stored.writeRevision++

            val policy = key.notificationPolicy
            val now = TimeProvider.elapsedRealtime()
            val shouldNotify = policy == null || quality != Quality.VALID || stored.lastNotifiedValue == null ||
                passesPolicyLocked(stored, value, policy, now)

            if (shouldNotify) {
                pending.remove(key.id)
                IpcLog.i("StateStore", "update: ${key.id} = $value (rev=$newRevision, quality=$quality)")
                stored.lastNotifiedElapsedMs = now
                stored.lastNotifiedValue = value
                val envelope = buildPropertyEnvelopeLocked(stored, value, quality, sourceElapsedMs, causeOperationId)
                immediate = envelope
                enqueueEmissionLocked(envelope)
            } else {
                // 被过滤：记录待发候选，窗口到期后用最新权威值重判（不丢最后一个合格变化）
                pending[key.id] = PendingNotify(causeOperationId, newRevision)
                IpcLog.d(
                    "StateStore",
                    "Property ${key.id} value=$value stored (rev=$newRevision) but notification deferred by policy"
                )
                ensureTrailingEdgeTickLocked()
            }
        }
        drainEmissions()
        return immediate
    }

    /** 发射一次事件（与属性更新共用同一条有序发射管线，保证事件与状态之间的相对顺序）。 */
    fun emitEvent(
        eventKeyId: String,
        payload: IpcPayload,
        sourceElapsedMs: Long = TimeProvider.elapsedRealtime()
    ): SubscriptionEnvelope {
        val envelope = SubscriptionEnvelope(
            "",
            serviceInstanceId,
            SubscriptionEnvelope.KIND_EVENT,
            eventKeyId,
            0L,
            0L,
            payload,
            Quality.VALID.value,
            sourceElapsedMs,
            null,
            null,
            0,
            false,
            null
        )
        synchronized(lock) {
            checkNotClosed()
            enqueueEmissionLocked(envelope)
        }
        drainEmissions()
        return envelope
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
            return snapshotLocked(stored, key as PropertyKey<Any>) as PropertySnapshot<T>
        }
    }

    /**
     * 按 id 读取服务端**真实注册**的 key（不再用 createString 临时构造 key，见工单 P2-11）。
     * 未注册返回 null。
     */
    fun readSnapshotById(keyId: String): PropertySnapshot<Any>? {
        synchronized(lock) {
            val stored = properties[keyId] ?: return null
            @Suppress("UNCHECKED_CAST")
            return snapshotLocked(stored, stored.key as PropertyKey<Any>)
        }
    }

    fun readAllSnapshots(keys: List<String>): List<PropertySnapshot<*>> {
        synchronized(lock) {
            return keys.mapNotNull { keyId ->
                properties[keyId]?.let { stored ->
                    @Suppress("UNCHECKED_CAST")
                    snapshotLocked(stored, stored.key as PropertyKey<Any>)
                }
            }
        }
    }

    fun writeTokenOf(keyId: String): String? = synchronized(lock) {
        properties[keyId]?.writeToken(serviceInstanceId)
    }

    fun containsKey(keyId: String): Boolean = properties.containsKey(keyId)

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

    fun close() {
        synchronized(lock) {
            closed = true
            cancelTickLocked()
            pending.clear()
            emissionQueue.clear()
            emitting = false
        }
    }

    // ---------------- 内部实现 ----------------

    private fun checkNotClosed() {
        if (closed) throw IpcError(ErrorCode.SERVICE_CLOSED, "StateStore is closed")
    }

    private fun snapshotLocked(stored: StoredProperty, key: PropertyKey<Any>): PropertySnapshot<Any> {
        return PropertySnapshot(
            key = key,
            value = stored.value,
            quality = stored.quality,
            revision = stored.revision,
            sourceElapsedMs = stored.sourceElapsedMs,
            serviceInstanceId = serviceInstanceId,
            writeToken = stored.writeToken(serviceInstanceId)
        )
    }

    private fun passesPolicyLocked(
        stored: StoredProperty,
        value: Any?,
        policy: NotificationPolicy,
        nowElapsedMs: Long
    ): Boolean {
        if (policy.minDelta > 0.0 && value is Number) {
            val lastNumber = (stored.lastNotifiedValue as? Number)?.toDouble()
            if (lastNumber != null) {
                val delta = Math.abs(value.toDouble() - lastNumber)
                if (delta < policy.minDelta) {
                    IpcLog.d(
                        "StateStore",
                        "Property ${stored.key.id} suppressed by deadband (delta=$delta < minDelta=${policy.minDelta})"
                    )
                    return false
                }
            }
        }
        if (policy.minNotificationIntervalMs > 0) {
            val elapsedSinceLast = nowElapsedMs - stored.lastNotifiedElapsedMs
            if (elapsedSinceLast < policy.minNotificationIntervalMs) {
                IpcLog.d(
                    "StateStore",
                    "Property ${stored.key.id} suppressed by throttle (${elapsedSinceLast}ms < ${policy.minNotificationIntervalMs}ms)"
                )
                return false
            }
        }
        return true
    }

    private fun buildPropertyEnvelopeLocked(
        stored: StoredProperty,
        value: Any?,
        quality: Quality,
        sourceElapsedMs: Long,
        causeOperationId: String?
    ): SubscriptionEnvelope {
        return SubscriptionEnvelope(
            "", // 由调用方填充 subscriptionId
            serviceInstanceId,
            SubscriptionEnvelope.KIND_PROPERTY,
            stored.key.id,
            0L, // 由 DeliveryQueue 出队时推进 deliverySeq
            stored.revision,
            IpcPayload.ofAny(value),
            quality.value,
            sourceElapsedMs,
            causeOperationId,
            null,
            0,
            false,
            null
        )
    }

    private fun enqueueEmissionLocked(envelope: SubscriptionEnvelope) {
        emissionQueue.addLast(envelope)
    }

    /** 单飞 drainer：保证 sink 按入队顺序被串行调用。 */
    private fun drainEmissions() {
        synchronized(lock) {
            if (emitting) return
            emitting = true
        }
        while (true) {
            val batch: List<SubscriptionEnvelope> = synchronized(lock) {
                if (emissionQueue.isEmpty()) {
                    emitting = false
                    return
                }
                ArrayList(emissionQueue).also { emissionQueue.clear() }
            }
            for (envelope in batch) {
                val target = sink
                if (target == null) continue
                try {
                    target(envelope)
                } catch (t: Throwable) {
                    IpcLog.e("StateStore", "emission sink failed for ${envelope.capabilityId}", t)
                }
            }
        }
    }

    private fun ensureTrailingEdgeTickLocked() {
        if (tick != null || closed) return
        val schedulerRef = scheduler ?: return
        if (sink == null) {
            IpcLog.w(
                "StateStore",
                "Notification policy suppressed an update for ${pending.keys} but no emission sink is configured; deferred value cannot be delivered"
            )
            return
        }
        tick = schedulerRef.schedulePeriodic(TRAILING_EDGE_TICK_MS) { flushPending() }
    }

    private fun cancelTickLocked() {
        tick?.cancel()
        tick = null
    }

    /** 限频窗口到期后的补发检查（可能由调度线程调用）。 */
    internal fun flushPending() {
        val emissions = mutableListOf<SubscriptionEnvelope>()
        synchronized(lock) {
            if (closed || pending.isEmpty()) {
                cancelTickLocked()
                return
            }
            val now = TimeProvider.elapsedRealtime()
            val iterator = pending.entries.iterator()
            while (iterator.hasNext()) {
                val entry = iterator.next()
                val stored = properties[entry.key]
                if (stored == null || stored.value == null) {
                    iterator.remove()
                    continue
                }
                val policy = stored.key.notificationPolicy
                if (policy == null) {
                    iterator.remove()
                    continue
                }
                // 死区：到期时若已回到死区内，允许不发（规格 5.4 规则 4）
                if (policy.minDelta > 0.0 && stored.lastNotifiedValue != null) {
                    val lastNumber = (stored.lastNotifiedValue as? Number)?.toDouble()
                    val currentNumber = (stored.value as? Number)?.toDouble()
                    if (lastNumber != null && currentNumber != null &&
                        Math.abs(currentNumber - lastNumber) < policy.minDelta
                    ) {
                        IpcLog.d("StateStore", "Deferred notification for ${entry.key} dropped: value returned inside deadband")
                        iterator.remove()
                        continue
                    }
                }
                // 限频：窗口未到则保留待发，下个 tick 再判（不能在此清除，否则会丢最后一个合格变化）
                if (!passesPolicyLocked(stored, stored.value, policy, now)) {
                    continue
                }
                stored.lastNotifiedElapsedMs = now
                stored.lastNotifiedValue = stored.value
                IpcLog.i(
                    "StateStore",
                    "Deferred notification flushed: ${entry.key} = ${stored.value} (rev=${stored.revision})"
                )
                val envelope = buildPropertyEnvelopeLocked(
                    stored,
                    stored.value,
                    stored.quality,
                    stored.sourceElapsedMs,
                    entry.value.causeOperationId
                )
                emissions.add(envelope)
                iterator.remove()
            }
            emissions.forEach { emissionQueue.addLast(it) }
            if (pending.isEmpty()) cancelTickLocked()
        }
        if (emissions.isNotEmpty()) drainEmissions()
    }
}
