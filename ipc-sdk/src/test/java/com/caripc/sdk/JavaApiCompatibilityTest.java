package com.caripc.sdk;

import android.content.ComponentName;
import android.content.Context;
import android.os.Bundle;

import com.caripc.contract.CancelHandle;
import com.caripc.contract.CapabilityKey;
import com.caripc.contract.CommandKey;
import com.caripc.contract.ErrorCode;
import com.caripc.contract.IpcCallback;
import com.caripc.contract.IpcError;
import com.caripc.contract.PropertyKey;
import com.caripc.contract.PropertySnapshot;
import com.caripc.contract.PropertyUpdate;
import com.caripc.contract.ServiceSchema;
import com.caripc.contract.SetReceipt;
import com.caripc.contract.SubscribeOptions;
import com.caripc.runtime.PermissionPolicy;

import org.junit.Assert;
import org.junit.Test;

import java.util.Collections;

/**
 * 纯 Java 应用调用 CarIpc 中间件 API 兼容性验证测试。
 *
 * 覆盖两条链路：
 * 1. 契约定义 + Handler + 异步回调（可执行）；
 * 2. 发布/消费服务的完整签名（编译期验证，运行需 Android Context/Binder，用 Assume 跳过执行）。
 */
public class JavaApiCompatibilityTest {

    @Test
    public void testJavaContractAndApiCompilation() {
        // 1. 纯 Java 定义契约
        PropertyKey<Float> tempKey = PropertyKey.createFloat("temp.target", true, true, true, 16.0f, 32.0f, "C", null);
        PropertyKey<Bundle> bundleKey = PropertyKey.createBundle("climate.bundle", true, true, true);
        CommandKey<String, String> cmdKey = CommandKey.stringToString("cmd.self_test");
        CommandKey<String, kotlin.Unit> unitCmdKey = CommandKey.stringToUnit("cmd.reset");

        ServiceSchema schema = ServiceSchema.builder("com.sample.service", 1, 0)
                .addProperty(tempKey)
                .addProperty(bundleKey)
                .addCommand(cmdKey)
                .addCommand(unitCmdKey)
                .build();

        Assert.assertEquals("com.sample.service", schema.getContractId());

        // 2. 纯 Java 验证 Handler 接口函数式调用
        OnSetHandler setHandler = (keyId, value, callerUid) -> SetReceipt.accepted();
        OnCallHandler callHandler = (commandId, param, callerUid) -> "RESULT_OK";

        SetReceipt receipt = setHandler.onSet("temp.target", 25.0f, 1001);
        Assert.assertNotNull(receipt);
        Object callResult = callHandler.onCall("cmd.self_test", null, 1001);
        Assert.assertEquals("RESULT_OK", callResult);

        // 拒绝请求：Java 侧两参构造 IpcError（@JvmOverloads）
        SetReceipt rejected = SetReceipt.rejected(new IpcError(ErrorCode.INVALID_ARGUMENT, "value out of range"));
        Assert.assertNotNull(rejected.getError());
        Assert.assertEquals(ErrorCode.INVALID_ARGUMENT, rejected.getError().getCode());

        // 3. 纯 Java 异步回调接口 IpcCallback 验证
        IpcCallback<String> callback = new IpcCallback<String>() {
            @Override
            public void onSuccess(String value) {
                Assert.assertEquals("HELLO", value);
            }

            @Override
            public void onError(IpcError error) {
                Assert.fail("Should not fail");
            }
        };

        callback.onSuccess("HELLO");
    }

    /**
     * 只做编译期签名验证（javac 会对本方法内所有调用做类型检查）。
     * 实际执行需要 Android Context 与 Binder 运行环境，这里用 Assume 跳过。
     */
    @Test
    public void testJavaPublisherAndConsumerApiSignaturesCompile() {
        org.junit.Assume.assumeTrue("compile-time Java API signature check (not executed at runtime)", false);
        publisherUsage(null);
        consumerUsage(null);
    }

    private void publisherUsage(Context context) {
        CarIpc ipc = CarIpc.create(context);

        ServicePublisher publisher = ipc.publishService(
                "com.sample.service",
                ServiceSchema.builder("com.sample.contract", 1, 0)
                        .addProperty(PropertyKey.createFloat("temp", true, true, true, 16.0f, 32.0f, "C", null))
                        .addCommand(CommandKey.stringToString("cmd"))
                        .build(),
                (OnSetHandler) (keyId, value, callerUid) -> SetReceipt.accepted(),
                (OnCallHandler) (commandId, param, callerUid) -> "OK"
        );

        publisher.update(PropertyKey.createFloat("temp"), 24.0f);
        publisher.emit(com.caripc.contract.EventKey.createString("evt"), "payload");
        publisher.close();

        // 自定义配置：@JvmOverloads 支持按位置部分传参
        CarIpc configured = CarIpc.create(
                context,
                new CarIpcConfig(
                        new ComponentName("com.caripc.registry", "com.caripc.registry.RegistryService"),
                        5000L
                )
        );
        configured.close();

        // ACL 配置（Java 侧构造 ServiceAcl，空集用 Collections.emptySet()）
        PermissionPolicy.ServiceAcl acl = new PermissionPolicy.ServiceAcl(
                "com.sample.service",
                Collections.singleton("com.company.server"),
                Collections.emptySet(),
                Collections.emptySet(),
                null
        );
        Assert.assertNotNull(acl);
    }

    private void consumerUsage(Context context) {
        CarIpc ipc = CarIpc.create(context);
        RemoteService client = ipc.connect("com.sample.service");

        client.awaitReady(3000, new IpcCallback<kotlin.Unit>() {
            @Override
            public void onSuccess(kotlin.Unit value) {
            }

            @Override
            public void onError(IpcError error) {
            }
        });

        PropertyKey<Float> temp = PropertyKey.createFloat("temp");
        client.get(temp, new IpcCallback<PropertySnapshot<Float>>() {
            @Override
            public void onSuccess(PropertySnapshot<Float> snapshot) {
            }

            @Override
            public void onError(IpcError error) {
            }
        });

        client.set(temp, 25.0f, new IpcCallback<SetReceipt>() {
            @Override
            public void onSuccess(SetReceipt receipt) {
            }

            @Override
            public void onError(IpcError error) {
            }
        });

        client.setIfVersion(temp, 25.0f, "write-token", new IpcCallback<SetReceipt>() {
            @Override
            public void onSuccess(SetReceipt receipt) {
            }

            @Override
            public void onError(IpcError error) {
            }
        });

        CommandKey<String, String> cmd = CommandKey.stringToString("cmd");
        client.call(cmd, "arg", new IpcCallback<String>() {
            @Override
            public void onSuccess(String result) {
            }

            @Override
            public void onError(IpcError error) {
            }
        });

        CancelHandle subscription = client.subscribe(
                Collections.<CapabilityKey>singletonList(temp),
                new SubscribeOptions(true, 16),
                message -> {
                    if (message.getPayload() instanceof PropertyUpdate) {
                        PropertyUpdate<?> update = (PropertyUpdate<?>) message.getPayload();
                    }
                }
        );
        subscription.cancel();

        client.close();
        ipc.close();
    }
}
