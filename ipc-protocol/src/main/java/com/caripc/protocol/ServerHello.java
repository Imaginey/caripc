package com.caripc.protocol;

import android.os.Parcel;
import android.os.Parcelable;
import java.util.ArrayList;
import java.util.List;

public final class ServerHello implements Parcelable {
    public final int transportMajor;
    public final int transportMinor;
    public final int contractMajor;
    public final int contractMinor;
    public final String serviceInstanceId;
    public final String sessionId;
    public final List<String> capabilities;
    public final int maxPayloadBytes;
    public final int windowSize;

    public ServerHello(
            int transportMajor,
            int transportMinor,
            int contractMajor,
            int contractMinor,
            String serviceInstanceId,
            String sessionId,
            List<String> capabilities,
            int maxPayloadBytes,
            int windowSize
    ) {
        this.transportMajor = transportMajor;
        this.transportMinor = transportMinor;
        this.contractMajor = contractMajor;
        this.contractMinor = contractMinor;
        this.serviceInstanceId = serviceInstanceId;
        this.sessionId = sessionId;
        this.capabilities = capabilities != null ? capabilities : new ArrayList<>();
        this.maxPayloadBytes = maxPayloadBytes;
        this.windowSize = windowSize;
    }

    private ServerHello(Parcel in) {
        transportMajor = in.readInt();
        transportMinor = in.readInt();
        contractMajor = in.readInt();
        contractMinor = in.readInt();
        serviceInstanceId = in.readString();
        sessionId = in.readString();
        capabilities = in.createStringArrayList();
        maxPayloadBytes = in.readInt();
        windowSize = in.readInt();
    }

    @Override
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeInt(transportMajor);
        dest.writeInt(transportMinor);
        dest.writeInt(contractMajor);
        dest.writeInt(contractMinor);
        dest.writeString(serviceInstanceId);
        dest.writeString(sessionId);
        dest.writeStringList(capabilities);
        dest.writeInt(maxPayloadBytes);
        dest.writeInt(windowSize);
    }

    @Override
    public int describeContents() {
        return 0;
    }

    public static final Creator<ServerHello> CREATOR = new Creator<ServerHello>() {
        @Override
        public ServerHello createFromParcel(Parcel in) {
            return new ServerHello(in);
        }

        @Override
        public ServerHello[] newArray(int size) {
            return new ServerHello[size];
        }
    };
}
