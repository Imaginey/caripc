package com.caripc.protocol;

import android.os.Parcel;
import android.os.Parcelable;

public final class RequestEnvelope implements Parcelable {
    public static final int OP_GET = 1;
    public static final int OP_SET = 2;
    public static final int OP_CALL = 3;
    public static final int OP_SET_IF_VERSION = 4;
    public static final int OP_GET_OPERATION = 5;

    public final String requestId;
    public final int operation;
    public final String capabilityId;
    public final IpcPayload payload;
    public final long deadlineElapsedMs;
    public final String expectedWriteToken;
    public final String clientCorrelationId;

    public RequestEnvelope(
            String requestId,
            int operation,
            String capabilityId,
            IpcPayload payload,
            long deadlineElapsedMs,
            String expectedWriteToken,
            String clientCorrelationId
    ) {
        this.requestId = requestId;
        this.operation = operation;
        this.capabilityId = capabilityId;
        this.payload = payload;
        this.deadlineElapsedMs = deadlineElapsedMs;
        this.expectedWriteToken = expectedWriteToken;
        this.clientCorrelationId = clientCorrelationId;
    }

    private RequestEnvelope(Parcel in) {
        requestId = in.readString();
        operation = in.readInt();
        capabilityId = in.readString();
        payload = in.readParcelable(IpcPayload.class.getClassLoader());
        deadlineElapsedMs = in.readLong();
        expectedWriteToken = in.readString();
        clientCorrelationId = in.readString();
    }

    @Override
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeString(requestId);
        dest.writeInt(operation);
        dest.writeString(capabilityId);
        dest.writeParcelable(payload, flags);
        dest.writeLong(deadlineElapsedMs);
        dest.writeString(expectedWriteToken);
        dest.writeString(clientCorrelationId);
    }

    @Override
    public int describeContents() {
        return 0;
    }

    public static final Creator<RequestEnvelope> CREATOR = new Creator<RequestEnvelope>() {
        @Override
        public RequestEnvelope createFromParcel(Parcel in) {
            return new RequestEnvelope(in);
        }

        @Override
        public RequestEnvelope[] newArray(int size) {
            return new RequestEnvelope[size];
        }
    };
}
