# 眼镜触控诊断页（Debug APK）

本页用于观察同一手机/眼镜在不同音频状态下的原始响应，避免正常录音工作流的二次确认、去重及开始/结束操作影响连续实验。诊断页仅注册在 `src/debug/AndroidManifest.xml`，发行版没有该入口。

## 能记录的事件

- MediaSession 原始 KeyEvent：按下、抬起、重复、长按、键码、设备、来源、事件时间与接收时间；诊断时不做去重。
- MediaSession 控制器回调：播放、暂停、停止、上一曲、下一曲、快进、快退。
- HFP 连接及音频状态；音频设备增减、实际媒体及麦克风路由。
- 系统有提供时的音量变化；厂商公开广播（包括已实测的 `+IPHONEACCEV` 类事件）。不把电量或普通音量变化误当录音指令。
- Telecom 的音频路由、静音、保持/恢复、接听、挂断/中止、DTMF 和 CallEvent。

触控在诊断期间只记事件，不调用正常录音/自动上传工作流。通话断开等触控请求被记录后保留本地测试连接，便于连续实验；系统或耳机仍可能自行关闭通道，此时以实际路由日志为准。

蓝牙麦克风模式会打开 AudioRecord，确认实际路由并定时记录输入峰值；样本读取后丢弃，不写声音文件。保持页面前台，测试不会建立运营商电话或呼叫联系人。

## 手机操作

打开入口后依次选：

1. 媒体模式（A2DP）：先记录左双击、右前滑、右后滑，各动作之间留出约三秒；重复一轮。
2. 蓝牙麦克风（SCO）：无 Telecom，保持媒体会话与音轨，重复同一组手势。
3. Telecom 通话：建立应用自己的本地通话连接并打开蓝牙麦克风，连续重复手势。
4. 回到媒体模式，再对照一次左双击，确认是否恢复分发。

页面提供手势标记、导出日志和结束诊断。没有收到事件只能说明当前可观察通道没有对应回调，不能直接证明某一方固件有错。

## 调试命令

```bash
adb shell am start -n com.vibecodingjapan.ideavox/.BluetoothTouchDiagnosticActivity --es mode media
adb shell am start -n com.vibecodingjapan.ideavox/.BluetoothTouchDiagnosticActivity --es mark '左侧双击'
adb shell am start -n com.vibecodingjapan.ideavox/.BluetoothTouchDiagnosticActivity --es mode sco
adb shell am start -n com.vibecodingjapan.ideavox/.BluetoothTouchDiagnosticActivity --es mode telecom
adb shell am start -n com.vibecodingjapan.ideavox/.BluetoothTouchDiagnosticActivity --ez export true
adb shell am start -n com.vibecodingjapan.ideavox/.BluetoothTouchDiagnosticActivity --es mode stop
adb exec-out run-as com.vibecodingjapan.ideavox cat files/bluetooth-diagnostics/touch-latest.log
```

日志最多保留 1000 行，导出或正常结束后写入应用私有目录 `files/bluetooth-diagnostics/touch-latest.log`。同时可从 logcat 的 `IdeavoxBtProbe` 标签读取。系统蓝牙栈标签 `AvrcpTargetService` / `AvrcpNativeInterface` / `HeadsetStateMachine` 可用于对照事件是否到达系统。
