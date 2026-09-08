package com.caripc.protocol;

import com.caripc.protocol.RequestEnvelope;
import com.caripc.protocol.SubscribeRequest;

interface ISession {
    oneway void request(in RequestEnvelope request);
    oneway void subscribe(in SubscribeRequest request);
    oneway void unsubscribe(String subscriptionId);
    oneway void acknowledge(String subscriptionId, long deliverySeq);
    oneway void cancel(String requestId);
    oneway void close();
}
