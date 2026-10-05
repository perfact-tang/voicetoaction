package com.vibecodingjapan.ideavox

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.media.MediaMetadataRetriever
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File
import java.util.UUID
import java.util.concurrent.CancellationException
import java.util.concurrent.atomic.AtomicBoolean

class UploadProcessingService : Service() {
  private val serviceJob = SupervisorJob()
  private val scope = CoroutineScope(serviceJob + Dispatchers.IO)
  private var wakeLock: PowerManager.WakeLock? = null
  private var foregroundStarted = false
  private var processingJob: Job? = null

  override fun onBind(intent: Intent?): IBinder? = null

  override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
    if (intent?.action == ACTION_CANCEL) {
      UploadRuntime.requestCancel()
      processingJob?.cancel(CancellationException("已取消"))
      if (!UploadRuntime.state.value.active) stopSelf()
      return START_NOT_STICKY
    }
    if (intent?.action == ACTION_PROCESS) {
      if (processingJob?.isActive == true) {
        updateProgress("已有上传任务正在进行", "请等待当前任务完成，或先取消当前任务", UploadRuntime.state.value.progress)
        return START_REDELIVER_INTENT
      }
      UploadRuntime.begin("准备处理", "正在初始化任务")
      acquireWakeLock()
      startForeground(UPLOAD_PROGRESS_NOTIFICATION_ID, progressNotification("准备处理", "正在初始化任务", 0, true))
      foregroundStarted = true
      val ids = intent.getStringArrayListExtra(EXTRA_IDS).orEmpty()
      val format =
        runCatching {
            UploadOutputFormat.valueOf(intent.getStringExtra(EXTRA_OUTPUT_FORMAT) ?: UploadOutputFormat.WAV.name)
          }
          .getOrDefault(if (intent.getBooleanExtra(EXTRA_COMPRESS, false)) UploadOutputFormat.MP3 else UploadOutputFormat.WAV)
      val speed = intent.getFloatExtra(EXTRA_SPEED, 1.0f)
      val language = runCatching { ReaderLanguage.valueOf(intent.getStringExtra(EXTRA_LANGUAGE) ?: ReaderLanguage.ZH.name) }.getOrDefault(ReaderLanguage.ZH)
      val mode = runCatching { UploadProcessMode.valueOf(intent.getStringExtra(EXTRA_MODE) ?: UploadProcessMode.MERGED.name) }.getOrDefault(UploadProcessMode.MERGED)
      val applyBluetoothDefaults = intent.getBooleanExtra(EXTRA_APPLY_BLUETOOTH_DEFAULTS, false)
      processingJob = scope.launch {
        var keepRuntimeState = false
        var originals = emptyList<RecordingItem>()
        try {
          runCatching {
              if (applyBluetoothDefaults) prepareBluetoothAutomaticUpload(ids)
              originals = appStore.snapshot.value.recordings.filter { it.id in ids }
              process(ids, format, speed, language, mode)
            }
            .onSuccess { uploaded -> showUploadSuccessNotification(uploaded) }
            .onFailure { error ->
              val message = if (error is CancellationException) "已取消" else error.message ?: "处理失败"
              restoreOriginalRecordings(originals, message)
              if (error !is CancellationException) {
                keepRuntimeState = true
                UploadRuntime.fail(message)
                showUploadFailureNotification(message)
              }
            }
        } finally {
          if (!keepRuntimeState) UploadRuntime.finish()
          releaseWakeLock()
          stopForeground(STOP_FOREGROUND_REMOVE)
          foregroundStarted = false
          processingJob = null
          stopSelf()
        }
      }
      return START_REDELIVER_INTENT
    }
    return START_NOT_STICKY
  }

  override fun onDestroy() {
    processingJob?.cancel()
    processingJob = null
    serviceJob.cancel()
    foregroundStarted = false
    releaseWakeLock()
    super.onDestroy()
  }

  private suspend fun process(ids: List<String>, format: UploadOutputFormat, speed: Float, language: ReaderLanguage, mode: UploadProcessMode): RecordingItem {
    if (ids.isEmpty()) throw IllegalStateException("没有选择要上传的录音文件")
    val snapshot = appStore.snapshot.value
    val items = ids.mapNotNull { id -> snapshot.recordings.find { it.id == id } }
    if (items.isEmpty()) throw IllegalStateException("找不到要上传的录音文件，请返回录音列表重新选择")
    val missingCount = ids.size - items.size
    if (missingCount > 0) throw IllegalStateException("有 $missingCount 个录音文件已不存在，请重新选择后上传")
    val unavailable = items.firstOrNull { item -> item.remoteListing || !File(item.filePath).exists() }
    if (unavailable != null) throw IllegalStateException("录音文件不可用：${unavailable.name}")
    return when (mode) {
      UploadProcessMode.MERGED -> processMerged(items, format, speed, language)
      UploadProcessMode.SEPARATE -> processSeparate(items, format, speed, language)
    }
  }

  private suspend fun prepareBluetoothAutomaticUpload(ids: List<String>) {
    val recordingId = ids.singleOrNull() ?: error("蓝牙自动上传只支持单个录音")
    val recording = waitForSavedRecording(recordingId)
    updateProgress("正在读取默认设置", "正在获取默认拨打部门", 1)
    val settings = appStore.snapshot.value
    val callingItems = firebaseRepository.loadAICallingItems(AppLanguageManager.current(applicationContext))
    val configured = settings.bluetoothDefaultAICalling
    val target =
      configured?.let { saved ->
        // 优先按 CMS 服务 ID 匹配（稳定）；旧快照没有 serviceId 时退回按 Drive 目录 ID 匹配。
        callingItems.firstOrNull { it.serviceId.isNotBlank() && it.serviceId == saved.serviceId }
          ?: callingItems.firstOrNull { it.aicallingId.isNotBlank() && it.aicallingId == saved.aicallingId }
      }
        ?: callingItems.firstOrNull()
        ?: error("没有可用的默认拨打部门")
    appStore.updateRecording(recording.id) {
      it.copy(
        aicallingId = target.aicallingId,
        aicallingTitle = target.title,
        aicallingInfo = target.info,
        serviceId = target.serviceId,
        serviceType = target.serviceType,
      )
    }
  }

  private suspend fun waitForSavedRecording(recordingId: String): RecordingItem {
    val deadline = System.currentTimeMillis() + BLUETOOTH_RECORDING_SAVE_WAIT_MS
    while (System.currentTimeMillis() < deadline) {
      val item = appStore.snapshot.value.recordings.firstOrNull { it.id == recordingId }
      if (item != null && item.filePath.isNotBlank() && File(item.filePath).exists()) return item
      delay(BLUETOOTH_RECORDING_SAVE_POLL_MS)
    }
    error("录音文件尚未保存完成")
  }

  private suspend fun processMerged(items: List<RecordingItem>, format: UploadOutputFormat, speed: Float, language: ReaderLanguage): RecordingItem {
    UploadRuntime.update("准备处理", "已选择 ${items.size} 个录音文件", 1)
    items.forEach { item ->
      appStore.updateRecording(item.id) { it.copy(uploadState = UploadState.PROCESSING, error = null) }
    }
    val output = File(appStore.processedDir, "merged-${System.currentTimeMillis()}.${format.extension}")
    val normalizedSpeed = if (kotlin.math.abs(speed - 1.0f) < 0.001f) 1.0f else speed
    val initialPhase =
      when {
        format == UploadOutputFormat.WAV && normalizedSpeed == 1.0f -> "正在合并录音"
        normalizedSpeed == 1.0f -> "正在转换格式"
        else -> "正在合并与倍速处理"
      }
    val merged =
      AudioProcessor.process(
        items = items,
        output = output,
        format = format,
        speed = speed,
        workDir = cacheDir,
        context = applicationContext,
        onProgress = { phase, progress ->
          val displayPhase =
            if (normalizedSpeed == 1.0f && phase == "正在合并录音" && format != UploadOutputFormat.WAV) {
              initialPhase
            } else {
              phase
            }
          updateProgress(displayPhase, output.name, progress)
        },
        shouldCancel = { UploadRuntime.cancelRequested.get() },
      )
    restoreOriginalRecordings(items)
    val recording =
      RecordingItem(
        id = UUID.randomUUID().toString(),
        name = merged.name,
        filePath = merged.absolutePath,
        createdAt = System.currentTimeMillis(),
        durationMs = AudioProcessor.durationMs(items, merged, speed),
        sizeBytes = merged.length(),
        kind = RecordingKind.MERGED,
        uploadState = UploadState.PROCESSING,
        aicallingId = items.firstOrNull()?.aicallingId,
        aicallingTitle = items.firstOrNull()?.aicallingTitle,
        aicallingInfo = items.firstOrNull()?.aicallingInfo,
        serviceId = items.firstOrNull()?.serviceId,
        serviceType = items.firstOrNull()?.serviceType,
      )
    appStore.upsertRecording(recording.copy(uploadState = UploadState.LOCAL))
    runCatching {
        firebaseRepository.uploadRecording(
          recording.copy(uploadState = UploadState.LOCAL),
          language,
          onProgress = { progress -> updateProgress("正在上传", recording.name, 85 + ((progress * 15) / 100)) },
          shouldCancel = { UploadRuntime.cancelRequested.get() },
        )
      }
      .onFailure { error ->
        appStore.updateRecording(recording.id) {
          it.copy(uploadState = UploadState.ERROR, error = if (error is CancellationException) "已取消" else error.message ?: "上传失败")
        }
      }
      .getOrElse { throw it }
    return recording
  }

  private suspend fun processSeparate(items: List<RecordingItem>, format: UploadOutputFormat, speed: Float, language: ReaderLanguage): RecordingItem {
    UploadRuntime.update("准备逐条上传", "已选择 ${items.size} 个录音文件", 1)
    var lastUploaded: RecordingItem? = null
    items.forEachIndexed { index, item ->
      if (UploadRuntime.cancelRequested.get()) throw CancellationException("已取消")
      val baseProgress = ((index.toFloat() / items.size) * 100).toInt().coerceIn(1, 95)
      appStore.updateRecording(item.id) { it.copy(uploadState = UploadState.PROCESSING, uploadProgress = 0, error = null) }
      val output = File(appStore.processedDir, "${fileStem(item.name)}-${System.currentTimeMillis()}.${format.extension}")
      val normalizedSpeed = if (kotlin.math.abs(speed - 1.0f) < 0.001f) 1.0f else speed
      val processed =
        AudioProcessor.process(
          items = listOf(item),
          output = output,
          format = format,
          speed = speed,
          workDir = cacheDir,
          context = applicationContext,
          onProgress = { phase, progress ->
            val itemProgress = baseProgress + ((progress.coerceIn(0, 80) / 100f) * (80f / items.size)).toInt()
            updateProgress("$phase（${index + 1}/${items.size}）", item.name, itemProgress.coerceIn(1, 95))
          },
          shouldCancel = { UploadRuntime.cancelRequested.get() },
        )
      appStore.updateRecording(item.id) { it.copy(uploadState = UploadState.LOCAL, uploadProgress = 0, error = null) }
      val recording =
        RecordingItem(
          id = UUID.randomUUID().toString(),
          name = processed.name,
          filePath = processed.absolutePath,
          createdAt = System.currentTimeMillis(),
          durationMs = AudioProcessor.durationMs(listOf(item), processed, normalizedSpeed),
          sizeBytes = processed.length(),
          kind = item.kind,
          uploadState = UploadState.PROCESSING,
          aicallingId = item.aicallingId,
          aicallingTitle = item.aicallingTitle,
          aicallingInfo = item.aicallingInfo,
          serviceId = item.serviceId,
          serviceType = item.serviceType,
      )
      appStore.upsertRecording(recording.copy(uploadState = UploadState.LOCAL))
      runCatching {
          firebaseRepository.uploadRecording(
            recording.copy(uploadState = UploadState.LOCAL),
            language,
            onProgress = { progress ->
              val uploadProgress = baseProgress + ((progress.coerceIn(0, 100) / 100f) * (100f / items.size)).toInt()
              updateProgress("正在上传（${index + 1}/${items.size}）", recording.name, uploadProgress.coerceIn(1, 99))
            },
            shouldCancel = { UploadRuntime.cancelRequested.get() },
          )
        }
        .onFailure { error ->
          appStore.updateRecording(recording.id) {
            it.copy(uploadState = UploadState.ERROR, error = if (error is CancellationException) "已取消" else error.message ?: "上传失败")
          }
        }
        .getOrElse { throw it }
      lastUploaded = recording
    }
    return lastUploaded ?: throw IllegalStateException("没有完成上传的录音文件")
  }

  private fun restoreOriginalRecordings(items: List<RecordingItem>, processingError: String? = null) {
    items.forEach { original ->
      appStore.updateRecording(original.id) {
        original.copy(
          uploadState = UploadState.LOCAL,
          uploadProgress = 0,
          error = processingError,
        )
      }
    }
  }

  private fun updateProgress(phase: String, detail: String, progress: Int) {
    UploadRuntime.update(phase, detail, progress)
    if (foregroundStarted) {
      (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
        .notify(UPLOAD_PROGRESS_NOTIFICATION_ID, progressNotification(phase, detail, progress, !UploadRuntime.cancelRequested.get()))
    }
  }

  private fun progressNotification(phase: String, detail: String, progress: Int, ongoing: Boolean): Notification {
    val pending =
      PendingIntent.getActivity(
        this,
        0,
        Intent(this, MainActivity::class.java),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
      )
    val cancelPending =
      PendingIntent.getService(
        this,
        1,
        Intent(this, UploadProcessingService::class.java).setAction(ACTION_CANCEL),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
      )
    return NotificationCompat.Builder(this, UPLOAD_CHANNEL_ID)
      .setSmallIcon(android.R.drawable.stat_sys_upload)
      .setContentTitle("上传".localized(this))
      .setContentText("${phase.localized(this)} · ${progress.coerceIn(0, 100)}%")
      .setStyle(NotificationCompat.BigTextStyle().bigText("${phase.localized(this)}\n${detail.localized(this)}"))
      .setProgress(100, progress.coerceIn(0, 100), progress <= 0)
      .setContentIntent(pending)
      .setOngoing(ongoing)
      .setOnlyAlertOnce(true)
      .apply {
        if (ongoing) {
          addAction(
            android.R.drawable.ic_menu_close_clear_cancel,
            "取消".localized(this@UploadProcessingService),
            cancelPending,
          )
        }
      }
      .build()
  }

  private fun showUploadSuccessNotification(recording: RecordingItem) {
    val pending =
      PendingIntent.getActivity(
        this,
        0,
        Intent(this, MainActivity::class.java),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
      )
    val notification =
      NotificationCompat.Builder(this, UPLOAD_CHANNEL_ID)
        .setSmallIcon(android.R.drawable.stat_sys_upload_done)
        .setContentTitle("上传成功".localized(this))
        .setContentText(recording.name)
        .setContentIntent(pending)
        .setAutoCancel(true)
        .build()
    (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).notify(UPLOAD_SUCCESS_NOTIFICATION_ID, notification)
  }

  private fun showUploadFailureNotification(message: String) {
    val pending =
      PendingIntent.getActivity(
        this,
        0,
        Intent(this, MainActivity::class.java),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
      )
    val notification =
      NotificationCompat.Builder(this, UPLOAD_CHANNEL_ID)
        .setSmallIcon(android.R.drawable.stat_notify_error)
        .setContentTitle("上传失败".localized(this))
        .setContentText(message.localized(this).take(96))
        .setStyle(NotificationCompat.BigTextStyle().bigText(message.localized(this)))
        .setContentIntent(pending)
        .setAutoCancel(true)
        .build()
    (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).notify(UPLOAD_FAILURE_NOTIFICATION_ID, notification)
  }

  private fun acquireWakeLock() {
    if (wakeLock?.isHeld == true) return
    val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
    wakeLock =
      powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "LuYin:UploadProcessing")
        .apply {
          setReferenceCounted(false)
          acquire(WAKE_LOCK_TIMEOUT_MS)
        }
  }

  private fun releaseWakeLock() {
    wakeLock?.let { lock ->
      if (lock.isHeld) runCatching { lock.release() }
    }
    wakeLock = null
  }

  companion object {
    const val ACTION_PROCESS = "com.vibecodingjapan.ideavox.upload.PROCESS"
    const val ACTION_CANCEL = "com.vibecodingjapan.ideavox.upload.CANCEL"
    const val EXTRA_IDS = "ids"
    const val EXTRA_COMPRESS = "compress"
    const val EXTRA_OUTPUT_FORMAT = "outputFormat"
    const val EXTRA_SPEED = "speed"
    const val EXTRA_LANGUAGE = "language"
    const val EXTRA_MODE = "mode"
    const val EXTRA_APPLY_BLUETOOTH_DEFAULTS = "applyBluetoothDefaults"
    private const val WAKE_LOCK_TIMEOUT_MS = 6 * 60 * 60 * 1000L
    private const val BLUETOOTH_RECORDING_SAVE_WAIT_MS = 12_000L
    private const val BLUETOOTH_RECORDING_SAVE_POLL_MS = 100L
    private const val UPLOAD_PROGRESS_NOTIFICATION_ID = 1003
    private const val UPLOAD_SUCCESS_NOTIFICATION_ID = 1004
    private const val UPLOAD_FAILURE_NOTIFICATION_ID = 1005
  }
}

enum class UploadProcessMode {
  MERGED,
  SEPARATE,
}

private fun fileStem(name: String): String =
  name.substringBeforeLast('.', missingDelimiterValue = name)
    .replace(Regex("[^A-Za-z0-9._-]+"), "-")
    .trim('-')
    .ifBlank { "recording" }

object AudioProcessor {
  private const val DURATION_TOLERANCE_RATIO = 0.04
  private const val DURATION_TOLERANCE_MS = 4_000L

  fun process(
    items: List<RecordingItem>,
    output: File,
    format: UploadOutputFormat,
    speed: Float,
    workDir: File,
    context: Context? = null,
    onProgress: (String, Int) -> Unit = { _, _ -> },
    shouldCancel: () -> Boolean = { false },
  ): File {
    val normalizedSpeed = if (kotlin.math.abs(speed - 1.0f) < 0.001f) 1.0f else speed
    val temporaryInputs = mutableListOf<File>()
    val mergedWav =
      if (format == UploadOutputFormat.WAV) {
        output
      } else {
        File(workDir, "merged-source-${System.currentTimeMillis()}.wav")
      }
    try {
      val expectedDurationMs = expectedDurationMs(items, normalizedSpeed)
      val inputFiles =
        items.mapIndexed { index, item ->
          val input = File(item.filePath)
          if (WavAudio.isCanonicalPcm16(input)) {
            input
          } else {
            val normalized = File(workDir, "normalized-${System.currentTimeMillis()}-$index.wav")
            temporaryInputs += normalized
            WavAudio.normalizePcm16ToCanonical(
              input = input,
              output = normalized,
              onProgress = { progress ->
                val completed = index.toDouble() + (progress / 100.0)
                val overall = ((completed / items.size) * 20.0).toInt().coerceIn(1, 20)
                onProgress("正在标准化音频", overall)
              },
              shouldCancel = shouldCancel,
            )
          }
        }
      if (normalizedSpeed == 1.0f) {
        WavAudio.merge(
          inputs = inputFiles,
          output = mergedWav,
          onProgress = { progress -> onProgress("正在合并录音", 20 + ((progress * 35) / 55).coerceIn(0, 35)) },
          shouldCancel = shouldCancel,
        )
      } else {
        WavAudio.mergeAndSpeed(
          inputs = inputFiles,
          output = mergedWav,
          speed = normalizedSpeed,
          onProgress = { progress -> onProgress("正在合并与倍速处理", 20 + ((progress * 35) / 55).coerceIn(0, 35)) },
          shouldCancel = shouldCancel,
        )
      }
      validateDuration(
        output = mergedWav,
        expectedDurationMs = expectedDurationMs,
        context = context,
        stage = "合并",
      )
      val mergedDurationMs = WavAudio.durationMs(mergedWav).takeIf { it > 0L } ?: expectedDurationMs
      return when (format) {
        UploadOutputFormat.WAV -> output
        UploadOutputFormat.AAC_M4A ->
          AacAudio.encodeWavToM4a(
            mergedWav,
            output,
            onProgress = { progress -> onProgress("正在转换为 M4A", 55 + ((progress * 30) / 100)) },
            shouldCancel = shouldCancel,
          ).also { mergedWav.delete() }
        UploadOutputFormat.MP3 -> {
          val scriptFile = File(workDir, "lame.all.js").takeIf { it.exists() }
          Mp3Audio.encodeWavToMp3(
            mergedWav,
            output,
            context,
            scriptFile,
            onProgress = { progress -> onProgress("正在转换为 MP3", 55 + ((progress * 30) / 100)) },
            shouldCancel = shouldCancel,
          ).also { mergedWav.delete() }
        }
      }
        .also { processed ->
          validateDuration(
            output = processed,
            expectedDurationMs = mergedDurationMs,
            context = context,
            stage = if (format == UploadOutputFormat.WAV) "合并" else "压缩",
          )
        }
    } catch (error: Exception) {
      if (output.exists()) output.delete()
      if (mergedWav != output && mergedWav.exists()) mergedWav.delete()
      throw error
    } finally {
      temporaryInputs.forEach { it.delete() }
    }
  }

  fun process(items: List<RecordingItem>, output: File, compress: Boolean, speed: Float, workDir: File): File =
    process(items, output, if (compress) UploadOutputFormat.MP3 else UploadOutputFormat.WAV, speed, workDir)

  fun durationMs(items: List<RecordingItem>, output: File, speed: Float): Long =
    if (output.extension.equals("wav", ignoreCase = true)) {
      WavAudio.durationMs(output)
    } else {
      expectedDurationMs(items, if (kotlin.math.abs(speed - 1.0f) < 0.001f) 1.0f else speed)
    }

  private fun expectedDurationMs(items: List<RecordingItem>, speed: Float): Long =
    ((items.sumOf { it.durationMs }.toDouble() / speed).toLong()).coerceAtLeast(0L)

  private fun validateDuration(output: File, expectedDurationMs: Long, context: Context?, stage: String) {
    if (expectedDurationMs <= 0L) return
    val actualDurationMs =
      if (output.extension.equals("wav", ignoreCase = true)) {
        WavAudio.durationMs(output)
      } else {
        compressedDurationMs(output, context)
          ?: if (context == null) {
            return
          } else {
            throw IllegalStateException("${stage}后的音频文件无法读取。已停止上传以避免服务器保存损坏文件。")
          }
      }
    val tolerance = maxOf(DURATION_TOLERANCE_MS, (expectedDurationMs * DURATION_TOLERANCE_RATIO).toLong())
    if (actualDurationMs + tolerance < expectedDurationMs) {
      throw IllegalStateException(
        "${stage}后的音频时长异常：原始约 ${formatProcessorDuration(expectedDurationMs)}，生成后约 ${formatProcessorDuration(actualDurationMs)}。已停止上传以避免服务器保存不完整文件。",
      )
    }
  }

  private fun formatProcessorDuration(ms: Long): String {
    val totalSeconds = (ms / 1000).coerceAtLeast(0)
    val hours = totalSeconds / 3600
    val minutes = (totalSeconds % 3600) / 60
    val seconds = totalSeconds % 60
    return "%d:%02d:%02d".format(hours, minutes, seconds)
  }

  private fun compressedDurationMs(output: File, context: Context?): Long? {
    if (context == null || !output.exists() || output.length() <= 0L) return null
    return runCatching {
        val retriever = MediaMetadataRetriever()
        try {
          retriever.setDataSource(output.absolutePath)
          retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()
        } finally {
          retriever.release()
        }
      }
      .getOrNull()
  }
}

object UploadRuntime {
  val state = MutableStateFlow(UploadTaskUiState())
  val cancelRequested = AtomicBoolean(false)
  private var startedAt = 0L

  fun begin(phase: String, detail: String) {
    cancelRequested.set(false)
    startedAt = System.currentTimeMillis()
    state.value = UploadTaskUiState(active = true, phase = phase, detail = detail, progress = 0, startedAt = startedAt, cancellable = true)
  }

  fun update(phase: String, detail: String, progress: Int) {
    if (!state.value.active) return
    if (state.value.terminal) return
    state.value =
      state.value.copy(
        phase = phase,
        detail = detail,
        progress = progress.coerceIn(0, 100),
        elapsedMs = System.currentTimeMillis() - startedAt,
        cancellable = !cancelRequested.get(),
      )
  }

  fun fail(message: String) {
    val now = System.currentTimeMillis()
    val current = state.value
    state.value =
      current.copy(
        active = true,
        phase = "上传失败",
        detail = message,
        elapsedMs = if (startedAt > 0L) now - startedAt else current.elapsedMs,
        cancellable = false,
        terminal = true,
        error = message,
      )
    cancelRequested.set(false)
  }

  fun requestCancel() {
    cancelRequested.set(true)
    update("正在取消", "请稍候，正在停止当前任务", state.value.progress)
  }

  fun finish() {
    state.value = UploadTaskUiState()
    cancelRequested.set(false)
  }
}
