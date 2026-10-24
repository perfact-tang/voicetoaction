# Eyewear 2 纯通话模式：双击启动 + 后滑结束/上传

## 目标配置

- HUAWEI Eyewear 2 只开“通话音频”，**关闭“媒体音频”**；媒体播放留给其他蓝牙耳机。
- 在华为智慧生活（`com.huawei.smarthome`）里把 Eyewear 2 的**双击手势设为“唤醒语音助手”**。
- 在 Android 的语音指令选择框里选择 **ideavox**，点“始终 / Always”。
- 应用设置保持“录音中调低通话音量结束”开启（后滑结束和第二次后滑上传都依赖这个通话音量通道）。

## 硬件限制（实测结论）

在 Eyewear 2 上：

- 未录音、SCO 未打开时，双击会发送 HFP `AT+BVRA`。Android 将其转成 `ACTION_VOICE_COMMAND`，应用可以收到。
- 一旦 HFP/SCO 音频打开（即使应用没有建立 Telecom 通话），眼镜就不再发送 `AT+BVRA`；同时也不会为第三方自管理 VoIP 通话发送可用的 `CHUP/onDisconnect`。
- 华为官方说明：只有手机原生电话支持眼镜接听/拒接/挂断，第三方网络通话不支持。

因此“保持 SCO 让眼镜出声”和“双击让应用收到事件”不能同时成立。SCO 期间只能用通话音量滑动控制。

**方向选择：用后滑（音量 -），不用前滑（音量 +）。**

SCO AudioOn 后约 2-3 秒，蓝牙栈会自动把通话音量同步回它记住的值，日志中是：

```text
AudioService: setStreamVolume(stream=0, index=15, dev=bt_sco, calling=com.android.bluetooth)
```

这个同步是**向上**的，和前滑产生的 `+1` 完全相同，无法在广播里区分，因此前滑会被误判成结束录音（表现为录音开始 2-3 秒后自己结束，或者第二次录音开始后前滑失效）。后滑是 `-1`，不会被这个同步触发。

## 最终流程

1. **双击一次**（不再要求第二次确认，不播“我在”/“再双击”）
   - 不再播“准备”；双击后直接打开 HFP SCO（`directSco=true`，不创建 Telecom 通话），手机侧只保留一个 Toast；
   - 路由确认后，在眼镜里播“开始录音”，同时开始录音；
   - 录音开始后前 10 秒为防误触保护期，但不再播“现在可以后滑结束”。
2. **第一次左镜腿后滑（通话音量 -，录音开始 10 秒后）**
   - 结束录音并保存；
   - **不结束 SCO/通话音频**；
   - 在眼镜里播“录音已结束，5秒内再后滑一次上传”。
3. **第二次后滑**
   - 触发自动上传，不再播“已开启上传”；
   - 上传成功后发一条应用通知“已上传”（通知渠道 `luyin_upload_result`，默认重要性），然后释放 SCO/通话音频。
4. **窗口内没有第二次后滑**
   - 不再播“已取消上传”，静默释放 SCO/通话音频；录音文件保留。

上传窗口约 8 秒（含提示音），足够完成第二次后滑。

## 语音提示策略

- 已移除：`准备`、`现在可以后滑结束`、`已开启上传`、`自动上传已完成`、`已取消上传`。
- 保留：`开始录音`、`录音已结束，5秒内再后滑一次上传`，以及必要的错误提示。
- 上传成功改用本地应用通知，标题为 `已上传`，正文是录音文件名；点击通知打开 App。

## 代码入口

- 双击：`MainActivity.consumeVoiceCommand()` / `handleVoiceCommand()`，调用 `BluetoothRecordingWorkflow.startVoiceRecording()`。
- 单次启动：`BluetoothRecordingWorkflow.startVoiceRecording()`，设置 `voiceCommandDirectSco=true` 后直接调用录音准备。
- 第一次后滑：`RecordingService` 的通话音量广播 → `BluetoothRecordingWorkflow.handleTelecomEnd(..., source="volume")`。
- 保持 SCO：`RecordingService.stopRecording()` 的 `keepSco=true` 分支；不恢复输入路由、不结束通话、不停止前台服务。
- 第二次后滑：`RecordingService` 的 `uploadVolumeGate` → `BluetoothRecordingWorkflow.handleUploadConfirmation()`。
- 释放：`RecordingService.ACTION_RELEASE_UPLOAD_ROUTE`；成功、失败或超时后调用。
- 音量门槛：`BluetoothVolumeStopGate(direction = -1)` 只接受 `value == previous - 1`，即音量降低一档；不绑定固定起始音量。**录音开始后的前 10 秒为防误触保护期，停止门禁在此期间忽略所有音量变化**；上传门禁 1.2s 仅用于去重。
- 最低音量预留：`withDownHeadroom()` 在音量为最小时预留一档，保证后滑仍能产生 `-1`。
- 10 秒保护期：SCO 建立的瞬间，眼镜/蓝牙栈可能产生音量扩大或缩小事件；保护期内不响应，避免刚开录音就自动结束。

## 验证

- 实机：用户确认 10 秒保护期内录音不再自动结束；听到“现在可以后滑结束”后，连续两轮“双击开始 → 后滑结束 → 第二次后滑上传”均成功，上传正常。
- 模拟：走真实音量广播的后滑测试连续两轮均触发 `StopAndArmUpload`；`keepSco=true`、上传、成功播报、释放和超时取消路径均已验证。10 秒保护期内（2.2s、9.5s）的后滑被忽略，11.5s 的后滑正常结束。
- 自动：103 项单元测试、`assembleDebug`、`lintDebug` 全部通过。

Debug 测试命令（仅 Debug APK）：

```bash
# 模拟一次双击启动
adb shell am start -a android.intent.action.VOICE_COMMAND

# 走真实音量门禁模拟后滑（推荐）
adb shell am broadcast -n com.vibecodingjapan.ideavox/.BluetoothTestCommandReceiver \
  --es command lower-call-volume

# 直接触发结束/上传流程（绕过音量门禁）
adb shell am broadcast -n com.vibecodingjapan.ideavox/.BluetoothTestCommandReceiver \
  --es command volume-up

# 查看当前状态（录音、SCO、通话会话）
adb shell am broadcast -n com.vibecodingjapan.ideavox/.BluetoothTestCommandReceiver \
  --es command status
```

## 与旧 Telecom 路径的关系

之前对通话地址、状态上报和 hold 回调的修复仍保留在 `BluetoothRecordingTelecom` 中，供媒体按键/Telecom 模式使用；本纯通话语音流程不再创建 Telecom 通话，因此不依赖这些修复。`docs/bluetooth-call-integration-2026-10-19.md` 中的地址/状态对照方法仍可用于旧路径排查。
