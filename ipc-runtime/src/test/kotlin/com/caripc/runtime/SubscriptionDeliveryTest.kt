package com.caripc.runtime

import com.caripc.contract.*
import com.caripc.protocol.IpcPayload
import com.caripc.protocol.ResponseEnvelope
import com.caripc.protocol.SubscribeRequest
import com.caripc.protocol.SubscriptionEnvelope
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * 订阅投递语义（工单 P1-2 / P1-3 / P1-4 / P1-5）。
 */
class SubscriptionDeliveryTest {

    private val temperature = PropertyKey.createFloat(
        id = "target_temperature",
        min = 16f,
        max = 32f,
        notificationPolicy = NotificationPolicy(minNotificationIntervalMs = 100L, minDelta = 0.5)
    )
    private val cabin = PropertyKey.createFloat(id = "cabin_temperature", readable = true, writable = false)
    private val event = EventKey.createString("self_test_finished")
    private val command = CommandKey.stringToString("start_self_test")

    private val schema = ServiceSchema(
        contractId = "test.contract",
        major = 1,
        minor = 0,
        properties = listOf(temperature, cabin),
        events = listOf(event),
        commands = listOf(command)
    )

    @Test
    fun testThrottledUpdateIsEventuallyDeliveredAtWindowExpiry() {
        // 修复前：限频抑制后没有到期重发，最后一个合格变化被永久丢弃
        FakeClock().use { clock ->
            val scheduler = ManualTimeoutScheduler()
            SessionHarness(schema, scheduler = scheduler).use { h ->
                h.stateStore.update(temperature, 20.0f) // 首次立即通知
                assertEquals(1, h.emissions.size)

                clock.advance(10)
                h.stateStore.update(temperature, 24.0f) // 窗口内：抑制
                assertEquals("throttled update must not be emitted immediately", 1, h.emissions.size)

                clock.advance(200) // 超过 minNotificationIntervalMs=100
                scheduler.tick()

                assertEquals("last qualified change must be flushed at window expiry", 2, h.emissions.size)
                assertEquals(24.0f, h.emissions[1].payload.toValue())
            }
        }
    }

    @Test
    fun testDeferredUpdateInsideDeadbandIsNotFlushed() {
        val deadbandKey = PropertyKey.createFloat(
            id = "deadband_value",
            notificationPolicy = NotificationPolicy(minNotificationIntervalMs = 100L, minDelta = 5.0)
        )
        val deadbandSchema = ServiceSchema(
            "test.deadband",
            1,
            0,
            properties = listOf(deadbandKey),
            events = emptyList(),
            commands = emptyList()
        )
        FakeClock().use { clock ->
            val scheduler = ManualTimeoutScheduler()
            SessionHarness(deadbandSchema, scheduler = scheduler).use { h ->
                h.stateStore.update(deadbandKey, 1.0f)
                assertEquals(1, h.emissions.size)

                clock.advance(10)
                h.stateStore.update(deadbandKey, 1.2f) // 死区内：抑制
                clock.advance(500)
                scheduler.tick()
                assertEquals("value still inside deadband must not be flushed", 1, h.emissions.size)

                // 权威值仍然更新（过滤不能污染状态）
                assertEquals(1.2f, h.stateStore.readSnapshotById("deadband_value")?.value)
            }
        }
    }

    @Test
    fun testSnapshotFramesPrecedeConcurrentUpdatesAndNoUpdateIsLost() {
        val iterations = 60
        repeat(iterations) { round ->
            SessionHarness(schema).use { h ->
                h.stateStore.update(cabin, 20.0f + round)

                val started = CountDownLatch(1)
                val updater = Thread {
                    started.await()
                    var value = 21.0f
                    repeat(20) {
                        value += 0.5f
                        h.stateStore.update(cabin, value.coerceAtMost(40f))
                        Thread.sleep(1)
                    }
                }
                updater.start()
                started.countDown()

                h.session.subscribe(SubscribeRequest("sub-race-$round", listOf("cabin_temperature"), true, 0))
                updater.join(5_000)

                // 等待队列排空
                var lastRevision = -1L
                repeat(40) {
                    val messages = h.drainSubscriptionMessages(60)
                    messages.filter { it.kind == SubscriptionEnvelope.KIND_PROPERTY }
                        .forEach { frame ->
                            assertTrue(
                                "revision must never go backwards (round=$round, got ${frame.revision} after $lastRevision)",
                                frame.revision >= lastRevision || lastRevision == -1L
                            )
                            lastRevision = frame.revision
                        }
                    if (lastRevision >= (h.stateStore.readSnapshotById("cabin_temperature")?.revision ?: 0L)) return@repeat
                }

                val authoritative = h.stateStore.readSnapshotById("cabin_temperature")!!
                assertEquals(
                    "subscriber must end up at the authoritative revision (round=$round)",
                    authoritative.revision,
                    lastRevision
                )
            }
        }
    }

    @Test
    fun testCoalescingKeepsLatestValueAndDoesNotEmitGap() {
        val queue = DeliveryQueue("sub-coalesce", capacity = 8)
        queue.enqueue(propertyFrame("sub-coalesce", "k", 10L, 1.0f))
        queue.enqueue(propertyFrame("sub-coalesce", "k", 11L, 2.0f))
        queue.enqueue(propertyFrame("sub-coalesce", "k", 12L, 3.0f))

        val batch = queue.pollBatch(8)
        assertEquals(1, batch.size)
        assertEquals(3.0f, batch[0].payload.toValue())
        assertEquals(0L, queue.droppedFrameCount)
        assertEquals(2L, queue.coalescedPropertyCount)
        assertFalse(
            "intentional state coalescing is not a delivery gap",
            batch.any { it.kind == SubscriptionEnvelope.KIND_GAP }
        )
    }

    @Test
    fun testOverflowProducesGapNoticeBeforeSubsequentFrames() {
        // 修复前：丢弃只累加计数，订阅者永远不知道数据有缺口
        val queue = DeliveryQueue("sub-gap", capacity = 2)
        queue.enqueue(eventFrame("sub-gap", "e", 1L))
        queue.enqueue(eventFrame("sub-gap", "e", 2L))
        assertFalse("third frame must be rejected", queue.enqueue(eventFrame("sub-gap", "e", 3L)))
        assertEquals(1L, queue.droppedEventCount)

        val batch = queue.pollBatch(8)
        assertEquals(3, batch.size)
        assertEquals(SubscriptionEnvelope.KIND_EVENT, batch[0].kind)
        assertEquals(SubscriptionEnvelope.KIND_EVENT, batch[1].kind)
        assertEquals("gap notice must be delivered before further data", SubscriptionEnvelope.KIND_GAP, batch[2].kind)
        assertEquals(ErrorCode.EVENT_GAP.code, batch[2].error?.errorCode)
        assertTrue(batch[2].deliverySeq > batch[1].deliverySeq)
        assertTrue(batch[2].gapFromSeq >= batch[1].deliverySeq)
        assertEquals(1L, queue.gapNoticeCount)
    }

    @Test
    fun testDroppedSnapshotFrameIsReportedAsGapAndNeverSilentlyLost() {
        val queue = DeliveryQueue("sub-snap", capacity = 1)
        val snapshotFrame = SubscriptionEnvelope(
            "sub-snap", "inst-1", SubscriptionEnvelope.KIND_PROPERTY, "k", 0L, 5L,
            IpcPayload.ofFloat(1.0f), Quality.VALID.value, 0L, null, "snap-1", 0, false, null
        )
        assertTrue(queue.enqueue(snapshotFrame))
        val second = SubscriptionEnvelope(
            "sub-snap", "inst-1", SubscriptionEnvelope.KIND_PROPERTY, "k2", 0L, 6L,
            IpcPayload.ofFloat(2.0f), Quality.VALID.value, 0L, null, "snap-1", 1, true, null
        )
        assertFalse("snapshot frames must never be dropped silently", queue.enqueue(second))
        assertEquals(1L, queue.droppedFrameCount)

        val batch = queue.pollBatch(8)
        assertTrue(batch.any { it.kind == SubscriptionEnvelope.KIND_GAP })
    }

    @Test
    fun testEventFramesAreDeliveredWithMonotonicDeliverySeq() {
        SessionHarness(schema).use { h ->
            h.session.subscribe(SubscribeRequest("sub-event", listOf("self_test_finished"), true, 0))
            h.drainSubscriptionMessages()

            repeat(5) { index ->
                h.stateStore.emitEvent("self_test_finished", IpcPayload.ofString("evt-$index"))
            }

            val messages = h.awaitSubscriptionMessages(5)
            assertEquals(5, messages.size)
            var previous = 0L
            messages.forEach { frame ->
                assertTrue("deliverySeq must be strictly increasing", frame.deliverySeq > previous)
                previous = frame.deliverySeq
            }
            assertEquals("evt-4", messages.last().payload.toValue())
        }
    }

    @Test
    fun testSubscriberReceivesSnapshotThenUpdateInOrder() {
        SessionHarness(schema).use { h ->
            h.stateStore.update(cabin, 22.0f)
            h.session.subscribe(SubscribeRequest("sub-order", listOf("cabin_temperature"), true, 0))

            val snapshot = h.awaitSubscriptionMessages(1)
            assertEquals(1, snapshot.size)
            assertNotNull("first frame must be the initial snapshot", snapshot[0].snapshotId)
            assertEquals(22.0f, snapshot[0].payload.toValue())

            h.stateStore.update(cabin, 23.5f)
            val update = h.awaitSubscriptionMessages(1)
            assertEquals(1, update.size)
            assertEquals(23.5f, update[0].payload.toValue())
            assertTrue(update[0].deliverySeq > snapshot[0].deliverySeq)
            assertTrue(update[0].revision > snapshot[0].revision)
        }
    }

    @Test
    fun testNoSchedulerStillDeliversImmediateNotifications() {
        SessionHarness(schema).use { h ->
            h.session.subscribe(SubscribeRequest("sub-nosched", listOf("cabin_temperature"), true, 0))
            h.drainSubscriptionMessages()
            h.stateStore.update(cabin, 25.0f)
            val messages = h.awaitSubscriptionMessages(1)
            assertEquals(1, messages.size)
            assertEquals(25.0f, messages[0].payload.toValue())
        }
    }

    @Test
    fun testResponseIsStillDeliveredWhenOutboundExecutorRejects() {
        // 出站执行器被占满时，响应不能静默丢失（工单 P0-3）：应退化为调用线程直接投递
        val saturated = OutboundExecutor(corePoolSize = 1, maxPoolSize = 1, queueCapacity = 1)
        val blocker = CountDownLatch(1)
        saturated.submit { blocker.await(5, TimeUnit.SECONDS) }
        saturated.submit { blocker.await(5, TimeUnit.SECONDS) }

        try {
            val harness = SessionHarness(schema, executor = saturated)
            harness.use { h ->
                val done = AtomicReference<ResponseEnvelope?>()
                h.responses.clear()
                val requestId = "req-saturated"
                h.session.request(
                    com.caripc.protocol.RequestEnvelope(
                        requestId,
                        com.caripc.protocol.RequestEnvelope.OP_GET,
                        "cabin_temperature",
                        null,
                        0L,
                        null,
                        null
                    )
                )
                val response = h.responses.poll(3, TimeUnit.SECONDS)
                assertNotNull("response must not be silently dropped when executor rejects", response)
                assertEquals(requestId, response!!.requestId)
                done.set(response)
            }
        } finally {
            blocker.countDown()
            saturated.shutdown()
        }
    }

    private fun propertyFrame(subId: String, keyId: String, revision: Long, value: Float): SubscriptionEnvelope =
        SubscriptionEnvelope(
            subId, "inst-1", SubscriptionEnvelope.KIND_PROPERTY, keyId, 0L, revision,
            IpcPayload.ofFloat(value), Quality.VALID.value, 0L, null, null, 0, false, null
        )

    private fun eventFrame(subId: String, keyId: String, revision: Long): SubscriptionEnvelope =
        SubscriptionEnvelope(
            subId, "inst-1", SubscriptionEnvelope.KIND_EVENT, keyId, 0L, revision,
            IpcPayload.ofString("evt"), Quality.VALID.value, 0L, null, null, 0, false, null
        )
}
