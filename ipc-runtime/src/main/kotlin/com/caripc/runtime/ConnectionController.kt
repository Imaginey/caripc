package com.caripc.runtime

import android.os.IBinder
import android.os.RemoteException
import com.caripc.contract.*
import com.caripc.protocol.*
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * 客户端逻辑连接：发现 → 握手 → 请求/订阅 → 重连。
 *
 * 修复要点：
 * - 超时由 [TimeoutScheduler] 真正驱动（工单 P0-1）；
 * - 负载编码在投递**之前**完成，编码失败转成错误回调（工单 P0-2）；
 * - 出站任务被拒绝时请求以显式错误完成，不再静默丢失（工单 P0-3）；
 * - 用线类型标签做运行期类型校验（工单 P0-6），void 命令用 NULL 负载（工单 P0-5）；
 * - 订阅侧对属性帧做「按 Key 单调版本」守卫，兜底任何残余乱序（工单 P1-4/P1-5）。
 */
class ConnectionController(
    val serviceId: String,
    private val registryConnector: RegistryConnector,
    private val outboundExecutor: OutboundExecutor,
    private val scheduler: TimeoutScheduler,
    private val clientInstanceId: String = UUID.randomUUID().toString()
) {
    companion object {
        const val TIMEOUT_TICK_MS = 50L
        const val TRANSPORT_MAJOR = 1
        const val TRANSPORT_MINOR = 0
    }

    enum class State {
        IDLE,
        DISCOVERING,
        CONNECTING,
        READY,
        RECONNECTING,
        CLOSED
    }

    class ClientSubscriptionRecord(
        val subscriptionId: String,
        val keys: List<CapabilityKey>,
        val replayLatest: Boolean,
        val listener: (SubscriptionMessage) -> Unit
    ) {
        val keyIds: List<String> = keys.map { it.id }
        private val keyById: Map<String, CapabilityKey> = keys.associateBy { it.id }
        val lastRevisionByKey = ConcurrentHashMap<String, Long>()

        fun key(id: String): CapabilityKey? = keyById[id]
    }

    private var state = State.IDLE
    private var activeEndpoint: IEndpoint? = null
    private var activeSession: ISession? = null
    private var endpointDeathRecipient: IBinder.DeathRecipient? = null
    private val watchId = AtomicLong(System.currentTimeMillis()).getAndIncrement()

    /** 对端协商上限，握手成功后更新（工单 P1-9 / P2-5）。 */
    @Volatile
    private var serverMaxPayloadBytes: Int = IpcPayload.MAX_PAYLOAD_BYTES

    /** 对端声明的 ACK 窗口（0 表示不做 ACK 流控）。 */
    @Volatile
    private var serverAckWindow: Int = 0

    val requestTracker = RequestTracker()
    private val subscriptions = ConcurrentHashMap<String, ClientSubscriptionRecord>()
    private val lock = Any()
    private val readyWaiters = mutableListOf<Waiter>()

    private class Waiter(val callback: ResultCallback<Unit>) {
        val completed = AtomicBoolean(false)

        @Volatile
        var tick: ScheduledTick? = null

        fun cancelTick() {
            tick?.cancel()
            tick = null
        }
    }

    private val timeoutTick: ScheduledTick = scheduler.schedulePeriodic(TIMEOUT_TICK_MS) {
        if (requestTracker.hasPending()) requestTracker.checkTimeouts()
    }

    private val clientCallback = object : IClientCallback.Stub() {
        override fun onSessionOpened(hello: ServerHello, session: ISession) {
            val mismatch = validateServerHello(hello)
            if (mismatch != null) {
                IpcLog.w("ConnectionController", "Rejecting session for $serviceId: ${mismatch.message}")
                failWaiters(mismatch)
                synchronized(lock) {
                    if (state != State.CLOSED) state = State.RECONNECTING
                }
                try {
                    session.close()
                } catch (_: Exception) {
                }
                return
            }

            var shouldClose = false
            var waiters: List<Waiter> = emptyList()
            var resubscribe: List<ClientSubscriptionRecord> = emptyList()
            synchronized(lock) {
                if (state == State.CLOSED) {
                    shouldClose = true
                } else {
                    activeSession = session
                    serverMaxPayloadBytes = hello.maxPayloadBytes
                    serverAckWindow = hello.windowSize
                    state = State.READY
                    IpcLog.i(
                        "ConnectionController",
                        "Session opened for $serviceId: serverInstance=${hello.serviceInstanceId}, sessionId=${hello.sessionId}, maxPayloadBytes=${hello.maxPayloadBytes}, ackWindow=${hello.windowSize}"
                    )
                    waiters = readyWaiters.toList()
                    readyWaiters.clear()
                    resubscribe = subscriptions.values.toList()
                }
            }

            if (shouldClose) {
                try {
                    session.close()
                } catch (_: Exception) {
                }
                return
            }

            // 锁外回调与投递：禁止持内部锁调用业务 listener
            waiters.forEach { waiter ->
                if (waiter.completed.compareAndSet(false, true)) {
                    waiter.cancelTick()
                    waiter.callback.onSuccess(Unit)
                }
            }
            resubscribe.forEach { sub ->
                try {
                    IpcLog.d("ConnectionController", "Restoring subscription ${sub.subscriptionId} for $serviceId")
                    session.subscribe(SubscribeRequest(sub.subscriptionId, sub.keyIds, sub.replayLatest, 0))
                } catch (e: RemoteException) {
                    IpcLog.w("ConnectionController", "Resubscribe failed for ${sub.subscriptionId}", e)
                }
            }
        }

        override fun onSessionRejected(error: ErrorEnvelope) {
            IpcLog.w("ConnectionController", "Session rejected for $serviceId: ${error.message}")
            synchronized(lock) {
                if (state != State.CLOSED) state = State.RECONNECTING
            }
            failWaiters(error.toIpcError())
        }

        override fun onResult(response: ResponseEnvelope) {
            when (response.status) {
                ResponseEnvelope.STATUS_OK,
                ResponseEnvelope.STATUS_ACCEPTED,
                ResponseEnvelope.STATUS_APPLIED -> requestTracker.completeSuccess(response.requestId, response)

                else -> {
                    val error = response.error?.toIpcError() ?: IpcError(
                        ErrorCode.INTERNAL_ERROR,
                        "Unknown error response",
                        response.requestId,
                        CompletionState.UNKNOWN
                    )
                    requestTracker.completeError(response.requestId, error)
                }
            }
        }

        override fun onSubscriptionMessage(msg: SubscriptionEnvelope) {
            val sub = subscriptions[msg.subscriptionId] ?: return
            val payload = buildPayload(sub, msg) ?: return
            val messageObj = object : SubscriptionMessage {
                override val subscriptionId: String = msg.subscriptionId
                override val serviceInstanceId: String = msg.serviceInstanceId
                override val payload: SubscriptionMessagePayload = payload
            }
            try {
                sub.listener(messageObj)
            } catch (t: Throwable) {
                IpcLog.w("ConnectionController", "Exception in subscription listener for ${msg.subscriptionId}", t)
            }
        }
    }

    private fun validateServerHello(hello: ServerHello): IpcError? {
        if (hello.transportMajor != TRANSPORT_MAJOR) {
            return IpcError(
                ErrorCode.VERSION_MISMATCH,
                "Incompatible transport version: client=$TRANSPORT_MAJOR, server=${hello.transportMajor}"
            )
        }
        val descriptor = advertisedContract
        if (descriptor != null && hello.contractMajor != descriptor.contractMajor) {
            return IpcError(
                ErrorCode.VERSION_MISMATCH,
                "Incompatible contract major: registry=${descriptor.contractMajor}, server=${hello.contractMajor}"
            )
        }
        return null
    }

    /** 注册中心发现到的契约版本；握手时用于交叉校验。 */
    @Volatile
    private var advertisedContract: ServiceDescriptor? = null

    private val registryCallback = object : IRegistryCallback.Stub() {
        override fun onPublished(token: RegistrationToken) {}

        override fun onPublishFailed(svcId: String, error: ErrorEnvelope) {
            IpcLog.w("ConnectionController", "Publish failed for $svcId: ${error.message}")
        }

        override fun onSnapshot(svcId: String, wId: Long, descriptor: ServiceDescriptor, endpoint: IEndpoint) {
            onRemoteDiscovered(descriptor, endpoint)
        }

        override fun onServiceUnavailable(svcId: String, wId: Long) {
            synchronized(lock) {
                // 文档 10.5: Registry 初始空快照不误断健康直连 Session
                if (activeSession != null && state == State.READY) {
                    IpcLog.d("ConnectionController", "Registry snapshot empty, but existing session is active. Keep session.")
                    return
                }
                state = State.DISCOVERING
            }
        }

        override fun onServiceChanged(svcId: String, wId: Long, descriptor: ServiceDescriptor, endpoint: IEndpoint) {
            onRemoteDiscovered(descriptor, endpoint)
        }

        override fun onError(wId: Long, error: ErrorEnvelope) {
            IpcLog.w("ConnectionController", "Registry error for watch $wId: ${error.message}")
        }
    }

    private fun onRemoteDiscovered(descriptor: ServiceDescriptor, endpoint: IEndpoint) {
        advertisedContract = descriptor
        var shouldOpen = false
        var hello: ClientHello? = null
        synchronized(lock) {
            if (state == State.CLOSED) return

            // 同一 Binder 且已就绪：无需重建
            if (activeEndpoint?.asBinder() == endpoint.asBinder() && state == State.READY) {
                return
            }

            endpointDeathRecipient?.let {
                try {
                    activeEndpoint?.asBinder()?.unlinkToDeath(it, 0)
                } catch (_: Exception) {
                }
            }

            activeEndpoint = endpoint
            state = State.CONNECTING

            val death = object : IBinder.DeathRecipient {
                override fun binderDied() {
                    onEndpointDied()
                }
            }
            endpointDeathRecipient = death
            try {
                endpoint.asBinder().linkToDeath(death, 0)
            } catch (e: RemoteException) {
                state = State.RECONNECTING
                return
            }

            hello = ClientHello(
                TRANSPORT_MAJOR,
                TRANSPORT_MINOR,
                descriptor.contractMajor,
                descriptor.contractMinor,
                clientInstanceId,
                UUID.randomUUID().toString()
            )
            shouldOpen = true
        }

        if (!shouldOpen) return
        // openSession 是 oneway，但仍放到锁外调用，避免持内部锁做跨进程调用
        try {
            endpoint.openSession(hello!!, clientCallback)
        } catch (e: RemoteException) {
            onEndpointDied()
        }
    }

    private fun onEndpointDied() {
        synchronized(lock) {
            if (state == State.CLOSED) return
            IpcLog.w("ConnectionController", "Target service endpoint died for $serviceId")
            state = State.RECONNECTING
            activeSession = null
            activeEndpoint = null
        }
        requestTracker.clearAllWithConnectionLost()
    }

    fun start() {
        var needDiscover = false
        synchronized(lock) {
            if (state == State.IDLE) {
                state = State.DISCOVERING
                needDiscover = true
            }
        }
        if (needDiscover) registryConnector.resolveAndWatch(serviceId, watchId, registryCallback)
    }

    fun awaitReady(timeoutMs: Long, callback: ResultCallback<Unit>) {
        val waiter = Waiter(callback)
        synchronized(lock) {
            when (state) {
                State.READY -> {
                    callback.onSuccess(Unit)
                    return
                }

                State.CLOSED -> {
                    callback.onError(IpcError(ErrorCode.SERVICE_CLOSED, "Connection closed"))
                    return
                }

                else -> readyWaiters.add(waiter)
            }
        }
        start()
        // 超时不再占用出站线程池：交给调度器（工单 P2-1）
        scheduler.schedulePeriodic(timeoutMs.coerceAtLeast(1L)) {
            if (waiter.completed.compareAndSet(false, true)) {
                synchronized(lock) { readyWaiters.remove(waiter) }
                waiter.callback.onError(
                    IpcError(ErrorCode.TIMEOUT, "awaitReady timed out after ${timeoutMs}ms")
                )
            }
            waiter.cancelTick()
        }.also { waiter.tick = it }
    }

    private fun failWaiters(error: IpcError) {
        val waiters: List<Waiter>
        synchronized(lock) {
            waiters = readyWaiters.toList()
            readyWaiters.clear()
        }
        waiters.forEach { waiter ->
            if (waiter.completed.compareAndSet(false, true)) {
                waiter.cancelTick()
                waiter.callback.onError(error)
            }
        }
    }

    @Suppress("UNCHECKED_CAST")
    fun <T : Any> get(key: PropertyKey<T>, timeoutMs: Long, callback: ResultCallback<PropertySnapshot<T>>) {
        val session = checkSessionReady(callback) ?: return
        val reqId = UUID.randomUUID().toString()
        val deadline = TimeProvider.elapsedRealtime() + timeoutMs

        requestTracker.register(reqId, timeoutMs, ResultCallback<ResponseEnvelope> { res ->
            res.onSuccess { resp ->
                val decoded = decodeResponse(resp, key.type, reqId)
                if (decoded.isFailure) {
                    callback.onError(decoded.exceptionOrNull() as IpcError)
                } else {
                    val value = decoded.getOrNull()
                    callback.onSuccess(
                        PropertySnapshot(
                            key = key,
                            value = value as? T,
                            quality = Quality.fromValue(resp.quality),
                            revision = resp.revision,
                            sourceElapsedMs = resp.sourceElapsedMs,
                            serviceInstanceId = resp.serviceInstanceId,
                            writeToken = resp.writeToken
                        )
                    )
                }
            }
            res.onFailure { th ->
                callback.onError(th.asIpcError("get failed"))
            }
        })

        val accepted = outboundExecutor.submit {
            try {
                session.request(
                    RequestEnvelope(reqId, RequestEnvelope.OP_GET, key.id, null, deadline, null, null)
                )
            } catch (e: RemoteException) {
                requestTracker.completeError(reqId, IpcError(ErrorCode.CONNECTION_LOST, "Remote call failed", reqId))
            }
        }
        if (!accepted) {
            requestTracker.completeError(
                reqId,
                IpcError(ErrorCode.RESOURCE_EXHAUSTED, "Outbound executor queue is full; request not sent", reqId)
            )
        }
    }

    fun <T : Any> set(
        key: PropertyKey<T>,
        value: T,
        writeToken: String? = null,
        timeoutMs: Long,
        callback: ResultCallback<SetReceipt>
    ) {
        try {
            key.validate(value)
        } catch (e: IllegalArgumentException) {
            callback.onError(IpcError(ErrorCode.INVALID_ARGUMENT, e.message ?: "Invalid value"))
            return
        }

        // 编码在投递之前完成：编码失败绝不能变成「无回调」（工单 P0-2）
        val payload = try {
            IpcPayload.ofAny(value)
        } catch (e: IpcError) {
            callback.onError(e)
            return
        }
        val oversize = checkPayloadSize(payload)
        if (oversize != null) {
            callback.onError(oversize)
            return
        }

        val session = checkSessionReady(callback) ?: return
        val reqId = UUID.randomUUID().toString()
        val deadline = TimeProvider.elapsedRealtime() + timeoutMs
        val op = if (writeToken != null) RequestEnvelope.OP_SET_IF_VERSION else RequestEnvelope.OP_SET

        requestTracker.register(reqId, timeoutMs, ResultCallback<ResponseEnvelope> { res ->
            res.onSuccess { resp ->
                val status = when (resp.status) {
                    ResponseEnvelope.STATUS_ACCEPTED -> SetStatus.ACCEPTED
                    ResponseEnvelope.STATUS_APPLIED -> SetStatus.APPLIED
                    else -> SetStatus.REJECTED
                }
                callback.onSuccess(SetReceipt(status, resp.operationId, resp.writeToken, resp.error?.toIpcError()))
            }
            res.onFailure { th ->
                callback.onError(th.asIpcError("set failed"))
            }
        })

        val accepted = outboundExecutor.submit {
            try {
                session.request(RequestEnvelope(reqId, op, key.id, payload, deadline, writeToken, null))
            } catch (e: RemoteException) {
                requestTracker.completeError(reqId, IpcError(ErrorCode.CONNECTION_LOST, "Remote set failed", reqId))
            }
        }
        if (!accepted) {
            requestTracker.completeError(
                reqId,
                IpcError(ErrorCode.RESOURCE_EXHAUSTED, "Outbound executor queue is full; request not sent", reqId)
            )
        }
    }

    @Suppress("UNCHECKED_CAST")
    fun <Req : Any, Resp : Any> call(
        command: CommandKey<Req, Resp>,
        param: Req,
        timeoutMs: Long,
        callback: ResultCallback<Resp>
    ) {
        val payload = try {
            IpcPayload.ofAny(param)
        } catch (e: IpcError) {
            callback.onError(e)
            return
        }
        val actualReqType = payload?.let { safeTypeOf(it, null) }
        if (actualReqType != null && actualReqType != command.reqType) {
            callback.onError(
                IpcError(
                    ErrorCode.TYPE_MISMATCH,
                    "Command ${command.id} expects reqType=${command.reqType} but got $actualReqType"
                )
            )
            return
        }
        val oversize = checkPayloadSize(payload)
        if (oversize != null) {
            callback.onError(oversize)
            return
        }

        val session = checkSessionReady(callback) ?: return
        val reqId = UUID.randomUUID().toString()
        val deadline = TimeProvider.elapsedRealtime() + timeoutMs

        requestTracker.register(reqId, timeoutMs, ResultCallback<ResponseEnvelope> { res ->
            res.onSuccess { resp ->
                val decoded = decodeResponse(resp, command.respType, reqId)
                if (decoded.isFailure) {
                    callback.onError(decoded.exceptionOrNull() as IpcError)
                } else if (command.respType == ValueType.NULL) {
                    callback.onSuccess(Unit as Resp)
                } else {
                    callback.onSuccess(decoded.getOrNull() as Resp)
                }
            }
            res.onFailure { th ->
                callback.onError(th.asIpcError("call failed"))
            }
        })

        val accepted = outboundExecutor.submit {
            try {
                session.request(RequestEnvelope(reqId, RequestEnvelope.OP_CALL, command.id, payload, deadline, null, null))
            } catch (e: RemoteException) {
                requestTracker.completeError(reqId, IpcError(ErrorCode.CONNECTION_LOST, "Remote call failed", reqId))
            }
        }
        if (!accepted) {
            requestTracker.completeError(
                reqId,
                IpcError(ErrorCode.RESOURCE_EXHAUSTED, "Outbound executor queue is full; request not sent", reqId)
            )
        }
    }

    fun subscribe(
        keys: List<CapabilityKey>,
        options: SubscribeOptions = SubscribeOptions(),
        listener: (SubscriptionMessage) -> Unit
    ): CancelHandle {
        val subId = UUID.randomUUID().toString()
        val record = ClientSubscriptionRecord(subId, keys, options.replayLatest, listener)
        subscriptions[subId] = record

        start()

        val session = synchronized(lock) { activeSession }
        if (session != null) {
            val accepted = outboundExecutor.submit {
                try {
                    session.subscribe(SubscribeRequest(subId, record.keyIds, options.replayLatest, options.windowSize))
                } catch (e: RemoteException) {
                    IpcLog.w("ConnectionController", "Subscribe failed on session", e)
                }
            }
            if (!accepted) {
                // 订阅请求投递失败必须让业务知道，不能静默等不到任何消息
                record.listener(
                    object : SubscriptionMessage {
                        override val subscriptionId: String = subId
                        override val serviceInstanceId: String = ""
                        override val payload: SubscriptionMessagePayload = SubscriptionErrorMessage(
                            IpcError(ErrorCode.RESOURCE_EXHAUSTED, "Outbound executor queue is full; subscribe not sent")
                        )
                    }
                )
            }
        }

        return CancelHandle {
            subscriptions.remove(subId)
            val current = synchronized(lock) { activeSession }
            current?.let {
                outboundExecutor.submit {
                    try {
                        it.unsubscribe(subId)
                    } catch (_: RemoteException) {
                    }
                }
            }
        }
    }

    private fun <T> checkSessionReady(callback: ResultCallback<T>): ISession? {
        synchronized(lock) {
            val session = activeSession
            if (session == null || state != State.READY) {
                callback.onError(IpcError(ErrorCode.SERVICE_UNAVAILABLE, "Service $serviceId is not ready (state=$state)"))
                return null
            }
            return session
        }
    }

    private fun checkPayloadSize(payload: IpcPayload?): IpcError? {
        if (payload == null) return null
        val limit = serverMaxPayloadBytes
        if (limit > 0 && payload.byteSize() > limit) {
            return IpcError(
                ErrorCode.PAYLOAD_LARGE,
                "Payload size ${payload.byteSize()} bytes exceeds negotiated limit $limit bytes"
            )
        }
        return null
    }

    /** 按线类型标签解码响应；类型不符明确报 TYPE_MISMATCH（工单 P0-6）。 */
    private fun decodeResponse(resp: ResponseEnvelope, expected: ValueType, reqId: String): Result<Any?> {
        val payload = resp.payload
        if (payload == null) {
            return if (expected == ValueType.NULL) {
                Result.success(null)
            } else {
                Result.failure(
                    IpcError(
                        ErrorCode.TYPE_MISMATCH,
                        "Response for $reqId has no payload but expects $expected",
                        reqId
                    )
                )
            }
        }
        val actual = safeTypeOf(payload, reqId) ?: return Result.failure(
            IpcError(ErrorCode.TYPE_MISMATCH, "Unknown wire type tag ${payload.typeTag}", reqId)
        )
        if (actual != expected) {
            return Result.failure(
                IpcError(ErrorCode.TYPE_MISMATCH, "Expected $expected but server returned $actual", reqId)
            )
        }
        return Result.success(payload.toValue())
    }

    private fun safeTypeOf(payload: IpcPayload, reqId: String?): ValueType? = try {
        ValueType.fromTag(payload.typeTag)
    } catch (_: IllegalArgumentException) {
        if (reqId != null) IpcLog.w("ConnectionController", "Unknown type tag ${payload.typeTag} for $reqId")
        null
    }

    private fun Throwable.asIpcError(fallback: String): IpcError =
        this as? IpcError ?: IpcError(ErrorCode.INTERNAL_ERROR, message ?: fallback)

    private fun buildPayload(sub: ClientSubscriptionRecord, msg: SubscriptionEnvelope): SubscriptionMessagePayload? {
        return when (msg.kind) {
            SubscriptionEnvelope.KIND_PROPERTY -> {
                val key = sub.key(msg.capabilityId)
                if (key !is PropertyKey<*>) {
                    SubscriptionErrorMessage(
                        IpcError(ErrorCode.UNKNOWN_CAPABILITY, "Property ${msg.capabilityId} is not in this subscription's contract")
                    )
                } else {
                    val previous = sub.lastRevisionByKey[msg.capabilityId]
                    if (previous != null && msg.revision <= previous) {
                        // 单调版本守卫：丢弃比已见版本更旧的帧，避免任何残余乱序造成值回退
                        IpcLog.d(
                            "ConnectionController",
                            "Dropped stale frame for ${msg.capabilityId} (rev=${msg.revision} <= $previous)"
                        )
                        null
                    } else {
                        val actual = msg.payload?.let { safeTypeOf(it, null) }
                        if (msg.payload != null && actual != key.type) {
                            SubscriptionErrorMessage(
                                IpcError(
                                    ErrorCode.TYPE_MISMATCH,
                                    "Property ${msg.capabilityId} expects ${key.type} but wire carried $actual"
                                )
                            )
                        } else {
                            sub.lastRevisionByKey[msg.capabilityId] = msg.revision
                            @Suppress("UNCHECKED_CAST")
                            val typedKey = key as PropertyKey<Any>
                            val snapshot = PropertySnapshot(
                                key = typedKey,
                                value = msg.payload?.toValue(),
                                quality = Quality.fromValue(msg.quality),
                                revision = msg.revision,
                                sourceElapsedMs = msg.sourceElapsedMs,
                                serviceInstanceId = msg.serviceInstanceId,
                                writeToken = null
                            )
                            PropertyUpdate(typedKey, snapshot)
                        }
                    }
                }
            }

            SubscriptionEnvelope.KIND_EVENT -> {
                val key = sub.key(msg.capabilityId)
                if (key !is EventKey<*>) {
                    SubscriptionErrorMessage(
                        IpcError(ErrorCode.UNKNOWN_CAPABILITY, "Event ${msg.capabilityId} is not in this subscription's contract")
                    )
                } else {
                    val value = msg.payload?.toValue()
                    val actual = msg.payload?.let { safeTypeOf(it, null) }
                    if (value == null || actual != key.type) {
                        SubscriptionErrorMessage(
                            IpcError(
                                ErrorCode.TYPE_MISMATCH,
                                "Event ${msg.capabilityId} expects ${key.type} but wire carried $actual"
                            )
                        )
                    } else {
                        @Suppress("UNCHECKED_CAST")
                        EventEmission(key as EventKey<Any>, value, msg.deliverySeq)
                    }
                }
            }

            SubscriptionEnvelope.KIND_GAP -> SubscriptionGap(
                msg.subscriptionId,
                msg.gapFromSeq,
                msg.deliverySeq,
                msg.error?.message ?: "delivery gap"
            )

            SubscriptionEnvelope.KIND_ERROR -> SubscriptionErrorMessage(
                msg.error?.toIpcError() ?: IpcError(ErrorCode.INTERNAL_ERROR, "Subscription error")
            )

            else -> null
        }
    }

    fun close() {
        var waiters: List<Waiter> = emptyList()
        var session: ISession? = null
        synchronized(lock) {
            if (state == State.CLOSED) return
            state = State.CLOSED
            waiters = readyWaiters.toList()
            readyWaiters.clear()
            session = activeSession
            endpointDeathRecipient?.let {
                try {
                    activeEndpoint?.asBinder()?.unlinkToDeath(it, 0)
                } catch (_: Exception) {
                }
            }
            activeSession = null
            activeEndpoint = null
            subscriptions.clear()
        }
        timeoutTick.cancel()
        registryConnector.unwatch(watchId, registryCallback)
        session?.let {
            try {
                it.close()
            } catch (_: Exception) {
            }
        }
        waiters.forEach { waiter ->
            if (waiter.completed.compareAndSet(false, true)) {
                waiter.cancelTick()
                waiter.callback.onError(IpcError(ErrorCode.SERVICE_CLOSED, "Connection closed"))
            }
        }
        requestTracker.clearAllWithConnectionLost()
    }

    fun currentState(): State = synchronized(lock) { state }

    fun negotiatedAckWindow(): Int = serverAckWindow
}
