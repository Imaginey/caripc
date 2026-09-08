package com.caripc.protocol;

import android.os.Parcel;
import android.os.Parcelable;
import java.util.ArrayList;
import java.util.List;

public final class ServiceDescriptor implements Parcelable {
    public final String serviceId;
    public final String contractId;
    public final int contractMajor;
    public final int contractMinor;
    public final int transportMajor;
    public final int transportMinor;
    public final String instanceId;
    public final long registryGeneration;
    public final int ownerUid;
    public final int userId;
    public final List<String> capabilities;

    public ServiceDescriptor(
            String serviceId,
            String contractId,
            int contractMajor,
            int contractMinor,
            int transportMajor,
            int transportMinor,
            String instanceId,
            long registryGeneration,
            int ownerUid,
            int userId,
            List<String> capabilities
    ) {
        this.serviceId = serviceId;
        this.contractId = contractId;
        this.contractMajor = contractMajor;
        this.contractMinor = contractMinor;
        this.transportMajor = transportMajor;
        this.transportMinor = transportMinor;
        this.instanceId = instanceId;
        this.registryGeneration = registryGeneration;
        this.ownerUid = ownerUid;
        this.userId = userId;
        this.capabilities = capabilities != null ? capabilities : new ArrayList<>();
    }

    private ServiceDescriptor(Parcel in) {
        serviceId = in.readString();
        contractId = in.readString();
        contractMajor = in.readInt();
        contractMinor = in.readInt();
        transportMajor = in.readInt();
        transportMinor = in.readInt();
        instanceId = in.readString();
        registryGeneration = in.readLong();
        ownerUid = in.readInt();
        userId = in.readInt();
        capabilities = in.createStringArrayList();
    }

    public ServiceDescriptor withGenerationAndOwner(long generation, int ownerUid, int userId) {
        return new ServiceDescriptor(
                this.serviceId,
                this.contractId,
                this.contractMajor,
                this.contractMinor,
                this.transportMajor,
                this.transportMinor,
                this.instanceId,
                generation,
                ownerUid,
                userId,
                this.capabilities
        );
    }

    @Override
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeString(serviceId);
        dest.writeString(contractId);
        dest.writeInt(contractMajor);
        dest.writeInt(contractMinor);
        dest.writeInt(transportMajor);
        dest.writeInt(transportMinor);
        dest.writeString(instanceId);
        dest.writeLong(registryGeneration);
        dest.writeInt(ownerUid);
        dest.writeInt(userId);
        dest.writeStringList(capabilities);
    }

    @Override
    public int describeContents() {
        return 0;
    }

    public static final Creator<ServiceDescriptor> CREATOR = new Creator<ServiceDescriptor>() {
        @Override
        public ServiceDescriptor createFromParcel(Parcel in) {
            return new ServiceDescriptor(in);
        }

        @Override
        public ServiceDescriptor[] newArray(int size) {
            return new ServiceDescriptor[size];
        }
    };
}
