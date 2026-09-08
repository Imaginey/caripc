package com.caripc.contract

enum class Quality(val value: Int) {
    VALID(1),
    UNINITIALIZED(2),
    UNAVAILABLE(3),
    STALE(4);

    companion object {
        @JvmStatic
        fun fromValue(value: Int): Quality = values().firstOrNull { it.value == value } ?: UNAVAILABLE
    }
}
