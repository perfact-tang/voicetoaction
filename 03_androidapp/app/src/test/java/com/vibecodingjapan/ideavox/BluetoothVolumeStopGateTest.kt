package com.vibecodingjapan.ideavox

import org.junit.Assert.*
import org.junit.Test

class BluetoothVolumeStopGateTest {
  @Test fun realScoAndVoiceCallBroadcastPairStopsExactlyOnce() {
    val gate = BluetoothVolumeStopGate()
    gate.begin("r1", 1000)
    assertFalse(gate.accept("r1", 6, 13, 14, 3000))
    assertTrue(gate.accept("r1", 0, 13, 14, 3010))
    assertFalse(gate.accept("r1", 0, 13, 14, 3015))
    assertFalse(gate.accept("r1", 0, 14, 15, 4000))
  }

  @Test fun routeSynchronizationDecreaseAndClampedVolumeAreNotStopGestures() {
    val gate = BluetoothVolumeStopGate()
    gate.begin("r1", 1000)
    assertFalse(gate.accept("r1", 0, 13, 14, 1100))
    assertFalse(gate.accept("r1", 0, 14, 13, 2000))
    assertFalse(gate.accept("r1", 0, 15, 15, 2100))
    assertFalse(gate.accept("r1", 0, 0, 0, 2200))
    assertTrue(gate.accept("r1", 0, 13, 14, 2300))
  }

  @Test fun twoRecordingCyclesRearmWithoutOldCleanupDisablingTheSecond() {
    val gate = BluetoothVolumeStopGate()
    gate.begin("r1", 1000)
    assertTrue(gate.accept("r1", 0, 13, 14, 2000))
    gate.clear("r1")
    assertFalse(gate.accept("r1", 0, 14, 15, 2100))
    gate.begin("r2", 3000)
    gate.clear("r1")
    assertFalse(gate.accept("r1", 0, 14, 15, 4000))
    assertTrue(gate.accept("r2", 0, 14, 15, 4100))
  }

  @Test fun repeatedRecordingsAtMaximumStillHaveAnUpwardGestureAvailable() {
    val gate = BluetoothVolumeStopGate()
    var volume = 13
    repeat(10) { index ->
      val id = "r$index"
      volume = BluetoothVolumeStopGate.withUpHeadroom(volume, 15)
      gate.begin(id, index * 2000L)
      assertTrue(volume < 15)
      val raised = volume + 1
      assertTrue(gate.accept(id, 0, volume, raised, index * 2000L + 1000))
      volume = raised
      gate.clear(id)
    }
  }

  @Test fun firstTenSecondsIgnoreTheBackwardStopSwipe() {
    val gate = BluetoothVolumeStopGate(minDelayMs = 10_000L, direction = -1)
    gate.begin("r1", 1000)
    // SCO setup / volume synchronization in the first ten seconds must not
    // close the recording.
    assertFalse(gate.accept("r1", 0, 14, 13, 9_000))
    assertTrue(gate.accept("r1", 0, 14, 13, 11_100))
  }

  @Test fun backwardSwipeAcceptsVolumeDownAndIgnoresUpwardHfpSync() {
    val gate = BluetoothVolumeStopGate(direction = -1)
    gate.begin("r1", 1000)
    // The stack syncs upward; that must not match a backward swipe.
    assertFalse(gate.accept("r1", 0, 14, 15, 2_000))
    assertFalse(gate.accept("r1", 0, 14, 14, 2_100))
    assertTrue(gate.accept("r1", 0, 14, 13, 2_200))
    assertFalse(gate.accept("r1", 0, 13, 12, 2_300))
  }

  @Test fun earlyHfpSyncIsIgnoredDuringTheStopGuardWindow() {
    val gate = BluetoothVolumeStopGate(minDelayMs = 5_000L)
    gate.begin("r1", 1000, startVolume = 13)
    // The stack synchronizes to its stored 14 about three seconds after start.
    assertFalse(gate.accept("r1", 0, 13, 14, 4_000))
    // The user's forward swipe is accepted after the guard.
    assertTrue(gate.accept("r1", 0, 13, 14, 6_100))
  }

  @Test fun twoStepHfpSyncIsRejectedWhileOneStepSwipeIsAccepted() {
    val gate = BluetoothVolumeStopGate()
    // Reserve max-2 = 13. The Bluetooth stack later restores 15 in one jump.
    gate.begin("r1", 1000, startVolume = 13)
    assertFalse(gate.accept("r1", 0, 13, 15, 3000))
    assertTrue(gate.accept("r1", 0, 13, 14, 3100))
  }

  @Test fun hfpVolumeSynchronizationJumpIsNotASwipe() {
    val gate = BluetoothVolumeStopGate()
    gate.begin("r1", 1000)
    // The SCO/HFP stack can sync its stored volume in one large jump after
    // AudioOn; this must not look like a one-step temple swipe.
    assertFalse(gate.accept("r1", 0, 10, 15, 5000))
    assertTrue(gate.accept("r1", 0, 10, 11, 5100))
  }

  @Test fun uploadConfirmationOnlyAcceptsTheReservedStartVolumeAfterItsDelay() {
    val gate = BluetoothVolumeStopGate(minDelayMs = 1_200L)
    gate.begin("r1", 1000, startVolume = 14)
    // A trailing broadcast from the stop swipe starts at 15, not the newly
    // reserved 14, so it cannot confirm the upload.
    assertFalse(gate.accept("r1", 0, 15, 16, 1500))
    assertFalse(gate.accept("r1", 0, 14, 15, 1800))
    assertTrue(gate.accept("r1", 0, 14, 15, 2300))
    assertFalse(gate.accept("r1", 0, 14, 15, 2400))
  }

  @Test fun unrelatedStreamAndInvalidExtrasCannotEndRecording() {
    val gate = BluetoothVolumeStopGate()
    gate.begin("r1", 0)
    assertFalse(gate.accept(null, 0, 2, 3, 1000))
    assertFalse(gate.accept("r1", 3, 2, 3, 1000))
    assertFalse(gate.accept("r1", 0, -1, 3, 1000))
    assertFalse(gate.accept("r1", 0, 2, -1, 1000))
    assertTrue(gate.accept("r1", 0, 2, 3, 1100))
  }
}
