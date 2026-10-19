package com.vibecodingjapan.ideavox

import android.Manifest
import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.AudioRouting
import android.media.AudioManager
import android.media.MediaRecorder
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.cancel
import java.util.concurrent.Executors
import java.io.File
import java.io.RandomAccessFile
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

class RecordingService : Service() {
  private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
  // Route negotiation and file finalization must never block MediaSession/Telecom callbacks.
  private val commands = Executors.newSingleThreadExecutor { task ->
    Thread(task, "ideavox-recording-commands")
  }
  private val shouldRecord = AtomicBoolean(false)
  private val paused = AtomicBoolean(false)
  private var recorder: AudioRecord? = null
  private var routingListener: AudioRouting.OnRoutingChangedListener? = null
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
  private val volumeStopGate = BluetoothVolumeStopGate()
  private var originalCallVolume: Int? = null
  private var reservedCallVolume: Int? = null
  private val volumeStopReceiver = object : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
      val state = RecordingRuntime.state.value
      if (!appStore.snapshot.value.bluetoothVolumeStopEnabled || BluetoothTouchDiagnostics.active || !RecordingRuntime.activeInputIsBluetooth) return
      if (state.status != RecordingStatus.RECORDING && state.status != RecordingStatus.PAUSED) return
      val stream = intent.getIntExtra("android.media.EXTRA_VOLUME_STREAM_TYPE", -1)
      val previous = intent.getIntExtra("android.media.EXTRA_PREV_VOLUME_STREAM_VALUE", -1)
      val value = intent.getIntExtra("android.media.EXTRA_VOLUME_STREAM_VALUE", -1)
      if (volumeStopGate.accept(state.activeRecordingId, stream, previous, value, android.os.SystemClock.elapsedRealtime())) {
        android.util.Log.i(TAG, "optional volume-up stop recording=${state.activeRecordingId} stream=$stream $previous->$value")
        BluetoothRecordingWorkflow.handleTelecomEnd(this@RecordingService, state.activeRecordingId)
      }
    }
  }

  override fun onCreate() {
    super.onCreate()
    ContextCompat.registerReceiver(this, volumeStopReceiver, IntentFilter("android.media.VOLUME_CHANGED_ACTION"), ContextCompat.RECEIVER_EXPORTED)
  }

  override fun onBind(intent: Intent?): IBinder? = null

  override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
    if (intent?.action == ACTION_START) {
      startForeground(1001, notification("正在准备录音"))
    }
    commands.execute {
      val began = android.os.SystemClock.elapsedRealtime()
      android.util.Log.i(TAG, "command=${intent?.action} start status=${RecordingRuntime.state.value.status}")
      runCatching {
        when (intent?.action) {
          ACTION_START -> startRecording(intent)
          ACTION_PAUSE -> pauseRecording(intent)
          ACTION_RESUME -> resumeRecording(intent)
          ACTION_STOP -> {
            val expected = intent.getStringExtra(EXTRA_EXPECTED_RECORDING_ID)
            if (expected == null || expected == currentId) stopRecording(intent)
          }
          ACTION_BLUETOOTH_AUDIO_LOST -> {
            if (intent.getStringExtra(EXTRA_RECORDING_ID) == currentId && recorder != null && currentUseCommunicationRoute) {
              android.util.Log.w(TAG, "SCO disconnected during recording=$currentId; preserving partial file")
              stopRecording(null)
              MandarinVoicePrompt.speak(this, "蓝牙麦克风当前不可用，录音已结束", useVoiceCommunication = false)
            }
          }
          ACTION_SET_INPUT_DEVICE -> setInputDevice(intent)
        }
      }.onFailure { error ->
        android.util.Log.e(TAG, "recording command failed action=${intent?.action}", error)
        if (intent?.action == ACTION_START) {
          shouldRecord.set(false)
          runCatching { recorder?.stop() }
          runBlocking { recordingJob?.join() }
          runCatching { recorder?.release() }
          recorder = null
          failRecordingStart("开启录音失败。")
        }
      }
      android.util.Log.i(TAG, "command=${intent?.action} elapsedMs=${android.os.SystemClock.elapsedRealtime() - began}")
      if (recorder == null) stopSelfResult(startId)
    }
    return START_STICKY
  }

  private fun startRecording(intent: Intent?) {
    if (recorder != null) return
    if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
      RecordingRuntime.state.value = RecorderUiState()
      BluetoothRecordingWorkflow.onRecordingStartFailed(intent?.getStringExtra(EXTRA_RECORDING_ID))
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
    // Include route preparation in resource ownership so focus release cannot
    // acknowledge while AudioRecord is still opening the Bluetooth microphone.
    RecordingRuntime.activeInputIsBluetooth = selectedBluetoothInput
    if (selectedBluetoothInput) BluetoothRecordingTelecom.observeBluetoothAudio(this)
    val useTelecomBluetoothControl =
      selectedBluetoothInput && shouldUseTelecomBluetoothControl(selectedInput.type)
    currentInputDeviceId = selectedInput?.id
    currentUseCommunicationRoute = selectedBluetoothInput
    currentUseTelecomControl = useTelecomBluetoothControl
    android.util.Log.i(
      TAG,
      "bluetooth control route mode=${appStore.snapshot.value.bluetoothRecordingControlMode} inputType=${selectedInput?.type}, telecomFallback=$currentUseTelecomControl",
    )
    // Telecom already owns MODE_IN_CALL and SCO. A second AudioManager request
    // changes the mode and forces another channel negotiation.
    val telecomOwnsRoute = currentUseTelecomControl && telecomControlPrewarmed && BluetoothRecordingTelecom.isReady()
    // Media mode needs continuous playback ownership for AVRCP gestures during
    // SCO capture. Keep the existing silent track; only Telecom takes ownership
    // away from MediaSession. Never restart the track just to refresh metadata.
    if (selectedBluetoothInput && currentUseTelecomControl) BluetoothMediaButtonService.suspendMediaRouteForCommunication(this)
    currentInputRoute = if (telecomOwnsRoute) null else AudioDeviceSelection.prepareInputRoute(this, selectedInput)
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
    recorder = nextRecorder // Own the resource before any initialization can fail.
    check(nextRecorder.state == AudioRecord.STATE_INITIALIZED) { "AudioRecord initialization failed" }
    selectedInput?.let { device ->
      val accepted = nextRecorder.setPreferredDevice(device)
      if (!accepted) {
        android.util.Log.w(TAG, "AudioRecord rejected preferred input id=${device.id}, type=${device.type}")
      }
    }

    WavAudio.createEmptyWav(file)
    nextRecorder.startRecording()
    if (selectedBluetoothInput && AudioDeviceSelection.waitForBluetoothAudioRecordRoute(nextRecorder) == null) {
      if (!telecomOwnsRoute) AudioDeviceSelection.reassertInputRoute(this, selectedInput)
      nextRecorder.setPreferredDevice(selectedInput)
      if (AudioDeviceSelection.waitForBluetoothAudioRecordRoute(nextRecorder) == null) {
        runCatching { nextRecorder.stop() }
        nextRecorder.release()
        recorder = null
        failRecordingStart("录音输入未切换到蓝牙麦克风，已停止以避免误用手机麦克风")
        return
      }
    }
    recorder = nextRecorder
    RecordingRuntime.activeInputIsBluetooth = selectedBluetoothInput
    shouldRecord.set(true)
    if (selectedBluetoothInput) {
      if (appStore.snapshot.value.bluetoothVolumeStopEnabled) reserveVolumeHeadroom()
      volumeStopGate.begin(currentId, android.os.SystemClock.elapsedRealtime())
    }
    RecordingRuntime.state.value =
      RecorderUiState(
        status = RecordingStatus.RECORDING,
        activeFileName = name,
        activeRecordingId = currentId,
      )
    recordingJob = serviceScope.launch {
      runCatching { writePcmLoop(nextRecorder, file, minBuffer) }.onFailure { error ->
        android.util.Log.e(TAG, "PCM capture failed; preserving partial recording", error)
        if (shouldRecord.get()) commands.execute {
          stopRecording(null)
          MandarinVoicePrompt.speak(this@RecordingService, "录音已结束")
          stopSelf()
        }
      }
    }
    startForeground(1001, notification("正在录音中"))
    if (currentUseTelecomControl) {
      if (telecomControlPrewarmed) {
        BluetoothRecordingTelecom.recordingStarted(currentId)
      } else {
        BluetoothRecordingTelecom.start(this, recordingId = currentId, requiresSco = selectedInput?.type == android.media.AudioDeviceInfo.TYPE_BLUETOOTH_SCO)
      }
    }
    routingListener = AudioRouting.OnRoutingChangedListener { audio ->
      if (commands.isShutdown) return@OnRoutingChangedListener
      val routed = audio.routedDevice
      android.util.Log.i(TAG, "capture route changed id=${routed?.id} type=${routed?.type} bluetoothExpected=$selectedBluetoothInput")
      if (selectedBluetoothInput && routed != null && !AudioDeviceSelection.isBluetoothInput(routed)) {
        commands.execute {
          if (recorder === nextRecorder && shouldRecord.get()) {
            stopRecording(null)
            MandarinVoicePrompt.speak(this, "蓝牙麦克风当前不可用，录音已结束", useVoiceCommunication = false)
            stopSelf()
          }
        }
      }
    }.also { nextRecorder.addOnRoutingChangedListener(it, Handler(Looper.getMainLooper())) }
    val announceStart = (
      intent?.getBooleanExtra(EXTRA_ANNOUNCE_START_ON_ACTIVE_ROUTE, false) == true ||
      (selectedBluetoothInput && intent?.getBooleanExtra(EXTRA_ANNOUNCE_START_ON_BLUETOOTH_ROUTE, false) == true)
    )
    BluetoothRecordingWorkflow.onRecordingStarted(this, currentId, announceStart, selectedBluetoothInput)
    if (!selectedBluetoothInput) BluetoothMediaButtonService.rearm(this)
  }

  private fun writePcmLoop(active: AudioRecord, file: File, bufferSize: Int) {
    val buffer = ByteArray(bufferSize)
    var lastLevelEmit = 0L
    RandomAccessFile(file, "rw").use { output ->
      output.seek(WavAudio.HEADER_SIZE.toLong())
      while (shouldRecord.get()) {
        val count = active.read(buffer, 0, buffer.size)
        if (count < 0 && shouldRecord.get()) error("AudioRecord read failed: $count")
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
            RecordingRuntime.state.update { it.copy(inputLevel = nextLevel, elapsedMs = elapsed) }
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
  }

  private fun stopRecording(intent: Intent?) {
    val active = recorder ?: return
    volumeStopGate.clear(currentId)
    restoreReservedVolume()
    val keepInputRouteMs = intent?.getLongExtra(EXTRA_KEEP_INPUT_ROUTE_MS, 0L)?.coerceAtLeast(0L) ?: 0L
    val voicePrompt = intent?.getStringExtra(EXTRA_VOICE_PROMPT).orEmpty()
    shouldRecord.set(false)
    routingListener?.let { active.removeOnRoutingChangedListener(it) }
    routingListener = null
    runCatching { active.stop() }
    runBlocking { recordingJob?.join() }
    currentFile?.let { file -> RandomAccessFile(file, "rw").use { WavAudio.updateHeader(it, bytesWritten) } }
    active.release()
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
    // Finalize the file and release the old route before arming upload. Fixed
    // delays could start uploading before the WAV header and local item existed.
    if (voicePrompt.isNotBlank()) MandarinVoicePrompt.speak(this, voicePrompt)
    if (keepInputRouteMs > 0) Thread.sleep(keepInputRouteMs)
    AudioDeviceSelection.restoreInputRoute(this, currentInputRoute)
    currentInputRoute = null
    currentInputDeviceId = null
    currentUseCommunicationRoute = false
    currentUseTelecomControl = false
    BluetoothRecordingTelecom.endFromApp(currentId)
    BluetoothRecordingWorkflow.onRecordingFinalized(this, currentId)
    BluetoothMediaButtonService.rearm(this)
  }

  override fun onDestroy() {
    unregisterReceiver(volumeStopReceiver)
    commands.execute {
      try {
        if (recorder != null) stopRecording(null)
      } finally {
        serviceScope.cancel()
      }
    }
    commands.shutdown()
    super.onDestroy()
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
      BluetoothRecordingTelecom.start(this, recordingId = currentId, requiresSco = nextDevice?.type == android.media.AudioDeviceInfo.TYPE_BLUETOOTH_SCO)
    } else {
      BluetoothRecordingTelecom.endFromApp()
    }
  }

  private fun reassertCurrentInputRoute() {
    if (!currentUseCommunicationRoute || BluetoothRecordingTelecom.isReady()) return
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
    volumeStopGate.clear(currentId)
    restoreReservedVolume()
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
  }

  private fun reserveVolumeHeadroom() {
    runCatching {
      val audio = getSystemService(AUDIO_SERVICE) as AudioManager
      val current = audio.getStreamVolume(AudioManager.STREAM_VOICE_CALL)
      val target = BluetoothVolumeStopGate.withHeadroom(current, audio.getStreamMaxVolume(AudioManager.STREAM_VOICE_CALL))
      if (target != current) {
        originalCallVolume = current
        reservedCallVolume = target
        audio.setStreamVolume(AudioManager.STREAM_VOICE_CALL, target, 0)
        android.util.Log.i(TAG, "reserved volume-up gesture headroom $current->$target recording=$currentId")
      }
    }.onFailure { android.util.Log.w(TAG, "unable to reserve call volume headroom", it) }
  }

  private fun restoreReservedVolume() {
    val original = originalCallVolume
    val reserved = reservedCallVolume
    originalCallVolume = null
    reservedCallVolume = null
    if (original == null || reserved == null) return
    runCatching {
      val audio = getSystemService(AUDIO_SERVICE) as AudioManager
      // Restore only our temporary adjustment; retain any explicit user changes.
      if (audio.getStreamVolume(AudioManager.STREAM_VOICE_CALL) == reserved) {
        audio.setStreamVolume(AudioManager.STREAM_VOICE_CALL, original, 0)
      }
    }.onFailure { android.util.Log.w(TAG, "unable to restore reserved call volume", it) }
  }

  companion object {
    const val ACTION_START = "com.vibecodingjapan.ideavox.record.START"
    const val ACTION_PAUSE = "com.vibecodingjapan.ideavox.record.PAUSE"
    const val ACTION_RESUME = "com.vibecodingjapan.ideavox.record.RESUME"
    const val ACTION_STOP = "com.vibecodingjapan.ideavox.record.STOP"
    private const val ACTION_BLUETOOTH_AUDIO_LOST = "com.vibecodingjapan.ideavox.record.BLUETOOTH_AUDIO_LOST"

    fun onBluetoothAudioLost(context: android.content.Context, recordingId: String?) {
      if (recordingId == null) return
      Handler(Looper.getMainLooper()).postDelayed({
        if (!BluetoothRecordingTelecom.isScoAudioConnected() && RecordingRuntime.activeInputIsBluetooth && RecordingRuntime.state.value.activeRecordingId == recordingId) {
          context.applicationContext.startService(
            Intent(context.applicationContext, RecordingService::class.java)
              .setAction(ACTION_BLUETOOTH_AUDIO_LOST).putExtra(EXTRA_RECORDING_ID, recordingId)
          )
        }
      }, 300L)
    }
    const val ACTION_SET_INPUT_DEVICE = "com.vibecodingjapan.ideavox.record.SET_INPUT_DEVICE"
    const val EXTRA_INPUT_DEVICE_ID = "inputDeviceId"
    const val EXTRA_RECORDING_ID = "recordingId"
    const val EXTRA_EXPECTED_RECORDING_ID = "expectedRecordingId"
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
    private const val TAG = "IdeavoxRecording"
  }
}

object RecordingRuntime {
  val state = MutableStateFlow(RecorderUiState())
  @Volatile var activeInputIsBluetooth: Boolean = false
}
