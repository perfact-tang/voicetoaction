package com.vibecodingjapan.ideavox

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BluetoothGestureStateMachineTest {
  @Test fun timedOutPreparationDoesNotDisableSubsequentPressesForever() {
    val next = BluetoothGestureStateMachine.onTrigger(
      BluetoothGesturePhase.StartingRecording("old", 12000), RecordingStatus.IDLE, null, "new", 12001,
    )
    assertEquals(BluetoothGestureEffect.ArmRecordingStart, next.effect)
    assertEquals(BluetoothGesturePhase.AwaitingStartConfirmation(17001 + BluetoothGestureStateMachine.CONFIRMATION_GRACE_MS), next.phase)
    assertEquals(next.phase, BluetoothGestureStateMachine.onRecordingStarted(next.phase, "old").phase)
  }

  @Test fun duplicateHangupCannotConfirmUploadWhileSavingOrAfterSaving() {
    val stop = BluetoothGestureStateMachine.onTelecomEnd(BluetoothGesturePhase.Idle, RecordingStatus.RECORDING, "r1", "r1")
    assertEquals(BluetoothGestureEffect.StopAndArmUpload("r1"), stop.effect)
    val duplicate = BluetoothGestureStateMachine.onTelecomEnd(stop.phase, RecordingStatus.RECORDING, "r1", "r1")
    assertEquals(BluetoothGestureEffect.Ignore, duplicate.effect)
    assertEquals(BluetoothGesturePhase.PreparingUploadConfirmation("r1"), duplicate.phase)
    val saved = BluetoothGestureStateMachine.onRecordingFinalized(duplicate.phase, "r1", 1000)
    assertEquals(
      BluetoothGesturePhase.AwaitingUploadConfirmation("r1", 11000 + BluetoothGestureStateMachine.CONFIRMATION_GRACE_MS),
      saved.phase,
    )
    val late = BluetoothGestureStateMachine.onTelecomEnd(saved.phase, RecordingStatus.IDLE, null, "r1")
    assertEquals(saved.phase, late.phase)
    assertEquals(BluetoothGestureEffect.Ignore, late.effect)
  }

  @Test fun previousCallCannotStopSecondRecordingOrArmNewConfirmation() {
    val recording = BluetoothGestureStateMachine.onTelecomEnd(BluetoothGesturePhase.Idle, RecordingStatus.RECORDING, "r2", "r1")
    assertEquals(BluetoothGestureEffect.Ignore, recording.effect)
    val idle = BluetoothGestureStateMachine.onTelecomEnd(BluetoothGesturePhase.Idle, RecordingStatus.IDLE, null, "r1")
    assertEquals(BluetoothGesturePhase.Idle, idle.phase)
    assertEquals(BluetoothGestureEffect.Ignore, idle.effect)
    val second = BluetoothGestureStateMachine.onTelecomEnd(recording.phase, RecordingStatus.RECORDING, "r2", "r2")
    assertEquals(BluetoothGestureEffect.StopAndArmUpload("r2"), second.effect)
  }

  @Test fun hangupDuringPreparationOnlyTargetsThatRecording() {
    val phase = BluetoothGesturePhase.StartingRecording("r2", 12000)
    assertEquals(phase, BluetoothGestureStateMachine.onTelecomEnd(phase, RecordingStatus.IDLE, null, "r1").phase)
    val queued = BluetoothGestureStateMachine.onTelecomEnd(phase, RecordingStatus.IDLE, null, "r2")
    assertEquals(phase.copy(stopRequested = true), queued.phase)
    assertEquals(BluetoothGestureEffect.StopAndArmUpload("r2"), BluetoothGestureStateMachine.onRecordingStarted(queued.phase, "r2").effect)
  }

  @Test
  fun automatedRecordingUsesSystemDefaultWhenBluetoothIsAbsent() {
    assertEquals(
      AutomatedRecordingRoute.SYSTEM_DEFAULT,
      AutomatedRecordingRouteResolver.resolve(
        bluetoothInputAvailable = false,
        bluetoothOutputAvailable = false,
      ),
    )
  }

  @Test
  fun automatedRecordingKeepsBluetoothFlowWhenInputOrMediaRouteExists() {
    assertEquals(
      AutomatedRecordingRoute.BLUETOOTH,
      AutomatedRecordingRouteResolver.resolve(
        bluetoothInputAvailable = true,
        bluetoothOutputAvailable = false,
      ),
    )
    assertEquals(
      AutomatedRecordingRoute.BLUETOOTH,
      AutomatedRecordingRouteResolver.resolve(
        bluetoothInputAvailable = false,
        bluetoothOutputAvailable = true,
      ),
    )
  }

  @Test
  fun idleFirstPressArmsAndSecondPressStartsWithinFiveSeconds() {
    val first =
      BluetoothGestureStateMachine.onTrigger(
        phase = BluetoothGesturePhase.Idle,
        recordingStatus = RecordingStatus.IDLE,
        activeRecordingId = null,
        nextRecordingId = "r1",
        now = 1_000L,
      )
    assertEquals(BluetoothGestureEffect.ArmRecordingStart, first.effect)

    val second =
      BluetoothGestureStateMachine.onTrigger(
        phase = first.phase,
        recordingStatus = RecordingStatus.IDLE,
        activeRecordingId = null,
        nextRecordingId = "r1",
        now = 5_999L,
      )
    assertEquals(BluetoothGestureEffect.StartRecording("r1"), second.effect)
  }

  @Test
  fun startConfirmationAcceptsALateTapWithinTheGracePeriod() {
    val first =
      BluetoothGestureStateMachine.onTrigger(
        phase = BluetoothGesturePhase.Idle,
        recordingStatus = RecordingStatus.IDLE,
        activeRecordingId = null,
        nextRecordingId = "r1",
        now = 1_000L,
      )
    val late =
      BluetoothGestureStateMachine.onTrigger(
        phase = first.phase,
        recordingStatus = RecordingStatus.IDLE,
        activeRecordingId = null,
        nextRecordingId = "r1",
        now = 7_500L, // 5s window + 1.5s grace
      )
    assertEquals(BluetoothGestureEffect.StartRecording("r1"), late.effect)
  }

  @Test
  fun expiredStartConfirmationRequiresAnotherConfirmationCycle() {
    val expired = BluetoothGesturePhase.AwaitingStartConfirmation(expiresAt = 4_000L)
    val next =
      BluetoothGestureStateMachine.onTrigger(
        phase = expired,
        recordingStatus = RecordingStatus.IDLE,
        activeRecordingId = null,
        nextRecordingId = "r2",
        now = 4_001L,
      )

    assertEquals(BluetoothGestureEffect.ArmRecordingStart, next.effect)
    assertTrue(next.phase is BluetoothGesturePhase.AwaitingStartConfirmation)
  }

  @Test
  fun recordingPressStopsAndSecondPressStartsUploadWithinTenSeconds() {
    val stop =
      BluetoothGestureStateMachine.onTrigger(
        phase = BluetoothGesturePhase.Idle,
        recordingStatus = RecordingStatus.RECORDING,
        activeRecordingId = "active",
        nextRecordingId = "unused",
        now = 10_000L,
    )
    assertEquals(BluetoothGestureEffect.StopAndArmUpload("active"), stop.effect)
    assertTrue(stop.phase is BluetoothGesturePhase.PreparingUploadConfirmation)

    val armed =
      BluetoothGestureStateMachine.armUploadConfirmation(
        phase = stop.phase,
        recordingId = "active",
        now = 15_000L,
      )

    val upload =
      BluetoothGestureStateMachine.onTrigger(
        phase = armed,
        recordingStatus = RecordingStatus.IDLE,
        activeRecordingId = null,
        nextRecordingId = "unused",
        now = 24_999L,
      )
    assertEquals(BluetoothGestureEffect.StartAutomaticUpload("active"), upload.effect)
    assertEquals(BluetoothGesturePhase.Idle, upload.phase)
  }

  @Test
  fun uploadPressIsRememberedUntilFileFinalization() {
    val phase = BluetoothGesturePhase.PreparingUploadConfirmation("active")
    val next =
      BluetoothGestureStateMachine.onTrigger(
        phase = phase,
        recordingStatus = RecordingStatus.IDLE,
        activeRecordingId = null,
        nextRecordingId = "unused",
        now = 20_000L,
      )

    assertEquals(BluetoothGestureEffect.Ignore, next.effect)
    assertEquals(phase.copy(uploadRequested = true), next.phase)
  }

  @Test
  fun uploadConfirmationExpiresAfterTenSeconds() {
    val expired =
      BluetoothGesturePhase.AwaitingUploadConfirmation(
        recordingId = "active",
        expiresAt = 20_000L,
      )
    val next =
      BluetoothGestureStateMachine.onTrigger(
        phase = expired,
        recordingStatus = RecordingStatus.IDLE,
        activeRecordingId = null,
        nextRecordingId = "next",
        now = 20_001L,
      )

    assertEquals(BluetoothGestureEffect.ArmRecordingStart, next.effect)
    assertTrue(next.phase is BluetoothGesturePhase.AwaitingStartConfirmation)
  }

  @Test
  fun startingPhaseRemembersStopUntilRecorderReportsStarted() {
    val phase = BluetoothGesturePhase.StartingRecording("r1", expiresAt = 20_000L)
    val next =
      BluetoothGestureStateMachine.onTrigger(
        phase = phase,
        recordingStatus = RecordingStatus.IDLE,
        activeRecordingId = null,
        nextRecordingId = "r2",
        now = 11_000L,
      )

    assertEquals(BluetoothGestureEffect.Ignore, next.effect)
    assertEquals(phase.copy(stopRequested = true), next.phase)
  }
  @Test fun queuedStopExecutesWhenRecordingStarts() {
    val phase = BluetoothGesturePhase.StartingRecording("r1", 20000, stopRequested = true)
    val next = BluetoothGestureStateMachine.onRecordingStarted(phase, "r1")
    assertEquals(BluetoothGestureEffect.StopAndArmUpload("r1"), next.effect)
    assertEquals(BluetoothGesturePhase.PreparingUploadConfirmation("r1"), next.phase)
  }

  @Test fun queuedUploadExecutesOnlyAfterMatchingFileFinalizes() {
    val phase = BluetoothGesturePhase.PreparingUploadConfirmation("r1", uploadRequested = true)
    assertEquals(phase, BluetoothGestureStateMachine.onRecordingFinalized(phase, "old", 1000).phase)
    val next = BluetoothGestureStateMachine.onRecordingFinalized(phase, "r1", 1000)
    assertEquals(BluetoothGestureEffect.StartAutomaticUpload("r1"), next.effect)
    assertEquals(BluetoothGesturePhase.Idle, next.phase)
    assertEquals(BluetoothGestureEffect.Ignore, BluetoothGestureStateMachine.onRecordingFinalized(next.phase, "r1", 1001).effect)
  }

  @Test fun uploadWindowStartsAtFinalizationAndStaleStartDoesNotEraseIt() {
    val next = BluetoothGestureStateMachine.onRecordingFinalized(BluetoothGesturePhase.PreparingUploadConfirmation("r1"), "r1", 5000)
    assertEquals(
      BluetoothGesturePhase.AwaitingUploadConfirmation("r1", 15000 + BluetoothGestureStateMachine.CONFIRMATION_GRACE_MS),
      next.phase,
    )
    assertEquals(next.phase, BluetoothGestureStateMachine.onRecordingStarted(next.phase, "old").phase)
  }

}
