# Local SOCKS5 / mixed stall investigation — 2026-08-30

## 1. Architecture trace

调查基线：Android 工作区为 main；原 `bridge.lock`、AAR metadata 和相邻
`../tcptun-go` HEAD 都是 `dd93a24dd1f4e7fa36478626490393dd7748cd18`。
`TCPTUN_GO_DIR` 未设置，构建脚本使用相邻 checkout。先完成只读追踪并输出调用链，之后才添加复现测试和修复。
下列 Kotlin 路径相对 `app/src/main/java/com/gostartkit/tcptun/`；Go 路径相对 `../tcptun-go/`。

| 层 | 实际 file:function | 行为 |
|---|---|---|
| 设置 | `RuntimeSettings.kt:RuntimeSettingsRepository.localSocksListenAddress` | `socksListenAll` 选择 `0.0.0.0` / `127.0.0.1`，附加 socksPort；connect address 始终 loopback |
| 应用设置 | `RuntimeSettingsRuntimeState.kt:publishFreshRuntime/rebindAppliedOwnership/reconciliationAction` | 已应用设置与物理 bridge epoch 绑定；结构变化 Replace，hot setting 另行 checkpoint |
| 构造配置 | `VpnServiceIntents.kt:buildStartPayload` | 将 listen、protocol、users、routeLocalProxyTraffic 传给 plan |
| 多配置 | `ProfileRunPlan.kt:ProfileRunPlan.toBridgeJson` | 调用各 AppConfig，取一个 local inbound，合并 native/direct/balance outbounds |
| JSON | `AppConfig.kt:toBridgeJson` | 仅从结构化 Profile 生成受控 runtime JSON 和本地 `socks5`/`mixed` inbound |
| 服务启动 | `TcptunVpnService.kt:startRuntimeNow/startBridge` | runtime lease → 原始 Android TUN FD → Bridge session → applied settings → 发布 Running |
| JNI 事务 | `BridgeSessionRuntime.kt:startSession/startNativeSession` → `BridgeSessionController.kt:start` | 安装强引用 callbacks；Configure；SetTun；Start；等待 core_ready；记录 exact session ID |
| 反射 | `TcptunBridge.kt:ReflectionTcptunBridge.configure/setTun/start` | 调用 Engine.configure/setTun/startConfiguredSessionWithDisabledOutbounds |
| native | `mobile/androidbridge/androidbridge.go:Engine.Configure/SetTun/startConfigSession` | Configure 保存严格 FileConfig；SetTun dup FD；创建 tun.Inbound 作为 PlatformInbounds，传入 RuntimeOptions |
| 编译 | `runtime_compiled.go:NewRuntimeFromConfig/newCompiledRuntime` | socks5/mixed 都创建 `newConfiguredMixedInbound`；socksOnly 标志限制 HTTP |
| bind/accept | `inbound_mixed.go:mixedInbound.Prepare/bind/Serve` | net.Listen(tcp, addr)，一条 accept loop；每连接 `activeConnTracker.Go` |
| 协议 | `inbound_mixed.go:handleConnError/handleSOCKS5/handleHTTP` | Peek 首字节、SOCKS auth/request、HTTP headers/auth；TCP 握手期限已有 10 秒 |
| 路由/转发 | `runtime_compiled.go:compiledInboundHandler.HandleStream/HandlePacket` | selectStream/selectPacket → Primary.DialContext → SOCKS/HTTP success reply → TCP relay 或 UDP association |
| TUN | `runtime_endpoint_bridge.go:platformEndpointHandler.HandleStream/HandlePacket` | TUN 不经 local accept loop，使用独立 platform inbound；最后共享 route/outbound |
| 外部 socket | `mobile/androidbridge/androidbridge.go:Engine.protectSocket` → Android `BridgeSessionServicePort.protectSocket` → `VpnService.protect(fd)` | 保护 outbound FD；`buildTun` 还排除 App UID；没有 per-socket Network.bindSocket |
| underlying | `UnderlyingNetworkCoordinator.kt:createCallback/update/publish` → `UnderlyingNetworkRuntime.kt:applyUpdate` | INTERNET/NOT_VPN 网络排名；setUnderlyingNetworks；可用网络替换才计划 handover recovery |
| recovery | `TcptunVpnService.kt:requestBridgeRestart/scheduleBridgeRestart/prepareBridgeRestart/continueBridgeRestart` | service/token/epoch/recovery generation fencing；1.5 秒 settle，已有 30 秒 restart cooldown；串行 stop/close/新 TUN/start |
| cleanup | `BridgeResourceLifecycle.kt:BridgeResourceStateMachine.beginStop` + `BridgeSessionStopController.kt:stop` | 先废止 epoch；Stop/WaitStopped(exact ID)/Abort；native 未释放不得关原 TUN、清 callbacks、释放 lease |
| 回调 | `ServiceLifecyclePolicy.kt:CallbackEpochGate/DeferredServiceStopGate`、`TcptunState.kt:applyBridgeStatusEvent` | 排除旧 epoch、旧 session、不递增 sequence 和旧 lifecycle cleanup |

`Runtime.Run`（`runtime.go`）确实监督所有 inbound：Serve 返回（包括意外 nil）或 panic
会触发 cancel 和组件清理。`Engine.startConfigSession` 等 Run 返回后发布 CORE_RUNTIME_FAILED/EXITED。
**不能把这个版本解释成“listener goroutine 一退出，父 runtime 就永久无感”。**
启动 ready 也等待所有 inbound，不只是 TUN。真正有问题的路径没有退出 Serve。

## 2. Ranked hypotheses

“概率”是相对排查优先级，不是未知现场样本的统计概率。

| 假设 | 概率/优先级 | 支持证据 | 反证/限制 | 如何证伪或进一步验证 |
|---|---|---|---|---|
| H1：accept 不再处理新连接 | 高；临时错误退避路径已复现 | mixed/socks 共用 Serve；临时 accept 错误退避从 1s 到 1h，无日志/状态；队列仍允许 TCP connect | 真正 Serve exit/panic 被 Runtime supervisor 捕获，不能支持“永久孤儿 listener”说法 | 故障时抓 accept_stalled、errno、LISTEN、greeting；确认无此分支且 accept 持续正常可否定这一具体路径 |
| H2：Android 健康盲区 | 已确认 | 旧 shouldProbeLocalProxy(false)=false；canConnect 只做 loopback TCP；健康时 next delay=null；status callback 不等于 accept activity | 前台强制 upstream 或真正 fatal callback 可发现部分问题 | 后台注入 listener 无响应，并断言 monitor 是否进行 B 层检测和确认；用外部 LAN client 验证 E |
| H3：旧 ownership 误清理新 session | 低到中，未复现 | 网络切换和重启时有复杂控制面；原测试可能在 restart settle 前检查旧 Running | 串行 lifecycle executor、lease、epoch/token/recovery fencing、exact WaitStopped，现有注入测试及真实启停通过 | handover 后必须等待新 epoch ready 再请求；旧回调/旧任务竞争压力、物理 OEM 设备进一步覆盖 |
| H4：FD/资源压力 | 作为触发条件可信；持续泄漏未确认 | EMFILE/ENFILE 的 Go Temporary 分类进入退避；4096 active connection limit 大于部分设备 FD 预算；TUN 既有 FD 不依赖 accept | 本次几百次请求、慢连接、重复启停没有单调 FD 增长；不是制造真实全进程 EMFILE | 故障当下采 fd/task/tcp/udp，按每轮结束基线比较；找资源所属者，不能只看平均值 |
| H5：mixed sniff/slow client starvation | 低（已测试规模内） | 连接上限到达时新连接会被拒绝；大量客户端仍可能造成压力 | 每连接 goroutine；已有 10s handshake deadline；active tracker deferred cleanup；两种协议各 96 个慢客户端未饿死正常请求 | 更高并发和故障客户端持续输入，UDP associate/RST/长期运行资源基线；不能以本次样本排除所有 DoS |
| H6：JNI/bridgeLock 卡住 | 可能的独立失效域，未确认现场发生 | LockedHealthBridgePort、LockedTcpingBridgePort 和 start/stop 共用 Java monitor；等待锁不可中断；timeout 参数不约束整个 JNI 调用 | native outbound probe 有自身 timeout；并发/合约测试未出现永久卡死 | `bridge_control` waiting_lock 无 entered = 锁争用；entered 无 returned = native 调用未返回；同时采支持的线程 dump |

另外，managed routes 默认只匹配 tun；local traffic 的 routeLocalProxyTraffic 默认关闭。
因此即使两个入口正常，TUN 直连规则生效而 local 走故障 balance/outbound，也能产生 C/D 层差异。
本次没有现场配置/抓包，不能排除这一条。底层 NETWORK_INTERNET callback 不是 tethering/下游接口监视器，
仅 hotspot IPv4 变化未必产生 handover 事件。

## 3. Confirmed root cause

**已确认的代码失效路径：local accept 重试错误地复用了最长一小时的 outbound/mux 退避。**

旧版 `mixedInbound.Serve` 在 `net.Error.Temporary()` 后 sleepContext，默认等待
1、2、4、8…3600 秒。只在成功 accept 后清零；资源已释放并不会中断睡眠。
该分支既不关闭 listener，也不退出 Serve，也不产生状态事件：

1. TUN/platform inbound 和既有 outbound 流可以继续运行。
2. listen socket 的内核 backlog 仍可接收连接：TCP connect 成功不代表应用已 Accept。
3. SOCKS greeting / mixed HTTP 停在队列，表现为 B；队列满后表现为 TCP 超时。
4. Engine.Status 是生命周期状态，StatusJSON 未包含 accept loop 的运行/退避字段，因此仍可能 Running。
5. 后台不探测；前台 TCP-only probe 可能假阳性；昂贵 upstream probe 又仅 UI forced。

三次模拟 EMFILE 后，旧代码还睡 4 秒：两种协议均在 1 秒 greeting 截止前失败；
同一真实 Runtime 的另一个 inbound 仍正常，Snapshot 为 running。
这验证的是失效机制，**不是设备上完整 TUN + EMFILE 的同场复现**。

**用户实际低频事故的最终根因：`not yet confirmed`。**
没有故障现场的 errno、FD 基线和流量证据，不能声称已定位是谁造成资源压力，
也不能认定每种“拒绝连接”都来自该退避（该路径本身保留 LISTEN）。

## 4. Reproduction

Native regression：`inbound_accept_recovery_test.go:TestLocalInboundRecoversPromptlyAfterAcceptResourcePressure`。
使用现有 newTestRuntime/runRuntimeAsync/waitRuntimeReady 帮助器、真实 TCP listener 和 Runtime。
仅将第一个 listener 的 Accept 包装为前三次返回标准 `net.OpError(os.SyscallError(EMFILE))`，
其后正常 Accept。不降低测试进程 RLIMIT，不耗尽宿主机 FD。两条真实 inbound 中另一条作为独立入口对照。

```sh
cd ../tcptun-go
go test -run TestLocalInboundRecoversPromptlyAfterAcceptResourcePressure -count=1 -v .
go test -race -run 'TestLocalInboundRecovers|TestMixedInbound|TestRuntimeCriticalInbound' -count=10 .
```

修复前：2/2 子案例确定性失败，约 8 秒完成，signature：
`level B: TCP connected but SOCKS greeting stalled after resource pressure cleared: ... i/o timeout`。
修复后：初轮两种协议约 21ms 完成；10 轮 race 回归中的全部子案例通过。
现场触发概率未知。复查旧版应在隔离 checkout 上只应用回归测试，不能先带入修复。

Android instrumentation 扩展现有 MixedProxyAuthenticationAndroidTest：每种协议 400 次
CONNECT/echo，20 轮 native stop/start；mixed 同时交替 HTTP CONNECT；保留原认证成功/失败、HTTP GET
和 Proxy-Authorization 不转发测试。96 个 silent/单字节/半 HTTP header 客户端并发，正常请求 <3 秒完成，
10.5 秒后确认服务端实际关闭慢连接。wildcard 测试探测设备非 loopback IPv4 并转发 echo。

## 5. Patch

Native branch `codex/local-listener-accept-recovery`，commit
`5fea14bb57f997303044d1bbd7826a3eca2a2620`（本地已提交，未推送）。

- `inbound_mixed.go`：accept 专用 5ms 初始、1s 上限，保留原取消语义和 fatal supervisor；不改变
  outbound/mux backoff、TUN、路由或 FD ownership。新增 accept_stalled/resumed/stopped 原生日志，
  含 tag、bind、generation、accepted、accept_errors、retry_delay、active/uptime；持续错误按 2 的幂次记录以控制日志量。
- `inbound_accept_recovery_test.go`：回归测试覆盖 socksOnly=true/false，另一个 inbound 不受影响。
- Android `bridge.lock`、bridge 文档与合约 identity 断言更新；从干净锁定 checkout 重建原有三种 ABI。
  **gomobile 导出签名、Bridge API 3、profile/wire 格式均未改变。**
- `LocalProxyHealthProbe.kt` / `Socks5Client.kt`：分开 TCP、SOCKS auth、本地请求响应、outbound、transfer。
  listener check 在认证后发送不支持的 command 0x09，要求 RFC 1928 REP=7，绝不发送 CONNECT 或进行 DNS/Internet 访问。
  这样不会因认证后主动 EOF 在 debug core 上产生 connection error 回调并反过来触发健康检查。
- `BridgeHealthRuntime.kt` / `BridgeHealthPolicy.kt`：本地探测不再依赖 UI；事件优先 + 5 分钟本地 safety timer；
  失败沿用 15 秒确认和已有 recovery，不加定时重启、不强制 Internet probe。
  sockets 检查在 JNI reconciliation 之前；socksListenAll 时额外检查至多八个设备 IPv4 地址。
  E-only failure 仅诊断，不用重启 TUN 掩盖接口/防火墙问题；设备自访成功标记 remote_unverified。
- `TcptunVpnService.kt`：core_ready 后、发布 Running/恢复 connectionsReady 前实际检查本地握手。
- `ServiceCoordination.kt` / `BridgeSessionRuntime.kt` / `TcptunState.kt`：关联 service/runtime token/lifecycle generation/
  bridge epoch/native session/status sequence/network/protocol/listen；稀疏诊断脱敏后在后台写 logcat。
- `VpnBridgePorts.kt`：记录 waiting_lock/entered/returned 与调用 ID、等待/总耗时，区别控制面和 listener 故障。
  **没有用取消 Future 来假装可以强制中断 JNI。现有 Java monitor/未知 native 卡死仍不是硬超时。**
- Android tests：增强既有认证、handover、runtime、resource-cycle harness，验证新请求/echo/逐轮 FD baseline，
  handover oracle 等待 replacement epoch，不能把 settle/cooldown 内旧 Running 当作新 listener ready。
- `scripts/capture-local-proxy-incident.py`：并行、有 10 秒单项上限的只读现场采集，不清日志、不重启、不发信号。

健康探测存在几个限制：定时器不是 Doze 唤醒锁；最长发现时间并非硬保证；前台/网络事件通常更早发现。
SOCKS probe 证明 shared accept/SOCKS/auth/request 分支，不单独证明 mixed 的 HTTP parser；HTTP 路径由 instrumentation/
现场 HTTP CONNECT 验证。原生 detailed 日志受 log level/callback 可用性限制，未增加公开 listener diagnostics ABI。

## 6. Tests

实际执行（Android 模拟器 Pixel_10 / Android 17、arm64；宿主 Darwin arm64 Go 1.26.4）：

| 验证 | 结果 |
|---|---|
| 旧 native + 新故障注入 | socks5/mixed 2/2 按预期失败；TCP 成功、greeting 超时、另一入口正常 |
| 最终提交的 `go test ./...` | 全部通过；包含真实 mobile/androidbridge、TUN、UDP/transport；根包 105.387s |
| `go test -race` 定向重复 10 轮 | 全通过；root package 5.708s |
| `bash scripts/check-maintainability.sh` / `:app:lintDebug` | 均通过；lint 0 errors、50 warnings（未把本次调查扩展为全部警告清理） |
| 最终 `:app:testDebugUnitTest` | 498 tests，0 failures/errors/skipped；包括 health、handover、lifecycle、锁等待阶段、配置生成 |
| 严格 `:app:verifyAndroidBridge` | 通过；clean=true，core=5fea14b，API 3；armeabi-v7a/arm64-v8a/x86_64 三种 AAR native 库 |
| 最终真实 AAR Android suite | 63 tests，0 failures/errors/skipped：MixedProxyAuthenticationAndroidTest、AndroidBridgeContractTest、ResourceCycleValidationTest、rapid lifecycle matrix |
| 单独完整 handover | 1 test 通过，0 skipped；加入 emulator saved-AP reconnect 后完成 Wi-Fi→cellular→Wi-Fi；替换 epoch 3/7 各通过 SOCKS+echo，中间 profile replacement epoch 5 |
| SOCKS5/mixed endurance | 各 400 请求、20 restart；最终每轮 stopped FD socks5=116–122、mixed=116–120，线程均 25 |
| slow-client | 各 96 连接；正常请求 <3s，10.5s 后所有 stalled 客户端服务端关闭 |
| wildcard/device interface | 非 loopback IPv4 SOCKS auth/request+echo 成功；不是远端 hotspot client 证明 |
| VPN/TUN start/stop 20 cycles | 每轮 Running 做 proxy auth+echo；stopped FD 第一/最后 5 轮中位数均 125；初始 idle=115，末轮=123；线程在第 7 轮后稳定 45（initial idle=36），不宣称零分配/零泄漏 |
| 现场采集脚本 | 正常完成，service/FD/thread 等可读；4 个 `/proc/PID/net/*` 被 SELinux 拒绝，manifest 如实记录；另验证 process absent 时拒绝继续 |

中间失败没有忽略：第一次合约测试暴露写死的历史 coreBuildID=017b9270d99d，更新为锁定实现 ID 后通过；
两次 Wi-Fi 回程测试因模拟器只启用 Wi-Fi、未重新关联 AP 而超时。只在 sdk_gphone 的显式 opt-in
`runtimeStressEmulatorWifiReconnect=true` 测试模式中重连已保存 AndroidWifi，生产代码未加入此行为。
随后 handover 使用新 epoch oracle 通过。这里不包含真实 OEM、hotspot 客户端或 TUN/EMFILE 同场复现。

复跑最终设备套件：

```sh
./gradlew :app:connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=com.tcptun.client.MixedProxyAuthenticationAndroidTest,com.tcptun.client.AndroidBridgeContractTest,com.tcptun.client.ResourceCycleValidationTest,com.tcptun.client.VpnRuntimeStressTest#rapidLifecycleCommandMatrixPreservesRuntimeOwnership \
  -Pandroid.testInstrumentationRunnerArguments.runtimeStressEnabled=true \
  -Pandroid.testInstrumentationRunnerArguments.resourceCycleEnabled=true \
  -Pandroid.testInstrumentationRunnerArguments.resourceCycleCount=20

./gradlew :app:connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=com.tcptun.client.VpnNetworkHandoverStressTest#wifiCellularRoundTripAndCallbackDuringStopPreserveOwnership \
  -Pandroid.testInstrumentationRunnerArguments.runtimeStressNetworkControl=true \
  -Pandroid.testInstrumentationRunnerArguments.runtimeStressEmulatorWifiReconnect=true
```


## 7. Remaining risks and incident procedure

- OEM Android、Doze、真实热点 NAT/防火墙、IPv6 wildcard 监听未完成设备覆盖。
- 已有 native UDP 协议测试执行，但未完成 Android UDP ASSOCIATE 长期异常断网/复用压力。
- 96 个慢连接和短期几百次请求不是持续数天或接近 4096 上限的恶意客户端压力。
- 没有确认真实 EMFILE 来源；不声称修复所有 socket/goroutine/dup TUN FD 泄漏。
- TUN 访问必须由 VPN 覆盖的另一 UID/应用发起，不能用被排除的 App 自己的 Socket 作为证据。
- `Engine.StatusJSON()` 仍没有 per-listener 原子诊断字段；JNI 停滞时看控制面 phase、线程栈和独立 socket 检查。
- native 新提交未推送；正常 release preflight 会拒绝远端尚不存在的 pin。必须先按项目发布流程发布 native commit，
  不能设置 ALLOW_UNPINNED_BRIDGE 绕过生产验证。

发生故障先采集，不手动重启：

```sh
python3 scripts/capture-local-proxy-incident.py --serial DEVICE_SERIAL \
  --package com.tcptun.client.debug --output /tmp/tcptun-incident-UNIQUE
```

采集 service/connectivity/VPN、interfaces/routes、FD symlinks、thread 数、进程状态、meminfo、
/proc/PID/net/{tcp,tcp6,udp,udp6}、当前 PID 的脱敏 TcpTun logcat，manifest 记录每项时间和权限失败。
/proc/net 是网络 namespace 视图，不全是该进程的 socket，需用 fd symlink inode 交叉匹配。
SELinux 拒绝是 unknown，不能据此判定 LISTEN 不存在。导出 App Diagnostics 的 Engine.status/statusJSON/
outbounds 另存；脚本不启动 instrumentation，避免销毁待调查 session。
需要线程栈时使用设备支持的 debuggerd/调试器；**不要直接给含 Go runtime 的进程发送 SIGQUIT/kill -3**。

| 层 | 下一步证据 | 不可误判 |
|---|---|---|
| A | 当前代理地址 TCP refused/timeout + inode 对应 LISTEN、FD/errno | 单纯 timeout 不能证明 listener 不存在；可能队列满或网络不达 |
| B | TCP 成功但 SOCKS greeting/auth/request 或 HTTP header 无响应 | TCP connect 只是内核队列成功，可能 accept backoff |
| C | SOCKS 非零 CONNECT reply / HTTP CONNECT 502/504；检查选中 outbound/route | TUN 规则可能走 direct，而 local 走另一个 outbound |
| D | CONNECT 成功后向受控 echo origin 发固定数据，双向字节不一致/停滞 | 不把“完成 CONNECT”当作数据转发正常 |
| E | 同时从设备 loopback、设备自有 Wi-Fi/hotspot 地址、外部 LAN client 请求 | 设备自访成功不证明外部防火墙/NAT/客户端路由正常 |

比较一次故障的 service_id/runtime_token/lifecycle_generation/bridge_epoch/native_session/status_seq/
restart token/network，并记录 UI visible、设备 uptime、网络开关、每轮 FD baseline。
当前脚本不会自动获取线程栈、Go goroutine 数或替用户运行外部 LAN 请求；这些缺项必须保留在事故记录中。
