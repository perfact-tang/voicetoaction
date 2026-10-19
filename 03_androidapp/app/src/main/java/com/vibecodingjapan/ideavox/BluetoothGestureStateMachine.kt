package com.vibecodingjapan.ideavox

sealed interface BluetoothGesturePhase {
  data object Idle : BluetoothGesturePhase

  data class AwaitingStartConfirmation(val expiresAt: Long) : BluetoothGesturePhase

  data class StartingRecording(val recordingId: String, val expiresAt: Long, val stopRequested: Boolean = false) : BluetoothGesturePhase

  data class PreparingUploadConfirmation(val recordingId: String, val uploadRequested: Boolean = false) : BluetoothGesturePhase

  data class AwaitingUploadConfirmation(
    val recordingId: String,
    val expiresAt: Long,
  ) : BluetoothGesturePhase
}

sealed interface BluetoothGestureEffect {
  data object ArmRecordingStart : BluetoothGestureEffect

  data class StartRecording(val recordingId: String) : BluetoothGestureEffect

  data class StopAndArmUpload(val recordingId: String) : BluetoothGestureEffect

  data class StartAutomaticUpload(val recordingId: String) : BluetoothGestureEffect

  data object Ignore : BluetoothGestureEffect
}

data class BluetoothGestureTransition(
  val phase: BluetoothGesturePhase,
  val effect: BluetoothGestureEffect,
)

object BluetoothGestureStateMachine {
  const val START_CONFIRMATION_WINDOW_MS = 5_000L
  const val UPLOAD_CONFIRMATION_WINDOW_MS = 10_000L
  /**
   * The call-only swipe flow keeps SCO while the glasses speak the upload
   * prompt. The total window includes the prompt; roughly five seconds remain
   * for the second backward swipe after the prompt finishes.
   */
  const val VOICE_UPLOAD_CONFIRMATION_WINDOW_MS = 8_000L
  /**
   * Keep accepting the confirmation for a short time after the spoken
   * countdown reaches one. The physical tap and the HFP/voice-command delivery
   * can arrive a few hundred milliseconds after the user starts the gesture.
   */
  const val CONFIRMATION_GRACE_MS = 1_500L
  private const val STARTING_TIMEOUT_MS = 12_000L

  fun onTelecomEnd(
    phase: BluetoothGesturePhase,
    recordingStatus: RecordingStatus,
    activeRecordingId: String?,
    callRecordingId: String?,
  ): BluetoothGestureTransition {
    val ignored = BluetoothGestureTransition(phase, BluetoothGestureEffect.Ignore)
    if (callRecordingId == null) return ignored
    if (phase is BluetoothGesturePhase.PreparingUploadConfirmation || phase is BluetoothGesturePhase.AwaitingUploadConfirmation) return ignored
    if (phase is BluetoothGesturePhase.StartingRecording && phase.recordingId == callRecordingId && recordingStatus == RecordingStatus.IDLE) {
      return BluetoothGestureTransition(phase.copy(stopRequested = true), BluetoothGestureEffect.Ignore)
    }
    if (activeRecordingId != callRecordingId || recordingStatus !in setOf(RecordingStatus.RECORDING, RecordingStatus.PAUSED)) return ignored
    return BluetoothGestureTransition(
      BluetoothGesturePhase.PreparingUploadConfirmation(callRecordingId),
      BluetoothGestureEffect.StopAndArmUpload(callRecordingId),
    )
  }

  fun onTrigger(
    phase: BluetoothGesturePhase,
    recordingStatus: RecordingStatus,
    activeRecordingId: String?,
    nextRecordingId: String,
    now: Long,
  ): BluetoothGestureTransition {
    when (phase) {
      is BluetoothGesturePhase.AwaitingStartConfirmation ->
        if (now <= phase.expiresAt) {
          return BluetoothGestureTransition(
            phase = BluetoothGesturePhase.StartingRecording(nextRecordingId, now + STARTING_TIMEOUT_MS),
            effect = BluetoothGestureEffect.StartRecording(nextRecordingId),
          )
        }
      is BluetoothGesturePhase.AwaitingUploadConfirmation ->
        if (now <= phase.expiresAt) {
          return BluetoothGestureTransition(
            phase = BluetoothGesturePhase.Idle,
            effect = BluetoothGestureEffect.StartAutomaticUpload(phase.recordingId),
          )
        }
      is BluetoothGesturePhase.StartingRecording ->
        if (recordingStatus == RecordingStatus.IDLE) {
          if (now <= phase.expiresAt) {
            return BluetoothGestureTransition(phase.copy(stopRequested = true), BluetoothGestureEffect.Ignore)
          }
          return BluetoothGestureTransition(
            BluetoothGesturePhase.AwaitingStartConfirmation(now + START_CONFIRMATION_WINDOW_MS + CONFIRMATION_GRACE_MS),
            BluetoothGestureEffect.ArmRecordingStart,
          )
        }
      is BluetoothGesturePhase.PreparingUploadConfirmation ->
        return BluetoothGestureTransition(phase.copy(uploadRequested = true), BluetoothGestureEffect.Ignore)
      BluetoothGesturePhase.Idle -> Unit
    }

    return if (recordingStatus == RecordingStatus.RECORDING || recordingStatus == RecordingStatus.PAUSED) {
      val recordingId = activeRecordingId ?: return BluetoothGestureTransition(BluetoothGesturePhase.Idle, BluetoothGestureEffect.Ignore)
      BluetoothGestureTransition(
        phase = BluetoothGesturePhase.PreparingUploadConfirmation(recordingId),
        effect = BluetoothGestureEffect.StopAndArmUpload(recordingId),
      )
    } else {
      BluetoothGestureTransition(
        phase = BluetoothGesturePhase.AwaitingStartConfirmation(now + START_CONFIRMATION_WINDOW_MS + CONFIRMATION_GRACE_MS),
        effect = BluetoothGestureEffect.ArmRecordingStart,
      )
    }
  }

  fun onRecordingStarted(phase: BluetoothGesturePhase, recordingId: String): BluetoothGestureTransition {
    if (phase !is BluetoothGesturePhase.StartingRecording || phase.recordingId != recordingId) {
      return BluetoothGestureTransition(phase, BluetoothGestureEffect.Ignore)
    }
    return if (phase.stopRequested) {
      BluetoothGestureTransition(BluetoothGesturePhase.PreparingUploadConfirmation(recordingId), BluetoothGestureEffect.StopAndArmUpload(recordingId))
    } else {
      BluetoothGestureTransition(BluetoothGesturePhase.Idle, BluetoothGestureEffect.Ignore)
    }
  }

  fun onRecordingFinalized(
    phase: BluetoothGesturePhase,
    recordingId: String,
    now: Long,
    uploadWindowMs: Long = UPLOAD_CONFIRMATION_WINDOW_MS,
  ): BluetoothGestureTransition {
    if (phase !is BluetoothGesturePhase.PreparingUploadConfirmation || phase.recordingId != recordingId) {
      return BluetoothGestureTransition(phase, BluetoothGestureEffect.Ignore)
    }
    return if (phase.uploadRequested) {
      BluetoothGestureTransition(BluetoothGesturePhase.Idle, BluetoothGestureEffect.StartAutomaticUpload(recordingId))
    } else {
      BluetoothGestureTransition(armUploadConfirmation(phase, recordingId, now, uploadWindowMs), BluetoothGestureEffect.Ignore)
    }
  }

  fun armUploadConfirmation(
    phase: BluetoothGesturePhase,
    recordingId: String,
    now: Long,
    uploadWindowMs: Long = UPLOAD_CONFIRMATION_WINDOW_MS,
  ): BluetoothGesturePhase =
    if (phase is BluetoothGesturePhase.PreparingUploadConfirmation && phase.recordingId == recordingId) {
      BluetoothGesturePhase.AwaitingUploadConfirmation(
        recordingId = recordingId,
        expiresAt = now + uploadWindowMs + CONFIRMATION_GRACE_MS,
      )
    } else {
      phase
    }
}
