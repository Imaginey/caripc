package com.caripc.runtime

import com.caripc.protocol.SubscriptionEnvelope
import java.util.LinkedList
import java.util.concurrent.atomic.AtomicLong

class DeliveryQueue(
    val subscriptionId: String,
    val capacity: Int = 128
) {
    private val queue = LinkedList<SubscriptionEnvelope>()
    private val deliverySeqCounter = AtomicLong(1L)
    private val lock = Any()
    private var isClosed = false

    var droppedEventCount = 0L
        private set
    var coalescedPropertyCount = 0L
        private set

    fun enqueue(envelope: SubscriptionEnvelope): Boolean {
        synchronized(lock) {
            if (isClosed) return false

            // 1. 如果是普通属性更新，尝试在未发送队列中做状态合并 (按 Key 保留最新值)
            if (envelope.kind == SubscriptionEnvelope.KIND_PROPERTY && envelope.snapshotId == null) {
                val it = queue.listIterator()
                while (it.hasNext()) {
                    val existing = it.next()
                    if (existing.kind == SubscriptionEnvelope.KIND_PROPERTY &&
                        existing.snapshotId == null &&
                        existing.capabilityId == envelope.capabilityId
                    ) {
                        // 合并替换现有项
                        it.set(envelope)
                        coalescedPropertyCount++
                        return true
                    }
                }
            }

            // 2. 检查队列容量
            if (queue.size >= capacity) {
                if (envelope.kind == SubscriptionEnvelope.KIND_EVENT) {
                    droppedEventCount++
                    return false
                }
                // 快照或控制消息必须尽力保留，若真超限则拒绝
                return false
            }

            queue.add(envelope)
            return true
        }
    }

    fun pollBatch(maxItems: Int = 16): List<SubscriptionEnvelope> {
        synchronized(lock) {
            if (isClosed || queue.isEmpty()) return emptyList()
            val list = mutableListOf<SubscriptionEnvelope>()
            for (i in 0 until minOf(maxItems, queue.size)) {
                val item = queue.removeFirst()
                // 出队时为消息正式分配唯一的连续 deliverySeq
                val withSeq = SubscriptionEnvelope(
                    subscriptionId,
                    item.serviceInstanceId,
                    item.kind,
                    item.capabilityId,
                    deliverySeqCounter.getAndIncrement(),
                    item.revision,
                    item.payload,
                    item.quality,
                    item.sourceElapsedMs,
                    item.causeOperationId,
                    item.snapshotId,
                    item.snapshotIndex,
                    item.isSnapshotEnd,
                    item.error
                )
                list.add(withSeq)
            }
            return list
        }
    }

    fun size(): Int = synchronized(lock) { queue.size }

    fun close() {
        synchronized(lock) {
            isClosed = true
            queue.clear()
        }
    }
}
