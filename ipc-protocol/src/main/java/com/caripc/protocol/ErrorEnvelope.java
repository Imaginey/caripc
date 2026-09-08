package com.caripc.protocol;

import android.os.Parcel;
import android.os.Parcelable;
import com.caripc.contract.CompletionState;
import com.caripc.contract.ErrorCode;
import com.caripc.contract.IpcError;

public final class ErrorEnvelope implements Parcelable {
    public final int errorCode;
    public final String message;
    public final String requestId;
    public final int completionState;
    public final String currentWriteToken;

    public ErrorEnvelope(int errorCode, String message, String requestId, int completionState, String currentWriteToken) {
        this.errorCode = errorCode;
        this.message = message != null ? message : "";
        this.requestId = requestId;
        this.completionState = completionState;
        this.currentWriteToken = currentWriteToken;
    }

    public ErrorEnvelope(IpcError error) {
        this(
            error.getCode().getCode(),
            error.getMessage(),
            error.getRequestId(),
            error.getCompletionState().ordinal(),
            error.getCurrentWriteToken()
        );
    }

    private ErrorEnvelope(Parcel in) {
        errorCode = in.readInt();
        message = in.readString();
        requestId = in.readString();
        completionState = in.readInt();
        currentWriteToken = in.readString();
    }

    public IpcError toIpcError() {
        CompletionState[] states = CompletionState.values();
        CompletionState state = (completionState >= 0 && completionState < states.length) ? states[completionState] : CompletionState.UNKNOWN;
        return new IpcError(
            ErrorCode.fromCode(errorCode),
            message,
            requestId,
            state,
            currentWriteToken
        );
    }

    @Override
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeInt(errorCode);
        dest.writeString(message);
        dest.writeString(requestId);
        dest.writeInt(completionState);
        dest.writeString(currentWriteToken);
    }

    @Override
    public int describeContents() {
        return 0;
    }

    public static final Creator<ErrorEnvelope> CREATOR = new Creator<ErrorEnvelope>() {
        @Override
        public ErrorEnvelope createFromParcel(Parcel in) {
            return new ErrorEnvelope(in);
        }

        @Override
        public ErrorEnvelope[] newArray(int size) {
            return new ErrorEnvelope[size];
        }
    };
}
