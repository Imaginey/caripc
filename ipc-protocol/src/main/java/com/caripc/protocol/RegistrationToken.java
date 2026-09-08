package com.caripc.protocol;

import android.os.Parcel;
import android.os.Parcelable;

public final class RegistrationToken implements Parcelable {
    public final String serviceId;
    public final String instanceId;
    public final long generation;
    public final String token;

    public RegistrationToken(String serviceId, String instanceId, long generation, String token) {
        this.serviceId = serviceId;
        this.instanceId = instanceId;
        this.generation = generation;
        this.token = token;
    }

    private RegistrationToken(Parcel in) {
        serviceId = in.readString();
        instanceId = in.readString();
        generation = in.readLong();
        token = in.readString();
    }

    @Override
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeString(serviceId);
        dest.writeString(instanceId);
        dest.writeLong(generation);
        dest.writeString(token);
    }

    @Override
    public int describeContents() {
        return 0;
    }

    public static final Creator<RegistrationToken> CREATOR = new Creator<RegistrationToken>() {
        @Override
        public RegistrationToken createFromParcel(Parcel in) {
            return new RegistrationToken(in);
        }

        @Override
        public RegistrationToken[] newArray(int size) {
            return new RegistrationToken[size];
        }
    };
}
