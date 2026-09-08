package com.caripc.protocol;

import com.caripc.protocol.ServiceDescriptor;
import com.caripc.protocol.RegistrationToken;
import com.caripc.protocol.IEndpoint;
import com.caripc.protocol.IRegistryCallback;

interface IRegistry {
    oneway void publish(in ServiceDescriptor descriptor, IEndpoint endpoint, IRegistryCallback callback);
    oneway void unpublish(in RegistrationToken token);
    oneway void resolveAndWatch(String serviceId, long watchId, IRegistryCallback callback);
    oneway void unwatch(long watchId, IRegistryCallback callback);
}
