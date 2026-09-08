package com.caripc.sdk

import com.caripc.contract.EventKey
import com.caripc.contract.PropertyKey
import com.caripc.contract.Quality
import java.io.Closeable

interface ServicePublisher : Closeable {
    fun <T : Any> update(key: PropertyKey<T>, value: T)
    fun <T : Any> update(key: PropertyKey<T>, value: T, quality: Quality)
    fun <T : Any> emit(eventKey: EventKey<T>, payload: T)
    override fun close()
}
