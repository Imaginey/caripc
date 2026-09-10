package com.caripc.sample.server

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import com.caripc.contract.ErrorCode
import com.caripc.contract.IpcError
import com.caripc.contract.SetReceipt
import com.caripc.runtime.IpcLog
import com.caripc.sample.climate.ClimateContract
import com.caripc.sdk.CarIpc
import com.caripc.sdk.ServicePublisher
import com.caripc.sdk.ktx.*

class ClimateServerService : Service() {

    private lateinit var ipc: CarIpc
    private var climatePublisher: ServicePublisher? = null
    private val mainHandler = Handler(Looper.getMainLooper())

    private var currentTargetTemp = 24.0f
    private var currentFanSpeed = 2
    private var currentCabinTemp = 23.5f

    override fun onCreate() {
        super.onCreate()
        IpcLog.i("ClimateServerService", "ClimateServerService onCreate() starting background daemon...")
        startInForeground()

        ipc = CarIpc.create(applicationContext)
        publishClimateService()
    }

    private fun publishClimateService() {
        climatePublisher = ipc.publishService(
            serviceId = ClimateContract.SERVICE_ID,
            schema = ClimateContract.SCHEMA
        ) {
            onSet(ClimateContract.TARGET_TEMPERATURE) { value, callerUid ->
                // 业务侧显式校验并拒绝非法值：错误要通过回执表达，而不是抛异常（见 P0-4/P2-13）
                if (value !in 16.0f..32.0f) {
                    IpcLog.w("ClimateServerService", "Rejecting out-of-range target temp: $value")
                    return@onSet SetReceipt.rejected(
                        IpcError(
                            ErrorCode.INVALID_ARGUMENT,
                            "target_temperature must be within [16.0, 32.0], got $value"
                        )
                    )
                }
                val callerPkg = packageManager.getPackagesForUid(callerUid)?.firstOrNull() ?: "UID_$callerUid"
                currentTargetTemp = value
                IpcLog.i("ClimateServerService", "Target temp set to: $value by $callerPkg (UID $callerUid)")

                mainHandler.postDelayed({
                    try {
                        climatePublisher?.update(ClimateContract.TARGET_TEMPERATURE, value)
                    } catch (e: Exception) {
                        IpcLog.e("ClimateServerService", "Failed to publish target temperature update", e)
                    }
                }, 100)
                SetReceipt.accepted()
            }

            onSet(ClimateContract.FAN_SPEED) { value, callerUid ->
                if (value !in 0..7) {
                    IpcLog.w("ClimateServerService", "Rejecting out-of-range fan speed: $value")
                    return@onSet SetReceipt.rejected(
                        IpcError(ErrorCode.INVALID_ARGUMENT, "fan_speed must be within [0, 7], got $value")
                    )
                }
                val callerPkg = packageManager.getPackagesForUid(callerUid)?.firstOrNull() ?: "UID_$callerUid"
                currentFanSpeed = value
                IpcLog.i("ClimateServerService", "Fan speed set to: $value by $callerPkg (UID $callerUid)")

                mainHandler.postDelayed({
                    try {
                        climatePublisher?.update(ClimateContract.FAN_SPEED, value)
                    } catch (e: Exception) {
                        IpcLog.e("ClimateServerService", "Failed to publish fan speed update", e)
                    }
                }, 100)
                SetReceipt.accepted()
            }

            onCall(ClimateContract.START_SELF_TEST) { _, callerUid ->
                val callerPkg = packageManager.getPackagesForUid(callerUid)?.firstOrNull() ?: "UID_$callerUid"
                IpcLog.i("ClimateServerService", "Self-test started by $callerPkg (UID $callerUid)")

                mainHandler.postDelayed({
                    try {
                        climatePublisher?.emit(ClimateContract.SELF_TEST_FINISHED, "SUCCESS_ALL_OK")
                        IpcLog.i("ClimateServerService", "Self-test completed, event SELF_TEST_FINISHED emitted.")
                    } catch (e: Exception) {
                        IpcLog.e("ClimateServerService", "Failed to emit self-test event", e)
                    }
                }, 500)
                "JOB_RECEIPT_1001"
            }
        }

        // 初始化默认属性快照
        climatePublisher?.update(ClimateContract.TARGET_TEMPERATURE, currentTargetTemp)
        climatePublisher?.update(ClimateContract.FAN_SPEED, currentFanSpeed)
        climatePublisher?.update(ClimateContract.CABIN_TEMPERATURE, currentCabinTemp)

        IpcLog.i("ClimateServerService", "Climate Service published successfully to Registry.")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        IpcLog.i("ClimateServerService", "ClimateServerService onStartCommand() started with START_STICKY")
        startInForeground()
        return START_STICKY
    }

    private fun startInForeground() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val channelId = "caripc_climate_channel"
                val notificationManager = getSystemService(NotificationManager::class.java)
                if (notificationManager != null && notificationManager.getNotificationChannel(channelId) == null) {
                    val channel = NotificationChannel(
                        channelId,
                        "CarIpc Climate Service",
                        NotificationManager.IMPORTANCE_LOW
                    ).apply {
                        description = "CarIpc Vehicle Climate Daemon"
                    }
                    notificationManager.createNotificationChannel(channel)
                }
                val notification = Notification.Builder(this, channelId)
                    .setContentTitle("Vehicle Climate Service")
                    .setContentText("Climate Daemon is active")
                    .setSmallIcon(android.R.drawable.stat_notify_sync)
                    .build()
                startForeground(1002, notification)
            }
        } catch (e: Exception) {
            IpcLog.w("ClimateServerService", "startForeground failed: ${e.message}")
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        IpcLog.i("ClimateServerService", "ClimateServerService onDestroy() shutting down...")
        climatePublisher?.close()
        ipc.close()
        super.onDestroy()
    }
}