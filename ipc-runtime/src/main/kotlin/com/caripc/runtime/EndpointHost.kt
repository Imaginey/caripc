package com.caripc.runtime

import android.os.Binder
import com.caripc.contract.ErrorCode
import com.caripc.protocol.*

class EndpointHost(
    private val sessionManager: SessionManager,
    private val transportMajor: Int = 1,
    private val transportMinor: Int = 0
) : IEndpoint.Stub() {

    override fun openSession(hello: ClientHello, callback: IClientCallback) {
        val callingUid = Binder.getCallingUid()
        IpcLog.i("EndpointHost", "openSession() requested by UID $callingUid, clientInstanceId=${hello.clientInstanceId}")

        // 校验传输版本兼容性
        if (hello.transportMajor != transportMajor) {
            IpcLog.w("EndpointHost", "Session rejected: version mismatch (server=$transportMajor, client=${hello.transportMajor})")
            try {
                callback.onSessionRejected(
                    ErrorEnvelope(
                        ErrorCode.VERSION_MISMATCH.code,
                        "Incompatible transport version: server=$transportMajor, client=${hello.transportMajor}",
                        hello.openRequestId,
                        0,
                        null
                    )
                )
            } catch (_: Exception) {}
            return
        }

        try {
            val sessionStub = sessionManager.createSession(callingUid, hello, callback)
            val serverHello = ServerHello(
                transportMajor,
                transportMinor,
                sessionManager.serviceDescriptor.contractMajor,
                sessionManager.serviceDescriptor.contractMinor,
                sessionManager.serviceDescriptor.instanceId,
                sessionManager.serviceDescriptor.serviceId,
                sessionManager.serviceDescriptor.capabilities,
                IpcPayload.MAX_PAYLOAD_BYTES,
                16
            )
            IpcLog.i("EndpointHost", "openSession() succeeded for service ${sessionManager.serviceDescriptor.serviceId} to UID $callingUid")
            callback.onSessionOpened(serverHello, sessionStub)
        } catch (e: Exception) {
            IpcLog.e("EndpointHost", "openSession() exception for UID $callingUid", e)
            try {
                callback.onSessionRejected(
                    ErrorEnvelope(
                        ErrorCode.INTERNAL_ERROR.code,
                        e.message ?: "Failed to open session",
                        hello.openRequestId,
                        0,
                        null
                    )
                )
            } catch (_: Exception) {}
        }
    }
}
