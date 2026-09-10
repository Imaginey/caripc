package com.caripc.sample.display

import com.caripc.contract.*
import kotlin.jvm.JvmField

object DisplayContract {
    const val SERVICE_ID = "com.company.launcher.display"

    @JvmField val CURRENT_TITLE = PropertyKey.createString(
        id = "current_title",
        readable = true,
        writable = true,
        observable = true
    )

    @JvmField val THEME_MODE = PropertyKey.createString(
        id = "theme_mode",
        readable = true,
        writable = true,
        observable = true
    )

    @JvmField val TRIGGER_ALERT = CommandKey.stringToString(
        id = "trigger_alert",
        retryPolicy = RetryPolicy.NEVER
    )

    @JvmField val SCHEMA = ServiceSchema(
        contractId = "launcher.display",
        major = 1,
        minor = 0,
        properties = listOf(CURRENT_TITLE, THEME_MODE),
        events = emptyList(),
        commands = listOf(TRIGGER_ALERT)
    )

    /** 兼容 Kotlin 习惯与文档示例的小写访问器 */
    val schema: ServiceSchema get() = SCHEMA
}
