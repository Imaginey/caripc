package com.caripc.registry

import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Binder
import android.os.IBinder
import com.caripc.protocol.*
import com.caripc.runtime.IpcLog
import com.caripc.runtime.PermissionPolicy
import com.caripc.runtime.RegistryStore
import java.io.FileDescriptor
import java.io.PrintWriter

class RegistryService : Service() {

    companion object {
        /** 集成层可通过 manifest meta-data 打开严格鉴权（默认关闭并打 WARN）。 */
        const val META_STRICT_PERMISSION_MODE = "com.caripc.registry.STRICT_PERMISSION_MODE"
    }

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
            IpcLog.i(
                "RegistryService",
                "unpublish: serviceId=${token.serviceId}, gen=${token.generation} from UID $callingUid"
            )
            registryStore.unpublish(callingUid, token)
        }

        override fun resolveAndWatch(serviceId: String, watchId: Long, callback: IRegistryCallback) {
            val callingUid = Binder.getCallingUid()
            IpcLog.i("RegistryService", "resolveAndWatch: serviceId=$serviceId, watchId=$watchId from UID $callingUid")
            registryStore.resolveAndWatch(callingUid, serviceId, watchId, callback)
        }

        override fun unwatch(watchId: Long, callback: IRegistryCallback?) {
            val callingUid = Binder.getCallingUid()
            IpcLog.i("RegistryService", "unwatch: watchId=$watchId from UID $callingUid")
            registryStore.unwatch(callingUid, watchId)
        }
    }

    override fun onCreate() {
        super.onCreate()
        IpcLog.i("RegistryService", "RegistryService onCreate() initialized.")
        val strict = readStrictPermissionMode()
        IpcLog.i("RegistryService", "Permission policy strictMode=$strict")
        permissionPolicy = PermissionPolicy(applicationContext, strictMode = strict)
        registryStore = RegistryStore(permissionPolicy)
        startInForeground()
    }

    private fun readStrictPermissionMode(): Boolean {
        return try {
            val info = packageManager.getApplicationInfo(packageName, PackageManager.GET_META_DATA)
            info.metaData?.getBoolean(META_STRICT_PERMISSION_MODE, false) ?: false
        } catch (e: Exception) {
            IpcLog.w("RegistryService", "Failed to read strict-mode meta-data: ${e.message}")
            false
        }
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
        writer?.println("strictPermissionMode=${permissionPolicy.isStrictMode()}")
        writer?.println(registryStore.dump())
    }
}
