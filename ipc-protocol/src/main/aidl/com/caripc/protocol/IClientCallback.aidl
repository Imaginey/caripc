package com.caripc.protocol;

import com.caripc.protocol.ServerHello;
import com.caripc.protocol.ErrorEnvelope;
import com.caripc.protocol.ResponseEnvelope;
import com.caripc.protocol.SubscriptionEnvelope;
import com.caripc.protocol.ISession;

interface IClientCallback {
    oneway void onSessionOpened(in ServerHello hello, ISession session);
    oneway void onSessionRejected(in ErrorEnvelope error);
    oneway void onResult(in ResponseEnvelope response);
    oneway void onSubscriptionMessage(in SubscriptionEnvelope message);
}
