# 通话集成与状态上报修复（2026-10-19）

> 后续实测确认 Eyewear 2 在 SCO 期间不发送可用的双击事件，因此最终纯通话流程改为“单次双击启动 + 前滑结束/上传”。当前有效流程见 [Eyewear 2 纯通话模式：双击启动 + 前滑结束/上传](bluetooth-call-only-voice-flow-2026-10-19.md)。本文保留的是旧 Telecom 路径的地址、状态流和回调修复记录，仍适用于媒体按键/Telecom 模式。

本轮只处理 HUAWEI Eyewear 2 的经典 HFP/SCO 通话控制，不扩大权限，也不改变媒体按键、前滑结束和手机端操作入口。代码已编译并通过单元测试；硬件双击仍未在本轮完成验收。

## 1. 通话地址：默认改为数字标识

旧代码使用：

```kotlin
Uri.fromParts("voip", "ideavox-bluetooth-recording", null)
```

Android 的 HFP CLCC 响应会在 `BluetoothInCallService.sendClccForCall()` 中取 URI 的 scheme-specific part，然后由原生 `btif_hf.cc` 的 `ClccResponse()` 逐字符过滤：只保留拨号字符，字母会被丢弃。纯字母地址因此可能以空号码发给眼镜。参考 [AOSP BluetoothInCallService.java](https://android.googlesource.com/platform/packages/apps/Bluetooth/+/d4ccf4691d5f29bb0c54013c360923a8dbf358bb/src/com/android/bluetooth/hfp/BluetoothInCallService.java) 与 [AOSP btif_hf.cc](https://android.googlesource.com/platform/packages/modules/Bluetooth/+/refs/heads/main/system/btif/src/btif_hf.cc)。

现在的默认地址是 `voip:5550100`。它仍然属于本应用的 `CAPABILITY_SELF_MANAGED` 本地 VoIP 账号，不会拨运营商电话。地址定义集中在 `BluetoothCallIdentity`，并有单测复现“字母地址过滤为空、数字地址保留”。

Debug 对照开关：

```bash
# 默认数字地址（也是当前默认值）
adb shell am broadcast -n com.vibecodingjapan.ideavox/.BluetoothTestCommandReceiver \
  --es command numeric-call-address --ez enabled true

# 临时切回旧的字母地址，用于 A/B 对照
adb shell am broadcast -n com.vibecodingjapan.ideavox/.BluetoothTestCommandReceiver \
  --es command legacy-call-address --ez enabled true
```

开关只在未通话、未录音时生效。日志会打印实际地址和过滤后的 HFP 号码：

```text
IdeavoxBtTelecom: requested call session=1 ... address=5550100 hfpDialable=5550100 legacyAlpha=false ...
IdeavoxBtConnection: call dialing session=1 address=voip:5550100 hfpDialable=5550100 ...
```

## 2. 状态流：拨号中 → 蓝牙路由 → 已接通

旧代码在 `onCreateOutgoingConnection` 内连续执行 `setDialing()` 和 `setActive()`，然后才请求蓝牙路由。现在改为：

1. `setAddress()`；
2. `setDialing()`，让 HFP 先看到 outgoing call setup；
3. 在拨号状态就请求 `CallAudioState.ROUTE_BLUETOOTH`；
4. 收到蓝牙音频路由或蓝牙 call endpoint 时，才 `setActive()`；
5. 如果 400 ms 内没有路由回调，用定时兜底 `setActive()`，避免一直停留在拨号状态。

这样眼镜先看到拨号/接通中的状态，再看到 active call，而不是 idle 直接跳到 active。日志中新增：

```text
IdeavoxBtConnection: call dialing ...
IdeavoxBtConnection: call active session=... reason=bluetooth audio route
IdeavoxBtTelecom: call endpoint session=... bluetooth=true ready=...
```

## 3. 回调与声明能力一致

旧代码声明了 `CAPABILITY_HOLD or CAPABILITY_SUPPORT_HOLD`，但收到保持、恢复、接听时都直接结束录音。这会让某些把通话键映射为 hold 的耳机得到一个“结束录音”的结果，和声明的能力不一致。

现在：

- 不再声明 hold 能力：`connectionCapabilities = 0`。
- `onDisconnect()` 和 `onAbort()` 才是结束录音的路径。
- `onHold()`、`onUnhold()`、`onAnswer()` 不再结束录音；如果平台仍然投递，只记录日志，并在状态确实变成 HOLDING 时恢复 ACTIVE。
- Debug 诊断模式下仍只记录事件并保留连接，方便继续观察原始回调。

对应单测：`BluetoothCallControlPolicyTest`。

## 4. 语音提示

- 结束录音后的 10 秒确认改为播报“录音已结束”，不再只播报“结束”。
- 倒计时数字改为追加到语音队列，避免“我在”或“录音已结束”还没播完就被第一个数字打断。
- 自动上传成功后的播报改为用户要求的“自动上传已完成”，并保留中/日/英/韩翻译。

## 5. 实机对照步骤

先覆盖安装 Debug APK，确认设置为“通话控制模式”，连接 HUAWEI Eyewear 2，然后抓取：

```bash
adb logcat -v threadtime -s \
  IdeavoxBtTelecom IdeavoxBtConnection IdeavoxBtWorkflow IdeavoxBtKeys \
  IdeavoxBtProbe IdeavoxAudioRoute IdeavoxRecording \
  BluetoothInCallService HeadsetStateMachine AndroidRuntime
```

按顺序执行四次左双击，每次等语音提示结束：

1. 未录音 → “我在”+5 秒倒计时；
2. 5 秒内再双击 → 建立通话、打开眼镜麦克风、播报“开启录音”；
3. 录音中双击 → 结束录音、播报“录音已结束”+10 秒倒计时；
4. 10 秒内双击 → 自动上传，成功后播报“自动上传已完成”。

第 3 步重点对照三层日志：

| 观察点 | 日志 | 判断 |
| --- | --- | --- |
| 眼镜是否发出挂断 | 系统 `BluetoothInCallService: BT - hanging up call` 或 `HeadsetStateMachine` 的 HFP 事件 | 没有这一层：眼镜/蓝牙栈未发出或未收到挂断 |
| 系统是否把挂断交给应用 | `IdeavoxBtConnection: HFP onDisconnect session=... address=...` | 有系统事件但没有应用回调：分发/连接身份问题 |
| 应用是否执行结束 | `IdeavoxBtWorkflow: telecom end recording=... effect=StopAndArmUpload` | 有回调但没有该日志：工作流状态不匹配 |
| 是否被误判为保持 | `TELECOM onHold ... ignored` | 出现该日志说明眼镜发的是 hold，不是 hangup |

如果第 3 步仍没有 `HFP onDisconnect`，先切换地址对照：

```bash
adb shell am broadcast -n com.vibecodingjapan.ideavox/.BluetoothTestCommandReceiver \
  --es command legacy-call-address --ez enabled true
```

再重复第 3 步，比较系统蓝牙日志中是否出现挂断。两种地址都没有系统挂断事件时，才把范围转向眼镜固件/手势映射，而不是继续改应用权限。

音频模式对照开关（默认保持现有通话音频模式；`true` 使用标准 VoIP 模式）：

```bash
adb shell am broadcast -n com.vibecodingjapan.ideavox/.BluetoothTestCommandReceiver \
  --es command voip-audio-mode --ez enabled true
```

读取当前通话控制状态：

```bash
adb shell am broadcast -n com.vibecodingjapan.ideavox/.BluetoothTestCommandReceiver \
  --es command status
```

日志中的 `call=` 字段包含 session、attached、endpoint、SCO、ready、实际地址、地址模式和当前 VoIP 音频模式。

## 6. 验证边界

- 已通过：92 项单元测试（新增数字/字母地址过滤、通话控制回调策略、成功播报文案测试）、`assembleDebug`、`lintDebug`。
- 待实机：HUAWEI Eyewear 2 第 3 步是否稳定产生 HFP hangup；第 4 步媒体按键是否在 10 秒窗口内恢复；“自动上传已完成”是否在眼镜上实际听到。
- 不能仅凭编译通过声称双击问题已修复；必须按上面的三层日志区分“眼镜没发”“系统没分发”“应用没处理”。
