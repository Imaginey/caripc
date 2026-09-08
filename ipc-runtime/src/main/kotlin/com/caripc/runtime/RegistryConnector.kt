package com.caripc.runtime

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.RemoteException
import android.util.Log
import com.caripc.protocol.*
import java.util.Random
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

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
    private var registryProxy: IRegistry? = null
    private var deathRecipient: IBinder.DeathRecipient? = null

    // 重连参数
    private var retryAttempt = 0
    private val baseDelayMs = 200L
    private val maxDelayMs = 10000L
    private val random = Random()

    // 意图表
    private val activePublishIntents = ConcurrentHashMap<String, PublishIntent>()
    private val activeWatchIntents = ConcurrentHashMap<Long, WatchIntent>()
    private val lock = Any()

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            synchronized(lock) {
                isConnecting.set(false)
                isBound = true
                retryAttempt = 0
                val proxy = IRegistry.Stub.asInterface(service)
                registryProxy = proxy

                val death = object : IBinder.DeathRecipient {
                    override fun binderDied() {
                        onRegistryDied()
                    }
                }
                deathRecipient = death
                try {
                    service?.linkToDeath(death, 0)
                } catch (_: RemoteException) {}

                Log.i("RegistryConnector", "Connected to Registry. Restoring intents...")
                IpcLog.i("RegistryConnector", "Connected to Registry. Restoring intents: ${activePublishIntents.size} publishes, ${activeWatchIntents.size} watches")
                restoreIntents(proxy)
            }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            synchronized(lock) {
                IpcLog.w("RegistryConnector", "onServiceDisconnected from Registry. Scheduling reconnect...")
                isBound = false
                registryProxy = null
                scheduleReconnect()
            }
        }

        override fun onBindingDied(name: ComponentName?) {
            synchronized(lock) {
                IpcLog.w("RegistryConnector", "onBindingDied from Registry. Scheduling reconnect...")
                try {
                    context.unbindService(this)
                } catch (_: Exception) {}
                isBound = false
                registryProxy = null
                scheduleReconnect()
            }
        }
    }

    fun ensureBound() {
        synchronized(lock) {
            if (isBound || isConnecting.get()) return
            isConnecting.set(true)
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
                isConnecting.set(false)
                scheduleReconnect()
            }
        }
    }

    private fun scheduleReconnect() {
        val delay = calculateBackoff(retryAttempt++)
        IpcLog.d("RegistryConnector", "Scheduling registry reconnect in ${delay}ms (attempt=$retryAttempt)")
        mainHandler.postDelayed({
            ensureBound()
        }, delay)
    }

    private fun calculateBackoff(attempt: Int): Long {
        val factor = 1L shl minOf(attempt, 6)
        val expDelay = minOf(baseDelayMs * factor, maxDelayMs)
        val jitter = random.nextInt(100)
        return expDelay + jitter
    }

    private fun onRegistryDied() {
        synchronized(lock) {
            IpcLog.w("RegistryConnector", "Registry Binder DIED! Existing P2P direct sessions remain ACTIVE. Scheduling reconnect...")
            registryProxy = null
            isBound = false
            try {
                context.unbindService(serviceConnection)
            } catch (_: Exception) {}
            scheduleReconnect()
        }
    }

    fun publish(descriptor: ServiceDescriptor, endpoint: IEndpoint, callback: IRegistryCallback) {
        val intent = PublishIntent(descriptor, endpoint, callback)
        activePublishIntents[descriptor.serviceId] = intent
        ensureBound()
        synchronized(lock) {
            registryProxy?.let { proxy ->
                try {
                    proxy.publish(descriptor, endpoint, callback)
                } catch (e: RemoteException) {
                    scheduleReconnect()
                }
            }
        }
    }

    fun unpublish(token: RegistrationToken) {
        activePublishIntents.remove(token.serviceId)
        synchronized(lock) {
            registryProxy?.let { proxy ->
                try {
                    proxy.unpublish(token)
                } catch (_: RemoteException) {}
            }
        }
    }

    fun resolveAndWatch(serviceId: String, watchId: Long, callback: IRegistryCallback) {
        val intent = WatchIntent(serviceId, watchId, callback)
        activeWatchIntents[watchId] = intent
        ensureBound()
        synchronized(lock) {
            registryProxy?.let { proxy ->
                try {
                    proxy.resolveAndWatch(serviceId, watchId, callback)
                } catch (e: RemoteException) {
                    scheduleReconnect()
                }
            }
        }
    }

    fun unwatch(watchId: Long, callback: IRegistryCallback?) {
        activeWatchIntents.remove(watchId)
        synchronized(lock) {
            registryProxy?.let { proxy ->
                try {
                    proxy.unwatch(watchId, callback)
                } catch (_: RemoteException) {}
            }
        }
    }

    private fun restoreIntents(proxy: IRegistry) {
        // 分批恢复发布意图
        val pubList = activePublishIntents.values.toList()
        for (pub in pubList) {
            try {
                proxy.publish(pub.descriptor, pub.endpoint, pub.callback)
            } catch (e: RemoteException) {
                Log.w("RegistryConnector", "Restore publish failed for ${pub.descriptor.serviceId}", e)
            }
        }
        // 分批恢复监听意图
        val watchList = activeWatchIntents.values.toList()
        for (watch in watchList) {
            try {
                proxy.resolveAndWatch(watch.serviceId, watch.watchId, watch.callback)
            } catch (e: RemoteException) {
                Log.w("RegistryConnector", "Restore watch failed for ${watch.serviceId}", e)
            }
        }
    }

    fun close() {
        synchronized(lock) {
            activePublishIntents.clear()
            activeWatchIntents.clear()
            if (isBound) {
                try {
                    context.unbindService(serviceConnection)
                } catch (_: Exception) {}
                isBound = false
                registryProxy = null
            }
        }
    }
}
