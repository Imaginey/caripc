package com.caripc.runtime

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.RemoteException
import com.caripc.contract.ErrorCode
import com.caripc.contract.IpcError
import com.caripc.protocol.*
import java.util.Random
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 注册中心连接器：绑定、重连、发布/监听的意图恢复。
 *
 * 修复要点（工单 P2-9 / P1-8）：
 * - 三条断开路径（onServiceDisconnected / onBindingDied / binderDied）统一处理，避免重复绑定；
 * - 支持撤销发布意图，防止已关闭的服务在重连后被重新发布。
 */
class RegistryConnector(
    private val context: Context,
    private val registryComponent: ComponentName = ComponentName("com.caripc.registry", "com.caripc.registry.RegistryService")
) {
    data class PublishIntent(
        val descriptor: ServiceDescriptor,
        val endpoint: IEndpoint,
        val callback: IRegistryCallback
    )

    data class WatchIntent(
        val serviceId: String,
        val watchId: Long,
        val callback: IRegistryCallback
    )

    private val mainHandler = Handler(Looper.getMainLooper())
    private val isConnecting = AtomicBoolean(false)
    private var isBound = false
    private var bindingRequested = false
    private var registryProxy: IRegistry? = null
    private var deathRecipient: IBinder.DeathRecipient? = null
    private val lock = Any()

    // 重连参数
    private var retryAttempt = 0
    private val baseDelayMs = 200L
    private val maxDelayMs = 10000L
    private val random = Random()

    // 意图表
    private val activePublishIntents = ConcurrentHashMap<String, PublishIntent>()
    private val activeWatchIntents = ConcurrentHashMap<Long, WatchIntent>()

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            var toRestore: IRegistry? = null
            synchronized(lock) {
                isConnecting.set(false)
                isBound = true
                bindingRequested = true
                retryAttempt = 0
                val proxy = IRegistry.Stub.asInterface(service)
                registryProxy = proxy
                if (service != null) {
                    val death = object : IBinder.DeathRecipient {
                        override fun binderDied() {
                            onRegistryDied()
                        }
                    }
                    deathRecipient = death
                    try {
                        service.linkToDeath(death, 0)
                    } catch (_: RemoteException) {
                    }
                }
                toRestore = proxy
            }
            IpcLog.i(
                "RegistryConnector",
                "Connected to Registry. Restoring intents: ${activePublishIntents.size} publishes, ${activeWatchIntents.size} watches"
            )
            val proxyForRestore: IRegistry? = toRestore
            if (proxyForRestore != null) restoreIntents(proxyForRestore)
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            IpcLog.w("RegistryConnector", "onServiceDisconnected from Registry. Scheduling reconnect...")
            handleDisconnect("onServiceDisconnected")
        }

        override fun onBindingDied(name: ComponentName?) {
            IpcLog.w("RegistryConnector", "onBindingDied from Registry. Scheduling reconnect...")
            handleDisconnect("onBindingDied")
        }
    }

    private fun handleDisconnect(reason: String) {
        synchronized(lock) {
            IpcLog.d("RegistryConnector", "handleDisconnect($reason): unbinding before reconnect")
            unbindLocked()
            scheduleReconnectLocked()
        }
    }

    private fun unbindLocked() {
        isBound = false
        registryProxy = null
        if (bindingRequested) {
            try {
                context.unbindService(serviceConnection)
            } catch (_: Exception) {
            }
            bindingRequested = false
        }
    }

    fun ensureBound() {
        val shouldBind: Boolean
        synchronized(lock) {
            if (isBound || isConnecting.get() || bindingRequested) return
            isConnecting.set(true)
            shouldBind = true
        }
        if (!shouldBind) return
        IpcLog.i("RegistryConnector", "Binding to registry component: $registryComponent")
        val intent = Intent().setComponent(registryComponent)
        val bindSuccess = try {
            context.bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
        } catch (e: Exception) {
            IpcLog.e("RegistryConnector", "Failed to bind to registry", e)
            false
        }
        if (!bindSuccess) {
            IpcLog.w("RegistryConnector", "bindService returned false. Will retry...")
            synchronized(lock) {
                isConnecting.set(false)
                bindingRequested = false
                scheduleReconnectLocked()
            }
        } else {
            synchronized(lock) { bindingRequested = true }
        }
    }

    private fun scheduleReconnectLocked() {
        val delay = calculateBackoff(retryAttempt++)
        IpcLog.d("RegistryConnector", "Scheduling registry reconnect in ${delay}ms (attempt=$retryAttempt)")
        mainHandler.postDelayed({ ensureBound() }, delay)
    }

    private fun calculateBackoff(attempt: Int): Long {
        val factor = 1L shl minOf(attempt, 6)
        val expDelay = minOf(baseDelayMs * factor, maxDelayMs)
        val jitter = random.nextInt(100)
        return expDelay + jitter
    }

    private fun onRegistryDied() {
        IpcLog.w(
            "RegistryConnector",
            "Registry Binder DIED! Existing P2P direct sessions remain ACTIVE. Scheduling reconnect..."
        )
        handleDisconnect("binderDied")
    }

    fun publish(descriptor: ServiceDescriptor, endpoint: IEndpoint, callback: IRegistryCallback) {
        val intent = PublishIntent(descriptor, endpoint, callback)
        activePublishIntents[descriptor.serviceId] = intent
        ensureBound()
        val proxy = synchronized(lock) { registryProxy } ?: return
        try {
            proxy.publish(descriptor, endpoint, callback)
        } catch (e: RemoteException) {
            synchronized(lock) { scheduleReconnectLocked() }
        }
    }

    /** 撤销发布意图（发布者关闭时调用），防止重连后复活。 */
    fun cancelPublishIntent(serviceId: String) {
        activePublishIntents.remove(serviceId)
    }

    fun hasPublishIntent(serviceId: String): Boolean = activePublishIntents.containsKey(serviceId)

    fun unpublish(token: RegistrationToken) {
        activePublishIntents.remove(token.serviceId)
        val proxy = synchronized(lock) { registryProxy } ?: return
        try {
            proxy.unpublish(token)
        } catch (_: RemoteException) {
        }
    }

    fun resolveAndWatch(serviceId: String, watchId: Long, callback: IRegistryCallback) {
        val intent = WatchIntent(serviceId, watchId, callback)
        activeWatchIntents[watchId] = intent
        ensureBound()
        val proxy = synchronized(lock) { registryProxy } ?: return
        try {
            proxy.resolveAndWatch(serviceId, watchId, callback)
        } catch (e: RemoteException) {
            synchronized(lock) { scheduleReconnectLocked() }
        }
    }

    fun unwatch(watchId: Long, callback: IRegistryCallback?) {
        activeWatchIntents.remove(watchId)
        val proxy = synchronized(lock) { registryProxy } ?: return
        try {
            proxy.unwatch(watchId, callback)
        } catch (_: RemoteException) {
        }
    }

    private fun restoreIntents(proxy: IRegistry) {
        // 快照式恢复；恢复前再次确认意图仍然有效，避免把已撤销的发布/监听恢复出来
        val pubList = activePublishIntents.values.toList()
        for (pub in pubList) {
            if (activePublishIntents[pub.descriptor.serviceId] !== pub) continue
            try {
                proxy.publish(pub.descriptor, pub.endpoint, pub.callback)
            } catch (e: RemoteException) {
                IpcLog.w("RegistryConnector", "Restore publish failed for ${pub.descriptor.serviceId}", e)
            }
        }
        val watchList = activeWatchIntents.values.toList()
        for (watch in watchList) {
            if (activeWatchIntents[watch.watchId] !== watch) continue
            try {
                proxy.resolveAndWatch(watch.serviceId, watch.watchId, watch.callback)
            } catch (e: RemoteException) {
                IpcLog.w("RegistryConnector", "Restore watch failed for ${watch.serviceId}", e)
            }
        }
    }

    fun close() {
        synchronized(lock) {
            activePublishIntents.clear()
            activeWatchIntents.clear()
            unbindLocked()
        }
    }

    /** 诊断用：当前是否已绑定注册中心。 */
    fun isRegistryBound(): Boolean = synchronized(lock) { isBound }

    /** 诊断用：未送达的意图数量。 */
    fun pendingIntentCount(): Int = activePublishIntents.size + activeWatchIntents.size

    fun requireProxy(): IRegistry {
        return synchronized(lock) { registryProxy } ?: throw IpcError(
            ErrorCode.SERVICE_UNAVAILABLE,
            "Registry is not connected"
        )
    }
}
