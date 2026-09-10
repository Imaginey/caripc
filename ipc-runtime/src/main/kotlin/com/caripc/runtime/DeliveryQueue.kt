package com.caripc.runtime

import com.caripc.contract.CompletionState
import com.caripc.contract.ErrorCode
import com.caripc.protocol.ErrorEnvelope
import com.caripc.protocol.SubscriptionEnvelope
import java.util.LinkedList
import java.util.concurrent.atomic.AtomicLong

/**
 * 单订阅有界投递队列。
 *
 * 语义（对应工单 P1-3 / P1-5）：
 * 1. 普通属性帧按 Key 合并，只保留最新值（设计如此，不视为缺口）；
 * 2. 初始快照帧（snapshotId != null）与控制帧不参与合并，容量不足时**明确失败**；
 * 3. 任何帧被丢弃都会在队列中插入一个缺口占位帧：它排在「已入队的帧之后、后续帧之前」，
 *    出队时被填充为真正的 KIND_GAP，订阅者因此能感知数据缺口而不是误以为状态稳定；
 * 4. deliverySeq 在出队（真正进入发送顺序）时分配，单调递增。
 */
class DeliveryQueue(
    val subscriptionId: String,
    val capacity: Int = 128
) {
    private val queue = LinkedList<SubscriptionEnvelope>()
    private val deliverySeqCounter = AtomicLong(1L)
    private val lock = Any()
    private var isClosed = false
    private var lastDeliveredSeq = 0L

    /** 队列中的缺口占位帧（未分配 deliverySeq，deliverySeq < 0）。 */
    private var gapPlaceholder: SubscriptionEnvelope? = null
    private var gapReason: String? = null
    private var gapDroppedCount = 0L

    var droppedEventCount = 0L
        private set
    var droppedFrameCount = 0L
        private set
    var coalescedPropertyCount = 0L
        private set
    var gapNoticeCount = 0L
        private set

    fun enqueue(envelope: SubscriptionEnvelope): Boolean {
        synchronized(lock) {
            if (isClosed) return false

            // 1. 普通属性帧：在未发送队列中按 Key 合并（保留最新）
            if (envelope.kind == SubscriptionEnvelope.KIND_PROPERTY && envelope.snapshotId == null) {
                val it = queue.listIterator()
                while (it.hasNext()) {
                    val existing = it.next()
                    if (existing.kind == SubscriptionEnvelope.KIND_PROPERTY &&
                        existing.snapshotId == null &&
                        existing.capabilityId == envelope.capabilityId
                    ) {
                        it.set(envelope)
                        coalescedPropertyCount++
                        return true
                    }
                }
            }

            // 2. 容量控制
            if (queue.size >= capacity) {
                if (envelope.kind == SubscriptionEnvelope.KIND_EVENT) {
                    droppedEventCount++
                    markGapLocked("event queue overflow")
                } else {
                    droppedFrameCount++
                    markGapLocked("frame queue overflow (snapshot/state frame dropped)")
                }
                return false
            }

            queue.add(envelope)
            return true
        }
    }

    fun pollBatch(maxItems: Int = 16): List<SubscriptionEnvelope> {
        synchronized(lock) {
            if (isClosed) return emptyList()
            val list = mutableListOf<SubscriptionEnvelope>()
            while (list.size < maxItems && queue.isNotEmpty()) {
                val item = queue.removeFirst()
                val seq = deliverySeqCounter.getAndIncrement()
                lastDeliveredSeq = seq
                if (item.kind == SubscriptionEnvelope.KIND_GAP && item.deliverySeq < 0L) {
                    list.add(buildGapEnvelopeLocked(seq))
                    gapPlaceholder = null
                    gapReason = null
                    gapDroppedCount = 0L
                    gapNoticeCount++
                } else {
                    list.add(
                        SubscriptionEnvelope(
                            subscriptionId,
                            item.serviceInstanceId,
                            item.kind,
                            item.capabilityId,
                            seq,
                            item.revision,
                            item.payload,
                            item.quality,
                            item.sourceElapsedMs,
                            item.causeOperationId,
                            item.snapshotId,
                            item.snapshotIndex,
                            item.isSnapshotEnd,
                            item.error,
                            item.gapFromSeq
                        )
                    )
                }
            }
            return list
        }
    }

    fun size(): Int = synchronized(lock) { queue.size }

    fun lastDeliveredSeq(): Long = synchronized(lock) { lastDeliveredSeq }

    fun hasPendingGap(): Boolean = synchronized(lock) { gapPlaceholder != null }

    fun close() {
        synchronized(lock) {
            isClosed = true
            queue.clear()
            gapPlaceholder = null
        }
    }

    /**
     * 在队尾插入缺口占位帧：丢弃发生在「当前已入队帧之后」，
     * 因此缺口通知必须排在它们后面、后续帧前面。
     */
    private fun markGapLocked(reason: String) {
        gapReason = reason
        gapDroppedCount++
        if (gapPlaceholder == null) {
            val placeholder = SubscriptionEnvelope(
                subscriptionId,
                "",
                SubscriptionEnvelope.KIND_GAP,
                "",
                -1L,
                0L,
                null,
                0,
                TimeProvider.elapsedRealtime(),
                null,
                null,
                0,
                false,
                null,
                -1L
            )
            gapPlaceholder = placeholder
            queue.addLast(placeholder)
        }
    }

    private fun buildGapEnvelopeLocked(seq: Long): SubscriptionEnvelope {
        val reason = "delivery gap: ${gapReason ?: "unknown"}, droppedFrames=$gapDroppedCount " +
            "(events=$droppedEventCount, frames=$droppedFrameCount), lastDeliveredSeq=$lastDeliveredSeq"
        return SubscriptionEnvelope(
            subscriptionId,
            "",
            SubscriptionEnvelope.KIND_GAP,
            "",
            seq,
            0L,
            null,
            0,
            TimeProvider.elapsedRealtime(),
            null,
            null,
            0,
            false,
            ErrorEnvelope(
                ErrorCode.EVENT_GAP.code,
                reason,
                null,
                CompletionState.UNKNOWN.ordinal,
                null
            ),
            lastDeliveredSeq
        )
    }
}
