# 录音列表拆分与已有转写重新处理

## 界面与交互

视觉方向：沿用现有深色与紫色强调色，以单行日期时间、清晰的字号层级和留白组织列表。
内容顺序：录音 / 上传成功两个入口 → 日期时间 → 时长、大小、格式 → 状态或上传方式 → 详情操作。
交互方向：整行点击进入详情；保留原生选中反馈和任务进度变化，取消占位的左侧播放圆钮。播放仍在原始录音详情中。

- **录音**只列出本机原始录音和分享导入的音频。上传时生成的转码、合并产物不再混入。
- **上传成功**按实际上传产物列出记录：合并上传一条，逐条上传每个产物各一条，并标注方式。
- 列表记录按时间倒序排列。新录音文件名使用 `yyyy-MM-dd-HH-mm-ss.wav`，不带品牌前缀；历史自动文件名在列表中显示为 `yyyy-MM-dd HH:mm`。用户自定义名称保留。
- 旧版本错误标记为 `ORIGINAL` 的 `/processed/` 文件也会排除在原始录音列表外。历史记录未保存上传方式时只显示“上传成功”，不猜测其方式。
- 成功上传记录仍使用当前账号的本地缓存，不恢复全量云端录音扫描；没有本地音频的成功记录也可进入详情和重新处理。账号切换沿用原有缓存隔离行为。
- 上传详情按需监听一个 `users/{uid}/recordings/{id}` 文档，显示服务器处理状态。关闭后解除监听。

## 重新处理

路径：录音列表 → 上传成功 → 任一记录 → 重新选择处理对象 → 选择启用中的 Skills 服务。

这次操作只更新原上传记录中的 `reprocessRequest`，不调用音频转换、Storage `putFile` 或 speech-to-text：

```json
{
  "reprocessRequest": {
    "id": "新 UUID",
    "status": "queued",
    "serviceId": "选中的 allalservice 文档 ID",
    "targetTitle": "显示名称",
    "requestedAt": "Firestore server timestamp"
  }
}
```

客户端使用 [Firestore 事务](https://firebase.google.com/docs/firestore/manage-data/transactions) 检查：登录、VIP、上传完成、首轮任务结束、存在转写元数据、服务归属/启用状态，以及没有排队/执行中的重新处理请求。选择范围为 `deepseek_harness`，符合此次“只重新跑 Skills”的功能范围。

服务端：

1. 原上传任务保持原状态和结果；每个请求生成独立的 `::reprocess::<requestId>` 任务 ID。
2. 显式请求绕过音频 URI 去重，同一请求的重复监听/对账保持同一任务 ID。
3. 服务端再次验证用户路径、VIP、处理对象归属、启用状态与类型。
4. 优先读取服务器计算出的原始转写路径；不存在时仅从该录音对应的 `stt/{uid}/{recordingId}.txt` 恢复。拒绝其他用户或记录的文本路径。
5. 文本缺失、空白或下载失败均使本次请求失败，不回退到音频下载或语音识别。
6. 对该文本运行所选服务当前生效版本的 Skills；结果写到 `reprocessRequest.result`，状态为 `queued → processing → success/failed`，原上传及初次结果保持不变。
7. 对账同时查询尚未结束的重新处理指令；服务重启可恢复未完成任务。网络/进程故障期间，外部 Skills 副作用仍取决于各 Skill 自身的幂等能力。

首轮 Skills 和 Google Drive 流程都在转写完成后立即保存本地文本、备份到 Storage 并写入转写元数据，后续处理失败仍能重用文本。历史 Google Drive 任务如果当时未保存转写，不会为了重新处理而偷偷重新识别。

沿用现有 owner-only `recordings` 读写规则，无新增集合、权限或复合索引。真实权限及 Skills 调用由服务器再次验证。

## 验证与交付

- Android：`testDebugUnitTest assembleDebug assembleDebugAndroidTest lintDebug --no-configuration-cache`；106 项单元测试通过。
- Node：`npm test`，其中 `scripts/reprocessing.test.mjs` 验证本地/云端文本复用、文本缺失不回退 STT、指令独立身份与重复音频去重、转写提前持久化。
- `RecordingListsTest` 覆盖旧转码文件分类、无本地音频的上传记录、旧快照兼容和标题格式。
- `RecordingListsInstrumentedTest` 在 Android 35 模拟器中验证列表切换、合并/逐条方式标注和详情入口，1 项通过，并检查三个界面截图；不上传音频或执行真实 Skills。
- 当前机器没有 SenseVoice 的 Python 环境和模型，因此既有真实 STT 集成测试按原脚本设计跳过；Node 的引擎策略、Skills 续跑及新增的 6 项重新处理测试通过。生产服务器/真实 Skills 尚未联调。

发布时需要一起更新 Android APK 和服务器上的 `monitor.js`、`monitor-transcripts.js`，并重启 monitor。仅安装新 APK、仍运行旧 monitor 时，重新处理指令不会执行。此次代码工作不包含生产部署或真实付费 Skills 执行。
