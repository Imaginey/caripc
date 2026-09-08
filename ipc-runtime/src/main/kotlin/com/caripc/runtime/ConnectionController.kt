package com.caripc.runtime

import android.os.IBinder
import android.os.RemoteException
import android.os.SystemClock
import android.util.Log
import com.caripc.contract.*
import com.caripc.protocol.*
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

class ConnectionController(
    val serviceId: String,
    private val registryConnector: RegistryConnector,
    private val outboundExecutor: OutboundExecutor,
    private val clientInstanceId: String = UUID.randomUUID().toString()
) {
    enum class State {
        IDLE,
        DISCOVERING,
        CONNECTING,
        READY,
        RECONNECTING,
        CLOSED
    }

    data class ClientSubscriptionRecord(
        val subscriptionId: String,
        val keys: List<String>,
        val replayLatest: Boolean,
        val listener: (SubscriptionMessage) -> Unit
    )

    private var state = State.IDLE
    private var activeEndpoint: IEndpoint? = null
    private var activeSession: ISession? = null
    private var endpointDeathRecipient: IBinder.DeathRecipient? = null
    private val watchId = AtomicLong(System.currentTimeMillis()).getAndIncrement()

    val requestTracker = RequestTracker()
    private val subscriptions = ConcurrentHashMap<String, ClientSubscriptionRecord>()
    private val lock = Any()
    private val readyWaiters = mutableListOf<ResultCallback<Unit>>()

    private val clientCallback = object : IClientCallback.Stub() {
        override fun onSessionOpened(hello: ServerHello, session: ISession) {
            synchronized(lock) {
                if (state == State.CLOSED) {
                    try {
                        session.close()
                    } catch (_: Exception) {}
                    return
                }
                activeSession = session
                state = State.READY
                IpcLog.i("ConnectionController", "Session opened for $serviceId! serverInstance=${hello.serviceInstanceId}")

                // 唤醒 awaitReady 等待者
                val waiters = readyWaiters.toList()
                readyWaiters.clear()
                waiters.forEach { it.onSuccess(Unit) }

                // 自动恢复之前的订阅
                for (sub in subscriptions.values) {
                    try {
                        IpcLog.d("ConnectionController", "Restoring subscription ${sub.subscriptionId} for $serviceId")
                        session.subscribe(
                            SubscribeRequest(
                                sub.subscriptionId,
                                sub.keys,
                                sub.replayLatest,
                                16
                            )
                        )
                    } catch (e: RemoteException) {
                        IpcLog.w("ConnectionController", "Resubscribe failed for ${sub.subscriptionId}", e)
                    }
                }
            }
        }

        override fun onSessionRejected(error: ErrorEnvelope) {
            synchronized(lock) {
                IpcLog.w("ConnectionController", "Session rejected for $serviceId: ${error.message}")
                state = State.RECONNECTING
                val waiters = readyWaiters.toList()
                readyWaiters.clear()
                waiters.forEach { it.onError(error.toIpcError()) }
            }
        }

        override fun onResult(response: ResponseEnvelope) {
            val reqId = response.requestId
            if (response.status == ResponseEnvelope.STATUS_OK) {
                requestTracker.completeSuccess(reqId, response)
            } else if (response.status == ResponseEnvelope.STATUS_ACCEPTED || response.status == ResponseEnvelope.STATUS_APPLIED) {
                requestTracker.completeSuccess(reqId, response)
            } else {
                val err = response.error?.toIpcError() ?: IpcError(
                    ErrorCode.INTERNAL_ERROR,
                    "Unknown error response",
                    reqId,
                    CompletionState.UNKNOWN
                )
                requestTracker.completeError(reqId, err)
            }
        }

        override fun onSubscriptionMessage(msg: SubscriptionEnvelope) {
            val sub = subscriptions[msg.subscriptionId] ?: return
            val payload: SubscriptionMessagePayload = when (msg.kind) {
                SubscriptionEnvelope.KIND_PROPERTY -> {
                    val rawVal = msg.payload?.toValue()
                    @Suppress("UNCHECKED_CAST")
                    val propKey = PropertyKey.string(msg.capabilityId) as PropertyKey<Any>
                    val snapshot = PropertySnapshot<Any>(
                        key = propKey,
                        value = rawVal,
                        quality = Quality.fromValue(msg.quality),
                        revision = msg.revision,
                        sourceElapsedMs = msg.sourceElapsedMs,
                        serviceInstanceId = msg.serviceInstanceId,
                        writeToken = null
                    )
                    PropertyUpdate(propKey, snapshot)
                }
                SubscriptionEnvelope.KIND_EVENT -> {
                    val rawVal = msg.payload?.toValue()?.toString() ?: ""
                    EventEmission(EventKey.string(msg.capabilityId), rawVal, msg.deliverySeq)
                }
                SubscriptionEnvelope.KIND_GAP -> {
                    SubscriptionGap(msg.subscriptionId, 0L, msg.deliverySeq, "Message gap detected")
                }
                SubscriptionEnvelope.KIND_ERROR -> {
                    SubscriptionErrorMessage(msg.error?.toIpcError() ?: IpcError(ErrorCode.INTERNAL_ERROR, "Subscription error"))
                }
                else -> return
            }

            val messageObj = object : SubscriptionMessage {
                override val subscriptionId: String = msg.subscriptionId
                override val serviceInstanceId: String = msg.serviceInstanceId
                override val payload: SubscriptionMessagePayload = payload
            }

            try {
                sub.listener(messageObj)
            } catch (e: Exception) {
                Log.w("ConnectionController", "Exception in subscription listener", e)
            }
        }
    }

    private val registryCallback = object : IRegistryCallback.Stub() {
        override fun onPublished(token: RegistrationToken) {}
        override fun onPublishFailed(svcId: String, error: ErrorEnvelope) {}

        override fun onSnapshot(svcId: String, wId: Long, descriptor: ServiceDescriptor, endpoint: IEndpoint) {
            onRemoteDiscovered(descriptor, endpoint)
        }

        override fun onServiceUnavailable(svcId: String, wId: Long) {
            synchronized(lock) {
                // 文档 10.5: Registry 初始空快照不误断健康直连 Session
                if (activeSession != null && state == State.READY) {
                    Log.d("ConnectionController", "Registry snapshot empty, but existing session is active. Keep session.")
                    return
                }
                state = State.DISCOVERING
            }
        }

        override fun onServiceChanged(svcId: String, wId: Long, descriptor: ServiceDescriptor, endpoint: IEndpoint) {
            onRemoteDiscovered(descriptor, endpoint)
        }

        override fun onError(wId: Long, error: ErrorEnvelope) {}
    }

    private fun onRemoteDiscovered(descriptor: ServiceDescriptor, endpoint: IEndpoint) {
        synchronized(lock) {
            if (state == State.CLOSED) return

            // 检查端点是否为同一 Binder
            if (activeEndpoint?.asBinder() == endpoint.asBinder() && state == State.READY) {
                return
            }

            // 清理旧端点死亡监听
            endpointDeathRecipient?.let {
                try {
                    activeEndpoint?.asBinder()?.unlinkToDeath(it, 0)
                } catch (_: Exception) {}
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

            // 发起 openSession
            val openReqId = UUID.randomUUID().toString()
            val hello = ClientHello(
                1, 0,
                descriptor.contractMajor, descriptor.contractMinor,
                clientInstanceId,
                openReqId
            )
            try {
                endpoint.openSession(hello, clientCallback)
            } catch (e: RemoteException) {
                onEndpointDied()
            }
        }
    }

    private fun onEndpointDied() {
        synchronized(lock) {
            if (state == State.CLOSED) return
            Log.w("ConnectionController", "Target service endpoint died for $serviceId")
            state = State.RECONNECTING
            activeSession = null
            activeEndpoint = null
            // 清理所有等待中的请求，标记连接丢失
            requestTracker.clearAllWithConnectionLost()
        }
    }

    fun start() {
        synchronized(lock) {
            if (state == State.IDLE) {
                state = State.DISCOVERING
                registryConnector.resolveAndWatch(serviceId, watchId, registryCallback)
            }
        }
    }

    fun awaitReady(timeoutMs: Long, callback: ResultCallback<Unit>) {
        synchronized(lock) {
            if (state == State.READY) {
                callback.onSuccess(Unit)
                return
            }
            if (state == State.CLOSED) {
                callback.onError(IpcError(ErrorCode.SERVICE_CLOSED, "Connection closed"))
                return
            }
            val completed = AtomicBoolean(false)
            val wrapped = ResultCallback<Unit> { res ->
                if (completed.compareAndSet(false, true)) {
                    callback.onResult(res)
                }
            }
            readyWaiters.add(wrapped)
            start()

            // 超时检测
            outboundExecutor.submit {
                SystemClock.sleep(timeoutMs)
                synchronized(lock) {
                    if (readyWaiters.remove(wrapped)) {
                        wrapped.onError(IpcError(ErrorCode.TIMEOUT, "awaitReady timed out after ${timeoutMs}ms"))
                    }
                }
            }
        }
    }

    @Suppress("UNCHECKED_CAST")
    fun <T : Any> get(key: PropertyKey<T>, timeoutMs: Long, callback: ResultCallback<PropertySnapshot<T>>) {
        val session = checkSessionReady(callback) ?: return
        val reqId = UUID.randomUUID().toString()
        val deadline = SystemClock.elapsedRealtime() + timeoutMs

        requestTracker.register(reqId, timeoutMs, ResultCallback<ResponseEnvelope> { res ->
            res.onSuccess { resp ->
                val rawVal = resp.payload?.toValue() as? T
                val snapshot = PropertySnapshot(
                    key = key,
                    value = rawVal,
                    quality = Quality.fromValue(resp.quality),
                    revision = resp.revision,
                    sourceElapsedMs = resp.sourceElapsedMs,
                    serviceInstanceId = resp.serviceInstanceId,
                    writeToken = resp.writeToken
                )
                callback.onSuccess(snapshot)
            }
            res.onFailure { th ->
                callback.onError(th as? IpcError ?: IpcError(ErrorCode.INTERNAL_ERROR, th.message ?: "Unknown"))
            }
        })

        outboundExecutor.submit {
            try {
                session.request(
                    RequestEnvelope(
                        reqId,
                        RequestEnvelope.OP_GET,
                        key.id,
                        null,
                        deadline,
                        null,
                        null
                    )
                )
            } catch (e: RemoteException) {
                requestTracker.completeError(reqId, IpcError(ErrorCode.CONNECTION_LOST, "Remote call failed", reqId))
            }
        }
    }

    fun <T : Any> set(key: PropertyKey<T>, value: T, writeToken: String? = null, timeoutMs: Long, callback: ResultCallback<SetReceipt>) {
        try {
            key.validate(value)
        } catch (e: IllegalArgumentException) {
            callback.onError(IpcError(ErrorCode.INVALID_ARGUMENT, e.message ?: "Invalid value"))
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
                callback.onError(th as? IpcError ?: IpcError(ErrorCode.INTERNAL_ERROR, th.message ?: "Unknown"))
            }
        })

        outboundExecutor.submit {
            try {
                session.request(
                    RequestEnvelope(
                        reqId,
                        op,
                        key.id,
                        IpcPayload.ofAny(value),
                        deadline,
                        writeToken,
                        null
                    )
                )
            } catch (e: RemoteException) {
                requestTracker.completeError(reqId, IpcError(ErrorCode.CONNECTION_LOST, "Remote set failed", reqId))
            }
        }
    }

    @Suppress("UNCHECKED_CAST")
    fun <Req : Any, Resp : Any> call(command: CommandKey<Req, Resp>, param: Req, timeoutMs: Long, callback: ResultCallback<Resp>) {
        val session = checkSessionReady(callback) ?: return
        val reqId = UUID.randomUUID().toString()
        val deadline = TimeProvider.elapsedRealtime() + timeoutMs

        requestTracker.register(reqId, timeoutMs, ResultCallback<ResponseEnvelope> { res ->
            res.onSuccess { resp ->
                val rawVal = resp.payload?.toValue() as? Resp
                if (rawVal != null) {
                    callback.onSuccess(rawVal)
                } else {
                    callback.onError(IpcError(ErrorCode.TYPE_MISMATCH, "Null or incompatible return value", reqId))
                }
            }
            res.onFailure { th ->
                callback.onError(th as? IpcError ?: IpcError(ErrorCode.INTERNAL_ERROR, th.message ?: "Unknown"))
            }
        })

        outboundExecutor.submit {
            try {
                session.request(
                    RequestEnvelope(
                        reqId,
                        RequestEnvelope.OP_CALL,
                        command.id,
                        IpcPayload.ofAny(param),
                        deadline,
                        null,
                        null
                    )
                )
            } catch (e: RemoteException) {
                requestTracker.completeError(reqId, IpcError(ErrorCode.CONNECTION_LOST, "Remote call failed", reqId))
            }
        }
    }

    fun subscribe(
        keys: List<CapabilityKey>,
        options: SubscribeOptions = SubscribeOptions(),
        listener: (SubscriptionMessage) -> Unit
    ): CancelHandle {
        val subId = UUID.randomUUID().toString()
        val keyIds = keys.map { it.id }
        val record = ClientSubscriptionRecord(subId, keyIds, options.replayLatest, listener)
        subscriptions[subId] = record

        start()

        synchronized(lock) {
            activeSession?.let { session ->
                outboundExecutor.submit {
                    try {
                        session.subscribe(
                            SubscribeRequest(
                                subId,
                                keyIds,
                                options.replayLatest,
                                options.windowSize
                            )
                        )
                    } catch (e: RemoteException) {
                        Log.w("ConnectionController", "Subscribe failed on session", e)
                    }
                }
            }
        }

        return CancelHandle {
            subscriptions.remove(subId)
            synchronized(lock) {
                activeSession?.let { session ->
                    outboundExecutor.submit {
                        try {
                            session.unsubscribe(subId)
                        } catch (_: RemoteException) {}
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

    fun close() {
        synchronized(lock) {
            state = State.CLOSED
            registryConnector.unwatch(watchId, registryCallback)
            activeSession?.let {
                try {
                    it.close()
                } catch (_: Exception) {}
            }
            endpointDeathRecipient?.let {
                try {
                    activeEndpoint?.asBinder()?.unlinkToDeath(it, 0)
                } catch (_: Exception) {}
            }
            activeSession = null
            activeEndpoint = null
            subscriptions.clear()
            requestTracker.clearAllWithConnectionLost()
        }
    }
}
