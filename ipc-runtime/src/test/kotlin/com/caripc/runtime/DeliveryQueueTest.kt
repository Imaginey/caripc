package com.caripc.runtime

import com.caripc.protocol.IpcPayload
import com.caripc.protocol.SubscriptionEnvelope
import org.junit.Assert.*
import org.junit.Test

class DeliveryQueueTest {

    @Test
    fun testPropertyCoalescingInQueue() {
        val queue = DeliveryQueue("sub-1", capacity = 10)

        val env1 = SubscriptionEnvelope(
            "sub-1", "inst-1", SubscriptionEnvelope.KIND_PROPERTY,
            "target_temp", 0L, 10L, IpcPayload.ofFloat(20.0f), 1, 1000L, null, null, 0, false, null
        )
        val env2 = SubscriptionEnvelope(
            "sub-1", "inst-1", SubscriptionEnvelope.KIND_PROPERTY,
            "target_temp", 0L, 11L, IpcPayload.ofFloat(22.0f), 1, 1010L, null, null, 0, false, null
        )
        val env3 = SubscriptionEnvelope(
            "sub-1", "inst-1", SubscriptionEnvelope.KIND_PROPERTY,
            "target_temp", 0L, 12L, IpcPayload.ofFloat(24.0f), 1, 1020L, null, null, 0, false, null
        )

        assertTrue(queue.enqueue(env1))
        assertTrue(queue.enqueue(env2))
        assertTrue(queue.enqueue(env3))

        // 由于同 Key 普通属性合并，队列中应只有 1 个元素（最新值 24.0f）
        assertEquals(1, queue.size())
        assertEquals(2L, queue.coalescedPropertyCount)

        val batch = queue.pollBatch(10)
        assertEquals(1, batch.size)
        assertEquals(24.0f, batch[0].payload.toValue())
        assertEquals(1L, batch[0].deliverySeq) // 检验 deliverySeq 分配
    }

    @Test
    fun testSnapshotNotCoalesced() {
        val queue = DeliveryQueue("sub-2", capacity = 10)

        val snap1 = SubscriptionEnvelope(
            "sub-2", "inst-1", SubscriptionEnvelope.KIND_PROPERTY,
            "temp", 0L, 1L, IpcPayload.ofFloat(20.0f), 1, 1000L, null, "snap-1", 0, false, null
        )
        val snap2 = SubscriptionEnvelope(
            "sub-2", "inst-1", SubscriptionEnvelope.KIND_PROPERTY,
            "temp", 0L, 2L, IpcPayload.ofFloat(21.0f), 1, 1000L, null, "snap-1", 1, true, null
        )

        assertTrue(queue.enqueue(snap1))
        assertTrue(queue.enqueue(snap2))

        // 初始快照带有 snapshotId，禁止合并！
        assertEquals(2, queue.size())
    }

    @Test
    fun testCapacityAndEventDropping() {
        val queue = DeliveryQueue("sub-3", capacity = 2)

        val event1 = SubscriptionEnvelope(
            "sub-3", "inst-1", SubscriptionEnvelope.KIND_EVENT,
            "evt", 0L, 1L, IpcPayload.ofString("e1"), 1, 1000L, null, null, 0, false, null
        )
        val event2 = SubscriptionEnvelope(
            "sub-3", "inst-1", SubscriptionEnvelope.KIND_EVENT,
            "evt", 0L, 2L, IpcPayload.ofString("e2"), 1, 1001L, null, null, 0, false, null
        )
        val event3 = SubscriptionEnvelope(
            "sub-3", "inst-1", SubscriptionEnvelope.KIND_EVENT,
            "evt", 0L, 3L, IpcPayload.ofString("e3"), 1, 1002L, null, null, 0, false, null
        )

        assertTrue(queue.enqueue(event1))
        assertTrue(queue.enqueue(event2))
        assertFalse("Third event must be rejected when capacity reached", queue.enqueue(event3))

        assertEquals(1L, queue.droppedEventCount)
    }
}
