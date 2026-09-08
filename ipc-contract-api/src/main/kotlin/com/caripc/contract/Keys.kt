package com.caripc.contract

sealed interface CapabilityKey {
    val id: String
}

data class NotificationPolicy(
    val minNotificationIntervalMs: Long = 0L,
    val minDelta: Double = 0.0
) {
    init {
        require(minNotificationIntervalMs >= 0) { "minNotificationIntervalMs must be non-negative" }
        require(minDelta >= 0.0 && !minDelta.isNaN() && !minDelta.isInfinite()) { "minDelta must be finite non-negative" }
    }
}

data class PropertyKey<T : Any>(
    override val id: String,
    val type: ValueType,
    val readable: Boolean = true,
    val writable: Boolean = false,
    val observable: Boolean = true,
    val unit: String? = null,
    val min: Double? = null,
    val max: Double? = null,
    val notificationPolicy: NotificationPolicy? = null
) : CapabilityKey {

    fun validate(value: T) {
        when (type) {
            ValueType.FLOAT -> {
                val f = value as? Float ?: throw IllegalArgumentException("Expected Float for key $id, got ${value::class.java}")
                if (f.isNaN() || f.isInfinite()) throw IllegalArgumentException("Float value for $id must be finite")
                min?.let { if (f < it) throw IllegalArgumentException("Value $f below min $it for key $id") }
                max?.let { if (f > it) throw IllegalArgumentException("Value $f exceeds max $it for key $id") }
            }
            ValueType.DOUBLE -> {
                val d = value as? Double ?: throw IllegalArgumentException("Expected Double for key $id, got ${value::class.java}")
                if (d.isNaN() || d.isInfinite()) throw IllegalArgumentException("Double value for $id must be finite")
                min?.let { if (d < it) throw IllegalArgumentException("Value $d below min $it for key $id") }
                max?.let { if (d > it) throw IllegalArgumentException("Value $d exceeds max $it for key $id") }
            }
            ValueType.INT -> {
                val i = value as? Int ?: throw IllegalArgumentException("Expected Int for key $id, got ${value::class.java}")
                min?.let { if (i < it) throw IllegalArgumentException("Value $i below min $it for key $id") }
                max?.let { if (i > it) throw IllegalArgumentException("Value $i exceeds max $it for key $id") }
            }
            ValueType.LONG -> {
                val l = value as? Long ?: throw IllegalArgumentException("Expected Long for key $id, got ${value::class.java}")
                min?.let { if (l < it) throw IllegalArgumentException("Value $l below min $it for key $id") }
                max?.let { if (l > it) throw IllegalArgumentException("Value $l exceeds max $it for key $id") }
            }
            ValueType.STRING -> {
                val s = value as? String ?: throw IllegalArgumentException("Expected String for key $id, got ${value::class.java}")
                max?.let { if (s.length > it) throw IllegalArgumentException("String length ${s.length} exceeds max $it for key $id") }
            }
            ValueType.BOOLEAN -> {
                if (value !is Boolean) throw IllegalArgumentException("Expected Boolean for key $id, got ${value::class.java}")
            }
            ValueType.BYTES -> {
                val b = value as? ByteArray ?: throw IllegalArgumentException("Expected ByteArray for key $id, got ${value::class.java}")
                max?.let { if (b.size > it) throw IllegalArgumentException("ByteArray size ${b.size} exceeds max $it for key $id") }
            }
            ValueType.RECORD -> Unit
            ValueType.BUNDLE -> Unit
        }
    }

    companion object {
        @JvmStatic
        @JvmName("booleanKey")
        @JvmOverloads
        fun boolean(id: String, readable: Boolean = true, writable: Boolean = false, observable: Boolean = true): PropertyKey<Boolean> =
            PropertyKey(id, ValueType.BOOLEAN, readable, writable, observable)

        @JvmStatic
        @JvmName("intKey")
        @JvmOverloads
        fun int(id: String, readable: Boolean = true, writable: Boolean = false, observable: Boolean = true, min: Int? = null, max: Int? = null, unit: String? = null): PropertyKey<Int> =
            PropertyKey(id, ValueType.INT, readable, writable, observable, unit, min?.toDouble(), max?.toDouble())

        @JvmStatic
        @JvmName("longKey")
        @JvmOverloads
        fun long(id: String, readable: Boolean = true, writable: Boolean = false, observable: Boolean = true, min: Long? = null, max: Long? = null, unit: String? = null): PropertyKey<Long> =
            PropertyKey(id, ValueType.LONG, readable, writable, observable, unit, min?.toDouble(), max?.toDouble())

        @JvmStatic
        @JvmName("floatKey")
        @JvmOverloads
        fun float(
            id: String,
            readable: Boolean = true,
            writable: Boolean = false,
            observable: Boolean = true,
            min: Float? = null,
            max: Float? = null,
            unit: String? = null,
            notificationPolicy: NotificationPolicy? = null
        ): PropertyKey<Float> =
            PropertyKey(id, ValueType.FLOAT, readable, writable, observable, unit, min?.toDouble(), max?.toDouble(), notificationPolicy)

        @JvmStatic
        @JvmName("doubleKey")
        @JvmOverloads
        fun double(id: String, readable: Boolean = true, writable: Boolean = false, observable: Boolean = true, min: Double? = null, max: Double? = null, unit: String? = null): PropertyKey<Double> =
            PropertyKey(id, ValueType.DOUBLE, readable, writable, observable, unit, min, max)

        @JvmStatic
        @JvmName("stringKey")
        @JvmOverloads
        fun string(id: String, readable: Boolean = true, writable: Boolean = false, observable: Boolean = true, maxLength: Int? = null): PropertyKey<String> =
            PropertyKey(id, ValueType.STRING, readable, writable, observable, null, null, maxLength?.toDouble())

        @JvmStatic
        @JvmName("bundleKey")
        @JvmOverloads
        fun <T : Any> bundle(id: String, readable: Boolean = true, writable: Boolean = false, observable: Boolean = true): PropertyKey<T> =
            PropertyKey(id, ValueType.BUNDLE, readable, writable, observable)
    }
}

data class EventKey<T : Any>(
    override val id: String,
    val type: ValueType
) : CapabilityKey {
    companion object {
        @JvmStatic
        fun string(id: String): EventKey<String> = EventKey(id, ValueType.STRING)

        @JvmStatic
        fun int(id: String): EventKey<Int> = EventKey(id, ValueType.INT)

        @JvmStatic
        fun <T : Any> bundle(id: String): EventKey<T> = EventKey(id, ValueType.BUNDLE)
    }
}

enum class RetryPolicy {
    NEVER,
    ONCE_IF_IDEMPOTENT
}

data class CommandKey<Req : Any, Resp : Any>(
    override val id: String,
    val reqType: ValueType,
    val respType: ValueType,
    val retryPolicy: RetryPolicy = RetryPolicy.NEVER
) : CapabilityKey {
    companion object {
        @JvmStatic
        @JvmOverloads
        fun stringToString(id: String, retryPolicy: RetryPolicy = RetryPolicy.NEVER): CommandKey<String, String> =
            CommandKey(id, ValueType.STRING, ValueType.STRING, retryPolicy)

        @JvmStatic
        @JvmOverloads
        fun stringToInt(id: String, retryPolicy: RetryPolicy = RetryPolicy.NEVER): CommandKey<String, Int> =
            CommandKey(id, ValueType.STRING, ValueType.INT, retryPolicy)

        @JvmStatic
        @JvmOverloads
        fun <Req : Any, Resp : Any> bundleToBundle(id: String, retryPolicy: RetryPolicy = RetryPolicy.NEVER): CommandKey<Req, Resp> =
            CommandKey(id, ValueType.BUNDLE, ValueType.BUNDLE, retryPolicy)
    }
}
