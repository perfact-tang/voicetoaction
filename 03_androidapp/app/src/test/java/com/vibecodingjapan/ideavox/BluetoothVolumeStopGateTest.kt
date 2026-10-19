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
      volume = BluetoothVolumeStopGate.withHeadroom(volume, 15)
      gate.begin(id, index * 2000L)
      assertTrue(volume < 15)
      val raised = volume + 1
      assertTrue(gate.accept(id, 0, volume, raised, index * 2000L + 1000))
      volume = raised
      gate.clear(id)
    }
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
