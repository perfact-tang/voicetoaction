# ideavox Android

ideavox是原生 Android Kotlin + Jetpack Compose 应用。当前工程包名为 `com.vibecodingjapan.ideavox`，与 `app/google-services.json` 中的 Firebase Android App 配置一致。

## 已实现

- Firebase Email/Password 登录、注册、退出。
- 未登录用户从录音上传入口发起上传时会直接进入登录/注册流程，登录成功后保留已选录音并继续进入合并上传确认。
- 笔记实时本地草稿缓存，保存时写入 `users/{uid}/notes/{noteId}`，`updatedAt` 使用 Firestore 服务端时间并兼容旧本地毫秒时间同步。
- 前台服务录音，支持后台、锁屏、页面切换持续录音。
- 录音开始、暂停、继续、结束；使用前台服务 + `AudioRecord` 写入单个 WAV 文件，暂停时不写入 PCM，继续后仍保持一个逻辑文件。
- 录音列表、原文件/合并文件标签、全部/原文件/合并文件筛选、播放、暂停、1.0x/1.5x/2.0x 播放速度。
- 可作为 Android 系统分享面板的音频接收目标；其他录音 App 分享来的音频会复制到应用私有目录，并加入本地录音列表。
- 可接收其他 App 通过系统分享面板发送的文字；内容会立即写入本地笔记草稿和持久化待同步队列，并在登录、启动或网络恢复后以 `YYYY-MM-DD HH:mm` 标题幂等同步到 Firestore 已保存笔记。
- 上传外部 WAV 前会读取真实的 RIFF/WAV 区块、采样率与声道，并标准化为 44.1kHz 单声道 PCM，兼容 16kHz 录音和带 LIST 等元数据区块的文件。
- 全局选择入口、多选录音、长按三横线手柄排序、本机 WAV 合并、倍速处理后上传。
- 上传处理支持 WAV 原始合并、M4A/AAC 压缩输出和 MP3 压缩输出；M4A 使用 Android 平台 `MediaCodec` + `MediaMuxer` 编码，MP3 使用参考工程同源的 `lame.all.js` 通过 Rhino 执行，支持正常速度、1.5x、1.75x、2x 处理。
- 多文件合并和倍速处理使用流式 WAV；倍速写入使用缓冲，M4A 直接批量读取 PCM 字节送入编码器，单文件 1x M4A 转换跳过临时 WAV 拷贝，避免长录音的大数组和逐采样点写入开销。
- 点击开始处理上传后会显示进度 Dialog，展示当前阶段、进度百分比、已执行时间，并提供取消及“最小化，后台运行”按钮；最小化（或按返回键）仅收起窗口，转换和上传仍由独立前台服务执行。
- 处理期间顶部显示“现在正在转换中，请不要关闭程序”、当前进度及“查看进度”入口；进入上传阶段提示切换为上传中。窗口最小化后可开启新录音、切换笔记或设置页，录音与转换可并发运行，重建 Activity 后保持最小化状态。
- 取消会中止合并/压缩循环，上传阶段会取消 Firebase Storage upload task；后台失败显示顶部详情入口和失败通知，不自动弹窗打断当前操作。正在处理的文件暂不可改名或删除，其他文件仍可操作。
- 合并处理时原文件只短暂显示处理中，合并文件生成后原文件会恢复处理前状态，避免原文件长期停留在 `PROCESSING` 或被误标为上传错误。
- Android TextToSpeech 朗读模式，支持中文、日语、英语、韩语和 0.5x 到 2.5x 语速。
- 朗读前台服务，离开朗读页或锁屏后继续播放，停止按钮主动结束。
- Android 13+ 朗读/录音启动前会请求通知权限，避免前台服务通知被系统拦截。
- Firebase Storage 先上传 `record/{uid}/{id}-{fileName}`，成功后写 Firestore `record`。
- 上传时会为 Storage 文件写入 `audio/wav` 等 content type 与录音自定义 metadata；同时在 `users/{uid}/recordings/{recordingId}` 保存私有录音元数据、上传进度、文件类型和 Storage URI。
- 合并上传时可选择中文、日语、英语、韩语，所选语言会写入 `record.language` 和用户私有录音元数据。
- 启动和登录后会同步 `users/{uid}/notes` 与 `users/{uid}/recordings` 到本地缓存；远端录音元数据会出现在列表中，本地文件不存在时显示为“仅云端”，可下载到本机后再播放或参与合并上传。
- 本地缓存按登录用户隔离；切换账号或退出时会清除笔记、草稿和云端录音元数据，只保留设备本地录音文件。
- Firebase 规则文件位于 `firestore.rules` 和 `storage.rules`，`firebase.json` 已指向这两个文件；策略为本人读写私有数据，录音上传和 `record` 队列写入需要 VIP。
- 「呼叫对象」的数据源已从 `/users/{uid}/aicalling` 改为 CMS 的 `/allalservice`：按 `userUid == uid` 查询，只列出**使用中且未删除**的服务，按 `sort` 排序；标题/说明按 App 语言取 `name<Lang>` / `description<Lang>`（缺失时按语言顺序回退，再回退到 `applicationName`）。列表会标注服务类型（Google Drive / DeepSeek Skill）。
- 录音上传时：`google_workspace_studio` 服务会把它 `googleDriveUrl` 里的 Drive 目录 ID 写入 `/record.aicallingid`，后端 `monitor.js` 无需改动即可继续把 Google Doc 生成到该目录；`deepseek_harness` 没有 Drive 目录，后端会回落到默认目录。两种类型都会额外写入 `serviceId` / `serviceType`（以及 Storage 自定义 metadata），供后端按 `allalservice/{serviceId}` 解析服务。
- 呼叫对象读取只做 `whereEqualTo("userUid", uid)` 单字段查询，`isDeleted` / `status` / `sort` 在本地过滤排序，因此**不需要**额外创建 `(userUid, sort)` 复合索引；规则只需要 `allalservice` 的本人只读权限（`03_androidapp/firestore.rules` 已补上，权威版本是 `02_appcms/firestore.rules`）。
- 后台任务结果推送（FCM）：登录后 App 会把本机 FCM 令牌登记到 `users/{uid}/fcmTokens/{token}`，退出登录时删除；后端（`monitor.js`）在 Google Doc 生成或 Skill 执行结束后向这些设备推送「○○ 执行完毕 / 执行失败」。前台收到消息时由 `PushNotificationService` 自建通知（渠道 `luyin_job_result`），后台时由系统按 `default_notification_channel_id` 展示。依赖 `com.google.firebase:firebase-messaging`（BOM 管理版本）。
- Android 本地自动备份已关闭，避免录音文件、草稿和本地快照被系统云备份带走。

## 当前限制

- MP3 编码已接入纯 JVM/Rhino 路径，端侧实机性能仍需用长录音样本验证；超长文件处理可能明显慢于原生 LAME/FFmpeg。
- 尚未接入 Firebase Emulator 自动化规则测试。

## 构建

```bash
JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" ./gradlew assembleDebug
```

成功后 APK 位于：

```text
app/build/outputs/apk/debug/app-debug.apk
```

## 已验证

- 最小化并发回归测试覆盖：实际 M4A 编码中开启 `AudioRecord` 录音、页面切换、恢复进度窗口、Activity 重建、编码完成后继续录音并保存，以及后台失败查看和关闭；Android 35 模拟器通过。
- M4A 模拟器测试覆盖短录音、两分钟录音、多文件合并、2x 倍速，以及取消后保留原文件和删除未完成输出。运行状态单测覆盖最小化与后台进度并发更新、失败状态保留、取消及新任务复位。
- `./gradlew testDebugUnitTest assembleDebug` 通过。
- `./gradlew lintDebug` 通过；录音服务会在服务内再次检查麦克风权限，避免权限被撤销时崩溃。
- 针对多文件上传 OOM，已验证 `lintDebug testDebugUnitTest assembleDebug` 通过；音频单测覆盖 WAV 合并/倍速和 WAV 合并到 MP3 输出。
- 已在 `medium_phone` Android 36 模拟器安装并启动 debug APK。
- 端侧验证过主界面渲染、录音开始、切换到笔记页后红色“正在录音中”状态条保持、返回录音页停止后生成本地 WAV 录音条目。
- 端侧验证过朗读模式布局，中文/日语/英语/韩语语言区、0.5x 到 2.5x 语速区、播放/暂停/停止/返回控制均可见；点击播放后 `ReaderService` 以前台服务启动并成功绑定系统 TTS，未再出现未绑定即 `speak` 的警告。

## 蓝牙控制修复与调试（2026-10-17）

- 首次按键只播报「我在」，进入 5 秒开始确认；实际接通麦克风开始收音后异步播报「开启录音」，不等待播报才收音。录音中按键结束后只播报「结束」，保留 10 秒上传确认。倒计时按单调时钟逐秒调度，确认按键可立即打断播报。
- 路由协商、AudioRecord 准备和 WAV 收尾在单独的串行工作线程执行，主线程继续处理手机、媒体会话和 Telecom 回调。确认上传前等待 WAV header 和本地录音记录保存完成；准备期间再次按下会记住确认。
- Telecom 通话控制期间暂停媒体保活；媒体控制模式保持同一条保活音轨，状态刷新不重建音轨。Telecom 已经建立的通话路由不再由 AudioManager 重复切换。保活线程使用独立取消标记，停止时不在主线程等待线程退出。
- 支持播放、暂停、耳机挂钩、下一曲、快进、停止、上一曲、快退，以及 Telecom 挂断/保持/恢复/接听。只接管媒体键，音量键和普通按键继续交给系统。
- 同一次物理按键跨入口去重。HUAWEI Eyewear 2 实测会发送 `downTime=eventTime=0` 的 AVRCP 事件，这类事件只做 80ms 短去重，避免后续按键一直被忽略。
- 记录设备增减、实际录音路由变化、按键来源及接收时间、工作流状态、指令耗时和 TTS 播放回调。录音中检测到蓝牙输入切回非蓝牙输入时停止并保留已录部分。

验证结果：67 个单元测试通过；`testDebugUnitTest assembleDebug assembleDebugAndroidTest lintDebug --no-configuration-cache` 通过。在 Android 16 实机连接 HUAWEI Eyewear 2 的一次媒体模式测试中，系统媒体键开启蓝牙 AudioRecord 约 1185ms，结束并保存约 164ms，结束按键在录音过程中正常到达。这是单次修复中间版本测量，不代表所有耳机或最终版本的稳定性能结论。后续手动恢复安装并授予权限后，AUTO/Telecom 模式确认键到蓝牙收音约 1.4 秒；MEDIA 模式开始服务约 1060ms、系统媒体暂停键结束并保存约 295ms。用户实测这两个中间配置下耳机手势仍未被应用接收，因此硬件控制问题尚未验收；已继续修正 MEDIA 模式下保活音轨被停掉的问题，覆盖更新后的用户真实手势验证仍失败，SCO 释放后左双击恢复正常，详见排查报告。自动 instrumentation 回归曾被 `INSTALL_FAILED_USER_RESTRICTED` 拦截，未执行测试用例。

抓取日志：

```bash
adb logcat -v threadtime -s IdeavoxBtKeys IdeavoxBtWorkflow IdeavoxBtTelecom IdeavoxBtConnection IdeavoxAudioRoute IdeavoxRecording TextToSpeech AndroidRuntime
```

新增 `BluetoothRecordingControlInstrumentedTest` 验证真实蓝牙/系统麦克风、主线程响应、录音中媒体结束和 WAV 保存。它会产生一条本地测试录音，不主动触发上传。推荐使用测试设备。`gradle.properties` 已设置 `android.injected.androidTest.leaveApksInstalledAfterRun=true`，防止 Gradle 默认清理主应用及私有数据；不要在有未备份录音的设备上覆盖这个设置。手动覆盖安装和执行 instrumentation 不会主动卸载主应用：

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb shell am instrument -w -e class com.vibecodingjapan.ideavox.BluetoothRecordingControlInstrumentedTest com.vibecodingjapan.ideavox.test/androidx.test.runner.AndroidJUnitRunner
```

后续纯触控诊断：HUAWEI Eyewear 2 媒体模式下左双击、右前滑、右后滑两轮六次分别发出暂停、下一曲、上一曲。仅 SCO 收音阶段未收到对应媒体键；Telecom 阶段确认眼镜 HFP 挂断指令到达应用，共三次挂断回调，之后 SCO 断开；后续音量变化不代表保持通话收音时的手势响应。详见 [触控实测与原始日志](docs/huawei-touch-probe-results-2026-10-17.md)，诊断入口见 [Debug 诊断页说明](docs/bluetooth-touch-probe.md)。手机设置临时切为 MEDIA 用于实测，原模式 AUTO 的快照及测试录音已在主机 `/tmp` 备份。

本次完整硬件排查结果、已验证内容和未解决项见 [蓝牙控制实机排查报告](docs/bluetooth-debug-2026-10-17.md)。当前不能把“耳机麦克风录音期间的真实手势控制”标为已修复。

10 月 18 日重复激活修复：每轮通话与录音身份绑定，隔离迟到回调；清理焦点与端点/SCO 就绪状态；挂断只结束所属录音一次，不再充当上传确认；准备超时可重新唤醒；媒体输出恢复后才进入上传倒计时。恢复实际开始收音后的「开启录音」提示。代码缺陷、日志证据及实机验收边界见 [重复激活排查与修复](docs/bluetooth-repeat-cycle-fix-2026-10-18.md)。

该手机/眼镜在真正停用媒体会话后，左右双击仍未稳定发送挂断。实测确认录音中滑动可发送 HFP 通话音量变化；音量下调方案已实测两轮结束并确认上传。按用户后续要求，当前可选开关改为「录音中调高通话音量结束」：左侧前滑增大通话音量，结束并保存后倒计时；满音量时预留一档，手机通话音量键也可能触发。自动上传真正完成后播报当前应用语言的「自动上传已完成」。此入口不代表双击问题已修复。详见 [前滑结束与成功播报](docs/bluetooth-gesture-polish-2026-10-18.md)。

10 月 19 日通话集成修复：默认通话地址改为数字 `voip:5550100`，避免 HFP CLCC 把旧字母标识过滤成空号码；状态流改为“拨号中 → 请求蓝牙路由 → 路由/端点就绪后接通”，并保留 400ms 兜底；不再声明 hold 能力，保持/恢复/接听回调不再结束录音，只有挂断/中止才结束。结束提示改为“录音已结束”，成功提示改为“自动上传已完成”，倒计时数字不再打断前一句。Debug 接收器新增数字/字母地址和通话音频模式对照开关。旧 Telecom 路径的对照方法见 [通话集成与状态上报修复](docs/bluetooth-call-integration-2026-10-19.md)。

10 月 19 日最终纯通话流程：实测确认 Eyewear 2 在 SCO 音频期间不再发送 `AT+BVRA`，也不为第三方 VoIP 通话发送可用的 `CHUP`，因此双击无法承担录音中的结束/上传。最终改为“单次双击启动录音 + 第一次后滑（音量 -）结束并保持 SCO + 眼镜里播上传提示 + 第二次后滑上传 + 成功后播报并释放 SCO”。双击在未录音时仍通过 HFP `AT+BVRA` / `ACTION_VOICE_COMMAND` 启动；录音使用直连 SCO，不创建 Telecom 通话。后滑版实机确认 10 秒保护期内不再自动结束，连续两轮“双击开始 → 后滑结束 → 第二次后滑上传”均成功；录音开始后的前 10 秒为防误触保护期，忽略音量变化，第 10 秒播报“现在可以后滑结束”。99 项单元测试、`assembleDebug`、`lintDebug` 通过。前滑（音量 +）会被蓝牙栈 AudioOn 后的自动音量同步误判成结束，因此改用后滑（音量 -）。配置、流程图和 Debug 命令见 [Eyewear 2 纯通话模式：双击启动 + 后滑结束/上传](docs/bluetooth-call-only-voice-flow-2026-10-19.md)。


10 月 21 日蓝牙设备优先级：录音设备现在同时保存 ID 和产品名，并按「固定 ID → 固定名称 → 当前媒体路由/第一个蓝牙」的顺序解析。只要 HUAWEI Eyewear 2 仍在连接列表中，后连接的其他蓝牙耳机不会替换它；Eyewear 断开的期间才回退，重新连接后自动恢复优先。启动录音时不再把临时媒体路由设备写回设置。设置页会显示固定的录音设备名，并提示“已固定的蓝牙录音设备只要仍连接，就不会被后连接的其他蓝牙耳机替换”。详见 [蓝牙录音设备优先固定](docs/bluetooth-device-priority-2026-10-21.md)。