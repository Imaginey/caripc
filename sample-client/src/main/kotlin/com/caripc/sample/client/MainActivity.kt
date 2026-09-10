package com.caripc.sample.client

import android.app.Activity
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.caripc.contract.CancelHandle
import com.caripc.contract.PropertyUpdate
import com.caripc.contract.SetReceipt
import com.caripc.contract.SubscribeOptions
import com.caripc.sample.climate.ClimateContract
import com.caripc.sample.display.DisplayContract
import com.caripc.sdk.CarIpc
import com.caripc.sdk.RemoteService
import com.caripc.sdk.ServicePublisher
import com.caripc.sdk.ktx.*

class MainActivity : Activity() {

    private lateinit var ipc: CarIpc
    private var displayPublisher: ServicePublisher? = null
    private var climateClient: RemoteService? = null
    private var climateSubscription: CancelHandle? = null

    private val mainHandler = Handler(Looper.getMainLooper())
    private lateinit var logTextView: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 32, 32, 32)
        }

        val btnSetTemp = Button(this).apply {
            text = "Set Temperature to 26.5°C"
            setOnClickListener {
                setTargetTemp(26.5f)
            }
        }

        val btnSelfTest = Button(this).apply {
            text = "Trigger Self Test"
            setOnClickListener {
                startSelfTest()
            }
        }

        logTextView = TextView(this).apply {
            text = "CarIpc Client (A) Initializing..."
            textSize = 16f
        }

        val scrollView = ScrollView(this).apply {
            addView(logTextView)
        }

        layout.addView(btnSetTemp)
        layout.addView(btnSelfTest)
        layout.addView(scrollView)
        setContentView(layout)

        ipc = CarIpc.create(applicationContext)

        // 1. 作为服务端发布 Display 服务 (供 B 消费)
        publishDisplayService()

        // 2. 作为客户端连接 Climate 服务 (由 B 发布)
        connectClimateService()
    }

    private fun publishDisplayService() {
        displayPublisher = ipc.publishService(
            serviceId = DisplayContract.SERVICE_ID,
            schema = DisplayContract.SCHEMA
        ) {
            onSet(DisplayContract.CURRENT_TITLE) { value, _ ->
                mainHandler.post { logTextView.append("\n[Display Server] Title set to: $value") }
                SetReceipt.accepted()
            }

            onCall(DisplayContract.TRIGGER_ALERT) { param, callerUid ->
                mainHandler.post { logTextView.append("\n[Display Server] Alert triggered: $param by UID $callerUid") }
                "ALERT_DISPLAYED_OK"
            }
        }
        displayPublisher?.update(DisplayContract.CURRENT_TITLE, "Main Dashboard")
        displayPublisher?.update(DisplayContract.THEME_MODE, "Dark")
        logTextView.append("\n[Server] Display Service published.")
    }

    private fun connectClimateService() {
        val climate = ipc.connect(ClimateContract.SERVICE_ID)
        climateClient = climate

        climate.awaitReady(5000) { res ->
            res.onSuccess {
                mainHandler.post {
                    logTextView.append("\n[Client] Connected to Climate Service (B).")
                    subscribeClimate(climate)
                }
            }
            res.onFailure { th ->
                mainHandler.post {
                    logTextView.append("\n[Client] Climate awaitReady failed: ${th.message}")
                }
            }
        }
    }

    private fun subscribeClimate(climate: RemoteService) {
        climateSubscription = climate.subscribe(
            keys = listOf(
                ClimateContract.TARGET_TEMPERATURE,
                ClimateContract.CABIN_TEMPERATURE,
                ClimateContract.FAN_SPEED,
                ClimateContract.SELF_TEST_FINISHED
            ),
            options = SubscribeOptions(replayLatest = true)
        ) { message ->
            mainHandler.post {
                val prop = message.payload as? PropertyUpdate<*>
                if (prop != null) {
                    logTextView.append("\n[Sub] Property updated: ${prop.key.id} = ${prop.snapshot.value} (rev=${prop.snapshot.revision})")
                } else {
                    logTextView.append("\n[Sub] Message received: ${message.payload}")
                }
            }
        }
    }

    private fun setTargetTemp(temp: Float) {
        climateClient?.set(ClimateContract.TARGET_TEMPERATURE, temp) { res ->
            res.onSuccess { receipt ->
                mainHandler.post {
                    logTextView.append("\n[Set] Target temp $temp receipt: status=${receipt.status}")
                }
            }
            res.onFailure { err ->
                mainHandler.post {
                    logTextView.append("\n[Set] Target temp failed: ${err.message}")
                }
            }
        }
    }

    private fun startSelfTest() {
        climateClient?.call(ClimateContract.START_SELF_TEST, "PARAM_CHECK") { res ->
            res.onSuccess { receipt ->
                mainHandler.post {
                    logTextView.append("\n[Call] Self-test invoked: response=$receipt")
                }
            }
            res.onFailure { err ->
                mainHandler.post {
                    logTextView.append("\n[Call] Self-test failed: ${err.message}")
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        climateSubscription?.cancel()
        climateClient?.close()
        displayPublisher?.close()
        ipc.close()
    }
}
