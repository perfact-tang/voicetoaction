package com.vibecodingjapan.ideavox

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.util.Log
import android.os.SystemClock
import android.os.Build
import androidx.core.content.ContextCompat
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ensureActive

object BluetoothRecordingWorkflow {
  private const val TAG = "IdeavoxBtWorkflow"
  private const val CROSS_CHANNEL_DEDUP_MS = 500L
  private const val STARTING_TIMEOUT_MS = 12_000L
  private val USER_CONTROL_SOURCES = setOf("onDisconnect", "onAbort", "telecom", "volume", "voice", "media")

  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
  private val lock = Any()
  private var phase: BluetoothGesturePhase = BluetoothGesturePhase.Idle
  private var expiryJob: Job? = null
  private var preparationJob: Job? = null
  private var lastUserControlAt = 0L
  /**
   * True when the current cycle was started by the call-only voice-command
   * channel. That path records through direct HFP SCO instead of creating a
   * self-managed Telecom call, so the glasses stay out of call state and keep
   * delivering AT+BVRA on every double-tap.
   */
  @Volatile private var voiceCommandDirectSco = false
  /**
   * True while the current cycle is driven by the call-only voice-command
   * channel. That channel does not depend on A2DP/AVRCP, so the upload
   * confirmation window must not be rejected just because the media route has
   * not recovered after SCO closes.
   */
  @Volatile private var voiceCommandControl = false

  /**
   * The same physical double-tap can arrive through two channels at once: HFP
   * (hang-up) and the call-only voice-command path. Keep only the first one.
   * Sequential steps are separated by the spoken prompt/countdown and are much
   * farther apart than this window.
   */
  private fun acceptUserControl(now: Long, source: String): Boolean = synchronized(lock) {
    if (now - lastUserControlAt in 0 until CROSS_CHANNEL_DEDUP_MS) {
      Log.i(TAG, "ignore duplicate control source=$source deltaMs=${now - lastUserControlAt}")
      return@synchronized false
    }
    lastUserControlAt = now
    true
  }

  fun announceAutomaticUploadSuccess(context: Context) {
    scope.launch {
      // A completed earlier upload must not interrupt a new control countdown
      // or route preparation. Use the route that is active when feedback plays.
      while (synchronized(lock) { phase != BluetoothGesturePhase.Idle }) delay(100L)
      MandarinVoicePrompt.speak(
        context.applicationContext,
        "自动上传已完成",
        useVoiceCommunication =
          RecordingRuntime.activeInputIsBluetooth || BluetoothRecordingTelecom.hasActiveConnection(),
        speechRate = 1.25f,
      )
      Log.i(TAG, "automatic upload success announcement queued")
      // Let the glasses finish the success phrase before closing the kept SCO.
      delay(3_000L)
      releaseUploadControl(context)
    }
  }

  internal fun startDebugBluetoothRecording(context: Context): Boolean {
    if (context.applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE == 0 ||
      RecordingRuntime.state.value.status != RecordingStatus.IDLE || BluetoothTouchDiagnostics.active ||
      AudioDeviceSelection.bluetoothInput(context) == null
    ) return false
    val now = SystemClock.elapsedRealtime()
    val transition = BluetoothGestureStateMachine.onTrigger(
      BluetoothGesturePhase.AwaitingStartConfirmation(now + 5_000L), RecordingStatus.IDLE, null,
      UUID.randomUUID().toString(), now,
    )
    synchronized(lock) { phase = transition.phase }
    execute(context.applicationContext, transition, usesTelecomControl = false)
    return true
  }

  /**
   * Call-only single-double-tap start. The glasses send AT+BVRA once; do not
   * ask for a second confirmation. Say "准备", open the direct SCO route and
   * let RecordingService announce "开始录音" in the glasses.
   */
  fun startVoiceRecording(context: Context): Boolean {
    if (BluetoothTouchDiagnostics.active) return false
    if (RecordingRuntime.state.value.status != RecordingStatus.IDLE) return false
    // The kept-SCO upload window owns the Bluetooth route until it releases.
    if (RecordingRuntime.activeInputIsBluetooth) return false
    val now = SystemClock.elapsedRealtime()
    if (!acceptUserControl(now, "voice")) return false
    val appContext = context.applicationContext
    val recordingId = UUID.randomUUID().toString()
    synchronized(lock) {
      if (phase != BluetoothGesturePhase.Idle) return false
      phase = BluetoothGesturePhase.StartingRecording(recordingId, now + STARTING_TIMEOUT_MS)
      voiceCommandDirectSco = true
      voiceCommandControl = true
    }
    Log.i(TAG, "voice single-tap start recording=$recordingId")
    MandarinVoicePrompt.speak(appContext, "准备", useVoiceCommunication = false, speechRate = 1.4f)
    startAutomatedRecording(appContext, recordingId)
    return true
  }

  private fun isPreparing(recordingId: String): Boolean = synchronized(lock) {
    val current = phase
    current is BluetoothGesturePhase.StartingRecording && current.recordingId == recordingId && !current.stopRequested
  }

  // A call ending is never an upload confirmation or an instruction for a later recording.
  fun handleTelecomEnd(
    context: Context,
    recordingId: String?,
    source: String = "telecom",
  ): BluetoothGestureEffect {
    if (BluetoothTouchDiagnostics.active) return BluetoothGestureEffect.Ignore
    val now = SystemClock.elapsedRealtime()
    if (source in USER_CONTROL_SOURCES && !acceptUserControl(now, source)) return BluetoothGestureEffect.Ignore
    val transition = synchronized(lock) {
      BluetoothGestureStateMachine.onTelecomEnd(
        phase, RecordingRuntime.state.value.status,
        RecordingRuntime.state.value.activeRecordingId, recordingId,
      ).also { phase = it.phase }
    }
    Log.i(TAG, "telecom end recording=$recordingId source=$source phase=${transition.phase} effect=${transition.effect}")
    execute(context.applicationContext, transition, usesTelecomControl = true)
    return transition.effect
  }

  fun handleTrigger(
    context: Context,
    fromTelecom: Boolean = false,
    source: String = if (fromTelecom) "telecom" else "media",
  ): BluetoothGestureEffect {
    if (BluetoothTouchDiagnostics.active) {
      BluetoothTouchDiagnostics.record("WORKFLOW intercepted trigger telecom=$fromTelecom source=$source; no recording/upload action")
      return BluetoothGestureEffect.Ignore
    }
    val appContext = context.applicationContext
    val now = SystemClock.elapsedRealtime()
    if (!acceptUserControl(now, source)) return BluetoothGestureEffect.Ignore
    voiceCommandDirectSco = source == "voice"
    voiceCommandControl = source == "voice"
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
    Log.i(TAG, "trigger at=$now telecom=$usesTelecomControl status=${RecordingRuntime.state.value.status} phase=${transition.phase} effect=${transition.effect}")
    execute(appContext, transition, usesTelecomControl)
    return transition.effect
  }

  /**
   * Second forward swipe during the kept-SCO upload window. Starts the upload
   * without closing the call; the upload result releases the route afterwards.
   */
  fun handleUploadConfirmation(
    context: Context,
    recordingId: String?,
    source: String = "volume",
  ): BluetoothGestureEffect {
    if (BluetoothTouchDiagnostics.active) return BluetoothGestureEffect.Ignore
    val id = recordingId ?: return BluetoothGestureEffect.Ignore
    val now = SystemClock.elapsedRealtime()
    if (!acceptUserControl(now, source)) return BluetoothGestureEffect.Ignore
    val transition = synchronized(lock) {
      val current = phase
      if (current is BluetoothGesturePhase.AwaitingUploadConfirmation && current.recordingId == id) {
        phase = BluetoothGesturePhase.Idle
        BluetoothGestureTransition(BluetoothGesturePhase.Idle, BluetoothGestureEffect.StartAutomaticUpload(id))
      } else {
        BluetoothGestureTransition(current, BluetoothGestureEffect.Ignore)
      }
    }
    Log.i(TAG, "upload confirmation recording=$id source=$source effect=${transition.effect}")
    execute(context.applicationContext, transition, usesTelecomControl = false)
    return transition.effect
  }

  /** Debug-only visibility for the kept-SCO upload window. */
  internal fun awaitingUploadRecordingId(): String? = synchronized(lock) {
    (phase as? BluetoothGesturePhase.AwaitingUploadConfirmation)?.recordingId
  }

  /** Close the kept HFP/SCO route after upload success/failure/timeout. */
  internal fun releaseUploadControl(context: Context) {
    context.applicationContext.startService(
      Intent(context, RecordingService::class.java)
        .setAction(RecordingService.ACTION_RELEASE_UPLOAD_ROUTE),
    )
  }

  fun onRecordingStarted(context: Context, recordingId: String, announceStart: Boolean = false, bluetoothInput: Boolean = false) {
    scope.launch {
      val transition = synchronized(lock) {
        BluetoothGestureStateMachine.onRecordingStarted(phase, recordingId).also { phase = it.phase }
      }
      if (transition.effect is BluetoothGestureEffect.StopAndArmUpload) {
        execute(context, transition, BluetoothRecordingTelecom.hasActiveConnection())
      } else if (announceStart && RecordingRuntime.state.value.activeRecordingId == recordingId && RecordingRuntime.state.value.status == RecordingStatus.RECORDING) {
        MandarinVoicePrompt.speak(context, "开始录音", useVoiceCommunication = bluetoothInput, speechRate = 1.25f)
        Log.i(TAG, "recording started announcement queued recording=$recordingId bluetooth=$bluetoothInput")
        scope.launch {
          delay(10_000L)
          if (
            RecordingRuntime.state.value.activeRecordingId == recordingId &&
              RecordingRuntime.state.value.status == RecordingStatus.RECORDING
          ) {
            MandarinVoicePrompt.speak(context, "现在可以后滑结束", useVoiceCommunication = bluetoothInput, speechRate = 1.25f)
          }
        }
      }
    }
  }

  fun onRecordingStartFailed(recordingId: String?) {
    synchronized(lock) {
      val current = phase
      if (current is BluetoothGesturePhase.StartingRecording && (recordingId == null || current.recordingId == recordingId)) {
        phase = BluetoothGesturePhase.Idle
        expiryJob?.cancel()
        expiryJob = null
        voiceCommandControl = false
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
        preparationJob?.cancel()
        preparationJob = null
        BluetoothRecordingTelecom.endFromApp()
        BluetoothConfirmationFeedback.startRecordingConfirmation(context)
        scheduleExpiry(context, transition.phase, endTelecomOnExpiry = false)
      }
      is BluetoothGestureEffect.StartRecording -> {
        cancelConfirmation()
        startAutomatedRecording(context, effect.recordingId)
      }
      is BluetoothGestureEffect.StopAndArmUpload -> {
        cancelConfirmation()
        stopRecordingAndArmUpload(context, usesTelecomControl)
      }
      is BluetoothGestureEffect.StartAutomaticUpload -> {
        cancelConfirmation()
        BluetoothConfirmationFeedback.playUploadConfirmed(context)
        startAutomaticUpload(context, effect.recordingId)
        voiceCommandControl = false
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
    val directSco = synchronized(lock) { voiceCommandDirectSco.also { voiceCommandDirectSco = false } }
    BluetoothRecordingTelecom.endFromApp()
    preparationJob?.cancel()
    preparationJob = scope.launch(Dispatchers.IO) {
      try {
        val settings = appStore.snapshot.value
        val pinnedDeviceId = settings.selectedRecordingDeviceId
        val pinnedDeviceName = settings.selectedRecordingDeviceName
        // The user's pinned recording device wins for as long as it is still
        // present. A later-connected media headset must not replace it.
        val selectedBluetoothInput =
          AudioDeviceSelection.findBluetoothInput(context, pinnedDeviceId, pinnedDeviceName)
        val preferredBluetoothName = pinnedDeviceName ?: BluetoothMediaRouteState.deviceName()
        val immediatelyAvailableBluetoothInput =
          selectedBluetoothInput
            ?: AudioDeviceSelection.bluetoothInput(
              context = context,
              preferredProductName = preferredBluetoothName,
            )
        val route =
          if (directSco) {
            // The voice-command path must never fall back to the phone
            // microphone; wait for the configured Bluetooth input instead.
            AutomatedRecordingRoute.BLUETOOTH
          } else {
            AutomatedRecordingRouteResolver.resolve(
              bluetoothInputAvailable = immediatelyAvailableBluetoothInput != null,
              bluetoothOutputAvailable =
                AudioDeviceSelection.bluetoothOutput(
                  context = context,
                  preferredProductName = preferredBluetoothName,
                ) != null,
            )
          }
        if (route == AutomatedRecordingRoute.SYSTEM_DEFAULT) {
          coroutineContext.ensureActive()
          if (!isPreparing(recordingId)) return@launch
          startSystemDefaultRecording(context, recordingId)
          return@launch
        }

        val discoveredInput =
          immediatelyAvailableBluetoothInput
            ?: AudioDeviceSelection.waitForBluetoothInput(
              context = context,
              preferredProductName = preferredBluetoothName,
              preferredDeviceId = pinnedDeviceId,
            )
        coroutineContext.ensureActive()
        if (!isPreparing(recordingId)) {
          onRecordingStartFailed(recordingId)
          return@launch
        }
        if (discoveredInput == null) {
          onRecordingStartFailed(recordingId)
          BluetoothMediaButtonService.rearm(context)
          MandarinVoicePrompt.speak(
            context,
            "蓝牙耳机麦克风尚未连接，请稍后再试。",
            useVoiceCommunication = false,
          )
          return@launch
        }

        val telecomPrewarmed =
          !directSco &&
            BluetoothRecordingControlModeResolver.shouldUseTelecom(
              appStore.snapshot.value.bluetoothRecordingControlMode,
              discoveredInput.type,
            )
        if (telecomPrewarmed) {
          BluetoothMediaButtonService.suspendMediaRouteForCommunication(context)
          BluetoothRecordingTelecom.start(
            context,
            allowWhilePreparing = true,
            preferredProductName = discoveredInput.productName?.toString(),
            preferredAddress = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) discoveredInput.address else null,
            recordingId = recordingId,
            requiresSco = discoveredInput.type == android.media.AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
          )
          val telecomDeadline = SystemClock.elapsedRealtime() + TELECOM_READY_TIMEOUT_MS
          while (!BluetoothRecordingTelecom.isReady() && isPreparing(recordingId) && SystemClock.elapsedRealtime() < telecomDeadline) {
            delay(TELECOM_READY_POLL_MS)
          }
          if (!isPreparing(recordingId)) {
            BluetoothRecordingTelecom.endFromApp(recordingId)
            onRecordingStartFailed(recordingId)
            BluetoothMediaButtonService.rearm(context)
            return@launch
          }
          if (!BluetoothRecordingTelecom.isReady()) {
            BluetoothRecordingTelecom.endFromApp(recordingId)
            onRecordingStartFailed(recordingId)
            BluetoothMediaButtonService.rearm(context)
            MandarinVoicePrompt.speak(
              context,
              "蓝牙耳机通话控制尚未准备好，请稍后再试。",
              useVoiceCommunication = false,
            )
            return@launch
          }
        }
        // AudioRecord verifies the actual route; do not wait for a second,
        // often stale availableCommunicationDevices list before requesting it.
        coroutineContext.ensureActive()
        if (!isPreparing(recordingId)) return@launch
        val bluetoothInput =
          AudioDeviceSelection.findBluetoothInput(
            context,
            discoveredInput.id,
            discoveredInput.productName?.toString(),
          ) ?: discoveredInput
        runCatching {
            context.startForegroundService(
              Intent(context, RecordingService::class.java)
                .setAction(RecordingService.ACTION_START)
                .putExtra(RecordingService.EXTRA_RECORDING_ID, recordingId)
                .putExtra(RecordingService.EXTRA_INPUT_DEVICE_ID, bluetoothInput.id)
                .putExtra(RecordingService.EXTRA_REQUIRE_BLUETOOTH_INPUT, true)
                .putExtra(RecordingService.EXTRA_ANNOUNCE_START_ON_BLUETOOTH_ROUTE, true)
                .putExtra(RecordingService.EXTRA_TELECOM_CONTROL_PREWARMED, telecomPrewarmed)
                .putExtra(RecordingService.EXTRA_FORCE_DIRECT_SCO, directSco)
            )
          }
        .onFailure { error ->
          Log.e(TAG, "Unable to start bluetooth recording service", error)
          if (telecomPrewarmed) BluetoothRecordingTelecom.endFromApp(recordingId)
          onRecordingStartFailed(recordingId)
          BluetoothMediaButtonService.rearm(context)
          MandarinVoicePrompt.speak(context, "开启录音失败。", useVoiceCommunication = false)
        }
      } catch (cancelled: CancellationException) {
        BluetoothRecordingTelecom.endFromApp(recordingId)
        throw cancelled
      } catch (error: Exception) {
        Log.e(TAG, "Bluetooth route preparation failed", error)
        BluetoothRecordingTelecom.endFromApp(recordingId)
        onRecordingStartFailed(recordingId)
        BluetoothMediaButtonService.rearm(context)
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
        BluetoothMediaButtonService.rearm(context)
        MandarinVoicePrompt.speak(context, "开启录音失败。", useVoiceCommunication = false)
      }
  }

  private fun stopRecordingAndArmUpload(context: Context, usesTelecomControl: Boolean) {
    context.startService(
      Intent(context, RecordingService::class.java)
        .setAction(RecordingService.ACTION_STOP)
        .putExtra(RecordingService.EXTRA_KEEP_INPUT_ROUTE_MS, 0L)
        .putExtra(RecordingService.EXTRA_FROM_TELECOM, usesTelecomControl)
        .putExtra(RecordingService.EXTRA_KEEP_SCO_FOR_UPLOAD, voiceCommandControl)
    )
  }

  fun onRecordingFinalized(context: Context, recordingId: String, keepScoForUpload: Boolean = false) {
    scope.launch {
      if (synchronized(lock) { (phase as? BluetoothGesturePhase.PreparingUploadConfirmation)?.recordingId != recordingId }) return@launch
      val voiceControl = synchronized(lock) { voiceCommandControl }
      if (!voiceControl && !keepScoForUpload) BluetoothMediaButtonService.rearm(context)
      // The call-only voice-command channel is independent of A2DP/AVRCP, so
      // it must not wait for (or be disabled by) media-route recovery.
      if (!voiceControl && !keepScoForUpload && AudioDeviceSelection.bluetoothOutput(context) != null) {
        val deadline = SystemClock.elapsedRealtime() + 3_000L
        while (!BluetoothMediaRouteState.isBluetoothAudioActive() && SystemClock.elapsedRealtime() < deadline) delay(50L)
        Log.i(TAG, "media recovery recording=$recordingId ready=${BluetoothMediaRouteState.isBluetoothAudioActive()} route=${BluetoothMediaRouteState.description()}")
        if (!BluetoothMediaRouteState.isBluetoothAudioActive()) {
          synchronized(lock) {
            if ((phase as? BluetoothGesturePhase.PreparingUploadConfirmation)?.recordingId == recordingId) phase = BluetoothGesturePhase.Idle
          }
          cancelConfirmation()
          MandarinVoicePrompt.speak(context, "录音已保存，蓝牙控制尚未恢复，请在手机上传。", useVoiceCommunication = false)
          return@launch
        }
      }
      val transition = synchronized(lock) {
        val current = phase
        if (current !is BluetoothGesturePhase.PreparingUploadConfirmation || current.recordingId != recordingId) return@launch
        val next = BluetoothGestureStateMachine.onRecordingFinalized(
          current,
          recordingId,
          SystemClock.elapsedRealtime(),
          uploadWindowMs =
            if (keepScoForUpload) BluetoothGestureStateMachine.VOICE_UPLOAD_CONFIRMATION_WINDOW_MS
            else BluetoothGestureStateMachine.UPLOAD_CONFIRMATION_WINDOW_MS,
        )
        phase = next.phase
        next
      }
      Log.i(TAG, "recording finalized id=$recordingId keepSco=$keepScoForUpload phase=${transition.phase} effect=${transition.effect}")
      if (!keepScoForUpload) BluetoothMediaButtonService.rearm(context)
      if (transition.effect is BluetoothGestureEffect.StartAutomaticUpload) {
        execute(context.applicationContext, transition, usesTelecomControl = false)
      } else {
        if (keepScoForUpload) BluetoothConfirmationFeedback.startUploadSwipeConfirmation(context)
        else BluetoothConfirmationFeedback.startUploadConfirmation(context)
        scheduleExpiry(context, transition.phase, endTelecomOnExpiry = false, releaseUploadOnExpiry = keepScoForUpload)
      }
    }
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
    context: Context,
    expectedPhase: BluetoothGesturePhase,
    endTelecomOnExpiry: Boolean,
    releaseUploadOnExpiry: Boolean = false,
  ) {
    expiryJob?.cancel()
    expiryJob =
      scope.launch {
        val expiresAt =
          when (expectedPhase) {
            is BluetoothGesturePhase.AwaitingStartConfirmation -> expectedPhase.expiresAt
            is BluetoothGesturePhase.AwaitingUploadConfirmation -> expectedPhase.expiresAt
            is BluetoothGesturePhase.PreparingUploadConfirmation -> SystemClock.elapsedRealtime()
            else -> SystemClock.elapsedRealtime()
          }
        delay((expiresAt - SystemClock.elapsedRealtime()).coerceAtLeast(0L))
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
          voiceCommandControl = false
          if (releaseUploadOnExpiry) {
            MandarinVoicePrompt.speak(
              context.applicationContext,
              "已取消上传",
              useVoiceCommunication = RecordingRuntime.activeInputIsBluetooth,
              speechRate = 1.25f,
            )
            delay(1_200L)
            releaseUploadControl(context)
          } else if (endTelecomOnExpiry) {
            BluetoothRecordingTelecom.endFromApp()
          }
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
  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
  @Volatile private var countdownJob: Job? = null

  fun startRecordingConfirmation(context: Context) = startCountdown(
    context, "我在", BluetoothGestureStateMachine.START_CONFIRMATION_WINDOW_MS,
  )

  fun startUploadConfirmation(context: Context) = startCountdown(
    context, "录音已结束", BluetoothGestureStateMachine.UPLOAD_CONFIRMATION_WINDOW_MS,
  )

  private fun startCountdown(context: Context, prompt: String, windowMs: Long) {
    cancel()
    val appContext = context.applicationContext
    val startedAt = SystemClock.elapsedRealtime()
    countdownJob = scope.launch {
      MandarinVoicePrompt.speak(appContext, prompt, useVoiceCommunication = false, speechRate = CONFIRMATION_SPEECH_RATE)
      val seconds = (windowMs / 1000).toInt()
      // Announce N at 0.5 s, N-1 at 1.5 s, ... 1 at N-0.5 s.
      // The confirmation window and feedback use the same monotonic clock.
      for (remaining in seconds downTo 1) {
        val dueAt = startedAt + 500L + (seconds - remaining) * 1000L
        delay((dueAt - SystemClock.elapsedRealtime()).coerceAtLeast(0L))
        // Queue each number instead of flushing: a slightly long prompt such as
        // "录音已结束" must not be cut off by the first countdown digit.
        MandarinVoicePrompt.speak(
          appContext,
          COUNTDOWN_NUMBERS[remaining - 1],
          useVoiceCommunication = false,
          speechRate = COUNTDOWN_SPEECH_RATE,
          flush = false,
        )
      }
    }
  }

  fun playUploadConfirmed(context: Context) {
    cancel()
    MandarinVoicePrompt.speak(
      context,
      "已开启上传",
      useVoiceCommunication = RecordingRuntime.activeInputIsBluetooth,
    )
  }

  /**
   * Spoken in the glasses while the kept SCO route is still active. The first
   * cue is immediate; the second one is delayed until after the HFP volume-sync
   * guard, so the user swipes when the upload gate is actually armed.
   */
  fun startUploadSwipeConfirmation(context: Context) {
    countdownJob?.cancel()
    countdownJob = scope.launch {
      MandarinVoicePrompt.speak(
        context.applicationContext,
        "录音已结束，5秒内再后滑一次上传",
        useVoiceCommunication = true,
        speechRate = 1.25f,
      )
    }
  }

  fun cancel() {
    countdownJob?.cancel()
    countdownJob = null
    MandarinVoicePrompt.stop()
  }

  private const val CONFIRMATION_SPEECH_RATE = 1.75f
  private const val COUNTDOWN_SPEECH_RATE = 1.15f
  private val COUNTDOWN_NUMBERS = listOf("一", "二", "三", "四", "五", "六", "七", "八", "九", "十")
}

private const val TELECOM_READY_TIMEOUT_MS = 3_000L
private const val TELECOM_READY_POLL_MS = 50L

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
