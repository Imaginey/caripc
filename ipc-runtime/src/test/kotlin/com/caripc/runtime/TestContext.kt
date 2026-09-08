package com.caripc.runtime

import android.content.Context
import android.content.ContextWrapper
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageManager

open class TestContext : ContextWrapper(null) {
    override fun getApplicationContext(): Context = this
    override fun getPackageName(): String = "com.caripc.test"
    override fun getApplicationInfo(): ApplicationInfo = ApplicationInfo().apply {
        packageName = "com.caripc.test"
        uid = 10001
    }
}
