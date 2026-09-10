package com.caripc.runtime

import android.os.Binder
import android.os.IBinder
import android.os.RemoteException
import com.caripc.contract.*
import com.caripc.protocol.*
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * 服务端会话与请求处理。
 *
 * 修复要点：
 * - 所有请求路径都有异常边界，异常一律转成显式错误响应（oneway 事务不会再把异常吞成「无响应」）；
 * - 契约校验（能力存在性、可读可写可观察、线类型、范围）在业务 handler 之前完成；
 * - 订阅建立与初始快照在同一临界区内完成，且快照帧先入队再对广播可见，杜绝倒序；
 * - 每个订阅同一时刻只有一个投递任务，保证 deliverySeq 单调；
 * - 队列溢出通过 KIND_GAP 通知订阅者。
 */
class SessionManager(
    val serviceDescriptor: ServiceDescriptor,
    val stateStore: StateStore,
    val permissionPolicy: PermissionPolicy,
    val outboundExecutor: OutboundExecutor,
    val onSetHandler: (keyId: String, value: Any?, callerUid: Int) -> SetReceipt,
    val onCallHandler: (commandId: String, param: Any?, callerUid: Int) -> Any?,
    val schema: ServiceSchema? = null,
    private val scheduler: TimeoutScheduler? = null
) {

    companion object {
        const val DELIVERY_BATCH_SIZE = 16
        const val DELIVERY_QUEUE_CAPACITY = 128
        const val MAX_SESSIONS = 64
        const val MAX_SESSIONS_PER_UID = 8
        const val MAX_SUBSCRIPTIONS_PER_SESSION = 32
        const val MAX_KEYS_PER_SUBSCRIPTION = 64
        const val MAX_SERVER_WAIT_MS = 30_000L

        /** v1 不做 ACK 窗口流控，向客户端显式声明为 0，避免空壳承诺。 */
        const val SUPPORTED_ACK_WINDOW = 0
    }

    class ClientSubscription(
        val subscriptionId: String,
        val keys: List<String>,
        val queue: DeliveryQueue
    ) {
        val draining = AtomicBoolean(false)
        val lastSentSeq = AtomicLong(0L)
        val highestAckedSeq = AtomicLong(0L)
        val staleAckCount = AtomicLong(0L)
    }

    class SessionContext(
        val sessionId: String,
        val ownerUid: Int,
        val ownerPackage: String,
        val clientInstanceId: String,
        val callback: IClientCallback,
        val deathRecipient: IBinder.DeathRecipient,
        val subscriptions: ConcurrentHashMap<String, ClientSubscription> = ConcurrentHashMap()
    )

    private val sessions = ConcurrentHashMap<String, SessionContext>()
    private val lock = Any()

    /** 正在执行的请求；用于取消语义判定。 */
    private val inFlightRequests = ConcurrentHashMap.newKeySet<String>()

    @Volatile
    private var serviceClosed = false

    /** 发布者关闭：拒绝新会话与后续请求，并清理已有会话。 */
    fun markServiceClosed() {
        serviceClosed = true
        closeAll()
    }

    fun isServiceClosed(): Boolean = serviceClosed

    /** 新建会话的结果：同时返回真实 sessionId（用于 ServerHello）与 ISession 句柄。 */
    data class CreatedSession(val sessionId: String, val session: ISession)

    fun createSession(callingUid: Int, hello: ClientHello, callback: IClientCallback): CreatedSession {
        if (serviceClosed) {
            throw IpcError(ErrorCode.SERVICE_CLOSED, "Service ${serviceDescriptor.serviceId} is closed")
        }
        val sessionId = UUID.randomUUID().toString()
        val clientPkg = permissionPolicy.getPackageName(callingUid)
        IpcLog.i(
            "SessionManager",
            "Client connected to [${serviceDescriptor.serviceId}]: package=$clientPkg, UID=$callingUid, sessionId=$sessionId, clientInstanceId=${hello.clientInstanceId}"
        )

        val ctx: SessionContext
        val deathRecipient = object : IBinder.DeathRecipient {
            override fun binderDied() {
                IpcLog.w(
                    "SessionManager",
                    "Client disconnected (binder died): package=$clientPkg, UID=$callingUid, sessionId=$sessionId"
                )
                removeSession(sessionId)
            }
        }

        synchronized(lock) {
            if (sessions.size >= MAX_SESSIONS) {
                throw IpcError(ErrorCode.RESOURCE_EXHAUSTED, "Too many active sessions (max $MAX_SESSIONS)")
            }
            val sameUid = sessions.values.count { it.ownerUid == callingUid }
            if (sameUid >= MAX_SESSIONS_PER_UID) {
                throw IpcError(
                    ErrorCode.RESOURCE_EXHAUSTED,
                    "UID $callingUid already holds $sameUid sessions (max $MAX_SESSIONS_PER_UID)"
                )
            }
            try {
                callback.asBinder().linkToDeath(deathRecipient, 0)
            } catch (e: RemoteException) {
                IpcLog.e("SessionManager", "Client callback already dead for sessionId=$sessionId", e)
                throw IpcError(ErrorCode.CONNECTION_LOST, "Client callback already dead")
            }
            ctx = SessionContext(
                sessionId,
                callingUid,
                clientPkg,
                hello.clientInstanceId,
                callback,
                deathRecipient
            )
            sessions[sessionId] = ctx
        }

        val sessionStub = object : ISession.Stub() {
            override fun request(req: RequestEnvelope) {
                if (!checkOwner(ctx, "request")) return
                IpcLog.d(
                    "SessionManager",
                    "Session[$sessionId] received request: op=${req.operation}, cap=${req.capabilityId}, reqId=${req.requestId}"
                )
                handleRequest(ctx, req)
            }

            override fun subscribe(req: SubscribeRequest) {
                if (!checkOwner(ctx, "subscribe")) {
                    sendSubscriptionError(ctx, req.subscriptionId, OWNER_MISMATCH_ERROR)
                    return
                }
                IpcLog.i(
                    "SessionManager",
                    "Client [pkg=${ctx.ownerPackage}, uid=${ctx.ownerUid}] subscribed to [${serviceDescriptor.serviceId}]: subId=${req.subscriptionId}, keys=${req.keys}"
                )
                handleSubscribe(ctx, req)
            }

            override fun unsubscribe(subscriptionId: String) {
                if (!checkOwner(ctx, "unsubscribe")) return
                IpcLog.i(
                    "SessionManager",
                    "Client [pkg=${ctx.ownerPackage}] unsubscribed from [${serviceDescriptor.serviceId}]: subId=$subscriptionId"
                )
                ctx.subscriptions.remove(subscriptionId)?.queue?.close()
            }

            override fun acknowledge(subscriptionId: String, deliverySeq: Long) {
                // ACK 只为观测与后续演进保留；v1 不做窗口流控（ServerHello.windowSize == 0 已声明）
                if (!checkOwner(ctx, "acknowledge")) return
                val sub = ctx.subscriptions[subscriptionId] ?: return
                val lastSent = sub.lastSentSeq.get()
                if (deliverySeq <= 0L || deliverySeq > lastSent) {
                    sub.staleAckCount.incrementAndGet()
                    IpcLog.w(
                        "SessionManager",
                        "Rejected out-of-range ACK subId=$subscriptionId seq=$deliverySeq (lastSent=$lastSent)"
                    )
                    return
                }
                while (true) {
                    val prev = sub.highestAckedSeq.get()
                    if (deliverySeq <= prev) {
                        sub.staleAckCount.incrementAndGet()
                        return
                    }
                    if (sub.highestAckedSeq.compareAndSet(prev, deliverySeq)) return
                }
            }

            override fun cancel(requestId: String) {
                if (!checkOwner(ctx, "cancel")) return
                // 服务端无法中断已进入业务执行的请求；不能把「发出取消」谎报为「取消成功」
                val executing = inFlightRequests.contains(requestId)
                val completion = if (executing) CompletionState.UNKNOWN else CompletionState.NOT_EXECUTED
                val message = if (executing) {
                    "Request $requestId is already executing; cancellation not confirmed"
                } else {
                    "Request $requestId cancelled before execution"
                }
                IpcLog.i("SessionManager", "cancel($requestId) from ${ctx.ownerPackage}: $message")
                respond(
                    ctx,
                    ResponseEnvelope(
                        requestId,
                        serviceDescriptor.instanceId,
                        ResponseEnvelope.STATUS_ERROR,
                        null,
                        ErrorEnvelope(ErrorCode.CANCELLED.code, message, requestId, completion.ordinal, null),
                        null,
                        null,
                        0,
                        0L,
                        TimeProvider.elapsedRealtime()
                    )
                )
            }

            override fun close() {
                if (!checkOwner(ctx, "close")) return
                removeSession(sessionId)
            }
        }
        return CreatedSession(sessionId, sessionStub)
    }

    private fun checkOwner(ctx: SessionContext, operation: String): Boolean {
        val caller = Binder.getCallingUid()
        return try {
            permissionPolicy.validateSessionOwner(caller, ctx.ownerUid)
            true
        } catch (e: IpcError) {
            IpcLog.w("SessionManager", "Session ${ctx.sessionId} rejected $operation from UID $caller: ${e.message}")
            false
        }
    }

    private val OWNER_MISMATCH_ERROR: IpcError
        get() = IpcError(
            ErrorCode.PERMISSION_DENIED,
            "Binder calling UID does not own this session",
            null,
            CompletionState.NOT_EXECUTED
        )

    // ---------------- 请求处理 ----------------

    private fun handleRequest(session: SessionContext, req: RequestEnvelope) {
        if (serviceClosed) {
            sendError(
                session,
                req.requestId,
                IpcError(ErrorCode.SERVICE_CLOSED, "Service is closed", req.requestId, CompletionState.NOT_EXECUTED)
            )
            return
        }
        val now = TimeProvider.elapsedRealtime()
        val deadline = clampDeadline(req.deadlineElapsedMs, now)
        if (deadline > 0L && now >= deadline) {
            sendError(
                session,
                req.requestId,
                IpcError(
                    ErrorCode.TIMEOUT,
                    "Request deadline passed before processing",
                    req.requestId,
                    CompletionState.NOT_EXECUTED
                )
            )
            return
        }

        inFlightRequests.add(req.requestId)
        try {
            when (req.operation) {
                RequestEnvelope.OP_GET -> handleGet(session, req)
                RequestEnvelope.OP_SET -> handleSetOperation(session, req, isConditional = false)
                RequestEnvelope.OP_SET_IF_VERSION -> handleSetOperation(session, req, isConditional = true)
                RequestEnvelope.OP_CALL -> handleCall(session, req)
                RequestEnvelope.OP_GET_OPERATION -> sendError(
                    session,
                    req.requestId,
                    IpcError(
                        ErrorCode.CAPABILITY_NOT_SUPPORTED,
                        "Operation tracking is not enabled in this build (see README: operationId tracking)",
                        req.requestId,
                        CompletionState.NOT_EXECUTED
                    )
                )
                else -> sendError(
                    session,
                    req.requestId,
                    IpcError(
                        ErrorCode.INVALID_ARGUMENT,
                        "Unknown operation ${req.operation}",
                        req.requestId,
                        CompletionState.NOT_EXECUTED
                    )
                )
            }
        } catch (e: IllegalArgumentException) {
            // 范围/NaN/长度等契约校验失败：明确的 INVALID_ARGUMENT，而不是笼统的 INTERNAL_ERROR
            IpcLog.w("SessionManager", "Request ${req.requestId} rejected by contract validation: ${e.message}")
            sendError(
                session,
                req.requestId,
                IpcError(
                    ErrorCode.INVALID_ARGUMENT,
                    e.message ?: "Invalid argument",
                    req.requestId,
                    CompletionState.NOT_EXECUTED
                )
            )
        } catch (e: IpcError) {
            IpcLog.w("SessionManager", "Request ${req.requestId} failed: ${e.message}")
            sendError(session, req.requestId, e)
        } catch (e: Throwable) {
            // oneway 事务不会把异常回传客户端；必须在这里兜底成显式错误响应
            IpcLog.e("SessionManager", "Request ${req.requestId} threw uncaught ${e::class.java.name}", e)
            sendError(
                session,
                req.requestId,
                IpcError(
                    ErrorCode.INTERNAL_ERROR,
                    e.message ?: e::class.java.simpleName,
                    req.requestId,
                    CompletionState.UNKNOWN
                )
            )
        } finally {
            inFlightRequests.remove(req.requestId)
        }
    }

    /** 服务端把客户端 deadline 钳制在允许的最大等待范围内（规格 11.2）。 */
    private fun clampDeadline(clientDeadline: Long, now: Long): Long {
        if (clientDeadline <= 0L) return 0L
        return minOf(clientDeadline, now + MAX_SERVER_WAIT_MS)
    }

    private fun handleGet(session: SessionContext, req: RequestEnvelope) {
        // schema == null 表示未装载契约（仅供内部/测试装配），此时跳过契约校验
        schema?.let { resolveProperty(req.capabilityId, needReadable = true) }
        permissionPolicy.authorizeOperation(session.ownerUid, serviceDescriptor.serviceId, req.capabilityId, isWrite = false)
        val snapshot = stateStore.readSnapshotById(req.capabilityId)
        respond(
            session,
            ResponseEnvelope(
                req.requestId,
                serviceDescriptor.instanceId,
                ResponseEnvelope.STATUS_OK,
                snapshot?.value?.let { IpcPayload.ofAny(it) },
                null,
                null,
                snapshot?.writeToken,
                snapshot?.quality?.value ?: Quality.UNINITIALIZED.value,
                snapshot?.revision ?: 0L,
                snapshot?.sourceElapsedMs ?: TimeProvider.elapsedRealtime()
            )
        )
    }

    private fun handleSetOperation(session: SessionContext, req: RequestEnvelope, isConditional: Boolean) {
        val key = schema?.let { resolveProperty(req.capabilityId, needReadable = false, needWritable = true) }
        permissionPolicy.authorizeOperation(session.ownerUid, serviceDescriptor.serviceId, req.capabilityId, isWrite = true)

        val decoded: Any? = if (key != null) {
            decodePayload(req.payload, key.type, req.capabilityId)
                ?: throw IpcError(
                    ErrorCode.INVALID_ARGUMENT,
                    "Null value is not allowed for property ${req.capabilityId}",
                    req.requestId,
                    CompletionState.NOT_EXECUTED
                )
        } else {
            req.payload?.toValue()
        }
        if (key != null) {
            val value = decoded ?: throw IpcError(
                ErrorCode.INVALID_ARGUMENT,
                "Null value is not allowed for property ${req.capabilityId}",
                req.requestId,
                CompletionState.NOT_EXECUTED
            )
            @Suppress("UNCHECKED_CAST")
            (key as PropertyKey<Any>).validate(value) // 范围/NaN/长度校验；失败抛 IllegalArgumentException
        }

        if (isConditional) {
            stateStore.validateAndAdvanceWriteToken(req.capabilityId, req.expectedWriteToken)
        }

        val receipt = onSetHandler(req.capabilityId, decoded, session.ownerUid)
        IpcLog.i(
            "SessionManager",
            "handleSetOperation: capabilityId=${req.capabilityId}, value=$decoded from Client[pkg=${session.ownerPackage}, uid=${session.ownerUid}] -> status=${receipt.status}"
        )

        val status = when (receipt.status) {
            SetStatus.ACCEPTED -> ResponseEnvelope.STATUS_ACCEPTED
            SetStatus.APPLIED -> ResponseEnvelope.STATUS_APPLIED
            SetStatus.REJECTED -> ResponseEnvelope.STATUS_ERROR
        }
        // 令牌在业务写入完成后再读一次，避免「返回即失效」（工单 P1-1）
        val writeToken = stateStore.writeTokenOf(req.capabilityId) ?: receipt.writeToken

        respond(
            session,
            ResponseEnvelope(
                req.requestId,
                serviceDescriptor.instanceId,
                status,
                null,
                receipt.error?.let { ErrorEnvelope(it) },
                receipt.operationId,
                writeToken,
                0,
                0L,
                TimeProvider.elapsedRealtime()
            )
        )
    }

    private fun handleCall(session: SessionContext, req: RequestEnvelope) {
        val command = schema?.let { resolveCommand(req.capabilityId) }
        permissionPolicy.authorizeOperation(session.ownerUid, serviceDescriptor.serviceId, req.capabilityId, isWrite = true)
        val arg = if (command != null) {
            decodePayload(req.payload, command.reqType, req.capabilityId, allowMissing = command.reqType == ValueType.NULL)
        } else {
            req.payload?.toValue()
        }
        val result = onCallHandler(req.capabilityId, arg, session.ownerUid)

        val payload: IpcPayload?
        if (command == null) {
            // 无契约装配：保持宽松语义，null 结果用显式 NULL 负载表达
            payload = IpcPayload.ofAny(result) ?: IpcPayload.ofNull()
        } else if (result == null) {
            if (command.respType == ValueType.NULL) {
                payload = IpcPayload.ofNull()
            } else {
                // 契约声明了返回类型但 handler 返回 null：是服务端契约违约，必须显式报错而不是让客户端猜
                throw IpcError(
                    ErrorCode.INTERNAL_ERROR,
                    "Command ${req.capabilityId} handler returned null but contract declares respType=${command.respType}",
                    req.requestId,
                    CompletionState.UNKNOWN
                )
            }
        } else {
            payload = IpcPayload.ofAny(result)
            val actual = safeTypeOf(payload)
            if (actual != command.respType) {
                throw IpcError(
                    ErrorCode.TYPE_MISMATCH,
                    "Command ${req.capabilityId} declares respType=${command.respType} but handler returned $actual",
                    req.requestId,
                    CompletionState.UNKNOWN
                )
            }
        }

        respond(
            session,
            ResponseEnvelope(
                req.requestId,
                serviceDescriptor.instanceId,
                ResponseEnvelope.STATUS_OK,
                payload,
                null,
                null,
                null,
                0,
                0L,
                TimeProvider.elapsedRealtime()
            )
        )
    }

    // ---------------- 契约校验 ----------------

    private fun resolveProperty(capabilityId: String, needReadable: Boolean, needWritable: Boolean = false): PropertyKey<*> {
        val currentSchema = schema ?: return PropertyKey.createString(capabilityId) // 无 schema（仅测试路径）不校验
        val key = currentSchema.findProperty(capabilityId)
            ?: throw IpcError(
                ErrorCode.UNKNOWN_CAPABILITY,
                "Capability $capabilityId is not declared in contract ${currentSchema.contractId}"
            )
        if (needReadable && !key.readable) {
            throw IpcError(ErrorCode.CAPABILITY_NOT_SUPPORTED, "Capability $capabilityId is not readable")
        }
        if (needWritable && !key.writable) {
            throw IpcError(ErrorCode.READ_ONLY, "Capability $capabilityId is read-only")
        }
        return key
    }

    private fun resolveCommand(commandId: String): CommandKey<*, *> {
        val currentSchema = schema ?: return CommandKey.stringToString(commandId)
        return currentSchema.findCommand(commandId)
            ?: throw IpcError(
                ErrorCode.UNKNOWN_CAPABILITY,
                "Command $commandId is not declared in contract ${currentSchema.contractId}"
            )
    }

    private fun isSubscribable(capabilityId: String): Boolean {
        val currentSchema = schema ?: return true
        currentSchema.findProperty(capabilityId)?.let { return it.observable }
        currentSchema.findEvent(capabilityId)?.let { return true }
        return false
    }

    private fun decodePayload(
        payload: IpcPayload?,
        expected: ValueType,
        capabilityId: String,
        allowMissing: Boolean = false
    ): Any? {
        if (payload == null) {
            if (expected == ValueType.NULL || allowMissing) return null
            throw IpcError(
                ErrorCode.INVALID_ARGUMENT,
                "Missing payload for $capabilityId (expected $expected)"
            )
        }
        val actual = safeTypeOf(payload)
        if (actual != expected) {
            throw IpcError(
                ErrorCode.TYPE_MISMATCH,
                "Capability $capabilityId expects $expected but received $actual"
            )
        }
        return payload.toValue()
    }

    private fun safeTypeOf(payload: IpcPayload): ValueType = try {
        ValueType.fromTag(payload.typeTag)
    } catch (_: IllegalArgumentException) {
        throw IpcError(ErrorCode.TYPE_MISMATCH, "Unknown wire type tag ${payload.typeTag}")
    }

    // ---------------- 订阅 ----------------

    private fun handleSubscribe(session: SessionContext, req: SubscribeRequest) {
        if (serviceClosed) {
            sendSubscriptionError(
                session,
                req.subscriptionId,
                IpcError(ErrorCode.SERVICE_CLOSED, "Service is closed")
            )
            return
        }
        if (req.subscriptionId.isBlank()) {
            sendSubscriptionError(
                session,
                req.subscriptionId,
                IpcError(ErrorCode.INVALID_ARGUMENT, "subscriptionId must not be blank")
            )
            return
        }
        if (req.keys.isEmpty()) {
            sendSubscriptionError(
                session,
                req.subscriptionId,
                IpcError(ErrorCode.INVALID_ARGUMENT, "keys must not be empty")
            )
            return
        }
        if (req.keys.size > MAX_KEYS_PER_SUBSCRIPTION) {
            sendSubscriptionError(
                session,
                req.subscriptionId,
                IpcError(
                    ErrorCode.RESOURCE_EXHAUSTED,
                    "Too many keys in one subscription (${req.keys.size} > $MAX_KEYS_PER_SUBSCRIPTION)"
                )
            )
            return
        }
        val invalidKey = req.keys.firstOrNull { !isSubscribable(it) }
        if (invalidKey != null) {
            sendSubscriptionError(
                session,
                req.subscriptionId,
                IpcError(
                    ErrorCode.UNKNOWN_CAPABILITY,
                    "Capability $invalidKey is not an observable property or event of contract ${schema?.contractId}"
                )
            )
            return
        }

        val existing = session.subscriptions[req.subscriptionId]
        if (existing != null && existing.keys != req.keys) {
            // 同 id 不同参数：明确报错，保留原订阅（规格 9.5）
            sendSubscriptionError(
                session,
                req.subscriptionId,
                IpcError(
                    ErrorCode.INVALID_ARGUMENT,
                    "Subscription ${req.subscriptionId} already exists with different keys"
                )
            )
            return
        }
        if (existing == null && session.subscriptions.size >= MAX_SUBSCRIPTIONS_PER_SESSION) {
            sendSubscriptionError(
                session,
                req.subscriptionId,
                IpcError(
                    ErrorCode.RESOURCE_EXHAUSTED,
                    "Too many subscriptions in this session (max $MAX_SUBSCRIPTIONS_PER_SESSION)"
                )
            )
            return
        }

        if (req.windowSize > 0 && SUPPORTED_ACK_WINDOW == 0) {
            IpcLog.d(
                "SessionManager",
                "Client requested ACK window=${req.windowSize} but this build advertises windowSize=0 (no ACK flow control)"
            )
        }

        val queue = DeliveryQueue(req.subscriptionId, DELIVERY_QUEUE_CAPACITY)
        val clientSub = ClientSubscription(req.subscriptionId, req.keys, queue)

        // 原子建立：读快照 → 快照帧入队 → 再对广播可见（工单 P1-4）
        stateStore.withStateLock {
            val snapshotId = UUID.randomUUID().toString()
            val frames = if (req.replayLatest) {
                val snapshots = stateStore.readAllSnapshots(req.keys)
                snapshots.mapIndexed { index, prop ->
                    SubscriptionEnvelope(
                        req.subscriptionId,
                        serviceDescriptor.instanceId,
                        SubscriptionEnvelope.KIND_PROPERTY,
                        prop.key.id,
                        0L,
                        prop.revision,
                        IpcPayload.ofAny(prop.value),
                        prop.quality.value,
                        prop.sourceElapsedMs,
                        null,
                        snapshotId,
                        index,
                        index == snapshots.size - 1,
                        null
                    )
                }
            } else {
                emptyList()
            }
            frames.forEach { frame ->
                if (!queue.enqueue(frame)) {
                    // 容量已按 MAX_KEYS_PER_SUBSCRIPTION 预留，理论不可达；仍显式记录，不静默丢快照
                    IpcLog.e(
                        "SessionManager",
                        "Initial snapshot frame dropped for ${req.subscriptionId} (key=${frame.capabilityId}); capacity=$DELIVERY_QUEUE_CAPACITY"
                    )
                }
            }
            session.subscriptions[req.subscriptionId] = clientSub
        }
        existing?.queue?.close()
        scheduleDrain(session, clientSub)
    }

    /** 供 StateStore 的发射管线调用：把一帧状态/事件投递给所有匹配订阅。 */
    fun broadcastPropertyUpdate(envelope: SubscriptionEnvelope) {
        val drains = ArrayList<Pair<SessionContext, ClientSubscription>>()
        // 与订阅注册互斥：保证「快照帧先入队、更新帧后入队」，且帧的版本推进顺序与入队顺序一致
        stateStore.withStateLock {
            for (session in sessions.values) {
                for (sub in session.subscriptions.values) {
                    if (envelope.capabilityId !in sub.keys) continue
                    val subEnvelope = SubscriptionEnvelope(
                        sub.subscriptionId,
                        envelope.serviceInstanceId,
                        envelope.kind,
                        envelope.capabilityId,
                        0L,
                        envelope.revision,
                        envelope.payload,
                        envelope.quality,
                        envelope.sourceElapsedMs,
                        envelope.causeOperationId,
                        envelope.snapshotId,
                        envelope.snapshotIndex,
                        envelope.isSnapshotEnd,
                        envelope.error,
                        envelope.gapFromSeq
                    )
                    sub.queue.enqueue(subEnvelope)
                    drains.add(session to sub)
                }
            }
        }
        drains.forEach { (session, sub) -> scheduleDrain(session, sub) }
    }

    /** 单飞投递：同一订阅同一时刻只有一个 drain 任务，保证 deliverySeq 单调（工单 P1-5）。 */
    private fun scheduleDrain(session: SessionContext, sub: ClientSubscription) {
        if (!sub.draining.compareAndSet(false, true)) return
        val accepted = outboundExecutor.submit { runDrain(session, sub) }
        if (!accepted) {
            sub.draining.set(false)
            IpcLog.e(
                "SessionManager",
                "Outbound executor rejected delivery for ${sub.subscriptionId}; retrying via scheduler"
            )
            sub.queue.enqueue(
                errorEnvelope(
                    sub.subscriptionId,
                    IpcError(
                        ErrorCode.RESOURCE_EXHAUSTED,
                        "Outbound executor queue is full; delivery delayed"
                    )
                )
            )
            retryDrainLater(session, sub)
        }
    }

    private fun retryDrainLater(session: SessionContext, sub: ClientSubscription) {
        val schedulerRef = scheduler ?: return
        var handle: ScheduledTick? = null
        handle = schedulerRef.schedulePeriodic(10L) {
            handle?.cancel()
            scheduleDrain(session, sub)
        }
    }

    private fun runDrain(session: SessionContext, sub: ClientSubscription) {
        try {
            while (true) {
                val batch = sub.queue.pollBatch(DELIVERY_BATCH_SIZE)
                if (batch.isEmpty()) return
                for (message in batch) {
                    sub.lastSentSeq.set(message.deliverySeq)
                    try {
                        session.callback.onSubscriptionMessage(message)
                    } catch (e: RemoteException) {
                        IpcLog.w(
                            "SessionManager",
                            "Failed to deliver message to session ${session.sessionId}; dropping remaining frames",
                            e
                        )
                        sub.queue.close()
                        return
                    } catch (t: Throwable) {
                        IpcLog.e("SessionManager", "Subscription callback threw", t)
                    }
                }
            }
        } finally {
            sub.draining.set(false)
            if (sub.queue.size() > 0) scheduleDrain(session, sub)
        }
    }

    private fun sendSubscriptionError(session: SessionContext, subscriptionId: String, error: IpcError) {
        IpcLog.w("SessionManager", "Subscription $subscriptionId rejected: ${error.message}")
        val queue = DeliveryQueue(subscriptionId, 4)
        queue.enqueue(errorEnvelope(subscriptionId, error))
        val sub = ClientSubscription(subscriptionId, emptyList(), queue)
        val accepted = outboundExecutor.submit { runDrain(session, sub) }
        if (!accepted) runDrain(session, sub)
    }

    private fun errorEnvelope(subscriptionId: String, error: IpcError): SubscriptionEnvelope {
        return SubscriptionEnvelope(
            subscriptionId,
            serviceDescriptor.instanceId,
            SubscriptionEnvelope.KIND_ERROR,
            "",
            0L,
            0L,
            null,
            0,
            TimeProvider.elapsedRealtime(),
            null,
            null,
            0,
            false,
            ErrorEnvelope(error)
        )
    }

    // ---------------- 响应发送 ----------------

    private fun sendError(session: SessionContext, requestId: String, error: IpcError) {
        respond(
            session,
            ResponseEnvelope(
                requestId,
                serviceDescriptor.instanceId,
                ResponseEnvelope.STATUS_ERROR,
                null,
                ErrorEnvelope(error),
                null,
                null,
                0,
                0L,
                TimeProvider.elapsedRealtime()
            )
        )
    }

    private fun respond(session: SessionContext, response: ResponseEnvelope) {
        sendResponse(session, response)
    }

    private fun sendResponse(session: SessionContext, response: ResponseEnvelope) {
        val accepted = outboundExecutor.submit {
            try {
                session.callback.onResult(response)
            } catch (e: RemoteException) {
                IpcLog.w("SessionManager", "Failed to send response ${response.requestId}", e)
            }
        }
        if (!accepted) {
            // 响应不能因为执行器拒绝而悄悄丢掉：退化为调用线程直接发送（oneway，不阻塞）
            IpcLog.w("SessionManager", "Outbound executor rejected response ${response.requestId}; sending inline")
            try {
                session.callback.onResult(response)
            } catch (e: RemoteException) {
                IpcLog.w("SessionManager", "Inline response delivery failed ${response.requestId}", e)
            }
        }
    }

    // ---------------- 会话清理与诊断 ----------------

    fun removeSession(sessionId: String) {
        val session = sessions.remove(sessionId) ?: return
        try {
            session.callback.asBinder().unlinkToDeath(session.deathRecipient, 0)
        } catch (_: Exception) {
        }
        for (sub in session.subscriptions.values) {
            sub.queue.close()
        }
        session.subscriptions.clear()
    }

    fun closeAll() {
        for (sessionId in sessions.keys) {
            removeSession(sessionId)
        }
    }

    fun activeSessionCount(): Int = sessions.size

    fun dumpSessions(): String {
        val sb = StringBuilder()
        sb.appendLine("CarIpc Service [${serviceDescriptor.serviceId}] Active Connected Clients (${sessions.size}):")
        if (sessions.isEmpty()) {
            sb.appendLine("  (No clients connected)")
        } else {
            sessions.values.forEachIndexed { index, session ->
                sb.appendLine("  [${index + 1}] Client: ${session.ownerPackage} (UID: ${session.ownerUid})")
                sb.appendLine("      SessionId : ${session.sessionId}")
                sb.appendLine("      ClientInst: ${session.clientInstanceId}")
                sb.appendLine("      Subscriptions (${session.subscriptions.size}):")
                session.subscriptions.values.forEach { sub ->
                    sb.appendLine(
                        "        - subId=${sub.subscriptionId}, keys=${sub.keys}, " +
                            "pending=${sub.queue.size()}, lastSentSeq=${sub.lastSentSeq.get()}, " +
                            "ackedSeq=${sub.highestAckedSeq.get()}, staleAcks=${sub.staleAckCount.get()}, " +
                            "droppedEvents=${sub.queue.droppedEventCount}, droppedFrames=${sub.queue.droppedFrameCount}, " +
                            "coalesced=${sub.queue.coalescedPropertyCount}, gapNotices=${sub.queue.gapNoticeCount}"
                    )
                }
            }
        }
        return sb.toString()
    }
}
