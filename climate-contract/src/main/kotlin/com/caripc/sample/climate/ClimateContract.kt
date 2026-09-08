package com.caripc.sample.climate

import com.caripc.contract.*

object ClimateContract {
    const val SERVICE_ID = "com.company.vehicle.climate"

    val TARGET_TEMPERATURE = PropertyKey.createFloat(
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

    val CABIN_TEMPERATURE = PropertyKey.createFloat(
        id = "cabin_temperature",
        readable = true,
        writable = false,
        observable = true,
        unit = "celsius",
        min = -40.0f,
        max = 80.0f
    )

    val FAN_SPEED = PropertyKey.createInt(
        id = "fan_speed",
        readable = true,
        writable = true,
        observable = true,
        min = 0,
        max = 7
    )

    val SELF_TEST_FINISHED = EventKey.createString("self_test_finished")

    val START_SELF_TEST = CommandKey.stringToString(
        id = "start_self_test",
        retryPolicy = RetryPolicy.NEVER
    )

    val schema = ServiceSchema(
        contractId = "vehicle.climate",
        major = 1,
        minor = 0,
        properties = listOf(TARGET_TEMPERATURE, CABIN_TEMPERATURE, FAN_SPEED),
        events = listOf(SELF_TEST_FINISHED),
        commands = listOf(START_SELF_TEST)
    )
}
