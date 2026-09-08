package com.caripc.runtime

import android.os.SystemClock
import com.caripc.contract.*
import com.caripc.protocol.*
import org.junit.Assert.*
import org.junit.Test
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

class EndToEndIpcTest {

    @Test
    fun testEndToEndPublishDiscoverSetGetAndSubscribe() {
        val outboundExecutor = OutboundExecutor(2, 4)

        // 1. 启动服务 B：发布空调服务
        val instanceIdB = "inst-b-1"
        val stateStoreB = StateStore(instanceIdB)
        val tempKey = PropertyKey.createFloat("target_temperature", min = 16f, max = 32f)
        val fanKey = PropertyKey.createInt("fan_speed", min = 0, max = 7)

        stateStoreB.registerProperty(tempKey)
        stateStoreB.registerProperty(fanKey)
        stateStoreB.update(tempKey, 22.0f)
        stateStoreB.update(fanKey, 1)

        val descriptorB = ServiceDescriptor(
            "com.company.vehicle.climate",
            "vehicle.climate",
            1, 0, 1, 0,
            instanceIdB, 100L, 10001, 0,
            listOf("target_temperature", "fan_speed", "start_self_test", "self_test_finished")
        )

        val dummyPolicy = PermissionPolicy(TestContext())

        var serverBTargetTemp = 22.0f
        val sessionMgrB = SessionManager(
            descriptorB,
            stateStoreB,
            dummyPolicy,
            outboundExecutor,
            onSetHandler = { keyId, value, _ ->
                if (keyId == "target_temperature") {
                    serverBTargetTemp = (value as Number).toFloat()
                    SetReceipt.accepted()
                } else SetReceipt.accepted()
            },
            onCallHandler = { cmdId, param, _ ->
                if (cmdId == "start_self_test") {
                    "RECEIPT_$param"
                } else null
            }
        )

        val endpointB = EndpointHost(sessionMgrB)

        // 2. 模拟客户端 A openSession 连接 B
        val openedLatch = CountDownLatch(1)
        val resultLatch = CountDownLatch(1)
        val subMessageLatch = CountDownLatch(2) // 1 initial snapshot + 1 real update

        var activeSessionA: ISession? = null
        val lastReceivedTemp = AtomicReference<Float>()
        val selfTestResult = AtomicReference<String>()

        val clientCallbackA = object : IClientCallback.Stub() {
            override fun onSessionOpened(hello: ServerHello, session: ISession) {
                activeSessionA = session
                openedLatch.countDown()
            }

            override fun onSessionRejected(error: ErrorEnvelope) {
                fail("Session rejected: ${error.message}")
            }

            override fun onResult(response: ResponseEnvelope) {
                if (response.operationId != null || response.status == ResponseEnvelope.STATUS_ACCEPTED) {
                    resultLatch.countDown()
                } else if (response.payload != null) {
                    selfTestResult.set(response.payload.toValue() as? String)
                    resultLatch.countDown()
                }
            }

            override fun onSubscriptionMessage(message: SubscriptionEnvelope) {
                if (message.kind == SubscriptionEnvelope.KIND_PROPERTY && message.capabilityId == "target_temperature") {
                    val raw = message.payload?.toValue() as? Float
                    if (raw != null) {
                        lastReceivedTemp.set(raw)
                        subMessageLatch.countDown()
                    }
                }
            }
        }

        endpointB.openSession(
            ClientHello(1, 0, 1, 0, "client-a-inst", UUID.randomUUID().toString()),
            clientCallbackA
        )

        assertTrue("Session must open within 2s", openedLatch.await(2, TimeUnit.SECONDS))
        assertNotNull(activeSessionA)

        // 3. 客户端 A 订阅目标温度并接收初始快照
        val subId = "sub-a-1"
        activeSessionA?.subscribe(
            SubscribeRequest(subId, listOf("target_temperature"), true, 16)
        )

        // 等待初始快照（值为 22.0f）
        Thread.sleep(200)
        assertEquals(22.0f, lastReceivedTemp.get())

        // 4. 客户端 A 发起 set(target_temperature, 26.5f)
        val setReqId = "req-set-1"
        activeSessionA?.request(
            RequestEnvelope(
                setReqId,
                RequestEnvelope.OP_SET,
                "target_temperature",
                IpcPayload.ofFloat(26.5f),
                TimeProvider.elapsedRealtime() + 3000L,
                null,
                null
            )
        )

        assertTrue("Set request must receive response", resultLatch.await(2, TimeUnit.SECONDS))
        assertEquals(26.5f, serverBTargetTemp)

        // 5. 服务端 B 模拟底层反馈 update 真实状态
        val updateEnv = stateStoreB.update(tempKey, 26.5f)
        assertNotNull(updateEnv)
        sessionMgrB.broadcastPropertyUpdate(updateEnv!!)

        assertTrue("Subscriber must receive real update", subMessageLatch.await(2, TimeUnit.SECONDS))
        assertEquals(26.5f, lastReceivedTemp.get())

        // 6. 客户端 A 调用命令 start_self_test
        val callLatch = CountDownLatch(1)
        val callCallback = object : IClientCallback.Stub() {
            override fun onSessionOpened(hello: ServerHello?, session: ISession?) {}
            override fun onSessionRejected(error: ErrorEnvelope?) {}
            override fun onResult(response: ResponseEnvelope) {
                selfTestResult.set(response.payload?.toValue() as? String)
                callLatch.countDown()
            }
            override fun onSubscriptionMessage(message: SubscriptionEnvelope?) {}
        }
        val sessionStub = sessionMgrB.createSession(
            android.os.Binder.getCallingUid(),
            ClientHello(1, 0, 1, 0, "client-cmd", UUID.randomUUID().toString()),
            callCallback
        )
        sessionStub.request(
            RequestEnvelope(
                "cmd-1",
                RequestEnvelope.OP_CALL,
                "start_self_test",
                IpcPayload.ofString("DIAG_MODE"),
                TimeProvider.elapsedRealtime() + 3000L,
                null,
                null
            )
        )

        assertTrue("Call command must finish", callLatch.await(2, TimeUnit.SECONDS))
        assertEquals("RECEIPT_DIAG_MODE", selfTestResult.get())

        // 7. 清理
        activeSessionA?.close()
        sessionMgrB.closeAll()
        outboundExecutor.shutdown()
    }

    @Test
    fun testBundlePayload() {
        val bundle = android.os.Bundle()
        val payload = IpcPayload.ofBundle(bundle)
        assertEquals(ValueType.BUNDLE.typeTag, payload.typeTag)
        val value = payload.toValue()
        assertTrue(value is android.os.Bundle)

        val ofAnyPayload = IpcPayload.ofAny(bundle)
        assertNotNull(ofAnyPayload)
        assertEquals(ValueType.BUNDLE.typeTag, ofAnyPayload!!.typeTag)
        assertTrue(ofAnyPayload.toValue() is android.os.Bundle)

        val bundleKey = PropertyKey.createBundle<android.os.Bundle>("test.bundle.key")
        assertEquals(ValueType.BUNDLE, bundleKey.type)
        bundleKey.validate(bundle)
    }
}
