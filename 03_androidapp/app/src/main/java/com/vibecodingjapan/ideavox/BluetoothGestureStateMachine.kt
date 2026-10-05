package com.vibecodingjapan.ideavox

sealed interface BluetoothGesturePhase {
  data object Idle : BluetoothGesturePhase

  data class AwaitingStartConfirmation(val expiresAt: Long) : BluetoothGesturePhase

  data class StartingRecording(val recordingId: String, val expiresAt: Long) : BluetoothGesturePhase

  data class PreparingUploadConfirmation(val recordingId: String) : BluetoothGesturePhase

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
  const val START_CONFIRMATION_WINDOW_MS = 3_000L
  const val UPLOAD_CONFIRMATION_WINDOW_MS = 10_000L
  private const val STARTING_TIMEOUT_MS = 12_000L

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
        if (now <= phase.expiresAt && recordingStatus == RecordingStatus.IDLE) {
          return BluetoothGestureTransition(phase, BluetoothGestureEffect.Ignore)
        }
      is BluetoothGesturePhase.PreparingUploadConfirmation ->
        return BluetoothGestureTransition(phase, BluetoothGestureEffect.Ignore)
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
        phase = BluetoothGesturePhase.AwaitingStartConfirmation(now + START_CONFIRMATION_WINDOW_MS),
        effect = BluetoothGestureEffect.ArmRecordingStart,
      )
    }
  }

  fun armUploadConfirmation(
    phase: BluetoothGesturePhase,
    recordingId: String,
    now: Long,
  ): BluetoothGesturePhase =
    if (phase is BluetoothGesturePhase.PreparingUploadConfirmation && phase.recordingId == recordingId) {
      BluetoothGesturePhase.AwaitingUploadConfirmation(
        recordingId = recordingId,
        expiresAt = now + UPLOAD_CONFIRMATION_WINDOW_MS,
      )
    } else {
      phase
    }
}
