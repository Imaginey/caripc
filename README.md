# CarIpc: Android 统一跨进程通信中间件使用指南

本项目是基于《Android 统一 IPC 中间件架构与实现规格》v1.3 落地的工业级车载/系统级统一跨进程通信中间件。

业务应用通过 SDK 可直接完成服务发布、按 `serviceId` 发现与直连、属性读写（`get` / `set` / `setIfVersion`）、命令调用（`call`）与多 Key 状态订阅（`subscribe` / Flow），**业务层无需手写任何 AIDL 文件**。

---

## 目录
- [一、 模块架构矩阵](#一-模块架构矩阵)
- [二、 构建与编译指南](#二-构建与编译指南)
- [三、 注册中心（Registry App）深度解析](#三-注册中心registry-app深度解析)
- [四、 快速上手与功能使用详解](#四-快速上手与功能使用详解)
  - [1. 定义强类型契约 (Contract)](#1-定义强类型契约-contract)
  - [2. 服务端：发布服务 (Publisher)](#2-服务端发布服务-publisher)
  - [3. 客户端：连接、调用与订阅 (Client)](#3-客户端连接调用与订阅-client)
  - [4. 进阶：Kotlin 协程与 Flow DSL 响应式编程](#4-进阶kotlin-协程与-flow-dsl-响应式编程)
  - [5. 单进程双角色（Dual-Role）](#5-单进程双角色dual-role)
  - [6. 纯 Java 应用接入与互操作指南](#6-纯-java-应用接入与互操作指南)
- [五、 关键机制与设计规范](#五-关键机制与设计规范)
- [六、 错误码与排查指南](#六-错误码与排查指南)

---

## 一、 模块架构矩阵

工程采用高内聚、低耦合的分层架构，共分为 10 个子模块：

| 模块名称 | 产物类型 | 模块路径 | 职责与核心说明 |
| :--- | :--- | :--- | :--- |
| **`:ipc-contract-api`** | Java/Kotlin Jar | `ipc-contract-api` | **契约定义层**：提供 `PropertyKey`, `CommandKey`, `EventKey`, `ServiceSchema`, `Quality`, `NotificationPolicy`, `IpcError`。包含运行期类型、范围（`min/max`）、`NaN/Infinity`、枚举强校验。 |
| **`:ipc-protocol`** | Android AAR | `ipc-protocol` | **线协议层**：包含底层通用通用 AIDL（`IRegistry`, `IEndpoint`, `ISession`, `IClientCallback`）与高效序列化 Parcelable 协议信封包（`RequestEnvelope`, `ResponseEnvelope`, `SubscriptionEnvelope`, `IpcPayload` 等）。 |
| **`:ipc-runtime`** | Android AAR | `ipc-runtime` | **运行时内核**：承载 `StateStore`（权威状态/死区限频过滤）、`DeliveryQueue`（有界队列/同Key状态合并）、`OutboundExecutor`（锁内不调远端Binder）、`RegistryStore`（generation安全清理）、`PermissionPolicy`（真实UID安全鉴权）等。 |
| **`:ipc-sdk`** | Android AAR | `ipc-sdk` | **业务 SDK**：面向业务开发者的统一 API 入口（`CarIpc`, `RemoteService`, `ServicePublisher`）。 |
| **`:ipc-sdk-ktx`** | Android AAR | `ipc-sdk-ktx` | **Kotlin 扩展**：提供挂起函数 `awaitReady`、`get`、`set`、`call`，Flow 数据流 `observe`，以及声明式构建器 `publishService { }`。 |
| **`:ipc-registry-app`** | Android APK | `ipc-registry-app` | **独立注册中心**：包名 `com.caripc.registry`，常驻后台 Service，仅处理寻址发现，不转发生意数据。 |
| **`:climate-contract`** | Java/Kotlin Jar | `climate-contract` | **业务契约示例**：车载空调系统业务契约。 |
| **`:display-contract`** | Java/Kotlin Jar | `display-contract` | **业务契约示例**：中控屏展示系统业务契约。 |
| **`:sample-server`** | Android APK | `sample-server` | **示例应用 B**：发布空调服务，同时消费展示服务（双角色）。 |
| **`:sample-client`** | Android APK | `sample-client` | **示例应用 A**：发布展示服务，同时连接、多 Key 订阅并控制空调服务（双角色）。 |

---

## 二、 构建与编译指南

### 1. 编译环境要求
- **JDK**：11 (建议 OpenJDK 11.0.1 或更高)
- **Android SDK**：API 33 (`compileSdk = 33`, `buildTools = 35.0.0`)
- **Gradle**：7.3.3 + AGP 7.2.2 + Kotlin 1.7.10

### 2. 常用编译命令

在工程根目录运行：

```powershell
# 1. 编译整包（构建所有 3 个 APK 与全部 AAR/Jar）
.\gradlew.bat assembleDebug

# 2. 单独编译指定产物
.\gradlew.bat :ipc-registry-app:assembleDebug   # 注册中心 APK
.\gradlew.bat :sample-server:assembleDebug      # 服务端 B APK
.\gradlew.bat :sample-client:assembleDebug      # 客户端 A APK
.\gradlew.bat :ipc-sdk:assembleDebug            # SDK AAR
.\gradlew.bat :ipc-sdk-ktx:assembleDebug        # SDK KTX AAR

# 3. 运行全量单元测试（覆盖全套端到端与运行时测试）
.\gradlew.bat test
```

### 3. 构建产物输出路径
- 注册中心 APK：`ipc-registry-app/build/outputs/apk/debug/ipc-registry-app-debug.apk`
- 示例服务端 APK：`sample-server/build/outputs/apk/debug/sample-server-debug.apk`
- 示例客户端 APK：`sample-client/build/outputs/apk/debug/sample-client-debug.apk`
- SDK AAR：`ipc-sdk/build/outputs/aar/ipc-sdk-debug.aar`
- KTX AAR：`ipc-sdk-ktx/build/outputs/aar/ipc-sdk-ktx-debug.aar`

---

## 三、 注册中心（Registry App）深度解析

### 1. 注册中心是一个 Service 吗？
**是的，注册中心 APK 的核心就是一个标准的导出 Service（`RegistryService`）。**
- 清单文件声明：
  ```xml
  <service
      android:name=".RegistryService"
      android:exported="true"
      android:permission="com.caripc.permission.ACCESS_REGISTRY">
      <intent-filter>
          <action android:name="com.caripc.registry.ACTION_REGISTRY" />
      </intent-filter>
  </service>
  ```
- 它没有 UI Activity，作为系统基础服务应用在后台运行。

### 2. 核心架构与高可用设计
1. **只做寻址，绝不中转业务数据**：
   - 注册中心只维护 `serviceId -> IEndpoint` 的映射；
   - 业务进程在通过注册中心发现对端后，双方通过底层通用 AIDL 建立点对点（P2P）直接 Binder 链接。
2. **中心崩溃不影响直连 Session**：
   - 即便注册中心挂掉或重启，业务进程 A 与 B 之间已建立的数据通路和订阅通知**完全不受影响**；
   - 注册中心重启后，SDK 内部的 `RegistryConnector` 会通过单任务指数退避重连机制重新发布和监听。
3. **防止服务抢注与幽灵覆盖**：
   - 注册中心为每次发布生成全局唯一的 `RegistrationToken`（附带递增的 `generation`）；
   - 只有持有有效 Token 的发布者才能取消发布；监听端点 Binder 死亡，确保进程崩溃时安全清理。

### 3. 如何查看当前已发布的服务？（`service list` vs `dumpsys`）

> **为什么不能直接用 `adb shell service list`？**
> `service list` 是 Android 系统底层的 `servicemanager` 命令，仅用于列出 `system_server` 注册的系统核心原生服务（如 `activity`, `package`, `audio` 等）。应用层/车机微服务没有也不应当有权限向底层系统 `servicemanager` 注册。
> 
> **推荐查看方式：** 使用 Android 标准的服务诊断命令 `dumpsys` 查看我们的注册中心：

```powershell
# 1. 安装注册中心（若未安装）
adb install -r ipc-registry-app/build/outputs/apk/debug/ipc-registry-app-debug.apk

# 2. 核心命令：直接查看当前所有已发布服务列表与监听者
adb shell dumpsys activity service com.caripc.registry/.RegistryService
```

**命令输出效果示例**：
```text
=== CarIpc Registry Diagnostics ===
CarIpc Active Registered Services (2):
  [1] ServiceId: com.company.vehicle.climate
      InstanceId   : 8a4ef210-6c9f-4315-992a-302efd189110
      Owner UID    : 10123
      Generation   : 101
      Version      : v1.0
      Capabilities : [target_temperature, cabin_temperature, fan_speed, start_self_test, self_test_finished]
  [2] ServiceId: com.company.vehicle.display
      InstanceId   : b219df01-098a-4ef1-8871-291efd812301
      Owner UID    : 10124
      Generation   : 102
      Version      : v1.0
      Capabilities : [current_title, theme_mode, trigger_alert]

CarIpc Active Watchers (1):
  [1] WatchId: 1773012983 -> ServiceId: com.company.vehicle.climate
```

---

## 四、 快速上手与功能使用详解

### 1. 定义强类型契约 (Contract)

在独立的契约模块（如 `:climate-contract`）中定义接口规范，不写 AIDL：

```kotlin
package com.caripc.sample.climate

import com.caripc.contract.*

object ClimateContract {
    const val SERVICE_ID = "com.company.vehicle.climate"

    // 属性：目标温度，支持数值边界与死区限频过滤
    val TARGET_TEMPERATURE = PropertyKey.createFloat(
        id = "target_temperature",
        min = 16.0f,
        max = 32.0f,
        notificationPolicy = NotificationPolicy(
            minDelta = 0.5,                  // 死区：数值变动 >= 0.5 才下发通知，防止高频震荡
            minNotificationIntervalMs = 100L // 限频：通知间隔不低于 100ms
        )
    )

    // 属性：风速，整型范围 0 ~ 7
    val FAN_SPEED = PropertyKey.createInt("fan_speed", min = 0, max = 7)

    // 原生支持复杂容器：Bundle 属性（如整车/分区状态集）
    val CLIMATE_SETTINGS_BUNDLE = PropertyKey.createBundle<android.os.Bundle>("climate_settings_bundle", readable = true, writable = true)

    // 命令：自检命令，入参为 String，返回值为 String
    val START_SELF_TEST = CommandKey.stringToString("start_self_test")

    // 命令：支持入参和返回值均为 Bundle 的高阶 RPC
    val EXECUTE_PROFILE_CMD = CommandKey.bundleToBundle<android.os.Bundle, android.os.Bundle>("execute_profile_cmd")

    // 事件：自检完成事件
    val SELF_TEST_FINISHED = EventKey.createString("self_test_finished")

    // 聚合定义 ServiceSchema
    val schema = ServiceSchema.builder(SERVICE_ID, majorVersion = 1, minorVersion = 0)
        .addProperty(TARGET_TEMPERATURE)
        .addProperty(FAN_SPEED)
        .addProperty(CLIMATE_SETTINGS_BUNDLE)
        .addCommand(START_SELF_TEST)
        .addCommand(EXECUTE_PROFILE_CMD)
        .addEvent(SELF_TEST_FINISHED)
        .build()
}
```

---

### 2. 服务端：发布服务 (Publisher)

在服务端应用中，通过 `CarIpc` 创建发布者：

```kotlin
import com.caripc.contract.SetReceipt
import com.caripc.sdk.CarIpc
import com.caripc.sdk.ktx.*

val ipc = CarIpc.create(context)

// 使用 DSL 声明式发布服务
val publisher = ipc.publishService(
    serviceId = ClimateContract.SERVICE_ID,
    schema = ClimateContract.schema
) {
    // 1. 处理客户端属性修改请求（set）
    onSet(ClimateContract.TARGET_TEMPERATURE) { targetTemp, callerUid ->
        println("收到来自 UID $callerUid 的温度修改请求: $targetTemp")

        // 驱动底层硬件修改，修改完成后更新权威状态（会自动广播给所有订阅者）
        publisher.update(ClimateContract.TARGET_TEMPERATURE, targetTemp)

        // 返回接受回执（SetReceipt.accepted / SetReceipt.applied / SetReceipt.rejected）
        SetReceipt.accepted()
    }

    // 2. 处理客户端命令调用（call）
    onCall(ClimateContract.START_SELF_TEST) { param, callerUid ->
        println("执行自检操作，参数: $param")
        
        // 也可以主动触发单向事件广播
        publisher.emit(ClimateContract.SELF_TEST_FINISHED, "DIAG_OK")

        // 返回同步响应结果
        "RESULT_STARTED_SUCCESS"
    }
}

// 初始化状态初始值（客户端订阅后，将自动收到此初始快照）
publisher.update(ClimateContract.TARGET_TEMPERATURE, 24.0f)
publisher.update(ClimateContract.FAN_SPEED, 2)
```

---

### 3. 客户端：连接、调用与订阅 (Client)

#### (1) 连接到对端服务
```kotlin
val ipc = CarIpc.create(context)
val climateClient = ipc.connect(ClimateContract.SERVICE_ID)

// 异步等待就绪（底层自动执行注册中心发现与直连握手）
climateClient.awaitReady(timeoutMillis = 5000) { readyResult ->
    readyResult.onSuccess {
        println("直连会话建立成功！")
    }
    readyResult.onFailure { error ->
        println("连接超时或失败: ${error.message}")
    }
}
```

#### (2) 读取属性快照 (`get`)
```kotlin
climateClient.get(ClimateContract.TARGET_TEMPERATURE) { res ->
    res.onSuccess { snapshot ->
        println("当前温度: ${snapshot.value}, 版本号: ${snapshot.revision}, 状态质量: ${snapshot.quality}")
    }
}
```

#### (3) 写入属性 (`set` / `setIfVersion`)
```kotlin
// 普通写入
climateClient.set(ClimateContract.TARGET_TEMPERATURE, 26.5f) { res ->
    res.onSuccess { receipt ->
        println("写入回执: status=${receipt.status}")
    }
}

// 基于版本令牌的 CAS 条件写（防止并发冲突覆盖）
climateClient.setIfVersion(ClimateContract.TARGET_TEMPERATURE, 26.5f, expectedWriteToken = lastToken) { res ->
    res.onSuccess { receipt -> ... }
    res.onFailure { error -> /* 若版本冲突，返回 ErrorCode.CONCURRENT_CONFLICT */ }
}
```

#### (4) 远程方法调用 (`call`)
```kotlin
climateClient.call(ClimateContract.START_SELF_TEST, "CHECK_SENSOR") { res ->
    res.onSuccess { receiptString ->
        println("命令返回: $receiptString")
    }
}
```

#### (5) 多 Key 状态与事件订阅 (`subscribe`)
```kotlin
val subHandle = climateClient.subscribe(
    keys = listOf(ClimateContract.TARGET_TEMPERATURE, ClimateContract.FAN_SPEED),
    options = SubscribeOptions(replayLatest = true) // replayLatest = true 会先原子重放一份当前最新快照
) { message ->
    val prop = message.payload as? PropertyUpdate<*>
    if (prop != null) {
        println("收到属性变更: ${prop.key.id} -> ${prop.snapshot.value}")
    }
}

// 取消订阅
subHandle.cancel()
```

---

### 4. 进阶：Kotlin 协程与 Flow DSL 响应式编程

`:ipc-sdk-ktx` 提供了协程友好的扩展函数，消除了层层回调嵌套：

```kotlin
lifecycleScope.launch {
    // 1. 挂起等待直连就绪
    climateClient.awaitReady()

    // 2. 挂起读取
    val snap = climateClient.get(ClimateContract.TARGET_TEMPERATURE)
    println("初始温度: ${snap.value}")

    // 3. 挂起设置
    val receipt = climateClient.set(ClimateContract.TARGET_TEMPERATURE, 25.0f)

    // 4. 挂起调用
    val result = climateClient.call(ClimateContract.START_SELF_TEST, "PARAM")

    // 5. 转换为 Flow 响应式监听多属性流
    climateClient.observe(listOf(ClimateContract.TARGET_TEMPERATURE, ClimateContract.FAN_SPEED))
        .collect { msg ->
            println("Flow 收到消息: ${msg.capabilityId} = ${msg.payload}")
        }
}
```

---

### 5. 单进程双角色（Dual-Role）

在复杂的车载系统中，一个进程往往既需要向外提供服务，又需要消费其他应用的服务。`CarIpc` 原生支持双角色：

```kotlin
class VehicleServiceManager(context: Context) {
    private val ipc = CarIpc.create(context)

    fun start() {
        // 角色 1: 作为服务端，发布当前进程负责的显示能力
        val displayPublisher = ipc.publishService(DisplayContract.SERVICE_ID, DisplayContract.schema) {
            onSet(DisplayContract.CURRENT_TITLE) { title, _ ->
                println("更新标题: $title")
                SetReceipt.accepted()
            }
        }

        // 角色 2: 作为客户端，连接由另一个进程负责的空调能力
        val climateClient = ipc.connect(ClimateContract.SERVICE_ID)
        climateClient.awaitReady(3000) {
            climateClient.set(ClimateContract.TARGET_TEMPERATURE, 22.0f) {}
        }
    }

    fun stop() {
        ipc.close() // 统一释放所有客户端直连与服务端端点
    }
}
```

---

### 6. 纯 Java 应用接入与互操作指南

中间件底层经过了高度的 Java 互操作（Java Interop）设计与对齐，**纯 Java 开发的应用无需依赖 Kotlin 协程/扩展库，只需引入标准 Jar/AAR 即可享有与 Kotlin 完全等价的完整功能**。

#### (1) 依赖配置
纯 Java 业务工程只需引入 `:ipc-contract-api` 与 `:ipc-sdk`，无需引入 `:ipc-sdk-ktx`：
```groovy
// build.gradle (Java Module)
dependencies {
    implementation project(':ipc-contract-api')
    implementation project(':ipc-sdk')
}
```

#### (2) 契约定义 (纯 Java)
使用契约工厂方法与 `ServiceSchema.builder()` 构建契约，完美避免 Java 关键字冲突：
```java
package com.company.vehicle.contract;

import android.os.Bundle;
import com.caripc.contract.*;

public final class ClimateContract {
    public static final String SERVICE_ID = "com.company.vehicle.climate";

    // 属性定义：双端完全一致采用 PropertyKey.createXxx 工厂方法
    public static final PropertyKey<Float> TARGET_TEMPERATURE = PropertyKey.createFloat("target_temperature", 16.0f, 32.0f);
    public static final PropertyKey<Integer> FAN_SPEED = PropertyKey.createInt("fan_speed", 0, 7);
    public static final PropertyKey<Bundle> CLIMATE_BUNDLE = PropertyKey.createBundle("climate_bundle");

    // 命令定义
    public static final CommandKey<String, String> START_SELF_TEST = CommandKey.stringToString("start_self_test");

    // 契约 Schema 聚合
    public static final ServiceSchema SCHEMA = ServiceSchema.builder(SERVICE_ID, 1, 0)
            .addProperty(TARGET_TEMPERATURE)
            .addProperty(FAN_SPEED)
            .addProperty(CLIMATE_BUNDLE)
            .addCommand(START_SELF_TEST)
            .build();

    private ClimateContract() {}
}
```

#### (3) 服务端发布 (纯 Java)
通过 `CarIpc.publishService` 结合 Java Lambda 注册 Handler：
```java
CarIpc ipc = CarIpc.create(context);

ServicePublisher publisher = ipc.publishService(
    ClimateContract.SERVICE_ID,
    ClimateContract.SCHEMA,
    // OnSetHandler: 处理属性修改
    (keyId, value, callerUid) -> {
        Log.i("CarIpcServer", "Java 收到属性设置请求: key=" + keyId + ", val=" + value);
        // 执行底层硬件控制并返回回执
        return SetReceipt.accepted();
    },
    // OnCallHandler: 处理远程命令调用
    (commandId, param, callerUid) -> {
        Log.i("CarIpcServer", "Java 收到命令调用: cmd=" + commandId + ", param=" + param);
        return "RESULT_SUCCESS";
    }
);

// 广播更新权威属性值
publisher.update(ClimateContract.TARGET_TEMPERATURE, 24.5f);
```

#### (4) 客户端调用与异步回调 (纯 Java)
SDK 为 Java 提供了标准的 `IpcCallback<T>` 双方法接口（`onSuccess` / `onError`），彻底规避 Kotlin Result 符号兼容问题：
```java
CarIpc ipc = CarIpc.create(context);
RemoteService client = ipc.connect(ClimateContract.SERVICE_ID);

// 1. 等待服务就绪
client.awaitReady(3000, new IpcCallback<Void>() {
    @Override
    public void onSuccess(Void unused) {
        Log.i("CarIpcClient", "已直连到服务端！");

        // 2. 异步读取属性 (get)
        client.get(ClimateContract.TARGET_TEMPERATURE, new IpcCallback<PropertySnapshot<Float>>() {
            @Override
            public void onSuccess(PropertySnapshot<Float> snapshot) {
                Log.i("CarIpcClient", "当前温度: " + snapshot.getValue() + ", 版本: " + snapshot.getRevision());
            }

            @Override
            public void onError(IpcError error) {
                Log.e("CarIpcClient", "读取失败: " + error.getMessage());
            }
        });

        // 3. 异步修改属性 (set)
        client.set(ClimateContract.TARGET_TEMPERATURE, 26.0f, new IpcCallback<SetReceipt>() {
            @Override
            public void onSuccess(SetReceipt receipt) {
                Log.i("CarIpcClient", "设置成功，回执状态: " + receipt.getStatus());
            }

            @Override
            public void onError(IpcError error) {
                Log.e("CarIpcClient", "设置失败: " + error.getMessage());
            }
        });

        // 4. 远程命令调用 (call)
        client.call(ClimateContract.START_SELF_TEST, "PARAM_CHECK", new IpcCallback<String>() {
            @Override
            public void onSuccess(String result) {
                Log.i("CarIpcClient", "命令调用结果: " + result);
            }

            @Override
            public void onError(IpcError error) {
                Log.e("CarIpcClient", "命令调用失败: " + error.getMessage());
            }
        });

        // 5. 状态订阅 (subscribe)
        CancelHandle subscription = client.subscribe(
            Collections.singletonList(ClimateContract.TARGET_TEMPERATURE),
            message -> {
                if (message.getPayload() instanceof PropertyUpdate) {
                    PropertyUpdate<?> update = (PropertyUpdate<?>) message.getPayload();
                    Log.i("CarIpcClient", "订阅推送: " + update.getKey().getId() + " = " + update.getSnapshot().getValue());
                }
            }
        );

        // 必要时取消订阅
        // subscription.cancel();
    }

    @Override
    public void onError(IpcError error) {
        Log.e("CarIpcClient", "服务连接失败: " + error.getMessage());
    }
});
```

---

## 五、 关键机制与设计规范

1. **死区与限频（Authoritative Store vs Notification）**：
   - 当设置了 `NotificationPolicy(minDelta = 0.5)` 时，即使温度从 24.0 变化到 24.2（增量未达 0.5），`StateStore` 中的**权威值依然会被准确更新为 24.2**，只是暂时抑制对外通知；当累积变化达到阈值后，立即触发通知。
2. **有界队列与状态合并（State Coalescing）**：
   - 订阅推送采用深度为 128 的有界队列；
   - 若客户端处理较慢发生堆积，**同一 PropertyKey 的中间更新状态会自动合为最新状态**，保障不会撑爆内存；
   - **初始快照帧（Snapshot）带有原子标记，绝不会被合并丢弃**，确保客户端总能拿到基准全量数据。
3. **独立发送线程池（Outbound Isolation）**：
   - `SessionManager` 在持有状态锁时只计算快照，并通过独立无阻塞的 `OutboundExecutor` 投递远端 Binder 跨进程调用，杜绝因对端挂起反向阻塞本端内核。
4. **安全鉴权（Permission Policy）**：
   - 每个直连 Session 在建立时捕获对端的 `callingUid`；
   - 后续任何请求到达时，均比对 `Binder.getCallingUid()` 与当前 Session 绑定的 `ownerUid`，杜绝 Binder 跨进程冒用和会话转交。

---

## 六、 错误码与成败回执排查指南

### 1. set / get 返回值与成败判断
- **`get` 读取操作**：返回 `Result<PropertySnapshot<T>>`。成功时可在快照中获取 `value`、`revision`（版本）、`quality`（数据质量）；失败时回调 `onFailure` 并附带 `IpcError`。
- **`set` 写入操作**：返回 `Result<SetReceipt>`。
  - 传输成功时返回回执 `receipt.status`：
    - `SetStatus.ACCEPTED`：服务端已接受，正在异步执行。
    - `SetStatus.APPLIED`：服务端已硬件生效。
    - `SetStatus.REJECTED`：服务端业务逻辑拒绝（`receipt.error` 包含具体原因）。
  - 校验失败或底层断开时触发 `onFailure`（如数值越界、超时、断连）。

### 2. 完整错误码对照表 (`ErrorCode`)

中间件在 [`ErrorCode.kt`](file:///d:/Study/CarIpc/ipc-contract-api/src/main/kotlin/com/caripc/contract/IpcError.kt) 中定义了标准错误码，涵盖传输、路由、校验与业务全场景：

| 错误码枚举 | Code 数值 | 含义说明 | 典型产生场景与排查建议 |
| :--- | :---: | :--- | :--- |
| `OK` | 0 | 成功 | 请求处理正常完成。 |
| `SERVICE_UNAVAILABLE` | 1 | 服务不可用 / 未就绪 | 目标服务尚未发布或直连 Session 正在建立中。建议稍后重试或检查服务端进程是否已启动。 |
| `CONNECTION_LOST` | 2 | 跨进程连接断开 | 对端服务端在处理过程中进程崩溃或被系统杀死。SDK 会自动触发重连。 |
| `PERMISSION_DENIED` | 3 | 权限不足 | 调用方 UID 尝试冒用他人 Session 或未通过签名权限校验。 |
| `SERVICE_ID_CONFLICT` | 4 | 服务注册冲突 | 不同的 UID 尝试使用同一个 `serviceId` 进行发布，抢占被拒绝。 |
| `VERSION_MISMATCH` | 5 | 协议/传输版本不兼容 | 客户端与服务端的 Transport 主版本不一致（如 1.x 与 2.x）。 |
| `UNKNOWN_CAPABILITY` | 6 | 未知能力 / 属性不存在 | 访问了契约 `ServiceSchema` 中未定义的 `keyId` 或命令名。 |
| `TYPE_MISMATCH` | 7 | 数据类型不匹配 | 传输的数据类型与契约定义不符（如契约定义 Float，实际传了 String）。 |
| `INVALID_ARGUMENT` | 8 | 参数数值非法 | 数值超出契约限制（低于 `min` 或高于 `max`），或浮点数传了 `NaN/Infinity`。在调用方 SDK 本地即可直接拦截。 |
| `READ_ONLY` | 9 | 属性只读 | 尝试对未注册 `onSet` 处理器的只读属性调用 `set`。 |
| `UNINITIALIZED` | 10 | 数据尚未初始化 | 服务端刚发布，尚未调用 `publisher.update()` 赋初值，快照 quality 为 `UNINITIALIZED`。 |
| `DATA_UNAVAILABLE` | 11 | 数据暂不可用 | 底层传感器或硬件离线，无法获取有效值。 |
| `TIMEOUT` | 12 | 调用超时 | 请求在指定的 `timeoutMs`（默认 3000ms）内未收到对端响应。排查服务端主线程卡顿或耗时操作。 |
| `CANCELLED` | 13 | 请求已取消 | 调用方主动取消了请求或注销了订阅。 |
| `TOO_MANY_REQUESTS` | 14 | 请求过于频繁 | 超过了服务端并发处理阈值。 |
| `RESOURCE_EXHAUSTED` | 15 | 资源耗尽 | 服务端会话或内存资源达到上限。 |
| `PAYLOAD_LARGE` | 16 | 负载超限 | 单次传输的数据超过 Binder 限制（1MB 共享空间），建议精简传输字段。 |
| `SLOW_CONSUMER` | 17 | 消费过慢 | 客户端处理速度过慢，触发了服务端的丢包合并保护。 |
| `EVENT_GAP` | 18 | 事件序列不连续 | 订阅消息发生乱序或丢包。 |
| `SERVICE_CLOSED` | 19 | 服务端已关闭 | 服务端已调用 `close()` 注销。 |
| `INTERNAL_ERROR` | 20 | 内部未知异常 | 服务端业务抛出了未捕获的运行时异常。 |
| `CONCURRENT_CONFLICT` | 21 | 并发版本冲突 (CAS) | 使用 `setIfVersion` 条件写时，当前写入令牌 `writeToken` 与服务端不一致，防止覆盖他人并发修改。重新读取最新快照后重试即可。 |
| `STALE_INSTANCE` | 22 | 过期实例标识 | 请求发送给了已被销毁的旧实例。 |
| `CAPABILITY_NOT_SUPPORTED`| 23 | 功能暂不支持 | 访问了当前协议版本预留但未开启的扩展特性。 |
| `OPERATION_EXPIRED` | 24 | 操作已过期 | 异步长时间任务的回执已过期。 |

---

## 七、 统一日志调试指南 (`IpcLog`)

中间件内置了轻量高可用的统一日志组件 [`IpcLog`](file:///d:/Study/CarIpc/ipc-runtime/src/main/kotlin/com/caripc/runtime/IpcLog.kt)，所有关键生命周期和跨进程调用均带有清晰的日志标记。

### 1. Logcat 过滤命令
在 Android Studio Logcat 或终端中使用统一前缀 `CarIpc` 进行过滤：
```powershell
# 过滤中间件全链路日志
adb logcat -s CarIpc*

# 或者在 Windows PowerShell 下筛选
adb logcat | Select-String "CarIpc"
```

### 2. 核心链路日志标签说明
- `CarIpc-RegistryService`：注册中心接收的发布（`publish`）、解绑（`unpublish`）、监听（`resolveAndWatch`）日志。
- `CarIpc-RegistryStore`：注册中心服务路由表维护、generation 清理、观察者快照下发。
- `CarIpc-RegistryConnector`：业务端连接注册中心、绑定状态变化、指数退避重连日志。
- `CarIpc-EndpointHost`：服务端端点接收 `openSession` 握手及版本兼容性校验。
- `CarIpc-SessionManager`：服务端点对点会话建立、写属性（`handleSetOperation`）、订阅、广播更新日志。
- `CarIpc-ConnectionController`：客户端直连会话状态机变迁（`DISCOVERING` -> `CONNECTING` -> `READY` -> `RECONNECTING`）、请求发送与回执。
- `CarIpc-StateStore`：服务端权威状态存储、死区过滤通知抑制与放行日志。
- `CarIpc-CarIpc`：SDK 入口 `publishService`、`connect`、`close` 日志。

---

## 八、 如何查看哪些应用/模块注册、连接或调用了服务

在多 App 车载系统中，开发者经常需要排查：**“当前有哪些模块发布了空调服务？又有哪些 App 正在连接、订阅或修改空调服务？”**

中间件通过 `PermissionPolicy` 自动将 Android 底层的 `callingUid` 解析为实际的**应用程序包名（Package Name）**，并提供**日志追踪**与 **`dumpsys` 诊断命令**两种方式。

### 1. 方式一：查看实时 Logcat 日志

通过筛选包含包名信息的日志，可以直接看清交互的双方：

```powershell
adb logcat -s CarIpc-RegistryStore CarIpc-SessionManager CarIpc-EndpointHost
```

#### (1) 查看“谁发布了服务” (Publisher)
当空调服务端（如 `com.caripc.sample.server`）启动并发布服务时，注册中心日志会清晰记录其包名与 UID：
```text
I/CarIpc-RegistryStore: publish() received: serviceId=com.caripc.service.climate, package=com.caripc.sample.server, UID=10122, instanceId=e1c07f4a-...
I/CarIpc-RegistryStore: publish succeeded: serviceId=com.caripc.service.climate, package=com.caripc.sample.server, UID=10122, gen=100
```

#### (2) 查看“谁在监听服务发现” (Watcher)
当客户端（如 `com.caripc.sample.client`）发起连接请求时，它会先向注册中心查询并监听空调服务：
```text
I/CarIpc-RegistryStore: resolveAndWatch() received: serviceId=com.caripc.service.climate, watcherPkg=com.caripc.sample.client, UID=10123, watchId=1
I/CarIpc-RegistryStore: Delivering snapshot to watcher 1 (com.caripc.sample.client) for com.caripc.service.climate
```

#### (3) 查看“谁与空调服务建立了直连会话” (Client Session)
客户端拿到服务端端点后建立直连会话，服务端日志会记录具体包名：
```text
I/CarIpc-EndpointHost: openSession: client com.caripc.sample.client (UID 10123) requested transport v1.0
I/CarIpc-SessionManager: Client connected to [com.caripc.service.climate]: package=com.caripc.sample.client, UID=10123, sessionId=550e8400-...
```

#### (4) 查看“谁订阅了哪些属性/事件” (Subscription)
客户端订阅属性变化时，会明确记录订阅的属性 Key 列表：
```text
I/CarIpc-SessionManager: Client [pkg=com.caripc.sample.client, uid=10123] subscribed to [com.caripc.service.climate]: subId=sub-01, keys=[climate.temp.target, climate.fan.speed]
```

#### (5) 查看“谁在修改温度或调用指令” (Set / Call)
当客户端发起 `set`（如调节温度）时，服务端不仅能校验权限，还能打印发起操作的应用包名：
```text
I/CarIpc-SessionManager: handleSetOperation: capabilityId=climate.temp.target, value=25.0 from Client[pkg=com.caripc.sample.client, uid=10123] -> status=ACCEPTED
```

---

### 2. 方式二：使用 `dumpsys` 无侵入实时查看当前全量路由表

无需查看刷屏的日志，随时在终端敲一条命令即可输出当前系统内所有已注册服务以及谁在监听：

```powershell
adb shell dumpsys activity service com.caripc.registry/.RegistryService
```

**输出示例**：
```text
================ CarIpc Registry Diagnostics ================
Dump Time: 2026-09-08 14:00:00

CarIpc Active Registered Services (1):
  [1] ServiceId: com.caripc.service.climate
      Owner Package: com.caripc.sample.server (UID: 10122)
      InstanceId   : e1c07f4a-89bc-4b1a-b67f-948fcf8d0112
      Generation   : 100
      Version      : v1.0
      Capabilities : [climate.temp.target, climate.temp.cabin, climate.fan.speed, climate.cmd.self_test, climate.evt.self_test_finished]

CarIpc Active Watchers (1):
  [1] WatchId: 1 -> ServiceId: com.caripc.service.climate [Watcher: com.caripc.sample.client (UID: 10123)]
=============================================================
```
从输出可以一目了然：
- 空调服务当前由 `com.caripc.sample.server`（UID 10122）发布并提供；
- 客户端 `com.caripc.sample.client`（UID 10123）正在作为观察者接入并监听该服务。
