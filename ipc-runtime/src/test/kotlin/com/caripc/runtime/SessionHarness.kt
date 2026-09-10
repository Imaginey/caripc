package com.caripc.runtime

import com.caripc.contract.*
import com.caripc.protocol.*
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * 服务端测试台：把 StateStore + SessionManager + 一个真实 ISession 会话装配起来，
 * 让契约校验、异常边界、订阅投递都能在不依赖真机 Binder 的情况下被验证。
 */
class SessionHarness(
    val schema: ServiceSchema,
    scheduler: TimeoutScheduler? = null,
    onSet: (String, Any?, Int) -> SetReceipt = { _, _, _ -> SetReceipt.applied() },
    onCall: (String, Any?, Int) -> Any? = { _, _, _ -> null },
    serviceId: String = "com.test.service",
    executor: OutboundExecutor = OutboundExecutor(4, 8)
) : AutoCloseable {

    val executor = executor
    val emissions = CopyOnWriteArrayList<SubscriptionEnvelope>()
    val responses = LinkedBlockingQueue<ResponseEnvelope>()
    val subscriptionMessages = LinkedBlockingQueue<SubscriptionEnvelope>()

    private var sessionManagerRef: SessionManager? = null

    val stateStore = StateStore("inst-1", scheduler) { envelope ->
        emissions.add(envelope)
        sessionManagerRef?.broadcastPropertyUpdate(envelope)
    }

    val descriptor = ServiceDescriptor(
        serviceId,
        schema.contractId,
        schema.major,
        schema.minor,
        1,
        0,
        "inst-1",
        0L,
        0,
        0,
        schema.properties.map { it.id } + schema.events.map { it.id } + schema.commands.map { it.id }
    )

    init {
        schema.properties.forEach { stateStore.registerProperty(it) }
        sessionManagerRef = SessionManager(
            serviceDescriptor = descriptor,
            stateStore = stateStore,
            permissionPolicy = PermissionPolicy(TestContext()),
            outboundExecutor = executor,
            onSetHandler = onSet,
            onCallHandler = onCall,
            schema = schema,
            scheduler = scheduler
        )
    }

    val sessionManager: SessionManager get() = sessionManagerRef!!

    val callback = object : IClientCallback.Stub() {
        override fun onSessionOpened(hello: ServerHello, session: ISession) {}

        override fun onSessionRejected(error: ErrorEnvelope) {}

        override fun onResult(response: ResponseEnvelope) {
            responses.add(response)
        }

        override fun onSubscriptionMessage(message: SubscriptionEnvelope) {
            subscriptionMessages.add(message)
        }
    }

    val session: ISession = sessionManager.createSession(
        0,
        ClientHello(1, 0, schema.major, schema.minor, "client-inst", "open-1"),
        callback
    ).session

    fun request(operation: Int, capabilityId: String, payload: IpcPayload?, expectedWriteToken: String? = null): ResponseEnvelope {
        val requestId = UUID.randomUUID().toString()
        session.request(RequestEnvelope(requestId, operation, capabilityId, payload, 0L, expectedWriteToken, null))
        return awaitResponse()
    }

    fun awaitResponse(timeoutMs: Long = 3_000L): ResponseEnvelope =
        responses.poll(timeoutMs, TimeUnit.MILLISECONDS) ?: error("no response within ${timeoutMs}ms")

    fun awaitSubscriptionMessages(count: Int, timeoutMs: Long = 3_000L): List<SubscriptionEnvelope> {
        val collected = mutableListOf<SubscriptionEnvelope>()
        val deadline = System.currentTimeMillis() + timeoutMs
        while (collected.size < count && System.currentTimeMillis() < deadline) {
            val msg = subscriptionMessages.poll(200, TimeUnit.MILLISECONDS) ?: continue
            collected.add(msg)
        }
        return collected
    }

    fun drainSubscriptionMessages(timeoutMs: Long = 300L): List<SubscriptionEnvelope> {
        val collected = mutableListOf<SubscriptionEnvelope>()
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            val msg = subscriptionMessages.poll(50, TimeUnit.MILLISECONDS)
            if (msg == null) {
                if (collected.isNotEmpty() || System.currentTimeMillis() >= deadline) break
            } else {
                collected.add(msg)
            }
        }
        return collected
    }

    fun errorCode(response: ResponseEnvelope): ErrorCode? =
        response.error?.let { ErrorCode.fromCode(it.errorCode) }

    override fun close() {
        sessionManagerRef?.closeAll()
        stateStore.close()
        executor.shutdown()
    }
}
