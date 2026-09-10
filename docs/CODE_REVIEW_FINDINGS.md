# CarIpc 代码评审缺陷工单（交付给实现方）

> ## 修复状态（已按本工单执行，全量 `assembleDebug test` 通过）
>
> | 级别 | 结论 |
> |---|---|
> | P0（6/6） | ✅ 全部修复：P0-1 超时调度器、P0-2 编码前置、P0-3 submit 失败传播、P0-4 统一异常边界、P0-5 NULL/Unit 命令、P0-6 线类型校验 |
> | P1（9/9） | ✅ 全部修复：P1-1 CAS token、P1-2 限频补发、P1-3 GAP 通知、P1-4 快照原子化、P1-5 单订阅串行投递、P1-6 watch 归属、P1-7 signature+ACL、P1-8 关闭即下线、P1-9 契约版本协商 |
> | P2（13/13） | ✅ 修复：P2-1 awaitReady 定时器、P2-2 requestId 去重、P2-3 订阅幂等/配额、P2-5 负载上限、P2-6 契约校验、P2-7 锁纪律、P2-8 unlink/link 顺序、P2-9 重连一致性、P2-10 observe 缓冲、P2-11 readSnapshotById、P2-12 deadline 钳制、P2-13 示例容错；P2-4 部分：`cancel`/`acknowledge` 已实现真实语义，`retryPolicy` 未实现自动重试（显式告警） |
> | P3 | ✅ 修复 P3-1（删重复 wrapper）、P3-3（去 persistent）、P3-5（debugLogging）、P3-6（shared UID/hex）；⚠️ 未改 P3-2（release 仍用 debug 签名，无独立证书，示例场景保留）、P3-4（JDK/buildTools 文档漂移，低优先） |
>
> **重要行为/线协议变更（升级时需重新构建并一起安装三个 APK）**：
> 1. 注册中心权限由 `normal` 改为 **`signature`**（`ipc-registry-app` 与业务应用必须用同一签名构建）。
> 2. 线协议 `SubscriptionEnvelope` 末尾新增 `gapFromSeq` 字段；`ValueType` 新增 `NULL(10)`（void/Unit 命令）。
> 3. `ServerHello.windowSize` 现为 **0**（v1 不做 ACK 窗口流控）；`ISession.createSession` 现返回 `CreatedSession(sessionId, session)`（内部 API）。
> 4. 无契约校验的「宽松路径」仅当 `SessionManager.schema == null`（仅内部/测试装配）时生效；生产 `CarIpc.publishService` 总是装载契约并严格校验。

---

> 本文是**可直接执行的缺陷清单**，不是讨论稿。每条包含：现象 → 代码证据（`文件:行`）→ 根因 → 影响 → 修复要求 → 可验证的验收标准。
>
> **核验基线**（评审时实测，请以此为准，不要凭猜测改动）：
> - 工程可离线构建通过：`.\gradlew.bat --offline :ipc-runtime:test :ipc-sdk:test :ipc-sdk-ktx:compileDebugKotlin` → exit 0
> - 现有单测全绿：`ipc-runtime` 12 个用例（DeliveryQueue 3 / EndToEndIpc 2 / RegistryStore 1 / RequestTracker 2 / StateStore 4）+ `ipc-sdk` Java 兼容性 1 个，0 failure
> - 评审范围：全部 10 个模块（契约/协议/运行时/SDK/KTX/注册中心/示例/构建脚本）
> - 对照规格：`docs/Android_IPC_Middleware_Architecture.md`（下称「规格」）、`README.md`（下称「README」）
>
> **关键结论：当前实现「能编译、能跑通示例、单测全绿」，但存在 6 个 P0 级缺陷，其中任意一个都会让业务调用永久得不到回调。测试全绿不代表可用——现有用例是同进程直调 Stub，不经过 Binder，覆盖不到这些路径。**

---

## 0. 问题索引表

| ID | 级别 | 位置 | 一句话 |
|---|---|---|---|
| P0-1 | 致命 | `ipc-runtime/.../RequestTracker.kt:75` | 超时扫描函数在生产代码中无任何调用点，请求超时永不触发且对象永久泄漏 |
| P0-2 | 致命 | `ConnectionController.kt:360,399` | 负载编解码在 `submit{}` lambda 内执行，抛异常被线程池吞掉，请求不发也不回调 |
| P0-3 | 致命 | `ConnectionController.kt:307,353,392,425,446` | `OutboundExecutor.submit()` 返回值被忽略，任务被拒绝时静默丢请求 |
| P0-4 | 致命 | `SessionManager.kt:246-294,129-162` | 服务端 SET/GET 路径只 catch `IpcError`，运行时异常在 oneway 事务中被 Binder 吞掉，客户端收不到响应 |
| P0-5 | 致命 | `ConnectionController.kt:380` | `call()` 用 erased cast 判断返回值，命令正常返回 null 必然误报 `TYPE_MISMATCH` |
| P0-6 | 致命 | `ConnectionController.kt:290,380` | 运行期类型校验实际不生效（unchecked cast），契约类型不符时不报错、坏值直落业务 |
| P1-1 | 高 | `SessionManager.kt:252-278` + `StateStore.kt:75,196` | `setIfVersion` 返回的 writeToken 立即失效，CAS 流程不可用；普通 `set` 不回传 token |
| P1-2 | 高 | `StateStore.kt:98-110` | 限频/死区抑制后无到期重发，最后一个合格变化被永久丢弃（违反规格 5.4 规则 3） |
| P1-3 | 高 | `DeliveryQueue.kt:43-50` + `SessionManager.kt:323,350` | 队列溢出丢消息对订阅者完全无感知，服务端从不产生 `KIND_GAP`/`EVENT_GAP`/`SLOW_CONSUMER` |
| P1-4 | 高 | `SessionManager.kt:296-328` | 先注册订阅后读快照，并发更新与初始快照倒序（违反验收项 T06） |
| P1-5 | 高 | `SessionManager.kt:357-372` + `DeliveryQueue.kt:64-79` | 同一订阅可被多线程并发发送，`deliverySeq` 分配在出队时 → 消息可达乱序 |
| P1-6 | 高 | `RegistryStore.kt:173-181` | `unwatch`/重复 `resolveAndWatch` 不校验调用者 UID，任何应用可注销或顶掉他人 watch |
| P1-7 | 高 | `PermissionPolicy.kt:50,88` + `ipc-registry-app/src/main/AndroidManifest.xml:6` | 鉴权 fail-open 且 ACL 是死代码；注册中心权限用 `normal`（规格要求 `signature`） |
| P1-8 | 高 | `CarIpc.kt:143-148,190-193` + `RegistryConnector.kt:196-215` | `ServicePublisher.close()` 无法真正下线：token 竞态导致不注销，重连后还会「复活」已关闭服务 |
| P1-9 | 高 | `EndpointHost.kt:18,36-46` | 契约版本协商缺失；`ServerHello.sessionId` 位置被传入 serviceId；`maxPayloadBytes` 客户端从不读取 |
| P2-1 | 中 | `ConnectionController.kt:251-280` | `awaitReady` 用睡眠任务当定时器占用出站线程池；被拒绝时 waiter 永久泄漏；`close()` 不清理 waiter |
| P2-2 | 中 | `RequestTracker.kt:25-28` | `register` 不做 requestId 去重，同 id 会遗弃前一个 callback（永不完成） |
| P2-3 | 中 | `SessionManager.kt:86-92,296-299` | 重复 subscriptionId 覆盖不关旧队列；`unsubscribe` 是唯一缺 owner 校验的方法；会话/订阅无配额 |
| P2-4 | 中 | `SessionManager.kt:94-101,216-228` + `Keys.kt:188` | 大量空壳接口与死配置（`acknowledge`/`cancel`/`GET_OPERATION`/`windowSize`/`retryPolicy`） |
| P2-5 | 中 | `ipc-protocol/.../IpcPayload.java:32,40` | 负载上限按字符数判断、Bundle 完全不检查；`PAYLOAD_LARGE` 从未产生，超大 Bundle 可致进程崩溃 |
| P2-6 | 中 | `SessionManager.kt:128-243` | 服务端不做 schema/契约校验：`readable`/`writable`/`observable`/`capabilities` 全部空转 |
| P2-7 | 中 | `RegistryStore.kt:44,138,226` | 持内部锁调用远端 callback 与 PackageManager；`dump()` 持锁；services/watchers 无配额 |
| P2-8 | 中 | `RegistryStore.kt:66-89` | 替换旧记录时先 unlink 后 link，link 失败会留下失去死亡清理的僵尸注册 |
| P2-9 | 中 | `RegistryConnector.kt:75-82` | `onServiceDisconnected` 重连路径不 unbind，与另两条路径不一致，存在重复绑定风险 |
| P2-10 | 中 | `ipc-sdk-ktx/.../CarIpcKtx.kt:62-69` | `observe()` 用 `trySend` 且忽略失败，Flow 消费慢时静默丢消息 |
| P2-11 | 中 | `SessionManager.kt:132` | 服务端 GET 用 `PropertyKey.createString()` 临时构造 key，类型信息丢失，仅靠类型擦除侥幸正确 |
| P2-12 | 中 | `SessionManager.kt:112-126` | 客户端传入的 deadline 未做上限钳制（规格要求「限制在允许的最大等待范围」） |
| P2-13 | 中 | `sample-server/.../ClimateServerService.kt:43-63` | 示例在 main 线程 post `update()`，越界值会抛异常导致示例进程崩溃 |
| P3-1 | 低 | `gradle/gradle/wrapper/` | 重复且内容完全相同的 Gradle Wrapper，两份都被 git 跟踪 |
| P3-2 | 低 | `ipc-registry-app/build.gradle.kts:21` 等 | release 构建用 debug 签名；`sample-client` 的 release 又完全没有签名配置 |
| P3-3 | 低 | `ipc-registry-app/src/main/AndroidManifest.xml:14` | `android:persistent="true"` 对三方 APK 不生效；自动拉前台服务与规格 15.1 冲突 |
| P3-4 | 低 | 各 `build.gradle.kts` | 文档与配置漂移（JDK 11 vs jvmTarget 1.8、buildTools 35 vs compileSdk 33、targetSdk 30） |
| P3-5 | 低 | `ipc-runtime/.../IpcLog.kt:13` | 日志默认全开且无 release 开关，会持续输出 UID/包名/业务值 |
| P3-6 | 低 | `PermissionPolicy.kt:31-38,133-142` | shared UID 下无法区分真实调用应用；证书 hex 解析对奇数长度静默返回 false |
| P3-7 | 低 | `ipc-runtime/src/test/...` | 测试基线不足：同进程直调、`returnDefaultValues=true`、缺 8 类关键用例 |

---

## 1. P0 级（必须优先修完，否则中间件不可用）

### P0-1 请求超时机制是死代码

**现象**：对端不响应时，`get` / `set` / `call` 的 callback **永远不会被调用**。

**证据**
- `ipc-runtime/src/main/kotlin/com/caripc/runtime/RequestTracker.kt:75` 定义 `checkTimeouts()`，但全工程唯一调用点是 `ipc-runtime/src/test/kotlin/com/caripc/runtime/RequestTrackerTest.kt:46`。
- 生产代码中没有任何 `Handler.postDelayed` / `ScheduledExecutorService` / `Timer` 周期性调用它（可用下方附录 A 的 grep 复核）。

**根因**：`RequestTracker.register()` 只记录 deadline，缺少驱动扫描的调度器；`ConnectionController` 也没有任何超时轮询。

**影响**
- `README.md`「六·1」「六·2」承诺的 `TIMEOUT(12)`（默认 3000ms）不成立，属于文档欺诈级偏差。
- `pendingRequests` 只增不减 → 每次无响应请求永久泄漏一个 `PendingRequest` + 其闭包（含业务 listener），长跑进程内存单调增长。
- 与 P0-2/P0-3/P0-4 叠加后，故障表现为「调用石沉大海」，无任何可观测信号（`timeoutCount` 恒为 0）。

**修复要求**
1. 在 `CarIpc` 内建立单一调度器（推荐 `ScheduledExecutorService`，1 个线程，daemon，周期 100ms 左右），周期性调用每个活跃 `RequestTracker.checkTimeouts()`；或按请求精确调度 `postDelayed`。
2. `CarIpc.close()` / `RemoteService.close()` 必须取消调度并 `clearAllWithConnectionLost()`；调度器不得随每次请求新建线程。
3. `RequestTracker.register()` 返回可取消句柄，`checkTimeouts` 触发后必须从 map 移除（现状已移除，保持）。

**验收标准**
- 新增用例：注册请求后不投递任何响应，在 `timeoutMs + ε` 内 callback 必须收到 `IpcError(ErrorCode.TIMEOUT)`，且 `timeoutCount == 1`、`pendingRequests` 为空。
- 新增用例：`checkTimeouts` 触发后再收到迟到响应，必须只完成一次（`lateResponseCount` 递增，业务 callback 不再被调用）。
- 新增用例：`close()` 后调度器不再运行（线程数不增长、无 `RejectedExecutionException`）。
- 禁止用「在 `awaitReady` 的 sleep 任务里顺手扫一遍」的方式交付。

---

### P0-2 负载编解码在出站任务内部执行，异常被线程池吞掉

**现象**：`set` / `call` 传入不支持的 Java 类型，或字符串/字节数组超限时，请求根本不会发出，调用方永远收不到回调（叠加 P0-1 后是永久挂起）。

**证据**
- `ipc-runtime/src/main/kotlin/com/caripc/runtime/ConnectionController.kt:353-369`（`set`）中 `IpcPayload.ofAny(value)` 位于 `outboundExecutor.submit { ... }` 的 lambda 内（`:360`）。
- `ConnectionController.kt:392-408`（`call`）同理（`:399`）。
- 抛点：`ipc-protocol/src/main/java/com/caripc/protocol/IpcPayload.java:33,37`（超限 `IllegalArgumentException`）、`:97`（`Unsupported value type`）。
- `submit` 内部的 `catch (RejectedExecutionException)` 只覆盖拒绝执行，不覆盖任务体抛出的异常；`ThreadPoolExecutor` 会把任务体异常交给线程的未捕获处理器。

**根因**：编解码（可能抛异常的纯计算）被放进了「调度」阶段，缺少「先编码、失败即回调」的前置校验。

**影响**：契约声明了 `PAYLOAD_LARGE(16)`、`INVALID_ARGUMENT(8)`，但这条路径一个都不会产生；业务侧表现为随机丢请求。

**修复要求**
1. 在 `submit` **之前**完成 `IpcPayload.ofAny(...)` 等所有可能抛异常的编码动作；捕获异常并转成 `callback.onError(IpcError(...))`（超限 → `PAYLOAD_LARGE`，类型不支持 → `INVALID_ARGUMENT` 或 `TYPE_MISMATCH`）。
2. `submit` 的 lambda 体内只允许出现不会抛业务异常的动作；如需兜底，lambda 自身包 `try/catch` 并把异常转成错误回调 + `IpcLog.e`。
3. 同样的编码异常在服务端响应路径（`SessionManager.kt:133,177`）也要处理，见 P0-4。

**验收标准**
- 新增用例：`set(key, 未支持的自定义对象)` → 回调立即（<50ms）收到 `IpcError`，且**没有**任何 Binder 调用发出。
- 新增用例：String 长度超 `MAX_PAYLOAD_BYTES` → 回调收到 `PAYLOAD_LARGE`，进程不产生未捕获异常日志。
- 新增用例：executor 内任意任务抛异常时，`IpcLog.e` 有记录且线程池不静默死亡。

---

### P0-3 `OutboundExecutor.submit()` 失败无人处理

**现象**：出站线程池队列满（容量 512）或已 shutdown 时，任务被静默丢弃，请求既不发送也不回调。

**证据**
- `ipc-runtime/src/main/kotlin/com/caripc/runtime/OutboundExecutor.kt:27-35`：`submit` 返回 `Boolean`，拒绝时只打日志。
- 调用点全部忽略返回值：`ConnectionController.kt:307`（get）、`:353`（set）、`:392`（call）、`:425`（subscribe）、`:446`（unsubscribe）、`:271`（awaitReady 定时器）；`SessionManager.kt:358,375` 同样忽略。
- `CarIpc.kt:116-123` 的 `close()` 会 `outboundExecutor.shutdown()`，此时外部仍可能持有 `RemoteService` 引用继续调用。

**根因**：`submit` 的失败语义没有向上传播，调用方没有「投递失败 → 立即以错误回调完成请求」的路径。

**修复要求**
1. 所有 `submit` 调用点处理 `false`：已注册的请求必须 `requestTracker.completeError(reqId, IpcError(RESOURCE_EXHAUSTED 或 SERVICE_CLOSED, ...))`。
2. `subscribe`/`awaitReady` 等无 requestId 的路径，投递失败必须走 listener/waiter 的错误完成分支，不得静默。
3. `close()` 之后再次调用业务 API，应立即返回 `SERVICE_CLOSED`，不允许「注册了但永不发送」。

**验收标准**
- 新增用例：用容量 1 的 executor + 占用 1 个在途任务，使后续 `submit` 被拒绝 → 调用方必须在 100ms 内收到错误回调，`pendingRequests` 为空。
- 新增用例：`ipc.close()` 后再 `remoteService.get(...)` → 回调收到 `SERVICE_CLOSED`，且无异常抛出。

---

### P0-4 服务端请求处理异常未兜底（oneway 事务会静默吞掉）

**现象**：客户端发送了一个会让服务端处理逻辑抛运行时异常的请求（类型不符、越界值、业务 handler 自身的 bug），客户端**收不到任何响应**。

**证据**
- `ipc-runtime/src/main/kotlin/com/caripc/runtime/SessionManager.kt:246-294`（`handleSetOperation`）只有 `catch (e: IpcError)`（`:280`），没有 `catch (Exception)`。
- 对比 `SessionManager.kt:172-214`（`OP_CALL`）有 `catch (e: Exception)`（`:201`）——同一文件内两条路径处理不一致，说明是遗漏而非设计。
- `OP_GET`（`SessionManager.kt:129-162`）同样只 `catch (IpcError)`，而 `:133` 的 `IpcPayload.ofAny(snapshot.value)` 对不支持的类型会抛 `IllegalArgumentException`。
- 抛点举例：`ipc-sdk-ktx/.../CarIpcKtx.kt:77,85` 的 `rawVal as T`（Java/Kotlin 泛型擦除后仍会在调用业务 handler 时产生 `ClassCastException`）、`ipc-contract-api/.../Keys.kt:29-67` 的 `validate()`（越界/NaN 抛 `IllegalArgumentException`，由业务 handler 内的 `publisher.update()` 触发）。
- AIDL 全部方法为 `oneway`（`ipc-protocol/src/main/aidl/com/caripc/protocol/ISession.aidl`），`oneway` 事务中服务端 `onTransact` 抛出的异常无法回传客户端，只会被 Binder 记录警告。

**根因**：服务端请求分发缺少统一异常边界（catch-all → `INTERNAL_ERROR` 响应）。

**影响**：任何一个客户端的错误入参 = 自己永久挂起；同时服务端无任何返回，监控/日志（`IpcLog`）也没有统一记录，排障困难。

**修复要求**
1. 在 `handleRequest`（以及 `ISession.Stub` 的每个方法）加统一 `try/catch(Exception)` → 回 `ResponseEnvelope(STATUS_ERROR, ErrorEnvelope(INTERNAL_ERROR, ...))`，并 `IpcLog.e` 带 `sessionId/capabilityId/requestId`。
2. `handleSetOperation` / `OP_GET` 单独补齐，保证与 `OP_CALL` 行为一致。
3. 服务端在调用业务 handler 前完成线类型与范围校验（见 P2-6），把「客户端传错类型」变成明确的 `TYPE_MISMATCH`/`INVALID_ARGUMENT` 响应，而不是等业务抛异常。
4. 明确 `oneway` 不是「不反馈」的借口：所有请求都必须有终态响应（成功或错误）。

**验收标准**
- 新增用例（服务端侧）：向 `ISession.request` 投递一个会触发 handler 抛异常的 SET（如往 Float 属性写 String），必须收到 `STATUS_ERROR` + `INTERNAL_ERROR`，且服务端进程不崩溃、日志含 requestId。
- 新增用例：`OP_GET` 一个类型不被 `IpcPayload` 支持的值，必须收到错误响应而非无响应。

---

### P0-5 `call()` 返回值判定错误：正常返回 null 会被判为失败

**现象**：服务端命令正常执行并返回 null（Kotlin 返回 `Unit` 语义、Java 返回 void/`null`）时，客户端收到 `TYPE_MISMATCH` 错误。

**证据**
- `ipc-runtime/src/main/kotlin/com/caripc/runtime/ConnectionController.kt:378-390`：`val rawVal = resp.payload?.toValue() as? Resp`（`:380`），`null` → `callback.onError(IpcError(ErrorCode.TYPE_MISMATCH, "Null or incompatible return value", reqId))`（`:384`）。
- 服务端：`SessionManager.kt:176-177` 取 `onCallHandler` 结果后 `IpcPayload.ofAny(result)`，而 `ipc-protocol/.../IpcPayload.java:88` 对 `null` 直接 `return null` → 响应 `STATUS_OK` 且 `payload == null`。

**根因**：把「payload 为 null」与「类型不匹配」混为一谈；`Resp` 是未具化类型参数，`as? Resp` 编译为 unchecked cast，运行期只能识别 null（见 P0-6）。

**影响**：任何 `void`/Unit 语义命令（最典型：空调自检、开关类指令）在契约上无法正常使用；`CommandKey.stringToString` 之外想返回「无结果」的契约无处安放。

**修复要求**
1. 明确契约：允许命令返回 null；`null` payload 必须走 `onSuccess(null)`（`Resp` 需放宽为可空，或显式提供 `CommandKey<Void>` 语义）。
2. 只有 `payload != null` 且 `ValueType.fromTag(payload.typeTag) != command.respType` 时才报 `TYPE_MISMATCH`。
3. 若决定「命令必须返回值」，则必须在契约层用类型系统禁止（当前 `CommandKey` 无法表达），不能靠运行时把它变成用户可见错误。

**验收标准**
- 新增用例：服务端 handler 返回 `null` → 客户端 `call` 回调 `onSuccess(null)`，无 `TYPE_MISMATCH`。
- 新增用例：契约声明 `respType = INT`，服务端返回 String → 客户端收到 `TYPE_MISMATCH`（当前实现收不到，见 P0-6）。

---

### P0-6 运行期类型校验实际不生效（契约类型不符不报错）

**现象**：契约声明 `PropertyKey<Float>`，线路上传来 Int 时，客户端不报 `TYPE_MISMATCH`，坏值直接进入业务；`CommandKey.reqType/respType` 全无约束力。

**证据**
- `ConnectionController.kt:290`（`get`）：`resp.payload?.toValue() as? T`。
- `ConnectionController.kt:380`（`call`）：`resp.payload?.toValue() as? Resp`。
- 两处 `T`/`Resp` 均为未具化（非 `reified`）类型参数，Kotlin 编译器只能给出 unchecked cast，运行期退化为对上界 `Any` 的转换，因此**永不返回 null（除非 payload 本身为 null）**。
- `IpcPayload.java:100-110` 的 `toValue()` 按 `typeTag` 返回不同类型的对象，正是运行期唯一可信的类型来源；`ipc-contract-api/.../ValueType.kt:19` 已提供 `fromTag(tag)`。

**根因**：用泛型擦除后的 cast 代替显式类型标签比对。

**影响**
- 服务端/契约不一致时静默错值（Float 属性收到 Int 会被当成 Float 使用，直到更远处 `ClassCastException`）。
- `README.md`「六·2」中 `TYPE_MISMATCH(7)` 的说明与实现不符。
- SDK 的「强类型」卖点不成立。

**修复要求**
1. 所有线上出入参路径用显式比对：`ValueType.fromTag(payload.typeTag)` vs `key.type` / `command.reqType` / `command.respType`，不一致抛/回 `TYPE_MISMATCH`。
2. 客户端 `set`/`call` 发送前同样校验入参类型与 `reqType`（当前只做了 `key.validate(value)`，对 `bundleToBundle` 等明显不匹配的入参无效）。
3. 服务端在业务 handler 之前做同样校验（与 P0-4 第 3 点合并实现）。

**验收标准**
- 新增用例（参数化）：对每个 `ValueType`，发送一个类型不符的 payload，客户端必须收到 `TYPE_MISMATCH`，日志含 keyId 与实际 typeTag。
- 新增用例：`PropertyKey<Float>` 收到 Int payload 时，业务 handler **不会**被调用。

---

## 2. P1 级（数据正确性与安全，必须在本迭代内修复）

### P1-1 `setIfVersion` 的 writeToken「返回即失效」

**证据**
- `SessionManager.kt:252-255`：条件写先执行 `stateStore.validateAndAdvanceWriteToken(req.capabilityId, req.expectedWriteToken)`，该函数内部 `stored.writeRevision++`（`StateStore.kt:196`）并返回新 token。
- `SessionManager.kt:258`：随后调用 `onSetHandler`；而 `README.md:220` 推荐的业务写法是在 handler 内调用 `publisher.update(...)`。
- `StateStore.update()` 内部又执行 `stored.writeRevision++`（`StateStore.kt:75`）→ 返回给客户端的 token（`SessionManager.kt:276` `newWriteToken ?: receipt.writeToken`）**在到达客户端之前就已经过期**。
- 另外普通 `set` 的 `receipt.writeToken` 通常为 `null`（`ipc-contract-api/.../PropertySnapshot.kt:30-36` 的 `SetReceipt.accepted()` 不填 token），客户端拿不到可用于 CAS 的令牌。

**影响**：README 示例代码路径下，`setIfVersion` 的第二次调用**必然** `CONCURRENT_CONFLICT`，CAS 能力实际不可用。

**修复要求**
1. 统一「写版本推进」的唯一入口：`update()` 与条件写校验不得各自 `++`。建议：`writeRevision` 只由「对外可见的写入生效」推进一次，或在响应组装时回填**当前最新** token。
2. `SetReceipt` 的 `writeToken` 必须由框架在写入完成后填充（不依赖业务显式传参）。
3. 补契约文档：token 语义（作用于哪个属性、何时失效、`set` 后是否需要重新 `get`）。

**验收标准**
- 新增用例：`get` 拿 token → `setIfVersion(token)` 成功 → 用响应返回的 token 再次 `setIfVersion` 成功（当前会失败）。
- 新增用例：并发两个 `setIfVersion(同一 token)`，恰好一个成功、一个 `CONCURRENT_CONFLICT`，且 `error.currentWriteToken` 可被成功方继续使用。

### P1-2 限频/死区抑制后没有到期重发，最后一个变化永久丢失

**证据**
- `StateStore.kt:98-110`：`minNotificationIntervalMs` 判定为「抑制」后直接 `return null`；`lastNotifiedElapsedMs` 只在 `shouldNotify == true` 时推进（`:109`），且**没有任何定时器**在窗口到期后用最新值重新判定。
- 死区同理（`:83-95`）：`lastNotifiedValue` 只在通知时推进，但没有「累积到阈值即发」之外的回补机制——规格要求的是「到期重新以最新状态判断并发送」。
- 规格明确禁止该行为：`docs/Android_IPC_Middleware_Architecture.md:328`「达到变化阈值但仍处于限频窗口时，保留最新候选，到期重新以最新状态判断并发送；**不得因为更新停止就丢掉最后一个合格变化**」；同文件 `:331` 还要求「基线在消息进入既有有序发送路径时推进，不在每次 update 时推进」。

**影响**：典型的连续变化场景（温度从 24.0 连续升到 26.0 后停止更新）订阅者可能永远停在 24.5，权威值却是 26.0 —— 状态显示与实际不符。

**修复要求**
1. 为启用 `NotificationPolicy` 的属性实现「保留最新候选 + 到期重判」：到期时用**当前权威值**重跑死区/限频判定，合格则入队，不合格则丢弃候选（规格 `:329` 允许）。
2. 通知基线（`lastNotifiedValue/ElapsedMs`）推进时机改为「进入有序发送路径时」，而非每次 `update`。
3. 定时任务随属性/服务关闭释放，不得每次 update 新建线程/定时器（规格 `:332`）。

**验收标准**
- 新增用例：`minNotificationIntervalMs = 100`，在 0/10/20ms 连续三次 update 后停止，第 100ms 后订阅者必须收到**最后一个值**（当前收不到）。
- 新增用例：到期时值已回到死区内，允许不发送（验证不会误发）。
- 新增用例：服务关闭后无遗留定时任务/线程。

### P1-3 队列溢出/丢事件对订阅者完全无感知

**证据**
- `DeliveryQueue.kt:43-50`：事件超容量时 `droppedEventCount++` 后 `return false`；非事件消息也 `return false`。
- 调用点忽略返回值：`SessionManager.kt:323`（初始快照 `queue.enqueue(envelope)`）、`:350`（广播 `sub.queue.enqueue(subEnvelope)`）。
- 协议与错误码已就绪但服务端从不产生：`KIND_GAP`（`ipc-protocol/.../SubscriptionEnvelope.java:9`）全工程仅在客户端 `ConnectionController.kt:133-135` 的 `when` 分支出现；`EVENT_GAP(18)`、`SLOW_CONSUMER(17)`、`TOO_MANY_REQUESTS(14)`、`RESOURCE_EXHAUSTED(15)` 无任何生产代码引用（附录 A 可复核）。
- 规格要求：`docs/...:702`「事件：待发队列满时终止该订阅并保留终止原因，在可用控制通道发送 GAP / SLOW_CONSUMER」；`:728`「初始快照与控制消息不能丢弃…放不下初始快照时明确失败」。

**影响**：慢消费者或突发风暴下静默丢状态/丢事件，业务侧看到的是「值不再变化」而非「数据有缺口」，且 `droppedEventCount` 无任何对外出口（`dumpsys` 也不输出）。

**修复要求**
1. 事件/状态被丢弃时必须向该订阅投递 `KIND_GAP`（含 `fromSeq`/`toSeq`/`reason`）；若连 GAP 都放不下，则按规格终止该订阅并发送终止原因。
2. 初始快照必须保证不被丢弃：容量不足时明确失败（`RESOURCE_EXHAUSTED`），不得静默丢帧。
3. `droppedEventCount`/`coalescedPropertyCount`/在途队列长度纳入 `dumpsys` 诊断输出。
4. 所有 `enqueue` 调用点必须处理返回值。

**验收标准**
- 新增用例：容量 2、连续投 3 个事件 → 客户端在事件之间或之后必须收到一次 `SubscriptionGap`，且 `fromSeq/toSeq` 覆盖缺口。
- 新增用例：慢消费者场景下，订阅者最终能确认「有缺口」而不是错以为值稳定。
- 新增用例：`dumpsys` 输出包含每个订阅的队列长度与丢弃计数。

### P1-4 订阅初始快照与并发更新倒序（违反验收项 T06）

**证据**
- `SessionManager.kt:296-299`：先把 `clientSub` 注册进 `session.subscriptions`。
- `SessionManager.kt:302-325`：之后才 `stateStore.readAllSnapshots(req.keys)` 并按序入队。
- 两步之间任何 `broadcastPropertyUpdate`（`:330-355`）都会把**更新**排在**快照之前**；`DeliveryQueue.pollBatch` 在出队时分配 `deliverySeq`（`DeliveryQueue.kt:64-79`），所以客户端会先收到新值、后收到更旧的快照值。
- 规格：`docs/...:1090` T06「初始快照与后续更新无倒序、无初始化窗口漏更新」。

**影响**：客户端若按到达顺序覆盖本地状态，会看到值回退；对「首帧必须是最新基准」的 UI 是可见 bug。

**修复要求**
1. 在 `StateStore` 锁内一次性完成「取快照 + 建立订阅」的原子化（例如 `StateStore` 提供 `snapshotAndRegister(subscriptionId, keys)`，在同一临界区内产出快照帧并登记订阅，后续 update 才可能进入该订阅）。
2. 或客户端侧用 `revision` 丢弃旧帧（弱方案，需明确写进契约）。
3. 保持「不丢更新」的要求：原子化后新更新必须仍然投递给该订阅。

**验收标准**
- 新增用例（并发）：订阅与 update 竞争 1000 次，客户端收到序列中不存在 `revision` 回退；且不丢失订阅之后发生的更新。
- 新增用例：`replayLatest=false` 时不得出现快照帧。

### P1-5 同一订阅的消息可乱序投递

**证据**
- `SessionManager.kt:357-372`：`drainSubscriptionQueue` 每次都 `outboundExecutor.submit {...}`；任务在锁外循环 `session.callback.onSubscriptionMessage(msg)`（`:362`），批次处理完又递归 `submit`。
- `deliverySeq` 在 `pollBatch` 内分配（`DeliveryQueue.kt:69`），但发送在队列锁之外、由线程池 4~8 个线程并发执行 → 后一批（更大 seq）可能先到达。
- 规格 `:695` 要求 ACK 序号「在消息实际进入发送顺序时分配」，且 `:697` 明确「不要用 stateRevision 替代 deliverySeq」；当前实现下 deliverySeq 的单调性无法保证。

**影响**：`deliverySeq` 单调性承诺被打破，任何依赖它做缺口/顺序判断的逻辑（含未来的 ACK 协议）都不可靠；客户端可能看到属性值乱序。

**修复要求**
1. 每个订阅（`subscriptionId`）保证**只有一个在途发送者**：用「单飞标志 + 队列非空则续跑」的模式，或为每个订阅使用独立串行执行器。
2. 递归 `submit` 必须检查返回值并保证「只要队列有数据就一定会被再次驱动」（与 P0-3 合并处理）。
3. 端到端验证乱序：在客户端记录到达顺序，与 `deliverySeq` 必须严格单调一致。

**验收标准**
- 新增用例：高频产生 1000 条消息，客户端收到序列的 `deliverySeq` 严格递增，无重复、无回退。
- 新增用例：`session.callback` 短暂阻塞时，后续消息仍在同一订阅内保序。

### P1-6 `unwatch` / `resolveAndWatch` 不校验归属，可被他人注销或顶掉

**证据**
- `RegistryStore.kt:173-181`：`unwatch(watchId, callback)` 只按 `watchId` 从 map 移除，**不校验调用者 UID**；`RegistryService.kt:38-42` 虽然取到了 `callingUid`，但并未传入。
- `RegistryStore.kt:154-155`：`watchers[watchId] = watcher` 直接覆盖同 id 记录，旧记录的 `deathRecipient` 未 unlink（既泄漏，又会在旧 binder 死亡时用同一 `watchId` 调用 `unwatch`，**把新 watcher 误删**）。
- `watchId` 由客户端自选且可预测：`ConnectionController.kt:40` 用 `System.currentTimeMillis()` 起始并自增。

**影响**：任意应用可枚举/猜测 `watchId`（时间戳量级），注销他人 watch → 目标应用发现能力被静默破坏；或占用 id 后触发误删。

**修复要求**
1. `unwatch` 必须校验 `Binder.getCallingUid() == watcher.callerUid`（并在 AIDL 层把 `callback` 作为身份校验的一部分，或改用注册时下发的不可猜测凭证）。
2. `resolveAndWatch` 遇到已存在的 `watchId`：同 UID 重复请求按幂等处理（返回当前快照、不重建记录）；不同 UID 直接拒绝（`PERMISSION_DENIED`/`INVALID_ARGUMENT`），并先清理旧 `deathRecipient`。
3. `watchId` 改为服务端生成（由 `onSnapshot` 回传）或至少加入不可猜测随机量。

**验收标准**
- 新增用例：UID A 注册 watch，UID B 用同一 `watchId` 调 `unwatch` → 被拒绝，A 的记录仍存在。
- 新增用例：同 id 重复 `resolveAndWatch` 后，旧 callback binder 死亡**不会**移除新 watcher。
- 新增用例：跨 UID `resolveAndWatch` 同 id → 拒绝。

### P1-7 鉴权 fail-open + ACL 是死代码 + 注册中心权限等级错误

**证据**
- `PermissionPolicy.kt:50`：`val acl = aclRules[serviceId] ?: return // 若无特定 ACL 则允许通过`（发布放行）。
- `PermissionPolicy.kt:88-90`：`authorizeOperation` 同样在无 ACL 或白名单为空时 `return`（读写放行）。
- `registerAcl`（`PermissionPolicy.kt:23`）在整个工程**无任何调用点**（附录 A 可复核）→ 所有鉴权分支都是死代码；`RegistryService.onCreate`（`ipc-registry-app/.../RegistryService.kt:45-51`）也只构造了 `PermissionPolicy`，没注册任何 ACL。
- 注册中心权限等级：`ipc-registry-app/src/main/AndroidManifest.xml:6` 为 `protectionLevel="normal"`，而规格 `docs/...:1009-1011` 明确要求 `android:protectionLevel="signature"`。`normal` 权限安装即自动授予，任何应用声明 `ACCESS_REGISTRY` 即可绑定注册中心。
- 契约标志未生效：`readable` / `writable` / `observable`（`ipc-contract-api/.../Keys.kt:20-22`）除构造外**无任何读取点**；只读属性只要注册了 `onSet` 就能写，`observable=false` 的 key 仍会被推送。
- `ServiceSchema.findProperty/findEvent/findCommand`（`ServiceSchema.kt:21-23`）无调用点 → 契约层校验完全绕过。

**影响**：README「五·4 真实 UID 安全鉴权」只在「同 session 冒用」一个维度成立（`validateSessionOwner` 实现正确，见第 5 节）；服务发布、属性读写、能力发现没有任何有效管控。

**修复要求**
1. `ACCESS_REGISTRY` 改为 `signature`（并同步 README/示例 manifest），或在无 signature 诉求时按规格提供显式配置项。
2. 提供 ACL 落地路径：注册中心支持从资源/配置加载 `ServiceAcl`（至少覆盖 `allowedPublisherPackages` 与 `requiredPublisherCertSha256`），并给出集成文档；默认策略必须显式声明（「未配置 = 放行」需写入文档并打 WARN 日志）。
3. 落地契约校验：请求路径按 `ServiceSchema` 校验 `capabilityId` 是否存在、是否 `readable/writable`，`subscribe` 校验 `observable`；未通过返回 `UNKNOWN_CAPABILITY` / `READ_ONLY` / `CAPABILITY_NOT_SUPPORTED`。
4. `capabilities` 列表必须参与校验（当前只在 `dumpsys` 里打印）。

**验收标准**
- 新增用例：A 应用（无签名权限）绑定注册中心 → 失败；A 应用 `set` 一个 `writable=false` 的属性 → `READ_ONLY`；`get` 一个 `readable=false` 的属性 → `PERMISSION_DENIED`（或契约约定的错误码）；订阅 `observable=false` 的 key → 不产生推送。
- 新增用例：`set`/`call`/`get` 一个 `ServiceSchema` 中不存在的 keyId → `UNKNOWN_CAPABILITY`，业务 handler **不**被调用。
- 文档：ACL 配置示例 + 默认策略说明。

### P1-8 `ServicePublisher.close()` 无法真正下线服务

**证据**
- `CarIpc.kt:143-155`：`regToken` 只在 `IRegistryCallback.onPublished`（异步、oneway 回调）中赋值。
- `CarIpc.kt:190-193`：`close()` 依赖 `regToken?.let { registryConnector.unpublish(it) }` → token 为 null 时不发 unpublish。
- `RegistryConnector.kt:159-168`：`unpublish` 才会 `activePublishIntents.remove(...)`；token 为 null 时该意图残留 → `RegistryConnector.kt:196-205` 的 `restoreIntents` 会在注册中心重连后**重新发布已关闭的服务**。
- `CarIpc.kt:190-193` 的 `close()` 只调用 `sessionManager.closeAll()`；`EndpointHost` 仍作为 Binder 存活，`StateStore` 仍在，新客户端仍可 `openSession` 并正常 `get/set`。

**影响**：服务下线不生效 → 客户端连到一个「已关闭但仍在服务」的实例；注册中心出现幽灵注册（且会随重连反复复活）。

**修复要求**
1. `close()` 必须在「发布完成」后同步拿到 token（例如 `publish` 改为可等待的回调完成，或在 `RegistryConnector` 内部维护 token 与 intent 的原子绑定），并在 token 未就绪时**先撤销 intent 再等待/补发 unpublish**，保证不会复活。
2. `close()` 后 `EndpointHost` 必须拒绝新的 `openSession`（返回 `SERVICE_CLOSED`），并停止广播。
3. `close()` 幂等；关闭后不得再产生任何业务回调（规格 `:710`）。
4. `CarIpc.close()` 需保证 `publishers` 全部走完上述流程后再 `registryConnector.close()`。

**验收标准**
- 新增用例：`publishService()` 后立即 `close()`（早于 onPublished）→ 注册中心最终不存在该 serviceId，且注册中心重启/重连后也不会复活。
- 新增用例：`close()` 后新客户端 `openSession` → 收到 `SERVICE_CLOSED`（或连接被拒），不得成功建立可用会话。
- 新增用例：连续两次 `close()` 无异常、无重复 unpublish。

### P1-9 契约版本协商缺失与协议字段错位

**证据**
- `EndpointHost.kt:18`：只比对 `hello.transportMajor != transportMajor`；客户端发来的 `contractMajor/contractMinor`（`ConnectionController.kt:216-221`）**从未比对**。
- `EndpointHost.kt:36-46`：`ServerHello` 的第 6 个参数是 `sessionId`（`ipc-protocol/.../ServerHello.java:14`），实参传的却是 `serviceDescriptor.serviceId` → **sessionId 字段被填成 serviceId**。
- 客户端不读取 `ServerHello` 的任何协商字段：`ConnectionController.kt:48-82` 的 `onSessionOpened` 只取 `hello.serviceInstanceId`；`maxPayloadBytes`（`ServerHello.java:16`）与 `windowSize`（`:17`）全工程无读取点。

**影响**：契约主版本不兼容时会「连接成功但调用处处失败/错值」，而不是明确的 `VERSION_MISMATCH`；负载上限协商形同虚设（见 P2-5）。

**修复要求**
1. `openSession` 校验 `contractMajor` 相等（`minor` 按契约兼容规则处理），不兼容回 `VERSION_MISMATCH`（规格 `README.md:559` 已承诺）。
2. 修正 `ServerHello.sessionId` 传参（传真实 sessionId），并让客户端校验 `contractMajor/minor` 与自身契约。
3. 客户端把 `maxPayloadBytes` 用于发送前预检（超限直接 `PAYLOAD_LARGE`）。

**验收标准**
- 新增用例：契约 major 不一致 → `openSession` 被拒绝且客户端 `awaitReady` 收到 `VERSION_MISMATCH`（当前会成功建立会话）。
- 新增用例：`ServerHello.sessionId == sessionId（服务端生成）!= serviceId`。
- 新增用例：payload 超过协商上限 → 在客户端被拦截，不发出 Binder 调用。

---

## 3. P2 级（可靠性与资源治理）

### P2-1 `awaitReady` 用睡眠任务当定时器
- 证据：`ConnectionController.kt:271-278` 在 `outboundExecutor` 里 `SystemClock.sleep(timeoutMs)`；默认 `awaitReadyTimeoutMs = 5000`（`CarIpcConfig.kt:8`），会占用 4 个核心线程之一；`LinkedBlockingQueue`（`OutboundExecutor.kt:18`）为 FIFO，正常请求会排在睡眠任务之后。
- 附加：`submit` 被拒时 waiter 既不会超时也不会被移除；`close()`（`ConnectionController.kt:467-486`）不清理 `readyWaiters` → 泄漏 + 永久等待。
- 要求：改用 `ScheduledExecutorService`/`Handler.postDelayed` 做超时；`close()` 清理所有 waiter 并以 `SERVICE_CLOSED` 完成；`awaitReady` 被拒时立即错误回调。
- 验收：并发 10 个 `awaitReady`（永不就绪）时，正常 `get` 的延迟不受影响；`close()` 后所有 awaitReady 回调都在 100ms 内完成。

### P2-2 `RequestTracker.register` 不去重
- 证据：`RequestTracker.kt:25-28` 直接 `pendingRequests[requestId] = ...`；同 id 覆盖会遗弃前一个 callback（既不完成也不超时 → 永久泄漏）。
- 要求：requestId 必须全局唯一（UUID 已保证），重复注册要么拒绝要么以错误完成旧请求；`register` 返回句柄。
- 验收：同 id 注册两次，第一个 callback 必须以错误完成（或注册被拒绝），不得静默遗弃。

### P2-3 订阅生命周期与配额
- 证据：`SessionManager.kt:296-299` 同 `subscriptionId` 直接覆盖且不关闭旧 `DeliveryQueue`（规格 `:708` 要求「重复完全相同请求幂等返回当前状态，参数冲突返回错误」）；`SessionManager.kt:86-92` 的 `unsubscribe` 是唯一没有 `validateSessionOwner` 的方法（对比 `:75,82,89,100,105`）；`sessions`/`subscriptions` 无上限（`RESOURCE_EXHAUSTED` 未使用）。
- 要求：订阅去重/幂等 + 关闭被替换队列；`unsubscribe` 补 owner 校验；为 session 数与订阅数设上限并返回 `RESOURCE_EXHAUSTED`。
- 验收：重复订阅同 id 后旧队列被关闭（监控可见）；跨 session 调 `unsubscribe` 被拒；超限时明确报错。

### P2-4 空壳接口与死配置
- 证据：`SessionManager.kt:94-96`（`acknowledge` 空实现）、`:98-101`（`cancel` 空实现）、`:216-228`（`OP_GET_OPERATION` 恒返回 `CAPABILITY_NOT_SUPPORTED`）；`SubscribeOptions.windowSize`（`Callbacks.kt:29`）传到服务端后被忽略（`SessionManager.kt:297` 硬编码容量 128，`DeliveryQueue.kt:9`）；`CommandKey.retryPolicy`（`Keys.kt:188`）无任何读取点。
- 规格依据：`docs/...:58`「内部 AIDL 可仅实现首版所需方法；acknowledge 和扩展操作属于演进接口示例，**首版无需暴露空实现**」。
- 要求：要么实现（ACK/取消/操作跟踪），要么从对外 AIDL 与契约中移除，禁止以空实现/恒错返回对外暴露；`windowSize`、`retryPolicy` 同理（实现或删除并在文档说明）。
- 验收：对外接口清单与实际能力一致；不存在「声明支持但恒定失败」的 API。

### P2-5 `IpcPayload` 负载上限判定
- 证据：`IpcPayload.java:32` 用 `stringVal.length()`（UTF-16 字符数）而非 UTF-8 字节数；`:40` 对 `bundleVal` 完全不检查 → 大 Bundle 可触发 `TransactionTooLargeException`（进程崩溃），而 `PAYLOAD_LARGE(16)` 从未产生。
- 要求：统一按字节估算（String 用 UTF-8 长度，Bundle 用 `Parcel.obtain().dataSize()` 或显式尺寸限制），超限抛 `IpcError(PAYLOAD_LARGE)` 并由调用方转成回调；与 P1-9 的 `maxPayloadBytes` 协商值联动。
- 验收：超限入参在客户端被拦截为 `PAYLOAD_LARGE`；超大 Bundle 不会导致进程崩溃。

### P2-6 服务端不做 schema/契约校验
- 证据：`SessionManager.kt:128-243` 的 `handleRequest` 直接按 `req.capabilityId` 调业务 handler；`ServiceSchema` 的查找方法无调用点；`readable/writable/observable` 无读取点（详见 P1-7 第 3 点）。
- 要求：与 P1-7 合并实现（能力存在性、可读可写可观察性、线类型与范围校验）。
- 验收：见 P1-7。

### P2-7 `RegistryStore` 持锁调用远端 + 无配额
- 证据：`RegistryStore.kt:44`（`publish` 全程 `synchronized(lock)`）内调用 `callback.onPublished`（`:63,95`）、`onPublishFailed`（`:54,87,102,107`）、`:47` 的 `authorizePublish`（内部走 `PackageManager` 跨进程查询）、`:98` 的 `onServiceChanged`；`resolveAndWatch`（`:138-171`）持锁回调 `onSnapshot`（`:162`）；`onPublisherDied`（`:183-195`）持锁通知所有 watcher；`dump()`（`:226-255`）持锁且内部解析包名。
- 规格依据：`docs/...:823`「禁止持有内部锁时调用远端或业务 listener…先在内部锁下完成状态修改并取出不可变任务，再释放锁并提交发送」。
- 要求：锁内只做状态变更与任务收集，锁外投递远端回调；`dump()` 先快照再格式化；为 services/watchers 设 per-UID 配额（`TOO_MANY_REQUESTS`/`RESOURCE_EXHAUSTED`）。
- 验收：注册中心内 100 个 watcher 且某个 watcher 的进程卡住时，`publish`/`resolveAndWatch` 的 P99 不明显劣化；`dumpsys` 不会被长事务阻塞。

### P2-8 `publish` 替换旧记录时的 unlink/link 顺序
- 证据：`RegistryStore.kt:66-70` 先 `unlinkToDeath(旧)`，`:83-89` 再 `linkToDeath(新)`；后者抛 `RemoteException` 时 `return`，但 `services[serviceId]` 仍是**旧记录且已失去死亡清理能力**（僵尸注册）。
- 要求：先成功 link 新记录，再 unlink 旧记录并替换 map；失败时保持旧记录**仍然受死亡监听保护**。
- 验收：模拟 link 失败，旧发布者崩溃后注册项仍被正确清理。

### P2-9 `RegistryConnector` 重连路径不一致
- 证据：`RegistryConnector.kt:75-82`（`onServiceDisconnected`）只置 `isBound=false` 后 `scheduleReconnect`，不 `unbindService`；而 `onBindingDied`（`:84-94`）与 `onRegistryDied`（`:132-142`）都会先 unbind。配合 `ensureBound`（`:97-115`）的 `isBound/isConnecting` 双标志，存在重复绑定/不再触发 `onServiceConnected` 的风险。
- 要求：三条断开路径统一（unbind → 清 proxy → 退避重连），并对 `bindService` 返回值与重复绑定做幂等保护。
- 验收：连续杀死注册中心进程 10 次，SDK 每次都能重新连接并恢复 publish/watch（可用日志断言）。

### P2-10 `observe()` 静默丢消息
- 证据：`ipc-sdk-ktx/.../CarIpcKtx.kt:62-69` 的 `trySend(msg)` 未检查返回值；`callbackFlow` 默认缓冲 64。
- 要求：明确背压策略（`buffer`/`CONFLATED` 或按 `droppedEventCount` 暴露缺口），`trySend` 失败必须可观测（至少计数 + 日志，最好发 GAP 语义事件）。
- 验收：消费慢于生产时，collector 能感知缺口/丢包计数。

### P2-11 服务端 GET 用 `createString` 临时构造 key
- 证据：`SessionManager.kt:132` `stateStore.readSnapshot(PropertyKey.createString(req.capabilityId))`。该 key 的类型为 `STRING`，而 `readSnapshot` 会把该临时 key 放进返回的 `PropertySnapshot`；当前仅因类型擦除而不出错（`StateStore.kt:152` 的 `stored.value as? T` 不做运行期检查）。
- 要求：`StateStore.readSnapshot(keyId: String)` 重载或让服务端按 schema 查出真实 key（与 P2-6 的校验合并）。
- 验收：GET 返回的快照 `key` 必须是服务端注册的真实 key（含正确的 `ValueType`/`unit`/`min/max`）。

### P2-12 客户端 deadline 未做上限钳制
- 证据：`SessionManager.kt:112-126` 只判断 `now >= req.deadlineElapsedMs`；规格 `docs/...:595` 要求「服务端将客户端值限制在允许的最大等待范围」。
- 另：客户端 `get` 用 `SystemClock.elapsedRealtime()`（`ConnectionController.kt:286`），`set`/`call` 用 `TimeProvider.elapsedRealtime()`（`:336,376`），服务端用 `TimeProvider`（`:112`）——需统一到单调时钟基准。
- 要求：服务端对 deadline 做上限钳制；全链路统一 `elapsedRealtime()`，禁止墙上时钟参与超时判定。
- 验收：客户端传超大 deadline 时服务端按上限处理；`TimeProvider` 的 `currentTimeMillis` 回退路径不得用于跨进程超时判定。

### P2-13 示例在 main 线程抛异常会导致示例进程崩溃
- 证据：`sample-server/.../ClimateServerService.kt:48-50,59-61` 在 `mainHandler.postDelayed` 中调用 `climatePublisher?.update(...)`；越界值（如客户端 `set` 40℃）会让 `StateStore.update` → `key.validate` 抛 `IllegalArgumentException`（`Keys.kt:34-35`）在**主线程**未捕获 → 进程崩溃。
- 要求：示例必须演示正确的错误处理（handler 内校验 + `SetReceipt.rejected(IpcError(INVALID_ARGUMENT))`），并在 `update` 外层兜底；顺带验证 P0-4 的服务端异常边界确实生效。
- 验收：客户端发送越界值 → 收到 `REJECTED`/错误回执，服务端进程存活。

---

## 4. P3 级（工程与构建）

| ID | 证据 | 要求 |
|---|---|---|
| P3-1 | `gradle/wrapper/*` 与 `gradle/gradle/wrapper/*` 内容完全相同（SHA256 一致），且两份都被 `git ls-files` 跟踪 | 删除嵌套的 `gradle/gradle/` 目录，只保留根级 wrapper |
| P3-2 | `ipc-registry-app/build.gradle.kts:21`、`sample-server/build.gradle.kts:21` 的 release 用 `signingConfigs.getByName("debug")`；`sample-client/build.gradle.kts` 无 release 签名配置 | 统一签名策略：release 用独立 signingConfig（或明确标注示例用途），三个 APK 保持一致 |
| P3-3 | `ipc-registry-app/.../AndroidManifest.xml:14` 与 `sample-server/...:14` 的 `android:persistent="true"`（对三方 APK 无效，需系统应用/预置）；`RegistryService.kt:50,59-84` 自行拉前台服务 | 按规格 `docs/...:1023`「是否预装、开机启动或采用产品服务管理策略，应由集成层决定，不由 SDK 擅自启动无关前台服务」整改：移除 `persistent`，把 FGS/开机自启改为可配置的集成层行为；README 相应更正 |
| P3-4 | 各 `build.gradle.kts` 的 `targetSdk=30`/`jvmTarget=1.8` 与 `README.md:46-49` 的 JDK 11 / buildTools 35 / compileSdk 33 不一致 | 对齐文档与配置；说明 targetSdk 30 的取舍（FGS 类型、后台限制） |
| P3-5 | `IpcLog.kt:13` `isDebugEnabled = true`，无 BuildConfig 判断；日志含 UID/包名/业务值（`SessionManager.kt:43,74,259`） | release 默认关闭 debug 级别，或按 BuildConfig/属性开关；敏感值脱敏 |
| P3-6 | `PermissionPolicy.kt:31-38` 取 `getPackagesForUid(uid)` 第一个包（规格 `:899` 明确要求高权限接入拒绝 shared UID 组合）；`hexStringToByteArray`（`:133-142`）对奇数长度会 `ArrayIndexOutOfBounds` 并被 `catch` 静默吞成 false | shared UID 显式判定并拒绝/告警；hex 解析做长度与字符合法性校验 |
| P3-7 | 测试基线：`EndToEndIpcTest` 同进程直调 `EndpointHost.openSession`/`createSession`（`:101,155`），不经真实 Binder；`ipc-runtime/build.gradle.kts:25` 与 `ipc-sdk/build.gradle.kts:25` 的 `returnDefaultValues = true` 让 `SystemClock.elapsedRealtime()`、`Binder.getCallingUid()` 返回 0 | 补 Binder 级（Instrumentation 或双进程）用例：超时、取消、重连、重发布、死亡清理、队列溢出/GAP、慢消费者、CAS 并发、类型不符、契约版本不兼容 |

---

## 5. 明确不要改动的部分（避免误伤）

以下是评审确认**正确**的设计，重构时不要回退：

1. **分层与依赖方向**：`contract-api`(纯 JVM jar) ← `protocol` ← `runtime` ← `sdk` ← `sdk-ktx`；`registry-app` 仅依赖 `runtime`。保持单向。
2. **P2P 直连模型**：注册中心只做寻址，不转发生意数据（`RegistryService`/`EndpointHost`）；中心崩溃不影响已建会话。
3. **防抢注机制**：`RegistryStore` 的 `generation` + `RegistrationToken` + 「同 UID 才能替换实例」（`RegistryStore.kt:49-70,113-129,183-195`），以及 `onPublisherDied` 里 `generation` 与 Binder 双重比对。这是正确且必要的，不要简化。
4. **`validateSessionOwner`**（`PermissionPolicy.kt:40-47` + `SessionManager.kt:75,82,89`）：会话建立时捕获 `callingUid` 并逐请求比对，防会话冒用，实现正确。
5. **死区不污染权威值**（`StateStore.kt:69-76`）：过滤只影响通知，不影响 `StateStore`，符合规格 5.4。改动限频逻辑时不得破坏该性质（`StateStoreTest.testDeadbandFilteringUpdatesStateStoreEvenWhenFiltered` 必须继续通过）。
6. **oneway + callback 的异步模型**与「锁内不调远端」在 `SessionManager` 的发送路径（`sendResponse`/`drainSubscriptionQueue` 通过 `outboundExecutor` 投递）是正确方向；问题只在 `ConnectionController` 与 `RegistryStore` 等其它位置没做到（P1-5/P2-1/P2-7）。
7. **`DeliveryQueue` 的同 Key 状态合并**（`DeliveryQueue.kt:26-40`）与「快照帧不参与合并」（`snapshotId != null` 判定）语义正确，保留（但需补 GAP 与返回值处理）。
8. **`IpcLog` 统一前缀与 `dumpsys` 诊断**（`RegistryService.dump`）方向正确，只需补指标（P1-3）。

---

## 6. 建议的提交切分（每步独立可验证）

| 步骤 | 内容 | 完成判据 |
|---|---|---|
| 1 | P0-1 + P0-3：超时调度器 + `submit` 失败传播 | 请求在无响应/被拒时都能在时限内收到错误回调；`pendingRequests` 为空 |
| 2 | P0-2 + P0-4：编解码前置 + 服务端统一异常边界 | 编码异常与服务端业务异常都变成明确的错误响应，无静默挂起 |
| 3 | P0-5 + P0-6 + P1-9：类型标签校验 + 契约版本协商 + `ServerHello` 错位修正 | 参数化类型不符用例全部报 `TYPE_MISMATCH`；null 返回值正常成功；版本不兼容被拒 |
| 4 | P1-1 + P1-2：writeToken 生命周期 + 限频到期重发 | CAS 连续两次成功；限频场景最后一个值必达 |
| 5 | P1-3 + P1-4 + P1-5：GAP 通知 + 快照原子化 + 单订阅串行投递 | 丢包可感知；无倒序；`deliverySeq` 严格单调 |
| 6 | P1-6 + P1-7 + P1-8：watch 归属校验 + ACL/signature 权限 + 契约校验 + `close()` 真正下线 | 跨 UID 攻击用例被拒；无签名应用无法绑定注册中心；关闭后不复活、不再服务 |
| 7 | P2-* 与 P3-*：资源治理、配额、诊断指标、构建与文档对齐 | 各项验收标准 + README 与实现一致 |

**交付纪律（强制）**
- 每个 P0/P1 缺陷必须附带**可失败的回归用例**，且用例要在修复前先跑出红灯（证明它能捕获该缺陷）。
- 禁止以下「糊法」：`catch (Exception) {}` 空吞；用 `awaitReady` 的 sleep 兼职做超时扫描；靠放大队列容量掩盖丢包（必须让丢包可感知）；用 Mock/同进程测试替代 Binder 级验证；为通过验收而只改测试不改实现；把 `TIMEOUT` 直接删掉而不实现调度器。
- 每步完成后更新 `README.md` 与实际行为一致的描述；与规格冲突时**以规格为准**，若确需偏离，必须在 README 中写明偏离原因与影响。

---

## 附录 A：复核命令（可用于自查每一条结论）

```powershell
# P0-1：确认 checkTimeouts 在生产代码中无调用点
# 期望：仅 RequestTracker.kt 的定义 + RequestTrackerTest.kt 的调用
rg -n "checkTimeouts" ipc-runtime ipc-sdk ipc-registry-app

# P1-3 / P2-4：确认错误码与 GAP 在生产代码中从未被产生
rg -n "KIND_GAP|SubscriptionGap|EVENT_GAP|SLOW_CONSUMER|TOO_MANY_REQUESTS|RESOURCE_EXHAUSTED|PAYLOAD_LARGE|OPERATION_EXPIRED|STALE_INSTANCE|DATA_UNAVAILABLE" --glob "*.kt" --glob "*.java"

# P1-7：确认 ACL 是死代码、契约标志从未被读取
rg -n "registerAcl" --glob "*.kt"
rg -n "\.readable|\.writable|\.observable|findProperty|findCommand|findEvent" --glob "*.kt"

# P2-4：确认死配置
rg -n "retryPolicy|windowSize" --glob "*.kt"

# P1-9：确认 maxPayloadBytes / contractMajor 从未被客户端读取
rg -n "maxPayloadBytes|contractMajor" --glob "*.kt"

# P3-1：确认重复 wrapper 均被跟踪
git ls-files gradle

# 基线：构建与测试
.\gradlew.bat --offline :ipc-runtime:test :ipc-sdk:test :ipc-sdk-ktx:compileDebugKotlin
Get-ChildItem -Recurse -Filter "TEST-*.xml" -Path ipc-runtime\build\test-results,ipc-sdk\build\test-results
```

**测试基线（评审时）**：`DeliveryQueueTest 3 / EndToEndIpcTest 2 / RegistryStoreTest 1 / RequestTrackerTest 2 / StateStoreTest 4 / JavaApiCompatibilityTest 1`，全部通过、0 failure、0 skipped。
