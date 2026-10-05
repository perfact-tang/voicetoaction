package com.vibecodingjapan.ideavox

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BluetoothGestureStateMachineTest {
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
  fun idleFirstPressArmsAndSecondPressStartsWithinThreeSeconds() {
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
        now = 3_999L,
      )
    assertEquals(BluetoothGestureEffect.StartRecording("r1"), second.effect)
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
  fun uploadPressIsIgnoredUntilBluetoothMediaRouteIsReady() {
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
    assertEquals(phase, next.phase)
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
  fun startingPhaseIgnoresRepeatedPressUntilRecorderReportsStarted() {
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
    assertEquals(phase, next.phase)
  }
}
