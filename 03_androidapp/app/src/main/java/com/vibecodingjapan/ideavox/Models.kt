package com.vibecodingjapan.ideavox

import kotlinx.serialization.Serializable

@Serializable
enum class RecordingKind {
  ORIGINAL,
  MERGED,
}

@Serializable
enum class UploadState {
  LOCAL,
  PROCESSING,
  UPLOADING,
  UPLOADED,
  ERROR,
}

@Serializable
data class RecordingItem(
  val id: String,
  val name: String,
  val filePath: String,
  val createdAt: Long,
  val durationMs: Long,
  val sizeBytes: Long,
  val kind: RecordingKind = RecordingKind.ORIGINAL,
  val uploadState: UploadState = UploadState.LOCAL,
  val uploadProgress: Int = 0,
  val storageUri: String? = null,
  val error: String? = null,
  val remoteId: String? = null,
  val remoteListing: Boolean = false,
  val aicallingId: String? = null,
  val aicallingTitle: String? = null,
  val aicallingInfo: String? = null,
  /** CMS `/allalservice` 文档 ID（仅通过呼叫对象发起的录音有值）。 */
  val serviceId: String? = null,
  /** CMS `allalservice.type`（仅通过呼叫对象发起的录音有值）。 */
  val serviceType: String? = null,
)

@Serializable
data class AICallingItem(
  val title: String,
  val info: String = "",
  /** CMS `/allalservice` 文档 ID，稳定的呼叫对象标识。 */
  val serviceId: String = "",
  /**
   * Google Drive 目录 ID，由 CMS 的 `googleDriveUrl` 反解得到。
   * 它会被写入 `/record.aicallingid`，后端 monitor.js 据此决定生成的 Google Doc 放在哪个目录。
   */
  val aicallingId: String = "",
  val sort: Long = 0L,
  /** CMS `allalservice.type`：`google_workspace_studio` 或 `deepseek_harness`。 */
  val serviceType: String = ServiceTypes.GOOGLE_WORKSPACE_STUDIO,
  /** `deepseek_harness` 的 `--skill`。 */
  val applicationName: String = "",
  /** `deepseek_harness` 当前生效的 Skill 版本号。 */
  val skillVersion: Int = 0,
) {
  val drivesGoogleDoc: Boolean get() = ServiceMapping.supportsDriveAutomation(serviceType)
}

@Serializable
data class NoteItem(
  val id: String,
  val title: String,
  val body: String,
  val updatedAt: Long,
  val hidden: Boolean = false,
)

@Serializable
data class PendingSharedNote(
  val id: String,
  val title: String,
  val body: String,
  val createdAt: Long,
  val ownerUid: String? = null,
)

@Serializable
data class LocalSnapshot(
  val activeUid: String? = null,
  val recordings: List<RecordingItem> = emptyList(),
  val notes: List<NoteItem> = emptyList(),
  val pendingSharedNotes: List<PendingSharedNote> = emptyList(),
  val noteDraft: String = "",
  val readerFontSizeSp: Float = 22f,
  val downloadedRemoteRecordingIds: List<String> = emptyList(),
  val selectedRecordingDeviceId: Int? = null,
  val selectedPlaybackDeviceId: Int? = null,
  val selectedUploadOutputFormat: UploadOutputFormat = UploadOutputFormat.AAC_M4A,
  val bluetoothRecordingControlMode: BluetoothRecordingControlMode = BluetoothRecordingControlMode.AUTO,
  val bluetoothHeadsetControlMonitoringEnabled: Boolean = true,
  val bluetoothDefaultAICalling: AICallingItem? = null,
  val bluetoothDefaultUploadLanguage: ReaderLanguage = ReaderLanguage.ZH,
  val bluetoothDefaultUploadSpeed: Float = 1.0f,
)

data class AuthUiState(
  val uid: String? = null,
  val email: String? = null,
  val isVip: Boolean = false,
  val loading: Boolean = false,
  val message: String? = null,
)

data class RecorderUiState(
  val status: RecordingStatus = RecordingStatus.IDLE,
  val activeFileName: String? = null,
  val activeRecordingId: String? = null,
  val elapsedMs: Long = 0L,
  val inputLevel: Float = 0f,
)

data class UploadTaskUiState(
  val active: Boolean = false,
  val phase: String = "",
  val detail: String = "",
  val progress: Int = 0,
  val startedAt: Long = 0L,
  val elapsedMs: Long = 0L,
  val cancellable: Boolean = false,
  val terminal: Boolean = false,
  val error: String? = null,
)

enum class RecordingStatus {
  IDLE,
  RECORDING,
  PAUSED,
}

enum class ReaderLanguage(val label: String, val tag: String, val recordCode: String) {
  ZH("中文", "zh-CN", "zh"),
  JA("日语", "ja-JP", "ja"),
  EN("英语", "en-US", "en"),
  KO("韩语", "ko-KR", "kr"),
}

enum class UploadOutputFormat(val label: String, val extension: String) {
  WAV("WAV 原始合并", "wav"),
  AAC_M4A("M4A/AAC 压缩", "m4a"),
  MP3("MP3 压缩", "mp3"),
}

enum class BluetoothRecordingControlMode(val label: String) {
  AUTO("自动判断"),
  MEDIA("媒体按键模式"),
  TELECOM("通话控制模式"),
}

val PlaybackSpeeds = listOf(1.0f, 1.5f, 2.0f)
val ReaderSpeeds = listOf(0.5f, 0.75f, 1.0f, 1.5f, 2.0f, 2.5f)
val UploadSpeeds = listOf(1.0f, 1.5f, 1.75f, 2.0f)
