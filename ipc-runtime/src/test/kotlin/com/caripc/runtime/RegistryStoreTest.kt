package com.caripc.runtime

import android.content.Context
import android.os.Binder
import com.caripc.contract.ErrorCode
import com.caripc.protocol.*
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

class RegistryStoreTest {


    private class MockEndpoint : IEndpoint.Stub() {
        override fun openSession(hello: ClientHello?, callback: IClientCallback?) {}
    }

    @Test
    fun testPublishAndWatchSnapshot() {
        val policy = PermissionPolicy(TestContext())
        val store = RegistryStore(policy)

        val desc = ServiceDescriptor(
            "com.test.service", "test.contract", 1, 0, 1, 0,
            "inst-1", 0L, 10001, 0, listOf("prop_a")
        )
        val endpoint = MockEndpoint()

        val publishedToken = AtomicReference<RegistrationToken>()
        store.publish(10001, desc, endpoint, object : IRegistryCallback.Stub() {
            override fun onPublished(token: RegistrationToken) {
                publishedToken.set(token)
            }
            override fun onPublishFailed(svcId: String?, error: ErrorEnvelope?) {}
            override fun onSnapshot(svcId: String?, wId: Long, d: ServiceDescriptor?, e: IEndpoint?) {}
            override fun onServiceUnavailable(svcId: String?, wId: Long) {}
            override fun onServiceChanged(svcId: String?, wId: Long, d: ServiceDescriptor?, e: IEndpoint?) {}
            override fun onError(wId: Long, error: ErrorEnvelope?) {}
        })

        assertNotNull(publishedToken.get())
        assertEquals("com.test.service", publishedToken.get().serviceId)
        assertTrue(publishedToken.get().generation >= 100L)

        // 验证 resolveAndWatch 原子快照返回
        val snapshotDiscovered = AtomicBoolean(false)
        store.resolveAndWatch(10001, "com.test.service", 1L, object : IRegistryCallback.Stub() {
            override fun onPublished(token: RegistrationToken?) {}
            override fun onPublishFailed(svcId: String?, error: ErrorEnvelope?) {}
            override fun onSnapshot(svcId: String?, wId: Long, d: ServiceDescriptor?, e: IEndpoint?) {
                snapshotDiscovered.set(true)
                assertEquals("inst-1", d?.instanceId)
            }
            override fun onServiceUnavailable(svcId: String?, wId: Long) {}
            override fun onServiceChanged(svcId: String?, wId: Long, d: ServiceDescriptor?, e: IEndpoint?) {}
            override fun onError(wId: Long, error: ErrorEnvelope?) {}
        })

        assertTrue(snapshotDiscovered.get())

        // 验证其他 UID 抢占失败
        val conflictReported = AtomicBoolean(false)
        store.publish(10002, desc, MockEndpoint(), object : IRegistryCallback.Stub() {
            override fun onPublished(token: RegistrationToken?) {}
            override fun onPublishFailed(svcId: String?, error: ErrorEnvelope?) {
                if (error?.errorCode == ErrorCode.SERVICE_ID_CONFLICT.code) {
                    conflictReported.set(true)
                }
            }
            override fun onSnapshot(svcId: String?, wId: Long, d: ServiceDescriptor?, e: IEndpoint?) {}
            override fun onServiceUnavailable(svcId: String?, wId: Long) {}
            override fun onServiceChanged(svcId: String?, wId: Long, d: ServiceDescriptor?, e: IEndpoint?) {}
            override fun onError(wId: Long, error: ErrorEnvelope?) {}
        })
        assertTrue("Hijacking by different UID must report SERVICE_ID_CONFLICT", conflictReported.get())
    }
}
