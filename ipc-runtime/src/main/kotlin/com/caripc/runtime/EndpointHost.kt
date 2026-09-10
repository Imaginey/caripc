package com.caripc.runtime

import android.os.Binder
import com.caripc.contract.ErrorCode
import com.caripc.contract.IpcError
import com.caripc.protocol.*

class EndpointHost(
    private val sessionManager: SessionManager,
    private val transportMajor: Int = 1,
    private val transportMinor: Int = 0
) : IEndpoint.Stub() {

    override fun openSession(hello: ClientHello, callback: IClientCallback) {
        val callingUid = Binder.getCallingUid()
        IpcLog.i("EndpointHost", "openSession() requested by UID $callingUid, clientInstanceId=${hello.clientInstanceId}")

        val descriptor = sessionManager.serviceDescriptor

        // 传输版本：主版本必须一致
        if (hello.transportMajor != transportMajor) {
            reject(
                hello,
                callback,
                IpcError(
                    ErrorCode.VERSION_MISMATCH,
                    "Incompatible transport version: server=$transportMajor, client=${hello.transportMajor}",
                    hello.openRequestId
                )
            )
            return
        }
        // 契约版本：主版本一致、客户端次版本不得高于服务端（工单 P1-9）
        if (hello.contractMajor != descriptor.contractMajor) {
            reject(
                hello,
                callback,
                IpcError(
                    ErrorCode.VERSION_MISMATCH,
                    "Incompatible contract major: server=${descriptor.contractMajor}, client=${hello.contractMajor}",
                    hello.openRequestId
                )
            )
            return
        }
        if (hello.contractMinor > descriptor.contractMinor) {
            reject(
                hello,
                callback,
                IpcError(
                    ErrorCode.VERSION_MISMATCH,
                    "Client contract minor ${hello.contractMinor} is newer than server ${descriptor.contractMinor}",
                    hello.openRequestId
                )
            )
            return
        }

        try {
            val created = sessionManager.createSession(callingUid, hello, callback)
            val serverHello = ServerHello(
                transportMajor,
                transportMinor,
                descriptor.contractMajor,
                descriptor.contractMinor,
                descriptor.instanceId,
                created.sessionId,
                descriptor.capabilities,
                IpcPayload.MAX_PAYLOAD_BYTES,
                SessionManager.SUPPORTED_ACK_WINDOW
            )
            IpcLog.i(
                "EndpointHost",
                "openSession() succeeded for service ${descriptor.serviceId} to UID $callingUid (sessionId=${created.sessionId}, ackWindow=${SessionManager.SUPPORTED_ACK_WINDOW})"
            )
            callback.onSessionOpened(serverHello, created.session)
        } catch (e: Exception) {
            val error = e as? IpcError ?: IpcError(
                ErrorCode.INTERNAL_ERROR,
                e.message ?: "Failed to open session",
                hello.openRequestId
            )
            IpcLog.e("EndpointHost", "openSession() failed for UID $callingUid: ${error.message}", e)
            reject(hello, callback, error)
        }
    }

    private fun reject(hello: ClientHello, callback: IClientCallback, error: IpcError) {
        IpcLog.w("EndpointHost", "Session rejected: ${error.message}")
        try {
            callback.onSessionRejected(ErrorEnvelope(error))
        } catch (_: Exception) {
        }
    }
}
