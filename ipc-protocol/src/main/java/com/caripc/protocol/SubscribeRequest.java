package com.caripc.protocol;

import android.os.Parcel;
import android.os.Parcelable;
import java.util.ArrayList;
import java.util.List;

public final class SubscribeRequest implements Parcelable {
    public final String subscriptionId;
    public final List<String> keys;
    public final boolean replayLatest;
    public final int windowSize;

    public SubscribeRequest(String subscriptionId, List<String> keys, boolean replayLatest, int windowSize) {
        this.subscriptionId = subscriptionId;
        this.keys = keys != null ? keys : new ArrayList<>();
        this.replayLatest = replayLatest;
        this.windowSize = windowSize;
    }

    private SubscribeRequest(Parcel in) {
        subscriptionId = in.readString();
        keys = in.createStringArrayList();
        replayLatest = in.readByte() != 0;
        windowSize = in.readInt();
    }

    @Override
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeString(subscriptionId);
        dest.writeStringList(keys);
        dest.writeByte((byte) (replayLatest ? 1 : 0));
        dest.writeInt(windowSize);
    }

    @Override
    public int describeContents() {
        return 0;
    }

    public static final Creator<SubscribeRequest> CREATOR = new Creator<SubscribeRequest>() {
        @Override
        public SubscribeRequest createFromParcel(Parcel in) {
            return new SubscribeRequest(in);
        }

        @Override
        public SubscribeRequest[] newArray(int size) {
            return new SubscribeRequest[size];
        }
    };
}
