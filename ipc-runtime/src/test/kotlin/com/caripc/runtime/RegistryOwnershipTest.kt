package com.caripc.runtime

import com.caripc.contract.ErrorCode
import com.caripc.protocol.*
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList

/**
 * 注册中心的归属与配额（工单 P1-6 / P1-7 / P2-7）。
 *
 * 修复前 unwatch 只按 watchId 删除、unpublish 不校验调用者，
 * 任何应用都能注销/顶替他人监听，或注销他人服务。
 */
class RegistryOwnershipTest {

    private class MockEndpoint : IEndpoint.Stub() {
        override fun openSession(hello: ClientHello?, callback: IClientCallback?) {}
    }

    private class RecordingCallback : IRegistryCallback.Stub() {
        val snapshots = CopyOnWriteArrayList<Pair<Long, ServiceDescriptor>>()
        val changes = CopyOnWriteArrayList<Long>()
        val unavailable = CopyOnWriteArrayList<Long>()
        val errors = CopyOnWriteArrayList<String>()
        val tokens = CopyOnWriteArrayList<RegistrationToken>()

        override fun onPublished(token: RegistrationToken) {
            tokens.add(token)
        }

        override fun onPublishFailed(svcId: String?, error: ErrorEnvelope?) {
            errors.add(error?.message ?: "publish failed")
        }

        override fun onSnapshot(svcId: String?, wId: Long, d: ServiceDescriptor?, e: IEndpoint?) {
            if (d != null) snapshots.add(wId to d)
        }

        override fun onServiceUnavailable(svcId: String?, wId: Long) {
            unavailable.add(wId)
        }

        override fun onServiceChanged(svcId: String?, wId: Long, d: ServiceDescriptor?, e: IEndpoint?) {
            changes.add(wId)
        }

        override fun onError(wId: Long, error: ErrorEnvelope?) {
            errors.add(error?.message ?: "error")
        }
    }

    private fun descriptor(serviceId: String, instanceId: String = "inst-1") = ServiceDescriptor(
        serviceId, "test.contract", 1, 0, 1, 0, instanceId, 0L, 0, 0, listOf("prop")
    )

    private fun store() = RegistryStore(PermissionPolicy(TestContext()))

    @Test
    fun testUnwatchFromAnotherUidIsRejected() {
        val store = store()
        val watcherCallback = RecordingCallback()
        store.publish(10001, descriptor("svc.a"), MockEndpoint(), RecordingCallback())
        store.resolveAndWatch(10001, "svc.a", 7L, watcherCallback)
        assertEquals(1, watcherCallback.snapshots.size)

        // 另一个 UID 尝试注销该监听
        store.unwatch(10002, 7L)

        // 监听必须仍然有效：服务下线时应当收到通知
        val token = RegistrationToken("svc.a", "inst-1", -1L, "")
        val owner = RecordingCallback()
        // 用持有者身份重新走一遍发布/注销，验证 watcher 仍然存活
        store.publish(10001, descriptor("svc.a", "inst-2"), MockEndpoint(), owner)
        assertTrue("watcher must survive a foreign unwatch", watcherCallback.changes.isNotEmpty())
    }

    @Test
    fun testWatchIdCannotBeHijackedByAnotherUid() {
        val store = store()
        val first = RecordingCallback()
        val second = RecordingCallback()
        store.publish(10001, descriptor("svc.b"), MockEndpoint(), RecordingCallback())

        store.resolveAndWatch(10001, "svc.b", 9L, first)
        assertEquals(1, first.snapshots.size)

        store.resolveAndWatch(10002, "svc.b", 9L, second)
        assertTrue("cross-UID watchId reuse must be rejected", second.errors.any { it.contains("another UID") })
        assertEquals("hijacking watcher must not receive a snapshot", 0, second.snapshots.size)

        // 原 watcher 仍然有效
        store.publish(10001, descriptor("svc.b", "inst-2"), MockEndpoint(), RecordingCallback())
        assertTrue(first.changes.contains(9L))
    }

    @Test
    fun testSameUidReregistrationWithSameWatchIdIsIdempotent() {
        val store = store()
        val callback = RecordingCallback()
        store.publish(10001, descriptor("svc.c"), MockEndpoint(), RecordingCallback())
        store.resolveAndWatch(10001, "svc.c", 11L, callback)
        store.resolveAndWatch(10001, "svc.c", 11L, callback)
        assertEquals("re-registration must deliver a fresh snapshot", 2, callback.snapshots.size)
        assertTrue(callback.errors.isEmpty())
    }

    @Test
    fun testUnpublishFromAnotherUidIsRejected() {
        val store = store()
        val owner = RecordingCallback()
        store.publish(10001, descriptor("svc.d"), MockEndpoint(), owner)
        val token = owner.tokens.first()

        // 其他 UID 持 token 注销：必须被拒绝，服务仍然在线
        store.unpublish(10002, token)
        val watcher = RecordingCallback()
        store.resolveAndWatch(10003, "svc.d", 13L, watcher)
        assertEquals("service must still be registered", 1, watcher.snapshots.size)

        // 持有者注销：成功
        store.unpublish(10001, token)
        val afterUnpublish = RecordingCallback()
        store.resolveAndWatch(10003, "svc.d", 14L, afterUnpublish)
        assertTrue(afterUnpublish.snapshots.isEmpty())
    }

    @Test
    fun testWatcherQuotaPerUidIsEnforced() {
        val store = store()
        store.publish(10001, descriptor("svc.e"), MockEndpoint(), RecordingCallback())
        val callback = RecordingCallback()
        repeat(RegistryStore.MAX_WATCHERS_PER_UID) { index ->
            store.resolveAndWatch(10001, "svc.e", index.toLong(), callback)
        }
        val overflow = RecordingCallback()
        store.resolveAndWatch(10001, "svc.e", 9_999L, overflow)
        assertTrue(
            "exceeding watcher quota must be reported",
            overflow.errors.any { it.contains("too many") || it.contains("max") }
        )
    }

    @Test
    fun testPublishQuotaPerUidIsEnforced() {
        val store = store()
        repeat(RegistryStore.MAX_SERVICES_PER_UID) { index ->
            store.publish(10001, descriptor("svc.many.$index"), MockEndpoint(), RecordingCallback())
        }
        val overflow = RecordingCallback()
        store.publish(10001, descriptor("svc.overflow"), MockEndpoint(), overflow)
        assertTrue(
            "exceeding service quota must be reported",
            overflow.errors.any { it.contains("already published") }
        )
    }

    @Test
    fun testDuplicateServiceIdFromDifferentUidIsRejected() {
        val store = store()
        store.publish(10001, descriptor("svc.f"), MockEndpoint(), RecordingCallback())
        val intruder = RecordingCallback()
        store.publish(10002, descriptor("svc.f"), MockEndpoint(), intruder)
        assertTrue(
            "service id hijack must be rejected",
            intruder.errors.any { it.contains("already owned") }
        )
    }

    @Test
    fun testDumpIncludesWatcherOwnership() {
        val store = store()
        store.publish(10001, descriptor("svc.g"), MockEndpoint(), RecordingCallback())
        store.resolveAndWatch(10001, "svc.g", 21L, RecordingCallback())
        val dump = store.dump()
        assertTrue(dump.contains("WatchId: 21"))
        assertTrue(dump.contains("svc.g"))
    }
}
