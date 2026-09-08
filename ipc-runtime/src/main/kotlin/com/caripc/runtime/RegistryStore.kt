package com.caripc.runtime

import android.os.IBinder
import android.os.RemoteException
import android.util.Log
import com.caripc.contract.ErrorCode
import com.caripc.contract.IpcError
import com.caripc.protocol.*
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

class RegistryStore(private val permissionPolicy: PermissionPolicy) {

    private class ServiceRecord(
        val descriptor: ServiceDescriptor,
        val endpoint: IEndpoint,
        val token: RegistrationToken,
        val generation: Long,
        val deathRecipient: IBinder.DeathRecipient
    )

    private class WatcherRecord(
        val watchId: Long,
        val serviceId: String,
        val callerUid: Int,
        val callerPackage: String,
        val callback: IRegistryCallback,
        val deathRecipient: IBinder.DeathRecipient
    )

    private val services = ConcurrentHashMap<String, ServiceRecord>() // key: serviceId
    private val watchers = ConcurrentHashMap<Long, WatcherRecord>() // key: watchId
    private val generationCounter = AtomicLong(100L)
    private val lock = Any()

    fun publish(
        callingUid: Int,
        descriptor: ServiceDescriptor,
        endpoint: IEndpoint,
        callback: IRegistryCallback
    ) {
        val callerPackage = permissionPolicy.getPackageName(callingUid)
        synchronized(lock) {
            try {
                IpcLog.i("RegistryStore", "publish() received: serviceId=${descriptor.serviceId}, package=$callerPackage, UID=$callingUid, instanceId=${descriptor.instanceId}")
                permissionPolicy.authorizePublish(callingUid, descriptor.serviceId)

                val existing = services[descriptor.serviceId]
                if (existing != null) {
                    // 若已被其他 UID 发布，拒绝抢占
                    if (existing.descriptor.ownerUid != callingUid) {
                        IpcLog.w("RegistryStore", "publish rejected: serviceId=${descriptor.serviceId} already owned by UID ${existing.descriptor.ownerUid}")
                        callback.onPublishFailed(
                            descriptor.serviceId,
                            ErrorEnvelope(ErrorCode.SERVICE_ID_CONFLICT.code, "Service already owned by UID ${existing.descriptor.ownerUid}", null, 0, null)
                        )
                        return
                    }
                    // 如果同一实例重复发布，幂等返回
                    if (existing.descriptor.instanceId == descriptor.instanceId) {
                        IpcLog.i("RegistryStore", "publish idempotent return: serviceId=${descriptor.serviceId}, instanceId=${descriptor.instanceId}")
                        callback.onPublished(existing.token)
                        return
                    }
                    // 替换旧发布：解绑旧死亡监听
                    try {
                        existing.endpoint.asBinder().unlinkToDeath(existing.deathRecipient, 0)
                    } catch (_: Exception) {}
                }

                val gen = generationCounter.getAndIncrement()
                val tokenStr = UUID.randomUUID().toString()
                val regToken = RegistrationToken(descriptor.serviceId, descriptor.instanceId, gen, tokenStr)
                val fullDescriptor = descriptor.withGenerationAndOwner(gen, callingUid, callingUid / 100000)

                val deathRecipient = object : IBinder.DeathRecipient {
                    override fun binderDied() {
                        onPublisherDied(descriptor.serviceId, gen, endpoint.asBinder())
                    }
                }

                try {
                    endpoint.asBinder().linkToDeath(deathRecipient, 0)
                } catch (e: RemoteException) {
                    IpcLog.e("RegistryStore", "publish failed: endpoint binder already dead", e)
                    callback.onPublishFailed(descriptor.serviceId, ErrorEnvelope(ErrorCode.CONNECTION_LOST.code, "Publisher binder already dead", null, 0, null))
                    return
                }

                val record = ServiceRecord(fullDescriptor, endpoint, regToken, gen, deathRecipient)
                services[descriptor.serviceId] = record

                IpcLog.i("RegistryStore", "publish succeeded: serviceId=${descriptor.serviceId}, package=$callerPackage, UID=$callingUid, gen=$gen, instanceId=${descriptor.instanceId}")
                callback.onPublished(regToken)

                // 通知当前所有监听该 serviceId 的观察者
                notifyWatchersServiceChanged(record)
            } catch (e: IpcError) {
                IpcLog.w("RegistryStore", "publish failed with IpcError: ${e.message}")
                try {
                    callback.onPublishFailed(descriptor.serviceId, ErrorEnvelope(e))
                } catch (_: Exception) {}
            } catch (e: Exception) {
                IpcLog.e("RegistryStore", "publish unexpected exception", e)
                try {
                    callback.onPublishFailed(descriptor.serviceId, ErrorEnvelope(ErrorCode.INTERNAL_ERROR.code, e.message ?: "Unknown", null, 0, null))
                } catch (_: Exception) {}
            }
        }
    }

    fun unpublish(token: RegistrationToken) {
        synchronized(lock) {
            IpcLog.i("RegistryStore", "unpublish() received: serviceId=${token.serviceId}, gen=${token.generation}")
            val record = services[token.serviceId] ?: return
            // 必须严格比较 generation 和 instanceId，避免旧实例撤销误删新注册实例
            if (record.generation == token.generation && record.token.token == token.token) {
                services.remove(token.serviceId)
                try {
                    record.endpoint.asBinder().unlinkToDeath(record.deathRecipient, 0)
                } catch (_: Exception) {}
                IpcLog.i("RegistryStore", "unpublish completed: serviceId=${token.serviceId}")
                notifyWatchersServiceUnavailable(token.serviceId)
            } else {
                IpcLog.w("RegistryStore", "unpublish ignored: generation mismatch (current=${record.generation}, req=${token.generation})")
            }
        }
    }

    fun resolveAndWatch(
        serviceId: String,
        watchId: Long,
        callback: IRegistryCallback
    ) {
        val callerUid = android.os.Binder.getCallingUid()
        val callerPackage = permissionPolicy.getPackageName(callerUid)
        synchronized(lock) {
            IpcLog.i("RegistryStore", "resolveAndWatch() received: serviceId=$serviceId, watcherPkg=$callerPackage, UID=$callerUid, watchId=$watchId")
            val deathRecipient = object : IBinder.DeathRecipient {
                override fun binderDied() {
                    IpcLog.w("RegistryStore", "Watcher binder died: watcherPkg=$callerPackage, UID=$callerUid, watchId=$watchId")
                    unwatch(watchId, callback)
                }
            }

            try {
                callback.asBinder().linkToDeath(deathRecipient, 0)
            } catch (e: RemoteException) {
                IpcLog.e("RegistryStore", "resolveAndWatch failed: watcher callback already dead", e)
                return
            }

            val watcher = WatcherRecord(watchId, serviceId, callerUid, callerPackage, callback, deathRecipient)
            watchers[watchId] = watcher

            // 原子地返回当前快照
            val current = services[serviceId]
            try {
                if (current != null) {
                    IpcLog.d("RegistryStore", "Delivering snapshot to watcher $watchId ($callerPackage) for $serviceId (instance=${current.descriptor.instanceId})")
                    callback.onSnapshot(serviceId, watchId, current.descriptor, current.endpoint)
                } else {
                    IpcLog.d("RegistryStore", "Delivering onServiceUnavailable to watcher $watchId ($callerPackage) for $serviceId (not yet registered)")
                    callback.onServiceUnavailable(serviceId, watchId)
                }
            } catch (e: RemoteException) {
                IpcLog.w("RegistryStore", "Failed to send initial snapshot to watcher $watchId", e)
            }
        }
    }

    fun unwatch(watchId: Long, callback: IRegistryCallback?) {
        synchronized(lock) {
            IpcLog.i("RegistryStore", "unwatch() received: watchId=$watchId")
            val watcher = watchers.remove(watchId) ?: return
            try {
                watcher.callback.asBinder().unlinkToDeath(watcher.deathRecipient, 0)
            } catch (_: Exception) {}
        }
    }

    private fun onPublisherDied(serviceId: String, generation: Long, deadBinder: IBinder) {
        synchronized(lock) {
            val record = services[serviceId] ?: return
            // 只有 generation 相同且 Binder 一致时才清理，防止新注册被旧死亡清理！
            if (record.generation == generation && record.endpoint.asBinder() == deadBinder) {
                IpcLog.w("RegistryStore", "Publisher Binder DIED! Cleaning up serviceId=$serviceId, gen=$generation")
                services.remove(serviceId)
                notifyWatchersServiceUnavailable(serviceId)
            } else {
                IpcLog.i("RegistryStore", "Old publisher died, ignoring: currentGen=${record.generation}, deadGen=$generation")
            }
        }
    }

    private fun notifyWatchersServiceChanged(record: ServiceRecord) {
        for (watcher in watchers.values) {
            if (watcher.serviceId == record.descriptor.serviceId) {
                try {
                    watcher.callback.onServiceChanged(
                        record.descriptor.serviceId,
                        watcher.watchId,
                        record.descriptor,
                        record.endpoint
                    )
                } catch (e: RemoteException) {
                    Log.w("RegistryStore", "Notify watcher failed: ${watcher.watchId}", e)
                }
            }
        }
    }

    private fun notifyWatchersServiceUnavailable(serviceId: String) {
        for (watcher in watchers.values) {
            if (watcher.serviceId == serviceId) {
                try {
                    watcher.callback.onServiceUnavailable(serviceId, watcher.watchId)
                } catch (e: RemoteException) {
                    Log.w("RegistryStore", "Notify watcher unavailable failed: ${watcher.watchId}", e)
                }
            }
        }
    }

    fun dump(): String {
        synchronized(lock) {
            val sb = StringBuilder()
            sb.appendLine("CarIpc Active Registered Services (${services.size}):")
            if (services.isEmpty()) {
                sb.appendLine("  (No services registered yet)")
            } else {
                services.values.forEachIndexed { index, record ->
                    val desc = record.descriptor
                    val ownerPkg = permissionPolicy.getPackageName(desc.ownerUid)
                    sb.appendLine("  [${index + 1}] ServiceId: ${desc.serviceId}")
                    sb.appendLine("      Owner Package: $ownerPkg (UID: ${desc.ownerUid})")
                    sb.appendLine("      InstanceId   : ${desc.instanceId}")
                    sb.appendLine("      Generation   : ${record.generation}")
                    sb.appendLine("      Version      : v${desc.contractMajor}.${desc.contractMinor}")
                    sb.appendLine("      Capabilities : ${desc.capabilities}")
                }
            }
            sb.appendLine()
            sb.appendLine("CarIpc Active Watchers (${watchers.size}):")
            if (watchers.isEmpty()) {
                sb.appendLine("  (No watchers registered yet)")
            } else {
                watchers.values.forEachIndexed { index, watcher ->
                    sb.appendLine("  [${index + 1}] WatchId: ${watcher.watchId} -> ServiceId: ${watcher.serviceId} [Watcher: ${watcher.callerPackage} (UID: ${watcher.callerUid})]")
                }
            }
            return sb.toString()
        }
    }
}
