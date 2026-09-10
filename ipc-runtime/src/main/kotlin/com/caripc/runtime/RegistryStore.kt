package com.caripc.runtime

import android.os.IBinder
import android.os.RemoteException
import com.caripc.contract.ErrorCode
import com.caripc.contract.IpcError
import com.caripc.protocol.*
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * 注册中心存储。
 *
 * 修复要点（工单 P1-6 / P1-7 / P2-7 / P2-8）：
 * - 监听（watch）记录绑定调用者 UID：注销与重复注册都必须校验归属，杜绝跨应用注销/顶替；
 * - 不再持内部锁调用远端 callback（锁内只做状态变更，回调在锁外投递）；
 * - 替换旧发布记录时先成功 link 新记录再 unlink 旧记录，避免留下失去死亡清理的僵尸注册；
 * - services / watchers / 单 UID 配额，超限返回 RESOURCE_EXHAUSTED。
 */
class RegistryStore(private val permissionPolicy: PermissionPolicy) {

    companion object {
        const val MAX_SERVICES = 256
        const val MAX_SERVICES_PER_UID = 32
        const val MAX_WATCHERS = 512
        const val MAX_WATCHERS_PER_UID = 64
    }

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
        // 鉴权放在锁外：其中包含 PackageManager 跨进程查询
        try {
            IpcLog.i(
                "RegistryStore",
                "publish() received: serviceId=${descriptor.serviceId}, package=$callerPackage, UID=$callingUid, instanceId=${descriptor.instanceId}"
            )
            permissionPolicy.authorizePublish(callingUid, descriptor.serviceId)
        } catch (e: IpcError) {
            IpcLog.w("RegistryStore", "publish rejected by policy: ${e.message}")
            safeCallback { callback.onPublishFailed(descriptor.serviceId, ErrorEnvelope(e)) }
            return
        }

        val deferred = mutableListOf<() -> Unit>()
        synchronized(lock) {
            try {
                val existing = services[descriptor.serviceId]
                if (existing != null) {
                    // 已被其他 UID 发布：拒绝抢占
                    if (existing.descriptor.ownerUid != callingUid) {
                        IpcLog.w(
                            "RegistryStore",
                            "publish rejected: serviceId=${descriptor.serviceId} already owned by UID ${existing.descriptor.ownerUid}"
                        )
                        deferred.add {
                            safeCallback {
                                callback.onPublishFailed(
                                    descriptor.serviceId,
                                    ErrorEnvelope(
                                        ErrorCode.SERVICE_ID_CONFLICT.code,
                                        "Service already owned by UID ${existing.descriptor.ownerUid}",
                                        null,
                                        0,
                                        null
                                    )
                                )
                            }
                        }
                        return@synchronized
                    }
                    // 同实例重复发布：幂等返回
                    if (existing.descriptor.instanceId == descriptor.instanceId) {
                        IpcLog.i(
                            "RegistryStore",
                            "publish idempotent return: serviceId=${descriptor.serviceId}, instanceId=${descriptor.instanceId}"
                        )
                        deferred.add { safeCallback { callback.onPublished(existing.token) } }
                        return@synchronized
                    }
                } else {
                    if (services.size >= MAX_SERVICES) {
                        deferred.add {
                            safeCallback {
                                callback.onPublishFailed(
                                    descriptor.serviceId,
                                    ErrorEnvelope(
                                        ErrorCode.RESOURCE_EXHAUSTED.code,
                                        "Registry holds too many services (max $MAX_SERVICES)",
                                        null,
                                        0,
                                        null
                                    )
                                )
                            }
                        }
                        return@synchronized
                    }
                    val owned = services.values.count { it.descriptor.ownerUid == callingUid }
                    if (owned >= MAX_SERVICES_PER_UID) {
                        deferred.add {
                            safeCallback {
                                callback.onPublishFailed(
                                    descriptor.serviceId,
                                    ErrorEnvelope(
                                        ErrorCode.RESOURCE_EXHAUSTED.code,
                                        "UID $callingUid already published $owned services (max $MAX_SERVICES_PER_UID)",
                                        null,
                                        0,
                                        null
                                    )
                                )
                            }
                        }
                        return@synchronized
                    }
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

                // 先成功挂死亡监听，再替换旧记录；失败则保持旧记录仍然受保护（工单 P2-8）
                try {
                    endpoint.asBinder().linkToDeath(deathRecipient, 0)
                } catch (e: RemoteException) {
                    IpcLog.e("RegistryStore", "publish failed: endpoint binder already dead", e)
                    deferred.add {
                        safeCallback {
                            callback.onPublishFailed(
                                descriptor.serviceId,
                                ErrorEnvelope(ErrorCode.CONNECTION_LOST.code, "Publisher binder already dead", null, 0, null)
                            )
                        }
                    }
                    return@synchronized
                }

                val oldRecord = services.put(
                    descriptor.serviceId,
                    ServiceRecord(fullDescriptor, endpoint, regToken, gen, deathRecipient)
                )
                oldRecord?.let {
                    try {
                        it.endpoint.asBinder().unlinkToDeath(it.deathRecipient, 0)
                    } catch (_: Exception) {
                    }
                }

                IpcLog.i(
                    "RegistryStore",
                    "publish succeeded: serviceId=${descriptor.serviceId}, package=$callerPackage, UID=$callingUid, gen=$gen, instanceId=${descriptor.instanceId}"
                )
                val record = services[descriptor.serviceId]!!
                deferred.add { safeCallback { callback.onPublished(regToken) } }
                deferred.addAll(watcherNotificationsLocked(record.descriptor.serviceId) { w ->
                    { safeCallback { w.callback.onServiceChanged(record.descriptor.serviceId, w.watchId, record.descriptor, record.endpoint) } }
                })
            } catch (e: IpcError) {
                IpcLog.w("RegistryStore", "publish failed with IpcError: ${e.message}")
                deferred.add { safeCallback { callback.onPublishFailed(descriptor.serviceId, ErrorEnvelope(e)) } }
            } catch (e: Exception) {
                IpcLog.e("RegistryStore", "publish unexpected exception", e)
                deferred.add {
                    safeCallback {
                        callback.onPublishFailed(
                            descriptor.serviceId,
                            ErrorEnvelope(ErrorCode.INTERNAL_ERROR.code, e.message ?: "Unknown", null, 0, null)
                        )
                    }
                }
            }
        }
        deferred.forEach { it() }
    }

    fun unpublish(callingUid: Int, token: RegistrationToken) {
        val deferred = mutableListOf<() -> Unit>()
        synchronized(lock) {
            IpcLog.i("RegistryStore", "unpublish() received: serviceId=${token.serviceId}, gen=${token.generation}, uid=$callingUid")
            val record = services[token.serviceId] ?: return@synchronized
            // 只有持有者本人可以注销
            if (record.descriptor.ownerUid != callingUid) {
                IpcLog.w(
                    "RegistryStore",
                    "unpublish rejected: serviceId=${token.serviceId} owned by UID ${record.descriptor.ownerUid}, caller=$callingUid"
                )
                return@synchronized
            }
            // 严格比较 generation 与 token，避免旧实例撤销误删新注册实例
            if (record.generation == token.generation && record.token.token == token.token) {
                services.remove(token.serviceId)
                try {
                    record.endpoint.asBinder().unlinkToDeath(record.deathRecipient, 0)
                } catch (_: Exception) {
                }
                IpcLog.i("RegistryStore", "unpublish completed: serviceId=${token.serviceId}")
                deferred.addAll(watcherNotificationsLocked(token.serviceId) { w ->
                    { safeCallback { w.callback.onServiceUnavailable(token.serviceId, w.watchId) } }
                })
            } else {
                IpcLog.w(
                    "RegistryStore",
                    "unpublish ignored: generation mismatch (current=${record.generation}, req=${token.generation})"
                )
            }
        }
        deferred.forEach { it() }
    }

    fun resolveAndWatch(
        callingUid: Int,
        serviceId: String,
        watchId: Long,
        callback: IRegistryCallback
    ) {
        val callerPackage = permissionPolicy.getPackageName(callingUid)
        val deferred = mutableListOf<() -> Unit>()
        synchronized(lock) {
            IpcLog.i(
                "RegistryStore",
                "resolveAndWatch() received: serviceId=$serviceId, watcherPkg=$callerPackage, UID=$callingUid, watchId=$watchId"
            )

            val existingWatcher = watchers[watchId]
            if (existingWatcher != null) {
                // watchId 由客户端提供：跨 UID 复用必须拒绝（工单 P1-6）
                if (existingWatcher.callerUid != callingUid) {
                    IpcLog.w(
                        "RegistryStore",
                        "resolveAndWatch rejected: watchId=$watchId owned by UID ${existingWatcher.callerUid}, caller=$callingUid"
                    )
                    deferred.add {
                        safeCallback {
                            callback.onError(
                                watchId,
                                ErrorEnvelope(ErrorCode.PERMISSION_DENIED.code, "watchId owned by another UID", null, 0, null)
                            )
                        }
                    }
                    return@synchronized
                }
                // 同 UID 幂等重注册：清理旧记录（含死亡监听）后重建
                try {
                    existingWatcher.callback.asBinder().unlinkToDeath(existingWatcher.deathRecipient, 0)
                } catch (_: Exception) {
                }
                watchers.remove(watchId)
            } else {
                if (watchers.size >= MAX_WATCHERS) {
                    deferred.add {
                        safeCallback {
                            callback.onError(
                                watchId,
                                ErrorEnvelope(ErrorCode.RESOURCE_EXHAUSTED.code, "too many watchers (max $MAX_WATCHERS)", null, 0, null)
                            )
                        }
                    }
                    return@synchronized
                }
                val owned = watchers.values.count { it.callerUid == callingUid }
                if (owned >= MAX_WATCHERS_PER_UID) {
                    deferred.add {
                        safeCallback {
                            callback.onError(
                                watchId,
                                ErrorEnvelope(
                                    ErrorCode.RESOURCE_EXHAUSTED.code,
                                    "UID $callingUid already holds $owned watchers (max $MAX_WATCHERS_PER_UID)",
                                    null,
                                    0,
                                    null
                                )
                            )
                        }
                    }
                    return@synchronized
                }
            }

            val deathRecipient = object : IBinder.DeathRecipient {
                override fun binderDied() {
                    IpcLog.w("RegistryStore", "Watcher binder died: watcherPkg=$callerPackage, UID=$callingUid, watchId=$watchId")
                    unwatch(callingUid, watchId)
                }
            }
            try {
                callback.asBinder().linkToDeath(deathRecipient, 0)
            } catch (e: RemoteException) {
                IpcLog.e("RegistryStore", "resolveAndWatch failed: watcher callback already dead", e)
                return@synchronized
            }

            val watcher = WatcherRecord(watchId, serviceId, callingUid, callerPackage, callback, deathRecipient)
            watchers[watchId] = watcher

            val current = services[serviceId]
            if (current != null) {
                IpcLog.d(
                    "RegistryStore",
                    "Delivering snapshot to watcher $watchId ($callerPackage) for $serviceId (instance=${current.descriptor.instanceId})"
                )
                deferred.add {
                    safeCallback {
                        callback.onSnapshot(serviceId, watchId, current.descriptor, current.endpoint)
                    }
                }
            } else {
                IpcLog.d(
                    "RegistryStore",
                    "Delivering onServiceUnavailable to watcher $watchId ($callerPackage) for $serviceId (not yet registered)"
                )
                deferred.add { safeCallback { callback.onServiceUnavailable(serviceId, watchId) } }
            }
        }
        deferred.forEach { it() }
    }

    /** 注销监听：必须由注册者本人发起（工单 P1-6）。 */
    fun unwatch(callingUid: Int, watchId: Long) {
        synchronized(lock) {
            val watcher = watchers[watchId] ?: return
            if (watcher.callerUid != callingUid) {
                IpcLog.w(
                    "RegistryStore",
                    "unwatch rejected: watchId=$watchId owned by UID ${watcher.callerUid}, caller=$callingUid"
                )
                return
            }
            watchers.remove(watchId)
            try {
                watcher.callback.asBinder().unlinkToDeath(watcher.deathRecipient, 0)
            } catch (_: Exception) {
            }
            IpcLog.i("RegistryStore", "unwatch completed: watchId=$watchId (UID $callingUid)")
        }
    }

    private fun onPublisherDied(serviceId: String, generation: Long, deadBinder: IBinder) {
        val deferred = mutableListOf<() -> Unit>()
        synchronized(lock) {
            val record = services[serviceId] ?: return
            // 只有 generation 相同且 Binder 一致时才清理，防止新注册被旧死亡清理
            if (record.generation == generation && record.endpoint.asBinder() == deadBinder) {
                IpcLog.w("RegistryStore", "Publisher Binder DIED! Cleaning up serviceId=$serviceId, gen=$generation")
                services.remove(serviceId)
                deferred.addAll(watcherNotificationsLocked(serviceId) { w ->
                    { safeCallback { w.callback.onServiceUnavailable(serviceId, w.watchId) } }
                })
            } else {
                IpcLog.i(
                    "RegistryStore",
                    "Old publisher died, ignoring: currentGen=${record.generation}, deadGen=$generation"
                )
            }
        }
        deferred.forEach { it() }
    }

    private inline fun watcherNotificationsLocked(
        serviceId: String,
        build: (WatcherRecord) -> () -> Unit
    ): List<() -> Unit> {
        return watchers.values.filter { it.serviceId == serviceId }.map(build)
    }

    private inline fun safeCallback(block: () -> Unit) {
        try {
            block()
        } catch (e: RemoteException) {
            IpcLog.w("RegistryStore", "Remote callback failed: ${e.message}")
        } catch (t: Throwable) {
            IpcLog.e("RegistryStore", "Callback threw", t)
        }
    }

    fun dump(): String {
        // 锁内只做快照；包名解析与格式化在锁外完成，避免 dumpsys 被慢事务阻塞
        val serviceSnapshot: List<ServiceRecord>
        val watcherSnapshot: List<WatcherRecord>
        synchronized(lock) {
            serviceSnapshot = services.values.toList()
            watcherSnapshot = watchers.values.toList()
        }
        val sb = StringBuilder()
        sb.appendLine("CarIpc Active Registered Services (${serviceSnapshot.size}):")
        if (serviceSnapshot.isEmpty()) {
            sb.appendLine("  (No services registered yet)")
        } else {
            serviceSnapshot.forEachIndexed { index, record ->
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
        sb.appendLine("CarIpc Active Watchers (${watcherSnapshot.size}):")
        if (watcherSnapshot.isEmpty()) {
            sb.appendLine("  (No watchers registered yet)")
        } else {
            watcherSnapshot.forEachIndexed { index, watcher ->
                sb.appendLine(
                    "  [${index + 1}] WatchId: ${watcher.watchId} -> ServiceId: ${watcher.serviceId} [Watcher: ${watcher.callerPackage} (UID: ${watcher.callerUid})]"
                )
            }
        }
        return sb.toString()
    }
}
