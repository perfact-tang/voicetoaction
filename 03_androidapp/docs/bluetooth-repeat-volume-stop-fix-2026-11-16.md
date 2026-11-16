# 第二次蓝牙录音后滑不结束：原因与修复

## 现象

第一轮“开始蓝牙录音 → 后滑（通话音量 -）→ 结束”正常；紧接着第二轮开始录音后，同样的后滑不再结束录音。

## 根因

结束手势本身到达了应用，但被上一轮遗留的“上传确认窗口”吞掉。

1. 第一轮通过后滑结束后，`BluetoothRecordingWorkflow` 进入
   `PreparingUploadConfirmation → AwaitingUploadConfirmation`，窗口为
   `UPLOAD_CONFIRMATION_WINDOW_MS (10s) + CONFIRMATION_GRACE_MS (1.5s)`；如果录音停止时还要等媒体路由恢复，进入窗口的时间还会再推迟最多 3 秒。
2. 第二轮如果通过 App 内「普通录音」按钮开始，该入口直接向 `RecordingService` 发送 `ACTION_START`，**不经过手势 workflow**，所以 workflow 仍停在上一轮的
   `AwaitingUploadConfirmation`。
3. 后滑经过 `RecordingService` 的 `BluetoothVolumeStopGate` 后被接受，`handleTelecomEnd()` 走到
   `BluetoothGestureStateMachine.onTelecomEnd()`；旧实现只要看到
   `Preparing/AwaitingUploadConfirmation` 就无条件 `Ignore`，不区分这个窗口属于哪一条录音，于是第二轮录音停不下来。
4. 同一个遗留状态还会造成：
   - 10 秒内用眼镜双击开启第二轮时，`startVoiceRecording()` 因 `phase != Idle` 直接拒绝；
   - 旧的过期任务到点后停止 TTS，保留 SCO 的情况下还会释放新一轮正在使用的路由；
   - 媒体键路径会把按键当成“上传上一轮录音”，而不是结束当前录音。

## 修改

- `BluetoothGestureStateMachine.onTelecomEnd()`：上传确认窗口只保护**同一条**录音。若结束请求来自另一条已经开始录音的 id，允许正常的
  `StopAndArmUpload`；旧录音迟到的挂断/结束回调仍被忽略。
- `BluetoothGestureStateMachine.onTrigger()`：`Awaiting/PreparingUploadConfirmation` 分支只有在
  `RecordingStatus.IDLE` 时才按上传确认处理；已有新录音在录时按普通结束手势处理。
- `BluetoothRecordingWorkflow.startVoiceRecording()` / `onRecordingStarted()`：新增
  `dropStaleUploadWindowLocked()`，新一轮开始（无论来自双击还是 App 内按钮）时丢弃其它录音遗留的上传确认窗口，并取消它的过期任务与 TTS。
- `RecordingService.startRecording()`：开始前调用 `abandonUploadWindow()`，若上一轮还保留着 SCO/上传窗口，先释放音量预留、上传门禁与输入路由，避免旧的过期回调破坏新一轮。
- `RecordingService.startRecording()`：钉住的录音设备 id 短暂缺失时，先按产品名找回同一副蓝牙耳机，再退回默认输入；退回时写 warning 日志。此前会静默改用手机麦克风，此时 `activeInputIsBluetooth=false`，后滑完全不会被处理。
- `RecordingService` 音量广播：后滑被保护期/已处理/录音 id 不匹配忽略时输出
  `volume swipe stop ignored ...` 日志，便于区分“手势没到”和“手势到了但被忽略”。

10 秒防误触保护期保持不变（`BluetoothVolumeStopGate(minDelayMs = 10_000L, direction = -1)`），第二轮仍然需要等录音开始满 10 秒再后滑；现在被忽略时会留下日志。

## 测试

`BluetoothGestureStateMachineTest` 新增：

- 旧上传窗口存在时，新一轮录音可以被结束（`Preparing` 与 `Awaiting` 两种阶段）。
- 旧录音迟到的结束回调仍不能结束新录音，也不会确认上传。
- 新录音进行中，媒体键/触发路径结束当前录音；空闲时上传窗口行为不变。

## 验证（待执行）

本机没有 JDK/Android SDK，改动未在此环境编译。需要在开发机执行：

```bash
cd 03_androidapp
./gradlew testDebugUnitTest assembleDebug
```

实机复测时抓取：

```bash
adb logcat -v time -s IdeavoxBtWorkflow IdeavoxRecording IdeavoxBtKeys IdeavoxAudioRoute
```

预期第二轮出现 `volume swipe guard 10s recording=...`，10 秒后后滑出现
`optional volume swipe stop recording=...` 和 `telecom end ... effect=StopAndArmUpload(...)`；若被忽略则出现
`volume swipe stop ignored ...`，可据此判断是保护期还是别的原因。
