package com.vibecodingjapan.ideavox

import android.Manifest
import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.io.File
import java.io.RandomAccessFile
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

class RecordingService : Service() {
  private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
  private val shouldRecord = AtomicBoolean(false)
  private val paused = AtomicBoolean(false)
  private var recorder: AudioRecord? = null
  private var recordingJob: Job? = null
  private var currentFile: File? = null
  private var startedAt = 0L
  private var pausedAt = 0L
  private var pausedTotal = 0L
  private var bytesWritten = 0L
  private var currentId = ""
  private var currentInputRoute: AudioInputRoute? = null
  private var currentInputDeviceId: Int? = null
  private var currentUseCommunicationRoute = false
  private var currentUseTelecomControl = false
  private var currentAICallingId: String? = null
  private var currentAICallingTitle: String? = null
  private var currentAICallingInfo: String? = null
  private var currentServiceId: String? = null
  private var currentServiceType: String? = null

  override fun onBind(intent: Intent?): IBinder? = null

  override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
    when (intent?.action) {
      ACTION_START -> startRecording(intent)
      ACTION_PAUSE -> pauseRecording(intent)
      ACTION_RESUME -> resumeRecording(intent)
      ACTION_STOP -> stopRecording(intent)
      ACTION_SET_INPUT_DEVICE -> setInputDevice(intent)
    }
    return START_STICKY
  }

  private fun startRecording(intent: Intent?) {
    if (recorder != null) return
    if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
      RecordingRuntime.state.value = RecorderUiState()
      stopSelf()
      return
    }
    startForeground(1001, notification("正在准备录音"))
    currentId = intent?.getStringExtra(EXTRA_RECORDING_ID)?.takeIf { it.isNotBlank() } ?: UUID.randomUUID().toString()
    val useSystemDefaultInput =
      intent?.getBooleanExtra(EXTRA_USE_SYSTEM_DEFAULT_INPUT, false) == true
    val requestedInputDeviceId =
      when {
        useSystemDefaultInput -> null
        intent?.hasExtra(EXTRA_INPUT_DEVICE_ID) == true ->
          intent.getIntExtra(EXTRA_INPUT_DEVICE_ID, -1).takeIf { it >= 0 }
        else -> appStore.snapshot.value.selectedRecordingDeviceId
      }
    val requireBluetoothInput = intent?.getBooleanExtra(EXTRA_REQUIRE_BLUETOOTH_INPUT, false) == true
    val announceStartOnBluetoothRoute =
      intent?.getBooleanExtra(EXTRA_ANNOUNCE_START_ON_BLUETOOTH_ROUTE, false) == true
    val announceStartOnActiveRoute =
      intent?.getBooleanExtra(EXTRA_ANNOUNCE_START_ON_ACTIVE_ROUTE, false) == true
    val telecomControlPrewarmed =
      intent?.getBooleanExtra(EXTRA_TELECOM_CONTROL_PREWARMED, false) == true
    var selectedInput = AudioDeviceSelection.findInput(this, requestedInputDeviceId)
    if (requireBluetoothInput && selectedInput?.let(AudioDeviceSelection::isBluetoothInput) != true) {
      selectedInput = AudioDeviceSelection.bluetoothInput(this)
    }
    if (requireBluetoothInput && selectedInput == null) {
      failRecordingStart("蓝牙麦克风当前不可用，录音未启动")
      return
    }
    currentAICallingId = intent?.getStringExtra(EXTRA_AICALLING_ID)
    currentAICallingTitle = intent?.getStringExtra(EXTRA_AICALLING_TITLE)
    currentAICallingInfo = intent?.getStringExtra(EXTRA_AICALLING_INFO)
    currentServiceId = intent?.getStringExtra(EXTRA_SERVICE_ID)
    currentServiceType = intent?.getStringExtra(EXTRA_SERVICE_TYPE)
    val now = System.currentTimeMillis()
    val name = nextRecordingFileName(now)
    val file = File(appStore.recordingsDir, name)
    currentFile = file
    startedAt = now
    pausedTotal = 0L
    bytesWritten = 0L
    paused.set(false)

    val selectedBluetoothInput = selectedInput?.let(AudioDeviceSelection::isBluetoothInput) == true
    val useTelecomBluetoothControl =
      selectedBluetoothInput && shouldUseTelecomBluetoothControl(selectedInput.type)
    currentInputDeviceId = selectedInput?.id
    currentUseCommunicationRoute = selectedBluetoothInput
    currentUseTelecomControl = useTelecomBluetoothControl
    android.util.Log.i(
      TAG,
      "bluetooth control route inputType=${selectedInput?.type}, telecomFallback=$currentUseTelecomControl",
    )
    currentInputRoute = AudioDeviceSelection.prepareInputRoute(this, selectedInput)
    if (
      selectedBluetoothInput &&
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
        currentInputRoute?.communicationDeviceSet != true &&
        !(currentUseTelecomControl && telecomControlPrewarmed && BluetoothRecordingTelecom.isReady())
    ) {
      failRecordingStart("无法连接蓝牙麦克风，录音未启动")
      return
    }
    if (
      selectedBluetoothInput &&
        currentInputRoute?.communicationDeviceSet != true &&
        currentUseTelecomControl &&
        telecomControlPrewarmed &&
        BluetoothRecordingTelecom.isReady()
    ) {
      android.util.Log.i(
        TAG,
        "AudioManager communication list is stale; continuing with active Telecom route and requiring AudioRecord route verification",
      )
    }
    if (currentUseTelecomControl && telecomControlPrewarmed) {
      android.util.Log.i(
        TAG,
        "prewarmed telecom control ready before recording announcement=${BluetoothRecordingTelecom.isReady()}",
      )
    }
    val audioSource =
      if (selectedBluetoothInput) {
        MediaRecorder.AudioSource.VOICE_COMMUNICATION
      } else {
        MediaRecorder.AudioSource.MIC
      }
    val minBuffer =
      AudioRecord.getMinBufferSize(WavAudio.SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        .coerceAtLeast(WavAudio.BYTES_PER_SAMPLE * 2048)
    val nextRecorder =
      AudioRecord(
        audioSource,
        WavAudio.SAMPLE_RATE,
        AudioFormat.CHANNEL_IN_MONO,
        AudioFormat.ENCODING_PCM_16BIT,
        minBuffer,
      )
    selectedInput?.let { device ->
      val accepted = nextRecorder.setPreferredDevice(device)
      if (!accepted) {
        android.util.Log.w(TAG, "AudioRecord rejected preferred input id=${device.id}, type=${device.type}")
      }
    }

    val suppressStartAnnouncement =
      (selectedBluetoothInput && announceStartOnBluetoothRoute) || announceStartOnActiveRoute
    paused.set(suppressStartAnnouncement)
    WavAudio.createEmptyWav(file)
    nextRecorder.startRecording()
    if (selectedBluetoothInput && AudioDeviceSelection.waitForBluetoothAudioRecordRoute(nextRecorder) == null) {
      AudioDeviceSelection.reassertInputRoute(this, selectedInput)
      nextRecorder.setPreferredDevice(selectedInput)
      if (AudioDeviceSelection.waitForBluetoothAudioRecordRoute(nextRecorder) == null) {
        runCatching { nextRecorder.stop() }
        nextRecorder.release()
        failRecordingStart("录音输入未切换到蓝牙麦克风，已停止以避免误用手机麦克风")
        return
      }
    }
    recorder = nextRecorder
    RecordingRuntime.activeInputIsBluetooth = selectedBluetoothInput
    shouldRecord.set(true)
    recordingJob = serviceScope.launch { writePcmLoop(nextRecorder, file, minBuffer) }
    RecordingRuntime.state.value =
      RecorderUiState(
        status = RecordingStatus.RECORDING,
        activeFileName = name,
        activeRecordingId = currentId,
      )
    BluetoothRecordingWorkflow.onRecordingStarted(currentId)
    startForeground(1001, notification("正在录音中"))
    if (currentUseTelecomControl) {
      if (telecomControlPrewarmed) {
        BluetoothRecordingTelecom.recordingStarted()
      } else {
        BluetoothRecordingTelecom.start(this)
      }
    }
    if (suppressStartAnnouncement) {
      MandarinVoicePrompt.stop()
      val readinessDeadline = System.currentTimeMillis() + START_PROMPT_READY_WAIT_MS
      while (!MandarinVoicePrompt.isReady(this) && System.currentTimeMillis() < readinessDeadline) {
        Thread.sleep(START_PROMPT_READY_POLL_MS)
      }
      if (MandarinVoicePrompt.isReady(this)) {
        android.util.Log.i(
          TAG,
          "playing start announcement on ${if (selectedBluetoothInput) "bluetooth communication" else "system media"} route",
        )
        MandarinVoicePrompt.speak(
          this,
          "开启录音",
          useVoiceCommunication = selectedBluetoothInput,
        )
        Thread.sleep(START_PROMPT_PLAYBACK_LEAD_MS)
      } else {
        android.util.Log.w(TAG, "TTS was not ready for bluetooth start announcement")
      }
      paused.set(false)
      startedAt = System.currentTimeMillis()
    }
    if (!currentUseTelecomControl) {
      BluetoothMediaButtonService.rearm(this)
    }
  }

  private fun writePcmLoop(active: AudioRecord, file: File, bufferSize: Int) {
    val buffer = ByteArray(bufferSize)
    var lastLevelEmit = 0L
    RandomAccessFile(file, "rw").use { output ->
      output.seek(WavAudio.HEADER_SIZE.toLong())
      while (shouldRecord.get()) {
        val count = active.read(buffer, 0, buffer.size)
        if (count > 0) {
          val now = System.currentTimeMillis()
          val activeRecording = !paused.get()
          if (activeRecording) {
            output.write(buffer, 0, count)
            bytesWritten += count
          }
          if (now - lastLevelEmit >= 80L) {
            val nextLevel = if (activeRecording) pcmLevel(buffer, count) else 0f
            val elapsed =
              if (activeRecording) {
                (now - startedAt - pausedTotal).coerceAtLeast(0L)
              } else {
                (pausedAt - startedAt - pausedTotal).coerceAtLeast(0L)
              }
            RecordingRuntime.state.value = RecordingRuntime.state.value.copy(inputLevel = nextLevel, elapsedMs = elapsed)
            lastLevelEmit = now
          }
        }
      }
      WavAudio.updateHeader(output, bytesWritten)
    }
  }

  private fun pcmLevel(buffer: ByteArray, count: Int): Float {
    var sumSquares = 0.0
    var samples = 0
    var index = 0
    while (index + 1 < count) {
      val low = buffer[index].toInt() and 0xFF
      val high = buffer[index + 1].toInt()
      val sample = (high shl 8) or low
      val normalized = sample / 32768.0
      sumSquares += normalized * normalized
      samples += 1
      index += 2
    }
    if (samples == 0) return 0f
    val rms = kotlin.math.sqrt(sumSquares / samples).toFloat()
    return (rms * 4.5f).coerceIn(0f, 1f)
  }

  private fun pauseRecording(intent: Intent?) {
    if (recorder == null || RecordingRuntime.state.value.status != RecordingStatus.RECORDING) return
    paused.set(true)
    pausedAt = System.currentTimeMillis()
    reassertCurrentInputRoute()
    RecordingRuntime.state.value =
      RecordingRuntime.state.value.copy(
        status = RecordingStatus.PAUSED,
        inputLevel = 0f,
        elapsedMs = (pausedAt - startedAt - pausedTotal).coerceAtLeast(0L),
    )
    startForeground(1001, notification("录音已暂停"))
    if (intent?.getBooleanExtra(EXTRA_FROM_TELECOM, false) == true) {
      MandarinVoicePrompt.speak(this, BluetoothHeadsetRecordingCommand.PAUSE_RECORDING.voicePrompt)
    }
    BluetoothMediaButtonService.rearm(this)
  }

  private fun resumeRecording(intent: Intent?) {
    if (recorder == null || RecordingRuntime.state.value.status != RecordingStatus.PAUSED) return
    reassertCurrentInputRoute()
    paused.set(false)
    pausedTotal += System.currentTimeMillis() - pausedAt
    RecordingRuntime.state.value = RecordingRuntime.state.value.copy(status = RecordingStatus.RECORDING)
    startForeground(1001, notification("正在录音中"))
    if (intent?.getBooleanExtra(EXTRA_FROM_TELECOM, false) == true) {
      MandarinVoicePrompt.speak(this, BluetoothHeadsetRecordingCommand.RESUME_RECORDING.voicePrompt)
    }
    BluetoothMediaButtonService.rearm(this)
    serviceScope.launch {
      delay(POST_PROMPT_ROUTE_REASSERT_MS)
      reassertCurrentInputRoute()
    }
  }

  private fun stopRecording(intent: Intent?) {
    val active = recorder ?: return
    val keepInputRouteMs = intent?.getLongExtra(EXTRA_KEEP_INPUT_ROUTE_MS, 0L)?.coerceAtLeast(0L) ?: 0L
    val voicePrompt = intent?.getStringExtra(EXTRA_VOICE_PROMPT).orEmpty()
    val fromTelecom = intent?.getBooleanExtra(EXTRA_FROM_TELECOM, false) == true
    shouldRecord.set(false)
    runCatching { active.stop() }
    active.release()
    runBlocking { recordingJob?.join() }
    recorder = null

    val file = currentFile
    if (file != null && file.exists()) {
      val duration = WavAudio.durationMs(file).takeIf { it > 0 } ?: (System.currentTimeMillis() - startedAt - pausedTotal).coerceAtLeast(0L)
      appStore.upsertRecording(
        RecordingItem(
          id = currentId,
          name = file.name,
          filePath = file.absolutePath,
          createdAt = System.currentTimeMillis(),
          durationMs = duration,
          sizeBytes = file.length(),
          aicallingId = currentAICallingId,
          aicallingTitle = currentAICallingTitle,
          aicallingInfo = currentAICallingInfo,
          serviceId = currentServiceId,
          serviceType = currentServiceType,
        )
      )
    }
    currentAICallingId = null
    currentAICallingTitle = null
    currentAICallingInfo = null
    currentServiceId = null
    currentServiceType = null
    currentFile = null
    RecordingRuntime.state.value = RecorderUiState()
    RecordingRuntime.activeInputIsBluetooth = false
    stopForeground(STOP_FOREGROUND_REMOVE)
    if (!fromTelecom) {
      BluetoothRecordingTelecom.endFromApp()
    }
    // BluetoothRecordingWorkflow owns the HFP -> A2DP transition after a
    // headset/Telecom stop. Restarting the silent media track here as well
    // creates competing AudioTracks and makes the route oscillate.
    if (!fromTelecom) {
      BluetoothMediaButtonService.rearm(this)
    }
    serviceScope.launch {
      if (voicePrompt.isNotBlank()) {
        MandarinVoicePrompt.speak(this@RecordingService, voicePrompt)
      }
      delay(keepInputRouteMs)
      AudioDeviceSelection.restoreInputRoute(this@RecordingService, currentInputRoute)
      currentInputRoute = null
      currentInputDeviceId = null
      currentUseCommunicationRoute = false
      currentUseTelecomControl = false
      if (!fromTelecom) {
        delay(800L)
        BluetoothMediaButtonService.rearm(this@RecordingService)
      }
      stopSelf()
    }
  }

  private fun notification(text: String): Notification {
    val openIntent = Intent(this, MainActivity::class.java)
    val pending =
      PendingIntent.getActivity(this, 0, openIntent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    return NotificationCompat.Builder(this, RECORDING_CHANNEL_ID)
      .setSmallIcon(android.R.drawable.ic_btn_speak_now)
      .setContentTitle("ideavox")
      .setContentText(text.localized(this))
      .setContentIntent(pending)
      .setOngoing(true)
      .build()
  }

  private fun nextRecordingFileName(timestamp: Long): String {
    val baseName = "ideavox-${SimpleDateFormat("yyyy-MM-dd-HH-mm", Locale.US).format(Date(timestamp))}"
    var candidate = "$baseName.wav"
    var index = 2
    while (File(appStore.recordingsDir, candidate).exists()) {
      candidate = "$baseName-$index.wav"
      index += 1
    }
    return candidate
  }

  private fun setInputDevice(intent: Intent) {
    val deviceId = if (intent.hasExtra(EXTRA_INPUT_DEVICE_ID)) intent.getIntExtra(EXTRA_INPUT_DEVICE_ID, -1).takeIf { it >= 0 } else null
    appStore.saveSelectedRecordingDevice(deviceId)
    val active = recorder ?: return
    val nextDevice = AudioDeviceSelection.findInput(this, deviceId)
    AudioDeviceSelection.restoreInputRoute(this, currentInputRoute)
    currentInputRoute = null
    currentInputDeviceId = nextDevice?.id
    currentUseCommunicationRoute = nextDevice?.let(AudioDeviceSelection::isBluetoothInput) == true
    currentUseTelecomControl =
      currentUseCommunicationRoute && shouldUseTelecomBluetoothControl(nextDevice?.type)
    currentInputRoute = AudioDeviceSelection.prepareInputRoute(this, nextDevice)
    active.setPreferredDevice(nextDevice)
    if (currentUseCommunicationRoute) {
      AudioDeviceSelection.reassertInputRoute(this, nextDevice)
    }
    if (currentUseTelecomControl) {
      BluetoothRecordingTelecom.start(this)
    } else {
      BluetoothRecordingTelecom.endFromApp()
    }
  }

  private fun reassertCurrentInputRoute() {
    if (!currentUseCommunicationRoute) return
    val selectedInput = AudioDeviceSelection.findInput(this, currentInputDeviceId)
    AudioDeviceSelection.reassertInputRoute(this, selectedInput)
    selectedInput?.let { device -> recorder?.preferredDevice = device }
  }

  private fun shouldUseTelecomBluetoothControl(inputDeviceType: Int?): Boolean =
    BluetoothRecordingControlModeResolver.shouldUseTelecom(
      appStore.snapshot.value.bluetoothRecordingControlMode,
      inputDeviceType,
    )

  private fun failRecordingStart(message: String) {
    android.util.Log.e(TAG, message)
    BluetoothRecordingWorkflow.onRecordingStartFailed(currentId.takeIf { it.isNotBlank() })
    if (currentUseTelecomControl) {
      BluetoothRecordingTelecom.endFromApp()
    }
    currentFile?.takeIf(File::exists)?.delete()
    currentFile = null
    AudioDeviceSelection.restoreInputRoute(this, currentInputRoute)
    currentInputRoute = null
    currentInputDeviceId = null
    currentUseCommunicationRoute = false
    currentUseTelecomControl = false
    RecordingRuntime.activeInputIsBluetooth = false
    RecordingRuntime.state.value = RecorderUiState()
    stopForeground(STOP_FOREGROUND_REMOVE)
    MandarinVoicePrompt.speak(this, message)
    BluetoothMediaButtonService.rearm(this)
    stopSelf()
  }

  companion object {
    const val ACTION_START = "com.vibecodingjapan.ideavox.record.START"
    const val ACTION_PAUSE = "com.vibecodingjapan.ideavox.record.PAUSE"
    const val ACTION_RESUME = "com.vibecodingjapan.ideavox.record.RESUME"
    const val ACTION_STOP = "com.vibecodingjapan.ideavox.record.STOP"
    const val ACTION_SET_INPUT_DEVICE = "com.vibecodingjapan.ideavox.record.SET_INPUT_DEVICE"
    const val EXTRA_INPUT_DEVICE_ID = "inputDeviceId"
    const val EXTRA_RECORDING_ID = "recordingId"
    const val EXTRA_REQUIRE_BLUETOOTH_INPUT = "requireBluetoothInput"
    const val EXTRA_USE_SYSTEM_DEFAULT_INPUT = "useSystemDefaultInput"
    const val EXTRA_ANNOUNCE_START_ON_BLUETOOTH_ROUTE = "announceStartOnBluetoothRoute"
    const val EXTRA_ANNOUNCE_START_ON_ACTIVE_ROUTE = "announceStartOnActiveRoute"
    const val EXTRA_TELECOM_CONTROL_PREWARMED = "telecomControlPrewarmed"
    const val EXTRA_AICALLING_ID = "aicallingId"
    const val EXTRA_AICALLING_TITLE = "aicallingTitle"
    const val EXTRA_AICALLING_INFO = "aicallingInfo"
    const val EXTRA_SERVICE_ID = "serviceId"
    const val EXTRA_SERVICE_TYPE = "serviceType"
    const val EXTRA_KEEP_INPUT_ROUTE_MS = "keepInputRouteMs"
    const val EXTRA_VOICE_PROMPT = "voicePrompt"
    const val EXTRA_FROM_TELECOM = "fromTelecom"
    private const val POST_PROMPT_ROUTE_REASSERT_MS = 900L
    private const val START_PROMPT_READY_WAIT_MS = 1_000L
    private const val START_PROMPT_READY_POLL_MS = 50L
    private const val START_PROMPT_PLAYBACK_LEAD_MS = 1_200L
    private const val TAG = "IdeavoxRecording"
  }
}

object RecordingRuntime {
  val state = MutableStateFlow(RecorderUiState())
  @Volatile var activeInputIsBluetooth: Boolean = false
}
