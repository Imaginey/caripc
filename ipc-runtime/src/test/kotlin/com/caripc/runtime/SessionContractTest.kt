package com.caripc.runtime

import com.caripc.contract.*
import com.caripc.protocol.IpcPayload
import com.caripc.protocol.ISession
import com.caripc.protocol.RequestEnvelope
import com.caripc.protocol.ResponseEnvelope
import com.caripc.protocol.SubscribeRequest
import com.caripc.protocol.SubscriptionEnvelope
import org.junit.Assert.*
import org.junit.Test

/**
 * 服务端契约校验与异常边界（工单 P0-4 / P0-5 / P0-6 / P1-1 / P2-6）。
 *
 * 这些用例在修复前必须失败：修复前未知 keyId、错误线类型、只读属性写入都会落到业务 handler，
 * 业务抛异常时 oneway 事务会把异常吞成「无响应」。
 */
class SessionContractTest {

    private val targetTemperature = PropertyKey.createFloat(
        id = "target_temperature",
        readable = true,
        writable = true,
        observable = true,
        min = 16f,
        max = 32f
    )
    private val cabinTemperature = PropertyKey.createFloat(
        id = "cabin_temperature",
        readable = true,
        writable = false,
        observable = true
    )
    private val internalValue = PropertyKey.createFloat(
        id = "internal_value",
        readable = false,
        writable = true,
        observable = false
    )
    private val selfTestFinished = EventKey.createString("self_test_finished")
    private val startSelfTest = CommandKey.stringToString("start_self_test")
    private val resetAll = CommandKey.createUnit("reset_all")
    private val badReturn = CommandKey.stringToString("bad_return")

    private val schema = ServiceSchema(
        contractId = "test.contract",
        major = 1,
        minor = 0,
        properties = listOf(targetTemperature, cabinTemperature, internalValue),
        events = listOf(selfTestFinished),
        commands = listOf(startSelfTest, resetAll, badReturn)
    )

    @Test
    fun testUnknownCapabilityIsRejectedBeforeBusinessHandler() {
        var handlerCalls = 0
        SessionHarness(schema, onSet = { _, _, _ -> handlerCalls++; SetReceipt.applied() }).use { h ->
            assertEquals(ErrorCode.UNKNOWN_CAPABILITY, h.errorCode(h.request(RequestEnvelope.OP_SET, "not_declared", IpcPayload.ofFloat(20f))))
            assertEquals(ErrorCode.UNKNOWN_CAPABILITY, h.errorCode(h.request(RequestEnvelope.OP_GET, "not_declared", null)))
            assertEquals(ErrorCode.UNKNOWN_CAPABILITY, h.errorCode(h.request(RequestEnvelope.OP_CALL, "not_a_command", IpcPayload.ofString("x"))))
            assertEquals("business handler must not be called for unknown capability", 0, handlerCalls)
        }
    }

    @Test
    fun testWriteToReadOnlyPropertyIsRejected() {
        SessionHarness(schema).use { h ->
            val response = h.request(RequestEnvelope.OP_SET, "cabin_temperature", IpcPayload.ofFloat(24f))
            assertEquals(ErrorCode.READ_ONLY, h.errorCode(response))
        }
    }

    @Test
    fun testReadOfNonReadablePropertyIsRejected() {
        SessionHarness(schema).use { h ->
            val response = h.request(RequestEnvelope.OP_GET, "internal_value", null)
            assertEquals(ErrorCode.CAPABILITY_NOT_SUPPORTED, h.errorCode(response))
        }
    }

    @Test
    fun testWireTypeMismatchIsRejected() {
        var received: Any? = null
        SessionHarness(schema, onSet = { _, value, _ -> received = value; SetReceipt.applied() }).use { h ->
            // 契约声明 Float，线上携带 Int
            val response = h.request(RequestEnvelope.OP_SET, "target_temperature", IpcPayload.ofInt(20))
            assertEquals(ErrorCode.TYPE_MISMATCH, h.errorCode(response))
            assertNull("handler must not receive a mistyped value", received)
        }
    }

    @Test
    fun testOutOfRangeValueIsRejectedWithInvalidArgument() {
        SessionHarness(schema).use { h ->
            assertEquals(
                ErrorCode.INVALID_ARGUMENT,
                h.errorCode(h.request(RequestEnvelope.OP_SET, "target_temperature", IpcPayload.ofFloat(40f)))
            )
            assertEquals(
                ErrorCode.INVALID_ARGUMENT,
                h.errorCode(h.request(RequestEnvelope.OP_SET, "target_temperature", IpcPayload.ofFloat(Float.NaN)))
            )
        }
    }

    @Test
    fun testBusinessExceptionBecomesExplicitErrorResponse() {
        // 修复前：oneway 事务吞掉异常，客户端永远收不到响应
        SessionHarness(schema, onSet = { _, _, _ -> throw IllegalStateException("hardware offline") }).use { h ->
            val response = h.request(RequestEnvelope.OP_SET, "target_temperature", IpcPayload.ofFloat(24f))
            assertEquals(ResponseEnvelope.STATUS_ERROR, response.status)
            assertEquals(ErrorCode.INTERNAL_ERROR, h.errorCode(response))
            assertTrue(response.error?.message?.contains("hardware offline") == true)
        }
    }

    @Test
    fun testCallReturningNullForNonNullContractIsExplicitError() {
        SessionHarness(schema, onCall = { _, _, _ -> null }).use { h ->
            val response = h.request(RequestEnvelope.OP_CALL, "start_self_test", IpcPayload.ofString("arg"))
            assertEquals(ErrorCode.INTERNAL_ERROR, h.errorCode(response))
            assertTrue(
                "error must explain the contract violation",
                response.error?.message?.contains("handler returned null") == true
            )
        }
    }

    @Test
    fun testUnitCommandSucceedsWithExplicitNullPayload() {
        SessionHarness(schema, onCall = { id, _, _ -> if (id == "reset_all") Unit else "ok" }).use { h ->
            val response = h.request(RequestEnvelope.OP_CALL, "reset_all", IpcPayload.ofNull())
            assertEquals(ResponseEnvelope.STATUS_OK, response.status)
            assertNotNull(response.payload)
            assertEquals(ValueType.NULL.typeTag, response.payload.typeTag)
            assertEquals(null, response.payload.toValue())
        }
    }

    @Test
    fun testCallWithWrongReturnTypeReportsTypeMismatch() {
        SessionHarness(schema, onCall = { id, _, _ -> if (id == "bad_return") 42 else "ok" }).use { h ->
            val response = h.request(RequestEnvelope.OP_CALL, "bad_return", IpcPayload.ofString("arg"))
            assertEquals(ErrorCode.TYPE_MISMATCH, h.errorCode(response))
        }
    }

    @Test
    fun testGetReturnsRegisteredContractType() {
        SessionHarness(schema).use { h ->
            h.stateStore.update(targetTemperature, 24.5f)
            val response = h.request(RequestEnvelope.OP_GET, "target_temperature", null)
            assertEquals(ResponseEnvelope.STATUS_OK, response.status)
            assertNotNull(response.payload)
            assertEquals(ValueType.FLOAT.typeTag, response.payload.typeTag)
            assertEquals(24.5f, response.payload.toValue())
            assertNotNull("writeToken must be returned for CAS usage", response.writeToken)
        }
    }

    @Test
    fun testConditionalWriteTokenRemainsUsableAfterHandlerAppliedValue() {
        // 修复前：handler 内部 update() 会再次推进写版本，导致返回给客户端的 token 立即失效
        lateinit var harness: SessionHarness
        harness = SessionHarness(
            schema,
            onSet = { keyId, value, _ ->
                if (keyId == "target_temperature") {
                    harness.stateStore.update(targetTemperature, value as Float)
                    SetReceipt.applied()
                } else {
                    SetReceipt.applied()
                }
            }
        )
        harness.use { h ->
            val firstSet = h.request(RequestEnvelope.OP_SET, "target_temperature", IpcPayload.ofFloat(20f))
            assertEquals(ResponseEnvelope.STATUS_APPLIED, firstSet.status)

            val token = h.request(RequestEnvelope.OP_GET, "target_temperature", null).writeToken
            assertNotNull(token)

            val conditional = h.request(
                RequestEnvelope.OP_SET_IF_VERSION,
                "target_temperature",
                IpcPayload.ofFloat(21f),
                expectedWriteToken = token
            )
            assertEquals(ResponseEnvelope.STATUS_APPLIED, conditional.status)
            val returnedToken = conditional.writeToken
            assertEquals(
                "returned token must reflect the state after the handler wrote",
                h.stateStore.writeTokenOf("target_temperature"),
                returnedToken
            )

            // 用返回的 token 继续做下一次条件写：必须成功（修复前必然 CONCURRENT_CONFLICT）
            val second = h.request(
                RequestEnvelope.OP_SET_IF_VERSION,
                "target_temperature",
                IpcPayload.ofFloat(22f),
                expectedWriteToken = returnedToken
            )
            assertEquals(ResponseEnvelope.STATUS_APPLIED, second.status)
            assertNotEquals(ErrorCode.CONCURRENT_CONFLICT, h.errorCode(second))
        }
    }

    @Test
    fun testStaleConditionalWriteTokenIsRejectedWithCurrentToken() {
        lateinit var harness: SessionHarness
        harness = SessionHarness(
            schema,
            onSet = { _, value, _ -> harness.stateStore.update(targetTemperature, value as Float); SetReceipt.applied() }
        )
        harness.use { h ->
            val token = h.request(RequestEnvelope.OP_GET, "target_temperature", null).writeToken
            assertNotNull(token)
            // 先用掉 token
            h.request(RequestEnvelope.OP_SET_IF_VERSION, "target_temperature", IpcPayload.ofFloat(20f), expectedWriteToken = token)
            // 旧 token 再用必须冲突，且错误里带上当前 token
            val conflict = h.request(
                RequestEnvelope.OP_SET_IF_VERSION,
                "target_temperature",
                IpcPayload.ofFloat(21f),
                expectedWriteToken = token
            )
            assertEquals(ErrorCode.CONCURRENT_CONFLICT, h.errorCode(conflict))
            assertNotNull(conflict.error?.currentWriteToken)
        }
    }

    @Test
    fun testSubscribeToUnknownKeyReportsErrorFrame() {
        SessionHarness(schema).use { h ->
            h.session.subscribe(
                com.caripc.protocol.SubscribeRequest("sub-unknown", listOf("nope"), true, 0)
            )
            val messages = h.awaitSubscriptionMessages(1)
            assertEquals(1, messages.size)
            assertEquals(SubscriptionEnvelope.KIND_ERROR, messages[0].kind)
            assertEquals(ErrorCode.UNKNOWN_CAPABILITY.code, messages[0].error?.errorCode)
        }
    }

    @Test
    fun testSubscribeToNonObservablePropertyReportsErrorFrame() {
        SessionHarness(schema).use { h ->
            h.session.subscribe(
                com.caripc.protocol.SubscribeRequest("sub-hidden", listOf("internal_value"), true, 0)
            )
            val messages = h.awaitSubscriptionMessages(1)
            assertEquals(SubscriptionEnvelope.KIND_ERROR, messages[0].kind)
            assertEquals(ErrorCode.UNKNOWN_CAPABILITY.code, messages[0].error?.errorCode)
        }
    }

    @Test
    fun testDuplicateSubscriptionWithDifferentKeysIsRejected() {
        SessionHarness(schema).use { h ->
            h.session.subscribe(com.caripc.protocol.SubscribeRequest("sub-1", listOf("target_temperature"), true, 0))
            h.drainSubscriptionMessages()
            h.session.subscribe(com.caripc.protocol.SubscribeRequest("sub-1", listOf("cabin_temperature"), true, 0))
            val messages = h.awaitSubscriptionMessages(1)
            assertEquals(SubscriptionEnvelope.KIND_ERROR, messages[0].kind)
            assertEquals(ErrorCode.INVALID_ARGUMENT.code, messages[0].error?.errorCode)
        }
    }

    @Test
    fun testSessionQuotaIsEnforced() {
        SessionHarness(schema).use { h ->
            val openedSessions = mutableListOf<ISession>()
            repeat(SessionManager.MAX_SESSIONS_PER_UID - 1) {
                openedSessions.add(
                    h.sessionManager.createSession(
                        0,
                        com.caripc.protocol.ClientHello(1, 0, 1, 0, "client-$it", "open-$it"),
                        h.callback
                    ).session
                )
            }
            val error = try {
                h.sessionManager.createSession(
                    0,
                    com.caripc.protocol.ClientHello(1, 0, 1, 0, "client-over", "open-over"),
                    h.callback
                )
                null
            } catch (e: IpcError) {
                e
            }
            assertNotNull("exceeding per-UID session quota must fail", error)
            assertEquals(ErrorCode.RESOURCE_EXHAUSTED, error!!.code)
            openedSessions.forEach { runCatching { it.close() } }
        }
    }

    @Test
    fun testClosedPublisherRejectsNewSessionsAndRequests() {
        SessionHarness(schema).use { h ->
            h.sessionManager.markServiceClosed()
            val error = try {
                h.sessionManager.createSession(
                    0,
                    com.caripc.protocol.ClientHello(1, 0, 1, 0, "late-client", "open-late"),
                    h.callback
                )
                null
            } catch (e: IpcError) {
                e
            }
            assertEquals(ErrorCode.SERVICE_CLOSED, error?.code)
            // 已建立会话上的后续请求也必须显式失败，而不是继续服务
            val response = h.request(RequestEnvelope.OP_GET, "target_temperature", null)
            assertEquals(ErrorCode.SERVICE_CLOSED, h.errorCode(response))
        }
    }

    @Test
    fun testOutOfRangeAckIsCountedAndIgnored() {
        SessionHarness(schema).use { h ->
            h.session.subscribe(com.caripc.protocol.SubscribeRequest("sub-ack", listOf("target_temperature"), true, 0))
            h.drainSubscriptionMessages()
            h.session.acknowledge("sub-ack", 999L)
            assertTrue(h.sessionManager.dumpSessions().contains("staleAcks=1"))
        }
    }

    private object Unused
}