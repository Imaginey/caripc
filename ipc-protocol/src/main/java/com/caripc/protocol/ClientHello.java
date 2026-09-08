package com.caripc.protocol;

import android.os.Parcel;
import android.os.Parcelable;

public final class ClientHello implements Parcelable {
    public final int transportMajor;
    public final int transportMinor;
    public final int contractMajor;
    public final int contractMinor;
    public final String clientInstanceId;
    public final String openRequestId;

    public ClientHello(int transportMajor, int transportMinor, int contractMajor, int contractMinor, String clientInstanceId, String openRequestId) {
        this.transportMajor = transportMajor;
        this.transportMinor = transportMinor;
        this.contractMajor = contractMajor;
        this.contractMinor = contractMinor;
        this.clientInstanceId = clientInstanceId;
        this.openRequestId = openRequestId;
    }

    private ClientHello(Parcel in) {
        transportMajor = in.readInt();
        transportMinor = in.readInt();
        contractMajor = in.readInt();
        contractMinor = in.readInt();
        clientInstanceId = in.readString();
        openRequestId = in.readString();
    }

    @Override
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeInt(transportMajor);
        dest.writeInt(transportMinor);
        dest.writeInt(contractMajor);
        dest.writeInt(contractMinor);
        dest.writeString(clientInstanceId);
        dest.writeString(openRequestId);
    }

    @Override
    public int describeContents() {
        return 0;
    }

    public static final Creator<ClientHello> CREATOR = new Creator<ClientHello>() {
        @Override
        public ClientHello createFromParcel(Parcel in) {
            return new ClientHello(in);
        }

        @Override
        public ClientHello[] newArray(int size) {
            return new ClientHello[size];
        }
    };
}
