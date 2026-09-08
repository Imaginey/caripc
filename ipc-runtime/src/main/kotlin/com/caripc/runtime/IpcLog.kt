package com.caripc.runtime

import android.util.Log

/**
 * CarIpc 统一日志打印组件。
 * 统一前缀为 CarIpc，便于在 logcat 中使用 `logcat -s CarIpc*` 或过滤 `CarIpc` 查看所有跨进程关键链路。
 */
object IpcLog {
    private const val PREFIX = "CarIpc"

    @JvmStatic
    var isDebugEnabled: Boolean = true

    @JvmStatic
    var printToConsole: Boolean = false // 在单元测试或非 Android 终端环境可开启控制台输出

    @JvmStatic
    fun v(tag: String, message: String) {
        if (!isDebugEnabled) return
        val fullTag = "$PREFIX-$tag"
        try {
            Log.v(fullTag, message)
        } catch (_: Throwable) {}
        if (printToConsole) println("V/$fullTag: $message")
    }

    @JvmStatic
    fun d(tag: String, message: String) {
        if (!isDebugEnabled) return
        val fullTag = "$PREFIX-$tag"
        try {
            Log.d(fullTag, message)
        } catch (_: Throwable) {}
        if (printToConsole) println("D/$fullTag: $message")
    }

    @JvmStatic
    fun i(tag: String, message: String) {
        val fullTag = "$PREFIX-$tag"
        try {
            Log.i(fullTag, message)
        } catch (_: Throwable) {}
        if (printToConsole) println("I/$fullTag: $message")
    }

    @JvmStatic
    fun w(tag: String, message: String, tr: Throwable? = null) {
        val fullTag = "$PREFIX-$tag"
        try {
            if (tr != null) Log.w(fullTag, message, tr) else Log.w(fullTag, message)
        } catch (_: Throwable) {}
        if (printToConsole) {
            println("W/$fullTag: $message")
            tr?.printStackTrace()
        }
    }

    @JvmStatic
    fun e(tag: String, message: String, tr: Throwable? = null) {
        val fullTag = "$PREFIX-$tag"
        try {
            if (tr != null) Log.e(fullTag, message, tr) else Log.e(fullTag, message)
        } catch (_: Throwable) {}
        if (printToConsole) {
            System.err.println("E/$fullTag: $message")
            tr?.printStackTrace()
        }
    }
}
