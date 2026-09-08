package com.caripc.protocol;

import com.caripc.protocol.RegistrationToken;
import com.caripc.protocol.ServiceDescriptor;
import com.caripc.protocol.ErrorEnvelope;
import com.caripc.protocol.IEndpoint;

interface IRegistryCallback {
    oneway void onPublished(in RegistrationToken token);
    oneway void onPublishFailed(String serviceId, in ErrorEnvelope error);
    oneway void onSnapshot(String serviceId, long watchId, in ServiceDescriptor descriptor, IEndpoint endpoint);
    oneway void onServiceUnavailable(String serviceId, long watchId);
    oneway void onServiceChanged(String serviceId, long watchId, in ServiceDescriptor descriptor, IEndpoint endpoint);
    oneway void onError(long watchId, in ErrorEnvelope error);
}
