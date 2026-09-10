package com.caripc.sdk

import android.content.Context
import com.caripc.contract.*
import com.caripc.protocol.*
import com.caripc.runtime.*
import java.io.Closeable
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

fun interface OnSetHandler {
    fun onSet(keyId: String, value: Any?, callerUid: Int): SetReceipt
}

fun interface OnCallHandler {
    fun onCall(commandId: String, param: Any?, callerUid: Int): Any?
}

class CarIpc private constructor(
    private val context: Context,
    private val config: CarIpcConfig
) : Closeable {

    private val scheduler: TimeoutScheduler = ExecutorTimeoutScheduler()

    private val outboundExecutor = OutboundExecutor(
        corePoolSize = config.outboundCoreThreads,
        maxPoolSize = config.outboundMaxThreads
    )

    private val registryConnector = RegistryConnector(
        context = context,
        registryComponent = config.registryComponent
    )

    private val permissionPolicy = PermissionPolicy(
        context = context,
        strictMode = config.strictPermissionMode,
        aclProvider = config.aclProvider
    )

    private val publishers = ConcurrentHashMap<String, ServicePublisherImpl>()
    private val connections = ConcurrentHashMap<String, RemoteServiceImpl>()

    fun publishService(
        serviceId: String,
        schema: ServiceSchema,
        onSetHandler: OnSetHandler,
        onCallHandler: OnCallHandler
    ): ServicePublisher {
        return publishService(serviceId, schema, onSetHandler::onSet, onCallHandler::onCall)
    }

    fun publishService(
        serviceId: String,
        schema: ServiceSchema,
        onSet: (keyId: String, value: Any?, callerUid: Int) -> SetReceipt,
        onCall: (commandId: String, param: Any?, callerUid: Int) -> Any?
    ): ServicePublisher {
        val instanceId = UUID.randomUUID().toString()
        IpcLog.i("CarIpc", "publishService() called for serviceId=$serviceId (instanceId=$instanceId)")

        // StateStore 与 SessionManager 互为依赖：用持有者打破循环（发射管线 → SessionManager 广播）
        val sessionManagerHolder = arrayOfNulls<SessionManager>(1)
        val stateStore = StateStore(instanceId, scheduler) { envelope ->
            sessionManagerHolder[0]?.broadcastPropertyUpdate(envelope)
        }

        // 注册 schema 中声明的所有 properties
        schema.properties.forEach { stateStore.registerProperty(it) }

        val capabilities = mutableListOf<String>().apply {
            addAll(schema.properties.map { it.id })
            addAll(schema.events.map { it.id })
            addAll(schema.commands.map { it.id })
        }

        val descriptor = ServiceDescriptor(
            serviceId,
            schema.contractId,
            schema.major,
            schema.minor,
            1, 0,
            instanceId,
            0L,
            0,
            0,
            capabilities
        )

        schema.commands.filter { it.retryPolicy != RetryPolicy.NEVER }.forEach {
            IpcLog.w(
                "CarIpc",
                "Command ${it.id} declares RetryPolicy.${it.retryPolicy} but this build does NOT implement automatic retry; " +
                    "callers must handle retries themselves (see README: 重试与幂等)"
            )
        }

        val sessionManager = SessionManager(
            serviceDescriptor = descriptor,
            stateStore = stateStore,
            permissionPolicy = permissionPolicy,
            outboundExecutor = outboundExecutor,
            onSetHandler = onSet,
            onCallHandler = onCall,
            schema = schema,
            scheduler = scheduler
        )
        sessionManagerHolder[0] = sessionManager

        val endpointHost = EndpointHost(sessionManager)

        val publisher = ServicePublisherImpl(
            serviceId,
            descriptor,
            stateStore,
            sessionManager,
            endpointHost,
            registryConnector,
            outboundExecutor
        )

        publishers[serviceId] = publisher
        publisher.startPublish()
        return publisher
    }

    fun connect(serviceId: String): RemoteService {
        IpcLog.i("CarIpc", "connect() called for serviceId=$serviceId")
        return connections.computeIfAbsent(serviceId) {
            val controller = ConnectionController(
                serviceId = serviceId,
                registryConnector = registryConnector,
                outboundExecutor = outboundExecutor,
                scheduler = scheduler
            )
            RemoteServiceImpl(controller, config)
        }
    }

    override fun close() {
        publishers.values.forEach { it.close() }
        publishers.clear()
        connections.values.forEach { it.close() }
        connections.clear()
        registryConnector.close()
        scheduler.shutdown()
        outboundExecutor.shutdown()
    }

    companion object {
        @JvmStatic
        @JvmOverloads
        fun create(context: Context, config: CarIpcConfig = CarIpcConfig()): CarIpc {
            IpcLog.isDebugEnabled = config.debugLogging
            return CarIpc(context.applicationContext ?: context, config)
        }
    }

    private class ServicePublisherImpl(
        val serviceId: String,
        val descriptor: ServiceDescriptor,
        val stateStore: StateStore,
        val sessionManager: SessionManager,
        val endpointHost: EndpointHost,
        val registryConnector: RegistryConnector,
        val outboundExecutor: OutboundExecutor
    ) : ServicePublisher {

        private var regToken: RegistrationToken? = null

        @Volatile
        private var closed = false

        fun startPublish() {
            registryConnector.publish(descriptor, endpointHost, object : IRegistryCallback.Stub() {
                override fun onPublished(token: RegistrationToken) {
                    regToken = token
                    if (closed) {
                        // close() 早于注册回调：立即撤销，避免「复活」一个已关闭的服务（工单 P1-8）
                        IpcLog.i("CarIpc", "Publisher for $serviceId closed before registration ack; unpublishing $token")
                        registryConnector.unpublish(token)
                    }
                }

                override fun onPublishFailed(svcId: String, error: ErrorEnvelope) {
                    IpcLog.w("CarIpc", "Publish failed for $svcId: ${error.message}")
                }

                override fun onSnapshot(svcId: String, wId: Long, d: ServiceDescriptor, e: IEndpoint) {}

                override fun onServiceUnavailable(svcId: String, wId: Long) {}

                override fun onServiceChanged(svcId: String, wId: Long, d: ServiceDescriptor, e: IEndpoint) {}

                override fun onError(wId: Long, error: ErrorEnvelope) {}
            })
        }

        override fun <T : Any> update(key: PropertyKey<T>, value: T) {
            update(key, value, Quality.VALID)
        }

        override fun <T : Any> update(key: PropertyKey<T>, value: T, quality: Quality) {
            // 通知由 StateStore 的有序发射管线统一送出（含限频补发），调用方不再手工广播
            stateStore.update(key, value, quality)
        }

        override fun <T : Any> emit(eventKey: EventKey<T>, payload: T) {
            stateStore.emitEvent(eventKey.id, IpcPayload.ofAny(payload))
        }

        override fun close() {
            if (closed) return
            closed = true
            // 1. 先撤销发布意图：注册中心重连后不会再把已关闭的服务重新发布
            registryConnector.cancelPublishIntent(serviceId)
            // 2. 已拿到 token 则显式注销
            regToken?.let { registryConnector.unpublish(it) }
            regToken = null
            // 3. 停止对外服务：拒绝新会话、清理已有会话与状态
            sessionManager.markServiceClosed()
            stateStore.close()
        }
    }

    private class RemoteServiceImpl(
        val controller: ConnectionController,
        val config: CarIpcConfig
    ) : RemoteService {

        init {
            controller.start()
        }

        private fun <T> toResultCallback(ipcCallback: IpcCallback<T>): ResultCallback<T> {
            return ResultCallback { result ->
                result.onSuccess { ipcCallback.onSuccess(it) }
                result.onFailure { th ->
                    ipcCallback.onError(th as? IpcError ?: IpcError(ErrorCode.INTERNAL_ERROR, th.message ?: "Unknown"))
                }
            }
        }

        override fun awaitReady(timeoutMs: Long, callback: ResultCallback<Unit>) {
            controller.awaitReady(if (timeoutMs > 0) timeoutMs else config.awaitReadyTimeoutMs) { res ->
                res.onSuccess { callback.onSuccess(Unit) }
                res.onFailure { th -> callback.onError(th as? IpcError ?: IpcError(ErrorCode.INTERNAL_ERROR, th.message ?: "Unknown")) }
            }
        }

        override fun awaitReady(timeoutMs: Long, callback: IpcCallback<Unit>) {
            awaitReady(timeoutMs, toResultCallback(callback))
        }

        override fun <T : Any> get(key: PropertyKey<T>, callback: ResultCallback<PropertySnapshot<T>>) {
            controller.get(key, config.requestTimeoutMs, callback)
        }

        override fun <T : Any> get(key: PropertyKey<T>, callback: IpcCallback<PropertySnapshot<T>>) {
            get(key, toResultCallback(callback))
        }

        override fun <T : Any> set(key: PropertyKey<T>, value: T, callback: ResultCallback<SetReceipt>) {
            controller.set(key, value, null, config.requestTimeoutMs, callback)
        }

        override fun <T : Any> set(key: PropertyKey<T>, value: T, callback: IpcCallback<SetReceipt>) {
            set(key, value, toResultCallback(callback))
        }

        override fun <T : Any> setIfVersion(key: PropertyKey<T>, value: T, writeToken: String?, callback: ResultCallback<SetReceipt>) {
            controller.set(key, value, writeToken, config.requestTimeoutMs, callback)
        }

        override fun <T : Any> setIfVersion(key: PropertyKey<T>, value: T, writeToken: String?, callback: IpcCallback<SetReceipt>) {
            setIfVersion(key, value, writeToken, toResultCallback(callback))
        }

        override fun <Req : Any, Resp : Any> call(command: CommandKey<Req, Resp>, param: Req, callback: ResultCallback<Resp>) {
            controller.call(command, param, config.requestTimeoutMs, callback)
        }

        override fun <Req : Any, Resp : Any> call(command: CommandKey<Req, Resp>, param: Req, callback: IpcCallback<Resp>) {
            call(command, param, toResultCallback(callback))
        }

        override fun subscribe(
            keys: List<CapabilityKey>,
            options: SubscribeOptions,
            listener: RemoteService.SubscriptionListener
        ): CancelHandle {
            return controller.subscribe(keys, options) { msg ->
                listener.onMessage(msg)
            }
        }

        override fun close() {
            controller.close()
        }
    }
}
