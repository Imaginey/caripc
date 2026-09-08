package com.caripc.registry

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import com.caripc.runtime.IpcLog

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        if (action == Intent.ACTION_BOOT_COMPLETED || action == "android.intent.action.QUICKBOOT_POWERON") {
            IpcLog.i("BootReceiver", "Received $action, launching RegistryService...")
            val serviceIntent = Intent(context, RegistryService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(serviceIntent)
            } else {
                context.startService(serviceIntent)
            }
        }
    }
}