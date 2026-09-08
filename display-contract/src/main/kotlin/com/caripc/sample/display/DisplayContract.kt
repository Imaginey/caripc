package com.caripc.sample.display

import com.caripc.contract.*

object DisplayContract {
    const val SERVICE_ID = "com.company.launcher.display"

    val CURRENT_TITLE = PropertyKey.string(
        id = "current_title",
        readable = true,
        writable = true,
        observable = true
    )

    val THEME_MODE = PropertyKey.string(
        id = "theme_mode",
        readable = true,
        writable = true,
        observable = true
    )

    val TRIGGER_ALERT = CommandKey.stringToString(
        id = "trigger_alert",
        retryPolicy = RetryPolicy.NEVER
    )

    val schema = ServiceSchema(
        contractId = "launcher.display",
        major = 1,
        minor = 0,
        properties = listOf(CURRENT_TITLE, THEME_MODE),
        events = emptyList(),
        commands = listOf(TRIGGER_ALERT)
    )
}
