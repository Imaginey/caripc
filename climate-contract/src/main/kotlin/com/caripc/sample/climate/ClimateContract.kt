package com.caripc.sample.climate

import com.caripc.contract.*
import kotlin.jvm.JvmField

object ClimateContract {
    const val SERVICE_ID = "com.company.vehicle.climate"

    @JvmField val TARGET_TEMPERATURE = PropertyKey.createFloat(
        id = "target_temperature",
        readable = true,
        writable = true,
        observable = true,
        unit = "celsius",
        min = 16.0f,
        max = 32.0f,
        notificationPolicy = NotificationPolicy(
            minNotificationIntervalMs = 50L,
            minDelta = 0.5
        )
    )

    @JvmField val CABIN_TEMPERATURE = PropertyKey.createFloat(
        id = "cabin_temperature",
        readable = true,
        writable = false,
        observable = true,
        unit = "celsius",
        min = -40.0f,
        max = 80.0f
    )

    @JvmField val FAN_SPEED = PropertyKey.createInt(
        id = "fan_speed",
        readable = true,
        writable = true,
        observable = true,
        min = 0,
        max = 7
    )

    @JvmField val SELF_TEST_FINISHED = EventKey.createString("self_test_finished")

    @JvmField val START_SELF_TEST = CommandKey.stringToString(
        id = "start_self_test",
        retryPolicy = RetryPolicy.NEVER
    )

    @JvmField val SCHEMA = ServiceSchema(
        contractId = "vehicle.climate",
        major = 1,
        minor = 0,
        properties = listOf(TARGET_TEMPERATURE, CABIN_TEMPERATURE, FAN_SPEED),
        events = listOf(SELF_TEST_FINISHED),
        commands = listOf(START_SELF_TEST)
    )

    /** 兼容 Kotlin 习惯与文档示例的小写访问器 */
    val schema: ServiceSchema get() = SCHEMA
}
