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
