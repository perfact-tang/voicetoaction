package com.vibecodingjapan.ideavox

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

class LocalStore(context: Context) {
  private val json = Json { ignoreUnknownKeys = true; prettyPrint = true }
  private val dataFile = File(context.filesDir, "luyin_snapshot.json")
  private val _snapshot = MutableStateFlow(loadSnapshot())
  val snapshot: StateFlow<LocalSnapshot> = _snapshot.asStateFlow()

  val recordingsDir: File = File(context.filesDir, "recordings").also { it.mkdirs() }
  val processedDir: File = File(context.filesDir, "processed").also { it.mkdirs() }

  @Synchronized
  fun setActiveUser(uid: String?) {
    save(UserScopedCache.switchUser(_snapshot.value, uid))
  }

  @Synchronized
  fun upsertRecording(recording: RecordingItem) {
    val current = _snapshot.value
    val next =
      current.copy(recordings = listOf(recording) + current.recordings.filterNot { it.id == recording.id })
    save(next)
  }

  @Synchronized
  fun updateRecording(id: String, transform: (RecordingItem) -> RecordingItem) {
    val current = _snapshot.value
    val next = current.copy(recordings = current.recordings.map { if (it.id == id) transform(it) else it })
    save(next)
  }

  @Synchronized
  fun deleteRecordings(ids: Set<String>) {
    val current = _snapshot.value
    save(current.copy(recordings = current.recordings.filterNot { it.id in ids }))
  }

  @Synchronized
  fun upsertNote(note: NoteItem) {
    val current = _snapshot.value
    val next = current.copy(notes = listOf(note) + current.notes.filterNot { it.id == note.id }, noteDraft = "")
    save(next)
  }

  @Synchronized
  fun enqueueSharedNote(note: PendingSharedNote) {
    val current = _snapshot.value
    save(
      current.copy(
        pendingSharedNotes = listOf(note) + current.pendingSharedNotes.filterNot { it.id == note.id },
        noteDraft = note.body,
      )
    )
  }

  @Synchronized
  fun claimUnownedSharedNotes(uid: String) {
    val current = _snapshot.value
    val claimed = current.pendingSharedNotes.map { note -> if (note.ownerUid == null) note.copy(ownerUid = uid) else note }
    val latestBody = claimed.firstOrNull { it.ownerUid == uid }?.body
    save(current.copy(pendingSharedNotes = claimed, noteDraft = latestBody ?: current.noteDraft))
  }

  @Synchronized
  fun completeSharedNote(id: String, uploaded: NoteItem) {
    val current = _snapshot.value
    val pending = current.pendingSharedNotes.firstOrNull { it.id == id }
    val remaining = current.pendingSharedNotes.filterNot { it.id == id }
    val nextVisibleDraft = remaining.firstOrNull { it.ownerUid == null || it.ownerUid == current.activeUid }?.body.orEmpty()
    save(
      current.copy(
        pendingSharedNotes = remaining,
        notes = listOf(uploaded) + current.notes.filterNot { it.id == uploaded.id },
        noteDraft = if (pending != null && current.noteDraft == pending.body) nextVisibleDraft else current.noteDraft,
      )
    )
  }

  @Synchronized
  fun mergeNotes(notes: List<NoteItem>) {
    val current = _snapshot.value
    save(current.copy(notes = SnapshotMerge.notes(current.notes, notes)))
  }

  @Synchronized
  fun mergeRecordings(recordings: List<RecordingItem>) {
    val current = _snapshot.value
    val visibleRemote = recordings.filterNot { it.remoteId in current.downloadedRemoteRecordingIds }
    save(current.copy(recordings = SnapshotMerge.recordings(current.recordings, visibleRemote)))
  }

  @Synchronized
  fun saveDraft(body: String) {
    save(_snapshot.value.copy(noteDraft = body))
  }

  @Synchronized
  fun saveReaderFontSize(sizeSp: Float) {
    save(_snapshot.value.copy(readerFontSizeSp = sizeSp.coerceIn(16f, 34f)))
  }

  @Synchronized
  fun markRemoteRecordingDownloaded(remoteId: String) {
    val current = _snapshot.value
    val ids = (current.downloadedRemoteRecordingIds + remoteId).distinct()
    val recordings = current.recordings.filterNot { it.remoteListing && it.remoteId == remoteId }
    save(current.copy(recordings = recordings, downloadedRemoteRecordingIds = ids))
  }

  @Synchronized
  fun saveSelectedRecordingDevice(deviceId: Int?) {
    save(_snapshot.value.copy(selectedRecordingDeviceId = deviceId))
  }

  @Synchronized
  fun saveSelectedPlaybackDevice(deviceId: Int?) {
    save(_snapshot.value.copy(selectedPlaybackDeviceId = deviceId))
  }

  @Synchronized
  fun saveSelectedUploadOutputFormat(format: UploadOutputFormat) {
    save(_snapshot.value.copy(selectedUploadOutputFormat = format))
  }

  @Synchronized
  fun saveBluetoothRecordingControlMode(mode: BluetoothRecordingControlMode) {
    save(_snapshot.value.copy(bluetoothRecordingControlMode = mode))
  }

  @Synchronized
  fun saveBluetoothHeadsetControlMonitoringEnabled(enabled: Boolean) {
    save(_snapshot.value.copy(bluetoothHeadsetControlMonitoringEnabled = enabled))
  }

  @Synchronized
  fun saveBluetoothDefaultAICalling(item: AICallingItem?) {
    save(_snapshot.value.copy(bluetoothDefaultAICalling = item))
  }

  @Synchronized
  fun saveBluetoothDefaultUploadLanguage(language: ReaderLanguage) {
    save(_snapshot.value.copy(bluetoothDefaultUploadLanguage = language))
  }

  @Synchronized
  fun saveBluetoothDefaultUploadSpeed(speed: Float) {
    val normalized = UploadSpeeds.minByOrNull { kotlin.math.abs(it - speed) } ?: 1.0f
    save(_snapshot.value.copy(bluetoothDefaultUploadSpeed = normalized))
  }

  private fun loadSnapshot(): LocalSnapshot {
    if (!dataFile.exists()) return LocalSnapshot()
    return runCatching { json.decodeFromString<LocalSnapshot>(dataFile.readText()) }.getOrDefault(LocalSnapshot())
  }

  private fun save(snapshot: LocalSnapshot) {
    dataFile.writeText(json.encodeToString(snapshot))
    _snapshot.value = snapshot
  }
}
