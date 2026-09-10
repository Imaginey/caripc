package com.caripc.protocol;

import android.os.Bundle;
import android.os.Parcel;
import android.os.Parcelable;
import com.caripc.contract.CompletionState;
import com.caripc.contract.ErrorCode;
import com.caripc.contract.IpcError;
import com.caripc.contract.ValueType;

/**
 * 线协议负载。
 *
 * <p>超限统一抛出 {@link IpcError}（PAYLOAD_LARGE / INVALID_ARGUMENT），
 * 而不是裸 {@link IllegalArgumentException}，以便调用方把它转成业务可见的错误回调；
 * 「无返回值」用 {@link ValueType#NULL} 显式表达，避免与「类型不匹配」混为一谈。</p>
 */
public final class IpcPayload implements Parcelable {
    public static final int MAX_PAYLOAD_BYTES = 64 * 1024; // 64 KiB limit（UTF-8 字节估算）

    public final int typeTag;
    public final boolean booleanVal;
    public final int intVal;
    public final long longVal;
    public final float floatVal;
    public final double doubleVal;
    public final String stringVal;
    public final byte[] bytesVal;
    public final Bundle bundleVal;

    public IpcPayload(int typeTag, boolean booleanVal, int intVal, long longVal, float floatVal, double doubleVal, String stringVal, byte[] bytesVal) {
        this(typeTag, booleanVal, intVal, longVal, floatVal, doubleVal, stringVal, bytesVal, null);
    }

    public IpcPayload(int typeTag, boolean booleanVal, int intVal, long longVal, float floatVal, double doubleVal, String stringVal, byte[] bytesVal, Bundle bundleVal) {
        this.typeTag = typeTag;
        this.booleanVal = booleanVal;
        this.intVal = intVal;
        this.longVal = longVal;
        this.floatVal = floatVal;
        this.doubleVal = doubleVal;
        if (stringVal != null) {
            int size = utf8Length(stringVal);
            if (size > MAX_PAYLOAD_BYTES) {
                throw new IpcError(
                        ErrorCode.PAYLOAD_LARGE,
                        "Payload string size " + size + " bytes exceeds max " + MAX_PAYLOAD_BYTES + " bytes",
                        null,
                        CompletionState.NOT_EXECUTED,
                        null);
            }
        }
        this.stringVal = stringVal;
        if (bytesVal != null && bytesVal.length > MAX_PAYLOAD_BYTES) {
            throw new IpcError(
                    ErrorCode.PAYLOAD_LARGE,
                    "Payload bytes size " + bytesVal.length + " exceeds max " + MAX_PAYLOAD_BYTES + " bytes",
                    null,
                    CompletionState.NOT_EXECUTED,
                    null);
        }
        this.bytesVal = bytesVal;
        if (bundleVal != null) {
            int size = bundleByteSize(bundleVal);
            if (size > MAX_PAYLOAD_BYTES) {
                throw new IpcError(
                        ErrorCode.PAYLOAD_LARGE,
                        "Payload bundle size " + size + " bytes exceeds max " + MAX_PAYLOAD_BYTES + " bytes",
                        null,
                        CompletionState.NOT_EXECUTED,
                        null);
            }
        }
        this.bundleVal = bundleVal;
    }

    private IpcPayload(Parcel in) {
        typeTag = in.readInt();
        booleanVal = in.readByte() != 0;
        intVal = in.readInt();
        longVal = in.readLong();
        floatVal = in.readFloat();
        doubleVal = in.readDouble();
        stringVal = in.readString();
        bytesVal = in.createByteArray();
        bundleVal = in.readBundle(IpcPayload.class.getClassLoader());
    }

    public static IpcPayload ofBoolean(boolean value) {
        return new IpcPayload(ValueType.BOOLEAN.getTypeTag(), value, 0, 0L, 0f, 0d, null, null);
    }

    public static IpcPayload ofInt(int value) {
        return new IpcPayload(ValueType.INT.getTypeTag(), false, value, 0L, 0f, 0d, null, null);
    }

    public static IpcPayload ofLong(long value) {
        return new IpcPayload(ValueType.LONG.getTypeTag(), false, 0, value, 0f, 0d, null, null);
    }

    public static IpcPayload ofFloat(float value) {
        return new IpcPayload(ValueType.FLOAT.getTypeTag(), false, 0, 0L, value, 0d, null, null);
    }

    public static IpcPayload ofDouble(double value) {
        return new IpcPayload(ValueType.DOUBLE.getTypeTag(), false, 0, 0L, 0f, value, null, null);
    }

    public static IpcPayload ofString(String value) {
        return new IpcPayload(ValueType.STRING.getTypeTag(), false, 0, 0L, 0f, 0d, value, null);
    }

    public static IpcPayload ofBytes(byte[] value) {
        return new IpcPayload(ValueType.BYTES.getTypeTag(), false, 0, 0L, 0f, 0d, null, value);
    }

    public static IpcPayload ofBundle(Bundle value) {
        return new IpcPayload(ValueType.BUNDLE.getTypeTag(), false, 0, 0L, 0f, 0d, null, null, value);
    }

    /** 显式「无返回值」负载（void / Unit 命令的成功结果）。 */
    public static IpcPayload ofNull() {
        return new IpcPayload(ValueType.NULL.getTypeTag(), false, 0, 0L, 0f, 0d, null, null, null);
    }

    public static IpcPayload ofAny(Object value) {
        if (value == null) return null;
        if (value == kotlin.Unit.INSTANCE) return ofNull();
        if (value instanceof Boolean) return ofBoolean((Boolean) value);
        if (value instanceof Integer) return ofInt((Integer) value);
        if (value instanceof Long) return ofLong((Long) value);
        if (value instanceof Float) return ofFloat((Float) value);
        if (value instanceof Double) return ofDouble((Double) value);
        if (value instanceof String) return ofString((String) value);
        if (value instanceof byte[]) return ofBytes((byte[]) value);
        if (value instanceof Bundle) return ofBundle((Bundle) value);
        throw new IpcError(
                ErrorCode.INVALID_ARGUMENT,
                "Unsupported value type: " + value.getClass().getName(),
                null,
                CompletionState.NOT_EXECUTED,
                null);
    }

    public Object toValue() {
        if (typeTag == ValueType.BOOLEAN.getTypeTag()) return booleanVal;
        if (typeTag == ValueType.INT.getTypeTag()) return intVal;
        if (typeTag == ValueType.LONG.getTypeTag()) return longVal;
        if (typeTag == ValueType.FLOAT.getTypeTag()) return floatVal;
        if (typeTag == ValueType.DOUBLE.getTypeTag()) return doubleVal;
        if (typeTag == ValueType.STRING.getTypeTag()) return stringVal;
        if (typeTag == ValueType.BYTES.getTypeTag()) return bytesVal;
        if (typeTag == ValueType.BUNDLE.getTypeTag()) return bundleVal;
        if (typeTag == ValueType.NULL.getTypeTag()) return null;
        throw new IllegalStateException("Unknown type tag: " + typeTag);
    }

    /** 本负载在传输上的近似字节数，用于与对端协商的 maxPayloadBytes 比对。 */
    public int byteSize() {
        if (typeTag == ValueType.STRING.getTypeTag()) return stringVal != null ? utf8Length(stringVal) : 0;
        if (typeTag == ValueType.BYTES.getTypeTag()) return bytesVal != null ? bytesVal.length : 0;
        if (typeTag == ValueType.BUNDLE.getTypeTag()) return bundleVal != null ? bundleByteSize(bundleVal) : 0;
        if (typeTag == ValueType.NULL.getTypeTag()) return 0;
        return 8;
    }

    /** UTF-8 字节数，超限即提前返回，避免为超长字符串分配副本。 */
    private static int utf8Length(String s) {
        int len = 0;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c < 0x80) {
                len += 1;
            } else if (c < 0x800) {
                len += 2;
            } else if (Character.isHighSurrogate(c) && i + 1 < s.length() && Character.isLowSurrogate(s.charAt(i + 1))) {
                len += 4;
                i++;
            } else {
                len += 3;
            }
            if (len > MAX_PAYLOAD_BYTES) return len;
        }
        return len;
    }

    /**
     * 用 Parcel 试写一次得到 Bundle 的真实尺寸。
     * 单元测试（android.jar stub，Parcel.obtain() 返回 null）下跳过检查。
     */
    private static int bundleByteSize(Bundle bundle) {
        Parcel parcel = Parcel.obtain();
        if (parcel == null) return 0;
        try {
            parcel.writeBundle(bundle);
            return parcel.dataSize();
        } catch (RuntimeException e) {
            throw new IpcError(
                    ErrorCode.INVALID_ARGUMENT,
                    "Bundle payload cannot be marshalled: " + e.getMessage(),
                    null,
                    CompletionState.NOT_EXECUTED,
                    null);
        } finally {
            try {
                parcel.recycle();
            } catch (RuntimeException ignored) {
                // 忽略回收异常
            }
        }
    }

    @Override
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeInt(typeTag);
        dest.writeByte((byte) (booleanVal ? 1 : 0));
        dest.writeInt(intVal);
        dest.writeLong(longVal);
        dest.writeFloat(floatVal);
        dest.writeDouble(doubleVal);
        dest.writeString(stringVal);
        dest.writeByteArray(bytesVal);
        dest.writeBundle(bundleVal);
    }

    @Override
    public int describeContents() {
        return 0;
    }

    public static final Creator<IpcPayload> CREATOR = new Creator<IpcPayload>() {
        @Override
        public IpcPayload createFromParcel(Parcel in) {
            return new IpcPayload(in);
        }

        @Override
        public IpcPayload[] newArray(int size) {
            return new IpcPayload[size];
        }
    };
}
