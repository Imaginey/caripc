package com.caripc.sdk

import android.content.ComponentName
import com.caripc.runtime.PermissionPolicy

/**
 * 注意：加了 @JvmOverloads，Java 可以按位置传前 N 个参数（如 `new CarIpcConfig(component, 5000)`），
 * 无需写满全部字段；`aclProvider` 位置靠后，Java 要传它时前面的字段需一并给出（可传 null 跳过）。
 */
data class CarIpcConfig @JvmOverloads constructor(
    val registryComponent: ComponentName = ComponentName("com.caripc.registry", "com.caripc.registry.RegistryService"),
    val requestTimeoutMs: Long = 3000L,
    val awaitReadyTimeoutMs: Long = 5000L,
    val outboundCoreThreads: Int = 4,
    val outboundMaxThreads: Int = 8,

    /**
     * 严格鉴权模式：
     * - false（默认）：未配置 ACL 的 serviceId 放行，但每次打 WARN 日志，行为显式可见；
     * - true：未配置 ACL 的 serviceId 一律拒绝，且共享 UID（一个 UID 多个包）拒绝接入。
     */
    val strictPermissionMode: Boolean = false,

    /** 集成层提供的 ACL 查询钩子；返回 null 表示该 serviceId 未配置 ACL。 */
    val aclProvider: ((serviceId: String) -> PermissionPolicy.ServiceAcl?)? = null,

    /** 是否输出 debug/verbose 级日志（release 建议关闭）。 */
    val debugLogging: Boolean = true
)
