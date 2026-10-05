package com.vibecodingjapan.ideavox

import android.media.AudioDeviceInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BluetoothRecordingControlModeResolverTest {
  @Test
  fun automaticModeUsesTelecomFallbackForClassicScoHeadset() {
    assertTrue(
      BluetoothRecordingControlModeResolver.shouldUseTelecom(
        BluetoothRecordingControlMode.AUTO,
        AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
      )
    )
  }

  @Test
  fun automaticModeKeepsMediaButtonsForBleHeadset() {
    assertFalse(
      BluetoothRecordingControlModeResolver.shouldUseTelecom(
        BluetoothRecordingControlMode.AUTO,
        AudioDeviceInfo.TYPE_BLE_HEADSET,
      )
    )
  }

  @Test
  fun explicitModeOverridesDetectedBluetoothProfile() {
    assertEquals(
      BluetoothRecordingControlMode.MEDIA,
      BluetoothRecordingControlModeResolver.effective(
        BluetoothRecordingControlMode.MEDIA,
        AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
      ),
    )
    assertEquals(
      BluetoothRecordingControlMode.TELECOM,
      BluetoothRecordingControlModeResolver.effective(
        BluetoothRecordingControlMode.TELECOM,
        AudioDeviceInfo.TYPE_BLE_HEADSET,
      ),
    )
  }
}
