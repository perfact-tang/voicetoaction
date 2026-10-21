# 蓝牙录音设备优先固定（Eyewear 2 优先）

## 需求

手机可能连接多个蓝牙设备。只要 HUAWEI Eyewear 2 仍在已连接列表中，即使之后又连接了其他蓝牙耳机，也要继续优先使用 Eyewear 2 作为录音/通话设备；只有 Eyewear 2 的 Bluetooth 真正断开后，才退回其他设备。

## 之前的问题

- 应用只保存了 `AudioDeviceInfo.id`。设备重连后 ID 可能变化，旧 ID 失效。
- 每次开始蓝牙录音时，`BluetoothRecordingWorkflow` 会用**当前媒体路由**的设备名去选蓝牙输入，并把这个临时设备 ID 写回录音设备设置。后连接的其他耳机成为媒体路由后，就会覆盖原来的 Eyewear 选择。
- `RecordingService` 在选中 ID 失效时直接取“第一个蓝牙输入”，没有稳定的优先级。

## 现在的实现

1. `LocalSnapshot` 新增 `selectedRecordingDeviceName`，与 `selectedRecordingDeviceId` 一起保存。
2. 用户在设置里选择录音设备时，同时保存 ID 和产品名；选择“系统默认”会清除两者。
3. `AudioDevicePriority.pick()` 的优先级：
   - 先匹配已保存的 **设备 ID**（仍在列表中时）；
   - ID 不匹配时，再按 **产品名** 匹配（覆盖重连后 ID 变化）；
   - 两者都不存在时，才回退到当前媒体路由/第一个蓝牙输入。
4. `BluetoothRecordingWorkflow.startAutomatedRecording()` 不再把临时解析到的设备写回设置，避免后连接的耳机覆盖固定选择。
5. `RecordingService.startRecording()` 同样先按固定 ID/名称找设备；只有固定设备不在列表时才回退。
6. 设置页会显示固定设备名；如果设备暂时未连接，显示“未连接（重新连接后优先）”。

## 行为

| 场景 | 使用的录音设备 |
| --- | --- |
| Eyewear 2 已连接，随后连接其他蓝牙耳机 | Eyewear 2 |
| Eyewear 2 的 Bluetooth 断开 | 其他蓝牙输入或系统默认 |
| Eyewear 2 重新连接 | 再次优先 Eyewear 2 |
| 用户在设置里改选其他设备 | 以新选择为固定设备 |

## 升级注意

旧版本只保存了设备 ID，没有名称。升级后请进入设置确认一次“录音：HUAWEI Eyewear 2”：

- 如果旧 ID 仍对应已连接的 Eyewear，应用启动时会自动补存名称；
- 如果旧 ID 已失效，请手动重新选择一次 Eyewear，之后 ID 变化也能靠名称恢复优先级。

## 验证

- 单元测试 `AudioDevicePriorityTest` 覆盖：固定 ID 优先、重连后按名称匹配、大小写/空格归一化、固定设备不存在时返回 null 以便回退。
- 全量：103 项单元测试、`assembleDebug`、`lintDebug` 通过。
- 实机：本轮构建时手机未连接，尚未安装/复测；连接后需确认“先连 Eyewear，再连其他耳机，录音仍走 Eyewear”。
