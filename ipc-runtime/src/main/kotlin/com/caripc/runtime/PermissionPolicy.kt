package com.caripc.runtime

import android.content.Context
import android.content.pm.PackageManager
import android.os.Binder
import android.os.Build
import com.caripc.contract.ErrorCode
import com.caripc.contract.IpcError
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap

/**
 * 鉴权策略。
 *
 * 修复要点（工单 P1-7 / P3-6）：
 * - 不再「未配置 ACL 就静默放行」：默认放行但每次打 WARN；[strictMode] 下直接拒绝；
 * - 共享 UID（一个 UID 关联多个包）在严格模式下拒绝，非严格模式告警，不再假装能区分真实调用应用；
 * - 证书摘要解析对非法输入显式失败并记录日志，不再静默返回 false。
 */
class PermissionPolicy(
    private val context: Context,
    private val strictMode: Boolean = false,
    private val aclProvider: ((serviceId: String) -> ServiceAcl?)? = null
) {

    data class ServiceAcl @JvmOverloads constructor(
        val serviceId: String,
        val allowedPublisherPackages: Set<String> = emptySet(),
        val allowedReaderPackages: Set<String> = emptySet(),
        val allowedWriterPackages: Set<String> = emptySet(),
        val requiredPublisherCertSha256: String? = null
    )

    private val aclRules = mutableMapOf<String, ServiceAcl>()
    private val warnedServices = ConcurrentHashMap.newKeySet<String>()

    fun registerAcl(acl: ServiceAcl) {
        aclRules[acl.serviceId] = acl
    }

    fun isStrictMode(): Boolean = strictMode

    fun captureCallingUid(): Int {
        return Binder.getCallingUid()
    }

    fun getPackageName(uid: Int): String {
        return try {
            val packages = context.packageManager?.getPackagesForUid(uid)
            when {
                packages == null || packages.isEmpty() -> "uid:$uid"
                packages.size == 1 -> packages[0]
                else -> "${packages[0]} (+${packages.size - 1} shared)"
            }
        } catch (_: Throwable) {
            "uid:$uid"
        }
    }

    fun validateSessionOwner(callingUid: Int, sessionOwnerUid: Int) {
        if (callingUid != sessionOwnerUid) {
            throw IpcError(
                ErrorCode.PERMISSION_DENIED,
                "Binder calling UID $callingUid does not match session owner UID $sessionOwnerUid"
            )
        }
    }

    fun authorizePublish(callingUid: Int, serviceId: String) {
        val acl = aclFor(serviceId)
        if (acl == null) {
            enforceDefaultPolicy(serviceId, "publish")
            return
        }

        val packages = packagesForUid(callingUid, serviceId, "publish")

        // 1. 包名白名单
        if (acl.allowedPublisherPackages.isNotEmpty()) {
            val hasAllowedPackage = packages.any { it in acl.allowedPublisherPackages }
            if (!hasAllowedPackage) {
                throw IpcError(
                    ErrorCode.PERMISSION_DENIED,
                    "Calling UID $callingUid (packages: ${packages.joinToString()}) not authorized to publish $serviceId"
                )
            }
        }

        // 2. 证书 SHA-256
        if (!acl.requiredPublisherCertSha256.isNullOrBlank()) {
            val pm = context.packageManager
            val certMatched = packages.any { checkSigningCertificateSha256(pm, it, acl.requiredPublisherCertSha256) }
            if (!certMatched) {
                throw IpcError(
                    ErrorCode.PERMISSION_DENIED,
                    "Calling UID $callingUid failed certificate verification for $serviceId"
                )
            }
        }
    }

    fun authorizeOperation(callingUid: Int, serviceId: String, capabilityId: String, isWrite: Boolean) {
        val acl = aclFor(serviceId)
        if (acl == null) {
            enforceDefaultPolicy(serviceId, if (isWrite) "write" else "read")
            return
        }
        val allowedSet = if (isWrite) acl.allowedWriterPackages else acl.allowedReaderPackages
        if (allowedSet.isEmpty()) return

        val packages = packagesForUid(callingUid, serviceId, if (isWrite) "write" else "read")
        if (!packages.any { it in allowedSet }) {
            throw IpcError(
                ErrorCode.PERMISSION_DENIED,
                "UID $callingUid is not allowed to ${if (isWrite) "write" else "read"} $capabilityId on $serviceId"
            )
        }
    }

    private fun aclFor(serviceId: String): ServiceAcl? = aclProvider?.invoke(serviceId) ?: aclRules[serviceId]

    /** 未配置 ACL：严格模式拒绝，否则放行但显式告警（不再静默 fail-open）。 */
    private fun enforceDefaultPolicy(serviceId: String, operation: String) {
        if (strictMode) {
            throw IpcError(
                ErrorCode.PERMISSION_DENIED,
                "No ACL configured for $serviceId (strict permission mode denies $operation)"
            )
        }
        if (warnedServices.add(serviceId)) {
            IpcLog.w(
                "PermissionPolicy",
                "No ACL configured for $serviceId; $operation is allowed by default. " +
                    "Configure ServiceAcl (or enable strictPermissionMode) to enforce access control."
            )
        }
    }

    private fun packagesForUid(callingUid: Int, serviceId: String, operation: String): List<String> {
        val pm = context.packageManager
        val packages = pm?.getPackagesForUid(callingUid)
        if (packages.isNullOrEmpty()) {
            throw IpcError(
                ErrorCode.PERMISSION_DENIED,
                "No packages associated with calling UID $callingUid"
            )
        }
        if (packages.size > 1) {
            val message = "UID $callingUid is shared by ${packages.size} packages (${packages.joinToString()}); " +
                "caller identity cannot be distinguished"
            if (strictMode) {
                throw IpcError(ErrorCode.PERMISSION_DENIED, "$message; $operation denied in strict mode")
            }
            IpcLog.w("PermissionPolicy", "$message; $operation to $serviceId allowed by default")
        }
        return packages.toList()
    }

    private fun checkSigningCertificateSha256(pm: PackageManager?, packageName: String, expectedSha256: String): Boolean {
        if (pm == null) return false
        val expectedHex = expectedSha256.replace(":", "").trim().lowercase()
        val expectedBytes = hexStringToByteArray(expectedHex) ?: run {
            IpcLog.e("PermissionPolicy", "Invalid SHA-256 hex for certificate check on $packageName: '$expectedSha256'")
            return false
        }
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                if (pm.hasSigningCertificate(packageName, expectedBytes, 1 /* CERT_INPUT_SHA256_DIGEST */)) {
                    return true
                }
            }
            // 回退兼容方案
            @Suppress("DEPRECATION")
            val info = pm.getPackageInfo(packageName, PackageManager.GET_SIGNATURES)
            val signatures = info?.signatures ?: return false
            for (sig in signatures) {
                val md = MessageDigest.getInstance("SHA-256")
                val hex = bytesToHex(md.digest(sig.toByteArray()))
                if (hex.equals(expectedHex, ignoreCase = true)) {
                    return true
                }
            }
        } catch (e: Exception) {
            IpcLog.w("PermissionPolicy", "Certificate check failed for $packageName: ${e.message}")
            return false
        }
        return false
    }

    /** 非法输入返回 null（不再因为奇数长度静默抛异常被吞）。 */
    private fun hexStringToByteArray(s: String): ByteArray? {
        if (s.isEmpty() || s.length % 2 != 0) return null
        val data = ByteArray(s.length / 2)
        var i = 0
        while (i < s.length) {
            val high = Character.digit(s[i], 16)
            val low = Character.digit(s[i + 1], 16)
            if (high < 0 || low < 0) return null
            data[i / 2] = ((high shl 4) + low).toByte()
            i += 2
        }
        return data
    }

    private fun bytesToHex(bytes: ByteArray): String {
        val sb = StringBuilder(bytes.size * 2)
        for (b in bytes) {
            sb.append(String.format("%02x", b))
        }
        return sb.toString()
    }
}
