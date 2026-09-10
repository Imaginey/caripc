package com.caripc.protocol;

import android.os.Parcel;
import android.os.Parcelable;

public final class SubscriptionEnvelope implements Parcelable {
    public static final int KIND_PROPERTY = 1;
    public static final int KIND_EVENT = 2;
    public static final int KIND_GAP = 3;
    public static final int KIND_ERROR = 4;

    public final String subscriptionId;
    public final String serviceInstanceId;
    public final int kind;
    public final String capabilityId;
    public final long deliverySeq;
    public final long revision;
    public final IpcPayload payload;
    public final int quality;
    public final long sourceElapsedMs;
    public final String causeOperationId;
    public final String snapshotId;
    public final int snapshotIndex;
    public final boolean isSnapshotEnd;
    public final ErrorEnvelope error;
    /** 仅 KIND_GAP 有效：缺口起始序号（该序号及之前的消息已不完整）。无缺口时为 -1。 */
    public final long gapFromSeq;

    public SubscriptionEnvelope(
            String subscriptionId,
            String serviceInstanceId,
            int kind,
            String capabilityId,
            long deliverySeq,
            long revision,
            IpcPayload payload,
            int quality,
            long sourceElapsedMs,
            String causeOperationId,
            String snapshotId,
            int snapshotIndex,
            boolean isSnapshotEnd,
            ErrorEnvelope error
    ) {
        this(subscriptionId, serviceInstanceId, kind, capabilityId, deliverySeq, revision, payload, quality,
                sourceElapsedMs, causeOperationId, snapshotId, snapshotIndex, isSnapshotEnd, error, -1L);
    }

    public SubscriptionEnvelope(
            String subscriptionId,
            String serviceInstanceId,
            int kind,
            String capabilityId,
            long deliverySeq,
            long revision,
            IpcPayload payload,
            int quality,
            long sourceElapsedMs,
            String causeOperationId,
            String snapshotId,
            int snapshotIndex,
            boolean isSnapshotEnd,
            ErrorEnvelope error,
            long gapFromSeq
    ) {
        this.subscriptionId = subscriptionId;
        this.serviceInstanceId = serviceInstanceId;
        this.kind = kind;
        this.capabilityId = capabilityId;
        this.deliverySeq = deliverySeq;
        this.revision = revision;
        this.payload = payload;
        this.quality = quality;
        this.sourceElapsedMs = sourceElapsedMs;
        this.causeOperationId = causeOperationId;
        this.snapshotId = snapshotId;
        this.snapshotIndex = snapshotIndex;
        this.isSnapshotEnd = isSnapshotEnd;
        this.error = error;
        this.gapFromSeq = gapFromSeq;
    }

    private SubscriptionEnvelope(Parcel in) {
        subscriptionId = in.readString();
        serviceInstanceId = in.readString();
        kind = in.readInt();
        capabilityId = in.readString();
        deliverySeq = in.readLong();
        revision = in.readLong();
        payload = in.readParcelable(IpcPayload.class.getClassLoader());
        quality = in.readInt();
        sourceElapsedMs = in.readLong();
        causeOperationId = in.readString();
        snapshotId = in.readString();
        snapshotIndex = in.readInt();
        isSnapshotEnd = in.readByte() != 0;
        error = in.readParcelable(ErrorEnvelope.class.getClassLoader());
        gapFromSeq = in.readLong();
    }

    @Override
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeString(subscriptionId);
        dest.writeString(serviceInstanceId);
        dest.writeInt(kind);
        dest.writeString(capabilityId);
        dest.writeLong(deliverySeq);
        dest.writeLong(revision);
        dest.writeParcelable(payload, flags);
        dest.writeInt(quality);
        dest.writeLong(sourceElapsedMs);
        dest.writeString(causeOperationId);
        dest.writeString(snapshotId);
        dest.writeInt(snapshotIndex);
        dest.writeByte((byte) (isSnapshotEnd ? 1 : 0));
        dest.writeParcelable(error, flags);
        dest.writeLong(gapFromSeq);
    }

    @Override
    public int describeContents() {
        return 0;
    }

    public static final Creator<SubscriptionEnvelope> CREATOR = new Creator<SubscriptionEnvelope>() {
        @Override
        public SubscriptionEnvelope createFromParcel(Parcel in) {
            return new SubscriptionEnvelope(in);
        }

        @Override
        public SubscriptionEnvelope[] newArray(int size) {
            return new SubscriptionEnvelope[size];
        }
    };
}
