package com.caripc.registry

import android.app.Service
import android.content.Intent
import android.os.Binder
import android.os.IBinder
import com.caripc.protocol.*
import com.caripc.runtime.IpcLog
import com.caripc.runtime.PermissionPolicy
import com.caripc.runtime.RegistryStore
import java.io.FileDescriptor
import java.io.PrintWriter

class RegistryService : Service() {

    private lateinit var permissionPolicy: PermissionPolicy
    private lateinit var registryStore: RegistryStore

    private val binder = object : IRegistry.Stub() {
        override fun publish(descriptor: ServiceDescriptor, endpoint: IEndpoint, callback: IRegistryCallback) {
            val callingUid = Binder.getCallingUid()
            IpcLog.i("RegistryService", "publish: serviceId=${descriptor.serviceId} from UID $callingUid")
            registryStore.publish(callingUid, descriptor, endpoint, callback)
        }

        override fun unpublish(token: RegistrationToken) {
            val callingUid = Binder.getCallingUid()
            IpcLog.i("RegistryService", "unpublish: serviceId=${token.serviceId}, gen=${token.generation} from UID $callingUid")
            registryStore.unpublish(token)
        }

        override fun resolveAndWatch(serviceId: String, watchId: Long, callback: IRegistryCallback) {
            val callingUid = Binder.getCallingUid()
            IpcLog.i("RegistryService", "resolveAndWatch: serviceId=$serviceId, watchId=$watchId from UID $callingUid")
            registryStore.resolveAndWatch(serviceId, watchId, callback)
        }

        override fun unwatch(watchId: Long, callback: IRegistryCallback?) {
            val callingUid = Binder.getCallingUid()
            IpcLog.i("RegistryService", "unwatch: watchId=$watchId from UID $callingUid")
            registryStore.unwatch(watchId, callback)
        }
    }

    override fun onCreate() {
        super.onCreate()
        IpcLog.i("RegistryService", "RegistryService onCreate() initialized.")
        permissionPolicy = PermissionPolicy(applicationContext)
        registryStore = RegistryStore(permissionPolicy)
        startInForeground()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        IpcLog.i("RegistryService", "RegistryService onStartCommand() started and set to START_STICKY")
        startInForeground()
        return START_STICKY
    }

    private fun startInForeground() {
        try {
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                val channelId = "caripc_registry_channel"
                val notificationManager = getSystemService(android.app.NotificationManager::class.java)
                if (notificationManager != null && notificationManager.getNotificationChannel(channelId) == null) {
                    val channel = android.app.NotificationChannel(
                        channelId,
                        "CarIpc Registry Service",
                        android.app.NotificationManager.IMPORTANCE_LOW
                    ).apply {
                        description = "CarIpc Central Registry Daemon"
                    }
                    notificationManager.createNotificationChannel(channel)
                }
                val notification = android.app.Notification.Builder(this, channelId)
                    .setContentTitle("CarIpc Registry")
                    .setContentText("IPC Central Registry is active")
                    .setSmallIcon(android.R.drawable.stat_notify_sync)
                    .build()
                startForeground(1001, notification)
            }
        } catch (e: Exception) {
            IpcLog.w("RegistryService", "startForeground failed: ${e.message}")
        }
    }

    override fun onBind(intent: Intent?): IBinder {
        IpcLog.i("RegistryService", "RegistryService onBind() by action=${intent?.action}")
        return binder
    }

    override fun dump(fd: FileDescriptor?, writer: PrintWriter?, args: Array<out String>?) {
        writer?.println("=== CarIpc Registry Diagnostics ===")
        writer?.println(registryStore.dump())
    }
}
