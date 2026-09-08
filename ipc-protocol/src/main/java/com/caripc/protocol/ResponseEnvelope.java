package com.caripc.protocol;

import android.os.Parcel;
import android.os.Parcelable;

public final class ResponseEnvelope implements Parcelable {
    public static final int STATUS_OK = 0;
    public static final int STATUS_ERROR = 1;
    public static final int STATUS_ACCEPTED = 2;
    public static final int STATUS_APPLIED = 3;

    public final String requestId;
    public final String serviceInstanceId;
    public final int status;
    public final IpcPayload payload;
    public final ErrorEnvelope error;
    public final String operationId;
    public final String writeToken;
    public final int quality;
    public final long revision;
    public final long sourceElapsedMs;

    public ResponseEnvelope(
            String requestId,
            String serviceInstanceId,
            int status,
            IpcPayload payload,
            ErrorEnvelope error,
            String operationId,
            String writeToken,
            int quality,
            long revision,
            long sourceElapsedMs
    ) {
        this.requestId = requestId;
        this.serviceInstanceId = serviceInstanceId;
        this.status = status;
        this.payload = payload;
        this.error = error;
        this.operationId = operationId;
        this.writeToken = writeToken;
        this.quality = quality;
        this.revision = revision;
        this.sourceElapsedMs = sourceElapsedMs;
    }

    private ResponseEnvelope(Parcel in) {
        requestId = in.readString();
        serviceInstanceId = in.readString();
        status = in.readInt();
        payload = in.readParcelable(IpcPayload.class.getClassLoader());
        error = in.readParcelable(ErrorEnvelope.class.getClassLoader());
        operationId = in.readString();
        writeToken = in.readString();
        quality = in.readInt();
        revision = in.readLong();
        sourceElapsedMs = in.readLong();
    }

    @Override
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeString(requestId);
        dest.writeString(serviceInstanceId);
        dest.writeInt(status);
        dest.writeParcelable(payload, flags);
        dest.writeParcelable(error, flags);
        dest.writeString(operationId);
        dest.writeString(writeToken);
        dest.writeInt(quality);
        dest.writeLong(revision);
        dest.writeLong(sourceElapsedMs);
    }

    @Override
    public int describeContents() {
        return 0;
    }

    public static final Creator<ResponseEnvelope> CREATOR = new Creator<ResponseEnvelope>() {
        @Override
        public ResponseEnvelope createFromParcel(Parcel in) {
            return new ResponseEnvelope(in);
        }

        @Override
        public ResponseEnvelope[] newArray(int size) {
            return new ResponseEnvelope[size];
        }
    };
}
