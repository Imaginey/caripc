package com.caripc.contract

/**
 * Supported wire value types for CarIpc middleware.
 */
enum class ValueType(val typeTag: Int) {
    BOOLEAN(1),
    INT(2),
    LONG(3),
    FLOAT(4),
    DOUBLE(5),
    STRING(6),
    BYTES(7),
    RECORD(8),
    BUNDLE(9);

    companion object {
        @JvmStatic
        fun fromTag(tag: Int): ValueType = values().firstOrNull { it.typeTag == tag }
            ?: throw IllegalArgumentException("Unknown type tag: $tag")
    }
}
