package com.caripc.runtime

import android.content.Context
import android.content.pm.PackageManager
import android.os.Binder
import android.os.Build
import com.caripc.contract.ErrorCode
import com.caripc.contract.IpcError
import java.security.MessageDigest

class PermissionPolicy(private val context: Context) {

    data class ServiceAcl(
        val serviceId: String,
        val allowedPublisherPackages: Set<String> = emptySet(),
        val allowedReaderPackages: Set<String> = emptySet(),
        val allowedWriterPackages: Set<String> = emptySet(),
        val requiredPublisherCertSha256: String? = null
    )

    private val aclRules = mutableMapOf<String, ServiceAcl>()

    fun registerAcl(acl: ServiceAcl) {
        aclRules[acl.serviceId] = acl
    }

    fun captureCallingUid(): Int {
        return Binder.getCallingUid()
    }

    fun getPackageName(uid: Int): String {
        return try {
            val packages = context.packageManager?.getPackagesForUid(uid)
            packages?.firstOrNull() ?: "uid:$uid"
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
        val acl = aclRules[serviceId] ?: return // 若无特定 ACL 则允许通过

        val pm = context.packageManager
        val packages = pm.getPackagesForUid(callingUid) ?: throw IpcError(
            ErrorCode.PERMISSION_DENIED,
            "No packages associated with calling UID $callingUid"
        )

        // 1. 检查包名白名单
        if (acl.allowedPublisherPackages.isNotEmpty()) {
            val hasAllowedPackage = packages.any { it in acl.allowedPublisherPackages }
            if (!hasAllowedPackage) {
                throw IpcError(
                    ErrorCode.PERMISSION_DENIED,
                    "Calling UID $callingUid (packages: ${packages.joinToString()}) not authorized to publish $serviceId"
                )
            }
        }

        // 2. 检查证书 SHA-256 摘要
        if (!acl.requiredPublisherCertSha256.isNullOrBlank()) {
            var certMatched = false
            for (pkg in packages) {
                if (checkSigningCertificateSha256(pm, pkg, acl.requiredPublisherCertSha256)) {
                    certMatched = true
                    break
                }
            }
            if (!certMatched) {
                throw IpcError(
                    ErrorCode.PERMISSION_DENIED,
                    "Calling UID $callingUid failed certificate verification for $serviceId"
                )
            }
        }
    }

    fun authorizeOperation(callingUid: Int, serviceId: String, capabilityId: String, isWrite: Boolean) {
        val acl = aclRules[serviceId] ?: return
        val allowedSet = if (isWrite) acl.allowedWriterPackages else acl.allowedReaderPackages
        if (allowedSet.isEmpty()) return

        val pm = context.packageManager
        val packages = pm.getPackagesForUid(callingUid) ?: throw IpcError(
            ErrorCode.PERMISSION_DENIED,
            "No packages for UID $callingUid"
        )

        if (!packages.any { it in allowedSet }) {
            throw IpcError(
                ErrorCode.PERMISSION_DENIED,
                "UID $callingUid is not allowed to ${if (isWrite) "write" else "read"} $capabilityId on $serviceId"
            )
        }
    }

    private fun checkSigningCertificateSha256(pm: PackageManager, packageName: String, expectedSha256: String): Boolean {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                val hexClean = expectedSha256.replace(":", "").lowercase()
                val bytes = hexStringToByteArray(hexClean)
                if (pm.hasSigningCertificate(packageName, bytes, 1 /* CERT_INPUT_SHA256_DIGEST */)) {
                    return true
                }
            }
            // 回退兼容方案
            @Suppress("DEPRECATION")
            val info = pm.getPackageInfo(packageName, PackageManager.GET_SIGNATURES)
            val signatures = info.signatures ?: return false
            for (sig in signatures) {
                val md = MessageDigest.getInstance("SHA-256")
                val digest = md.digest(sig.toByteArray())
                val hex = bytesToHex(digest)
                if (hex.equals(expectedSha256.replace(":", ""), ignoreCase = true)) {
                    return true
                }
            }
        } catch (e: Exception) {
            return false
        }
        return false
    }

    private fun hexStringToByteArray(s: String): ByteArray {
        val len = s.length
        val data = ByteArray(len / 2)
        var i = 0
        while (i < len) {
            data[i / 2] = ((Character.digit(s[i], 16) shl 4) + Character.digit(s[i + 1], 16)).toByte()
            i += 2
        }
        return data
    }

    private fun bytesToHex(bytes: ByteArray): String {
        val sb = StringBuilder()
        for (b in bytes) {
            sb.append(String.format("%02x", b))
        }
        return sb.toString()
    }
}
