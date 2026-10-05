package com.vibecodingjapan.ideavox

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.util.Log
import androidx.core.content.ContextCompat
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

object BluetoothRecordingWorkflow {
  private const val TAG = "IdeavoxBtWorkflow"

  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
  private val lock = Any()
  private var phase: BluetoothGesturePhase = BluetoothGesturePhase.Idle
  private var expiryJob: Job? = null

  fun handleTrigger(
    context: Context,
    fromTelecom: Boolean = false,
  ): BluetoothGestureEffect {
    val appContext = context.applicationContext
    val now = System.currentTimeMillis()
    val usesTelecomControl = fromTelecom || BluetoothRecordingTelecom.hasActiveConnection()
    val transition =
      synchronized(lock) {
        BluetoothGestureStateMachine.onTrigger(
          phase = phase,
          recordingStatus = RecordingRuntime.state.value.status,
          activeRecordingId = RecordingRuntime.state.value.activeRecordingId,
          nextRecordingId = UUID.randomUUID().toString(),
          now = now,
        ).also { phase = it.phase }
      }
    Log.d(TAG, "trigger phase=${transition.phase} effect=${transition.effect}")
    execute(appContext, transition, usesTelecomControl)
    return transition.effect
  }

  fun onRecordingStarted(recordingId: String) {
    synchronized(lock) {
      val current = phase
      if (current is BluetoothGesturePhase.StartingRecording && current.recordingId == recordingId) {
        phase = BluetoothGesturePhase.Idle
        expiryJob?.cancel()
        expiryJob = null
      }
    }
  }

  fun onRecordingStartFailed(recordingId: String?) {
    synchronized(lock) {
      val current = phase
      if (recordingId == null || current !is BluetoothGesturePhase.StartingRecording || current.recordingId == recordingId) {
        phase = BluetoothGesturePhase.Idle
        expiryJob?.cancel()
        expiryJob = null
      }
    }
  }

  private fun execute(
    context: Context,
    transition: BluetoothGestureTransition,
    usesTelecomControl: Boolean,
  ) {
    when (val effect = transition.effect) {
      BluetoothGestureEffect.ArmRecordingStart -> {
        BluetoothConfirmationFeedback.startRecordingConfirmation(context)
        scheduleExpiry(transition.phase, endTelecomOnExpiry = false)
      }
      is BluetoothGestureEffect.StartRecording -> {
        cancelConfirmation()
        startAutomatedRecording(context, effect.recordingId)
      }
      is BluetoothGestureEffect.StopAndArmUpload -> {
        val usedBluetoothInput = RecordingRuntime.activeInputIsBluetooth
        stopRecordingAndArmUpload(context, usesTelecomControl)
        if (usedBluetoothInput) {
          prepareBluetoothUploadConfirmation(context, effect.recordingId)
        } else {
          prepareSystemUploadConfirmation(context, effect.recordingId)
        }
      }
      is BluetoothGestureEffect.StartAutomaticUpload -> {
        cancelConfirmation()
        BluetoothConfirmationFeedback.playUploadConfirmed(context)
        startAutomaticUpload(context, effect.recordingId)
        if (usesTelecomControl) BluetoothRecordingTelecom.endFromApp()
      }
      BluetoothGestureEffect.Ignore -> Unit
    }
  }

  private fun startAutomatedRecording(context: Context, recordingId: String) {
    if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
      onRecordingStartFailed(recordingId)
      MandarinVoicePrompt.speak(context, "请先开启麦克风权限。", useVoiceCommunication = false)
      return
    }
    BluetoothRecordingTelecom.endFromApp()
    scope.launch {
      val preferredBluetoothName = BluetoothMediaRouteState.deviceName()
      val immediatelyAvailableBluetoothInput =
        AudioDeviceSelection.bluetoothInput(
          context = context,
          preferredProductName = preferredBluetoothName,
        )
      val route =
        AutomatedRecordingRouteResolver.resolve(
          bluetoothInputAvailable = immediatelyAvailableBluetoothInput != null,
          bluetoothOutputAvailable =
            AudioDeviceSelection.bluetoothOutput(
              context = context,
              preferredProductName = preferredBluetoothName,
            ) != null,
        )
      if (route == AutomatedRecordingRoute.SYSTEM_DEFAULT) {
        startSystemDefaultRecording(context, recordingId)
        return@launch
      }

      val discoveredInput =
        immediatelyAvailableBluetoothInput
          ?: AudioDeviceSelection.waitForBluetoothInput(
            context = context,
            preferredProductName = preferredBluetoothName,
          )
      if (discoveredInput == null) {
        onRecordingStartFailed(recordingId)
        MandarinVoicePrompt.speak(
          context,
          "蓝牙耳机麦克风尚未连接，请稍后再试。",
          useVoiceCommunication = false,
        )
        return@launch
      }

      val telecomPrewarmed =
        BluetoothRecordingControlModeResolver.shouldUseTelecom(
          appStore.snapshot.value.bluetoothRecordingControlMode,
          discoveredInput.type,
        )
      if (telecomPrewarmed) {
        BluetoothMediaButtonService.suspendMediaRouteForCommunication(context)
        delay(MEDIA_SUSPEND_BEFORE_TELECOM_MS)
        BluetoothRecordingTelecom.start(
          context,
          allowWhilePreparing = true,
          preferredProductName = discoveredInput.productName?.toString(),
          preferredAddress = discoveredInput.address,
        )
        val telecomDeadline = System.currentTimeMillis() + TELECOM_READY_TIMEOUT_MS
        while (!BluetoothRecordingTelecom.isReady() && System.currentTimeMillis() < telecomDeadline) {
          delay(TELECOM_READY_POLL_MS)
        }
        if (!BluetoothRecordingTelecom.isReady()) {
          BluetoothRecordingTelecom.endFromApp()
          onRecordingStartFailed(recordingId)
          MandarinVoicePrompt.speak(
            context,
            "蓝牙耳机通话控制尚未准备好，请稍后再试。",
            useVoiceCommunication = false,
          )
          return@launch
        }
      }
      val bluetoothInput =
        if (telecomPrewarmed) {
          AudioDeviceSelection.bluetoothInput(context, discoveredInput.productName?.toString()) ?: discoveredInput
        } else {
          AudioDeviceSelection.waitForBluetoothCommunicationReady(
            context = context,
            inputDevice = discoveredInput,
          )
        }
      if (bluetoothInput == null) {
        onRecordingStartFailed(recordingId)
        MandarinVoicePrompt.speak(
          context,
          "蓝牙耳机通话麦克风尚未准备好，请稍后再试。",
          useVoiceCommunication = false,
        )
        return@launch
      }
      appStore.saveSelectedRecordingDevice(bluetoothInput.id)
      runCatching {
          context.startForegroundService(
            Intent(context, RecordingService::class.java)
              .setAction(RecordingService.ACTION_START)
              .putExtra(RecordingService.EXTRA_RECORDING_ID, recordingId)
              .putExtra(RecordingService.EXTRA_INPUT_DEVICE_ID, bluetoothInput.id)
              .putExtra(RecordingService.EXTRA_REQUIRE_BLUETOOTH_INPUT, true)
              .putExtra(RecordingService.EXTRA_ANNOUNCE_START_ON_BLUETOOTH_ROUTE, true)
              .putExtra(RecordingService.EXTRA_TELECOM_CONTROL_PREWARMED, telecomPrewarmed)
          )
        }
      .onFailure { error ->
        Log.e(TAG, "Unable to start bluetooth recording service", error)
        if (telecomPrewarmed) BluetoothRecordingTelecom.endFromApp()
        onRecordingStartFailed(recordingId)
        MandarinVoicePrompt.speak(context, "开启录音失败。", useVoiceCommunication = false)
      }
    }
  }

  private fun startSystemDefaultRecording(context: Context, recordingId: String) {
    Log.i(
      TAG,
      "No bluetooth headset is connected; starting automated recording with the system default microphone and media route",
    )
    runCatching {
        context.startForegroundService(
          Intent(context, RecordingService::class.java)
            .setAction(RecordingService.ACTION_START)
            .putExtra(RecordingService.EXTRA_RECORDING_ID, recordingId)
            .putExtra(RecordingService.EXTRA_USE_SYSTEM_DEFAULT_INPUT, true)
            .putExtra(RecordingService.EXTRA_ANNOUNCE_START_ON_ACTIVE_ROUTE, true)
        )
      }
      .onFailure { error ->
        Log.e(TAG, "Unable to start system-default automated recording", error)
        onRecordingStartFailed(recordingId)
        MandarinVoicePrompt.speak(context, "开启录音失败。", useVoiceCommunication = false)
      }
  }

  private fun stopRecordingAndArmUpload(context: Context, usesTelecomControl: Boolean) {
    context.startService(
      Intent(context, RecordingService::class.java)
        .setAction(RecordingService.ACTION_STOP)
        .putExtra(RecordingService.EXTRA_KEEP_INPUT_ROUTE_MS, 0L)
        .putExtra(RecordingService.EXTRA_FROM_TELECOM, usesTelecomControl)
    )
  }

  private fun prepareBluetoothUploadConfirmation(
    context: Context,
    recordingId: String,
  ) {
    cancelConfirmation()
    scope.launch {
      // Classic HFP reports the first button as Telecom hang-up, not as a media
      // PLAY event. Finish that call, then arm MediaSession immediately. Audio
      // may still be returning to A2DP, but button capture no longer waits for
      // the output route transition.
      delay(TELECOM_RELEASE_GRACE_MS)
      BluetoothRecordingTelecom.endFromApp()
      BluetoothMediaButtonService.rearm(context)
      val armedPhase = armUploadConfirmation(recordingId)
      if (armedPhase !is BluetoothGesturePhase.AwaitingUploadConfirmation) return@launch

      Log.i(
        TAG,
        "Bluetooth upload confirmation armed immediately after SCO hang-up; current route=${BluetoothMediaRouteState.description()}",
      )
      BluetoothConfirmationFeedback.startUploadConfirmation(context)
      scheduleExpiry(armedPhase, endTelecomOnExpiry = false)
    }
  }

  private fun prepareSystemUploadConfirmation(
    context: Context,
    recordingId: String,
  ) {
    cancelConfirmation()
    scope.launch {
      // No SCO/Telecom route exists in this branch. Keep the normal system
      // media route and arm the same upload confirmation without waiting for
      // an HFP -> A2DP transition.
      delay(SYSTEM_RECORDING_STOP_SETTLE_MS)
      BluetoothRecordingTelecom.endFromApp()
      BluetoothMediaButtonService.rearm(context)
      val armedPhase = armUploadConfirmation(recordingId)
      if (armedPhase !is BluetoothGesturePhase.AwaitingUploadConfirmation) return@launch
      Log.i(
        TAG,
        "System media route retained; starting upload confirmation route=${BluetoothMediaRouteState.description()}",
      )
      BluetoothConfirmationFeedback.startUploadConfirmation(context)
      scheduleExpiry(armedPhase, endTelecomOnExpiry = false)
    }
  }

  private fun armUploadConfirmation(recordingId: String): BluetoothGesturePhase =
    synchronized(lock) {
      val next =
        BluetoothGestureStateMachine.armUploadConfirmation(
          phase = phase,
          recordingId = recordingId,
          now = System.currentTimeMillis(),
        )
      phase = next
      next
    }

  private fun startAutomaticUpload(context: Context, recordingId: String) {
    runCatching {
        val settings = appStore.snapshot.value
        context.startForegroundService(
          Intent(context, UploadProcessingService::class.java)
            .setAction(UploadProcessingService.ACTION_PROCESS)
            .putStringArrayListExtra(UploadProcessingService.EXTRA_IDS, arrayListOf(recordingId))
            .putExtra(UploadProcessingService.EXTRA_OUTPUT_FORMAT, settings.selectedUploadOutputFormat.name)
            .putExtra(UploadProcessingService.EXTRA_SPEED, settings.bluetoothDefaultUploadSpeed)
            .putExtra(UploadProcessingService.EXTRA_LANGUAGE, settings.bluetoothDefaultUploadLanguage.name)
            .putExtra(UploadProcessingService.EXTRA_MODE, UploadProcessMode.SEPARATE.name)
            .putExtra(UploadProcessingService.EXTRA_APPLY_BLUETOOTH_DEFAULTS, true)
        )
      }
      .onFailure { error ->
        Log.e(TAG, "Automatic bluetooth upload failed", error)
        MandarinVoicePrompt.speak(
          context,
          "自动上传失败，${error.message ?: "请打开应用查看。"}",
          useVoiceCommunication = false,
        )
      }
  }

  private fun scheduleExpiry(
    expectedPhase: BluetoothGesturePhase,
    endTelecomOnExpiry: Boolean,
  ) {
    expiryJob?.cancel()
    expiryJob =
      scope.launch {
        val expiresAt =
          when (expectedPhase) {
            is BluetoothGesturePhase.AwaitingStartConfirmation -> expectedPhase.expiresAt
            is BluetoothGesturePhase.AwaitingUploadConfirmation -> expectedPhase.expiresAt
            is BluetoothGesturePhase.PreparingUploadConfirmation -> System.currentTimeMillis()
            else -> System.currentTimeMillis()
          }
        delay((expiresAt - System.currentTimeMillis()).coerceAtLeast(0L))
        var expired = false
        synchronized(lock) {
          if (phase == expectedPhase) {
            phase = BluetoothGesturePhase.Idle
            expired = true
          }
        }
        if (expired) {
          // Stop TTS before releasing SCO. Otherwise a final queued countdown
          // number can migrate to the phone speaker as Telecom tears down.
          BluetoothConfirmationFeedback.cancel()
          if (endTelecomOnExpiry) BluetoothRecordingTelecom.endFromApp()
        }
      }
  }

  private fun cancelConfirmation() {
    expiryJob?.cancel()
    expiryJob = null
    BluetoothConfirmationFeedback.cancel()
  }
}

private object BluetoothConfirmationFeedback {
  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
  @Volatile private var countdownJob: Job? = null

  fun startRecordingConfirmation(context: Context) {
    cancel()
    MandarinVoicePrompt.speak(
      context,
      "已监测到操作，3秒内再按一下，开启录音。三，二，一",
      useVoiceCommunication = false,
      speechRate = CONFIRMATION_SPEECH_RATE,
    )
  }

  fun startUploadConfirmation(context: Context) {
    cancel()
    val appContext = context.applicationContext
    countdownJob =
      scope.launch {
        MandarinVoicePrompt.speak(
          appContext,
          "录音已结束，10秒内再按一下，触发自动上传。十",
          useVoiceCommunication = false,
          speechRate = CONFIRMATION_SPEECH_RATE,
        )
        delay(UPLOAD_COUNTDOWN_FIRST_NUMBER_DELAY_MS)
        UPLOAD_COUNTDOWN_NUMBERS.forEachIndexed { index, number ->
          MandarinVoicePrompt.speak(
            appContext,
            number,
            useVoiceCommunication = false,
            speechRate = COUNTDOWN_SPEECH_RATE,
          )
          if (index < UPLOAD_COUNTDOWN_NUMBERS.lastIndex) {
            delay(UPLOAD_COUNTDOWN_INTERVAL_MS)
          }
        }
      }
  }

  fun playUploadConfirmed(context: Context) {
    cancel()
    MandarinVoicePrompt.speak(
      context,
      "已开启上传",
      useVoiceCommunication = false,
    )
  }

  fun cancel() {
    countdownJob?.cancel()
    countdownJob = null
    MandarinVoicePrompt.stop()
  }

  private const val CONFIRMATION_SPEECH_RATE = 1.75f
  private const val COUNTDOWN_SPEECH_RATE = 1.15f
  private const val UPLOAD_COUNTDOWN_FIRST_NUMBER_DELAY_MS = 2_400L
  private const val UPLOAD_COUNTDOWN_INTERVAL_MS = 900L
  private val UPLOAD_COUNTDOWN_NUMBERS = listOf("九", "八", "七", "六", "五", "四", "三", "二", "一")
}

private const val TELECOM_READY_TIMEOUT_MS = 3_000L
private const val TELECOM_READY_POLL_MS = 50L
private const val MEDIA_SUSPEND_BEFORE_TELECOM_MS = 180L
private const val TELECOM_RELEASE_GRACE_MS = 180L
private const val SYSTEM_RECORDING_STOP_SETTLE_MS = 300L

internal enum class AutomatedRecordingRoute {
  BLUETOOTH,
  SYSTEM_DEFAULT,
}

internal object AutomatedRecordingRouteResolver {
  fun resolve(
    bluetoothInputAvailable: Boolean,
    bluetoothOutputAvailable: Boolean,
  ): AutomatedRecordingRoute =
    if (bluetoothInputAvailable || bluetoothOutputAvailable) {
      AutomatedRecordingRoute.BLUETOOTH
    } else {
      AutomatedRecordingRoute.SYSTEM_DEFAULT
    }
}
