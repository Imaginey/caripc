package com.caripc.sdk;

import android.content.Context;
import android.os.Bundle;

import com.caripc.contract.CancelHandle;
import com.caripc.contract.CommandKey;
import com.caripc.contract.IpcCallback;
import com.caripc.contract.IpcError;
import com.caripc.contract.PropertyKey;
import com.caripc.contract.PropertySnapshot;
import com.caripc.contract.ServiceSchema;
import com.caripc.contract.SetReceipt;

import org.junit.Assert;
import org.junit.Test;

import java.util.Collections;

/**
 * 纯 Java 应用调用 CarIpc 中间件 API 兼容性验证测试
 */
public class JavaApiCompatibilityTest {

    @Test
    public void testJavaContractAndApiCompilation() {
        // 1. 纯 Java 定义契约
        PropertyKey<Float> tempKey = PropertyKey.createFloat("temp.target", true, true, true, 16.0f, 32.0f, "C", null);
        PropertyKey<Bundle> bundleKey = PropertyKey.createBundle("climate.bundle", true, true, true);
        CommandKey<String, String> cmdKey = CommandKey.stringToString("cmd.self_test");

        ServiceSchema schema = ServiceSchema.builder("com.sample.service", 1, 0)
                .addProperty(tempKey)
                .addProperty(bundleKey)
                .addCommand(cmdKey)
                .build();

        Assert.assertEquals("com.sample.service", schema.getContractId());

        // 2. 纯 Java 验证 Handler 接口函数式调用
        OnSetHandler setHandler = (keyId, value, callerUid) -> SetReceipt.accepted();
        OnCallHandler callHandler = (commandId, param, callerUid) -> "RESULT_OK";

        SetReceipt receipt = setHandler.onSet("temp.target", 25.0f, 1001);
        Assert.assertNotNull(receipt);
        Object callResult = callHandler.onCall("cmd.self_test", null, 1001);
        Assert.assertEquals("RESULT_OK", callResult);

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
}