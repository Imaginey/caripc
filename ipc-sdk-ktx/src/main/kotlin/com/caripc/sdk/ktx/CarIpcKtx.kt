package com.caripc.sdk.ktx

import com.caripc.contract.*
import com.caripc.sdk.CarIpc
import com.caripc.sdk.RemoteService
import com.caripc.sdk.ServicePublisher
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

suspend fun RemoteService.awaitReady(timeoutMillis: Long = 5000L) {
    suspendCancellableCoroutine<Unit> { cont ->
        awaitReady(timeoutMillis) { res ->
            res.onSuccess { cont.resume(Unit) }
            res.onFailure { cont.resumeWithException(it) }
        }
    }
}

suspend fun <T : Any> RemoteService.get(key: PropertyKey<T>): PropertySnapshot<T> {
    return suspendCancellableCoroutine { cont ->
        get(key) { res ->
            res.onSuccess { cont.resume(it) }
            res.onFailure { cont.resumeWithException(it) }
        }
    }
}

suspend fun <T : Any> RemoteService.set(key: PropertyKey<T>, value: T): SetReceipt {
    return suspendCancellableCoroutine { cont ->
        set(key, value) { res ->
            res.onSuccess { cont.resume(it) }
            res.onFailure { cont.resumeWithException(it) }
        }
    }
}

suspend fun <T : Any> RemoteService.setIfVersion(key: PropertyKey<T>, value: T, writeToken: String?): SetReceipt {
    return suspendCancellableCoroutine { cont ->
        setIfVersion(key, value, writeToken) { res ->
            res.onSuccess { cont.resume(it) }
            res.onFailure { cont.resumeWithException(it) }
        }
    }
}

suspend fun <Req : Any, Resp : Any> RemoteService.call(command: CommandKey<Req, Resp>, param: Req): Resp {
    return suspendCancellableCoroutine { cont ->
        call(command, param) { res ->
            res.onSuccess { cont.resume(it) }
            res.onFailure { cont.resumeWithException(it) }
        }
    }
}

fun RemoteService.observe(
    keys: List<CapabilityKey>,
    options: SubscribeOptions = SubscribeOptions()
): Flow<SubscriptionMessage> = callbackFlow {
    val handle = subscribe(keys, options) { msg ->
        trySend(msg)
    }
    awaitClose {
        handle.cancel()
    }
}

class ServiceBuilder(val schema: ServiceSchema) {
    private val setHandlers = mutableMapOf<String, (value: Any?, callerUid: Int) -> SetReceipt>()
    private val callHandlers = mutableMapOf<String, (param: Any?, callerUid: Int) -> Any?>()

    @Suppress("UNCHECKED_CAST")
    fun <T : Any> onSet(key: PropertyKey<T>, handler: (value: T, callerUid: Int) -> SetReceipt) {
        setHandlers[key.id] = { rawVal, callerUid ->
            handler(rawVal as T, callerUid)
        }
    }

    @Suppress("UNCHECKED_CAST")
    fun <Req : Any, Resp : Any> onCall(command: CommandKey<Req, Resp>, handler: (param: Req, callerUid: Int) -> Resp) {
        callHandlers[command.id] = { rawVal, callerUid ->
            handler(rawVal as Req, callerUid)
        }
    }

    fun buildSetHandler(): (keyId: String, value: Any?, callerUid: Int) -> SetReceipt = { keyId, value, callerUid ->
        val h = setHandlers[keyId]
        if (h != null) {
            h(value, callerUid)
        } else {
            SetReceipt.rejected(IpcError(ErrorCode.READ_ONLY, "No set handler for $keyId"))
        }
    }

    fun buildCallHandler(): (commandId: String, param: Any?, callerUid: Int) -> Any? = { commandId, param, callerUid ->
        val h = callHandlers[commandId]
        if (h != null) {
            h(param, callerUid)
        } else {
            throw IpcError(ErrorCode.UNKNOWN_CAPABILITY, "No command handler for $commandId")
        }
    }
}

fun CarIpc.publishService(
    serviceId: String,
    schema: ServiceSchema,
    block: ServiceBuilder.() -> Unit
): ServicePublisher {
    val builder = ServiceBuilder(schema)
    builder.block()
    return publishService(
        serviceId,
        schema,
        builder.buildSetHandler(),
        builder.buildCallHandler()
    )
}
