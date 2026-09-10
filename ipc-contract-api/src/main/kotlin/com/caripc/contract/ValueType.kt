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
    BUNDLE(9),

    /**
     * 显式「无返回值」负载。用于声明 void / Unit 语义的命令：
     * 成功返回空结果与「类型不匹配」必须可区分，不能让 null 同时表达两件事。
     */
    NULL(10);

    companion object {
        @JvmStatic
        fun fromTag(tag: Int): ValueType = values().firstOrNull { it.typeTag == tag }
            ?: throw IllegalArgumentException("Unknown type tag: $tag")
    }
}
