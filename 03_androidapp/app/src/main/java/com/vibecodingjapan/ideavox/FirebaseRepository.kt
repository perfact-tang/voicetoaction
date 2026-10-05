package com.vibecodingjapan.ideavox

import android.net.Uri
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.Timestamp
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.SetOptions
import com.google.firebase.storage.FirebaseStorage
import com.google.firebase.storage.StorageMetadata
import com.google.firebase.storage.UploadTask
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.tasks.await
import java.io.File
import java.util.concurrent.CancellationException
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

class FirebaseRepository(private val localStore: LocalStore) {
  private val auth = FirebaseAuth.getInstance()
  private val db = FirebaseFirestore.getInstance()
  private val storage = FirebaseStorage.getInstance()
  private val sharedNotesSyncMutex = Mutex()

  val currentUid: String?
    get() = auth.currentUser?.uid

  val currentEmail: String?
    get() = auth.currentUser?.email

  suspend fun refreshAuthState(): AuthUiState {
    val user = auth.currentUser ?: return AuthUiState()
    val vip = runCatching { isVip(user.uid) }.getOrDefault(false)
    return AuthUiState(uid = user.uid, email = user.email, isVip = vip)
  }

  suspend fun signIn(email: String, password: String): AuthUiState {
    auth.signInWithEmailAndPassword(email.trim(), password).await()
    return refreshAuthState()
  }

  suspend fun register(email: String, password: String): AuthUiState {
    auth.createUserWithEmailAndPassword(email.trim(), password).await()
    return refreshAuthState()
  }

  fun signOut() {
    auth.signOut()
  }

  suspend fun syncUserData() {
    val uid = currentUid ?: return
    refreshNotes(uid)
    val userRoot = db.collection("users").document(uid)
    val remoteRecordings = userRoot.collection("recordings").get().await().documents.mapNotNull { it.toRecordingItem() }
    localStore.mergeRecordings(remoteRecordings)
  }

  suspend fun refreshNotes() {
    val uid = requireNotNull(currentUid) { "请先登录" }
    syncPendingSharedNotes()
    refreshNotes(uid)
  }

  private suspend fun refreshNotes(uid: String) {
    val userRoot = db.collection("users").document(uid)
    val remoteNotes =
      userRoot.collection("notes")
        .whereEqualTo("hidden", false)
        .get()
        .await()
        .documents
        .mapNotNull { it.toNoteItem() }
    localStore.mergeNotes(remoteNotes)
  }

  /**
   * 读取呼叫对象。数据来源是 CMS 的 `/allalservice`（`where userUid == uid`），
   * 而不是旧的 `/users/{uid}/aicalling`。
   *
   * 这里只做 `userUid` 单字段等值查询：Firestore 会自动为单字段建索引，
   * `isDeleted` / `status` / `sort` 全部在本地过滤与排序，因此部署时**不需要**
   * 额外创建 `(userUid, sort)` 复合索引。
   */
  suspend fun loadAICallingItems(language: AppLanguage = AppLanguage.ZH): List<AICallingItem> {
    val uid = requireNotNull(currentUid) { "请先登录" }
    return db.collection(ALLALSERVICE_COLLECTION)
      .whereEqualTo("userUid", uid)
      .get()
      .await()
      .documents
      .mapNotNull { it.toAICallingItem(language) }
      .sortedWith(compareBy<AICallingItem> { it.sort }.thenBy { it.title })
  }

  suspend fun saveNote(note: NoteItem) {
    val uid = requireNotNull(currentUid) { "请先登录" }
    writeNote(uid, note)
    localStore.upsertNote(note)
  }

  suspend fun syncPendingSharedNotes() {
    sharedNotesSyncMutex.withLock {
      val uid = currentUid ?: return
      localStore.claimUnownedSharedNotes(uid)
      val pending =
        localStore.snapshot.value.pendingSharedNotes
          .filter { it.ownerUid == uid }
          .sortedBy { it.createdAt }
      pending.forEach { shared ->
        val note = NoteItem(shared.id, shared.title, shared.body, shared.createdAt)
        writeNote(uid, note)
        localStore.completeSharedNote(shared.id, note)
      }
    }
  }

  private suspend fun writeNote(uid: String, note: NoteItem) {
    val data =
      mapOf(
        "id" to note.id,
        "title" to note.title,
        "body" to note.body,
        "tags" to emptyList<String>(),
        "color" to "paper",
        "hidden" to note.hidden,
        "updatedAt" to FieldValue.serverTimestamp(),
      )
    db.collection("users").document(uid).collection("notes").document(note.id).set(data).await()
  }

  suspend fun uploadRecording(
    recording: RecordingItem,
    language: ReaderLanguage = ReaderLanguage.ZH,
    onProgress: (Int) -> Unit = {},
    shouldCancel: () -> Boolean = { false },
  ) {
    val uid = requireNotNull(currentUid) { "请先登录" }
    if (!isVip(uid)) throw IllegalStateException("当前账号未开通录音上传权限")
    localStore.updateRecording(recording.id) { it.copy(uploadState = UploadState.UPLOADING, uploadProgress = 1) }
    val file = File(recording.filePath)
    if (!file.exists()) throw IllegalStateException("录音文件不存在")
    val safeName = FileNames.safeRecordingFileName(recording.name, recording.id)
    val ref = storage.reference.child("record/$uid/${recording.id}-$safeName")
    val metadata =
      StorageMetadata.Builder()
        .setContentType(RecordingUploadMetadata.contentTypeFor(file))
        .setCustomMetadata("recordingId", recording.id)
        .setCustomMetadata("recordingKind", recording.kind.name)
        .setCustomMetadata("durationMs", recording.durationMs.toString())
        .apply {
          recording.aicallingId?.takeIf { it.isNotBlank() }?.let { setCustomMetadata("aicallingid", it) }
          recording.serviceId?.takeIf { it.isNotBlank() }?.let { setCustomMetadata("serviceId", it) }
          recording.serviceType?.takeIf { it.isNotBlank() }?.let { setCustomMetadata("serviceType", it) }
        }
        .build()
    val task = ref.putFile(Uri.fromFile(file), metadata)
    awaitStorageUpload(
      task = task,
      recordingId = recording.id,
      onProgress = onProgress,
      shouldCancel = shouldCancel,
    )
    if (shouldCancel()) {
      task.cancel()
      throw CancellationException("已取消")
    }
    val storageUri = ref.toString()
    db.collection("record")
      .add(
        buildMap<String, Any> {
          put("language", language.recordCode)
          put("projectID", "vibecodingjapan")
          put("recordFile", storageUri)
          put("state", "uploaded")
          // monitor.js 用 aicallingid 作为 Google Drive 目录 ID。
          // google_workspace_studio 服务由 CMS 的 googleDriveUrl 反解得到；
          // deepseek_harness 没有目录，这里不写该字段，后端会回落到默认目录。
          recording.aicallingId?.takeIf { it.isNotBlank() }?.let { put("aicallingid", it) }
          // CMS 服务身份，便于后端按 allalservice/{serviceId} 解析服务类型与 Skill 包。
          recording.serviceId?.takeIf { it.isNotBlank() }?.let { put("serviceId", it) }
          recording.serviceType?.takeIf { it.isNotBlank() }?.let { put("serviceType", it) }
        }
      )
      .await()
    db.collection("users").document(uid).collection("recordings").document(recording.id)
      .set(RecordingUploadMetadata.firestoreMap(recording, storageUri, language), SetOptions.merge())
      .await()
    localStore.updateRecording(recording.id) {
      it.copy(uploadState = UploadState.UPLOADED, uploadProgress = 100, storageUri = storageUri, error = null)
    }
  }

  private suspend fun awaitStorageUpload(
    task: UploadTask,
    recordingId: String,
    onProgress: (Int) -> Unit,
    shouldCancel: () -> Boolean,
  ) {
    suspendCancellableCoroutine { continuation ->
      val completed = AtomicBoolean(false)

      fun publish(progress: Int) {
        val safeProgress = progress.coerceIn(1, 100)
        onProgress(safeProgress)
        localStore.updateRecording(recordingId) {
          it.copy(uploadState = UploadState.UPLOADING, uploadProgress = safeProgress)
        }
      }

      fun finish(error: Throwable? = null) {
        if (!completed.compareAndSet(false, true)) return
        if (!continuation.isActive) return
        if (error == null) {
          continuation.resume(Unit)
        } else {
          continuation.resumeWithException(error)
        }
      }

      task.addOnProgressListener { snapshot ->
        if (!continuation.isActive) return@addOnProgressListener
        if (shouldCancel()) {
          task.cancel()
          finish(CancellationException("已取消"))
          return@addOnProgressListener
        }
        val progress =
          if (snapshot.totalByteCount > 0) {
            ((snapshot.bytesTransferred * 100) / snapshot.totalByteCount).toInt().coerceIn(1, 99)
          } else {
            1
          }
        publish(progress)
      }
      task.addOnPausedListener {
        if (continuation.isActive) publish(1)
      }
      task.addOnSuccessListener {
        publish(100)
        finish()
      }
      task.addOnFailureListener { finish(it) }
      task.addOnCanceledListener { finish(CancellationException("已取消")) }
      continuation.invokeOnCancellation {
        if (!completed.get()) task.cancel()
      }
    }
  }

  suspend fun downloadRecording(recording: RecordingItem): RecordingItem {
    val storageUri = recording.storageUri ?: throw IllegalStateException("没有可下载的云端文件")
    val localId = recording.remoteId ?: recording.id.removePrefix(REMOTE_RECORDING_PREFIX)
    val safeName = FileNames.safeRecordingFileName(recording.name, localId)
    val target = File(localStore.recordingsDir, safeName)
    if (!recording.remoteListing) {
      localStore.updateRecording(recording.id) { it.copy(uploadState = UploadState.PROCESSING, error = null) }
    }
    storage.getReferenceFromUrl(storageUri).getFile(target).await()
    val localRecording =
      recording.copy(
        id = localId,
        filePath = target.absolutePath,
        sizeBytes = target.length().takeIf { size -> size > 0 } ?: recording.sizeBytes,
        durationMs = if (target.extension.equals("wav", ignoreCase = true)) WavAudio.durationMs(target) else recording.durationMs,
        uploadState = UploadState.LOCAL,
        uploadProgress = 0,
        storageUri = null,
        error = null,
        remoteId = null,
        remoteListing = false,
      )
    localStore.upsertRecording(localRecording)
    recording.remoteId?.let { localStore.markRemoteRecordingDownloaded(it) }
    return localRecording
  }

  suspend fun deleteUploadedRecordingMetadata(recording: RecordingItem) {
    val uid = requireNotNull(currentUid) { "请先登录" }
    val remoteId = recording.remoteId ?: recording.id.removePrefix(REMOTE_RECORDING_PREFIX)
    db.collection("users").document(uid).collection("recordings").document(remoteId).delete().await()
    localStore.deleteRecordings(setOf(recording.id))
    if (!recording.remoteListing && isLocalRecordingAvailable(recording)) {
      localStore.updateRecording(recording.id) {
        it.copy(uploadState = UploadState.LOCAL, uploadProgress = 0, storageUri = null, error = null, remoteId = null, remoteListing = false)
      }
    }
  }

  private suspend fun isVip(uid: String): Boolean {
    val doc = db.collection("userinfo").document(uid).get().await()
    return doc.getString("usertype") == "vip"
  }

  private fun DocumentSnapshot.toNoteItem(): NoteItem? {
    val body = getString("body") ?: return null
    return NoteItem(
      id = getString("id") ?: id,
      title = getString("title") ?: "未命名笔记",
      body = body,
      updatedAt = updatedAtMillis(),
      hidden = getBoolean("hidden") ?: false,
    )
  }

  private fun DocumentSnapshot.updatedAtMillis(): Long {
    val value = get("updatedAt")
    return when (value) {
      is Timestamp -> value.toDate().time
      is Number -> value.toLong()
      else -> 0L
    }
  }

  private fun DocumentSnapshot.toRecordingItem(): RecordingItem? {
    val remoteId = getString("id") ?: this.id
    return RecordingItem(
      id = "$REMOTE_RECORDING_PREFIX$remoteId",
      name = getString("name") ?: "$remoteId.wav",
      filePath = "",
      createdAt = getLong("createdAt") ?: 0L,
      durationMs = getLong("durationMs") ?: 0L,
      sizeBytes = getLong("sizeBytes") ?: 0L,
      kind = runCatching { RecordingKind.valueOf(getString("kind") ?: RecordingKind.ORIGINAL.name) }.getOrDefault(RecordingKind.ORIGINAL),
      uploadState = runCatching { UploadState.valueOf(getString("uploadState") ?: UploadState.UPLOADED.name) }.getOrDefault(UploadState.UPLOADED),
      uploadProgress = (getLong("uploadProgress") ?: 100L).toInt(),
      storageUri = getString("storageUri"),
      error = getString("error"),
      remoteId = remoteId,
      remoteListing = true,
      aicallingId = getString("aicallingid") ?: getString("aicallingId"),
      aicallingTitle = getString("aicallingTitle"),
      aicallingInfo = getString("aicallingInfo"),
      serviceId = getString("serviceId"),
      serviceType = getString("serviceType"),
    )
  }

  private fun DocumentSnapshot.toAICallingItem(language: AppLanguage): AICallingItem? {
    val raw = data ?: return null
    val values = HashMap<String, Any?>()
    raw.forEach { (key, value) -> values[key] = value }
    return ServiceMapping.toCallingItem(values, id, language)
  }

  companion object {
    private const val REMOTE_RECORDING_PREFIX = "remote:"
    /** CMS 的服务集合，呼叫对象的唯一来源。 */
    private const val ALLALSERVICE_COLLECTION = "allalservice"
  }
}
