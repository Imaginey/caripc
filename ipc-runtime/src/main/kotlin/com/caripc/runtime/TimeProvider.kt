package com.caripc.runtime

object TimeProvider {
    var timeSource: () -> Long = {
        try {
            android.os.SystemClock.elapsedRealtime()
        } catch (_: Throwable) {
            System.currentTimeMillis()
        }
    }

    fun elapsedRealtime(): Long = timeSource()
}
