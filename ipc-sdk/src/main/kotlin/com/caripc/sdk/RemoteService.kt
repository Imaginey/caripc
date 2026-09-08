package com.caripc.sdk

import com.caripc.contract.*
import java.io.Closeable

interface RemoteService : Closeable {
    fun awaitReady(timeoutMs: Long, callback: ResultCallback<Unit>)
    fun awaitReady(timeoutMs: Long, callback: IpcCallback<Unit>)

    fun <T : Any> get(key: PropertyKey<T>, callback: ResultCallback<PropertySnapshot<T>>)
    fun <T : Any> get(key: PropertyKey<T>, callback: IpcCallback<PropertySnapshot<T>>)

    fun <T : Any> set(key: PropertyKey<T>, value: T, callback: ResultCallback<SetReceipt>)
    fun <T : Any> set(key: PropertyKey<T>, value: T, callback: IpcCallback<SetReceipt>)

    fun <T : Any> setIfVersion(key: PropertyKey<T>, value: T, writeToken: String?, callback: ResultCallback<SetReceipt>)
    fun <T : Any> setIfVersion(key: PropertyKey<T>, value: T, writeToken: String?, callback: IpcCallback<SetReceipt>)

    fun <Req : Any, Resp : Any> call(command: CommandKey<Req, Resp>, param: Req, callback: ResultCallback<Resp>)
    fun <Req : Any, Resp : Any> call(command: CommandKey<Req, Resp>, param: Req, callback: IpcCallback<Resp>)

    fun subscribe(keys: List<CapabilityKey>, options: SubscribeOptions, listener: SubscriptionListener): CancelHandle
    fun subscribe(keys: List<CapabilityKey>, listener: SubscriptionListener): CancelHandle =
        subscribe(keys, SubscribeOptions(), listener)

    override fun close()

    fun interface SubscriptionListener {
        fun onMessage(message: SubscriptionMessage)
    }
}
