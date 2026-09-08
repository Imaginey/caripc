package com.caripc.runtime

import android.os.Binder
import android.os.IBinder
import android.os.RemoteException
import android.os.SystemClock
import android.util.Log
import com.caripc.contract.*
import com.caripc.protocol.*
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

class SessionManager(
    val serviceDescriptor: ServiceDescriptor,
    val stateStore: StateStore,
    val permissionPolicy: PermissionPolicy,
    val outboundExecutor: OutboundExecutor,
    val onSetHandler: (keyId: String, value: Any?, callerUid: Int) -> SetReceipt,
    val onCallHandler: (commandId: String, param: Any?, callerUid: Int) -> Any?
) {
    class ClientSubscription(
        val subscriptionId: String,
        val keys: List<String>,
        val queue: DeliveryQueue
    )

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

    fun createSession(callingUid: Int, hello: ClientHello, callback: IClientCallback): ISession {
        val sessionId = UUID.randomUUID().toString()
        val clientPkg = permissionPolicy.getPackageName(callingUid)
        IpcLog.i("SessionManager", "Client connected to [${serviceDescriptor.serviceId}]: package=$clientPkg, UID=$callingUid, sessionId=$sessionId, clientInstanceId=${hello.clientInstanceId}")

        var sessionContext: SessionContext? = null
        val deathRecipient = object : IBinder.DeathRecipient {
            override fun binderDied() {
                IpcLog.w("SessionManager", "Client disconnected (binder died): package=${sessionContext?.ownerPackage ?: clientPkg}, UID=$callingUid, sessionId=$sessionId")
                removeSession(sessionId)
            }
        }

        try {
            callback.asBinder().linkToDeath(deathRecipient, 0)
        } catch (e: RemoteException) {
            IpcLog.e("SessionManager", "Client callback already dead for sessionId=$sessionId", e)
            throw IpcError(ErrorCode.CONNECTION_LOST, "Client callback already dead")
        }

        val ctx = SessionContext(
            sessionId,
            callingUid,
            clientPkg,
            hello.clientInstanceId,
            callback,
            deathRecipient
        )
        sessionContext = ctx
        sessions[sessionId] = ctx

        return object : ISession.Stub() {
            override fun request(req: RequestEnvelope) {
                val caller = Binder.getCallingUid()
                IpcLog.d("SessionManager", "Session[$sessionId] received request: op=${req.operation}, cap=${req.capabilityId}, reqId=${req.requestId} from [${ctx.ownerPackage}, UID $caller]")
                permissionPolicy.validateSessionOwner(caller, ctx.ownerUid)
                handleRequest(ctx, req)
            }

            override fun subscribe(req: SubscribeRequest) {
                val caller = Binder.getCallingUid()
                IpcLog.i("SessionManager", "Client [pkg=${ctx.ownerPackage}, uid=$caller] subscribed to [${serviceDescriptor.serviceId}]: subId=${req.subscriptionId}, keys=${req.keys}")
                permissionPolicy.validateSessionOwner(caller, ctx.ownerUid)
                handleSubscribe(ctx, req)
            }

            override fun unsubscribe(subscriptionId: String) {
                val caller = Binder.getCallingUid()
                IpcLog.i("SessionManager", "Client [pkg=${ctx.ownerPackage}, uid=$caller] unsubscribed from [${serviceDescriptor.serviceId}]: subId=$subscriptionId")
                permissionPolicy.validateSessionOwner(caller, ctx.ownerUid)
                val sub = ctx.subscriptions.remove(subscriptionId)
                sub?.queue?.close()
            }

            override fun acknowledge(subscriptionId: String, deliverySeq: Long) {
                // P0 默认不强制 ACK 逻辑，若有需要预留 ACK 入口
            }

            override fun cancel(requestId: String) {
                val caller = Binder.getCallingUid()
                permissionPolicy.validateSessionOwner(caller, sessionContext.ownerUid)
            }

            override fun close() {
                val caller = Binder.getCallingUid()
                permissionPolicy.validateSessionOwner(caller, sessionContext.ownerUid)
                removeSession(sessionId)
            }
        }
    }

    private fun handleRequest(session: SessionContext, req: RequestEnvelope) {
        val now = TimeProvider.elapsedRealtime()
        if (req.deadlineElapsedMs > 0 && now >= req.deadlineElapsedMs) {
            sendResponse(
                session,
                ResponseEnvelope(
                    req.requestId,
                    serviceDescriptor.instanceId,
                    ResponseEnvelope.STATUS_ERROR,
                    null,
                    ErrorEnvelope(ErrorCode.TIMEOUT.code, "Request deadline passed before processing", req.requestId, CompletionState.NOT_EXECUTED.ordinal, null),
                    null, null, 0, 0L, now
                )
            )
            return
        }

        when (req.operation) {
            RequestEnvelope.OP_GET -> {
                try {
                    permissionPolicy.authorizeOperation(session.ownerUid, serviceDescriptor.serviceId, req.capabilityId, isWrite = false)
                    val snapshot = stateStore.readSnapshot(PropertyKey.createString(req.capabilityId))
                    val payload = IpcPayload.ofAny(snapshot.value)
                    sendResponse(
                        session,
                        ResponseEnvelope(
                            req.requestId,
                            serviceDescriptor.instanceId,
                            ResponseEnvelope.STATUS_OK,
                            payload,
                            null,
                            null,
                            snapshot.writeToken,
                            snapshot.quality.value,
                            snapshot.revision,
                            snapshot.sourceElapsedMs
                        )
                    )
                } catch (e: IpcError) {
                    sendResponse(
                        session,
                        ResponseEnvelope(
                            req.requestId,
                            serviceDescriptor.instanceId,
                            ResponseEnvelope.STATUS_ERROR,
                            null,
                            ErrorEnvelope(e),
                            null, null, 0, 0L, now
                        )
                    )
                }
            }

            RequestEnvelope.OP_SET -> {
                handleSetOperation(session, req, isConditional = false)
            }

            RequestEnvelope.OP_SET_IF_VERSION -> {
                handleSetOperation(session, req, isConditional = true)
            }

            RequestEnvelope.OP_CALL -> {
                try {
                    permissionPolicy.authorizeOperation(session.ownerUid, serviceDescriptor.serviceId, req.capabilityId, isWrite = true)
                    val arg = req.payload?.toValue()
                    val result = onCallHandler(req.capabilityId, arg, session.ownerUid)
                    val respPayload = IpcPayload.ofAny(result)
                    sendResponse(
                        session,
                        ResponseEnvelope(
                            req.requestId,
                            serviceDescriptor.instanceId,
                            ResponseEnvelope.STATUS_OK,
                            respPayload,
                            null,
                            null, null, 0, 0L, now
                        )
                    )
                } catch (e: IpcError) {
                    sendResponse(
                        session,
                        ResponseEnvelope(
                            req.requestId,
                            serviceDescriptor.instanceId,
                            ResponseEnvelope.STATUS_ERROR,
                            null,
                            ErrorEnvelope(e),
                            null, null, 0, 0L, now
                        )
                    )
                } catch (e: Exception) {
                    sendResponse(
                        session,
                        ResponseEnvelope(
                            req.requestId,
                            serviceDescriptor.instanceId,
                            ResponseEnvelope.STATUS_ERROR,
                            null,
                            ErrorEnvelope(ErrorCode.INTERNAL_ERROR.code, e.message ?: "Call error", req.requestId, CompletionState.UNKNOWN.ordinal, null),
                            null, null, 0, 0L, now
                        )
                    )
                }
            }

            RequestEnvelope.OP_GET_OPERATION -> {
                sendResponse(
                    session,
                    ResponseEnvelope(
                        req.requestId,
                        serviceDescriptor.instanceId,
                        ResponseEnvelope.STATUS_ERROR,
                        null,
                        ErrorEnvelope(ErrorCode.CAPABILITY_NOT_SUPPORTED.code, "Operation tracking not enabled", req.requestId, CompletionState.NOT_EXECUTED.ordinal, null),
                        null, null, 0, 0L, now
                    )
                )
            }

            else -> {
                sendResponse(
                    session,
                    ResponseEnvelope(
                        req.requestId,
                        serviceDescriptor.instanceId,
                        ResponseEnvelope.STATUS_ERROR,
                        null,
                        ErrorEnvelope(ErrorCode.INVALID_ARGUMENT.code, "Unknown operation ${req.operation}", req.requestId, CompletionState.NOT_EXECUTED.ordinal, null),
                        null, null, 0, 0L, now
                    )
                )
            }
        }
    }

    private fun handleSetOperation(session: SessionContext, req: RequestEnvelope, isConditional: Boolean) {
        val now = TimeProvider.elapsedRealtime()
        try {
            permissionPolicy.authorizeOperation(session.ownerUid, serviceDescriptor.serviceId, req.capabilityId, isWrite = true)

            // 如果是条件写，原子检查写版本
            var newWriteToken: String? = null
            if (isConditional) {
                newWriteToken = stateStore.validateAndAdvanceWriteToken(req.capabilityId, req.expectedWriteToken)
            }

            val rawVal = req.payload?.toValue()
            val receipt = onSetHandler(req.capabilityId, rawVal, session.ownerUid)
            IpcLog.i("SessionManager", "handleSetOperation: capabilityId=${req.capabilityId}, value=$rawVal from Client[pkg=${session.ownerPackage}, uid=${session.ownerUid}] -> status=${receipt.status}")

            val status = when (receipt.status) {
                SetStatus.ACCEPTED -> ResponseEnvelope.STATUS_ACCEPTED
                SetStatus.APPLIED -> ResponseEnvelope.STATUS_APPLIED
                SetStatus.REJECTED -> ResponseEnvelope.STATUS_ERROR
            }

            sendResponse(
                session,
                ResponseEnvelope(
                    req.requestId,
                    serviceDescriptor.instanceId,
                    status,
                    null,
                    receipt.error?.let { ErrorEnvelope(it) },
                    receipt.operationId,
                    newWriteToken ?: receipt.writeToken,
                    0, 0L, now
                )
            )
        } catch (e: IpcError) {
            IpcLog.w("SessionManager", "handleSetOperation failed: ${e.message}")
            sendResponse(
                session,
                ResponseEnvelope(
                    req.requestId,
                    serviceDescriptor.instanceId,
                    ResponseEnvelope.STATUS_ERROR,
                    null,
                    ErrorEnvelope(e),
                    null, null, 0, 0L, now
                )
            )
        }
    }

    private fun handleSubscribe(session: SessionContext, req: SubscribeRequest) {
        val queue = DeliveryQueue(req.subscriptionId, capacity = 128)
        val clientSub = ClientSubscription(req.subscriptionId, req.keys, queue)
        session.subscriptions[req.subscriptionId] = clientSub

        // 原子读取初始快照并排入队列
        if (req.replayLatest) {
            val snapshots = stateStore.readAllSnapshots(req.keys)
            val snapshotId = UUID.randomUUID().toString()
            snapshots.forEachIndexed { index, prop ->
                val isEnd = index == snapshots.size - 1
                val envelope = SubscriptionEnvelope(
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
                    isEnd,
                    null
                )
                queue.enqueue(envelope)
            }
        }

        drainSubscriptionQueue(session, clientSub)
    }

    fun broadcastPropertyUpdate(envelope: SubscriptionEnvelope) {
        for (session in sessions.values) {
            for (sub in session.subscriptions.values) {
                if (envelope.capabilityId in sub.keys) {
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
                        envelope.error
                    )
                    sub.queue.enqueue(subEnvelope)
                    drainSubscriptionQueue(session, sub)
                }
            }
        }
    }

    private fun drainSubscriptionQueue(session: SessionContext, sub: ClientSubscription) {
        outboundExecutor.submit {
            val batch = sub.queue.pollBatch(16)
            for (msg in batch) {
                try {
                    session.callback.onSubscriptionMessage(msg)
                } catch (e: RemoteException) {
                    Log.w("SessionManager", "Failed to deliver message to session ${session.sessionId}", e)
                    break
                }
            }
            if (sub.queue.size() > 0) {
                drainSubscriptionQueue(session, sub)
            }
        }
    }

    private fun sendResponse(session: SessionContext, response: ResponseEnvelope) {
        outboundExecutor.submit {
            try {
                session.callback.onResult(response)
            } catch (e: RemoteException) {
                Log.w("SessionManager", "Failed to send response ${response.requestId}", e)
            }
        }
    }

    fun removeSession(sessionId: String) {
        val session = sessions.remove(sessionId) ?: return
        try {
            session.callback.asBinder().unlinkToDeath(session.deathRecipient, 0)
        } catch (_: Exception) {}
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
                    sb.appendLine("        - subId=${sub.subscriptionId}, keys=${sub.keys}")
                }
            }
        }
        return sb.toString()
    }
}
