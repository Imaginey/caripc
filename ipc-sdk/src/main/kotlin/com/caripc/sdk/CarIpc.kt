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

    private val outboundExecutor = OutboundExecutor(
        corePoolSize = config.outboundCoreThreads,
        maxPoolSize = config.outboundMaxThreads
    )

    private val registryConnector = RegistryConnector(
        context = context,
        registryComponent = config.registryComponent
    )

    private val permissionPolicy = PermissionPolicy(context)
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
        val stateStore = StateStore(instanceId)

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
            0, 0,
            capabilities
        )

        val sessionManager = SessionManager(
            descriptor,
            stateStore,
            permissionPolicy,
            outboundExecutor,
            onSet,
            onCall
        )

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
                serviceId,
                registryConnector,
                outboundExecutor
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
        outboundExecutor.shutdown()
    }

    companion object {
        @JvmStatic
        @JvmOverloads
        fun create(context: Context, config: CarIpcConfig = CarIpcConfig()): CarIpc {
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

        fun startPublish() {
            registryConnector.publish(descriptor, endpointHost, object : IRegistryCallback.Stub() {
                override fun onPublished(token: RegistrationToken) {
                    regToken = token
                }
                override fun onPublishFailed(svcId: String, error: ErrorEnvelope) {}
                override fun onSnapshot(svcId: String, wId: Long, d: ServiceDescriptor, e: IEndpoint) {}
                override fun onServiceUnavailable(svcId: String, wId: Long) {}
                override fun onServiceChanged(svcId: String, wId: Long, d: ServiceDescriptor, e: IEndpoint) {}
                override fun onError(wId: Long, error: ErrorEnvelope) {}
            })
        }

        override fun <T : Any> update(key: PropertyKey<T>, value: T) {
            update(key, value, Quality.VALID)
        }

        @Suppress("UNCHECKED_CAST")
        override fun <T : Any> update(key: PropertyKey<T>, value: T, quality: Quality) {
            val envelope = stateStore.update(key, value, quality)
            if (envelope != null) {
                sessionManager.broadcastPropertyUpdate(envelope)
            }
        }

        override fun <T : Any> emit(eventKey: EventKey<T>, payload: T) {
            val envelope = SubscriptionEnvelope(
                "",
                descriptor.instanceId,
                SubscriptionEnvelope.KIND_EVENT,
                eventKey.id,
                0L,
                0L,
                IpcPayload.ofAny(payload),
                Quality.VALID.value,
                android.os.SystemClock.elapsedRealtime(),
                null,
                null,
                0,
                false,
                null
            )
            sessionManager.broadcastPropertyUpdate(envelope)
        }

        override fun close() {
            regToken?.let { registryConnector.unpublish(it) }
            sessionManager.closeAll()
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
