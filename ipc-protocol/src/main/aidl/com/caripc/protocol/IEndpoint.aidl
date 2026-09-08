package com.caripc.protocol;

import com.caripc.protocol.ClientHello;
import com.caripc.protocol.IClientCallback;

interface IEndpoint {
    oneway void openSession(in ClientHello hello, IClientCallback callback);
}
