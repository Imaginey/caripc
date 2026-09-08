package com.caripc.sdk

import android.content.ComponentName

data class CarIpcConfig(
    val registryComponent: ComponentName = ComponentName("com.caripc.registry", "com.caripc.registry.RegistryService"),
    val requestTimeoutMs: Long = 3000L,
    val awaitReadyTimeoutMs: Long = 5000L,
    val outboundCoreThreads: Int = 4,
    val outboundMaxThreads: Int = 8
)
