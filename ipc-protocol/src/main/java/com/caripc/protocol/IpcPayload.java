package com.caripc.protocol;

import android.os.Bundle;
import android.os.Parcel;
import android.os.Parcelable;
import com.caripc.contract.ValueType;

public final class IpcPayload implements Parcelable {
    public static final int MAX_PAYLOAD_BYTES = 64 * 1024; // 64 KiB limit

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
        if (stringVal != null && stringVal.length() > MAX_PAYLOAD_BYTES) {
            throw new IllegalArgumentException("Payload string exceeds max bytes");
        }
        this.stringVal = stringVal;
        if (bytesVal != null && bytesVal.length > MAX_PAYLOAD_BYTES) {
            throw new IllegalArgumentException("Payload bytes exceed max bytes");
        }
        this.bytesVal = bytesVal;
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

    public static IpcPayload ofAny(Object value) {
        if (value == null) return null;
        if (value instanceof Boolean) return ofBoolean((Boolean) value);
        if (value instanceof Integer) return ofInt((Integer) value);
        if (value instanceof Long) return ofLong((Long) value);
        if (value instanceof Float) return ofFloat((Float) value);
        if (value instanceof Double) return ofDouble((Double) value);
        if (value instanceof String) return ofString((String) value);
        if (value instanceof byte[]) return ofBytes((byte[]) value);
        if (value instanceof Bundle) return ofBundle((Bundle) value);
        throw new IllegalArgumentException("Unsupported value type: " + value.getClass().getName());
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
        throw new IllegalStateException("Unknown type tag: " + typeTag);
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
