package com.vibecodingjapan.ideavox

import android.view.KeyEvent
import org.junit.Assert.*
import org.junit.Test

class BluetoothKeyDeduplicatorTest {
  @Test fun samePhysicalPressAcrossReceiversRunsOnce() {
    val keys = BluetoothKeyDeduplicator()
    assertTrue(keys.accept(1, 85, 100, 100))
    assertFalse(keys.accept(1, 85, 100, 100))
  }
  @Test fun distinctRapidPressesAndOtherDevicesAreRetained() {
    val keys = BluetoothKeyDeduplicator()
    assertTrue(keys.accept(1, 85, 100, 100))
    assertTrue(keys.accept(1, 85, 101, 101))
    assertTrue(keys.accept(2, 85, 101, 101))
  }
  @Test fun timestampFreeAvrcpEventsDoNotDisableSubsequentPresses() {
    val keys = BluetoothKeyDeduplicator()
    assertTrue(keys.accept(-1, 87, 0, 0, receivedAt = 100))
    assertFalse(keys.accept(-1, 87, 0, 0, receivedAt = 110))
    assertTrue(keys.accept(-1, 87, 0, 0, receivedAt = 300))
  }
  @Test fun previousRewindAndStopAreSupportedButVolumeAndNavigationAreNot() {
    assertTrue(KeyEvent.KEYCODE_MEDIA_PREVIOUS in BluetoothMediaKeys.supported)
    assertTrue(KeyEvent.KEYCODE_MEDIA_REWIND in BluetoothMediaKeys.supported)
    assertTrue(KeyEvent.KEYCODE_MEDIA_STOP in BluetoothMediaKeys.supported)
    assertFalse(KeyEvent.KEYCODE_VOLUME_UP in BluetoothMediaKeys.supported)
    assertFalse(KeyEvent.KEYCODE_BACK in BluetoothMediaKeys.supported)
  }
}
